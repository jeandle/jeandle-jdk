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
 * @summary PEA scalar-replaces zero-, one-, and multi-element arrays for
 *          every primitive kind without losing defaults, overwrites, or bits
 * @library /test/lib /
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.pea.TestPEAPrimitiveArrayScalarReplacement
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;

import jdk.test.lib.Asserts;

public class TestPEAPrimitiveArrayScalarReplacement {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestPEAPrimitiveArrayScalarReplacement$TestWrapper";
    private static final String DEOPTIMIZE = "@llvm.experimental.deoptimize";
    private static final String LOWERED_DEOPTIMIZE = "@__llvm_deoptimize";

    public static void main(String[] args) throws Exception {
        Method booleans = TestWrapper.class.getMethod(
                "testBoolean", boolean.class, boolean.class);
        Method bytes = TestWrapper.class.getMethod(
                "testByte", byte.class, byte.class, byte.class);
        Method shorts = TestWrapper.class.getMethod(
                "testShort", short.class, short.class, short.class);
        Method chars = TestWrapper.class.getMethod(
                "testChar", char.class, char.class, char.class);
        Method ints = TestWrapper.class.getMethod(
                "testInt", int.class, int.class, int.class);
        Method longs = TestWrapper.class.getMethod(
                "testLong", long.class, long.class, long.class);
        Method floats = TestWrapper.class.getMethod(
                "testFloat", int.class, float.class, float.class, float.class);
        Method doubles = TestWrapper.class.getMethod(
                "testDouble", int.class, double.class, double.class, double.class);
        Method booleanChecksum = TestWrapper.class.getMethod(
                "booleanChecksum", int.class, int.class, int.class,
                boolean.class, boolean.class, boolean.class, boolean.class, boolean.class);
        Method[] targets = {booleans, bytes, shorts, chars, ints, longs, floats, doubles};

        PEATestUtils.behaviorRun(WRAPPER, targets)
                .dontinline(booleanChecksum)
                .runPEAOnOffEquivalent();

        try (PEATestUtils.RunResult run = PEATestUtils.shapeRun(WRAPPER, targets)
                .dontinline(booleanChecksum)
                .run()) {
            for (Method target : targets) {
                run.report(target).assertFinalTransformIdle();
                assertPrimitiveArrayScalarReplacement(run, target);
            }
        }
    }

    private static void assertPrimitiveArrayScalarReplacement(
            PEATestUtils.RunResult run, Method target) throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.PEARound first = report.round(0);
        PEATestUtils.IRBody before = first.before();
        PEATestUtils.IRBody after = report.finalAfter();

        Asserts.assertEquals(before.allocations().size(), 3,
                target + ": zero-, one-, and four-element source arrays");
        for (PEATestUtils.AllocationSite site : before.allocations()) {
            Asserts.assertEquals(site.key().kind(), PEATestUtils.AllocationKind.ARRAY,
                    target + ": primitive allocation kind");
        }
        Asserts.assertEquals(first.effectCount("EliminateAllocation"), 3L,
                target + ": exact allocation elimination effects");

        int sourceStores = before.lineCount("store atomic");
        int sourceLoads = before.lineCount("load atomic");
        Asserts.assertTrue(sourceStores >= 5,
                target + ": constant-index first writes, overwrites, and independent slots");
        Asserts.assertTrue(sourceLoads >= 1,
                target + ": at least the untouched default element reaches PEA as a load");
        Asserts.assertEquals(first.effectCount("EliminateStore", "store atomic"),
                (long) sourceStores, target + ": every source element store is eliminated");
        Asserts.assertEquals(first.effectCount("ReplaceLoad", "load atomic"),
                (long) sourceLoads, target + ": every source element load is replaced");

        after.assertRetainsExactlyOriginalAllocations(before);
        after.assertAbsent("store atomic");
        after.assertAbsent("load atomic");
        Asserts.assertEquals(run.finalIR(target).loweredAllocCount(), 0,
                target + ": no lowered primitive allocation");
        run.finalIR(target).assertAbsent("store atomic");
        run.finalIR(target).assertAbsent("load atomic");
        after.assertAbsent(DEOPTIMIZE);
        run.finalIR(target).assertAbsent(LOWERED_DEOPTIMIZE);
        Asserts.assertEquals(first.alwaysEscapes(), 0, target + ": no escaping array");
        Asserts.assertEquals(first.neverEscapes(), 3,
                target + ": every qualified array is NeverEscapes");
        Asserts.assertEquals(first.partiallyEscapes(), 0,
                target + ": no qualified array is partially escaping");
        Asserts.assertEquals(first.effectCount("Materialize"), 0L,
                target + ": no qualified array is materialized");
    }

    public static class TestWrapper {
        public static void main(String[] args) throws Exception {
            PEATestUtils.compileConfiguredTargetsAtLevel4();

            long digest = 0x243F6A8885A308D3L;
            digest = mix(digest, checkBoolean(false, true));
            digest = mix(digest, checkBoolean(true, false));

            digest = mix(digest, checkByte(Byte.MIN_VALUE, Byte.MAX_VALUE, (byte) -1));
            digest = mix(digest, checkByte((byte) 0x55, (byte) 0xAA, (byte) 0));
            digest = mix(digest, checkShort(Short.MIN_VALUE, Short.MAX_VALUE, (short) -1));
            digest = mix(digest, checkShort((short) 0x1234, (short) 0xFEDC, (short) 0));
            digest = mix(digest, checkChar(Character.MIN_VALUE, Character.MAX_VALUE,
                    (char) 0x8000));
            digest = mix(digest, checkChar((char) 0x1234, (char) 0xFEDC, (char) 0));
            digest = mix(digest, checkInt(Integer.MIN_VALUE, Integer.MAX_VALUE, -1));
            digest = mix(digest, checkInt(0x01234567, 0x89ABCDEF, 0));

            digest = mix(digest, checkLong(0x0123456789ABCDEFL,
                    0xFEDCBA9876543210L, 0x8000000000000001L));
            digest = mix(digest, checkLong(Long.MIN_VALUE, Long.MAX_VALUE, -1L));

            digest = mix(digest, checkFloat(0x7FC01234, 0x80000000, 0x00000000));
            digest = mix(digest, checkFloat(0xFFC05678, 0x00000000, 0x7F7FFFFF));
            digest = mix(digest, checkDouble(0x7FF8000000001234L,
                    0x8000000000000000L, 0x0000000000000000L));
            digest = mix(digest, checkDouble(0xFFF8000000005678L,
                    0x0000000000000000L, 0x7FEFFFFFFFFFFFFFL));

            System.out.println("PEA-RESULT:" + Long.toUnsignedString(digest, 16));
        }

        public static long testBoolean(boolean first, boolean second) {
            boolean[] empty = new boolean[0];
            int emptyLength = empty.length;
            empty = null;
            boolean[] one = new boolean[1];
            boolean oneDefault = one[0];
            int oneLength = one.length;
            one = null;
            boolean[] many = new boolean[4];
            int manyLength = many.length;
            many[0] = first;
            many[1] = second;
            many[2] = first;
            many[3] = second;
            many[1] = first;
            many[1] = second;
            boolean many0 = many[0];
            boolean many1 = many[1];
            boolean many2 = many[2];
            boolean many3 = many[3];
            many = null;
            return booleanChecksum(emptyLength, oneLength, manyLength,
                    oneDefault, many0, many1, many2, many3);
        }

        public static long testByte(byte first, byte second, byte third) {
            byte[] empty = new byte[0];
            byte[] one = new byte[1];
            byte[] many = new byte[4];
            byte oneDefault = one[0];
            many[0] = first;
            many[1] = second;
            many[2] = third;
            many[3] = first;
            many[1] = first;
            many[1] = second;
            return primitiveChecksum(empty.length, one.length, many.length,
                    oneDefault, many[0], many[1], many[2], many[3]);
        }

        public static long testShort(short first, short second, short third) {
            short[] empty = new short[0];
            short[] one = new short[1];
            short[] many = new short[4];
            short oneDefault = one[0];
            many[0] = first;
            many[1] = second;
            many[2] = third;
            many[3] = first;
            many[1] = first;
            many[1] = second;
            return primitiveChecksum(empty.length, one.length, many.length,
                    oneDefault, many[0], many[1], many[2], many[3]);
        }

        public static long testChar(char first, char second, char third) {
            char[] empty = new char[0];
            char[] one = new char[1];
            char[] many = new char[4];
            char oneDefault = one[0];
            many[0] = first;
            many[1] = second;
            many[2] = third;
            many[3] = first;
            many[1] = first;
            many[1] = second;
            return primitiveChecksum(empty.length, one.length, many.length,
                    oneDefault, many[0], many[1], many[2], many[3]);
        }

        public static long testInt(int first, int second, int third) {
            int[] empty = new int[0];
            int[] one = new int[1];
            int[] many = new int[4];
            int oneDefault = one[0];
            many[0] = first;
            many[1] = second;
            many[2] = third;
            many[3] = first;
            many[1] = first;
            many[1] = second;
            return primitiveChecksum(empty.length, one.length, many.length,
                    oneDefault, many[0], many[1], many[2], many[3]);
        }

        public static long testLong(long first, long second, long third) {
            long[] empty = new long[0];
            long[] one = new long[1];
            long[] many = new long[4];
            long oneDefault = one[0];
            many[0] = first;
            many[1] = second;
            many[2] = third;
            many[3] = first;
            many[1] = first;
            many[1] = second;
            return primitiveChecksum(empty.length, one.length, many.length,
                    oneDefault, many[0], many[1], many[2], many[3]);
        }

        public static float testFloat(int selector, float first, float second, float third) {
            float[] empty = new float[0];
            float[] one = new float[1];
            float[] many = new float[4];
            float oneDefault = one[0];
            many[0] = first;
            many[1] = second;
            many[2] = third;
            many[3] = first;
            many[1] = first;
            many[1] = second;
            return switch (selector) {
                case 0 -> oneDefault;
                case 1 -> many[0];
                case 2 -> many[1];
                case 3 -> many[2];
                case 4 -> many[3];
                default -> empty.length + one.length + many.length;
            };
        }

        public static double testDouble(int selector, double first, double second, double third) {
            double[] empty = new double[0];
            double[] one = new double[1];
            double[] many = new double[4];
            double oneDefault = one[0];
            many[0] = first;
            many[1] = second;
            many[2] = third;
            many[3] = first;
            many[1] = first;
            many[1] = second;
            return switch (selector) {
                case 0 -> oneDefault;
                case 1 -> many[0];
                case 2 -> many[1];
                case 3 -> many[2];
                case 4 -> many[3];
                default -> empty.length + one.length + many.length;
            };
        }

        private static long checkBoolean(boolean first, boolean second) {
            long actual = testBoolean(first, second);
            int values = (first ? 2 | 8 : 0) | (second ? 4 | 16 : 0);
            long expected = (1L << 40) | (4L << 32) | values;
            Asserts.assertEquals(actual, expected, "boolean array state");
            return actual;
        }

        private static long checkByte(byte first, byte second, byte third) {
            long actual = testByte(first, second, third);
            long expected = primitiveChecksum(0, 1, 4, 0,
                    first, second, third, first);
            Asserts.assertEquals(actual, expected, "byte array state");
            return actual;
        }

        private static long checkShort(short first, short second, short third) {
            long actual = testShort(first, second, third);
            long expected = primitiveChecksum(0, 1, 4, 0,
                    first, second, third, first);
            Asserts.assertEquals(actual, expected, "short array state");
            return actual;
        }

        private static long checkChar(char first, char second, char third) {
            long actual = testChar(first, second, third);
            long expected = primitiveChecksum(0, 1, 4, 0,
                    first, second, third, first);
            Asserts.assertEquals(actual, expected, "char array state");
            return actual;
        }

        private static long checkInt(int first, int second, int third) {
            long actual = testInt(first, second, third);
            long expected = primitiveChecksum(0, 1, 4, 0,
                    first, second, third, first);
            Asserts.assertEquals(actual, expected, "int array state");
            return actual;
        }

        private static long checkLong(long first, long second, long third) {
            long actual = testLong(first, second, third);
            long expected = primitiveChecksum(0, 1, 4, 0,
                    first, second, third, first);
            Asserts.assertEquals(actual, expected, "long array state");
            return actual;
        }

        private static long checkFloat(int firstBits, int secondBits, int thirdBits) {
            float first = Float.intBitsToFloat(firstBits);
            float second = Float.intBitsToFloat(secondBits);
            float third = Float.intBitsToFloat(thirdBits);
            int[] expected = {0, firstBits, secondBits, thirdBits, firstBits,
                    Float.floatToRawIntBits(5.0f)};
            long digest = 0;
            for (int selector = 0; selector < expected.length; selector++) {
                int actual = Float.floatToRawIntBits(
                        testFloat(selector, first, second, third));
                Asserts.assertEquals(actual, expected[selector],
                        "float raw array bits at selector " + selector);
                digest = mix(digest, Integer.toUnsignedLong(actual));
            }
            return digest;
        }

        private static long checkDouble(long firstBits, long secondBits, long thirdBits) {
            double first = Double.longBitsToDouble(firstBits);
            double second = Double.longBitsToDouble(secondBits);
            double third = Double.longBitsToDouble(thirdBits);
            long[] expected = {0, firstBits, secondBits, thirdBits, firstBits,
                    Double.doubleToRawLongBits(5.0)};
            long digest = 0;
            for (int selector = 0; selector < expected.length; selector++) {
                long actual = Double.doubleToRawLongBits(
                        testDouble(selector, first, second, third));
                Asserts.assertEquals(actual, expected[selector],
                        "double raw array bits at selector " + selector);
                digest = mix(digest, actual);
            }
            return digest;
        }

        private static long primitiveChecksum(int emptyLength, int oneLength, int manyLength,
                                              long untouched, long first, long second,
                                              long third, long last) {
            long value = lengths(emptyLength, oneLength, manyLength);
            value = mix(value, untouched);
            value = mix(value, first);
            value = mix(value, second);
            value = mix(value, third);
            return mix(value, last);
        }

        public static long booleanChecksum(int emptyLength, int oneLength, int manyLength,
                                           boolean untouched, boolean first, boolean second,
                                           boolean third, boolean last) {
            int values = (untouched ? 1 : 0)
                    | (first ? 2 : 0)
                    | (second ? 4 : 0)
                    | (third ? 8 : 0)
                    | (last ? 16 : 0);
            return ((long) emptyLength << 48) | ((long) oneLength << 40)
                    | ((long) manyLength << 32) | values;
        }

        private static long lengths(int emptyLength, int oneLength, int manyLength) {
            return ((long) emptyLength << 42) ^ ((long) oneLength << 21) ^ manyLength;
        }

        private static long mix(long accumulator, long value) {
            return Long.rotateLeft(accumulator ^ value, 17)
                    * 0x9E3779B97F4A7C15L;
        }
    }
}
