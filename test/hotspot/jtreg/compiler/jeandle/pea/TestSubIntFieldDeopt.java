/*
 * Copyright (c) 2026, the Jeandle-JDK Authors. All Rights Reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  See the GNU General Public
 * License version 2 for more details.
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
 * @summary PEA widens non-constant sub-int (byte/char/short) virtual-object
 *          descriptor field values to i32 so the HotSpot stackmap parser
 *          reads an int-width location; a raw sub-int value would land in a
 *          sub-int stackmap location and be silently reconstructed from
 *          unrelated bytes on deopt.
 * @library /test/lib /
 * @modules java.base/jdk.internal.misc
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      -XX:-UseCompressedOops -XX:-UseCompressedClassPointers
 *      compiler.jeandle.pea.TestSubIntFieldDeopt
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;
import java.util.List;

import jdk.internal.misc.Unsafe;
import jdk.test.lib.Asserts;

public class TestSubIntFieldDeopt {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestSubIntFieldDeopt$TestWrapper";
    private static final Unsafe UNSAFE = Unsafe.getUnsafe();

    public static void main(String[] args) throws Exception {
        Method target = TestWrapper.class.getMethod("test", byte.class, char.class,
                                                    short.class, int.class, boolean.class);
        Method requestDeopt = TestWrapper.class.getDeclaredMethod("requestDeopt");

        PEATestUtils.behaviorRun(WRAPPER, target)
                .dontinline(requestDeopt)
                .runPEAOnOffEquivalent();

        try (PEATestUtils.RunResult run = PEATestUtils.shapeRun(WRAPPER, target)
                .dontinline(requestDeopt)
                .run()) {
            PEATestUtils.PEAReport report = run.report(target);
            PEATestUtils.PEARound firstRound = report.round(0);
            Asserts.assertEquals(firstRound.neverEscapes(), 1,
                    "the SubIntFields instance is scalar-replaced");
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

            // Every sub-int field entry keeps its T_INT wire encoding, and
            // every non-constant value is a widened i32 operand (the
            // pea.deopt.widen zext), never a raw i8/i16/i1.
            assertWidenedIntField(point, "b");
            assertWidenedIntField(point, "c");
            assertWidenedIntField(point, "s");
            assertWidenedIntField(point, "z");
            PEATestUtils.VirtualObjectEntry intField = point.fields().get(
                    Math.toIntExact(UNSAFE.objectFieldOffset(
                            TestWrapper.SubIntFields.class.getDeclaredField("i"))));
            Asserts.assertNotNull(intField);
            Asserts.assertEquals(intField.basicType(), PEATestUtils.DeoptBasicType.INT);
        }
    }

    private static void assertWidenedIntField(
            PEATestUtils.VirtualObjectDescriptor descriptor, String fieldName)
            throws Exception {
        int offset = Math.toIntExact(UNSAFE.objectFieldOffset(
                TestWrapper.SubIntFields.class.getDeclaredField(fieldName)));
        PEATestUtils.VirtualObjectEntry field = descriptor.fields().get(offset);
        Asserts.assertNotNull(field, "descriptor must carry field " + fieldName);
        Asserts.assertEquals(field.basicType(), PEATestUtils.DeoptBasicType.INT,
                fieldName + " keeps the T_INT wire encoding");
        Asserts.assertEquals(field.value().kind(), PEATestUtils.DeoptValueKind.SCALAR);
        String operand = field.value().operand();
        Asserts.assertTrue(operand.startsWith("i32 "),
                fieldName + " value is widened to i32, got: " + operand);
    }

    public static class TestWrapper {
        private static final Method DEOPT_TARGET = target();

        public static class SubIntFields {
            public byte b;
            public char c;
            public short s;
            public boolean z;
            public int i;
        }

        public static void main(String[] args) throws Exception {
            new SubIntFields();
            PEATestUtils.compileConfiguredTargetsAtLevel4();
            int result = test((byte) -7, '￼', (short) -1234, 424242, true);
            if (result != 3417) {
                throw new AssertionError("sub-int field deopt result " + result);
            }
            System.out.println("PEA-RESULT:" + result);
        }

        public static int test(byte b, char c, short s, int i, boolean z) {
            SubIntFields o = new SubIntFields();
            o.b = b;
            o.c = c;
            o.s = s;
            o.z = z;
            o.i = i;

            requestDeopt();

            if (o.b != b || o.c != c || o.s != s || o.z != z || o.i != i) {
                return Integer.MIN_VALUE + 1;
            }
            o.b = 100;
            o.c = 'A';
            o.s = 321;
            o.z = false;
            o.i = 31;
            if (o.b != 100 || o.c != 'A' || o.s != 321 || o.z != false || o.i != 31) {
                return Integer.MIN_VALUE + 2;
            }
            return o.b * 30 + o.c + o.s + o.i - (o.z ? 1 : 0);
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
                return TestWrapper.class.getMethod("test", byte.class, char.class,
                                                   short.class, int.class, boolean.class);
            } catch (ReflectiveOperationException failure) {
                throw new ExceptionInInitializerError(failure);
            }
        }
    }
}
