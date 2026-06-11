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
 * @summary Test the intrinsics implementation of Preconditions.checkIndex for int and long
 * @requires os.arch=="amd64" | os.arch=="x86_64" | os.arch=="aarch64"
 * @modules java.base/jdk.internal.util
 * @library /test/lib /
 * @run main/othervm compiler.jeandle.intrinsic.TestPreconditionsCheckIndex
 */

package compiler.jeandle.intrinsic;

import compiler.jeandle.fileCheck.FileCheck;
import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import jdk.internal.util.Preconditions;

public class TestPreconditionsCheckIndex {

    public static void main(String[] args) throws Exception {
        String dumpPath = Files.createTempDirectory("jeandle_preconditions_checkindex").toString();

        ArrayList<String> commandArgs = new ArrayList<>(List.of(
                "--add-exports", "java.base/jdk.internal.util=ALL-UNNAMED",
                "-Xbatch",
                "-XX:-TieredCompilation",
                "-XX:+UseJeandleCompiler",
                "-Xcomp",
                "-Xlog:jeandle=debug",
                "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dumpPath,
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::check_int_valid",
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::check_long_valid",
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::check_int_neg_len",
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::check_int_oob",
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::check_long_neg_len",
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::check_long_oob",
                TestWrapper.class.getName()));

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(commandArgs);
        OutputAnalyzer output = ProcessTools.executeCommand(pb);

        output.shouldHaveExitValue(0)
              .shouldContain("is parsed as intrinsic");

        FileCheck intValid = new FileCheck(dumpPath,
                TestWrapper.class.getDeclaredMethod("check_int_valid", int.class, int.class), false);
        // Verify the guard: icmp slt for length < 0 check
        intValid.checkPattern("icmp slt i32");
        // Verify unsigned range check: icmp uge for index >= length
        intValid.checkPattern("icmp uge i32");

        FileCheck longValid = new FileCheck(dumpPath,
                TestWrapper.class.getDeclaredMethod("check_long_valid", long.class, long.class), false);
        longValid.checkPattern("icmp slt i64");
        longValid.checkPattern("icmp uge i64");
    }

    static class TestWrapper {
        public static void main(String[] args) {
            // Warm up valid paths
            for (int i = 0; i < 10_000; i++) {
                Asserts.assertEquals(check_int_valid(i % 100, 100), i % 100);
                Asserts.assertEquals(check_long_valid(i % 100L, 100L), i % 100L);
            }

            // Test negative length — should throw IndexOutOfBoundsException
            try {
                check_int_neg_len(0);
                throw new RuntimeException("Expected IndexOutOfBoundsException for negative length");
            } catch (IndexOutOfBoundsException e) {
                // expected
            }

            try {
                check_long_neg_len(0L);
                throw new RuntimeException("Expected IndexOutOfBoundsException for negative length (long)");
            } catch (IndexOutOfBoundsException e) {
                // expected
            }

            // Test out-of-bounds index — should throw IndexOutOfBoundsException
            try {
                check_int_oob(10);
                throw new RuntimeException("Expected IndexOutOfBoundsException for out-of-bounds index");
            } catch (IndexOutOfBoundsException e) {
                // expected
            }

            try {
                check_long_oob(10L);
                throw new RuntimeException("Expected IndexOutOfBoundsException for out-of-bounds index (long)");
            } catch (IndexOutOfBoundsException e) {
                // expected
            }

            // ---- Boundary tests ----

            // Last valid index: checkIndex(length-1, length) should pass
            Asserts.assertEquals(9, check_int_valid(9, 10), "checkIndex(9, 10) should pass");
            Asserts.assertEquals(99L, check_long_valid(99L, 100L), "checkIndex(99L, 100L) should pass");

            // Index == length: out of bounds (one past the end)
            try {
                check_int_valid(10, 10);
                throw new RuntimeException("Expected IndexOutOfBoundsException for index == length");
            } catch (IndexOutOfBoundsException e) {
                // expected
            }
            try {
                check_long_valid(10L, 10L);
                throw new RuntimeException("Expected IndexOutOfBoundsException for index == length (long)");
            } catch (IndexOutOfBoundsException e) {
                // expected
            }

            // Empty range: checkIndex(0, 0) — index 0 is out of bounds in an empty range
            try {
                check_int_valid(0, 0);
                throw new RuntimeException("Expected IndexOutOfBoundsException for index 0 in empty range");
            } catch (IndexOutOfBoundsException e) {
                // expected
            }
            try {
                check_long_valid(0L, 0L);
                throw new RuntimeException("Expected IndexOutOfBoundsException for index 0 in empty range (long)");
            } catch (IndexOutOfBoundsException e) {
                // expected
            }

            // Smallest valid range: checkIndex(0, 1)
            Asserts.assertEquals(0, check_int_valid(0, 1), "checkIndex(0, 1) should pass");
            Asserts.assertEquals(0L, check_long_valid(0L, 1L), "checkIndex(0L, 1L) should pass");

            // Negative index with valid length (unsigned comparison catches it)
            try {
                check_int_valid(-1, 10);
                throw new RuntimeException("Expected IndexOutOfBoundsException for negative index");
            } catch (IndexOutOfBoundsException e) {
                // expected — -1 interpreted as unsigned is very large, so uge check catches it
            }
            try {
                check_long_valid(-1L, 10L);
                throw new RuntimeException("Expected IndexOutOfBoundsException for negative index (long)");
            } catch (IndexOutOfBoundsException e) {
                // expected
            }
        }

        static int check_int_valid(int index, int length) {
            return Preconditions.checkIndex(index, length, null);
        }

        static long check_long_valid(long index, long length) {
            return Preconditions.checkIndex(index, length, null);
        }

        static int check_int_neg_len(int index) {
            return Preconditions.checkIndex(index, -1, null);
        }

        static long check_long_neg_len(long index) {
            return Preconditions.checkIndex(index, -1L, null);
        }

        static int check_int_oob(int length) {
            return Preconditions.checkIndex(length + 1, length, null);
        }

        static long check_long_oob(long length) {
            return Preconditions.checkIndex(length + 1L, length, null);
        }
    }
}
