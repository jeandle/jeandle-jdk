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
 * @test TestLLVMDTrigs.java
 * @summary Test LLVM DTrig intrinsics implementation
 * @requires os.arch=="amd64" | os.arch=="x86_64" | os.arch=="aarch64"
 * @library /test/lib /
 * @build compiler.jeandle.fileCheck.FileCheck
 * @run driver TestLLVMDTrigs
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

public class TestLLVMDTrigs {
    // Java.lang.Math function Error tolerance is 1~2 ulp(according to JDK doc).
    // Test tolerance: 1 ulp (suitable for standard libm precision validation).
    private static final int ULP_TOLERANCE = 1;

    private static double v = Math.abs(1.0d);   // Force load java.lang.Math class

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
        } else {
            throw new IllegalArgumentException("Unsupported argument: " + args[0]);
        }
    }

    private static void runVM() throws Exception {
        // Test sin function with LLVM intrinsic
        OutputAnalyzer output = runTestProcess("testSin");
        output.shouldHaveExitValue(0);

        String testDumpPath = System.getProperty("java.io.tmpdir") + "/test_sin";
        FileCheck checker = new FileCheck(testDumpPath, TestLLVMDTrigs.class.getDeclaredMethod("double_sin", double.class), false);
        checker.check("define hotspotcc double @TestLLVMDTrigs_double_sin");
        checker.check("call double @llvm.sin.f64");

        // Test cos function with LLVM intrinsic
        output = runTestProcess("testCos");
        output.shouldHaveExitValue(0);

        testDumpPath = System.getProperty("java.io.tmpdir") + "/test_cos";
        checker = new FileCheck(testDumpPath, TestLLVMDTrigs.class.getDeclaredMethod("double_cos", double.class), false);
        checker.check("define hotspotcc double @TestLLVMDTrigs_double_cos");
        checker.check("call double @llvm.cos.f64");

        // Test tan function with LLVM intrinsic
        output = runTestProcess("testTan");
        output.shouldHaveExitValue(0);

        testDumpPath = System.getProperty("java.io.tmpdir") + "/test_tan";
        checker = new FileCheck(testDumpPath, TestLLVMDTrigs.class.getDeclaredMethod("double_tan", double.class), false);
        checker.check("define hotspotcc double @TestLLVMDTrigs_double_tan");
        checker.check("call double @llvm.tan.f64");
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
        loadCmd.add("-XX:CompileCommand=compileonly,TestLLVMDTrigs::" + testType.toLowerCase().replace("test", "double_"));
        loadCmd.add("TestLLVMDTrigs");
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
        Asserts.assertLTE(Math.abs(double_sin_verified(1.5d) - double_sin(1.5d)), ULP_TOLERANCE * StrictMath.ulp(double_sin_verified(1.5d)));
        Asserts.assertLTE(Math.abs(double_sin_verified(-1.5d) - double_sin(-1.5d)), ULP_TOLERANCE * StrictMath.ulp(double_sin_verified(-1.5d)));
        Asserts.assertEquals(double_sin_verified(Double.NaN), double_sin(Double.NaN));
        Asserts.assertEquals(double_sin_verified(Double.POSITIVE_INFINITY), double_sin(Double.POSITIVE_INFINITY));
        Asserts.assertEquals(double_sin_verified(Double.NEGATIVE_INFINITY), double_sin(Double.NEGATIVE_INFINITY));

        // Test random values
        for (int i = 0; i < 1000; i++) {
            double d = random.nextDouble();
            Asserts.assertLTE(Math.abs(double_sin_verified(d) - double_sin(d)), ULP_TOLERANCE * StrictMath.ulp(double_sin_verified(d)));
        }
    }

    private static void testCosFunction() {
        Random random = new Random();

        // Test specific values
        Asserts.assertLTE(Math.abs(double_cos_verified(1.5d) - double_cos(1.5d)), ULP_TOLERANCE * StrictMath.ulp(double_cos_verified(1.5d)));
        Asserts.assertLTE(Math.abs(double_cos_verified(-1.5d) - double_cos(-1.5d)), ULP_TOLERANCE * StrictMath.ulp(double_cos_verified(-1.5d)));
        Asserts.assertEquals(double_cos_verified(Double.NaN), double_cos(Double.NaN));
        Asserts.assertEquals(double_cos_verified(Double.POSITIVE_INFINITY), double_cos(Double.POSITIVE_INFINITY));
        Asserts.assertEquals(double_cos_verified(Double.NEGATIVE_INFINITY), double_cos(Double.NEGATIVE_INFINITY));

        // Test random values
        for (int i = 0; i < 1000; i++) {
            double d = random.nextDouble();
            Asserts.assertLTE(Math.abs(double_cos_verified(d) - double_cos(d)), ULP_TOLERANCE * StrictMath.ulp(double_cos_verified(d)));
        }
    }

    private static void testTanFunction() {
        Random random = new Random();

        // Test specific values
        Asserts.assertLTE(Math.abs(double_tan_verified(1.5d) - double_tan(1.5d)), ULP_TOLERANCE * StrictMath.ulp(double_tan_verified(1.5d)));
        Asserts.assertLTE(Math.abs(double_tan_verified(-1.5d) - double_tan(-1.5d)), ULP_TOLERANCE * StrictMath.ulp(double_tan_verified(-1.5d)));
        Asserts.assertEquals(double_tan_verified(Double.NaN), double_tan(Double.NaN));
        Asserts.assertEquals(double_tan_verified(Double.POSITIVE_INFINITY), double_tan(Double.POSITIVE_INFINITY));
        Asserts.assertEquals(double_tan_verified(Double.NEGATIVE_INFINITY), double_tan(Double.NEGATIVE_INFINITY));

        // Test random values
        for (int i = 0; i < 1000; i++) {
            double d = random.nextDouble();
            Asserts.assertLTE(Math.abs(double_tan_verified(d) - double_tan(d)), ULP_TOLERANCE * StrictMath.ulp(double_tan_verified(d)));
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
}
