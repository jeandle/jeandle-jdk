/*
 * Copyright (c) 2026, the Jeandle-JDK Authors. All Rights Reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License version 2 for more details (a copy of the
 * LICENSE file that accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 */

/*
 * @test
 * @key randomness
 * @summary Test the intrinsic implementation of Math.signum(double) and Math.signum(float)
 * @requires os.arch=="amd64" | os.arch=="x86_64" | os.arch=="aarch64"
 * @library /test/lib /
 * @build jdk.test.lib.Asserts
 * @run main/othervm compiler.jeandle.intrinsic.TestSignum
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

public class TestSignum {
    public static void main(String[] args) throws Exception {
        // Run 1: intrinsic enabled. The subprocess checks results bit-exactly
        // and its dumped IR must contain the copysign-based select chain.
        String intrinsicDump = Files.createTempDirectory("jeandle_test_signum_on").toString();
        OutputAnalyzer output = runSubprocess(intrinsicDump, "-XX:+UseSignumIntrinsic");
        output.shouldHaveExitValue(0);

        FileCheck doubleChecker = new FileCheck(intrinsicDump,
                TestWrapper.class.getMethod("double_signum", double.class), false);
        doubleChecker.checkPattern("define hotspotcc double .*TestSignum.*double_signum.*");
        doubleChecker.check("fcmp ord double");
        doubleChecker.check("fcmp oeq double");
        doubleChecker.check("call double @llvm.copysign.f64");
        doubleChecker.checkPattern("select i1 .* double .* double");
        doubleChecker.checkPattern("select i1 .* double .* double");
        // The intrinsic must replace the call, not sit next to it.
        doubleChecker.checkNot("java_lang_Math_signum");

        FileCheck floatChecker = new FileCheck(intrinsicDump,
                TestWrapper.class.getMethod("float_signum", float.class), false);
        floatChecker.checkPattern("define hotspotcc float .*TestSignum.*float_signum.*");
        floatChecker.check("fcmp ord float");
        floatChecker.check("fcmp oeq float");
        floatChecker.check("call float @llvm.copysign.f32");
        floatChecker.checkPattern("select i1 .* float .* float");
        floatChecker.checkPattern("select i1 .* float .* float");
        floatChecker.checkNot("java_lang_Math_signum");

        output.shouldContain("is parsed as intrinsic")
              .shouldContain("java.lang.Math.signum");

        // Run 2: control group with the intrinsic flag disabled (x86 turns it
        // on by default, so disable it explicitly). Results must stay correct
        // and the raw (pre-optimization) IR must not contain the intrinsic's
        // copysign/select-chain shape. Only the raw dump is a valid witness
        // here: with the intrinsic off the call stays an invoke of the
        // outlined Math.signum callee (no floating-point IR at all in the
        // wrapper), while the LLVM optimizer could still fold an inlined
        // fallback body into similar shapes in the optimized dump.
        String controlDump = Files.createTempDirectory("jeandle_test_signum_off").toString();
        output = runSubprocess(controlDump, "-XX:-UseSignumIntrinsic");
        // No intrinsic parse may mention Math.signum itself; the copySign
        // intrinsic of the separately compiled callee body still appears.
        output.shouldHaveExitValue(0)
              .shouldNotContain("java.lang.Math.signum");

        doubleChecker = new FileCheck(controlDump,
                TestWrapper.class.getMethod("double_signum", double.class), false);
        doubleChecker.checkPattern("define hotspotcc double .*TestSignum.*double_signum.*");
        doubleChecker.checkNot("llvm.copysign");
        doubleChecker.checkNot("fcmp ord");
        doubleChecker.checkNot("select i1");

        floatChecker = new FileCheck(controlDump,
                TestWrapper.class.getMethod("float_signum", float.class), false);
        floatChecker.checkPattern("define hotspotcc float .*TestSignum.*float_signum.*");
        floatChecker.checkNot("llvm.copysign");
        floatChecker.checkNot("fcmp ord");
        floatChecker.checkNot("select i1");
    }

    private static OutputAnalyzer runSubprocess(String dumpDir, String signumFlag) throws Exception {
        ArrayList<String> commandArgs = new ArrayList<>(List.of(
                "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
                "-Xlog:jeandle=debug", "-XX:+UnlockDiagnosticVMOptions", "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dumpDir,
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::double_signum",
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::float_signum",
                signumFlag,
                TestWrapper.class.getName()));

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(commandArgs);
        return ProcessTools.executeCommand(pb);
    }

    static class TestWrapper {
        static double v = Math.abs(1.0d);   // Force load java.lang.Math class

        // IEEE signum on raw bits: zeros and NaNs pass through unchanged
        // (payload and sign kept), every other value maps to a signed 1.0.
        private static long ieeeSignumBitsD(double d) {
            long bits = Double.doubleToRawLongBits(d);
            boolean zero = (bits & 0x7fffffffffffffffL) == 0;
            boolean nan = (bits & 0x7ff0000000000000L) == 0x7ff0000000000000L
                       && (bits & 0x000fffffffffffffL) != 0;
            if (zero || nan) {
                return bits;
            }
            return (bits & 0x8000000000000000L) | Double.doubleToRawLongBits(1.0d);
        }

        private static int ieeeSignumBitsF(float f) {
            int bits = Float.floatToRawIntBits(f);
            boolean zero = (bits & 0x7fffffff) == 0;
            boolean nan = (bits & 0x7f800000) == 0x7f800000 && (bits & 0x007fffff) != 0;
            if (zero || nan) {
                return bits;
            }
            return (bits & 0x80000000) | Float.floatToRawIntBits(1.0f);
        }

        // Compare the Jeandle-compiled result against the IEEE formula and
        // against interpreted execution, all at raw-bit level so that NaN
        // payloads and signed zeros cannot slip through.
        private static void checkD(String what, double d) {
            long expected = ieeeSignumBitsD(d);
            long got = Double.doubleToRawLongBits(double_signum(d));
            Asserts.assertEquals(got, expected, what);
            Asserts.assertEquals(got, Double.doubleToRawLongBits(ref_double_signum(d)),
                    what + " (vs interpreted)");
        }

        private static void checkF(String what, float f) {
            int expected = ieeeSignumBitsF(f);
            int got = Float.floatToRawIntBits(float_signum(f));
            Asserts.assertEquals(got, expected, what);
            Asserts.assertEquals(got, Float.floatToRawIntBits(ref_float_signum(f)),
                    what + " (vs interpreted)");
        }

        public static void main(String[] args) {
            double posNaN = Double.longBitsToDouble(0x7ff8000000000042L);  // NaN with a payload
            double negNaN = Double.longBitsToDouble(0xfff8000000000042L);
            float posNaNF = Float.intBitsToFloat(0x7fc00042);
            float negNaNF = Float.intBitsToFloat(0xffc00042);

            // --- NaN: the original value comes back, payload and sign kept ---
            checkD("+NaN payload -> same bits", posNaN);
            checkD("-NaN payload -> same bits", negNaN);
            checkD("canonical NaN -> same bits", Double.NaN);
            checkF("+NaN payload -> same bits", posNaNF);
            checkF("-NaN payload -> same bits", negNaNF);
            checkF("canonical NaN -> same bits", Float.NaN);

            // --- zeros: the original signed zero comes back ---
            checkD("+0.0 -> +0.0", 0.0d);
            checkD("-0.0 -> -0.0", -0.0d);
            checkF("+0.0 -> +0.0", 0.0f);
            checkF("-0.0 -> -0.0", -0.0f);

            // --- everything else maps to a signed 1.0 ---
            checkD("+x -> +1.0", 2.5d);
            checkD("-x -> -1.0", -2.5d);
            checkD("+MIN_VALUE -> +1.0", Double.MIN_VALUE);
            checkD("-MIN_VALUE -> -1.0", -Double.MIN_VALUE);
            checkD("+MAX_VALUE -> +1.0", Double.MAX_VALUE);
            checkD("-MAX_VALUE -> -1.0", -Double.MAX_VALUE);
            checkD("+inf -> +1.0", Double.POSITIVE_INFINITY);
            checkD("-inf -> -1.0", Double.NEGATIVE_INFINITY);
            checkD("+subnormal -> +1.0", Double.longBitsToDouble(1L));
            checkD("-subnormal -> -1.0", Double.longBitsToDouble(0x8000000000000001L));
            checkF("+x -> +1.0", 2.5f);
            checkF("-x -> -1.0", -2.5f);
            checkF("+MIN_VALUE -> +1.0", Float.MIN_VALUE);
            checkF("-MIN_VALUE -> -1.0", -Float.MIN_VALUE);
            checkF("+MAX_VALUE -> +1.0", Float.MAX_VALUE);
            checkF("-MAX_VALUE -> -1.0", -Float.MAX_VALUE);
            checkF("+inf -> +1.0", Float.POSITIVE_INFINITY);
            checkF("-inf -> -1.0", Float.NEGATIVE_INFINITY);
            checkF("+subnormal -> +1.0", Float.intBitsToFloat(1));
            checkF("-subnormal -> -1.0", Float.intBitsToFloat(0x80000001));

            // --- random values vs interpreted execution, bit-for-bit ---
            Random random = new Random();
            for (int i = 0; i < 1000; i++) {
                checkD("random double " + i, randomDouble(random));
            }
            for (int i = 0; i < 1000; i++) {
                checkF("random float " + i, randomFloat(random));
            }

            System.out.println("TestSignum PASSED");
        }

        // Full raw-bit space: normals, subnormals, infinities, zeros and NaNs
        // of both signs (a raw long's sign bit becomes the double's sign bit).
        private static double randomDouble(Random random) {
            switch (random.nextInt(3)) {
                case 0: return Double.longBitsToDouble(random.nextLong());
                case 1: return (random.nextDouble() - 0.5) * Math.pow(10.0, random.nextInt(600) - 300);
                default: return random.nextBoolean() ? 0.0d : -0.0d;
            }
        }

        private static float randomFloat(Random random) {
            switch (random.nextInt(3)) {
                case 0: return Float.intBitsToFloat(random.nextInt());
                case 1: return (random.nextFloat() - 0.5f) * (float) Math.pow(10.0, random.nextInt(70) - 35);
                default: return random.nextBoolean() ? 0.0f : -0.0f;
            }
        }

        public static double double_signum(double d) {
            return Math.signum(d);
        }

        public static float float_signum(float f) {
            return Math.signum(f);
        }

        // Not listed in CompileCommand, so these stay interpreted and act as
        // the reference implementation.
        public static double ref_double_signum(double d) {
            return Math.signum(d);
        }

        public static float ref_float_signum(float f) {
            return Math.signum(f);
        }
    }
}
