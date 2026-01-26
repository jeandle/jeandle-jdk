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
 * @run main/othervm compiler.jeandle.intrinsic.TestTanDouble
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

public class TestTanDouble {
    public static void main(String[] args) throws Exception {
        boolean is_x86 = System.getProperty("os.arch").equals("amd64");
        String dump_path = System.getProperty("java.io.tmpdir");

        // intrinsic by StubRoutine
        if (is_x86) {
            ArrayList<String> command_args = new ArrayList<String>(List.of(
                "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
                "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory="+dump_path,
                "-XX:CompileCommand=compileonly,"+TestWrapper.class.getName()+"::tan_double",
                "-XX:+UnlockDiagnosticVMOptions", "-XX:+UseLibmIntrinsic", "-XX:+JeandleUseHotspotIntrinsics",
                TestWrapper.class.getName()));

            ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(command_args);
            OutputAnalyzer output = ProcessTools.executeCommand(pb);

            output.shouldHaveExitValue(0)
                .shouldContain("Method `static jdouble java.lang.Math.tan(jdouble)` is parsed as intrinsic");

            // Verify llvm IR
            FileCheck checker = new FileCheck(dump_path, TestWrapper.class.getMethod("tan_double", double.class), false);
            // find compiled method
            checker.check("define hotspotcc double @\"compiler_jeandle_intrinsic_TestTanDouble$TestWrapper_tan_double");
            // check IR
            checker.checkNext("entry:");
            checker.checkNext("br label %bci_0");
            checker.checkNext("bci_0:");
            checker.checkNext("call double @StubRoutines_dtan");
            checker.checkNext("ret double");
        }

        // intrinsic by SharedRuntime
        dump_path = System.getProperty("java.io.tmpdir")+"/test2";
        Path tmp2 = Path.of(dump_path);
        if (!Files.exists(tmp2)) {
            Files.createDirectory(tmp2);
        }

        ArrayList<String> command_args = new ArrayList<String>(List.of(
            "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
            "-Xlog:jeandle=debug", "-XX:+ForceUnreachable",
            "-XX:CompileCommand=compileonly,"+TestWrapper.class.getName()+"::tan_double",
            "-XX:+JeandleUseHotspotIntrinsics"));
        if (is_x86) {
            command_args.addAll(List.of("-XX:+UnlockDiagnosticVMOptions", "-XX:-UseLibmIntrinsic"));
        }
        command_args.add(TestWrapper.class.getName());
        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(command_args);
        OutputAnalyzer output = ProcessTools.executeCommand(pb);
        output.shouldHaveExitValue(0)
            .shouldContain("Method `static jdouble java.lang.Math.tan(jdouble)` is parsed as intrinsic");

        command_args = new ArrayList<String>(List.of(
            "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
            "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR",
            "-XX:JeandleDumpDirectory="+dump_path,
            "-XX:CompileCommand=compileonly,"+TestWrapper.class.getName()+"::tan_double",
            "-XX:+JeandleUseHotspotIntrinsics"));
        if (is_x86) {
            command_args.addAll(List.of("-XX:+UnlockDiagnosticVMOptions", "-XX:-UseLibmIntrinsic"));
        }
        command_args.add(TestWrapper.class.getName());
        pb = ProcessTools.createLimitedTestJavaProcessBuilder(command_args);
        output = ProcessTools.executeCommand(pb);
        output.shouldHaveExitValue(0)
            .shouldContain("Method `static jdouble java.lang.Math.tan(jdouble)` is parsed as intrinsic");
        // Verify llvm IR
        FileCheck checker = new FileCheck(dump_path, TestWrapper.class.getMethod("tan_double", double.class), false);
        // find compiled method
        checker.check("define hotspotcc double @\"compiler_jeandle_intrinsic_TestTanDouble$TestWrapper_tan_double");
        // check IR
        checker.checkNext("entry:");
        checker.checkNext("br label %bci_0");
        checker.checkNext("bci_0:");
        checker.checkNextPattern("call double inttoptr \\(i64 (\\d+) to ptr\\)");
        checker.checkNext("ret double");
    }

    static public class TestWrapper {
        static double v = Math.abs(1.0d);   // Force load java.lang.Math class
        public static void main(String[] args) {
            Random random = new Random();
            Asserts.assertEquals(tan_double_verified(1.5d), tan_double(1.5d));
            Asserts.assertEquals(tan_double_verified(-1.5d), tan_double(-1.5d));
            Asserts.assertEquals(tan_double_verified(Double.NaN), tan_double(Double.NaN));
            Asserts.assertEquals(tan_double_verified(Double.POSITIVE_INFINITY), tan_double(Double.POSITIVE_INFINITY));
            Asserts.assertEquals(tan_double_verified(Double.NEGATIVE_INFINITY), tan_double(Double.NEGATIVE_INFINITY));
            for (int i=0; i< 1000; i++) {
                double d = random.nextDouble();
                Asserts.assertEquals(tan_double_verified(d) , tan_double(d));
            }
        }

        public static double tan_double(double a) {
            return Math.tan(a);
        }

        public static double tan_double_verified(double a) {
            return Math.tan(a);
        }
    }
}
