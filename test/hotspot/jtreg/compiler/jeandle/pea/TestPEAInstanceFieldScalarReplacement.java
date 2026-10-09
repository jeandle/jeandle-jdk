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
 * @summary PEA instance-field scalar replacement tracks default, first-write,
 *          and overwrite states for every Java field kind
 * @library /test/lib /
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.pea.TestPEAInstanceFieldScalarReplacement
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;

import jdk.test.lib.Asserts;

public class TestPEAInstanceFieldScalarReplacement {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestPEAInstanceFieldScalarReplacement$TestWrapper";

    public static void main(String[] args) throws Exception {
        Method integral = TestWrapper.class.getMethod("testIntegralState",
                int.class, boolean.class, boolean.class, byte.class, byte.class,
                short.class, short.class, char.class, char.class, int.class, int.class,
                long.class, long.class);
        Method floatValue = TestWrapper.class.getMethod("testFloatState",
                int.class, float.class, float.class);
        Method doubleValue = TestWrapper.class.getMethod("testDoubleState",
                int.class, double.class, double.class);
        Method reference = TestWrapper.class.getMethod("testReferenceState",
                int.class, Object.class, Object.class);
        Method[] targets = {integral, floatValue, doubleValue, reference};

        assertConfiguredTargetOverrideRejected(integral);

        try (PEATestUtils.RunResult run = PEATestUtils.shapeRun(WRAPPER, targets).run()) {
            assertNeverEscapeShape(run, integral, 12, 6);
            assertNeverEscapeShape(run, floatValue, 2, 1);
            assertNeverEscapeShape(run, doubleValue, 2, 1);
            assertNeverEscapeShape(run, reference, 2, 1);
        }

        PEATestUtils.assertPEAOnOffEquivalent(WRAPPER, targets);
    }

    private static void assertConfiguredTargetOverrideRejected(Method target) {
        for (String flag : new String[] {
                "-Dcompiler.jeandle.pea.configuredTargets",
                "-Dcompiler.jeandle.pea.configuredTargets=decoy"}) {
            try {
                PEATestUtils.behaviorRun(WRAPPER, target).extraFlags(flag);
                throw new RuntimeException("Expected configured-target override rejection: " + flag);
            } catch (IllegalArgumentException expected) {
                Asserts.assertTrue(expected.getMessage().contains("configured PEA targets"));
            }
        }
        PEATestUtils.behaviorRun(WRAPPER, target)
                .extraFlags("-Dcompiler.jeandle.pea.configuredTargetsExtra=allowed");
    }

    private static void assertNeverEscapeShape(PEATestUtils.RunResult run, Method target,
                                                int stores, int loads) throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.PEARound first = report.round(0);
        Asserts.assertTrue(first.hasStats(), target + ": missing round-0 PEA stats");
        Asserts.assertEquals(first.neverEscapes(), 1, target + ": NeverEscapes");
        Asserts.assertEquals(first.partiallyEscapes(), 0, target + ": PartiallyEscapes");
        Asserts.assertEquals(first.alwaysEscapes(), 0, target + ": AlwaysEscapes");
        Asserts.assertEquals(first.before().peaAllocCount(), 1,
                target + ": round-0 allocation");
        first.before().assertLineCount("store atomic", stores);
        first.before().assertLineCount("load atomic", loads);
        Asserts.assertEquals(report.effects("EliminateAllocation").size(), 1,
                target + ": allocation effects");
        Asserts.assertEquals(report.effects("EliminateStore").size(), stores,
                target + ": store effects");
        Asserts.assertEquals(report.effects("ReplaceLoad").size(), loads + 1,
                target + ": field loads plus allocation-null guard");
        Asserts.assertEquals(report.finalAfter().peaAllocCount(), 0,
                target + ": final PEA allocation");
        report.finalAfter().assertAbsent("store atomic");
        report.finalAfter().assertAbsent("load atomic");
        Asserts.assertEquals(run.frontendIR(target).peaAllocCount(), 1,
                target + ": frontend allocation");
        Asserts.assertEquals(run.finalIR(target).loweredAllocCount(), 0,
                target + ": final dump allocation");
        run.finalIR(target).assertAbsent("store atomic");
        run.finalIR(target).assertAbsent("load atomic");
    }

    public static class TestWrapper {
        public static class Fields {
            public boolean z;
            public byte b;
            public short s;
            public char c;
            public int i;
            public long j;
            public float f;
            public double d;
            public Object o;
        }

        public static void main(String[] args) throws Exception {
            new Fields();
            PEATestUtils.compileConfiguredTargetsAtLevel4();

            long digest = 0x13579BDF2468ACE0L;
            digest = mix(digest, integralCases());
            digest = mix(digest, floatCases());
            digest = mix(digest, doubleCases());
            digest = mix(digest, referenceCases());
            System.out.println("PEA-RESULT:" + Long.toUnsignedString(digest, 16));
        }

        public static long testIntegralState(int state,
                                             boolean firstZ, boolean secondZ,
                                             byte firstB, byte secondB,
                                             short firstS, short secondS,
                                             char firstC, char secondC,
                                             int firstI, int secondI,
                                             long firstJ, long secondJ) {
            Fields fields = new Fields();
            if (state != 0) {
                fields.j = firstJ;
                fields.b = firstB;
                fields.i = firstI;
                fields.z = firstZ;
                fields.c = firstC;
                fields.s = firstS;
            }
            if (state == 2) {
                fields.s = secondS;
                fields.z = secondZ;
                fields.i = secondI;
                fields.c = secondC;
                fields.b = secondB;
                fields.j = secondJ;
            }
            return integralChecksum(fields.z, fields.b, fields.s, fields.c, fields.i, fields.j);
        }

        public static float testFloatState(int state, float first, float second) {
            Fields fields = new Fields();
            if (state != 0) {
                fields.f = first;
            }
            if (state == 2) {
                fields.f = second;
            }
            return fields.f;
        }

        public static double testDoubleState(int state, double first, double second) {
            Fields fields = new Fields();
            if (state != 0) {
                fields.d = first;
            }
            if (state == 2) {
                fields.d = second;
            }
            return fields.d;
        }

        public static Object testReferenceState(int state, Object first, Object second) {
            Fields fields = new Fields();
            if (state != 0) {
                fields.o = first;
            }
            if (state == 2) {
                fields.o = second;
            }
            return fields.o;
        }

        private static long integralCases() {
            long digest = 0;
            digest = checkIntegral(digest, 0,
                    true, false, (byte) -128, (byte) 127,
                    Short.MIN_VALUE, Short.MAX_VALUE, Character.MAX_VALUE, Character.MIN_VALUE,
                    Integer.MIN_VALUE, Integer.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE);
            digest = checkIntegral(digest, 1,
                    true, false, (byte) -128, (byte) 127,
                    Short.MIN_VALUE, Short.MAX_VALUE, Character.MAX_VALUE, Character.MIN_VALUE,
                    Integer.MIN_VALUE, Integer.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE);
            digest = checkIntegral(digest, 2,
                    true, false, (byte) -128, (byte) 127,
                    Short.MIN_VALUE, Short.MAX_VALUE, Character.MAX_VALUE, Character.MIN_VALUE,
                    Integer.MIN_VALUE, Integer.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE);
            digest = checkIntegral(digest, 2,
                    false, true, (byte) 0, (byte) -1,
                    (short) 0, (short) -1, (char) 0, (char) 0x8000,
                    0, -1, 0L, 0x0123456789ABCDEFL);
            return digest;
        }

        private static long checkIntegral(long digest, int state,
                                          boolean firstZ, boolean secondZ,
                                          byte firstB, byte secondB,
                                          short firstS, short secondS,
                                          char firstC, char secondC,
                                          int firstI, int secondI,
                                          long firstJ, long secondJ) {
            long actual = testIntegralState(state, firstZ, secondZ, firstB, secondB,
                    firstS, secondS, firstC, secondC, firstI, secondI, firstJ, secondJ);
            boolean z = state == 0 ? false : state == 1 ? firstZ : secondZ;
            byte b = state == 0 ? 0 : state == 1 ? firstB : secondB;
            short s = state == 0 ? 0 : state == 1 ? firstS : secondS;
            char c = state == 0 ? 0 : state == 1 ? firstC : secondC;
            int i = state == 0 ? 0 : state == 1 ? firstI : secondI;
            long j = state == 0 ? 0 : state == 1 ? firstJ : secondJ;
            long expected = integralChecksum(z, b, s, c, i, j);
            Asserts.assertEquals(actual, expected);
            return mix(digest, actual);
        }

        private static long floatCases() {
            int[] firstBits = {0x7fc01234, 0x80000000, 0x00000001, 0x7f800000};
            int[] secondBits = {0xffc05678, 0x00000000, 0x7f7fffff, 0xff800000};
            long digest = 0;
            digest = checkFloat(digest, 0, firstBits[0], secondBits[0]);
            for (int i = 0; i < firstBits.length; i++) {
                digest = checkFloat(digest, 1, firstBits[i], secondBits[i]);
                digest = checkFloat(digest, 2, firstBits[i], secondBits[i]);
            }
            return digest;
        }

        private static long checkFloat(long digest, int state, int firstBits, int secondBits) {
            float first = Float.intBitsToFloat(firstBits);
            float second = Float.intBitsToFloat(secondBits);
            int actual = Float.floatToRawIntBits(testFloatState(state, first, second));
            int expected = state == 0 ? 0 : state == 1 ? firstBits : secondBits;
            Asserts.assertEquals(actual, expected);
            return mix(digest, Integer.toUnsignedLong(actual));
        }

        private static long doubleCases() {
            long[] firstBits = {0x7ff8000000001234L, 0x8000000000000000L,
                    0x0000000000000001L, 0x7ff0000000000000L};
            long[] secondBits = {0xfff8000000005678L, 0x0000000000000000L,
                    0x7fefffffffffffffL, 0xfff0000000000000L};
            long digest = 0;
            digest = checkDouble(digest, 0, firstBits[0], secondBits[0]);
            for (int i = 0; i < firstBits.length; i++) {
                digest = checkDouble(digest, 1, firstBits[i], secondBits[i]);
                digest = checkDouble(digest, 2, firstBits[i], secondBits[i]);
            }
            return digest;
        }

        private static long checkDouble(long digest, int state, long firstBits, long secondBits) {
            double first = Double.longBitsToDouble(firstBits);
            double second = Double.longBitsToDouble(secondBits);
            long actual = Double.doubleToRawLongBits(testDoubleState(state, first, second));
            long expected = state == 0 ? 0 : state == 1 ? firstBits : secondBits;
            Asserts.assertEquals(actual, expected);
            return mix(digest, actual);
        }

        private static long referenceCases() {
            Object first = new Object();
            Object second = new Object();
            long digest = 0;
            digest = checkReference(digest, 0, first, second, null);
            digest = checkReference(digest, 1, first, second, first);
            digest = checkReference(digest, 1, null, second, null);
            digest = checkReference(digest, 2, first, second, second);
            digest = checkReference(digest, 2, first, null, null);
            digest = checkReference(digest, 2, null, second, second);
            return digest;
        }

        private static long checkReference(long digest, int state, Object first,
                                           Object second, Object expected) {
            Object actual = testReferenceState(state, first, second);
            Asserts.assertTrue(actual == expected);
            return mix(digest, actual == null ? 0 : actual == first ? 1 : 2);
        }

        private static long integralChecksum(boolean z, byte b, short s, char c, int i, long j) {
            long result = z ? 0x6A09E667F3BCC909L : 0;
            result = result * 31 + b;
            result = result * 31 + s;
            result = result * 31 + c;
            result = result * 31 + i;
            return result * 31 + j;
        }

        private static long mix(long accumulator, long value) {
            return Long.rotateLeft(accumulator ^ value, 17) * 0x9E3779B97F4A7C15L;
        }
    }
}
