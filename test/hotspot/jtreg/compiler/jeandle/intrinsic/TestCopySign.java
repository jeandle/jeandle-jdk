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
 * @summary Test the intrinsic implementation of Math.copySign(double,double) and Math.copySign(float,float)
 * @requires os.arch=="amd64" | os.arch=="x86_64" | os.arch=="aarch64"
 * @library /test/lib /
 * @build jdk.test.lib.Asserts
 * @run main/othervm compiler.jeandle.intrinsic.TestCopySign
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

public class TestCopySign {
    public static void main(String[] args) throws Exception {
        // Run 1: intrinsics enabled. The subprocess checks results bit-exactly
        // and its dumped IR must contain the llvm.copysign calls.
        String intrinsicDump = Files.createTempDirectory("jeandle_test_copySign_on").toString();
        OutputAnalyzer output = runSubprocess(intrinsicDump, "-XX:+UseCopySignIntrinsic");
        output.shouldHaveExitValue(0);

        FileCheck doubleChecker = new FileCheck(intrinsicDump,
                TestWrapper.class.getMethod("double_copySign", double.class, double.class), false);
        doubleChecker.checkPattern("define hotspotcc double .*TestCopySign.*double_copySign.*");
        doubleChecker.check("call double @llvm.copysign.f64");

        FileCheck floatChecker = new FileCheck(intrinsicDump,
                TestWrapper.class.getMethod("float_copySign", float.class, float.class), false);
        floatChecker.checkPattern("define hotspotcc float .*TestCopySign.*float_copySign.*");
        floatChecker.check("call float @llvm.copysign.f32");

        output.shouldContain("is parsed as intrinsic")
              .shouldContain("java.lang.Math.copySign");

        // Run 2: control group with the intrinsic flag disabled (x86 turns it
        // on by default, so disable it explicitly). Results must stay correct
        // and the raw (pre-optimization) IR must not contain an llvm.copysign
        // call. Only the raw dump is a valid witness here: with the intrinsic
        // off, Math.copySign inlines to raw-bit operations and the LLVM
        // optimizer may legitimately fold that pattern into llvm.copysign in
        // the optimized dump.
        String controlDump = Files.createTempDirectory("jeandle_test_copySign_off").toString();
        output = runSubprocess(controlDump, "-XX:-UseCopySignIntrinsic");
        // No intrinsic parse may mention Math.copySign itself; the bit
        // conversion intrinsics of the inlined fallback body still appear.
        output.shouldHaveExitValue(0)
              .shouldNotContain("java.lang.Math.copySign");

        doubleChecker = new FileCheck(controlDump,
                TestWrapper.class.getMethod("double_copySign", double.class, double.class), false);
        doubleChecker.checkPattern("define hotspotcc double .*TestCopySign.*double_copySign.*");
        doubleChecker.checkNot("llvm.copysign");

        floatChecker = new FileCheck(controlDump,
                TestWrapper.class.getMethod("float_copySign", float.class, float.class), false);
        floatChecker.checkPattern("define hotspotcc float .*TestCopySign.*float_copySign.*");
        floatChecker.checkNot("llvm.copysign");
    }

    private static OutputAnalyzer runSubprocess(String dumpDir, String copySignFlag) throws Exception {
        ArrayList<String> commandArgs = new ArrayList<>(List.of(
                "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
                "-Xlog:jeandle=debug", "-XX:+UnlockDiagnosticVMOptions", "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dumpDir,
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::double_copySign",
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::float_copySign",
                copySignFlag,
                TestWrapper.class.getName()));

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(commandArgs);
        return ProcessTools.executeCommand(pb);
    }

    static class TestWrapper {
        static double v = Math.abs(1.0d);   // Force load java.lang.Math class

        // IEEE 754 copySign on raw bits: magnitude bits of m, sign bit of s.
        private static long ieeeCopysignBitsD(double m, double s) {
            return (Double.doubleToRawLongBits(m) & 0x7fffffffffffffffL)
                 | (Double.doubleToRawLongBits(s) & 0x8000000000000000L);
        }

        private static int ieeeCopysignBitsF(float m, float s) {
            return (Float.floatToRawIntBits(m) & 0x7fffffff)
                 | (Float.floatToRawIntBits(s) & 0x80000000);
        }

        // Compare the Jeandle-compiled result against the IEEE formula and
        // against interpreted execution, all at raw-bit level so that NaN
        // payloads and signed zeros cannot slip through.
        private static void checkD(String what, double m, double s) {
            long expected = ieeeCopysignBitsD(m, s);
            long got = Double.doubleToRawLongBits(double_copySign(m, s));
            Asserts.assertEquals(got, expected, what);
            Asserts.assertEquals(got, Double.doubleToRawLongBits(ref_double_copySign(m, s)),
                    what + " (vs interpreted)");
        }

        private static void checkF(String what, float m, float s) {
            int expected = ieeeCopysignBitsF(m, s);
            int got = Float.floatToRawIntBits(float_copySign(m, s));
            Asserts.assertEquals(got, expected, what);
            Asserts.assertEquals(got, Float.floatToRawIntBits(ref_float_copySign(m, s)),
                    what + " (vs interpreted)");
        }

        public static void main(String[] args) {
            double posNaN = Double.longBitsToDouble(0x7ff8000000000042L);  // NaN with a payload
            double negNaN = Double.longBitsToDouble(0xfff8000000000042L);
            float posNaNF = Float.intBitsToFloat(0x7fc00042);
            float negNaNF = Float.intBitsToFloat(0xffc00042);

            // --- (magnitude, NaN sign) : the NaN's own sign bit decides ---
            checkD("(+x, +NaN) -> +x", 2.5d, Double.NaN);
            checkD("(+x, -NaN) -> -x", 2.5d, negNaN);
            checkD("(-x, +NaN) -> +x", -2.5d, Double.NaN);
            checkD("(-x, -NaN) -> -x", -2.5d, negNaN);
            checkF("(+x, +NaN) -> +x", 2.5f, Float.NaN);
            checkF("(+x, -NaN) -> -x", 2.5f, negNaNF);
            checkF("(-x, +NaN) -> +x", -2.5f, Float.NaN);
            checkF("(-x, -NaN) -> -x", -2.5f, negNaNF);

            // --- (NaN magnitude, +/-sign) : NaN survives, payload kept, sign follows ---
            checkD("(NaN, +1) -> +NaN same payload", posNaN, 1.0d);
            checkD("(NaN, -1) -> -NaN same payload", posNaN, -1.0d);
            checkD("(-NaN, +1) -> +NaN same payload", negNaN, 1.0d);
            checkD("(-NaN, -1) -> -NaN same payload", negNaN, -1.0d);
            checkF("(NaN, +1) -> +NaN same payload", posNaNF, 1.0f);
            checkF("(NaN, -1) -> -NaN same payload", posNaNF, -1.0f);
            checkF("(-NaN, +1) -> +NaN same payload", negNaNF, 1.0f);
            checkF("(-NaN, -1) -> -NaN same payload", negNaNF, -1.0f);

            // --- (magnitude, +/-0.0) : the zero's sign decides ---
            checkD("(x, +0.0) -> +x", 3.0d, 0.0d);
            checkD("(x, -0.0) -> -x", 3.0d, -0.0d);
            checkD("(+0.0, -0.0) -> -0.0", 0.0d, -0.0d);
            checkD("(-0.0, +0.0) -> +0.0", -0.0d, 0.0d);
            checkF("(x, +0.0) -> +x", 3.0f, 0.0f);
            checkF("(x, -0.0) -> -x", 3.0f, -0.0f);
            checkF("(+0.0, -0.0) -> -0.0", 0.0f, -0.0f);
            checkF("(-0.0, +0.0) -> +0.0", -0.0f, 0.0f);

            // --- (magnitude, negative sign) -> -|magnitude|; positive -> +|magnitude| ---
            checkD("(x, -y) -> -|x|", 4.75d, -1e-300d);
            checkD("(-x, -y) -> -|x|", -4.75d, -Double.MAX_VALUE);
            checkD("(-x, +y) -> |x|", -4.75d, Double.MIN_VALUE);
            checkD("(+inf, -1) -> -inf", Double.POSITIVE_INFINITY, -1.0d);
            checkD("(subnormal, -1) -> -subnormal", Double.longBitsToDouble(1L), -1.0d);
            checkF("(x, -y) -> -|x|", 4.75f, -1e-30f);
            checkF("(-x, -y) -> -|x|", -4.75f, -Float.MAX_VALUE);
            checkF("(-x, +y) -> |x|", -4.75f, Float.MIN_VALUE);
            checkF("(+inf, -1) -> -inf", Float.POSITIVE_INFINITY, -1.0f);
            checkF("(subnormal, -1) -> -subnormal", Float.intBitsToFloat(1), -1.0f);

            // --- random pairs vs interpreted execution, bit-for-bit ---
            Random random = new Random();
            for (int i = 0; i < 1000; i++) {
                checkD("random double pair " + i,
                        randomDouble(random), randomDouble(random));
            }
            for (int i = 0; i < 1000; i++) {
                checkF("random float pair " + i,
                        randomFloat(random), randomFloat(random));
            }

            System.out.println("TestCopySign PASSED");
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

        public static double double_copySign(double m, double s) {
            return Math.copySign(m, s);
        }

        public static float float_copySign(float m, float s) {
            return Math.copySign(m, s);
        }

        // Not listed in CompileCommand, so these stay interpreted and act as
        // the reference implementation.
        public static double ref_double_copySign(double m, double s) {
            return Math.copySign(m, s);
        }

        public static float ref_float_copySign(float m, float s) {
            return Math.copySign(m, s);
        }
    }
}
