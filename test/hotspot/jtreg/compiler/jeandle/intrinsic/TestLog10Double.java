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
 * @run main/othervm compiler.jeandle.intrinsic.TestLog10Double
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

public class TestLog10Double {
    public static void main(String[] args) throws Exception {
        boolean is_x86 = System.getProperty("os.arch").equals("amd64");
        String dump_path = System.getProperty("java.io.tmpdir");

        // intrinsic by StubRoutine
        ArrayList<String> command_args = new ArrayList<String>(List.of(
            "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
            "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR",
            "-XX:JeandleDumpDirectory="+dump_path, "-XX:+JeandleUseHotspotIntrinsics",
            "-XX:CompileCommand=compileonly,"+TestWrapper.class.getName()+"::log10_double"));
        if (is_x86) {
          command_args.addAll(List.of("-XX:+UnlockDiagnosticVMOptions", "-XX:+UseLibmIntrinsic"));
        }
        command_args.add(TestWrapper.class.getName());

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(command_args);
        OutputAnalyzer output = ProcessTools.executeCommand(pb);

        output.shouldHaveExitValue(0)
              .shouldContain("Method `static jdouble java.lang.Math.log10(jdouble)` is parsed as intrinsic");

        // Verify llvm IR
        FileCheck checker = new FileCheck(dump_path, TestWrapper.class.getMethod("log10_double", double.class), false);
        // find compiled method
        checker.checkPattern("define hotspotcc double .*compiler_jeandle_intrinsic_TestLog10Double\\$TestWrapper_log10_double.*(double %0)");
        // check IR
        checker.checkNext("entry:");
        checker.checkNext("br label %bci_0");
        checker.checkNext("bci_0:");
        if (is_x86) {
            checker.checkNext("call double @StubRoutines_dlog10");
        } else {
            checker.checkNextPattern("call double inttoptr \\(i64 (\\d+) to ptr\\).*#\\d+");
        }
        checker.checkNext("ret double");
        // check gc-leaf-function
        if (is_x86) {
            checker.checkPattern("declare double @StubRoutines_dlog10.*#\\d+");
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
                "-XX:CompileCommand=compileonly,"+TestWrapper.class.getName()+"::log10_double",
                "-XX:+UnlockDiagnosticVMOptions", "-XX:-UseLibmIntrinsic", "-XX:+JeandleUseHotspotIntrinsics",
                TestWrapper.class.getName()));
            pb = ProcessTools.createLimitedTestJavaProcessBuilder(command_args);
            output = ProcessTools.executeCommand(pb);
            output.shouldHaveExitValue(0)
                .shouldContain("Method `static jdouble java.lang.Math.log10(jdouble)` is parsed as intrinsic");

            command_args = new ArrayList<String>(List.of(
                "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
                "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory="+dump_path,
                "-XX:CompileCommand=compileonly,"+TestWrapper.class.getName()+"::log10_double",
                "-XX:+UnlockDiagnosticVMOptions", "-XX:-UseLibmIntrinsic", "-XX:+JeandleUseHotspotIntrinsics",
                TestWrapper.class.getName()));
            pb = ProcessTools.createLimitedTestJavaProcessBuilder(command_args);
            output = ProcessTools.executeCommand(pb);
            output.shouldHaveExitValue(0)
                .shouldContain("Method `static jdouble java.lang.Math.log10(jdouble)` is parsed as intrinsic");
            // Verify llvm IR
            checker = new FileCheck(dump_path, TestWrapper.class.getMethod("log10_double", double.class), false);
            // find compiled method
            checker.checkPattern("define hotspotcc double .*compiler_jeandle_intrinsic_TestLog10Double\\$TestWrapper_log10_double.*(double %0)");
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
        public static void main(String[] args) {
            Random random = new Random();

            // Test special values
            testCase(1.0d);                    // log10(1) = 0
            testCase(Math.E);                  // log10(e)
            testCase(0.0d);                    // log10(0) = -Infinity
            testCase(Double.NaN);
            testCase(Double.POSITIVE_INFINITY);
            testCase(Double.NEGATIVE_INFINITY);
            testCase(-1.0d);                   // log10(negative) = NaN

            // Test boundary values
            testCase(Double.MIN_VALUE);
            testCase(Double.MIN_NORMAL);
            testCase(Double.MAX_VALUE);

            // Test random values in different ranges
            for (int i = 0; i < 100; i++) {
                testCase(random.nextDouble());                    // (0, 1)
                testCase(1.0 + random.nextDouble() * 9.0);       // (1, 10)
                testCase(10.0 + random.nextDouble() * 90.0);     // (10, 100)
                testCase(100.0 * (1.0 + random.nextDouble()));   // (100, 200)
            }
        }

        private static void testCase(double value) {
            Asserts.assertEquals(log10_double_verified(value), log10_double(value));
        }

        public static double log10_double(double a) {
            return Math.log10(a);
        }

        public static double log10_double_verified(double a) {
            return Math.log10(a);
        }
    }
}
