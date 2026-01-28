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
 * @run main/othervm compiler.jeandle.intrinsic.TestAbsDouble
 */

package compiler.jeandle.intrinsic;

import compiler.jeandle.fileCheck.FileCheck;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestAbsDouble {
    public static void main(String[] args) throws Exception {
        String dump_path = System.getProperty("java.io.tmpdir");
        ArrayList<String> command_args = new ArrayList<String>(List.of(
            "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
            "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR",
            "-XX:JeandleDumpDirectory="+dump_path,
            "-XX:CompileCommand=compileonly,"+TestWrapper.class.getName()+"::abs_double",
            "-XX:CompileCommand=compileonly,"+TestWrapper.class.getName()+"::abs_double_with_const_unaligned",
            TestWrapper.class.getName()
        ));
    
        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(command_args);
        OutputAnalyzer output = ProcessTools.executeCommand(pb);

        output.shouldHaveExitValue(0)
              .shouldContain("Method `static jdouble java.lang.Math.abs(jdouble)` is parsed as intrinsic");

        // Verify llvm IR
        FileCheck checker = new FileCheck(dump_path, TestWrapper.class.getMethod("abs_double", double.class), false);
        // find compiled method
        checker.check("define hotspotcc double @\"compiler_jeandle_intrinsic_TestAbsDouble$TestWrapper_abs_double");
        // check IR
        checker.checkNext("entry:");
        checker.checkNext("br label %bci_0");
        checker.checkNext("bci_0:");
        // the llvm intrinsic is used
        checker.checkNext("call double @llvm.fabs.f64(double %0)");
    }

    static public class TestWrapper {
        static double v = Math.abs(1.0d);   // Force load java.lang.Math class
        public static void main(String[] args) {
            Random random = new Random();
            Asserts.assertEquals(1.5d, abs_double(1.5d));
            Asserts.assertEquals(1.5d, abs_double(-1.5d));
            Asserts.assertEquals(Double.NaN, abs_double(Double.NaN));
            Asserts.assertEquals(Double.POSITIVE_INFINITY, abs_double(Double.POSITIVE_INFINITY));
            Asserts.assertEquals(Double.POSITIVE_INFINITY, abs_double(Double.NEGATIVE_INFINITY));
            for (int i=0; i< 1000; i++) {
                double d = random.nextDouble();
                double r = d > 0.0d ? d : -1*d;
                Asserts.assertEquals(r , abs_double(d));
            }
            Asserts.assertEquals(1.5d, abs_double_with_const_unaligned(1.5d));
        }

        public static double abs_double(double a) {
            return Math.abs(a);
        }

        public static double abs_double_with_const_unaligned(double a) {
            blackhole(1.0); // Insert a double constant (1.0) to break 16-byte alignment
            return Math.abs(a);
        }

        public static void blackhole(double a) {}
    }
}
