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
 * @summary Test the intrinsic implementation of Math.rint (round to nearest, ties to even)
 * @requires os.arch=="amd64" | os.arch=="x86_64" | os.arch=="aarch64"
 * @library /test/lib /
 * @build jdk.test.lib.Asserts
 * @run main/othervm compiler.jeandle.intrinsic.TestRintDouble
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

public class TestRintDouble {
    public static void main(String[] args) throws Exception {
        String dumpPath = Files.createTempDirectory("jeandle_test_rint").toString();

        ArrayList<String> commandArgs = new ArrayList<>(List.of(
                "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
                "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dumpPath,
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::rint_double",
                TestWrapper.class.getName()));

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(commandArgs);
        OutputAnalyzer output = ProcessTools.executeCommand(pb);

        output.shouldHaveExitValue(0)
              .shouldContain("is parsed as intrinsic");

        // Verify llvm IR — only check the intrinsic is used, not control flow
        FileCheck checker = new FileCheck(dumpPath,
                TestWrapper.class.getMethod("rint_double", double.class), false);
        checker.checkPattern("llvm\\.rint");
    }

    static class TestWrapper {
        static double v = Math.rint(1.0d); // Force load java.lang.Math class

        public static void main(String[] args) {
            Random random = new Random();

            // Special values
            Asserts.assertTrue(Double.isNaN(rint_double(Double.NaN)), "rint(NaN)");
            Asserts.assertTrue(rint_double(Double.POSITIVE_INFINITY) == Double.POSITIVE_INFINITY, "rint(+Inf)");
            Asserts.assertTrue(rint_double(Double.NEGATIVE_INFINITY) == Double.NEGATIVE_INFINITY, "rint(-Inf)");
            Asserts.assertTrue(Double.doubleToRawLongBits(rint_double(-0.0d)) == Double.doubleToRawLongBits(-0.0d),
                    "rint(-0.0) should be -0.0");
            Asserts.assertTrue(Double.doubleToRawLongBits(rint_double(0.0d)) == Double.doubleToRawLongBits(0.0d),
                    "rint(0.0) should be 0.0");

            // Already-integer values
            Asserts.assertEquals(3.0d, rint_double(3.0d), "rint(3.0)");
            Asserts.assertEquals(-5.0d, rint_double(-5.0d), "rint(-5.0)");

            // Ties-to-even (banker's rounding) — critical corner cases
            Asserts.assertEquals(0.0d, rint_double(0.5d), "rint(0.5) -> 0.0 (round to even)");
            Asserts.assertEquals(2.0d, rint_double(1.5d), "rint(1.5) -> 2.0 (round to even)");
            Asserts.assertEquals(2.0d, rint_double(2.5d), "rint(2.5) -> 2.0 (round to even)");
            Asserts.assertEquals(4.0d, rint_double(3.5d), "rint(3.5) -> 4.0 (round to even)");
            Asserts.assertEquals(4.0d, rint_double(4.5d), "rint(4.5) -> 4.0 (round to even)");
            // Negative ties-to-even
            Asserts.assertTrue(Double.doubleToRawLongBits(rint_double(-0.5d))
                            == Double.doubleToRawLongBits(-0.0d),
                    "rint(-0.5) -> -0.0 (round to even)");
            Asserts.assertEquals(-2.0d, rint_double(-1.5d), "rint(-1.5) -> -2.0 (round to even)");
            Asserts.assertEquals(-2.0d, rint_double(-2.5d), "rint(-2.5) -> -2.0 (round to even)");

            // Non-tie fractional values
            Asserts.assertEquals(4.0d, rint_double(3.7d), "rint(3.7) -> 4.0");
            Asserts.assertEquals(-4.0d, rint_double(-3.7d), "rint(-3.7) -> -4.0");
            Asserts.assertEquals(3.0d, rint_double(3.2d), "rint(3.2) -> 3.0");
            Asserts.assertEquals(-3.0d, rint_double(-3.2d), "rint(-3.2) -> -3.0");

            // Subnormal
            Asserts.assertEquals(0.0d, rint_double(Double.MIN_VALUE), "rint(MIN_VALUE)");
            Asserts.assertEquals(0.0d, rint_double(Double.MIN_NORMAL), "rint(MIN_NORMAL)");

            // Random values
            for (int i = 0; i < 1000; i++) {
                double d = random.nextDouble() * 2000.0 - 1000.0;
                double expected = StrictMath.rint(d);
                double actual = rint_double(d);
                Asserts.assertEquals(expected, actual, "rint random mismatch for d=" + d);
            }

            System.out.println("TestRintDouble PASSED");
        }

        public static double rint_double(double a) {
            return Math.rint(a);
        }
    }
}
