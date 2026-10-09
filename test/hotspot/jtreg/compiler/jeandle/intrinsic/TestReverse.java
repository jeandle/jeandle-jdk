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
 * @summary Test the intrinsic implementation of Integer.reverse and Long.reverse
 * @requires os.arch=="amd64" | os.arch=="x86_64" | os.arch=="aarch64"
 * @library /test/lib /
 * @build jdk.test.lib.Asserts
 * @run main/othervm compiler.jeandle.intrinsic.TestReverse
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

public class TestReverse {
    public static void main(String[] args) throws Exception {
        String dumpPath = Files.createTempDirectory("jeandle_test_reverse").toString();

        ArrayList<String> commandArgs = new ArrayList<>(List.of(
                "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
                "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dumpPath,
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::reverse_int",
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::reverse_long",
                TestWrapper.class.getName()));

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(commandArgs);
        OutputAnalyzer output = ProcessTools.executeCommand(pb);

        output.shouldHaveExitValue(0)
              .shouldContain("Method `static jint java.lang.Integer.reverse(jint)` is parsed as intrinsic")
              .shouldContain("Method `static jlong java.lang.Long.reverse(jlong)` is parsed as intrinsic");

        new FileCheck(dumpPath, TestWrapper.class.getMethod("reverse_int", int.class), false)
                .checkPattern("llvm\\.bitreverse\\.i32");
        new FileCheck(dumpPath, TestWrapper.class.getMethod("reverse_long", long.class), false)
                .checkPattern("llvm\\.bitreverse\\.i64");
    }

    static class TestWrapper {
        static int vi = Integer.reverse(1);   // Force load Integer class
        static long vl = Long.reverse(1L);    // Force load Long class

        public static void main(String[] args) {
            checkInt(0);
            checkInt(-1);
            checkInt(1);
            checkInt(Integer.MIN_VALUE);
            checkInt(Integer.MAX_VALUE);
            checkInt(0x55555555);
            checkInt(0xAAAAAAAA);
            checkInt(0x01234567);

            checkLong(0L);
            checkLong(-1L);
            checkLong(1L);
            checkLong(Long.MIN_VALUE);
            checkLong(Long.MAX_VALUE);
            checkLong(0x5555555555555555L);
            checkLong(0xAAAAAAAAAAAAAAAAL);
            checkLong(0x0123456789ABCDEFL);

            Random random = new Random(0x5EEDBEEF);
            for (int i = 0; i < 1000; i++) {
                checkInt(random.nextInt());
                checkLong(random.nextLong());
            }

            System.out.println("TestReverse PASSED");
        }

        private static void checkInt(int value) {
            Asserts.assertEquals(reverseIntReference(value), reverse_int(value),
                                 "Integer.reverse mismatch for " + value);
        }

        private static void checkLong(long value) {
            Asserts.assertEquals(reverseLongReference(value), reverse_long(value),
                                 "Long.reverse mismatch for " + value);
        }

        public static int reverse_int(int value) {
            return Integer.reverse(value);
        }

        public static long reverse_long(long value) {
            return Long.reverse(value);
        }

        private static int reverseIntReference(int value) {
            int result = 0;
            for (int bit = 0; bit < Integer.SIZE; bit++) {
                result = (result << 1) | (value & 1);
                value >>>= 1;
            }
            return result;
        }

        private static long reverseLongReference(long value) {
            long result = 0;
            for (int bit = 0; bit < Long.SIZE; bit++) {
                result = (result << 1) | (value & 1L);
                value >>>= 1;
            }
            return result;
        }
    }
}
