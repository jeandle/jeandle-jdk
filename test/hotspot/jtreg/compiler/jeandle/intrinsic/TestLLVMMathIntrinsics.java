/*
 * Copyright (c) 2025, the Jeandle-JDK Authors. All Rights Reserved.
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
 * @test TestLLVMMathIntrinsics.java
 * @summary Test LLVM DTrig intrinsics implementation
 * @requires os.arch=="amd64" | os.arch=="x86_64" | os.arch=="aarch64"
 * @library /test/lib /
 * @build compiler.jeandle.fileCheck.FileCheck
 * @run driver TestLLVMMathIntrinsics
 */

import compiler.jeandle.fileCheck.FileCheck;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.nio.file.Path;
import java.nio.file.Files;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestLLVMMathIntrinsics {
    // Java.lang.Math function Error tolerance is 1~2 ulp(according to JDK doc).
    // Test tolerance: 2 ulp (safe upper bound for standard libm precision validation).
    private static final int ULP_TOLERANCE = 2;

    private static double v = Math.abs(1.0d);   // Force load java.lang.Math class

    private static void assertWithinUlp(double computed, double reference, double maxUlp) {
        double error = Math.abs(computed - reference);
        double tolerance = maxUlp * StrictMath.ulp(reference);
        Asserts.assertLTE(error, tolerance);
    }

    private static final String[] baseProcArgs = new String[] {
        "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
        "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR",
        "-XX:-JeandleUseHotspotIntrinsics"  // Disable Hotspot intrinsics to use LLVM intrinsic
    };

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            runVM();
        } else if (args[0].equals("testSin")) {
            testSinFunction();
        } else if (args[0].equals("testCos")) {
            testCosFunction();
        } else if (args[0].equals("testTan")) {
            testTanFunction();
        } else if (args[0].equals("testLog")) {
            testLogFunction();
        } else if (args[0].equals("testLog10")) {
            testLog10Function();
        } else if (args[0].equals("testExp")) {
            testExpFunction();
        } else if (args[0].equals("testPow")) {
            testPowFunction();
        } else {
            throw new IllegalArgumentException("Unsupported argument: " + args[0]);
        }
    }

    private static void runVM() throws Exception {
        // Test sin function with LLVM intrinsic
        OutputAnalyzer output = runTestProcess("testSin");
        output.shouldHaveExitValue(0);

        String testDumpPath = System.getProperty("java.io.tmpdir") + "/test_sin";
        FileCheck checker = new FileCheck(testDumpPath, TestLLVMMathIntrinsics.class.getDeclaredMethod("double_sin", double.class), false);
        checker.checkPattern("define hotspotcc double .*TestLLVMMathIntrinsics_double_sin.*");
        checker.check("call double @llvm.sin.f64");

        // Test cos function with LLVM intrinsic
        output = runTestProcess("testCos");
        output.shouldHaveExitValue(0);

        testDumpPath = System.getProperty("java.io.tmpdir") + "/test_cos";
        checker = new FileCheck(testDumpPath, TestLLVMMathIntrinsics.class.getDeclaredMethod("double_cos", double.class), false);
        checker.checkPattern("define hotspotcc double .*TestLLVMMathIntrinsics_double_cos.*");
        checker.check("call double @llvm.cos.f64");

        // Test tan function with LLVM intrinsic
        output = runTestProcess("testTan");
        output.shouldHaveExitValue(0);

        testDumpPath = System.getProperty("java.io.tmpdir") + "/test_tan";
        checker = new FileCheck(testDumpPath, TestLLVMMathIntrinsics.class.getDeclaredMethod("double_tan", double.class), false);
        checker.checkPattern("define hotspotcc double .*TestLLVMMathIntrinsics_double_tan.*");
        checker.check("call double @llvm.tan.f64");

        // Test log function with LLVM intrinsic
        output = runTestProcess("testLog");
        output.shouldHaveExitValue(0);

        testDumpPath = System.getProperty("java.io.tmpdir") + "/test_log";
        checker = new FileCheck(testDumpPath, TestLLVMMathIntrinsics.class.getDeclaredMethod("double_log", double.class), false);
        checker.checkPattern("define hotspotcc double .*TestLLVMMathIntrinsics_double_log.*");
        checker.check("call double @llvm.log.f64");

        // Test log10 function with LLVM intrinsic
        output = runTestProcess("testLog10");
        output.shouldHaveExitValue(0);

        testDumpPath = System.getProperty("java.io.tmpdir") + "/test_log10";
        checker = new FileCheck(testDumpPath, TestLLVMMathIntrinsics.class.getDeclaredMethod("double_log10", double.class), false);
        checker.checkPattern("define hotspotcc double .*TestLLVMMathIntrinsics_double_log10.*");
        checker.check("call double @llvm.log10.f64");

        // Test exp function with LLVM intrinsic
        output = runTestProcess("testExp");
        output.shouldHaveExitValue(0);

        testDumpPath = System.getProperty("java.io.tmpdir") + "/test_exp";
        checker = new FileCheck(testDumpPath, TestLLVMMathIntrinsics.class.getDeclaredMethod("double_exp", double.class), false);
        checker.checkPattern("define hotspotcc double .*TestLLVMMathIntrinsics_double_exp.*");
        checker.check("call double @llvm.exp.f64");

        // Test pow function with LLVM intrinsic
        output = runTestProcess("testPow");
        output.shouldHaveExitValue(0);

        testDumpPath = System.getProperty("java.io.tmpdir") + "/test_pow";
        checker = new FileCheck(testDumpPath, TestLLVMMathIntrinsics.class.getDeclaredMethod("double_pow", double.class, double.class), false);
        checker.checkPattern("define hotspotcc double .*TestLLVMMathIntrinsics_double_pow.*");
        checker.check("call double @llvm.pow.f64");
    }

    private static OutputAnalyzer runTestProcess(String testType) throws Exception {
        String dumpDir = System.getProperty("java.io.tmpdir") + "/" + testType.toLowerCase().replace("test", "test_");
        Path dumpPath = Path.of(dumpDir);
        if (!Files.exists(dumpPath)) {
            Files.createDirectory(dumpPath);
        }

        List<String> loadCmd = new ArrayList<>();
        loadCmd.addAll(Arrays.asList(baseProcArgs));
        loadCmd.add("-XX:JeandleDumpDirectory=" + dumpDir);
        loadCmd.add("-XX:CompileCommand=compileonly,TestLLVMMathIntrinsics::" + testType.toLowerCase().replace("test", "double_"));
        loadCmd.add("TestLLVMMathIntrinsics");
        loadCmd.add(testType);

        String testClassPath = System.getProperty("test.classes", ".");
        String classPathProp = "-Dtest.classes=" + testClassPath;
        loadCmd.add(0, classPathProp);

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(loadCmd);
        return ProcessTools.executeProcess(pb);
    }

    private static void testSinFunction() {
        Random random = new Random();

        // Test specific values
        assertWithinUlp(double_sin(1.5d), double_sin_verified(1.5d), ULP_TOLERANCE);
        assertWithinUlp(double_sin(-1.5d), double_sin_verified(-1.5d), ULP_TOLERANCE);
        Asserts.assertEquals(double_sin_verified(Double.NaN), double_sin(Double.NaN));
        Asserts.assertEquals(double_sin_verified(Double.POSITIVE_INFINITY), double_sin(Double.POSITIVE_INFINITY));
        Asserts.assertEquals(double_sin_verified(Double.NEGATIVE_INFINITY), double_sin(Double.NEGATIVE_INFINITY));

        // Test random values
        for (int i = 0; i < 1000; i++) {
            double d = random.nextDouble();
            assertWithinUlp(double_sin(d), double_sin_verified(d), ULP_TOLERANCE);
        }
    }

    private static void testCosFunction() {
        Random random = new Random();

        // Test specific values
        assertWithinUlp(double_cos(1.5d), double_cos_verified(1.5d), ULP_TOLERANCE);
        assertWithinUlp(double_cos(-1.5d), double_cos_verified(-1.5d), ULP_TOLERANCE);
        Asserts.assertEquals(double_cos_verified(Double.NaN), double_cos(Double.NaN));
        Asserts.assertEquals(double_cos_verified(Double.POSITIVE_INFINITY), double_cos(Double.POSITIVE_INFINITY));
        Asserts.assertEquals(double_cos_verified(Double.NEGATIVE_INFINITY), double_cos(Double.NEGATIVE_INFINITY));

        // Test random values
        for (int i = 0; i < 1000; i++) {
            double d = random.nextDouble();
            assertWithinUlp(double_cos(d), double_cos_verified(d), ULP_TOLERANCE);
        }
    }

    private static void testTanFunction() {
        Random random = new Random();

        // Test specific values
        assertWithinUlp(double_tan(1.5d), double_tan_verified(1.5d), ULP_TOLERANCE);
        assertWithinUlp(double_tan(-1.5d), double_tan_verified(-1.5d), ULP_TOLERANCE);
        Asserts.assertEquals(double_tan_verified(Double.NaN), double_tan(Double.NaN));
        Asserts.assertEquals(double_tan_verified(Double.POSITIVE_INFINITY), double_tan(Double.POSITIVE_INFINITY));
        Asserts.assertEquals(double_tan_verified(Double.NEGATIVE_INFINITY), double_tan(Double.NEGATIVE_INFINITY));

        // Test random values
        for (int i = 0; i < 1000; i++) {
            double d = random.nextDouble();
            assertWithinUlp(double_tan(d), double_tan_verified(d), ULP_TOLERANCE);
        }
    }

    private static void testLogFunction() {
        Random random = new Random();

        // Test specific values
        assertWithinUlp(double_log(Math.E), double_log_verified(Math.E), ULP_TOLERANCE);
        assertWithinUlp(double_log(1.0d), double_log_verified(1.0d), ULP_TOLERANCE);
        Asserts.assertEquals(double_log_verified(Double.NaN), double_log(Double.NaN));
        Asserts.assertEquals(double_log_verified(Double.POSITIVE_INFINITY), double_log(Double.POSITIVE_INFINITY));
        Asserts.assertEquals(double_log_verified(0.0d), double_log(0.0d));

        // Test random positive values
        for (int i = 0; i < 1000; i++) {
            double d = random.nextDouble() * 1000.0;
            assertWithinUlp(double_log(d), double_log_verified(d), ULP_TOLERANCE);
        }
    }

    private static void testLog10Function() {
        Random random = new Random();

        // Test specific values
        assertWithinUlp(double_log10(10.0d), double_log10_verified(10.0d), ULP_TOLERANCE);
        assertWithinUlp(double_log10(100.0d), double_log10_verified(100.0d), ULP_TOLERANCE);
        assertWithinUlp(double_log10(1.0d), double_log10_verified(1.0d), ULP_TOLERANCE);
        Asserts.assertEquals(double_log10_verified(Double.NaN), double_log10(Double.NaN));
        Asserts.assertEquals(double_log10_verified(Double.POSITIVE_INFINITY), double_log10(Double.POSITIVE_INFINITY));
        Asserts.assertEquals(double_log10_verified(0.0d), double_log10(0.0d));

        // Test random positive values
        for (int i = 0; i < 1000; i++) {
            double d = random.nextDouble() * 1000.0;
            assertWithinUlp(double_log10(d), double_log10_verified(d), ULP_TOLERANCE);
        }
    }

    private static void testExpFunction() {
        Random random = new Random();

        // Test specific values
        assertWithinUlp(double_exp(0.0d), double_exp_verified(0.0d), ULP_TOLERANCE);
        assertWithinUlp(double_exp(1.0d), double_exp_verified(1.0d), ULP_TOLERANCE);
        assertWithinUlp(double_exp(-1.0d), double_exp_verified(-1.0d), ULP_TOLERANCE);
        Asserts.assertEquals(double_exp_verified(Double.NaN), double_exp(Double.NaN));
        Asserts.assertEquals(double_exp_verified(Double.POSITIVE_INFINITY), double_exp(Double.POSITIVE_INFINITY));
        Asserts.assertEquals(double_exp_verified(Double.NEGATIVE_INFINITY), double_exp(Double.NEGATIVE_INFINITY));

        // Test random values
        for (int i = 0; i < 100; i++) {
            double d = (random.nextDouble() - 0.5) * 10.0; // [-5, 5)
            assertWithinUlp(double_exp(d), double_exp_verified(d), ULP_TOLERANCE);
        }
    }

    private static void testPowFunction() {
        Random random = new Random();

        // Test specific values
        assertWithinUlp(double_pow(2.0d, 3.0d), double_pow_verified(2.0d, 3.0d), ULP_TOLERANCE);
        assertWithinUlp(double_pow(4.0d, 0.5d), double_pow_verified(4.0d, 0.5d), ULP_TOLERANCE);
        assertWithinUlp(double_pow(1.0d, 100.0d), double_pow_verified(1.0d, 100.0d), ULP_TOLERANCE);

        // Test edge cases
        Asserts.assertEquals(double_pow_verified(Double.NaN, 2.0d), double_pow(Double.NaN, 2.0d));
        Asserts.assertEquals(double_pow_verified(2.0d, Double.NaN), double_pow(2.0d, Double.NaN));
        Asserts.assertEquals(double_pow_verified(Double.POSITIVE_INFINITY, 2.0d), double_pow(Double.POSITIVE_INFINITY, 2.0d));
        Asserts.assertEquals(double_pow_verified(2.0d, Double.POSITIVE_INFINITY), double_pow(2.0d, Double.POSITIVE_INFINITY));

        // Test random values
        for (int i = 0; i < 1000; i++) {
            double base = random.nextDouble() * 10.0 + 0.1;
            double exponent = (random.nextDouble() - 0.5) * 10.0; // [-5, 5)
            assertWithinUlp(double_pow(base, exponent), double_pow_verified(base, exponent), ULP_TOLERANCE);
        }

        // test negative base
        for (int i = 0; i < 100; i++) {
            double base = -random.nextDouble() * 10.0;
            int exponent = random.nextInt(10) - 5; // [-5, 5)
            assertWithinUlp(double_pow(base, exponent), double_pow_verified(base, exponent), ULP_TOLERANCE);
        }
    }

    public static double double_sin(double a) {
        return Math.sin(a);
    }

    public static double double_sin_verified(double a) {
        return StrictMath.sin(a);
    }

    public static double double_cos(double a) {
        return Math.cos(a);
    }

    public static double double_cos_verified(double a) {
        return StrictMath.cos(a);
    }

    public static double double_tan(double a) {
        return Math.tan(a);
    }

    public static double double_tan_verified(double a) {
        return StrictMath.tan(a);
    }

    public static double double_log(double a) {
        return Math.log(a);
    }

    public static double double_log_verified(double a) {
        return StrictMath.log(a);
    }

    public static double double_log10(double a) {
        return Math.log10(a);
    }

    public static double double_log10_verified(double a) {
        return StrictMath.log10(a);
    }

    public static double double_exp(double a) {
        return Math.exp(a);
    }

    public static double double_exp_verified(double a) {
        return StrictMath.exp(a);
    }

    public static double double_pow(double a, double b) {
        return Math.pow(a, b);
    }

    public static double double_pow_verified(double a, double b) {
        return StrictMath.pow(a, b);
    }
}
