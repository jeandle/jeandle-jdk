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
 * @summary Test the intrinsic implementation of Math.floor
 * @requires os.arch=="amd64" | os.arch=="x86_64" | os.arch=="aarch64"
 * @library /test/lib /
 * @build jdk.test.lib.Asserts
 * @run main/othervm compiler.jeandle.intrinsic.TestFloorDouble
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

public class TestFloorDouble {
    public static void main(String[] args) throws Exception {
        String dumpPath = Files.createTempDirectory("jeandle_test_floor").toString();

        ArrayList<String> commandArgs = new ArrayList<>(List.of(
                "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
                "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dumpPath,
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::floor_double",
                TestWrapper.class.getName()));

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(commandArgs);
        OutputAnalyzer output = ProcessTools.executeCommand(pb);

        output.shouldHaveExitValue(0)
              .shouldContain("is parsed as intrinsic");

        // Verify llvm IR — only check the intrinsic is used, not control flow
        FileCheck checker = new FileCheck(dumpPath,
                TestWrapper.class.getMethod("floor_double", double.class), false);
        checker.checkPattern("llvm\\.floor");
    }

    static class TestWrapper {
        static double v = Math.floor(1.0d); // Force load java.lang.Math class

        public static void main(String[] args) {
            Random random = new Random();

            // Special values
            Asserts.assertTrue(Double.isNaN(floor_double(Double.NaN)), "floor(NaN)");
            Asserts.assertTrue(floor_double(Double.POSITIVE_INFINITY) == Double.POSITIVE_INFINITY, "floor(+Inf)");
            Asserts.assertTrue(floor_double(Double.NEGATIVE_INFINITY) == Double.NEGATIVE_INFINITY, "floor(-Inf)");
            Asserts.assertTrue(Double.doubleToRawLongBits(floor_double(-0.0d)) == Double.doubleToRawLongBits(-0.0d),
                    "floor(-0.0) should be -0.0");
            Asserts.assertTrue(Double.doubleToRawLongBits(floor_double(0.0d)) == Double.doubleToRawLongBits(0.0d),
                    "floor(0.0) should be 0.0");

            // Already-integer values
            Asserts.assertEquals(3.0d, floor_double(3.0d), "floor(3.0)");
            Asserts.assertEquals(-5.0d, floor_double(-5.0d), "floor(-5.0)");
            Asserts.assertEquals(0.0d, floor_double(0.0d), "floor(0.0)");

            // Fractional values
            Asserts.assertEquals(3.0d, floor_double(3.7d), "floor(3.7)");
            Asserts.assertEquals(-4.0d, floor_double(-3.7d), "floor(-3.7)");
            Asserts.assertEquals(3.0d, floor_double(3.1d), "floor(3.1)");
            Asserts.assertEquals(-4.0d, floor_double(-3.1d), "floor(-3.1)");

            // Near boundary values
            Asserts.assertEquals(0.0d, floor_double(0.49999999999999994d), "floor(0.4999...)");
            Asserts.assertEquals(-1.0d, floor_double(-0.49999999999999994d), "floor(-0.4999...)");

            // Subnormal
            Asserts.assertEquals(0.0d, floor_double(Double.MIN_VALUE), "floor(MIN_VALUE)");
            Asserts.assertEquals(0.0d, floor_double(Double.MIN_NORMAL), "floor(MIN_NORMAL)");

            // Large value (already integer)
            Asserts.assertEquals(1e15, floor_double(1e15 + 0.5), "floor(1e15+0.5) -- large integer");

            // Random values
            for (int i = 0; i < 1000; i++) {
                double d = random.nextDouble() * 2000.0 - 1000.0;
                double expected = StrictMath.floor(d);
                double actual = floor_double(d);
                Asserts.assertEquals(expected, actual, "floor random mismatch for d=" + d);
            }

            System.out.println("TestFloorDouble PASSED");
        }

        public static double floor_double(double a) {
            return Math.floor(a);
        }
    }
}
