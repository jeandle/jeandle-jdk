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
 * @summary Test the intrinsic implementation of Math.round(float) and Math.round(double)
 * @requires os.arch=="amd64" | os.arch=="x86_64" | os.arch=="aarch64"
 * @library /test/lib /
 * @build jdk.test.lib.Asserts
 * @run main/othervm compiler.jeandle.intrinsic.TestRound
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

public class TestRound {
    public static void main(String[] args) throws Exception {
        // Run 1: intrinsics enabled. The subprocess checks results against the
        // exact specification values and its dumped IR must contain the
        // floor/sub/cmp/select + fptosi.sat chain.
        String intrinsicDump = Files.createTempDirectory("jeandle_test_round_on").toString();
        OutputAnalyzer output = runSubprocess(intrinsicDump);
        output.shouldHaveExitValue(0);

        FileCheck intChecker = new FileCheck(intrinsicDump,
                TestWrapper.class.getMethod("int_round", float.class), false);
        intChecker.checkPattern("define hotspotcc i32 .*TestRound.*int_round.*");
        intChecker.check("call float @llvm.floor.f32");
        intChecker.check("call i32 @llvm.fptosi.sat.i32.f32");
        // The intrinsic must replace the call, not sit next to it.
        intChecker.checkNot("java_lang_Math_round");

        FileCheck longChecker = new FileCheck(intrinsicDump,
                TestWrapper.class.getMethod("long_round", double.class), false);
        longChecker.checkPattern("define hotspotcc i64 .*TestRound.*long_round.*");
        longChecker.check("call double @llvm.floor.f64");
        longChecker.check("call i64 @llvm.fptosi.sat.i64.f64");
        longChecker.checkNot("java_lang_Math_round");

        output.shouldContain("is parsed as intrinsic")
              .shouldContain("java.lang.Math.round");

        // Run 2: control group with the intrinsics disabled through
        // -XX:DisableIntrinsic (Math.round has no dedicated VM flag switch).
        // Results must stay correct and the raw (pre-optimization) IR must
        // not contain the intrinsic's floor/fptosi.sat shape. Only the raw
        // dump is a valid witness here: with the intrinsics off the call
        // either stays an invoke of the outlined Math.round callee or inlines
        // its integer-bit-manipulation fallback body, neither of which uses
        // floating-point rounding, while the LLVM optimizer could still fold
        // a hand-rolled fallback into similar shapes in the optimized dump.
        String controlDump = Files.createTempDirectory("jeandle_test_round_off").toString();
        output = runSubprocess(controlDump, "-XX:DisableIntrinsic=_roundF,_roundD");
        // No intrinsic parse may mention Math.round itself.
        output.shouldHaveExitValue(0)
              .shouldNotContain("java.lang.Math.round");

        intChecker = new FileCheck(controlDump,
                TestWrapper.class.getMethod("int_round", float.class), false);
        intChecker.checkPattern("define hotspotcc i32 .*TestRound.*int_round.*");
        intChecker.checkNot("llvm.floor");
        intChecker.checkNot("llvm.fptosi.sat");

        longChecker = new FileCheck(controlDump,
                TestWrapper.class.getMethod("long_round", double.class), false);
        longChecker.checkPattern("define hotspotcc i64 .*TestRound.*long_round.*");
        longChecker.checkNot("llvm.floor");
        longChecker.checkNot("llvm.fptosi.sat");
    }

    private static OutputAnalyzer runSubprocess(String dumpDir, String... extraFlags)
            throws Exception {
        // Extra flags must precede the main class name or they become
        // program arguments instead of JVM options.
        ArrayList<String> commandArgs = new ArrayList<>(List.of(
                "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
                "-Xlog:jeandle=debug", "-XX:+UnlockDiagnosticVMOptions", "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dumpDir,
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::int_round",
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::long_round"));
        commandArgs.addAll(List.of(extraFlags));
        commandArgs.add(TestWrapper.class.getName());

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(commandArgs);
        return ProcessTools.executeCommand(pb);
    }

    static class TestWrapper {
        static double v = Math.abs(1.0d);   // Force load java.lang.Math class

        // Both the exact expected value and the interpreted reference are
        // checked: the fixed cases below pin the specification value, the
        // reference guards against a wrong-but-matching constant.
        private static void checkF(String what, float f, int expected) {
            Asserts.assertEquals(int_round(f), expected, what);
            Asserts.assertEquals(int_round(f), ref_int_round(f), what + " (vs interpreted)");
        }

        private static void checkD(String what, double d, long expected) {
            Asserts.assertEquals(long_round(d), expected, what);
            Asserts.assertEquals(long_round(d), ref_long_round(d), what + " (vs interpreted)");
        }

        public static void main(String[] args) {
            // --- ties round toward positive infinity ---
            checkF("round(2.5f) -> 3", 2.5f, 3);
            checkF("round(-2.5f) -> -2", -2.5f, -2);
            checkF("round(0.5f) -> 1", 0.5f, 1);
            checkF("round(-0.5f) -> 0", -0.5f, 0);
            checkD("round(2.5d) -> 3", 2.5d, 3L);
            checkD("round(-2.5d) -> -2", -2.5d, -2L);
            checkD("round(0.5d) -> 1", 0.5d, 1L);
            checkD("round(-0.5d) -> 0", -0.5d, 0L);

            // --- non-tie halves of both signs ---
            checkF("round(-0.4f) -> 0", -0.4f, 0);
            checkF("round(-0.6f) -> -1", -0.6f, -1);
            checkD("round(-0.4d) -> 0", -0.4d, 0L);
            checkD("round(-0.6d) -> -1", -0.6d, -1L);

            // --- the double-rounding traps of a naive floor(a + 0.5) ---
            // 0.49999999999999994d is the largest double below 0.5; its sum
            // with 0.5d rounds-to-even to 1.0d, so floor(a + 0.5) would
            // return 1 instead of the specified 0.
            checkD("round(0.49999999999999994d) -> 0", 0.49999999999999994d, 0L);
            checkF("round(0.49999997f) -> 0", 0.49999997f, 0);
            // 8388609.0f + 0.5f rounds-to-even to 8388610.0f (ulp = 1 above
            // 2^23), so floor(a + 0.5) would return 8388610.
            checkF("round(8388609.0f) -> 8388609", 8388609.0f, 8388609);
            // 4503599627370497.0d + 0.5d rounds-to-even to 4503599627370498.0d
            // (ulp = 1 above 2^52); the exact result keeps the +1 off.
            checkD("round(4503599627370497.0d) -> 4503599627370497",
                    4503599627370497.0d, 4503599627370497L);
            // Largest magnitudes whose half is still representable: ties must
            // survive at ulp == 0.5 too.
            checkF("round(4194304.5f) -> 4194305", 4194304.5f, 4194305);
            checkD("round(2251799813685249.5d) -> 2251799813685250",
                    2251799813685249.5d, 2251799813685250L);

            // --- NaN converts to 0 (both widths) ---
            checkF("round(NaNf) -> 0", Float.NaN, 0);
            checkF("round(-NaNf payload) -> 0",
                    Float.intBitsToFloat(0xffc00042), 0);
            checkD("round(NaNd) -> 0", Double.NaN, 0L);
            checkD("round(-NaNd payload) -> 0",
                    Double.longBitsToDouble(0xfff8000000000042L), 0L);

            // --- infinities and out-of-range values saturate ---
            checkF("round(+inf f) -> Integer.MAX_VALUE",
                    Float.POSITIVE_INFINITY, Integer.MAX_VALUE);
            checkF("round(-inf f) -> Integer.MIN_VALUE",
                    Float.NEGATIVE_INFINITY, Integer.MIN_VALUE);
            checkD("round(+inf d) -> Long.MAX_VALUE",
                    Double.POSITIVE_INFINITY, Long.MAX_VALUE);
            checkD("round(-inf d) -> Long.MIN_VALUE",
                    Double.NEGATIVE_INFINITY, Long.MIN_VALUE);

            checkF("round(2^31f) -> Integer.MAX_VALUE", 2.1474836E9f, Integer.MAX_VALUE);
            checkF("round(-2^31f) -> Integer.MIN_VALUE", -2.1474836E9f, Integer.MIN_VALUE);
            // The float neighbours of the int range boundaries: ulp is 128
            // just below 2^31 and 256 just above it.
            checkF("round(2^31+256f) -> Integer.MAX_VALUE", 2.1474839E9f, Integer.MAX_VALUE);
            checkF("round(-(2^31+256)f) -> Integer.MIN_VALUE", -2.1474839E9f, Integer.MIN_VALUE);
            checkF("round(2^31-128f) -> 2147483520", 2.1474835E9f, 2147483520);
            checkF("round(Float.MAX_VALUE) -> Integer.MAX_VALUE",
                    Float.MAX_VALUE, Integer.MAX_VALUE);
            checkF("round(-Float.MAX_VALUE) -> Integer.MIN_VALUE",
                    -Float.MAX_VALUE, Integer.MIN_VALUE);

            checkD("round(2^63d) -> Long.MAX_VALUE", 9.223372036854776E18, Long.MAX_VALUE);
            checkD("round(-2^63d) -> Long.MIN_VALUE", -9.223372036854776E18, Long.MIN_VALUE);
            // The double neighbours of the long range boundaries: ulp is 1024
            // just below 2^63 and 2048 just above it.
            checkD("round(2^63 + 2048d) -> Long.MAX_VALUE",
                    9.223372036854778E18, Long.MAX_VALUE);
            checkD("round(-(2^63 + 2048)d) -> Long.MIN_VALUE",
                    -9.223372036854778E18, Long.MIN_VALUE);
            checkD("round(largest double < 2^63) -> itself",
                    9.223372036854775E18, 9223372036854774784L);
            checkD("round(Double.MAX_VALUE) -> Long.MAX_VALUE",
                    Double.MAX_VALUE, Long.MAX_VALUE);
            checkD("round(-Double.MAX_VALUE) -> Long.MIN_VALUE",
                    -Double.MAX_VALUE, Long.MIN_VALUE);

            // --- zeros and subnormals ---
            checkF("round(+0.0f) -> 0", 0.0f, 0);
            checkF("round(-0.0f) -> 0", -0.0f, 0);
            checkF("round(Float.MIN_VALUE) -> 0", Float.MIN_VALUE, 0);
            checkD("round(+0.0d) -> 0", 0.0d, 0L);
            checkD("round(-0.0d) -> 0", -0.0d, 0L);
            checkD("round(Double.MIN_VALUE) -> 0", Double.MIN_VALUE, 0L);

            // --- random values vs interpreted execution, compared exactly ---
            Random random = new Random();
            for (int i = 0; i < 1000; i++) {
                float f = randomFloat(random);
                Asserts.assertEquals(int_round(f), ref_int_round(f),
                        "random float " + i + " (" + f + ")");
            }
            for (int i = 0; i < 1000; i++) {
                double d = randomDouble(random);
                Asserts.assertEquals(long_round(d), ref_long_round(d),
                        "random double " + i + " (" + d + ")");
            }

            System.out.println("TestRound PASSED");
        }

        // Full raw-bit space: normals, subnormals, infinities, zeros and NaNs
        // of both signs, plus values scaled across the int/long saturation
        // boundaries.
        private static float randomFloat(Random random) {
            switch (random.nextInt(4)) {
                case 0: return Float.intBitsToFloat(random.nextInt());
                case 1: return (random.nextFloat() - 0.5f) * 2000.0f;
                case 2: return Math.scalb((random.nextFloat() - 0.5f) * 4.0f,
                                          random.nextInt(64) + 20);  // near 2^31
                default: return random.nextBoolean() ? 0.0f : -0.0f;
            }
        }

        private static double randomDouble(Random random) {
            switch (random.nextInt(4)) {
                case 0: return Double.longBitsToDouble(random.nextLong());
                case 1: return (random.nextDouble() - 0.5) * 2000.0;
                case 2: return Math.scalb((random.nextDouble() - 0.5) * 4.0,
                                          random.nextInt(128) + 52);  // near 2^63
                default: return random.nextBoolean() ? 0.0d : -0.0d;
            }
        }

        public static int int_round(float a) {
            return Math.round(a);
        }

        public static long long_round(double a) {
            return Math.round(a);
        }

        // Not listed in CompileCommand, so these stay interpreted and act as
        // the reference implementation.
        public static int ref_int_round(float a) {
            return Math.round(a);
        }

        public static long ref_long_round(double a) {
            return Math.round(a);
        }
    }
}
