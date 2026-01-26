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
 * @run main/othervm compiler.jeandle.intrinsic.TestExpDouble
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

public class TestExpDouble {
    public static void main(String[] args) throws Exception {
        boolean is_x86 = System.getProperty("os.arch").equals("amd64");
        String dump_path = System.getProperty("java.io.tmpdir");

        // intrinsic by StubRoutine
        ArrayList<String> command_args = new ArrayList<String>(List.of(
            "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
            "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR",
            "-XX:JeandleDumpDirectory="+dump_path, "-XX:+JeandleUseHotspotIntrinsics",
            "-XX:CompileCommand=compileonly,"+TestWrapper.class.getName()+"::exp_double"));
        if (is_x86) {
          command_args.addAll(List.of("-XX:+UnlockDiagnosticVMOptions", "-XX:+UseLibmIntrinsic"));
        }
        command_args.add(TestWrapper.class.getName());

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(command_args);
        OutputAnalyzer output = ProcessTools.executeCommand(pb);

        output.shouldHaveExitValue(0)
              .shouldContain("Method `static jdouble java.lang.Math.exp(jdouble)` is parsed as intrinsic");

        // Verify llvm IR
        FileCheck checker = new FileCheck(dump_path, TestWrapper.class.getMethod("exp_double", double.class), false);
        // find compiled method
        checker.checkPattern("define hotspotcc double .*compiler_jeandle_intrinsic_TestExpDouble\\$TestWrapper_exp_double.*(double %0)");
        // check IR
        checker.checkNext("entry:");
        checker.checkNext("br label %bci_0");
        checker.checkNext("bci_0:");
        if (is_x86) {
            checker.checkNext("call double @StubRoutines_dexp");
        } else {
            checker.checkNextPattern("call double inttoptr \\(i64 (\\d+) to ptr\\)");
        }
        checker.checkNext("ret double");

        // intrinsic by SharedRuntime
        if (is_x86) {
            dump_path = System.getProperty("java.io.tmpdir")+"/test2";
            Path tmp2 = Path.of(dump_path);
            if (!Files.exists(tmp2)) {
                Files.createDirectory(tmp2);
            }

            command_args = new ArrayList<String>(List.of(
                "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
                "-Xlog:jeandle=debug", "-XX:+ForceUnreachable", "-XX:+JeandleUseHotspotIntrinsics",
                "-XX:CompileCommand=compileonly,"+TestWrapper.class.getName()+"::exp_double",
                "-XX:+UnlockDiagnosticVMOptions", "-XX:-UseLibmIntrinsic",
                TestWrapper.class.getName()));
            pb = ProcessTools.createLimitedTestJavaProcessBuilder(command_args);
            output = ProcessTools.executeCommand(pb);
            output.shouldHaveExitValue(0)
                .shouldContain("Method `static jdouble java.lang.Math.exp(jdouble)` is parsed as intrinsic");

            command_args = new ArrayList<String>(List.of(
                "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
                "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory="+dump_path, "-XX:+JeandleUseHotspotIntrinsics",
                "-XX:CompileCommand=compileonly,"+TestWrapper.class.getName()+"::exp_double",
                "-XX:+UnlockDiagnosticVMOptions", "-XX:-UseLibmIntrinsic",
                TestWrapper.class.getName()));
            pb = ProcessTools.createLimitedTestJavaProcessBuilder(command_args);
            output = ProcessTools.executeCommand(pb);
            output.shouldHaveExitValue(0)
                .shouldContain("Method `static jdouble java.lang.Math.exp(jdouble)` is parsed as intrinsic");
            // Verify llvm IR
            checker = new FileCheck(dump_path, TestWrapper.class.getMethod("exp_double", double.class), false);
            // find compiled method
            checker.checkPattern("define hotspotcc double .*compiler_jeandle_intrinsic_TestExpDouble\\$TestWrapper_exp_double.*(double %0)");
            // check IR
            checker.checkNext("entry:");
            checker.checkNext("br label %bci_0");
            checker.checkNext("bci_0:");
            checker.checkNextPattern("call double inttoptr \\(i64 (\\d+) to ptr\\)");
            checker.checkNext("ret double");
        }
    }

    static public class TestWrapper {
        public static void main(String[] args) {
            Random random = new Random();

            // Test special values
            testCase(0.0d);                    // exp(0) = 1
            testCase(1.0d);                    // exp(1) = e
            testCase(-1.0d);                   // exp(-1) = 1/e
            testCase(Double.NaN);
            testCase(Double.POSITIVE_INFINITY); // exp(+∞) = +∞
            testCase(Double.NEGATIVE_INFINITY); // exp(-∞) = 0

            // Test boundary values
            testCase(Double.MIN_VALUE);
            testCase(Double.MIN_NORMAL);
            testCase(Double.MAX_VALUE);

            // Test values that may cause overflow/underflow
            testCase(710.0);   // Near overflow threshold (exp(710) ≈ 1.0e308)
            testCase(-745.0);  // Near underflow threshold (exp(-745) ≈ 4.94e-324)

            // Test random values in different ranges
            for (int i = 0; i < 100; i++) {
                testCase(random.nextDouble() * 2 - 1);           // (-1, 1)
                testCase(1.0 + random.nextDouble() * 4.0);       // (1, 5)
                testCase(-1.0 - random.nextDouble() * 4.0);      // (-5, -1)
                testCase(10.0 * (random.nextDouble() - 0.5));    // (-5, 5)
            }

            // Test larger values
            for (int i = 0; i < 50; i++) {
                testCase(10.0 + random.nextDouble() * 100.0);    // (10, 110)
                testCase(-100.0 - random.nextDouble() * 100.0);  // (-200, -100)
            }
        }

        private static void testCase(double value) {
            Asserts.assertEquals(exp_double_verified(value), exp_double(value));
        }

        public static double exp_double(double a) {
            return Math.exp(a);
        }

        public static double exp_double_verified(double a) {
            return Math.exp(a);
        }
    }
}
