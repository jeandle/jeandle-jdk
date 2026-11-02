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
 *
 */

/*
 * @test
 * @key randomness
 * @summary Default libm policy: Jeandle compiles Math.log/log10 to the HotSpot
 *          runtime entries, bit-identical to interpreted execution
 * @requires os.arch=="amd64" | os.arch=="x86_64" | os.arch=="aarch64"
 * @library /test/lib /
 * @build jdk.test.lib.Asserts
 * @run main/othervm compiler.jeandle.intrinsic.TestLibmDefaultPolicy
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

/**
 * Regression test for the out-of-the-box libm selection policy
 * (JeandleUseHotspotIntrinsics defaults to true).
 *
 * The subprocess runs with DEFAULT flags -- neither JeandleUseHotspotIntrinsics
 * nor UseLibmIntrinsic is passed -- and must (a) compile Math.log/Math.log10
 * to the HotSpot runtime entries rather than the llvm builtins, and (b) return
 * bit-identical results to interpreted execution, closing the bit mismatches
 * Task 1's baseline run measured under the old default (llvm.* -> host glibc
 * versus the interpreter's StubRoutines fdlibm entries).
 */
public class TestLibmDefaultPolicy {
    public static void main(String[] args) throws Exception {
        boolean is_x86 = System.getProperty("os.arch").equals("amd64")
                || System.getProperty("os.arch").equals("x86_64");

        String dumpDir = Files.createTempDirectory("jeandle_test_libm_default").toString();
        OutputAnalyzer output = runSubprocess(dumpDir);

        // --- Raw IR of the wrappers: the runtime entry, never the llvm
        // builtin. This is the primary regression check -- it failed while
        // the default selected the LLVM path (the dumps contained
        // @llvm.log.f64) -- so it runs before any subprocess output checks.
        //
        // "double_log10" sorts before "double_log" in ASCII ('1' < '_'), so
        // every double_log10 dump file sorts before every double_log one and
        // the double_log checker (whose name prefix also matches the log10
        // files) resolves to its own newest file.
        FileCheck logChecker = new FileCheck(dumpDir,
                TestWrapper.class.getMethod("double_log", double.class), false);
        logChecker.checkPattern("define hotspotcc double .*TestLibmDefaultPolicy.*double_log.*");
        if (is_x86) {
            // Default UseLibmIntrinsic: the interpreter's own libm stub.
            logChecker.check("call double @StubRoutines_dlog");
        } else {
            // No platform libm stubs here, so the always-linked fdlibm port:
            // an inttoptr address constant aliased as @SharedRuntime_dlog.
            logChecker.checkPattern("call double inttoptr \\(i64 \\d+ to ptr\\)");
            logChecker.checkPatternAnywhere("@SharedRuntime_dlog = alias");
        }
        logChecker.checkNot("llvm.log.f64");
        logChecker.checkPattern("gc-leaf-function");

        FileCheck log10Checker = new FileCheck(dumpDir,
                TestWrapper.class.getMethod("double_log10", double.class), false);
        log10Checker.checkPattern("define hotspotcc double .*TestLibmDefaultPolicy.*double_log10.*");
        if (is_x86) {
            log10Checker.check("call double @StubRoutines_dlog10");
        } else {
            log10Checker.checkPattern("call double inttoptr \\(i64 \\d+ to ptr\\)");
            log10Checker.checkPatternAnywhere("@SharedRuntime_dlog10 = alias");
        }
        log10Checker.checkNot("llvm.log10.f64");
        log10Checker.checkPattern("gc-leaf-function");

        // --- Value closure evidence printed by the subprocess (it also
        // asserts everything itself, so a violation fails the exit code).
        output.shouldHaveExitValue(0)
              .shouldContain("Method `static jdouble java.lang.Math.log(jdouble)` is parsed as intrinsic")
              .shouldContain("Method `static jdouble java.lang.Math.log10(jdouble)` is parsed as intrinsic")
              .shouldContain("anchors bit-identical: log=14 log10=4")
              .shouldContain("random samples bit-identical: log=1000 log10=1000")
              .shouldContain("ZERO_MISMATCH_CLOSURE")
              .shouldContain("TestLibmDefaultPolicy PASSED (default flags)");
    }

    private static OutputAnalyzer runSubprocess(String dumpDir) throws Exception {
        // DEFAULT policy flags: no JeandleUseHotspotIntrinsics and no
        // UseLibmIntrinsic anywhere in the command line.
        ArrayList<String> commandArgs = new ArrayList<>(List.of(
                "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
                "-Xlog:jeandle=debug", "-XX:+UnlockDiagnosticVMOptions", "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dumpDir,
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::double_log",
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::double_log10"));
        commandArgs.add(TestWrapper.class.getName());

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(commandArgs);
        return ProcessTools.executeCommand(pb);
    }

    static class TestWrapper {
        // Force java.lang.Math to be loaded/initialized BEFORE anything is
        // compiled: without this the first Jeandle compile of the wrapper
        // happens while Math is uninitialized, the intrinsic is not applied,
        // and the wrapper compiles to an immediate deoptimize (observed in
        // Task 1's baseline runs). Same trick as TestFpMinMax.TestWrapper.
        static final double FORCE_MATH_LOAD = Math.log(1.0);

        static long sink; // keeps both call paths alive

        public static void main(String[] args) {
            // Trigger the -Xcomp compilation of both wrappers first.
            sink += Double.doubleToRawLongBits(double_log(2.0));
            sink += Double.doubleToRawLongBits(double_log10(2.0));
            sink += Double.doubleToRawLongBits(ref_double_log(2.0));
            sink += Double.doubleToRawLongBits(ref_double_log10(2.0));

            checkAnchors();
            checkRandomSamples();
            long logChecked = sweep(true);
            long log10Checked = sweep(false);
            System.out.println("ZERO_MISMATCH_CLOSURE log_checked=" + logChecked
                    + " log10_checked=" + log10Checked + " sink=" + sink);
            System.out.println("TestLibmDefaultPolicy PASSED (default flags)");
        }

        // --- comparison helpers ---

        private static String bits(double d) {
            return "0x" + Long.toHexString(Double.doubleToRawLongBits(d));
        }

        // Bit-exact comparison, NaN payloads and zero signs included.
        private static void checkBits(String what, double computed, double ref) {
            Asserts.assertTrue(
                    Double.doubleToRawLongBits(computed) == Double.doubleToRawLongBits(ref),
                    what + ": compiled " + computed + " (" + bits(computed)
                            + ") != interpreted " + ref + " (" + bits(ref) + ")");
        }

        // --- the Task 1 regression anchors ---
        //
        // Under the old default (JeandleUseHotspotIntrinsics=false) the
        // compiled wrappers called llvm.log/llvm.log10, which LLVM lowered to
        // the host glibc, while the interpreted references went through the
        // interpreter's StubRoutines fdlibm entries. Task 1's baseline sweep
        // (seed 20261102, 478671 inputs) measured 14 one-ulp Math.log
        // mismatches and, for Math.log10, 6478 finite mismatches of which
        // these 4 were the only two-ulp ones, plus 82463 NaN-payload
        // differences. The inputs and the interpreter's answer bits below are
        // copied from that evidence:
        // docs/reports/2026-11-02-numeric-bit-intrinsics/baseline-before/driver-full.out
        // With the default flipped to the runtime entries, compiled and
        // interpreted execution call the same routine, so every anchor must
        // now be bit-identical -- and equal to the recorded fdlibm value,
        // which also rules out both sides drifting off together.

        // The 14 Math.log one-ulp mismatch inputs.
        private static final long[] LOG_ANCHOR_INPUTS = {
            0x3ff0df91071c30b4L, 0x3ff0e4349493d13fL, 0x3ff0d6476daf300bL,
            0x3ff02b4ac0d6319aL, 0x3ff0cd2481aaf21aL, 0x3ff0424a1ce8fc61L,
            0x3ff035b8c2167e4aL, 0x3ff06861268e9b6cL, 0x3ff0b3e32354bfa3L,
            0x3ff031892920b14cL, 0x3ff0be48e2087b26L, 0x3ff029dae256ee03L,
            0x3ff0fb064857e1fcL, 0x401821077501340cL,
        };
        // The interpreter's recorded Math.log answers for the inputs above.
        private static final long[] LOG_ANCHOR_INTERP_EXPECTED = {
            0x3fab35b64b169e20L, 0x3fabc265ccaa9dc9L, 0x3faa1b97421b5567L,
            0x3f85884c0952e668L, 0x3fa905771991c1e6L, 0x3f907090514b5e85L,
            0x3f8aaface93772fcL, 0x3f99c496bcba2c01L, 0x3fa601924722c8d9L,
            0x3f889e8bcfdc291aL, 0x3fa73fe7b68eeaccL, 0x3f84d2412c896899L,
            0x3fae74416f06395fL, 0x3ffcc101d9d75ab1L,
        };
        // The 4 Math.log10 inputs that mismatched by two ulp (the other 6474
        // finite log10 mismatches were one-ulp).
        private static final long[] LOG10_TWO_ULP_ANCHOR_INPUTS = {
            0x3ff092e0cfff6a70L, 0x3ff088b763aa25dcL,
            0x3ff0119211124877L, 0x3ff024d0ad77ffaaL,
        };
        // The interpreter's recorded Math.log10 answers for the inputs above.
        private static final long[] LOG10_TWO_ULP_ANCHOR_INTERP_EXPECTED = {
            0x3f8f55eda71adcb9L, 0x3f8d33f00415fa73L,
            0x3f5e753af10e2cb0L, 0x3f6fd599d99425a7L,
        };

        private static void checkAnchors() {
            for (int i = 0; i < LOG_ANCHOR_INPUTS.length; i++) {
                double x = Double.longBitsToDouble(LOG_ANCHOR_INPUTS[i]);
                double computed = double_log(x);
                checkBits("log anchor " + i + " (" + x + ")", computed, ref_double_log(x));
                Asserts.assertEquals(Double.doubleToRawLongBits(computed),
                        LOG_ANCHOR_INTERP_EXPECTED[i],
                        "log anchor " + i + ": expected the recorded fdlibm bits "
                                + Long.toHexString(LOG_ANCHOR_INTERP_EXPECTED[i]));
            }
            for (int i = 0; i < LOG10_TWO_ULP_ANCHOR_INPUTS.length; i++) {
                double x = Double.longBitsToDouble(LOG10_TWO_ULP_ANCHOR_INPUTS[i]);
                double computed = double_log10(x);
                checkBits("log10 two-ulp anchor " + i + " (" + x + ")", computed, ref_double_log10(x));
                Asserts.assertEquals(Double.doubleToRawLongBits(computed),
                        LOG10_TWO_ULP_ANCHOR_INTERP_EXPECTED[i],
                        "log10 two-ulp anchor " + i + ": expected the recorded fdlibm bits "
                                + Long.toHexString(LOG10_TWO_ULP_ANCHOR_INTERP_EXPECTED[i]));
            }
            System.out.println("anchors bit-identical: log=" + LOG_ANCHOR_INPUTS.length
                    + " log10=" + LOG10_TWO_ULP_ANCHOR_INPUTS.length);
        }

        // --- random samples: bit-identical on the default path ---

        private static void checkRandomSamples() {
            Random random = new Random();
            for (int i = 0; i < 1000; i++) {
                double x = randomDouble(random);
                checkBits("random log " + i + " (" + x + ")",
                        double_log(x), ref_double_log(x));
            }
            for (int i = 0; i < 1000; i++) {
                double x = randomDouble(random);
                checkBits("random log10 " + i + " (" + x + ")",
                        double_log10(x), ref_double_log10(x));
            }
            System.out.println("random samples bit-identical: log=1000 log10=1000");
        }

        // Full raw-bit space plus moderate magnitudes: normals, subnormals,
        // infinities, zeros and NaNs of both signs (NaN payload identity
        // included, which is where log10 of a negative input diverged).
        private static double randomDouble(Random random) {
            switch (random.nextInt(4)) {
                case 0: return Double.longBitsToDouble(random.nextLong());
                case 1: return (random.nextDouble() - 0.5) * 200.0;
                case 2: return Math.scalb((random.nextDouble() - 0.5) * 4.0,
                                          random.nextInt(128) + 52);
                default: return random.nextBoolean() ? 0.0d : -0.0d;
            }
        }

        // --- the full Task 1 baseline sweep, re-run against the fixed build ---
        //
        // Same input generator and seed (20261102) as Task 1's temporary
        // BaselineLibmBits driver: 478671 inputs covering specials, every
        // biased exponent, power-of-two and power-of-ten neighbors, values
        // near 1.0, random normals, subnormals and raw bit patterns. The
        // baseline measured 14 + 88941 raw-bit mismatches (log / log10);
        // against the fixed default the identical sweep must produce ZERO.

        private static long sweep(boolean isLog) {
            String op = isLog ? "Math.log" : "Math.log10";
            double[] xs = buildBaselineInputs();
            long mismatch = 0;
            for (double x : xs) {
                long c = Double.doubleToRawLongBits(isLog ? double_log(x) : double_log10(x));
                long i = Double.doubleToRawLongBits(isLog ? ref_double_log(x) : ref_double_log10(x));
                sink += c ^ i;
                if (c != i) {
                    mismatch++;
                    if (mismatch <= 10) {
                        System.out.println(String.format(
                            "MISMATCH %s input=0x%016x compiled=0x%016x interp=0x%016x",
                            op, Double.doubleToRawLongBits(x), c, i));
                    }
                }
            }
            System.out.println("sweep " + op + " checked=" + xs.length
                    + " total_raw_bit_mismatch=" + mismatch);
            Asserts.assertTrue(mismatch == 0,
                    op + ": " + mismatch + " of " + xs.length
                            + " inputs differ in raw bits between compiled and interpreted execution");
            return xs.length;
        }

        static double[] buildBaselineInputs() {
            Random rnd = new Random(BASELINE_SEED);
            List<Double> l = new ArrayList<>();

            // 1) special values
            double[] specials = {
                0.0, -0.0, 1.0, -1.0, 2.0, 0.5, Math.E, 10.0, 100.0, 1000.0, 1e10, 1e-10,
                Double.MIN_VALUE, Double.MIN_NORMAL, Double.MAX_VALUE,
                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN,
                0.1, 0.2, 0.3, 1.0 / 3.0, 2.0 / 3.0, 3.0 / 7.0, 1e300, 1e-300, 1e308, 1e-308,
                Math.PI, Math.sqrt(2.0), 1.0 / Math.sqrt(2.0),
            };
            for (double d : specials) { l.add(d); l.add(-d); }

            // 2) every biased exponent with a few interesting mantissas
            long[] mantissas = {0L, 1L, 2L, 0x0008_0000L, 0x000F_FFFFL,
                                0x0001_2345_6789_ABCDL & 0xFFFFFFFFFFFFL};
            for (int e = 0; e <= 2047; e++) {
                for (long m : mantissas) {
                    l.add(Double.longBitsToDouble(((long) e << 52) | m));
                    l.add(Double.longBitsToDouble(0x8000_0000_0000_0000L | ((long) e << 52) | m));
                }
            }

            // 3) neighbors of every normal power of two (fdlibm reduction
            //    switches here)
            for (int k = -1022; k <= 1023; k++) {
                double p = Double.longBitsToDouble(((long) (1023 + k)) << 52);
                l.add(p);
                l.add(Math.nextUp(p));
                l.add(Math.nextDown(p));
                l.add(Math.nextUp(Math.nextUp(p)));
                l.add(Math.nextDown(Math.nextDown(p)));
            }

            // 4) neighbors of powers of ten (log10 reduction / exactness
            //    region)
            for (int k = -300; k <= 300; k++) {
                double p;
                try {
                    p = Double.parseDouble("1e" + k);
                } catch (NumberFormatException e) { continue; }
                l.add(p);
                l.add(Math.nextUp(p));
                l.add(Math.nextDown(p));
            }

            // 5) values near 1.0 (log(x) ~ 0, hardest rounding region)
            for (int i = 0; i < 20000; i++) {
                long bits = 0x3FF0_0000_0000_0000L + (rnd.nextLong() & 0x000F_FFFF_FFFF_FFFFL) / 16L;
                l.add(Double.longBitsToDouble(bits));
                l.add(Double.longBitsToDouble(bits | 0x8000_0000_0000_0000L));
            }

            // 6) random normals spanning every exponent (mantissa uniform)
            for (int i = 0; i < 300000; i++) {
                int e = 1 + rnd.nextInt(0x7FE);
                long m = rnd.nextLong() & 0x000F_FFFF_FFFF_FFFFL;
                l.add(Double.longBitsToDouble(((long) e << 52) | m));
            }

            // 7) random subnormals
            for (int i = 0; i < 2000; i++) {
                l.add(Double.longBitsToDouble(rnd.nextLong() & 0x000F_FFFF_FFFF_FFFFL));
            }

            // 8) completely random bit patterns (covers negative/Inf/NaN
            //    inputs)
            for (int i = 0; i < 100000; i++) {
                l.add(Double.longBitsToDouble(rnd.nextLong()));
            }

            double[] xs = new double[l.size()];
            for (int i = 0; i < xs.length; i++) xs[i] = l.get(i);
            return xs;
        }

        static final long BASELINE_SEED = 20261102L;

        // Compiled by Jeandle (see the CompileCommand list above).
        public static double double_log(double a) {
            return Math.log(a);
        }

        public static double double_log10(double a) {
            return Math.log10(a);
        }

        // Not listed in the CompileCommand, so these stay interpreted and act
        // as the reference implementation (the interpreter's Math.* entries).
        public static double ref_double_log(double a) {
            return Math.log(a);
        }

        public static double ref_double_log10(double a) {
            return Math.log10(a);
        }
    }
}
