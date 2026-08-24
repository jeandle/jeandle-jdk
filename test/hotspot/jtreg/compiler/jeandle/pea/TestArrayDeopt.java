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
 * @summary PEA reconstructs int[4] {7,0,9,0} in a continuing, exactly
 *          deoptimized level-4 frame and preserves every slot
 * @library /test/lib /
 * @modules java.base/jdk.internal.misc
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      -XX:-UseCompressedOops -XX:-UseCompressedClassPointers
 *      compiler.jeandle.pea.TestArrayDeopt
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

import jdk.internal.misc.Unsafe;
import jdk.test.lib.Asserts;

public class TestArrayDeopt {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestArrayDeopt$TestWrapper";

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
            Asserts.assertEquals(report.round0Before().allocationBCIs().size(), 1,
                    "one source int[] allocation");
            PEATestUtils.PEARound firstRound = report.round(0);
            Asserts.assertEquals(firstRound.neverEscapes(), 1);
            Asserts.assertEquals(firstRound.partiallyEscapes(), 0);
            Asserts.assertEquals(firstRound.alwaysEscapes(), 0);
            Asserts.assertEquals(report.finalAfter().allocationBCIs(), List.of(),
                    "NeverEscapes array allocation eliminated");
            Asserts.assertEquals(run.finalIR(target).loweredAllocCount(), 0,
                    "no lowered array allocation remains");

            String callee = PEATestUtils.MethodId.of(requestDeopt).llvmFunctionName();
            PEATestUtils.DeoptBundle bundle =
                    report.finalAfter().deoptBundleAtCall(callee, 0);
            bundle.assertVirtualObjectIds(0);
            PEATestUtils.VirtualObjectDescriptor array = bundle.virtualObject(0);
            Asserts.assertEquals(array.kind(), PEATestUtils.DescriptorKind.ARRAY);
            int base = Unsafe.ARRAY_INT_BASE_OFFSET;
            int scale = Unsafe.ARRAY_INT_INDEX_SCALE;
            Asserts.assertEquals(array.elements().keySet(),
                    Set.of(base, base + scale, base + 2 * scale, base + 3 * scale),
                    "exact int[4] descriptor offsets");
            assertIntElement(array, base, "i32 7");
            assertIntElement(array, base + scale, "i32 0");
            assertIntElement(array, base + 2 * scale, "i32 9");
            assertIntElement(array, base + 3 * scale, "i32 0");
        }
    }

    private static void assertIntElement(
            PEATestUtils.VirtualObjectDescriptor descriptor,
            int offset, String operand) {
        PEATestUtils.VirtualObjectEntry element = descriptor.elements().get(offset);
        Asserts.assertNotNull(element);
        Asserts.assertEquals(element.basicType(), PEATestUtils.DeoptBasicType.INT);
        Asserts.assertEquals(element.value().kind(),
                PEATestUtils.DeoptValueKind.SCALAR);
        Asserts.assertEquals(element.value().operand(), operand);
    }

    public static class TestWrapper {
        private static final Method DEOPT_TARGET = target();

        public static void main(String[] args) throws Exception {
            PEATestUtils.compileConfiguredTargetsAtLevel4();
            int result = test();
            if (result != 0x13570BDF) {
                throw new AssertionError("array result " + result);
            }
            System.out.println("PEA-RESULT:" + Integer.toUnsignedString(result, 16));
        }

        public static int test() {
            int[] array = new int[4];
            array[0] = 7;
            array[2] = 9;

            requestDeopt();

            int slot0 = array[0];
            int slot1 = array[1];
            int slot2 = array[2];
            int slot3 = array[3];
            if (array.length != 4 || slot0 != 7 || slot1 != 0
                    || slot2 != 9 || slot3 != 0) {
                return Integer.MIN_VALUE + 1;
            }
            array[0] = -1;
            array[1] = 2;
            array[2] = -3;
            array[3] = 4;
            if (array[0] != -1 || array[1] != 2
                    || array[2] != -3 || array[3] != 4) {
                return Integer.MIN_VALUE + 2;
            }
            return (slot0 << 28) ^ (slot1 << 20)
                    ^ (slot2 << 12) ^ slot3 ^ 0x63579BDF;
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
