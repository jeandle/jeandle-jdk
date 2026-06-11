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
 *          Double.doubleToRawLongBits, and Double.longBitsToDouble
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
    }
}
