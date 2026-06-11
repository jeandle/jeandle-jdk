/*
 * Copyright (c) 2025, 2026, the Jeandle-JDK Authors. All Rights Reserved.
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
 * @key randomness
 * @library /test/lib /
 * @build jdk.test.lib.Asserts
 * @run main/othervm compiler.jeandle.intrinsic.TestAbsFloat
 */

package compiler.jeandle.intrinsic;

import compiler.jeandle.fileCheck.FileCheck;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestAbsFloat {
    public static void main(String[] args) throws Exception {
        String dump_path = Files.createTempDirectory("jeandle_test_absfloat").toString();
        ArrayList<String> command_args = new ArrayList<String>(List.of(
            "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
            "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR",
            "-XX:JeandleDumpDirectory="+dump_path,
            "-XX:CompileCommand=compileonly,"+TestWrapper.class.getName()+"::abs_float",
            "-XX:CompileCommand=compileonly,"+TestWrapper.class.getName()+"::unaligned_abs_float",
            TestWrapper.class.getName()
        ));

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(command_args);
        OutputAnalyzer output = ProcessTools.executeCommand(pb);

        output.shouldHaveExitValue(0)
              .shouldContain("is parsed as intrinsic");

        // Verify the llvm intrinsic is used — only check the intrinsic call, not control flow
        FileCheck checker = new FileCheck(dump_path, TestWrapper.class.getMethod("abs_float", float.class), false);
        checker.checkPattern("llvm\\.fabs\\.f32");
    }

    static public class TestWrapper {
        static float v = Math.abs(1.0f);   // Force load java.lang.Math class
        public static void main(String[] args) {
            Random random = new Random();

            // Basic values
            Asserts.assertEquals(1.5f, abs_float(1.5f), "abs(1.5f)");
            Asserts.assertEquals(1.5f, abs_float(-1.5f), "abs(-1.5f)");
            Asserts.assertEquals(Float.NaN, abs_float(Float.NaN), "abs(NaN)");
            Asserts.assertEquals(Float.POSITIVE_INFINITY, abs_float(Float.POSITIVE_INFINITY), "abs(+Inf)");
            Asserts.assertEquals(Float.POSITIVE_INFINITY, abs_float(Float.NEGATIVE_INFINITY), "abs(-Inf)");

            // Negative zero: Math.abs(-0.0f) should be 0.0f
            Asserts.assertTrue(Float.floatToRawIntBits(abs_float(-0.0f))
                            == Float.floatToRawIntBits(0.0f),
                    "abs(-0.0f) should be +0.0f");
            Asserts.assertTrue(Float.floatToRawIntBits(abs_float(0.0f))
                            == Float.floatToRawIntBits(0.0f),
                    "abs(0.0f) should be +0.0f");

            // Subnormal values
            Asserts.assertEquals(Float.MIN_VALUE, abs_float(Float.MIN_VALUE),
                    "abs(MIN_VALUEf) = MIN_VALUEf (already positive)");
            Asserts.assertEquals(Float.MIN_VALUE, abs_float(-Float.MIN_VALUE),
                    "abs(-MIN_VALUEf) = MIN_VALUEf");

            // Random values
            for (int i = 0; i < 1000; i++) {
                float f = random.nextFloat();
                float r = f > 0.0f ? f : -1 * f;
                Asserts.assertEquals(r, abs_float(f), "abs random float");
            }

            // Unaligned access test (matching TestAbsDouble pattern)
            Asserts.assertEquals(1.5f, unaligned_abs_float(1.5f), "unaligned abs(1.5f)");
        }

        public static float abs_float(float a) {
            return Math.abs(a);
        }

        public static float unaligned_abs_float(float a) {
            blackhole(1.0f); // Insert a float constant to break alignment
            return Math.abs(a);
        }

        public static void blackhole(float a) {}
    }
}
