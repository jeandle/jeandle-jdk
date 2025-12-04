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
 * @run main/othervm compiler.jeandle.intrinsic.TestCosDouble
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

public class TestCosDouble {
    public static void main(String[] args) throws Exception {
        boolean is_x86 = System.getProperty("os.arch").equals("amd64");
        String dump_path = System.getProperty("java.io.tmpdir");

        // intrinsic by StubRoutine
        ArrayList<String> command_args = new ArrayList<String>(List.of(
            "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
            "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR",
            "-XX:JeandleDumpDirectory="+dump_path,
            "-XX:CompileCommand=compileonly,"+TestEqualsWrapper.class.getName()+"::cos_double",
            "-XX:+JeandleUseRuntimeIntrinsics"));
        if (is_x86) {
          command_args.addAll(List.of("-XX:+UnlockDiagnosticVMOptions", "-XX:+UseLibmIntrinsic"));
        }
        command_args.add(TestEqualsWrapper.class.getName());

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(command_args);
        OutputAnalyzer output = ProcessTools.executeCommand(pb);

        output.shouldHaveExitValue(0)
              .shouldContain("Method `static jdouble java.lang.Math.cos(jdouble)` is parsed as intrinsic");

        // Verify llvm IR
        FileCheck checker = new FileCheck(dump_path, TestEqualsWrapper.class.getMethod("cos_double", double.class), false);
        // find compiled method
        checker.check("define hotspotcc double @\"compiler_jeandle_intrinsic_TestCosDouble$TestEqualsWrapper_cos_double");
        // check IR
        checker.checkNext("entry:");
        checker.checkNext("br label %bci_0");
        checker.checkNext("bci_0:");
        checker.checkNext("call double @StubRoutines_dcos");
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
                "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory="+dump_path,
                "-XX:CompileCommand=compileonly,"+TestEqualsWrapper.class.getName()+"::cos_double",
                "-XX:+UnlockDiagnosticVMOptions", "-XX:-UseLibmIntrinsic", "-XX:+JeandleUseRuntimeIntrinsics",
                TestEqualsWrapper.class.getName()));
            pb = ProcessTools.createLimitedTestJavaProcessBuilder(command_args);
            output = ProcessTools.executeCommand(pb);
            output.shouldHaveExitValue(0)
                .shouldContain("Method `static jdouble java.lang.Math.cos(jdouble)` is parsed as intrinsic");
            // Verify llvm IR
            checker = new FileCheck(dump_path, TestEqualsWrapper.class.getMethod("cos_double", double.class), false);
            // find compiled method
            checker.check("define hotspotcc double @\"compiler_jeandle_intrinsic_TestCosDouble$TestEqualsWrapper_cos_double");
            // check IR
            checker.checkNext("entry:");
            checker.checkNext("br label %bci_0");
            checker.checkNext("bci_0:");
            checker.checkNext("call double @SharedRuntime_dcos");
            checker.checkNext("ret double");
        }

        // intrinsic by LLVM
        if (is_x86) { // TODO: add support for other architectures
            dump_path = System.getProperty("java.io.tmpdir")+"/test3";
            Path tmp3 = Path.of(dump_path);
            if (!Files.exists(tmp3)) {
                Files.createDirectory(tmp3);
            }

            command_args = new ArrayList<String>(List.of(
                "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
                "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory="+dump_path,
                "-XX:CompileCommand=compileonly,"+TestApproximateWrapper.class.getName()+"::cos_double",
                "-XX:-JeandleUseRuntimeIntrinsics",
                TestApproximateWrapper.class.getName()));
            pb = ProcessTools.createLimitedTestJavaProcessBuilder(command_args);
            output = ProcessTools.executeCommand(pb);
            output.shouldHaveExitValue(0)
                .shouldContain("Method `static jdouble java.lang.Math.cos(jdouble)` is parsed as intrinsic");
            // Verify llvm IR
            checker = new FileCheck(dump_path, TestApproximateWrapper.class.getMethod("cos_double", double.class), false);
            // find compiled method
            checker.check("define hotspotcc double @\"compiler_jeandle_intrinsic_TestCosDouble$TestApproximateWrapper_cos_double");
            // check IR
            checker.checkNext("entry:");
            checker.checkNext("br label %bci_0");
            checker.checkNext("bci_0:");
            checker.checkNext("call double @llvm.cos.f64");
            checker.checkNext("ret double");
        }
    }

    static public class TestEqualsWrapper {
        static double v = Math.abs(1.0d);   // Force load java.lang.Math class
        public static void main(String[] args) {
            Random random = new Random();
            Asserts.assertEquals(cos_double_verified(1.5d), cos_double(1.5d));
            Asserts.assertEquals(cos_double_verified(-1.5d), cos_double(-1.5d));
            Asserts.assertEquals(cos_double_verified(Double.NaN), cos_double(Double.NaN));
            Asserts.assertEquals(cos_double_verified(Double.POSITIVE_INFINITY), cos_double(Double.POSITIVE_INFINITY));
            Asserts.assertEquals(cos_double_verified(Double.NEGATIVE_INFINITY), cos_double(Double.NEGATIVE_INFINITY));
            for (int i=0; i< 1000; i++) {
                double d = random.nextDouble();
                Asserts.assertEquals(cos_double_verified(d) , cos_double(d));
            }
        }

        public static double cos_double(double a) {
            return Math.cos(a);
        }

        public static double cos_double_verified(double a) {
            return Math.cos(a);
        }
    }

    static public class TestApproximateWrapper {
        static double v = Math.abs(1.0d);   // Force load java.lang.Math class
        static double epsilon = 1e-15;
        public static void main(String[] args) {
            Random random = new Random();
            Asserts.assertLessThan(Math.abs(cos_double_verified(1.5d) - cos_double(1.5d)), epsilon);
            Asserts.assertLessThan(Math.abs(cos_double_verified(-1.5d) - cos_double(-1.5d)), epsilon);
            Asserts.assertEquals(cos_double_verified(Double.NaN), cos_double(Double.NaN));
            Asserts.assertEquals(cos_double_verified(Double.POSITIVE_INFINITY), cos_double(Double.POSITIVE_INFINITY));
            Asserts.assertEquals(cos_double_verified(Double.NEGATIVE_INFINITY), cos_double(Double.NEGATIVE_INFINITY));
            for (int i=0; i< 1000; i++) {
                double d = random.nextDouble();
                Asserts.assertLessThan(Math.abs(cos_double_verified(d) - cos_double(d)), epsilon);
            }
        }

        public static double cos_double(double a) {
            return Math.cos(a);
        }

        public static double cos_double_verified(double a) {
            return Math.cos(a);
        }
    }
}
