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
 * @summary Test the intrinsics implementation of Integer/Long::compareUnsigned
 * @library /test/lib /
 * @run main/othervm compiler.jeandle.intrinsic.TestCompareUnsigned
 */


package compiler.jeandle.intrinsic;

import compiler.jeandle.fileCheck.FileCheck;
import jdk.test.lib.Asserts;
import jdk.test.lib.Utils;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class TestCompareUnsigned {
    public static void main(String[] args) throws Exception {
        String dumpPath = Files.createTempDirectory("jeandle_compare_unsigned").toString();

        ArrayList<String> commandArgs = new ArrayList<>(List.of(
                "-Xbatch",
                "-XX:-TieredCompilation",
                "-XX:+UseJeandleCompiler",
                "-Xcomp",
                "-Xlog:jeandle=debug",
                "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dumpPath,
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::compare_int",
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::compare_long",
                TestWrapper.class.getName()));

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(commandArgs);
        OutputAnalyzer output = ProcessTools.executeCommand(pb);

        output.shouldHaveExitValue(0)
              .shouldContain("Method `static jint java.lang.Integer.compareUnsigned(jint, jint)` is parsed as intrinsic")
              .shouldContain("Method `static jint java.lang.Long.compareUnsigned(jlong, jlong)` is parsed as intrinsic");

        // Verify IR — only check semantic features (unsigned comparison and select), not control flow
        FileCheck intCheck = new FileCheck(dumpPath, TestWrapper.class.getMethod("compare_int", int.class, int.class), false);
        intCheck.checkPattern("icmp ult i32");
        intCheck.checkPattern("icmp ugt i32");
        intCheck.checkPattern("select i1");
        intCheck.checkPattern("select i1");

        FileCheck longCheck = new FileCheck(dumpPath, TestWrapper.class.getMethod("compare_long", long.class, long.class), false);
        longCheck.checkPattern("icmp ult i64");
        longCheck.checkPattern("icmp ugt i64");
        longCheck.checkPattern("select i1");
        longCheck.checkPattern("select i1");
    }

    static class TestWrapper {
        static final int TRUE_VALUE = 10;
        static final int FALSE_VALUE = 4;

        static int v1 = Integer.compareUnsigned(1, 1); // Force load java.lang.Integer class
        static int v2 = Long.compareUnsigned(1L, 1L); // Force load java.lang.Long class

        // The test logic refers to compiler/intrinsics/TestCompareUnsigned.java.
        public static void main(String[] args) {
            var random = Utils.getRandomInstance();
            for (int i = 0; i < 1000; i++) {
                int x = random.nextInt();
                int y = random.nextInt();
                Asserts.assertEquals(lessThanInt(x, x), FALSE_VALUE);
                Asserts.assertEquals(compareInt(x, x), 0);
                Asserts.assertEquals(lessThanInt(x, y), expectedResult(x, y) < 0 ? TRUE_VALUE : FALSE_VALUE);
                Asserts.assertEquals(compareInt(x, y), expectedResult(x, y));
                Asserts.assertEquals(compareIntWithImm1(x), expectedResult(x, 42));
                Asserts.assertEquals(compareIntWithImm2(x), expectedResult(x, 42 << 12));
                Asserts.assertEquals(compareIntWithImm3(x), expectedResult(x, 42 << 24));
                Asserts.assertEquals(compareIntWithImm4(x), expectedResult(x, Integer.MIN_VALUE));
            }
            for (int i = 0; i < 1000; i++) {
                long x = random.nextLong();
                long y = random.nextLong();
                Asserts.assertEquals(lessThanLong(x, x), FALSE_VALUE);
                Asserts.assertEquals(compareLong(x, x), 0);
                Asserts.assertEquals(lessThanLong(x, y), expectedResult(x, y) < 0 ? TRUE_VALUE : FALSE_VALUE);
                Asserts.assertEquals(compareLong(x, y), expectedResult(x, y));
                Asserts.assertEquals(compareLongWithImm1(x), expectedResult(x, 42));
                Asserts.assertEquals(compareLongWithImm2(x), expectedResult(x, 42 << 12));
                Asserts.assertEquals(compareLongWithImm3(x), expectedResult(x, 42 << 24));
                Asserts.assertEquals(compareLongWithImm4(x), expectedResult(x, Integer.MIN_VALUE));
                Asserts.assertEquals(compareLongWithImm5(x), expectedResult(x, Long.MIN_VALUE));
            }

            // Boundary cases for int: 0 and -1 are unsigned extremes
            // compareUnsigned(0, -1) → -1 (when interpreted unsigned, -1 is MAX_UNSIGNED which is > 0)
            Asserts.assertEquals(-1, compare_int(0, -1), "compareUnsigned(0, -1)");
            Asserts.assertEquals(1, compare_int(-1, 0), "compareUnsigned(-1, 0)");
            Asserts.assertEquals(0, compare_int(0, 0), "compareUnsigned(0, 0)");
            Asserts.assertEquals(0, compare_int(-1, -1), "compareUnsigned(-1, -1)");
            // 1 vs -2: small positive vs near-max unsigned
            Asserts.assertEquals(-1, compare_int(1, -2), "compareUnsigned(1, -2)");

            // Boundary cases for long
            Asserts.assertEquals(-1, compare_long(0L, -1L), "compareUnsigned(0L, -1L)");
            Asserts.assertEquals(1, compare_long(-1L, 0L), "compareUnsigned(-1L, 0L)");
            Asserts.assertEquals(0, compare_long(0L, 0L), "compareUnsigned(0L, 0L)");
            Asserts.assertEquals(0, compare_long(-1L, -1L), "compareUnsigned(-1L, -1L)");
            Asserts.assertEquals(-1, compare_long(1L, -2L), "compareUnsigned(1L, -2L)");
        }

        static int expectedResult(int x, int y) {
            return Integer.compare(x + Integer.MIN_VALUE, y + Integer.MIN_VALUE);
        }

        static int expectedResult(long x, long y) {
            return Long.compare(x + Long.MIN_VALUE, y + Long.MIN_VALUE);
        }

        static int lessThanInt(int x, int y) {
            return compare_int(x, y) < 0 ? TRUE_VALUE : FALSE_VALUE;
        }

        static int lessThanLong(long x, long y) {
            return compare_long(x, y) < 0 ? TRUE_VALUE : FALSE_VALUE;
        }

        static int compareInt(int x, int y) {
            return compare_int(x, y);
        }

        static int compareIntWithImm1(int x) {
            return compare_int(x, 42);
        }

        static int compareIntWithImm2(int x) {
            return compare_int(x, 42 << 12);
        }

        static int compareIntWithImm3(int x) {
            return compare_int(x, 42 << 24);
        }

        static int compareIntWithImm4(int x) {
            return compare_int(x, Integer.MIN_VALUE);
        }

        static int compareLong(long x, long y) {
            return compare_long(x, y);
        }

        static int compareLongWithImm1(long x) {
            return compare_long(x, 42);
        }

        static int compareLongWithImm2(long x) {
            return compare_long(x, 42 << 12);
        }

        static int compareLongWithImm3(long x) {
            return compare_long(x, 42 << 24);
        }

        static int compareLongWithImm4(long x) {
            return compare_long(x, Integer.MIN_VALUE);
        }

        static int compareLongWithImm5(long x) {
            return compare_long(x, Long.MIN_VALUE);
        }

        public static int compare_int(int left, int right) {
            return Integer.compareUnsigned(left, right);
        }

        public static int compare_long(long left, long right) {
            return Long.compareUnsigned(left, right);
        }
    }
}
