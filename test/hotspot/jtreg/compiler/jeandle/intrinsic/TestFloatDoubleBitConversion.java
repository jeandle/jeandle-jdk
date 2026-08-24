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
 * accompanied this code.
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 */

/*
 * @test
 * @key randomness
 * @summary Test the intrinsic implementation of Float.floatToRawIntBits, Float.intBitsToFloat,
 *          Double.doubleToRawLongBits, Double.longBitsToDouble, Float.floatToIntBits, and
 *          Double.doubleToLongBits
 * @library /test/lib /
 * @build jdk.test.lib.Asserts
 * @run main/othervm compiler.jeandle.intrinsic.TestFloatDoubleBitConversion
 */

package compiler.jeandle.intrinsic;

import compiler.jeandle.fileCheck.FileCheck;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestFloatDoubleBitConversion {
    public static void main(String[] args) throws Exception {
        String dumpPath = Files.createTempDirectory("jeandle_test_bitconv").toString();

        ArrayList<String> commandArgs = new ArrayList<>(List.of(
                "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
                "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dumpPath,
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::floatToRawIntBits",
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::intBitsToFloat",
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::doubleToRawLongBits",
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::longBitsToDouble",
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::floatToIntBits",
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::doubleToLongBits",
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::main",
                TestWrapper.class.getName()));

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(commandArgs);
        OutputAnalyzer output = ProcessTools.executeCommand(pb);

        output.shouldHaveExitValue(0)
              .shouldContain("is parsed as intrinsic");

        // Verify bitcast intrinsic is present for each method
        FileCheck floatToIntChecker = new FileCheck(dumpPath,
                TestWrapper.class.getMethod("floatToRawIntBits", float.class), false);
        floatToIntChecker.checkPattern("bitcast");

        FileCheck intToFloatChecker = new FileCheck(dumpPath,
                TestWrapper.class.getMethod("intBitsToFloat", int.class), false);
        intToFloatChecker.checkPattern("bitcast");

        FileCheck doubleToLongChecker = new FileCheck(dumpPath,
                TestWrapper.class.getMethod("doubleToRawLongBits", double.class), false);
        doubleToLongChecker.checkPattern("bitcast");

        FileCheck longToDoubleChecker = new FileCheck(dumpPath,
                TestWrapper.class.getMethod("longBitsToDouble", long.class), false);
        longToDoubleChecker.checkPattern("bitcast");

        // floatToIntBits/doubleToLongBits canonicalize NaN, so unlike the raw variants above
        // they must lower to an isnan compare feeding a select, not a bare bitcast.
        FileCheck floatToIntBitsChecker = new FileCheck(dumpPath,
                TestWrapper.class.getMethod("floatToIntBits", float.class), false);
        floatToIntBitsChecker.checkPattern("fcmp une");
        floatToIntBitsChecker.checkPattern("bitcast");
        floatToIntBitsChecker.checkPattern("select");

        FileCheck doubleToLongBitsChecker = new FileCheck(dumpPath,
                TestWrapper.class.getMethod("doubleToLongBits", double.class), false);
        doubleToLongBitsChecker.checkPattern("fcmp une");
        doubleToLongBitsChecker.checkPattern("bitcast");
        doubleToLongBitsChecker.checkPattern("select");
    }

    static class TestWrapper {
        // Force load java.lang.Float and java.lang.Double classes
        static int vi = Float.floatToRawIntBits(1.0f);
        static float vf = Float.intBitsToFloat(0x3f800000);
        static long vl = Double.doubleToRawLongBits(1.0d);
        static double vd = Double.longBitsToDouble(0x3ff0000000000000L);

        public static void main(String[] args) {
            Random random = new Random();

            // ===== Float.floatToRawIntBits =====
            Asserts.assertEquals(0x00000000, floatToRawIntBits(0.0f), "floatToRawIntBits(+0.0)");
            Asserts.assertEquals(0x80000000, floatToRawIntBits(-0.0f), "floatToRawIntBits(-0.0)");
            Asserts.assertEquals(0x7f800000, floatToRawIntBits(Float.POSITIVE_INFINITY),
                    "floatToRawIntBits(+Inf)");
            Asserts.assertEquals(0xff800000, floatToRawIntBits(Float.NEGATIVE_INFINITY),
                    "floatToRawIntBits(-Inf)");

            // NaN: floatToRawIntBits preserves exact NaN bit pattern (key difference from floatToIntBits)
            int nanBits = floatToRawIntBits(Float.NaN);
            Asserts.assertTrue(Float.isNaN(intBitsToFloat(nanBits)),
                    "floatToRawIntBits(NaN) should preserve NaN");
            Asserts.assertEquals(nanBits, 0x7fc00000, "floatToRawIntBits(NaN) should be canonical NaN bits");

            // Signaling NaN: floatToRawIntBits must NOT canonicalize
            int signalingNanBits = 0x7f800001; // signaling NaN
            float signalingNan = intBitsToFloat(signalingNanBits);
            Asserts.assertTrue(Float.isNaN(signalingNan), "signaling NaN should still be NaN");
            // The key property: floatToRawIntBits preserves the exact bit pattern
            Asserts.assertEquals(signalingNanBits, floatToRawIntBits(signalingNan),
                    "floatToRawIntBits must preserve signaling NaN bit pattern");

            // Subnormal and boundary
            Asserts.assertEquals(floatToRawIntBits(Float.MIN_VALUE),
                    Float.floatToRawIntBits(Float.MIN_VALUE), "floatToRawIntBits(MIN_VALUE)");
            Asserts.assertEquals(floatToRawIntBits(Float.MIN_NORMAL),
                    Float.floatToRawIntBits(Float.MIN_NORMAL), "floatToRawIntBits(MIN_NORMAL)");
            Asserts.assertEquals(floatToRawIntBits(Float.MAX_VALUE),
                    Float.floatToRawIntBits(Float.MAX_VALUE), "floatToRawIntBits(MAX_VALUE)");

            // ===== Float.intBitsToFloat =====
            Asserts.assertTrue(intBitsToFloat(0x00000000) == 0.0f, "intBitsToFloat(+0)");
            Asserts.assertTrue(Float.floatToRawIntBits(intBitsToFloat(0x80000000)) == 0x80000000,
                    "intBitsToFloat(-0)");
            Asserts.assertEquals(Float.POSITIVE_INFINITY, intBitsToFloat(0x7f800000),
                    "intBitsToFloat(+Inf bits)");
            Asserts.assertEquals(Float.NEGATIVE_INFINITY, intBitsToFloat(0xff800000),
                    "intBitsToFloat(-Inf bits)");

            // Round-trip identity for random floats: intBitsToFloat(floatToRawIntBits(f)) == f
            for (int i = 0; i < 1000; i++) {
                float f = Float.intBitsToFloat(random.nextInt());
                int bits = floatToRawIntBits(f);
                float restored = intBitsToFloat(bits);
                Asserts.assertEquals(Float.floatToRawIntBits(f), Float.floatToRawIntBits(restored),
                        "Float round-trip identity failed");
            }

            // ===== Float.floatToIntBits =====
            // Unlike floatToRawIntBits, every NaN input -- however it was constructed -- must
            // canonicalize to the single bit pattern 0x7fc00000.
            Asserts.assertEquals(0x7fc00000, floatToIntBits(Float.NaN), "floatToIntBits(NaN)");
            Asserts.assertEquals(0x7fc00000, floatToIntBits(intBitsToFloat(0x7f800001)),
                    "floatToIntBits(signaling NaN) must canonicalize");
            Asserts.assertEquals(0x7fc00000, floatToIntBits(intBitsToFloat(0xffc00001)),
                    "floatToIntBits(negative NaN payload) must canonicalize");
            Asserts.assertEquals(0x7fc00000, floatToIntBits(intBitsToFloat(0x7fffffff)),
                    "floatToIntBits(all-ones NaN payload) must canonicalize");

            // Non-NaN values are bit-identical to floatToRawIntBits: zero, infinities, and an
            // interpreter-computed reference for boundary/subnormal values.
            Asserts.assertEquals(0x00000000, floatToIntBits(0.0f), "floatToIntBits(+0.0)");
            Asserts.assertEquals(0x80000000, floatToIntBits(-0.0f), "floatToIntBits(-0.0)");
            Asserts.assertEquals(0x7f800000, floatToIntBits(Float.POSITIVE_INFINITY),
                    "floatToIntBits(+Inf)");
            Asserts.assertEquals(0xff800000, floatToIntBits(Float.NEGATIVE_INFINITY),
                    "floatToIntBits(-Inf)");
            Asserts.assertEquals(Float.floatToIntBits(Float.MIN_VALUE), floatToIntBits(Float.MIN_VALUE),
                    "floatToIntBits(MIN_VALUE)");
            Asserts.assertEquals(Float.floatToIntBits(Float.MAX_VALUE), floatToIntBits(Float.MAX_VALUE),
                    "floatToIntBits(MAX_VALUE)");

            // Random non-NaN floats: compare against the interpreter-run Float.floatToIntBits.
            for (int i = 0; i < 1000; i++) {
                float f = (random.nextFloat() - 0.5f) * 2000.0f;
                Asserts.assertEquals(Float.floatToIntBits(f), floatToIntBits(f),
                        "floatToIntBits random mismatch for " + f);
            }

            // ===== Double.doubleToRawLongBits =====
            Asserts.assertEquals(0x0000000000000000L, doubleToRawLongBits(0.0d),
                    "doubleToRawLongBits(+0.0)");
            Asserts.assertEquals(0x8000000000000000L, doubleToRawLongBits(-0.0d),
                    "doubleToRawLongBits(-0.0)");
            Asserts.assertEquals(0x7ff0000000000000L, doubleToRawLongBits(Double.POSITIVE_INFINITY),
                    "doubleToRawLongBits(+Inf)");
            Asserts.assertEquals(0xfff0000000000000L, doubleToRawLongBits(Double.NEGATIVE_INFINITY),
                    "doubleToRawLongBits(-Inf)");

            // NaN: doubleToRawLongBits preserves exact NaN bit pattern
            long nanBitsL = doubleToRawLongBits(Double.NaN);
            Asserts.assertTrue(Double.isNaN(longBitsToDouble(nanBitsL)),
                    "doubleToRawLongBits(NaN) should preserve NaN");
            Asserts.assertEquals(nanBitsL, 0x7ff8000000000000L,
                    "doubleToRawLongBits(NaN) should be canonical NaN bits");

            // Signaling NaN: doubleToRawLongBits must NOT canonicalize
            long signalingNanBitsL = 0x7ff0000000000001L; // signaling NaN
            double signalingNanL = longBitsToDouble(signalingNanBitsL);
            Asserts.assertTrue(Double.isNaN(signalingNanL), "signaling NaN (double) should still be NaN");
            Asserts.assertEquals(signalingNanBitsL, doubleToRawLongBits(signalingNanL),
                    "doubleToRawLongBits must preserve signaling NaN bit pattern");

            // Subnormal and boundary
            Asserts.assertEquals(doubleToRawLongBits(Double.MIN_VALUE),
                    Double.doubleToRawLongBits(Double.MIN_VALUE), "doubleToRawLongBits(MIN_VALUE)");
            Asserts.assertEquals(doubleToRawLongBits(Double.MIN_NORMAL),
                    Double.doubleToRawLongBits(Double.MIN_NORMAL), "doubleToRawLongBits(MIN_NORMAL)");
            Asserts.assertEquals(doubleToRawLongBits(Double.MAX_VALUE),
                    Double.doubleToRawLongBits(Double.MAX_VALUE), "doubleToRawLongBits(MAX_VALUE)");

            // ===== Double.longBitsToDouble =====
            Asserts.assertTrue(longBitsToDouble(0x0000000000000000L) == 0.0d,
                    "longBitsToDouble(+0)");
            Asserts.assertTrue(Double.doubleToRawLongBits(longBitsToDouble(0x8000000000000000L))
                            == 0x8000000000000000L,
                    "longBitsToDouble(-0)");
            Asserts.assertEquals(Double.POSITIVE_INFINITY, longBitsToDouble(0x7ff0000000000000L),
                    "longBitsToDouble(+Inf bits)");
            Asserts.assertEquals(Double.NEGATIVE_INFINITY, longBitsToDouble(0xfff0000000000000L),
                    "longBitsToDouble(-Inf bits)");

            // Round-trip identity for random doubles: longBitsToDouble(doubleToRawLongBits(d)) == d
            for (int i = 0; i < 1000; i++) {
                double d = Double.longBitsToDouble(random.nextLong());
                long bits = doubleToRawLongBits(d);
                double restored = longBitsToDouble(bits);
                Asserts.assertEquals(Double.doubleToRawLongBits(d), Double.doubleToRawLongBits(restored),
                        "Double round-trip identity failed");
            }

            // ===== Double.doubleToLongBits =====
            // Unlike doubleToRawLongBits, every NaN input must canonicalize to
            // 0x7ff8000000000000L.
            Asserts.assertEquals(0x7ff8000000000000L, doubleToLongBits(Double.NaN),
                    "doubleToLongBits(NaN)");
            Asserts.assertEquals(0x7ff8000000000000L, doubleToLongBits(longBitsToDouble(0x7ff0000000000001L)),
                    "doubleToLongBits(signaling NaN) must canonicalize");
            Asserts.assertEquals(0x7ff8000000000000L, doubleToLongBits(longBitsToDouble(0xfff8000000000001L)),
                    "doubleToLongBits(negative NaN payload) must canonicalize");
            Asserts.assertEquals(0x7ff8000000000000L, doubleToLongBits(longBitsToDouble(0x7fffffffffffffffL)),
                    "doubleToLongBits(all-ones NaN payload) must canonicalize");

            // Non-NaN values are bit-identical to doubleToRawLongBits.
            Asserts.assertEquals(0x0000000000000000L, doubleToLongBits(0.0d), "doubleToLongBits(+0.0)");
            Asserts.assertEquals(0x8000000000000000L, doubleToLongBits(-0.0d), "doubleToLongBits(-0.0)");
            Asserts.assertEquals(0x7ff0000000000000L, doubleToLongBits(Double.POSITIVE_INFINITY),
                    "doubleToLongBits(+Inf)");
            Asserts.assertEquals(0xfff0000000000000L, doubleToLongBits(Double.NEGATIVE_INFINITY),
                    "doubleToLongBits(-Inf)");
            Asserts.assertEquals(Double.doubleToLongBits(Double.MIN_VALUE), doubleToLongBits(Double.MIN_VALUE),
                    "doubleToLongBits(MIN_VALUE)");
            Asserts.assertEquals(Double.doubleToLongBits(Double.MAX_VALUE), doubleToLongBits(Double.MAX_VALUE),
                    "doubleToLongBits(MAX_VALUE)");

            // Random non-NaN doubles: compare against the interpreter-run Double.doubleToLongBits.
            for (int i = 0; i < 1000; i++) {
                double d = (random.nextDouble() - 0.5d) * 2000.0d;
                Asserts.assertEquals(Double.doubleToLongBits(d), doubleToLongBits(d),
                        "doubleToLongBits random mismatch for " + d);
            }

            System.out.println("TestFloatDoubleBitConversion PASSED");
        }

        public static int floatToRawIntBits(float f) {
            return Float.floatToRawIntBits(f);
        }

        public static float intBitsToFloat(int bits) {
            return Float.intBitsToFloat(bits);
        }

        public static long doubleToRawLongBits(double d) {
            return Double.doubleToRawLongBits(d);
        }

        public static double longBitsToDouble(long bits) {
            return Double.longBitsToDouble(bits);
        }

        public static int floatToIntBits(float f) {
            return Float.floatToIntBits(f);
        }

        public static long doubleToLongBits(double d) {
            return Double.doubleToLongBits(d);
        }
    }
}
