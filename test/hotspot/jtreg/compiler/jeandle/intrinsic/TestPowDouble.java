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
 * You should have received a copy of the GNU General Public License
 * version 2 along with this work; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 */

/*
 * @test
 * @key randomness
 * @summary Test the intrinsic implementation of Math.pow(double, double)
 * @requires os.arch=="amd64" | os.arch=="x86_64" | os.arch=="aarch64"
 * @library /test/lib /
 * @build jdk.test.lib.Asserts
 * @run main/othervm compiler.jeandle.intrinsic.TestPowDouble
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

public class TestPowDouble {
    public static void main(String[] args) throws Exception {
        boolean is_x86 = System.getProperty("os.arch").equals("amd64")
                || System.getProperty("os.arch").equals("x86_64");

        // Run 1: dual-path with the platform libm stub. On x86 (default
        // UseLibmIntrinsic) StubRoutines::dpow exists and both the compiled
        // code and the interpreter call it, so results must be bit-exact
        // against the interpreted reference. On aarch64 there is no dpow
        // stub, so the compiled code falls back to SharedRuntime::dpow --
        // which is again what the interpreter calls, still bit-exact.
        String stubDump = Files.createTempDirectory("jeandle_test_pow_stub").toString();
        OutputAnalyzer output;
        if (is_x86) {
            output = runSubprocess(stubDump, "stub",
                    "-XX:+JeandleUseHotspotIntrinsics", "-XX:+UseLibmIntrinsic");
        } else {
            output = runSubprocess(stubDump, "stub", "-XX:+JeandleUseHotspotIntrinsics");
        }
        output.shouldHaveExitValue(0);
        checkDumps(stubDump, is_x86 ? MODE_STUB : MODE_SHARED);

        // Run 2: x86 with the libm stubs disabled. Both the compiled code
        // and the interpreter now go through SharedRuntime::dpow (the fdlibm
        // port), still bit-exact.
        if (is_x86) {
            String sharedDump = Files.createTempDirectory("jeandle_test_pow_shared").toString();
            output = runSubprocess(sharedDump, "shared",
                    "-XX:+JeandleUseHotspotIntrinsics",
                    "-XX:-UseLibmIntrinsic");
            output.shouldHaveExitValue(0);
            checkDumps(sharedDump, MODE_SHARED);
        }

        // Run 3: LLVM-only mode (-XX:-JeandleUseHotspotIntrinsics). The
        // compiled code calls llvm.pow, which lowers to the host libm, while
        // the reference stays on the fdlibm-based interpreter entry: random
        // results are compared with a 2 ulp tolerance, the special-value
        // table still exactly.
        String llvmDump = Files.createTempDirectory("jeandle_test_pow_llvm").toString();
        output = runSubprocess(llvmDump, "llvm",
                "-XX:-JeandleUseHotspotIntrinsics");
        output.shouldHaveExitValue(0);
        checkDumps(llvmDump, MODE_LLVM);
    }

    // Verify the raw (pre-optimization) IR dumps of the wrappers.
    private static final int MODE_STUB = 0;    // StubRoutines::dpow (x86 libm stubs on)
    private static final int MODE_SHARED = 1;  // SharedRuntime::dpow (stubs off)
    private static final int MODE_LLVM = 2;    // llvm.pow (-JeandleUseHotspotIntrinsics)

    private static void checkDumps(String dumpDir, int mode) throws Exception {
        // double_pow(double, double): the plain dual-path call -- the stub
        // where generated, else SharedRuntime (an inttoptr address constant
        // aliased as @SharedRuntime_dpow), else llvm.pow.
        // double_pow's dump lookup matches the files of the other wrappers
        // too (file names are matched by method-name prefix), so pick this
        // method's file explicitly: ASCII order of the suffixes sorts the
        // six files as [double_pow2, double_pow, double_pow_half,
        // double_pow_negzero, double_pow_phi, double_pow_zero] regardless
        // of the per-run dump hash.
        FileCheck powChecker = new FileCheck(dumpDir,
                TestWrapper.class.getMethod("double_pow", double.class, double.class),
                false, 1);
        powChecker.checkPattern("define hotspotcc double .*TestPowDouble.*double_pow.*");
        if (mode == MODE_STUB) {
            powChecker.check("call double @StubRoutines_dpow");
            powChecker.check("ret double");
            powChecker.check("gc-leaf-function");
        } else if (mode == MODE_SHARED) {
            powChecker.checkPattern("call double inttoptr \\(i64 \\d+ to ptr\\)");
            // The SharedRuntime_dpow alias sits in the globals section above
            // the define, outside the sequential cursor's reach.
            powChecker.checkPatternAnywhere("@SharedRuntime_dpow = alias");
        } else {
            powChecker.check("call double @llvm.pow.f64");
            powChecker.checkNot("StubRoutines_dpow");
            powChecker.checkNot("SharedRuntime_dpow");
        }

        // double_pow2(double): in the raw IR the literal 2.0 exponent is
        // still an argument of the pow callee the mode selected -- the
        // constant-exponent identities are folded on the LLVM side
        // (LibmIdentityFolding on the runtime call, LLVM's own libcall
        // simplifier on llvm.pow), not by the frontend. In the optimized
        // IR only the multiply remains, in every mode.
        FileCheck pow2Checker = new FileCheck(dumpDir,
                TestWrapper.class.getMethod("double_pow2", double.class), false);
        pow2Checker.checkPattern("define hotspotcc double .*TestPowDouble.*double_pow2.*");
        if (mode == MODE_STUB) {
            pow2Checker.check("call double @StubRoutines_dpow");
        } else if (mode == MODE_SHARED) {
            pow2Checker.checkPattern("call double inttoptr \\(i64 \\d+ to ptr\\)");
        } else {
            pow2Checker.check("call double @llvm.pow.f64");
            pow2Checker.checkNot("StubRoutines_dpow");
            pow2Checker.checkNot("SharedRuntime_dpow");
        }
        pow2Checker.checkNot("fmul double");

        FileCheck pow2OptChecker = new FileCheck(dumpDir,
                TestWrapper.class.getMethod("double_pow2", double.class), true);
        // The optimized define carries added return attributes (e.g.
        // noundef on a constant result), so allow attributes between the
        // calling convention and the type.
        pow2OptChecker.checkPattern("define hotspotcc.*double .*TestPowDouble.*double_pow2.*");
        pow2OptChecker.check("fmul double");
        // checkNot scans the whole file, so the negatives pin the call forms
        // and leave the module-level declaration/alias lines alone.
        pow2OptChecker.checkNot("call double @llvm.pow.f64");
        pow2OptChecker.checkNot("call double @StubRoutines_dpow");
        pow2OptChecker.checkNot("call double inttoptr");

        // double_pow_zero(double) / double_pow_negzero(double):
        // pow(x, +-0.0) -> 1.0 is one of the exact identities (fdlibm tests
        // (iy|ly)==0 before its NaN check, so even a NaN base yields 1.0).
        // Raw IR keeps the call; optimized IR is just the constant.
        FileCheck powZeroChecker = new FileCheck(dumpDir,
                TestWrapper.class.getMethod("double_pow_zero", double.class), false);
        powZeroChecker.checkPattern("define hotspotcc double .*TestPowDouble.*double_pow_zero.*");
        if (mode == MODE_STUB) {
            powZeroChecker.check("call double @StubRoutines_dpow");
        } else if (mode == MODE_SHARED) {
            powZeroChecker.checkPattern("call double inttoptr \\(i64 \\d+ to ptr\\)");
        } else {
            powZeroChecker.check("call double @llvm.pow.f64");
        }
        FileCheck powZeroOptChecker = new FileCheck(dumpDir,
                TestWrapper.class.getMethod("double_pow_zero", double.class), true);
        powZeroOptChecker.checkPattern("define hotspotcc.*double .*TestPowDouble.*double_pow_zero.*");
        // Substring check: the literal '+' in 1.000000e+00 must not go
        // through the regex path of checkPattern.
        powZeroOptChecker.check("ret double 1.000000e+00");
        powZeroOptChecker.checkNot("call double @llvm.pow.f64");
        powZeroOptChecker.checkNot("call double @StubRoutines_dpow");
        powZeroOptChecker.checkNot("call double inttoptr");
        FileCheck powNegZeroOptChecker = new FileCheck(dumpDir,
                TestWrapper.class.getMethod("double_pow_negzero", double.class), true);
        powNegZeroOptChecker.checkPattern(
                "define hotspotcc.*double .*TestPowDouble.*double_pow_negzero.*");
        powNegZeroOptChecker.check("ret double 1.000000e+00");
        powNegZeroOptChecker.checkNot("call double @llvm.pow.f64");
        powNegZeroOptChecker.checkNot("call double @StubRoutines_dpow");
        powNegZeroOptChecker.checkNot("call double inttoptr");

        // double_pow_phi(double, boolean): the exponent merge hides the
        // 2.0 from the frontend, so the raw IR calls pow with a phi
        // operand; once optimization collapses the phi to the constant,
        // the LLVM-side identity folding produces the multiply.
        FileCheck powPhiChecker = new FileCheck(dumpDir,
                TestWrapper.class.getMethod("double_pow_phi", double.class, boolean.class), false);
        powPhiChecker.checkPattern("define hotspotcc double .*TestPowDouble.*double_pow_phi.*");
        if (mode == MODE_STUB) {
            powPhiChecker.check("call double @StubRoutines_dpow");
        } else if (mode == MODE_SHARED) {
            powPhiChecker.checkPattern("call double inttoptr \\(i64 \\d+ to ptr\\)");
        } else {
            powPhiChecker.check("call double @llvm.pow.f64");
        }
        powPhiChecker.checkNot("fmul double");
        FileCheck powPhiOptChecker = new FileCheck(dumpDir,
                TestWrapper.class.getMethod("double_pow_phi", double.class, boolean.class), true);
        powPhiOptChecker.checkPattern("define hotspotcc.*double .*TestPowDouble.*double_pow_phi.*");
        powPhiOptChecker.check("fmul double");
        powPhiOptChecker.checkNot("call double @llvm.pow.f64");
        powPhiOptChecker.checkNot("call double @StubRoutines_dpow");
        powPhiOptChecker.checkNot("call double inttoptr");

        // The frontend keeps the runtime call for constant exponents;
        // LLVM specializes the half exponent after inlining.
        FileCheck powHalfChecker = new FileCheck(dumpDir,
                TestWrapper.class.getMethod("double_pow_half", double.class), false);
        powHalfChecker.checkPattern("define hotspotcc double .*TestPowDouble.*double_pow_half.*");
        if (mode == MODE_STUB) {
            powHalfChecker.check("call double @StubRoutines_dpow");
            powHalfChecker.checkNot("llvm.sqrt");
        } else if (mode == MODE_SHARED) {
            powHalfChecker.checkPattern("call double inttoptr \\(i64 \\d+ to ptr\\)");
            powHalfChecker.checkNot("llvm.sqrt");
            powHalfChecker.checkPatternAnywhere("@SharedRuntime_dpow = alias");
        } else {
            powHalfChecker.check("call double @llvm.pow.f64");
            powHalfChecker.checkNot("llvm.sqrt");
        }
        if (mode != MODE_LLVM) {
            FileCheck halfOpt = new FileCheck(dumpDir,
                    TestWrapper.class.getMethod("double_pow_half", double.class), true);
            halfOpt.checkPatternAnywhere("call double @llvm.sqrt.f64");
            halfOpt.checkPatternAnywhere(mode == MODE_STUB
                    ? "call double @StubRoutines_dpow"
                    : "call double inttoptr \\(i64 \\d+ to ptr\\)");
        }
    }

    private static OutputAnalyzer runSubprocess(String dumpDir, String mode, String... extraFlags)
            throws Exception {
        // Extra flags must precede the main class name or they become
        // program arguments instead of JVM options. The mode argument after
        // the main class selects the subprocess' comparison strictness.
        ArrayList<String> commandArgs = new ArrayList<>(List.of(
                "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
                "-Xlog:jeandle=debug", "-XX:+UnlockDiagnosticVMOptions", "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dumpDir,
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::double_pow",
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::double_pow2",
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::double_pow_half",
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::double_pow_zero",
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::double_pow_negzero",
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::double_pow_phi"));
        commandArgs.addAll(List.of(extraFlags));
        commandArgs.add(TestWrapper.class.getName());
        commandArgs.add(mode);

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(commandArgs);
        return ProcessTools.executeCommand(pb);
    }

    static class TestWrapper {
        static double v = Math.abs(1.0d);   // Force load java.lang.Math class

        public static void main(String[] args) {
            // In the stub and SharedRuntime modes the compiled code and the
            // interpreted reference call the very same dpow routine, so every
            // result must match bit for bit. In llvm mode the compiled side
            // uses the host libm, so only the special-value table is exact
            // and random pairs get a 2 ulp tolerance.
            boolean bitExact = !args[0].equals("llvm");
            checkSpecialTable(bitExact);
            checkRandomPairs(bitExact);
            checkPow2(bitExact);
            checkPowHalf(bitExact);
            checkPowZero(bitExact);
            checkPowPhi(bitExact);
            System.out.println("TestPowDouble PASSED (" + args[0] + ")");
        }

        // --- comparison helpers ---

        private static String bits(double d) {
            return "0x" + Long.toHexString(Double.doubleToRawLongBits(d));
        }

        // Bit-exact comparison against the interpreted reference, NaN
        // payloads and zero signs included.
        private static void checkBits(String what, double computed, double ref) {
            Asserts.assertTrue(
                    Double.doubleToRawLongBits(computed) == Double.doubleToRawLongBits(ref),
                    what + ": compiled " + computed + " (" + bits(computed)
                            + ") != interpreted " + ref + " (" + bits(ref) + ")");
        }

        // Compare against the specification value: exact bits for everything
        // except NaN, where any NaN payload is acceptable.
        private static void checkSpec(String what, double computed, double expected) {
            if (Double.isNaN(expected)) {
                Asserts.assertTrue(Double.isNaN(computed),
                        what + ": expected NaN, got " + computed + " (" + bits(computed) + ")");
            } else {
                Asserts.assertTrue(
                        Double.doubleToRawLongBits(computed) == Double.doubleToRawLongBits(expected),
                        what + ": expected " + expected + " (" + bits(expected) + "), got "
                                + computed + " (" + bits(computed) + ")");
            }
        }

        // Value comparison with Double.equals semantics (NaN == NaN, but
        // +0.0 != -0.0), used for the llvm-mode table where the host libm
        // may choose a different NaN payload than fdlibm.
        private static void checkValue(String what, double computed, double expected) {
            // Double.compare uses doubleToLongBits semantics: all NaNs
            // compare equal, but +0.0 and -0.0 do not.
            Asserts.assertTrue(Double.compare(computed, expected) == 0,
                    what + ": expected " + expected + " (" + bits(expected) + "), got "
                            + computed + " (" + bits(computed) + ")");
        }

        // 2 ulp tolerance against the interpreted reference for the llvm
        // mode, where llvm.pow lowers to the host libm's pow.
        private static void checkUlp(String what, double computed, double ref) {
            if (Double.isNaN(ref)) {
                Asserts.assertTrue(Double.isNaN(computed), what + ": expected NaN, got " + computed);
                return;
            }
            if (Double.isInfinite(ref) || Double.isInfinite(computed) || ref == 0.0) {
                Asserts.assertEquals(computed, ref, what);
                return;
            }
            double error = Math.abs(computed - ref);
            double tolerance = 2 * StrictMath.ulp(ref);
            Asserts.assertTrue(error <= tolerance,
                    what + ": computed " + computed + " (" + bits(computed) + ") vs interpreted "
                            + ref + " (" + bits(ref) + "), error " + error + " > " + tolerance);
        }

        private static void check(String what, double computed, double expected, double ref,
                                  boolean bitExact) {
            checkSpec(what, computed, expected);
            if (bitExact) {
                checkBits(what, computed, ref);
            } else if (Double.isNaN(expected) || Double.isNaN(ref)) {
                Asserts.assertTrue(Double.isNaN(computed) && Double.isNaN(ref), what);
            } else {
                checkUlp(what, computed, ref);
            }
        }

        // --- Math.pow(a, b) special-value table from the javadoc ---

        private static final double[][] SPEC_TABLE = {
            // y == +/-0 returns 1, even for a NaN base.
            {+0.0d, +0.0d, 1.0d},
            {-0.0d, +0.0d, 1.0d},
            {+0.0d, -0.0d, 1.0d},
            {-0.0d, -0.0d, 1.0d},
            {Double.NaN, +0.0d, 1.0d},
            {Double.NaN, -0.0d, 1.0d},
            // y == 1 returns x.
            {3.7d, 1.0d, 3.7d},
            {-3.7d, 1.0d, -3.7d},
            {Double.POSITIVE_INFINITY, 1.0d, Double.POSITIVE_INFINITY},
            // A NaN operand (with the other one non-zero) returns NaN.
            {Double.NaN, 2.0d, Double.NaN},
            {Double.NaN, Double.NaN, Double.NaN},
            // |x| == 1 with infinite y (or a NaN exponent on +1) is NaN.
            // This is the one spec rule the host libm does not implement
            // (IEEE 754 says pow(1, y) is 1 regardless of y), pinned
            // separately in llvm mode below.
            {1.0d, Double.NaN, Double.NaN},
            {1.0d, Double.POSITIVE_INFINITY, Double.NaN},
            {-1.0d, Double.POSITIVE_INFINITY, Double.NaN},
            {1.0d, Double.NEGATIVE_INFINITY, Double.NaN},
            {-1.0d, Double.NEGATIVE_INFINITY, Double.NaN},
            // |x| > 1, |x| < 1 with infinite y.
            {0.9d, Double.POSITIVE_INFINITY, +0.0d},
            {0.9d, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY},
            {-0.9d, Double.POSITIVE_INFINITY, +0.0d},
            {-0.9d, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY},
            {1.1d, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY},
            {1.1d, Double.NEGATIVE_INFINITY, +0.0d},
            {-1.1d, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY},
            {-1.1d, Double.NEGATIVE_INFINITY, +0.0d},
            // x == +0 with positive/negative, odd/even y.
            {+0.0d, 2.0d, +0.0d},
            {+0.0d, -2.0d, Double.POSITIVE_INFINITY},
            {+0.0d, 3.0d, +0.0d},
            {+0.0d, -3.0d, Double.POSITIVE_INFINITY},
            // x == -0: odd integer exponents keep the sign, others do not.
            // The -0.0 result for (-0.0)^3 is sign-sensitive and pins the
            // slow path's zero handling.
            {-0.0d, 3.0d, -0.0d},
            {-0.0d, 2.0d, +0.0d},
            {-0.0d, -3.0d, Double.NEGATIVE_INFINITY},
            {-0.0d, -2.0d, Double.POSITIVE_INFINITY},
            // Non-integer exponent of a negative zero behaves like +0.
            {-0.0d, 0.5d, +0.0d},
            // x == +infinity.
            {Double.POSITIVE_INFINITY, 0.5d, Double.POSITIVE_INFINITY},
            {Double.POSITIVE_INFINITY, -1.0d, +0.0d},
            {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY},
            {Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, +0.0d},
            // x == -infinity: odd integer exponents keep the sign.
            {Double.NEGATIVE_INFINITY, 3.0d, Double.NEGATIVE_INFINITY},
            {Double.NEGATIVE_INFINITY, 2.0d, Double.POSITIVE_INFINITY},
            {Double.NEGATIVE_INFINITY, -3.0d, -0.0d},
            {Double.NEGATIVE_INFINITY, 0.5d, Double.POSITIVE_INFINITY},
            // Negative base with even / odd / non-integer exponents.
            {2.0d, 2.0d, 4.0d},
            {-2.0d, 3.0d, -8.0d},
            {-2.0d, 2.0d, 4.0d},
            {-2.0d, -3.0d, -0.125d},
            {-2.0d, 0.5d, Double.NaN},
            {-1.0d, 0.5d, Double.NaN},
            // Overflow of an exactly-representable product.
            {1e300d, 2.0d, Double.POSITIVE_INFINITY},
            {1e-300d, 2.0d, +0.0d},
        };

        // The host libm answers pow(1, y) with 1.0 for every non-finite y
        // (an IEEE 754 rule the Math.pow specification does not follow), and
        // pow(-1, +-inf) likewise. The llvm mode cannot match the spec on
        // those inputs; everything else in the table is exact there.
        private static boolean llvmDiverges(double base, double exp) {
            return Double.isNaN(exp) || Double.isInfinite(exp)
                    ? base == 1.0d || (base == -1.0d && Double.isInfinite(exp))
                    : false;
        }

        private static void checkSpecialTable(boolean bitExact) {
            for (int i = 0; i < SPEC_TABLE.length; i++) {
                double base = SPEC_TABLE[i][0];
                double exp = SPEC_TABLE[i][1];
                double expected = SPEC_TABLE[i][2];
                if (!bitExact && llvmDiverges(base, exp)) {
                    continue;
                }
                check("pow[" + i + "](" + base + ", " + exp + ")",
                        double_pow(base, exp), expected, ref_double_pow(base, exp), bitExact);
            }

            if (!bitExact) {
                // llvm.pow follows the host libm, whose pow(1, y) is 1.0 for
                // every non-finite y where the Math.pow specification says
                // NaN. Pin the documented divergence of the LLVM-backed mode
                // for all five skipped rows.
                Asserts.assertEquals(double_pow(1.0d, Double.NaN), 1.0d,
                        "llvm.pow(1, NaN) is the libm value 1.0, not the spec NaN");
                Asserts.assertEquals(double_pow(1.0d, Double.POSITIVE_INFINITY), 1.0d,
                        "llvm.pow(1, +inf) is the libm value 1.0, not the spec NaN");
                Asserts.assertEquals(double_pow(1.0d, Double.NEGATIVE_INFINITY), 1.0d,
                        "llvm.pow(1, -inf) is the libm value 1.0, not the spec NaN");
                Asserts.assertEquals(double_pow(-1.0d, Double.POSITIVE_INFINITY), 1.0d,
                        "llvm.pow(-1, +inf) is the libm value 1.0, not the spec NaN");
                Asserts.assertEquals(double_pow(-1.0d, Double.NEGATIVE_INFINITY), 1.0d,
                        "llvm.pow(-1, -inf) is the libm value 1.0, not the spec NaN");
            }
        }

        private static void checkRandomPairs(boolean bitExact) {
            Random random = new Random();
            for (int i = 0; i < 1000; i++) {
                double base = randomDouble(random);
                double exp = randomDouble(random);
                String what = "random pow " + i + " (" + base + ", " + exp + ")";
                if (bitExact) {
                    checkBits(what, double_pow(base, exp), ref_double_pow(base, exp));
                } else {
                    checkUlp(what, double_pow(base, exp), ref_double_pow(base, exp));
                }
            }
        }

        // Full raw-bit space plus moderate magnitudes: normals, subnormals,
        // infinities, zeros and NaNs of both signs.
        private static double randomDouble(Random random) {
            switch (random.nextInt(4)) {
                case 0: return Double.longBitsToDouble(random.nextLong());
                case 1: return (random.nextDouble() - 0.5) * 200.0;
                case 2: return Math.scalb((random.nextDouble() - 0.5) * 4.0,
                                          random.nextInt(128) + 52);
                default: return random.nextBoolean() ? 0.0d : -0.0d;
            }
        }

        // --- Math.pow(x, 2.0): the x*x fold ---

        private static void checkPow2(boolean bitExact) {
            // x*x is the exact result for every input (the same arithmetic
            // the fdlibm port performs), so even the llvm mode compares raw
            // bits against the interpreted reference.
            double[] values = {
                +0.0d, -0.0d, 1.0d, -1.0d, 0.5d, -0.5d, 1.5d, -1.5d,
                Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                1e300d, Double.MAX_VALUE, Double.MIN_VALUE, 1e-162d, 5e-324d,
            };
            for (int i = 0; i < values.length; i++) {
                double x = values[i];
                String what = "pow2(" + x + ")";
                checkSpec(what, double_pow2(x), x * x);
                checkBits(what, double_pow2(x), ref_double_pow(x, 2.0d));
            }
            Random random = new Random();
            for (int i = 0; i < 1000; i++) {
                double x = randomDouble(random);
                checkBits("random pow2 " + i + " (" + x + ")",
                        double_pow2(x), ref_double_pow(x, 2.0d));
            }
        }

        // --- Math.pow(x, 0.5): the guarded sqrt specialization ---

        private static void checkPowHalf(boolean bitExact) {
            double[][] table = {
                // pow(+-0, 0.5) is +0.0 although sqrt(-0.0) is -0.0: these
                // pins keep the zeros on the dpow slow path.
                {+0.0d, +0.0d},
                {-0.0d, +0.0d},
                // A negative base with a non-integer exponent is NaN --
                // must come from the slow path, not from sqrt.
                {-1.0d, Double.NaN},
                {-4.0d, Double.NaN},
                {Double.NaN, Double.NaN},
                {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY},
                {Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY},
                {1.0d, 1.0d},
                {4.0d, 2.0d},
                {1e300d, 1e150d},
                {1e-300d, 1e-150d},
                {5e-324d, Math.sqrt(5e-324d)},
            };
            for (int i = 0; i < table.length; i++) {
                double x = table[i][0];
                double expected = table[i][1];
                String what = "pow_half(" + x + ")";
                if (bitExact) {
                    check(what, double_pow_half(x), expected, ref_double_pow(x, 0.5d), true);
                } else {
                    checkValue(what, double_pow_half(x), expected);
                    checkValue(what, double_pow_half(x), ref_double_pow(x, 0.5d));
                }
            }
            Random random = new Random();
            for (int i = 0; i < 1000; i++) {
                double x = randomDouble(random);
                String what = "random pow_half " + i + " (" + x + ")";
                if (bitExact) {
                    // Both sides run the same dpow routine, which returns
                    // sqrt(x) for a non-negative base: bit-exact everywhere,
                    // NaN payloads included.
                    checkBits(what, double_pow_half(x), ref_double_pow(x, 0.5d));
                } else {
                    // llvm mode performs no 0.5 specialization (no runtime
                    // entry), so the compiled side is the host libm's
                    // pow(x, 0.5) while the reference runs the interpreter's
                    // fdlibm port -- two implementations, compared with the
                    // ulp tolerance. checkUlp still demands exact agreement
                    // for the zeros and infinities and NaN for the negative
                    // and NaN bases.
                    checkUlp(what, double_pow_half(x), ref_double_pow(x, 0.5d));
                }
            }
        }

        // --- Math.pow(x, +/-0.0): the constant 1.0 identity fold ---

        private static void checkPowZero(boolean bitExact) {
            // The fold is exact for every input including NaN bases (the
            // fdlibm port tests (iy|ly)==0 before its NaN check and returns
            // one), and the llvm mode's own pow(x, 0.0) -> 1.0 fold is
            // equally exact, so raw bits match the interpreted reference in
            // every mode.
            double[] values = {
                +0.0d, -0.0d, 1.0d, -1.0d, 0.5d, 3.7d, -3.7d, 1e300d,
                Double.NaN, Double.longBitsToDouble(0x7ff0000000000001L),
                Double.longBitsToDouble(0xfff0000000000001L),
                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                Double.MAX_VALUE, Double.MIN_VALUE, 5e-324d,
            };
            for (int i = 0; i < values.length; i++) {
                double x = values[i];
                checkBits("pow_zero(+0.0)(" + x + ")",
                        double_pow_zero(x), ref_double_pow(x, +0.0d));
                checkBits("pow_zero(-0.0)(" + x + ")",
                        double_pow_negzero(x), ref_double_pow(x, -0.0d));
            }
        }

        // --- Math.pow(x, { 2.0, 2.0 }): phi-hidden constant exponent ---

        private static void checkPowPhi(boolean bitExact) {
            // The constant survives behind the merge in the frontend and is
            // only exposed to the identity folding during optimization; the
            // result is x*x, bit-exact against the interpreted reference in
            // every mode.
            double[] values = {
                +0.0d, -0.0d, 1.0d, -1.0d, 0.5d, -1.5d, 2.0d, -2.0d, 1e300d, 1e-300d,
                Double.NaN, Double.longBitsToDouble(0x7ff0000000000001L),
                Double.longBitsToDouble(0xfff0000000000001L),
                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                Double.MAX_VALUE, Double.MIN_VALUE, 5e-324d,
            };
            for (double x : values) {
                for (boolean cond : new boolean[] {false, true}) {
                    checkBits("pow_phi(" + x + ", " + cond + ")",
                            double_pow_phi(x, cond), ref_double_pow(x, 2.0d));
                }
            }
        }

        // Compiled by Jeandle (see the CompileCommand list above).
        public static double double_pow(double a, double b) {
            return Math.pow(a, b);
        }

        public static double double_pow2(double a) {
            return Math.pow(a, 2.0d);
        }

        public static double double_pow_half(double a) {
            return Math.pow(a, 0.5d);
        }

        public static double double_pow_zero(double a) {
            return Math.pow(a, 0.0d);
        }

        public static double double_pow_negzero(double a) {
            return Math.pow(a, -0.0d);
        }

        public static double double_pow_phi(double a, boolean cond) {
            // Identical constants on both arms: the frontend's operand-stack
            // merge yields a phi, not a constant, so the exponent is only
            // visible to the optimizer.
            double e = cond ? 2.0d : 2.0d;
            return Math.pow(a, e);
        }

        // Not listed in the CompileCommand, so these stay interpreted and
        // act as the reference implementation.
        public static double ref_double_pow(double a, double b) {
            return Math.pow(a, b);
        }
    }
}
