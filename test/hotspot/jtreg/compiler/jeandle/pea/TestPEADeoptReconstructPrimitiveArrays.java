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
 * @summary PEA reconstructs every primitive array kind, length, default, and
 *          boundary value at exact active-frame deoptimization
 * @library /test/lib /
 * @modules java.base/jdk.internal.misc
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      -XX:-UseCompressedOops -XX:-UseCompressedClassPointers
 *      compiler.jeandle.pea.TestPEADeoptReconstructPrimitiveArrays
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jdk.internal.misc.Unsafe;
import jdk.test.lib.Asserts;

public class TestPEADeoptReconstructPrimitiveArrays {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestPEADeoptReconstructPrimitiveArrays$TestWrapper";

    public static void main(String[] args) throws Exception {
        Method bool = TestWrapper.class.getMethod("testBoolean");
        Method bytes = TestWrapper.class.getMethod("testByte");
        Method shorts = TestWrapper.class.getMethod("testShort");
        Method chars = TestWrapper.class.getMethod("testChar");
        Method ints = TestWrapper.class.getMethod("testInt");
        Method longs = TestWrapper.class.getMethod("testLong");
        Method floats = TestWrapper.class.getMethod("testFloat");
        Method doubles = TestWrapper.class.getMethod("testDouble");
        Method requestDeopt = TestWrapper.class.getDeclaredMethod("requestDeopt");
        Method[] targets = {bool, bytes, shorts, chars, ints, longs, floats, doubles};

        PEATestUtils.behaviorRun(WRAPPER, targets)
                .dontinline(requestDeopt)
                .runPEAOnOffEquivalent();

        try (PEATestUtils.RunResult run = PEATestUtils.shapeRun(WRAPPER, targets)
                .dontinline(requestDeopt)
                .run()) {
            assertArrayShape(run, bool, requestDeopt,
                    Unsafe.ARRAY_BOOLEAN_BASE_OFFSET,
                    Unsafe.ARRAY_BOOLEAN_INDEX_SCALE,
                    PEATestUtils.DeoptBasicType.INT, null,
                    List.of("boolean false", "boolean true", "boolean false",
                            "boolean false", "boolean true", "boolean false"));
            assertArrayShape(run, bytes, requestDeopt,
                    Unsafe.ARRAY_BYTE_BASE_OFFSET,
                    Unsafe.ARRAY_BYTE_INDEX_SCALE,
                    PEATestUtils.DeoptBasicType.INT, "i8 ",
                    List.of("i8 0", "i8 -101", "i8 0", "i8 0",
                            "i8 37", "i8 99"));
            assertArrayShape(run, shorts, requestDeopt,
                    Unsafe.ARRAY_SHORT_BASE_OFFSET,
                    Unsafe.ARRAY_SHORT_INDEX_SCALE,
                    PEATestUtils.DeoptBasicType.INT, "i16 ",
                    List.of("i16 0", "i16 -22222", "i16 0", "i16 0",
                            "i16 1234", "i16 30000"));
            assertArrayShape(run, chars, requestDeopt,
                    Unsafe.ARRAY_CHAR_BASE_OFFSET,
                    Unsafe.ARRAY_CHAR_INDEX_SCALE,
                    PEATestUtils.DeoptBasicType.INT, "i16 ",
                    List.of("i16 0", "i16 1", "i16 0", "i16 0",
                            "i16 23100", "i16 -2"));
            assertArrayShape(run, ints, requestDeopt,
                    Unsafe.ARRAY_INT_BASE_OFFSET,
                    Unsafe.ARRAY_INT_INDEX_SCALE,
                    PEATestUtils.DeoptBasicType.INT, "i32 ",
                    List.of("i32 0", "i32 -2147483648", "i32 0", "i32 0",
                            "i32 324508639", "i32 2147483647"));
            assertArrayShape(run, longs, requestDeopt,
                    Unsafe.ARRAY_LONG_BASE_OFFSET,
                    Unsafe.ARRAY_LONG_INDEX_SCALE,
                    PEATestUtils.DeoptBasicType.LONG, "i64 ",
                    List.of("i64 0", "i64 -9000000001", "i64 0", "i64 0",
                            "i64 1234567890123", "i64 9223372036854775807"));
            assertArrayShape(run, floats, requestDeopt,
                    Unsafe.ARRAY_FLOAT_BASE_OFFSET,
                    Unsafe.ARRAY_FLOAT_INDEX_SCALE,
                    PEATestUtils.DeoptBasicType.FLOAT, "float ", null);
            assertArrayShape(run, doubles, requestDeopt,
                    Unsafe.ARRAY_DOUBLE_BASE_OFFSET,
                    Unsafe.ARRAY_DOUBLE_INDEX_SCALE,
                    PEATestUtils.DeoptBasicType.DOUBLE, "double ", null);
        }
    }

    private static void assertArrayShape(
            PEATestUtils.RunResult run, Method target, Method requestDeopt,
            int baseOffset, int indexScale, PEATestUtils.DeoptBasicType type,
            String operandPrefix, List<String> exactOperands) throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();
        List<Integer> sourceBCIs = before.allocationBCIs();

        Asserts.assertEquals(sourceBCIs.size(), 3,
                target + ": zero, one, and many source arrays");
        Asserts.assertEquals(new HashSet<>(sourceBCIs).size(), 3,
                target + ": distinct array allocation BCIs");
        PEATestUtils.PEARound firstRound = report.round(0);
        Asserts.assertEquals(firstRound.neverEscapes(), 3,
                target + ": every array never escapes");
        Asserts.assertEquals(firstRound.partiallyEscapes(), 0,
                target + ": no array partially escapes");
        Asserts.assertEquals(firstRound.alwaysEscapes(), 0,
                target + ": no array always escapes");
        Asserts.assertEquals(after.allocationBCIs(), List.of(),
                target + ": all array allocations eliminated");
        Asserts.assertEquals(after.peaAllocCount(), 0,
                target + ": no PEA array allocation remains");
        Asserts.assertEquals(run.finalIR(target).loweredAllocCount(), 0,
                target + ": no lowered array allocation remains");

        String callee = PEATestUtils.MethodId.of(requestDeopt).llvmFunctionName();
        PEATestUtils.DeoptBundle bundle = after.deoptBundleAtCall(callee, 0);
        bundle.assertVirtualObjectIds(0, 1, 2);
        PEATestUtils.VirtualObjectDescriptor empty = bundle.virtualObject(0);
        PEATestUtils.VirtualObjectDescriptor one = bundle.virtualObject(1);
        PEATestUtils.VirtualObjectDescriptor many = bundle.virtualObject(2);

        Asserts.assertEquals(empty.kind(), PEATestUtils.DescriptorKind.ARRAY);
        Asserts.assertEquals(one.kind(), PEATestUtils.DescriptorKind.ARRAY);
        Asserts.assertEquals(many.kind(), PEATestUtils.DescriptorKind.ARRAY);
        Asserts.assertEquals(empty.klassOperand(), one.klassOperand(),
                target + ": same primitive array klass");
        Asserts.assertEquals(one.klassOperand(), many.klassOperand(),
                target + ": same primitive array klass");
        Asserts.assertEquals(empty.elements().keySet(), Set.of(),
                target + ": length-zero descriptor");
        Asserts.assertEquals(one.elements().keySet(), Set.of(baseOffset),
                target + ": length-one descriptor");
        Set<Integer> manyOffsets = Set.of(
                baseOffset, baseOffset + indexScale,
                baseOffset + 2 * indexScale, baseOffset + 3 * indexScale,
                baseOffset + 4 * indexScale);
        Asserts.assertEquals(many.elements().keySet(), manyOffsets,
                target + ": exact length-many descriptor offsets");

        assertElement(one, baseOffset, type, operandPrefix,
                exactOperands == null ? null : exactOperands.get(0), target);
        for (int index = 0; index < 5; index++) {
            assertElement(many, baseOffset + index * indexScale,
                    type, operandPrefix,
                    exactOperands == null ? null : exactOperands.get(index + 1),
                    target);
        }
    }

    private static void assertElement(
            PEATestUtils.VirtualObjectDescriptor descriptor, int offset,
            PEATestUtils.DeoptBasicType type, String operandPrefix,
            String exactOperand, Method target) {
        PEATestUtils.VirtualObjectEntry element = descriptor.elements().get(offset);
        Asserts.assertNotNull(element,
                target + ": missing array element at byte offset " + offset);
        Asserts.assertEquals(element.basicType(), type,
                target + ": element basic type at byte offset " + offset);
        Asserts.assertEquals(element.value().kind(),
                PEATestUtils.DeoptValueKind.SCALAR,
                target + ": scalar element at byte offset " + offset);
        if (exactOperand != null) {
            if (exactOperand.startsWith("boolean ")) {
                boolean expected = Boolean.parseBoolean(
                        exactOperand.substring("boolean ".length()));
                Asserts.assertEquals(booleanValue(element.value().operand()), expected,
                        target + ": logical boolean value at byte offset " + offset);
            } else {
                Asserts.assertEquals(element.value().operand(), exactOperand,
                        target + ": exact element operand at byte offset " + offset);
            }
        } else {
            Asserts.assertTrue(element.value().operand().startsWith(operandPrefix),
                    target + ": typed element operand at byte offset " + offset);
        }
    }

    private static boolean booleanValue(String operand) {
        return switch (operand) {
            case "i1 true", "i1 1", "i8 1" -> true;
            case "i1 false", "i1 0", "i8 0" -> false;
            default -> throw new AssertionError(
                    "invalid normalized boolean operand: " + operand);
        };
    }

    public static class TestWrapper {
        private static final int FLOAT_LEFT_BITS = 0x7FC12345;
        private static final int FLOAT_MIDDLE_BITS = 0x3F123456;
        private static final int FLOAT_RIGHT_BITS = 0x80000000;
        private static final int FLOAT_MUTATION_BITS = 0xFFA54321;
        private static final long DOUBLE_LEFT_BITS = 0x7FF8123456789ABCL;
        private static final long DOUBLE_MIDDLE_BITS = 0x3FE123456789ABCDL;
        private static final long DOUBLE_RIGHT_BITS = 0x8000000000000000L;
        private static final long DOUBLE_MUTATION_BITS = 0xFFF123456789ABCDL;

        private static final Method BOOLEAN_TARGET = target("testBoolean");
        private static final Method BYTE_TARGET = target("testByte");
        private static final Method SHORT_TARGET = target("testShort");
        private static final Method CHAR_TARGET = target("testChar");
        private static final Method INT_TARGET = target("testInt");
        private static final Method LONG_TARGET = target("testLong");
        private static final Method FLOAT_TARGET = target("testFloat");
        private static final Method DOUBLE_TARGET = target("testDouble");

        private static Method deoptTarget;

        public static void main(String[] args) throws Exception {
            Float.intBitsToFloat(0);
            Double.longBitsToDouble(0);
            PEATestUtils.compileConfiguredTargetsAtLevel4();

            long payload = 0x6A09E667F3BCC909L;
            long result;
            deoptTarget = BOOLEAN_TARGET;
            result = testBoolean();
            requireSuccess(result);
            payload = mix(payload, result);
            deoptTarget = BYTE_TARGET;
            result = testByte();
            requireSuccess(result);
            payload = mix(payload, result);
            deoptTarget = SHORT_TARGET;
            result = testShort();
            requireSuccess(result);
            payload = mix(payload, result);
            deoptTarget = CHAR_TARGET;
            result = testChar();
            requireSuccess(result);
            payload = mix(payload, result);
            deoptTarget = INT_TARGET;
            result = testInt();
            requireSuccess(result);
            payload = mix(payload, result);
            deoptTarget = LONG_TARGET;
            result = testLong();
            requireSuccess(result);
            payload = mix(payload, result);
            deoptTarget = FLOAT_TARGET;
            result = testFloat();
            requireSuccess(result);
            payload = mix(payload, result);
            deoptTarget = DOUBLE_TARGET;
            result = testDouble();
            requireSuccess(result);
            payload = mix(payload, result);

            System.out.println("PEA-RESULT:" + Long.toUnsignedString(payload, 16));
        }

        public static long testBoolean() {
            boolean[] empty = new boolean[0];
            boolean[] one = new boolean[1];
            boolean[] many = new boolean[5];
            many[0] = true;
            many[3] = true;
            many[4] = false;

            requestDeopt();

            if (empty.length != 0 || one.length != 1 || many.length != 5
                    || one[0] || !many[0] || many[1] || many[2]
                    || !many[3] || many[4]) {
                return Long.MIN_VALUE + 1;
            }
            one[0] = true;
            many[0] = false;
            many[4] = true;
            if (!one[0] || many[0] || !many[4]) {
                return Long.MIN_VALUE + 2;
            }
            return (one[0] ? 1L : 0L) | (many[4] ? 2L : 0L);
        }

        public static long testByte() {
            byte[] empty = new byte[0];
            byte[] one = new byte[1];
            byte[] many = new byte[5];
            many[0] = -101;
            many[3] = 37;
            many[4] = 99;

            requestDeopt();

            if (empty.length != 0 || one.length != 1 || many.length != 5
                    || one[0] != 0 || many[0] != -101 || many[1] != 0
                    || many[2] != 0 || many[3] != 37 || many[4] != 99) {
                return Long.MIN_VALUE + 3;
            }
            one[0] = -7;
            many[0] = 55;
            many[4] = -88;
            if (one[0] != -7 || many[0] != 55 || many[4] != -88) {
                return Long.MIN_VALUE + 4;
            }
            return ((long) one[0] << 16)
                    ^ ((long) many[0] << 8) ^ (many[4] & 0xFFL);
        }

        public static long testShort() {
            short[] empty = new short[0];
            short[] one = new short[1];
            short[] many = new short[5];
            many[0] = -22222;
            many[3] = 1234;
            many[4] = 30000;

            requestDeopt();

            if (empty.length != 0 || one.length != 1 || many.length != 5
                    || one[0] != 0 || many[0] != -22222 || many[1] != 0
                    || many[2] != 0 || many[3] != 1234 || many[4] != 30000) {
                return Long.MIN_VALUE + 5;
            }
            one[0] = -30000;
            many[0] = 22222;
            many[4] = -1234;
            if (one[0] != -30000 || many[0] != 22222 || many[4] != -1234) {
                return Long.MIN_VALUE + 6;
            }
            return ((long) one[0] << 32)
                    ^ ((long) many[0] << 16) ^ (many[4] & 0xFFFFL);
        }

        public static long testChar() {
            char[] empty = new char[0];
            char[] one = new char[1];
            char[] many = new char[5];
            many[0] = '\u0001';
            many[3] = '\u5A3C';
            many[4] = '\uFFFE';

            requestDeopt();

            if (empty.length != 0 || one.length != 1 || many.length != 5
                    || one[0] != 0 || many[0] != '\u0001' || many[1] != 0
                    || many[2] != 0 || many[3] != '\u5A3C'
                    || many[4] != '\uFFFE') {
                return Long.MIN_VALUE + 7;
            }
            one[0] = '\uFFFF';
            many[0] = '\u1234';
            many[4] = '\u03A9';
            if (one[0] != '\uFFFF' || many[0] != '\u1234'
                    || many[4] != '\u03A9') {
                return Long.MIN_VALUE + 8;
            }
            return ((long) one[0] << 32)
                    ^ ((long) many[0] << 16) ^ many[4];
        }

        public static long testInt() {
            int[] empty = new int[0];
            int[] one = new int[1];
            int[] many = new int[5];
            many[0] = Integer.MIN_VALUE;
            many[3] = 0x13579BDF;
            many[4] = Integer.MAX_VALUE;

            requestDeopt();

            if (empty.length != 0 || one.length != 1 || many.length != 5
                    || one[0] != 0 || many[0] != Integer.MIN_VALUE
                    || many[1] != 0 || many[2] != 0
                    || many[3] != 0x13579BDF
                    || many[4] != Integer.MAX_VALUE) {
                return Long.MIN_VALUE + 9;
            }
            one[0] = -1;
            many[0] = 0x2468ACE0;
            many[4] = -2023406815;
            if (one[0] != -1 || many[0] != 0x2468ACE0
                    || many[4] != -2023406815) {
                return Long.MIN_VALUE + 10;
            }
            return ((long) one[0] << 32)
                    ^ Integer.toUnsignedLong(many[0])
                    ^ Long.rotateLeft(Integer.toUnsignedLong(many[4]), 11);
        }

        public static long testLong() {
            long[] empty = new long[0];
            long[] one = new long[1];
            long[] many = new long[5];
            many[0] = -9000000001L;
            many[3] = 1234567890123L;
            many[4] = Long.MAX_VALUE;

            requestDeopt();

            if (empty.length != 0 || one.length != 1 || many.length != 5
                    || one[0] != 0L || many[0] != -9000000001L
                    || many[1] != 0L || many[2] != 0L
                    || many[3] != 1234567890123L
                    || many[4] != Long.MAX_VALUE) {
                return Long.MIN_VALUE + 11;
            }
            one[0] = Long.MIN_VALUE;
            many[0] = 0x123456789ABCDEF0L;
            many[4] = -0x0FEDCBA987654321L;
            if (one[0] != Long.MIN_VALUE
                    || many[0] != 0x123456789ABCDEF0L
                    || many[4] != -0x0FEDCBA987654321L) {
                return Long.MIN_VALUE + 12;
            }
            return one[0] ^ Long.rotateLeft(many[0], 9)
                    ^ Long.rotateRight(many[4], 7);
        }

        public static long testFloat() {
            float[] empty = new float[0];
            float[] one = new float[1];
            float[] many = new float[5];
            many[0] = Float.intBitsToFloat(FLOAT_LEFT_BITS);
            many[3] = Float.intBitsToFloat(FLOAT_MIDDLE_BITS);
            many[4] = Float.intBitsToFloat(FLOAT_RIGHT_BITS);

            requestDeopt();

            if (empty.length != 0 || one.length != 1 || many.length != 5
                    || Float.floatToRawIntBits(one[0]) != 0
                    || Float.floatToRawIntBits(many[0]) != FLOAT_LEFT_BITS
                    || Float.floatToRawIntBits(many[1]) != 0
                    || Float.floatToRawIntBits(many[2]) != 0
                    || Float.floatToRawIntBits(many[3]) != FLOAT_MIDDLE_BITS
                    || Float.floatToRawIntBits(many[4]) != FLOAT_RIGHT_BITS) {
                return Long.MIN_VALUE + 13;
            }
            one[0] = Float.intBitsToFloat(FLOAT_MUTATION_BITS);
            many[0] = Float.intBitsToFloat(0x00000001);
            many[4] = Float.intBitsToFloat(0xFF800000);
            if (Float.floatToRawIntBits(one[0]) != FLOAT_MUTATION_BITS
                    || Float.floatToRawIntBits(many[0]) != 0x00000001
                    || Float.floatToRawIntBits(many[4]) != 0xFF800000) {
                return Long.MIN_VALUE + 14;
            }
            return (Integer.toUnsignedLong(Float.floatToRawIntBits(one[0])) << 1)
                    ^ Integer.toUnsignedLong(Float.floatToRawIntBits(many[0]))
                    ^ Long.rotateLeft(Integer.toUnsignedLong(
                            Float.floatToRawIntBits(many[4])), 17);
        }

        public static long testDouble() {
            double[] empty = new double[0];
            double[] one = new double[1];
            double[] many = new double[5];
            many[0] = Double.longBitsToDouble(DOUBLE_LEFT_BITS);
            many[3] = Double.longBitsToDouble(DOUBLE_MIDDLE_BITS);
            many[4] = Double.longBitsToDouble(DOUBLE_RIGHT_BITS);

            requestDeopt();

            if (empty.length != 0 || one.length != 1 || many.length != 5
                    || Double.doubleToRawLongBits(one[0]) != 0L
                    || Double.doubleToRawLongBits(many[0]) != DOUBLE_LEFT_BITS
                    || Double.doubleToRawLongBits(many[1]) != 0L
                    || Double.doubleToRawLongBits(many[2]) != 0L
                    || Double.doubleToRawLongBits(many[3]) != DOUBLE_MIDDLE_BITS
                    || Double.doubleToRawLongBits(many[4]) != DOUBLE_RIGHT_BITS) {
                return Long.MIN_VALUE + 15;
            }
            one[0] = Double.longBitsToDouble(DOUBLE_MUTATION_BITS);
            many[0] = Double.longBitsToDouble(0x0000000000000001L);
            many[4] = Double.longBitsToDouble(0xFFF0000000000000L);
            if (Double.doubleToRawLongBits(one[0]) != DOUBLE_MUTATION_BITS
                    || Double.doubleToRawLongBits(many[0])
                            != 0x0000000000000001L
                    || Double.doubleToRawLongBits(many[4])
                            != 0xFFF0000000000000L) {
                return Long.MIN_VALUE + 16;
            }
            return Long.rotateLeft(Double.doubleToRawLongBits(one[0]), 3)
                    ^ Double.doubleToRawLongBits(many[0])
                    ^ Long.rotateRight(Double.doubleToRawLongBits(many[4]), 13);
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

        private static Method target(String name) {
            try {
                return TestWrapper.class.getMethod(name);
            } catch (ReflectiveOperationException failure) {
                throw new ExceptionInInitializerError(failure);
            }
        }

        private static void requireSuccess(long result) {
            if (result >= Long.MIN_VALUE + 1 && result <= Long.MIN_VALUE + 16) {
                throw new AssertionError("array workload failure " + result);
            }
        }

        private static long mix(long accumulator, long value) {
            return Long.rotateLeft(accumulator ^ value, 13)
                    * 0x9E3779B97F4A7C15L;
        }
    }
}
