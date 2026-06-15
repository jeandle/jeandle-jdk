/*
 * Copyright (c) 2026, the Jeandle-JDK Authors. All Rights Reserved.
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
 * @summary Test the intrinsic implementation of Math.sqrt and StrictMath.sqrt
 * @library /test/lib /
 * @build jdk.test.lib.Asserts
 * @run main/othervm compiler.jeandle.intrinsic.TestSqrtDouble
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

public class TestSqrtDouble {
    public static void main(String[] args) throws Exception {
        String dumpPath = Files.createTempDirectory("jeandle_test_sqrt").toString();

        ArrayList<String> commandArgs = new ArrayList<>(List.of(
                "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
                "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dumpPath,
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::sqrt_double",
                TestWrapper.class.getName()));

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(commandArgs);
        OutputAnalyzer output = ProcessTools.executeCommand(pb);

        output.shouldHaveExitValue(0)
              .shouldContain("is parsed as intrinsic");

        // Verify llvm IR — only check the intrinsic call, not control flow structure
        FileCheck checker = new FileCheck(dumpPath,
                TestWrapper.class.getMethod("sqrt_double", double.class), false);
        checker.checkPattern("llvm\\.sqrt\\.f64");
    }

    static class TestWrapper {
        static double v = Math.sqrt(1.0d); // Force load java.lang.Math class

        public static void main(String[] args) {
            Random random = new Random();

            // Special values
            Asserts.assertEquals(0.0d, sqrt_double(0.0d), "sqrt(0.0)");
            Asserts.assertTrue(Double.doubleToRawLongBits(sqrt_double(-0.0d)) == Double.doubleToRawLongBits(-0.0d),
                    "sqrt(-0.0) should be -0.0");
            Asserts.assertTrue(Double.isNaN(sqrt_double(Double.NaN)), "sqrt(NaN)");
            Asserts.assertTrue(Double.isInfinite(sqrt_double(Double.POSITIVE_INFINITY))
                    && sqrt_double(Double.POSITIVE_INFINITY) > 0, "sqrt(+Inf)");
            Asserts.assertTrue(Double.isNaN(sqrt_double(Double.NEGATIVE_INFINITY)), "sqrt(-Inf)");
            Asserts.assertTrue(Double.isNaN(sqrt_double(-1.0d)), "sqrt(-1.0)");

            // Subnormal and boundary values
            Asserts.assertEquals(StrictMath.sqrt(Double.MIN_VALUE), sqrt_double(Double.MIN_VALUE),
                    "sqrt(MIN_VALUE)");
            Asserts.assertEquals(StrictMath.sqrt(Double.MIN_NORMAL), sqrt_double(Double.MIN_NORMAL),
                    "sqrt(MIN_NORMAL)");
            Asserts.assertEquals(StrictMath.sqrt(Double.MAX_VALUE), sqrt_double(Double.MAX_VALUE),
                    "sqrt(MAX_VALUE)");

            // Common values
            Asserts.assertEquals(2.0d, sqrt_double(4.0d), "sqrt(4.0)");
            Asserts.assertEquals(3.0d, sqrt_double(9.0d), "sqrt(9.0)");
            Asserts.assertEquals(StrictMath.sqrt(2.0d), sqrt_double(2.0d), "sqrt(2.0)");

            // Random positive values
            for (int i = 0; i < 1000; i++) {
                double d = Math.abs(random.nextDouble()) * 1e6;
                double expected = StrictMath.sqrt(d);
                double actual = sqrt_double(d);
                Asserts.assertLTE(Math.abs(expected - actual), Math.ulp(expected) * 2,
                        "sqrt random: expected=" + expected + " actual=" + actual);
            }

            System.out.println("TestSqrtDouble PASSED");
        }

        public static double sqrt_double(double a) {
            return Math.sqrt(a);
        }
    }
}
