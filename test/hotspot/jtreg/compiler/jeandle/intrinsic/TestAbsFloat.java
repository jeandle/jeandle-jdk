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
 * @run main/othervm compiler.jeandle.intrinsic.TestAbsFloat
 */

package compiler.jeandle.intrinsic;

import compiler.jeandle.fileCheck.FileCheck;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestAbsFloat {
    public static void main(String[] args) throws Exception {
        String dump_path = System.getProperty("java.io.tmpdir");
        ArrayList<String> command_args = new ArrayList<String>(List.of(
            "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
            "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR",
            "-XX:JeandleDumpDirectory="+dump_path,
            "-XX:CompileCommand=compileonly,"+TestWrapper.class.getName()+"::abs_float",
            TestWrapper.class.getName()
        ));
    
        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(command_args);
        OutputAnalyzer output = ProcessTools.executeCommand(pb);

        output.shouldHaveExitValue(0)
              .shouldContain("Method `static jfloat java.lang.Math.abs(jfloat)` is parsed as intrinsic");

        // Verify llvm IR
        FileCheck checker = new FileCheck(dump_path, TestWrapper.class.getMethod("abs_float", float.class), false);
        // find compiled method
        checker.check("define hotspotcc float @\"compiler_jeandle_intrinsic_TestAbsFloat$TestWrapper_abs_float");
        // check IR
        checker.checkNext("entry:");
        checker.checkNext("br label %bci_0");
        checker.checkNext("bci_0:");
        // the llvm intrinsic is used
        checker.checkNext("call float @llvm.fabs.f32(float %0)");
    }

    static public class TestWrapper {
        static float v = Math.abs(1.0f);   // Force load java.lang.Math class
        public static void main(String[] args) {
            Random random = new Random();
            Asserts.assertEquals(1.5f, abs_float(1.5f));
            Asserts.assertEquals(1.5f, abs_float(-1.5f));
            Asserts.assertEquals(Float.NaN, abs_float(Float.NaN));
            Asserts.assertEquals(Float.POSITIVE_INFINITY, abs_float(Float.POSITIVE_INFINITY));
            Asserts.assertEquals(Float.POSITIVE_INFINITY, abs_float(Float.NEGATIVE_INFINITY));
            for (int i=0; i< 1000; i++) {
                float f = random.nextFloat();
                float r = f > 0.0f ? f : -1*f;
                Asserts.assertEquals(r , abs_float(f));
            }
        }

        public static float abs_float(float a) {
            return Math.abs(a);
        }
    }
}
