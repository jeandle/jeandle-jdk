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
 * @summary PEA reconstructs a never-escaping instance in a continuing,
 *          exactly deoptimized level-4 frame
 * @library /test/lib /
 * @modules java.base/jdk.internal.misc
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      -XX:-UseCompressedOops -XX:-UseCompressedClassPointers
 *      compiler.jeandle.pea.TestNeverEscapeDeopt
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

import jdk.internal.misc.Unsafe;
import jdk.test.lib.Asserts;

public class TestNeverEscapeDeopt {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestNeverEscapeDeopt$TestWrapper";
    private static final Unsafe UNSAFE = Unsafe.getUnsafe();

    public static void main(String[] args) throws Exception {
        Method target = TestWrapper.class.getMethod("test");
        Method requestDeopt = TestWrapper.class.getDeclaredMethod("requestDeopt");

        PEATestUtils.behaviorRun(WRAPPER, target)
                .dontinline(requestDeopt)
                .runPEAOnOffEquivalent();

        try (PEATestUtils.RunResult run = PEATestUtils.shapeRun(WRAPPER, target)
                .dontinline(requestDeopt)
                .run()) {
            PEATestUtils.PEAReport report = run.report(target);
            List<Integer> sourceBCIs = report.round0Before().allocationBCIs();
            Asserts.assertEquals(sourceBCIs.size(), 1,
                    "one source Point allocation");
            PEATestUtils.PEARound firstRound = report.round(0);
            Asserts.assertEquals(firstRound.neverEscapes(), 1);
            Asserts.assertEquals(firstRound.partiallyEscapes(), 0);
            Asserts.assertEquals(firstRound.alwaysEscapes(), 0);
            Asserts.assertEquals(report.finalAfter().allocationBCIs(), List.of(),
                    "NeverEscapes OrigAlloc eliminated");
            Asserts.assertEquals(run.finalIR(target).loweredAllocCount(), 0,
                    "no lowered allocation remains");

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
                    "exact Point descriptor fields");
            assertIntField(point, xOffset, "i32 10");
            assertIntField(point, yOffset, "i32 20");
        }
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
        private static final Method DEOPT_TARGET = target();

        public static class Point {
            public int x;
            public int y;
        }

        public static void main(String[] args) throws Exception {
            new Point();
            PEATestUtils.compileConfiguredTargetsAtLevel4();
            int result = test();
            if (result != 3093) {
                throw new AssertionError("never-escape result " + result);
            }
            System.out.println("PEA-RESULT:" + result);
        }

        public static int test() {
            Point point = new Point();
            point.x = 10;
            point.y = 20;

            requestDeopt();

            if (point.x != 10 || point.y != 20) {
                return Integer.MIN_VALUE + 1;
            }
            point.x = 31;
            point.y = -7;
            if (point.x != 31 || point.y != -7) {
                return Integer.MIN_VALUE + 2;
            }
            return point.x * 100 + point.y;
        }

        private static void requestDeopt() {
            PEATestUtils.ActiveFrameDeoptEvidence evidence =
                    PEATestUtils.deoptimizeActiveFrame(DEOPT_TARGET, 2);
            if (!evidence.frameDeoptimized()
                    || evidence.compilationLevel() != 4
                    || evidence.markedNMethods() != 1) {
                throw new AssertionError("exact active-frame deopt evidence");
            }
        }

        private static Method target() {
            try {
                return TestWrapper.class.getMethod("test");
            } catch (ReflectiveOperationException failure) {
                throw new ExceptionInInitializerError(failure);
            }
        }
    }
}
