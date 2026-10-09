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
 * @requires os.arch=="amd64" | os.arch=="x86_64" | os.arch=="aarch64"
 * @summary Test the intrinsic implementation of Math.multiplyHigh and Math.unsignedMultiplyHigh
 * @library /test/lib /
 * @run main/othervm compiler.jeandle.intrinsic.TestMultiplyHigh
 */

package compiler.jeandle.intrinsic;

import compiler.jeandle.fileCheck.FileCheck;
import jdk.test.lib.Asserts;
import jdk.test.lib.Utils;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class TestMultiplyHigh {
    public static void main(String[] args) throws Exception {
        String dumpPath = Files.createTempDirectory("jeandle_multiply_high").toString();

        // Note: TestWrapper::main is intentionally NOT in the compileonly list below, so it
        // always runs interpreted. That makes every direct Math.multiplyHigh/unsignedMultiplyHigh
        // call made inside main() (used below as the correctness oracle) run the real
        // interpreter/pure-Java reference behavior, independent of the intrinsic lowering under
        // test in the multiply_high/unsigned_multiply_high wrapper methods.
        ArrayList<String> commandArgs = new ArrayList<>(List.of(
                "-Xbatch",
                "-XX:-TieredCompilation",
                "-XX:+UseJeandleCompiler",
                "-Xcomp",
                "-Xlog:jeandle=debug",
                "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dumpPath,
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::multiply_high",
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::unsigned_multiply_high",
                TestWrapper.class.getName()));

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(commandArgs);
        OutputAnalyzer output = ProcessTools.executeCommand(pb);

        output.shouldHaveExitValue(0)
              .shouldContain("is parsed as intrinsic");

        // multiplyHigh: sign-extend both operands to i128, multiply, shift right 64, truncate.
        FileCheck signedCheck = new FileCheck(dumpPath, TestWrapper.class.getMethod("multiply_high", long.class, long.class), false);
        signedCheck.checkPattern("sext i64 .* to i128");
        signedCheck.checkPattern("sext i64 .* to i128");
        signedCheck.checkPattern("mul i128");
        signedCheck.checkPattern("lshr i128 .*, 64");
        signedCheck.checkPattern("trunc i128 .* to i64");

        // unsignedMultiplyHigh: same shape but zero-extend instead of sign-extend.
        FileCheck unsignedCheck = new FileCheck(dumpPath, TestWrapper.class.getMethod("unsigned_multiply_high", long.class, long.class), false);
        unsignedCheck.checkPattern("zext i64 .* to i128");
        unsignedCheck.checkPattern("zext i64 .* to i128");
        unsignedCheck.checkPattern("mul i128");
        unsignedCheck.checkPattern("lshr i128 .*, 64");
        unsignedCheck.checkPattern("trunc i128 .* to i64");
    }

    static class TestWrapper {
        // Force load java.lang.Math
        static final long DUMMY1 = Math.multiplyHigh(1L, 1L);
        static final long DUMMY2 = Math.unsignedMultiplyHigh(1L, 1L);

        public static void main(String[] args) {
            var random = Utils.getRandomInstance();

            // ---- Basic / boundary values ----
            Asserts.assertEquals(0L, multiply_high(0L, 0L), "multiplyHigh(0,0)");
            Asserts.assertEquals(0L, multiply_high(1L, 1L), "multiplyHigh(1,1)");
            Asserts.assertEquals(-1L, multiply_high(-1L, 1L), "multiplyHigh(-1,1) high word is all ones");
            Asserts.assertEquals(Math.multiplyHigh(Long.MAX_VALUE, Long.MAX_VALUE),
                    multiply_high(Long.MAX_VALUE, Long.MAX_VALUE), "multiplyHigh(MAX,MAX)");
            Asserts.assertEquals(Math.multiplyHigh(Long.MIN_VALUE, Long.MIN_VALUE),
                    multiply_high(Long.MIN_VALUE, Long.MIN_VALUE), "multiplyHigh(MIN,MIN)");
            Asserts.assertEquals(Math.multiplyHigh(Long.MIN_VALUE, Long.MAX_VALUE),
                    multiply_high(Long.MIN_VALUE, Long.MAX_VALUE), "multiplyHigh(MIN,MAX)");

            Asserts.assertEquals(0L, unsigned_multiply_high(0L, 0L), "unsignedMultiplyHigh(0,0)");
            Asserts.assertEquals(0L, unsigned_multiply_high(1L, 1L), "unsignedMultiplyHigh(1,1)");
            Asserts.assertEquals(Math.unsignedMultiplyHigh(-1L, -1L),
                    unsigned_multiply_high(-1L, -1L), "unsignedMultiplyHigh(-1,-1)");
            Asserts.assertEquals(Math.unsignedMultiplyHigh(Long.MAX_VALUE, Long.MAX_VALUE),
                    unsigned_multiply_high(Long.MAX_VALUE, Long.MAX_VALUE), "unsignedMultiplyHigh(MAX,MAX)");
            Asserts.assertEquals(Math.unsignedMultiplyHigh(Long.MIN_VALUE, Long.MIN_VALUE),
                    unsigned_multiply_high(Long.MIN_VALUE, Long.MIN_VALUE), "unsignedMultiplyHigh(MIN,MIN)");

            // ---- Random fuzzing across the full long range, checked against the
            //      interpreter-run Math.multiplyHigh/unsignedMultiplyHigh. ----
            for (int i = 0; i < 5000; i++) {
                long x = random.nextLong();
                long y = random.nextLong();
                Asserts.assertEquals(Math.multiplyHigh(x, y), multiply_high(x, y),
                        "multiplyHigh mismatch for x=" + x + " y=" + y);
                Asserts.assertEquals(Math.unsignedMultiplyHigh(x, y), unsigned_multiply_high(x, y),
                        "unsignedMultiplyHigh mismatch for x=" + x + " y=" + y);
            }

            System.out.println("TestMultiplyHigh PASSED");
        }

        public static long multiply_high(long x, long y) {
            return Math.multiplyHigh(x, y);
        }

        public static long unsigned_multiply_high(long x, long y) {
            return Math.unsignedMultiplyHigh(x, y);
        }
    }
}
