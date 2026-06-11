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
 * @summary Test the intrinsic implementation of Integer.bitCount and Long.bitCount
 * @requires os.arch=="amd64" | os.arch=="x86_64" | os.arch=="aarch64"
 * @library /test/lib /
 * @build jdk.test.lib.Asserts
 * @run main/othervm compiler.jeandle.intrinsic.TestBitCount
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

public class TestBitCount {
    public static void main(String[] args) throws Exception {
        String dumpPath = Files.createTempDirectory("jeandle_test_bitcount").toString();

        ArrayList<String> commandArgs = new ArrayList<>(List.of(
                "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
                "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dumpPath,
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::bitCount_int",
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::bitCount_long",
                TestWrapper.class.getName()));

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(commandArgs);
        OutputAnalyzer output = ProcessTools.executeCommand(pb);

        output.shouldHaveExitValue(0)
              .shouldContain("is parsed as intrinsic");

        // Verify llvm IR — only check the intrinsic calls are used
        FileCheck intChecker = new FileCheck(dumpPath,
                TestWrapper.class.getMethod("bitCount_int", int.class), false);
        intChecker.checkPattern("llvm\\.ctpop\\.i32");

        FileCheck longChecker = new FileCheck(dumpPath,
                TestWrapper.class.getMethod("bitCount_long", long.class), false);
        longChecker.checkPattern("llvm\\.ctpop\\.i64");
    }

    static class TestWrapper {
        static int vi = Integer.bitCount(1);   // Force load Integer class
        static long vl = Long.bitCount(1L);    // Force load Long class

        public static void main(String[] args) {
            Random random = new Random();

            // --- Integer.bitCount corner cases ---
            Asserts.assertEquals(0, bitCount_int(0), "bitCount(0)");
            Asserts.assertEquals(32, bitCount_int(-1), "bitCount(-1) = all bits set");
            Asserts.assertEquals(1, bitCount_int(1), "bitCount(1)");
            Asserts.assertEquals(1, bitCount_int(Integer.MIN_VALUE), "bitCount(MIN_VALUE) = sign bit only");
            Asserts.assertEquals(31, bitCount_int(Integer.MAX_VALUE), "bitCount(MAX_VALUE)");
            Asserts.assertEquals(16, bitCount_int(0x55555555), "bitCount(0x55555555) = alternating bits");
            Asserts.assertEquals(16, bitCount_int(0xAAAAAAAA), "bitCount(0xAAAAAAAA) = alternating bits");
            Asserts.assertEquals(1, bitCount_int(0x80000000), "bitCount(high bit)");
            Asserts.assertEquals(2, bitCount_int(0x80000001), "bitCount(high + low bit)");
            Asserts.assertEquals(32, bitCount_int(0xFFFFFFFF), "bitCount(all bits)");

            // Random int values
            for (int i = 0; i < 1000; i++) {
                int v = random.nextInt();
                int expected = Integer.bitCount(v);
                // Use a reference implementation for cross-check
                int ref = 0;
                for (int b = 0; b < 32; b++) {
                    if ((v & (1 << b)) != 0) ref++;
                }
                Asserts.assertEquals(ref, expected, "reference mismatch for int " + v);
                Asserts.assertEquals(expected, bitCount_int(v), "bitCount int mismatch for " + v);
            }

            // --- Long.bitCount corner cases ---
            Asserts.assertEquals(0, bitCount_long(0L), "bitCount(0L)");
            Asserts.assertEquals(64, bitCount_long(-1L), "bitCount(-1L) = all bits set");
            Asserts.assertEquals(1, bitCount_long(1L), "bitCount(1L)");
            Asserts.assertEquals(1, bitCount_long(Long.MIN_VALUE), "bitCount(MIN_VALUEL) = sign bit only");
            Asserts.assertEquals(63, bitCount_long(Long.MAX_VALUE), "bitCount(MAX_VALUEL)");
            Asserts.assertEquals(32, bitCount_long(0x5555555555555555L), "bitCount(alternatingL)");
            Asserts.assertEquals(1, bitCount_long(0x8000000000000000L), "bitCount(high bitL)");
            Asserts.assertEquals(2, bitCount_long(0x8000000000000001L), "bitCount(high + low bitL)");
            Asserts.assertEquals(64, bitCount_long(0xFFFFFFFFFFFFFFFFL), "bitCount(all bitsL)");

            // Random long values
            for (int i = 0; i < 1000; i++) {
                long v = random.nextLong();
                int expected = Long.bitCount(v);
                // Cross-check with reference
                int ref = 0;
                for (int b = 0; b < 64; b++) {
                    if ((v & (1L << b)) != 0) ref++;
                }
                Asserts.assertEquals(ref, expected, "reference mismatch for long " + v);
                Asserts.assertEquals(expected, bitCount_long(v), "bitCount long mismatch for " + v);
            }

            System.out.println("TestBitCount PASSED");
        }

        public static int bitCount_int(int a) {
            return Integer.bitCount(a);
        }

        public static int bitCount_long(long a) {
            return Long.bitCount(a);
        }
    }
}
