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
 * @run main/othervm compiler.jeandle.intrinsic.TestAbsInt
 */

package compiler.jeandle.intrinsic;

import compiler.jeandle.fileCheck.FileCheck;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestAbsInt {
    public static void main(String[] args) throws Exception {
        String dump_path = System.getProperty("java.io.tmpdir");
        ArrayList<String> command_args = new ArrayList<String>(List.of(
                "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
                "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dump_path,
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::abs_int",
                TestWrapper.class.getName()));

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(command_args);
        OutputAnalyzer output = ProcessTools.executeCommand(pb);

        output.shouldHaveExitValue(0)
                .shouldContain("Method `static jint java.lang.Math.abs(jint)` is parsed as intrinsic");

        // Verify llvm IR
        FileCheck checker = new FileCheck(dump_path, TestWrapper.class.getMethod("abs_int", int.class), false);
        // find compiled method
        checker.check(
                "define hotspotcc i32 @\"compiler_jeandle_intrinsic_TestAbsInt$TestWrapper_abs_int");
        // check IR
        checker.checkNext("entry:");
        checker.checkNext("br label %bci_0");
        checker.checkNext("bci_0:");
        // the llvm intrinsic is used
        checker.checkNext("call i32 @llvm.abs.i32(i32 %0, i1 false)");
    }

    static public class TestWrapper {
        static int v = Math.abs(1); // Force load java.lang.Math class

        public static void main(String[] args) {
            Random random = new Random();
            Asserts.assertEquals(1, abs_int(1));
            Asserts.assertEquals(1, abs_int(-1));
            Asserts.assertEquals(Integer.MAX_VALUE, abs_int(Integer.MAX_VALUE));
            Asserts.assertEquals(Integer.MIN_VALUE, abs_int(Integer.MIN_VALUE));
            for (int k = 0; k < 1000; k++) {
                int i = random.nextInt();
                int r = i > 0 ? i : -1 * i;
                Asserts.assertEquals(r, abs_int(i));
            }
        }

        public static int abs_int(int a) {
            return Math.abs(a);
        }
    }
}
