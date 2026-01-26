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
 * @run main/othervm compiler.jeandle.intrinsic.TestLogDouble
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

public class TestLogDouble {
    public static void main(String[] args) throws Exception {
        boolean is_x86 = System.getProperty("os.arch").equals("amd64");
        String dump_path = System.getProperty("java.io.tmpdir");

        // intrinsic by StubRoutine
        ArrayList<String> command_args = new ArrayList<String>(List.of(
            "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
            "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR",
            "-XX:JeandleDumpDirectory="+dump_path,
            "-XX:CompileCommand=compileonly,"+TestWrapper.class.getName()+"::log_double",
            "-XX:+JeandleUseHotspotIntrinsics"));
        if (is_x86) {
          command_args.addAll(List.of("-XX:+UnlockDiagnosticVMOptions", "-XX:+UseLibmIntrinsic"));
        }
        command_args.add(TestWrapper.class.getName());

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(command_args);
        OutputAnalyzer output = ProcessTools.executeCommand(pb);

        output.shouldHaveExitValue(0)
              .shouldContain("Method `static jdouble java.lang.Math.log(jdouble)` is parsed as intrinsic");

        // Verify llvm IR
        FileCheck checker = new FileCheck(dump_path, TestWrapper.class.getMethod("log_double", double.class), false);
        // find compiled method
        checker.checkPattern("define hotspotcc double .*compiler_jeandle_intrinsic_TestLogDouble\\$TestWrapper_log_double.*(double %0)");
        // check IR
        checker.checkNext("entry:");
        checker.checkNext("br label %bci_0");
        checker.checkNext("bci_0:");
        if (is_x86) {
            checker.checkNext("call double @StubRoutines_dlog");
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
                "-Xlog:jeandle=debug", "-XX:+ForceUnreachable",
                "-XX:CompileCommand=compileonly,"+TestWrapper.class.getName()+"::log_double",
                "-XX:+UnlockDiagnosticVMOptions", "-XX:-UseLibmIntrinsic", "-XX:+JeandleUseHotspotIntrinsics",
                TestWrapper.class.getName()));
            pb = ProcessTools.createLimitedTestJavaProcessBuilder(command_args);
            output = ProcessTools.executeCommand(pb);
            output.shouldHaveExitValue(0)
                .shouldContain("Method `static jdouble java.lang.Math.log(jdouble)` is parsed as intrinsic");

            command_args = new ArrayList<String>(List.of(
                "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
                "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory="+dump_path,
                "-XX:CompileCommand=compileonly,"+TestWrapper.class.getName()+"::log_double",
                "-XX:+UnlockDiagnosticVMOptions", "-XX:-UseLibmIntrinsic", "-XX:+JeandleUseHotspotIntrinsics",
                TestWrapper.class.getName()));
            pb = ProcessTools.createLimitedTestJavaProcessBuilder(command_args);
            output = ProcessTools.executeCommand(pb);
            output.shouldHaveExitValue(0)
                .shouldContain("Method `static jdouble java.lang.Math.log(jdouble)` is parsed as intrinsic");
            // Verify llvm IR
            checker = new FileCheck(dump_path, TestWrapper.class.getMethod("log_double", double.class), false);
            // find compiled method
            checker.checkPattern("define hotspotcc double .*compiler_jeandle_intrinsic_TestLogDouble\\$TestWrapper_log_double.*(double %0)");
            // check IR
            checker.checkNext("entry:");
            checker.checkNext("br label %bci_0");
            checker.checkNext("bci_0:");
            checker.checkNextPattern("call double inttoptr \\(i64 (\\d+) to ptr\\)");
            checker.checkNext("ret double");
        }
    }

    static public class TestWrapper {
        static double v = Math.log(1.0d);   // Force load java.lang.Math class with log function
        public static void main(String[] args) {
            Random random = new Random();

            // Test special values
            Asserts.assertEquals(log_double_verified(1.0d), log_double(1.0d));        // log(1) = 0
            Asserts.assertEquals(log_double_verified(Math.E), log_double(Math.E));    // log(e) = 1
            Asserts.assertEquals(log_double_verified(0.0d), log_double(0.0d));        // log(0) = -Infinity
            Asserts.assertEquals(log_double_verified(Double.NaN), log_double(Double.NaN));
            Asserts.assertEquals(log_double_verified(Double.POSITIVE_INFINITY), log_double(Double.POSITIVE_INFINITY));
            Asserts.assertEquals(log_double_verified(Double.NEGATIVE_INFINITY), log_double(Double.NEGATIVE_INFINITY));
            Asserts.assertEquals(log_double_verified(-1.0d), log_double(-1.0d));      // log(negative) = NaN

            // Test very small positive values
            Asserts.assertEquals(log_double_verified(Double.MIN_VALUE), log_double(Double.MIN_VALUE));
            Asserts.assertEquals(log_double_verified(Double.MIN_NORMAL), log_double(Double.MIN_NORMAL));

            // Test large values
            Asserts.assertEquals(log_double_verified(Double.MAX_VALUE), log_double(Double.MAX_VALUE));

            // Test random values in different ranges
            for (int i = 0; i < 250; i++) {
                double d = random.nextDouble();  // (0, 1)
                Asserts.assertEquals(log_double_verified(d), log_double(d));
            }

            for (int i = 0; i < 250; i++) {
                double d = 1.0 + random.nextDouble() * 9.0;  // (1, 10)
                Asserts.assertEquals(log_double_verified(d), log_double(d));
            }

            for (int i = 0; i < 250; i++) {
                double d = 10.0 + random.nextDouble() * 90.0;  // (10, 100)
                Asserts.assertEquals(log_double_verified(d), log_double(d));
            }

            for (int i = 0; i < 250; i++) {
                double d = 100.0 * (1.0 + random.nextDouble() * 99.0);  // (100, 10000)
                Asserts.assertEquals(log_double_verified(d), log_double(d));
            }
        }

        public static double log_double(double a) {
            return Math.log(a);
        }

        public static double log_double_verified(double a) {
            return Math.log(a);
        }
    }
}
