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
 * @key randomness
 * @summary Test the intrinsic implementation of Math.ceil
 * @requires os.arch=="amd64" | os.arch=="x86_64" | os.arch=="aarch64"
 * @library /test/lib /
 * @build jdk.test.lib.Asserts
 * @run main/othervm compiler.jeandle.intrinsic.TestCeilDouble
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

public class TestCeilDouble {
    public static void main(String[] args) throws Exception {
        String dumpPath = Files.createTempDirectory("jeandle_test_ceil").toString();

        ArrayList<String> commandArgs = new ArrayList<>(List.of(
                "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
                "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dumpPath,
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::ceil_double",
                TestWrapper.class.getName()));

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(commandArgs);
        OutputAnalyzer output = ProcessTools.executeCommand(pb);

        output.shouldHaveExitValue(0)
              .shouldContain("is parsed as intrinsic");

        // Verify llvm IR — only check the intrinsic is used, not control flow
        FileCheck checker = new FileCheck(dumpPath,
                TestWrapper.class.getMethod("ceil_double", double.class), false);
        checker.checkPattern("llvm\\.ceil");
    }

    static class TestWrapper {
        static double v = Math.ceil(1.0d); // Force load java.lang.Math class

        public static void main(String[] args) {
            Random random = new Random();

            // Special values
            Asserts.assertTrue(Double.isNaN(ceil_double(Double.NaN)), "ceil(NaN)");
            Asserts.assertTrue(ceil_double(Double.POSITIVE_INFINITY) == Double.POSITIVE_INFINITY, "ceil(+Inf)");
            Asserts.assertTrue(ceil_double(Double.NEGATIVE_INFINITY) == Double.NEGATIVE_INFINITY, "ceil(-Inf)");
            Asserts.assertTrue(Double.doubleToRawLongBits(ceil_double(-0.0d)) == Double.doubleToRawLongBits(-0.0d),
                    "ceil(-0.0) should be -0.0");
            Asserts.assertTrue(Double.doubleToRawLongBits(ceil_double(0.0d)) == Double.doubleToRawLongBits(0.0d),
                    "ceil(0.0) should be 0.0");

            // Already-integer values
            Asserts.assertEquals(3.0d, ceil_double(3.0d), "ceil(3.0)");
            Asserts.assertEquals(-5.0d, ceil_double(-5.0d), "ceil(-5.0)");

            // Fractional values
            Asserts.assertEquals(4.0d, ceil_double(3.7d), "ceil(3.7)");
            Asserts.assertEquals(-3.0d, ceil_double(-3.7d), "ceil(-3.7)");
            Asserts.assertEquals(4.0d, ceil_double(3.1d), "ceil(3.1)");
            Asserts.assertEquals(-3.0d, ceil_double(-3.1d), "ceil(-3.1)");

            // Near boundary values
            Asserts.assertEquals(1.0d, ceil_double(0.49999999999999994d), "ceil(0.4999...)");
            // Math.ceil(-0.49999999999999994) == -0.0
            Asserts.assertTrue(Double.doubleToRawLongBits(ceil_double(-0.49999999999999994d))
                            == Double.doubleToRawLongBits(-0.0d),
                    "ceil(-0.4999...) should be -0.0");

            // Subnormal
            Asserts.assertEquals(1.0d, ceil_double(Double.MIN_VALUE), "ceil(MIN_VALUE)");
            Asserts.assertEquals(1.0d, ceil_double(Double.MIN_NORMAL), "ceil(MIN_NORMAL)");

            // Large value (already integer)
            Asserts.assertEquals(1e15, ceil_double(1e15 - 0.5), "ceil(1e15-0.5)");

            // Random values
            for (int i = 0; i < 1000; i++) {
                double d = random.nextDouble() * 2000.0 - 1000.0;
                double expected = StrictMath.ceil(d);
                double actual = ceil_double(d);
                Asserts.assertEquals(expected, actual, "ceil random mismatch for d=" + d);
            }

            System.out.println("TestCeilDouble PASSED");
        }

        public static double ceil_double(double a) {
            return Math.ceil(a);
        }
    }
}
