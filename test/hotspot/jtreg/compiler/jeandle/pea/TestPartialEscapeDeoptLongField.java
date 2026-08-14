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
 * @summary PEA reconstructs typed long and double fields for both runtime
 *          branches of a partial escape, then observes their full-width values
 * @library /test/lib /
 * @modules java.base/jdk.internal.misc
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      -XX:-UseCompressedOops -XX:-UseCompressedClassPointers
 *      compiler.jeandle.pea.TestPartialEscapeDeoptLongField
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

import jdk.internal.misc.Unsafe;
import jdk.test.lib.Asserts;

public class TestPartialEscapeDeoptLongField {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestPartialEscapeDeoptLongField$TestWrapper";
    private static final Unsafe UNSAFE = Unsafe.getUnsafe();

    public static void main(String[] args) throws Exception {
        Method partialFalse = TestWrapper.class.getMethod(
                "testPartialFalse", boolean.class, long.class, double.class);
        Method partialTrue = TestWrapper.class.getMethod(
                "testPartialTrue", boolean.class, long.class, double.class);
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
        int xOffset = fieldOffset("x");
        int longOffset = fieldOffset("longValue");
        int doubleOffset = fieldOffset("doubleValue");
        Asserts.assertEquals(point.fields().keySet(),
                Set.of(xOffset, longOffset, doubleOffset),
                target + ": exact int/long/double descriptor");
        assertScalar(point, xOffset, PEATestUtils.DeoptBasicType.INT, "i32 ");
        assertScalar(point, longOffset, PEATestUtils.DeoptBasicType.LONG, "i64 ");
        assertScalar(point, doubleOffset,
                PEATestUtils.DeoptBasicType.DOUBLE, "double ");
        Asserts.assertEquals(point.fields().get(xOffset).value().operand(), "i32 10");
    }

    private static int fieldOffset(String name) throws Exception {
        return Math.toIntExact(UNSAFE.objectFieldOffset(
                TestWrapper.Point.class.getDeclaredField(name)));
    }

    private static void assertScalar(
            PEATestUtils.VirtualObjectDescriptor descriptor,
            int offset, PEATestUtils.DeoptBasicType type, String operandPrefix) {
        PEATestUtils.VirtualObjectEntry field = descriptor.fields().get(offset);
        Asserts.assertNotNull(field);
        Asserts.assertEquals(field.basicType(), type);
        Asserts.assertEquals(field.value().kind(),
                PEATestUtils.DeoptValueKind.SCALAR);
        Asserts.assertTrue(field.value().operand().startsWith(operandPrefix));
    }

    public static class TestWrapper {
        private static final long LONG_VALUE = 0x123456789ABCDEF0L;
        private static final long DOUBLE_BITS = 0x7FF8123456789ABCL;
        private static final long ESCAPE_MARK = 0x6A09E667F3BCC909L;
        private static final Method PARTIAL_FALSE_TARGET =
                target("testPartialFalse");
        private static final Method PARTIAL_TRUE_TARGET =
                target("testPartialTrue");

        private static Method deoptTarget;
        private static Point global;

        public static class Point {
            public int x;
            public long longValue;
            public double doubleValue;
        }

        public static void main(String[] args) throws Exception {
            new Point();
            PEATestUtils.compileConfiguredTargetsAtLevel4();
            double doubleValue = Double.longBitsToDouble(DOUBLE_BITS);

            global = null;
            deoptTarget = PARTIAL_FALSE_TARGET;
            long noEscape =
                    testPartialFalse(false, LONG_VALUE, doubleValue);
            if (global != null) {
                throw new AssertionError("false branch escaped");
            }

            global = null;
            deoptTarget = PARTIAL_TRUE_TARGET;
            long escape =
                    testPartialTrue(true, LONG_VALUE, doubleValue);
            if (global == null || escape != (noEscape ^ ESCAPE_MARK)) {
                throw new AssertionError("true branch did not retain identity");
            }

            long payload = Long.rotateLeft(noEscape, 23) ^ escape;
            System.out.println("PEA-RESULT:" + Long.toUnsignedString(payload, 16));
        }

        public static long testPartialFalse(
                boolean escape, long longValue, double doubleValue) {
            Point point = new Point();
            point.x = 10;
            point.longValue = longValue;
            point.doubleValue = doubleValue;

            requestDeopt();
            if (escape) {
                sink(point);
            }

            if (point.x != 10 || point.longValue != longValue
                    || Double.doubleToRawLongBits(point.doubleValue)
                            != Double.doubleToRawLongBits(doubleValue)
                    || escape != (global == point)) {
                return Long.MIN_VALUE + 1;
            }
            long result = point.longValue
                    ^ Double.doubleToRawLongBits(point.doubleValue);
            point.longValue = 0x0FEDCBA987654321L;
            point.doubleValue =
                    Double.longBitsToDouble(0xFFF123456789ABCDL);
            if (point.longValue != 0x0FEDCBA987654321L
                    || Double.doubleToRawLongBits(point.doubleValue)
                            != 0xFFF123456789ABCDL
                    || escape && global != point) {
                return Long.MIN_VALUE + 2;
            }
            result ^= Long.rotateLeft(point.longValue, 11);
            result ^= Long.rotateRight(
                    Double.doubleToRawLongBits(point.doubleValue), 7);
            return result ^ (global == point ? ESCAPE_MARK : 0L);
        }

        public static long testPartialTrue(
                boolean escape, long longValue, double doubleValue) {
            Point point = new Point();
            point.x = 10;
            point.longValue = longValue;
            point.doubleValue = doubleValue;

            requestDeopt();
            if (escape) {
                sink(point);
            }

            if (point.x != 10 || point.longValue != longValue
                    || Double.doubleToRawLongBits(point.doubleValue)
                            != Double.doubleToRawLongBits(doubleValue)
                    || escape != (global == point)) {
                return Long.MIN_VALUE + 3;
            }
            long result = point.longValue
                    ^ Double.doubleToRawLongBits(point.doubleValue);
            point.longValue = 0x0FEDCBA987654321L;
            point.doubleValue =
                    Double.longBitsToDouble(0xFFF123456789ABCDL);
            if (point.longValue != 0x0FEDCBA987654321L
                    || Double.doubleToRawLongBits(point.doubleValue)
                            != 0xFFF123456789ABCDL
                    || escape && global != point) {
                return Long.MIN_VALUE + 4;
            }
            result ^= Long.rotateLeft(point.longValue, 11);
            result ^= Long.rotateRight(
                    Double.doubleToRawLongBits(point.doubleValue), 7);
            return result ^ (global == point ? ESCAPE_MARK : 0L);
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
                return TestWrapper.class.getMethod(
                        name, boolean.class, long.class, double.class);
            } catch (ReflectiveOperationException failure) {
                throw new ExceptionInInitializerError(failure);
            }
        }
    }
}
