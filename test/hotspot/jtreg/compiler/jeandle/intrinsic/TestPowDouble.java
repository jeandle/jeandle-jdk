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
 * @test
 * @library /test/lib /
 * @build jdk.test.lib.Asserts
 * @run main/othervm compiler.jeandle.intrinsic.TestPowDouble
 */

package compiler.jeandle.intrinsic;

import compiler.jeandle.fileCheck.FileCheck;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.nio.file.Path;
import java.nio.file.Files;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestPowDouble {
    public static void main(String[] args) throws Exception {
        boolean is_x86 = System.getProperty("os.arch").equals("amd64");
        String dump_path = System.getProperty("java.io.tmpdir");

        // intrinsic by StubRoutine
        ArrayList<String> command_args = new ArrayList<String>(List.of(
            "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
            "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR",
            "-XX:JeandleDumpDirectory="+dump_path, "-XX:+JeandleUseHotspotIntrinsics",
            "-XX:CompileCommand=compileonly,"+TestWrapper.class.getName()+"::pow_double"));
        if (is_x86) {
          command_args.addAll(List.of("-XX:+UnlockDiagnosticVMOptions", "-XX:+UseLibmIntrinsic"));
        }
        command_args.add(TestWrapper.class.getName());

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(command_args);
        OutputAnalyzer output = ProcessTools.executeCommand(pb);

        output.shouldHaveExitValue(0)
            .shouldContain("Method `static jdouble java.lang.Math.pow(jdouble, jdouble)` is parsed as intrinsic");

        // Verify llvm IR
        FileCheck checker = new FileCheck(dump_path, TestWrapper.class.getMethod("pow_double", double.class, double.class), false);
        // find compiled method
        checker.checkPattern("define hotspotcc double .*compiler_jeandle_intrinsic_TestPowDouble\\$TestWrapper_pow_double.*(double %0, double %1)");
        // check IR
        checker.checkNext("entry:");
        checker.checkNext("br label %bci_0");
        checker.checkNext("bci_0:");
        if (is_x86) {
            checker.checkNext("call double @StubRoutines_dpow");
        } else {
            checker.checkNextPattern("call double inttoptr \\(i64 (\\d+) to ptr\\).*#\\d+");
        }
        checker.checkNext("ret double");
        // check gc-leaf-function
        if (is_x86) {
            checker.checkPattern("declare double @StubRoutines_dpow.*#\\d+");
        }
        checker.checkPattern("attributes #\\d+ = \\{ \"gc-leaf-function\" \\}");

        // intrinsic by SharedRuntime
        if (is_x86) {
            dump_path = System.getProperty("java.io.tmpdir")+"/test2";
            Path tmp2 = Path.of(dump_path);
            if (!Files.exists(tmp2)) {
                Files.createDirectory(tmp2);
            }

            command_args = new ArrayList<String>(List.of(
                "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
                "-Xlog:jeandle=debug", "-XX:+ForceUnreachable",
                "-XX:CompileCommand=compileonly,"+TestWrapper.class.getName()+"::pow_double",
                "-XX:+UnlockDiagnosticVMOptions", "-XX:-UseLibmIntrinsic", "-XX:+JeandleUseHotspotIntrinsics",
                TestWrapper.class.getName()));
            pb = ProcessTools.createLimitedTestJavaProcessBuilder(command_args);
            output = ProcessTools.executeCommand(pb);
            output.shouldHaveExitValue(0)
                .shouldContain("Method `static jdouble java.lang.Math.pow(jdouble, jdouble)` is parsed as intrinsic");

            command_args = new ArrayList<String>(List.of(
                "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
                "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory="+dump_path,
                "-XX:CompileCommand=compileonly,"+TestWrapper.class.getName()+"::pow_double",
                "-XX:+UnlockDiagnosticVMOptions", "-XX:-UseLibmIntrinsic", "-XX:+JeandleUseHotspotIntrinsics",
                TestWrapper.class.getName()));
            pb = ProcessTools.createLimitedTestJavaProcessBuilder(command_args);
            output = ProcessTools.executeCommand(pb);
            output.shouldHaveExitValue(0)
                .shouldContain("Method `static jdouble java.lang.Math.pow(jdouble, jdouble)` is parsed as intrinsic");
            // Verify llvm IR
            checker = new FileCheck(dump_path, TestWrapper.class.getMethod("pow_double", double.class, double.class), false);
            // find compiled method
            checker.checkPattern("define hotspotcc double .*compiler_jeandle_intrinsic_TestPowDouble\\$TestWrapper_pow_double.*(double %0, double %1)");
            // check IR
            checker.checkNext("entry:");
            checker.checkNext("br label %bci_0");
            checker.checkNext("bci_0:");
            // check gc-leaf-function
            checker.checkNextPattern("call double inttoptr \\(i64 (\\d+) to ptr\\).*#\\d+");
            checker.checkNext("ret double");
            checker.checkPattern("attributes #\\d+ = \\{ \"gc-leaf-function\" \\}");
        }
    }

    static public class TestWrapper {
        static double v = Math.pow(1.0d, 1.0d);   // Force load java.lang.Math class with pow function

        public static void main(String[] args) {
            Random random = new Random();

            // Test special values
            testCase(0.0d, 0.0d);                    // pow(0,0) = 1
            testCase(1.0d, 0.0d);                    // pow(1,0) = 1
            testCase(2.0d, 3.0d);                    // pow(2,3) = 8
            testCase(4.0d, 0.5d);                    // pow(4,0.5) = 2 (square root)
            testCase(8.0d, 1.0/3.0);                 // pow(8,1/3) = 2 (cube root)

            // Test NaN and Infinity cases
            testCase(Double.NaN, 1.0d);
            testCase(1.0d, Double.NaN);
            testCase(Double.NaN, Double.NaN);
            testCase(Double.POSITIVE_INFINITY, 2.0d);
            testCase(2.0d, Double.POSITIVE_INFINITY);
            testCase(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
            testCase(Double.NEGATIVE_INFINITY, 2.0d);
            testCase(2.0d, Double.NEGATIVE_INFINITY);
            testCase(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY);

            // Test negative base with various exponents
            testCase(-2.0d, 2.0d);                  // pow(-2,2) = 4
            testCase(-2.0d, 3.0d);                  // pow(-2,3) = -8
            testCase(-4.0d, 0.5d);                  // pow(-4,0.5) = NaN (negative base, fractional exponent)

            // Test boundary values
            testCase(Double.MIN_VALUE, 2.0d);
            testCase(2.0d, Double.MIN_VALUE);
            testCase(Double.MAX_VALUE, 0.5d);
            testCase(0.5d, Double.MAX_VALUE);

            // Test values near 1
            testCase(1.0d, Double.MAX_VALUE);
            testCase(1.0d, Double.MIN_VALUE);
            testCase(1.0d, Double.NEGATIVE_INFINITY);
            testCase(1.0d, Double.POSITIVE_INFINITY);

            // Test random values in different ranges
            for (int i = 0; i < 100; i++) {
                double base = random.nextDouble() * 10.0;          // (0, 10)
                double exponent = random.nextDouble() * 4.0 - 2.0; // (-2, 2)
                testCase(base, exponent);
            }

            for (int i = 0; i < 50; i++) {
                double base = 10.0 + random.nextDouble() * 90.0;   // (10, 100)
                double exponent = random.nextDouble() * 5.0;       // (0, 5)
                testCase(base, exponent);
            }

            for (int i = 0; i < 50; i++) {
                double base = random.nextDouble();                 // (0, 1)
                double exponent = 5.0 + random.nextDouble() * 5.0; // (5, 10)
                testCase(base, exponent);
            }

            // Test negative bases with integer exponents
            for (int i = 0; i < 50; i++) {
                double base = -1.0 - random.nextDouble() * 9.0;   // (-10, -1)
                double exponent = random.nextInt(10) - 5;         // integer exponents from -5 to 4
                testCase(base, exponent);
            }
        }

        private static void testCase(double a, double b) {
            Asserts.assertEquals(pow_double_verified(a, b), pow_double(a, b));
        }

        public static double pow_double(double a, double b) {
            return Math.pow(a, b);
        }

        public static double pow_double_verified(double a, double b) {
            return Math.pow(a, b);
        }
    }
}
