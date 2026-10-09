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
 */

/*
 * @test
 * @key randomness
 * @requires os.arch=="amd64" | os.arch=="x86_64" | os.arch=="aarch64"
 * @summary Test the intrinsics implementation of Integer/Long::divideUnsigned
 * @library /test/lib /
 * @run main/othervm compiler.jeandle.intrinsic.TestDivideUnsigned
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

public class TestDivideUnsigned {
    public static void main(String[] args) throws Exception {
        String dumpPath = Files.createTempDirectory("jeandle_divide_unsigned").toString();

        ArrayList<String> commandArgs = new ArrayList<>(List.of(
                "-Xbatch",
                "-XX:-TieredCompilation",
                "-XX:+UseJeandleCompiler",
                "-Xcomp",
                "-Xlog:jeandle=debug",
                "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dumpPath,
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::divide_int",
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::divide_long",
                TestWrapper.class.getName()));

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(commandArgs);
        OutputAnalyzer output = ProcessTools.executeCommand(pb);

        output.shouldHaveExitValue(0)
              .shouldContain("Method `static jint java.lang.Integer.divideUnsigned(jint, jint)` is parsed as intrinsic")
              .shouldContain("Method `static jlong java.lang.Long.divideUnsigned(jlong, jlong)` is parsed as intrinsic");

        // The zero check must dominate udiv: division by zero is undefined in
        // LLVM IR, while Java requires ArithmeticException.
        FileCheck intCheck = new FileCheck(dumpPath,
                TestWrapper.class.getMethod("divide_int", int.class, int.class), false);
        intCheck.checkPattern("icmp eq i32 .*0");
        intCheck.checkPattern("br i1");
        intCheck.checkPattern("zero_check_pass");
        intCheck.checkPattern("udiv i32");
        intCheck.checkPattern("zero_check_fail");

        FileCheck longCheck = new FileCheck(dumpPath,
                TestWrapper.class.getMethod("divide_long", long.class, long.class), false);
        longCheck.checkPattern("icmp eq i64 .*0");
        longCheck.checkPattern("br i1");
        longCheck.checkPattern("zero_check_pass");
        longCheck.checkPattern("udiv i64");
        longCheck.checkPattern("zero_check_fail");
    }

    static class TestWrapper {
        static int intDummy = Integer.divideUnsigned(1, 1); // Force java.lang.Integer initialization
        static long longDummy = Long.divideUnsigned(1L, 1L); // Force java.lang.Long initialization

        public static void main(String[] args) {
            int[] intValues = {
                    0, 1, 2, Integer.MAX_VALUE - 1, Integer.MAX_VALUE,
                    Integer.MIN_VALUE, -2, -1
            };
            for (int dividend : intValues) {
                for (int divisor : intValues) {
                    if (divisor != 0) {
                        verifyInt(dividend, divisor);
                    }
                }
            }

            long[] longValues = {
                    0L, 1L, 2L, Integer.MAX_VALUE, 1L << 32,
                    Long.MAX_VALUE - 1, Long.MAX_VALUE, Long.MIN_VALUE, -2L, -1L
            };
            for (long dividend : longValues) {
                for (long divisor : longValues) {
                    if (divisor != 0L) {
                        verifyLong(dividend, divisor);
                    }
                }
            }

            var random = Utils.getRandomInstance();
            for (int i = 0; i < 5000; i++) {
                int intDivisor = random.nextInt();
                long longDivisor = random.nextLong();
                verifyInt(random.nextInt(), intDivisor == 0 ? 1 : intDivisor);
                verifyLong(random.nextLong(), longDivisor == 0L ? 1L : longDivisor);
            }

            // Trigger each uncommon path only after the non-zero cases. This
            // avoids a trap-driven recompilation replacing the dumped IR that
            // FileCheck is intended to inspect.
            Asserts.assertThrows(ArithmeticException.class, () -> divide_int(1, 0));
            Asserts.assertThrows(ArithmeticException.class, () -> divide_long(1L, 0L));
        }

        static void verifyInt(int dividend, int divisor) {
            int expected = (int) (Integer.toUnsignedLong(dividend)
                    / Integer.toUnsignedLong(divisor));
            Asserts.assertEquals(divide_int(dividend, divisor), expected,
                    "Integer.divideUnsigned(" + dividend + ", " + divisor + ")");
        }

        static void verifyLong(long dividend, long divisor) {
            // This helper is excluded by compileonly, so the expected value is
            // produced by the interpreted Java implementation.
            long expected = Long.divideUnsigned(dividend, divisor);
            Asserts.assertEquals(divide_long(dividend, divisor), expected,
                    "Long.divideUnsigned(" + dividend + ", " + divisor + ")");
        }

        public static int divide_int(int dividend, int divisor) {
            return Integer.divideUnsigned(dividend, divisor);
        }

        public static long divide_long(long dividend, long divisor) {
            return Long.divideUnsigned(dividend, divisor);
        }
    }
}

