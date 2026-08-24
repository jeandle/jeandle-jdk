/*
 * Copyright (c) 2026, the Jeandle-JDK Authors. All Rights Reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 */

/*
 * @test
 * @summary PEA reconstructs a partial-escape instance before either runtime
 *          branch and preserves its OrigAlloc identity on the escape branch
 * @library /test/lib /
 * @modules java.base/jdk.internal.misc
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      -XX:-UseCompressedOops -XX:-UseCompressedClassPointers
 *      compiler.jeandle.pea.TestPartialEscapeDeopt
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

import jdk.internal.misc.Unsafe;
import jdk.test.lib.Asserts;

public class TestPartialEscapeDeopt {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestPartialEscapeDeopt$TestWrapper";
    private static final Unsafe UNSAFE = Unsafe.getUnsafe();

    public static void main(String[] args) throws Exception {
        Method partialFalse =
                TestWrapper.class.getMethod("testPartialFalse", boolean.class);
        Method partialTrue =
                TestWrapper.class.getMethod("testPartialTrue", boolean.class);
        Method requestDeopt = TestWrapper.class.getDeclaredMethod("requestDeopt");
        Method sink = TestWrapper.class.getDeclaredMethod(
                "sink", TestWrapper.Point.class);
        Method[] targets = {partialFalse, partialTrue};

        runBuilder(false, targets, requestDeopt, sink)
                .runPEAOnOffEquivalent();

        try (PEATestUtils.RunResult run =
                runBuilder(true, targets, requestDeopt, sink).run()) {
            assertPartialShape(run, partialFalse, requestDeopt);
            assertPartialShape(run, partialTrue, requestDeopt);
        }
    }

    private static PEATestUtils.RunBuilder runBuilder(
            boolean shape, Method[] targets, Method requestDeopt, Method sink) {
        PEATestUtils.RunBuilder builder = shape
                ? PEATestUtils.shapeRun(WRAPPER, targets)
                : PEATestUtils.behaviorRun(WRAPPER, targets);
        return builder.dontinline(requestDeopt).dontinline(sink);
    }

    private static void assertPartialShape(
            PEATestUtils.RunResult run, Method target, Method requestDeopt)
            throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        report.assertFinalTransformIdle();
        List<Integer> sourceBCIs = report.round0Before().allocationBCIs();
        Asserts.assertEquals(sourceBCIs.size(), 1,
                target + ": one source Point allocation");
        PEATestUtils.PEARound firstRound = report.round(0);
        Asserts.assertEquals(firstRound.neverEscapes(), 0);
        Asserts.assertEquals(firstRound.partiallyEscapes(), 1);
        Asserts.assertEquals(firstRound.alwaysEscapes(), 0);
        Asserts.assertEquals(report.finalAfter().allocationBCIs(), sourceBCIs,
                target + ": exact source OrigAlloc retained");
        Asserts.assertEquals(report.finalAfter().peaAllocCount(), 1);
        Asserts.assertEquals(run.finalIR(target).loweredAllocCount(), 1);

        String callee = PEATestUtils.MethodId.of(requestDeopt).llvmFunctionName();
        PEATestUtils.DeoptBundle bundle =
                report.finalAfter().deoptBundleAtCall(callee, 0);
        bundle.assertVirtualObjectIds(0);
        PEATestUtils.VirtualObjectDescriptor point = bundle.virtualObject(0);
        Asserts.assertEquals(point.kind(), PEATestUtils.DescriptorKind.INSTANCE);
        int xOffset = Math.toIntExact(UNSAFE.objectFieldOffset(
                TestWrapper.Point.class.getDeclaredField("x")));
        int yOffset = Math.toIntExact(UNSAFE.objectFieldOffset(
                TestWrapper.Point.class.getDeclaredField("y")));
        Asserts.assertEquals(point.fields().keySet(), Set.of(xOffset, yOffset),
                target + ": exact Point descriptor");
        assertIntField(point, xOffset, "i32 10");
        assertIntField(point, yOffset, "i32 20");
    }

    private static void assertIntField(
            PEATestUtils.VirtualObjectDescriptor descriptor,
            int offset, String operand) {
        PEATestUtils.VirtualObjectEntry field = descriptor.fields().get(offset);
        Asserts.assertNotNull(field);
        Asserts.assertEquals(field.basicType(), PEATestUtils.DeoptBasicType.INT);
        Asserts.assertEquals(field.value().kind(),
                PEATestUtils.DeoptValueKind.SCALAR);
        Asserts.assertEquals(field.value().operand(), operand);
    }

    public static class TestWrapper {
        private static final long ESCAPE_MARK = 0x6A09E667F3BCC909L;
        private static final Method PARTIAL_FALSE_TARGET =
                target("testPartialFalse");
        private static final Method PARTIAL_TRUE_TARGET =
                target("testPartialTrue");

        private static Method deoptTarget;
        private static Point global;

        public static class Point {
            public int x;
            public int y;
        }

        public static void main(String[] args) throws Exception {
            new Point();
            PEATestUtils.compileConfiguredTargetsAtLevel4();

            global = null;
            deoptTarget = PARTIAL_FALSE_TARGET;
            long noEscape = testPartialFalse(false);
            if (global != null) {
                throw new AssertionError("false branch escaped");
            }

            global = null;
            deoptTarget = PARTIAL_TRUE_TARGET;
            long escape = testPartialTrue(true);
            if (global == null || escape != (noEscape ^ ESCAPE_MARK)) {
                throw new AssertionError("true branch did not retain identity");
            }

            long payload = Long.rotateLeft(noEscape, 19) ^ escape;
            System.out.println("PEA-RESULT:" + Long.toUnsignedString(payload, 16));
        }

        public static long testPartialFalse(boolean escape) {
            Point point = new Point();
            point.x = 10;
            point.y = 20;

            requestDeopt();
            if (escape) {
                sink(point);
            }

            if (point.x != 10 || point.y != 20
                    || escape != (global == point)) {
                return Long.MIN_VALUE + 1;
            }
            point.x = 31;
            point.y = -7;
            if (point.x != 31 || point.y != -7
                    || escape && global != point) {
                return Long.MIN_VALUE + 2;
            }
            return point.x * 100L + point.y
                    ^ (global == point ? ESCAPE_MARK : 0L);
        }

        public static long testPartialTrue(boolean escape) {
            Point point = new Point();
            point.x = 10;
            point.y = 20;

            requestDeopt();
            if (escape) {
                sink(point);
            }

            if (point.x != 10 || point.y != 20
                    || escape != (global == point)) {
                return Long.MIN_VALUE + 3;
            }
            point.x = 31;
            point.y = -7;
            if (point.x != 31 || point.y != -7
                    || escape && global != point) {
                return Long.MIN_VALUE + 4;
            }
            return point.x * 100L + point.y
                    ^ (global == point ? ESCAPE_MARK : 0L);
        }

        private static void requestDeopt() {
            PEATestUtils.ActiveFrameDeoptEvidence evidence =
                    PEATestUtils.deoptimizeActiveFrame(deoptTarget, 2);
            if (!evidence.frameDeoptimized()
                    || evidence.compilationLevel() != 4
                    || evidence.markedNMethods() != 1) {
                throw new AssertionError("exact active-frame deopt evidence");
            }
        }

        private static void sink(Point point) {
            global = point;
        }

        private static Method target(String name) {
            try {
                return TestWrapper.class.getMethod(name, boolean.class);
            } catch (ReflectiveOperationException failure) {
                throw new ExceptionInInitializerError(failure);
            }
        }
    }
}
