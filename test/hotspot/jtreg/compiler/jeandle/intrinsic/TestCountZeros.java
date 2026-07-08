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
 * @summary Test the intrinsic implementation of Integer/Long.numberOf{Leading,Trailing}Zeros
 * @requires os.arch=="amd64" | os.arch=="x86_64" | os.arch=="aarch64"
 * @library /test/lib /
 * @build jdk.test.lib.Asserts
 * @run main/othervm compiler.jeandle.intrinsic.TestCountZeros
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

public class TestCountZeros {
    public static void main(String[] args) throws Exception {
        String dumpPath = Files.createTempDirectory("jeandle_test_count_zeros").toString();

        ArrayList<String> commandArgs = new ArrayList<>(List.of(
                "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
                "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dumpPath,
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::nlz_int",
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::nlz_long",
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::ntz_int",
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::ntz_long",
                TestWrapper.class.getName()));

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(commandArgs);
        OutputAnalyzer output = ProcessTools.executeCommand(pb);

        output.shouldHaveExitValue(0)
              .shouldContain("is parsed as intrinsic");

        // Verify llvm IR — pin the lowering path to ctlz/cttz of the right width.
        new FileCheck(dumpPath, TestWrapper.class.getMethod("nlz_int", int.class), false)
                .checkPattern("llvm\\.ctlz\\.i32");
        new FileCheck(dumpPath, TestWrapper.class.getMethod("nlz_long", long.class), false)
                .checkPattern("llvm\\.ctlz\\.i64");
        new FileCheck(dumpPath, TestWrapper.class.getMethod("ntz_int", int.class), false)
                .checkPattern("llvm\\.cttz\\.i32");
        new FileCheck(dumpPath, TestWrapper.class.getMethod("ntz_long", long.class), false)
                .checkPattern("llvm\\.cttz\\.i64");
    }

    static class TestWrapper {
        static int v0 = Integer.numberOfLeadingZeros(1);   // Force load Integer class
        static int v1 = Long.numberOfTrailingZeros(1L);    // Force load Long class

        public static void main(String[] args) {
            Random random = new Random();

            // --- Integer.numberOfLeadingZeros corner cases ---
            Asserts.assertEquals(32, nlz_int(0), "nlz(0)");
            Asserts.assertEquals(0, nlz_int(-1), "nlz(-1) = all bits set");
            Asserts.assertEquals(31, nlz_int(1), "nlz(1)");
            Asserts.assertEquals(0, nlz_int(Integer.MIN_VALUE), "nlz(MIN_VALUE) = high bit set");
            Asserts.assertEquals(1, nlz_int(Integer.MAX_VALUE), "nlz(MAX_VALUE)");
            Asserts.assertEquals(15, nlz_int(0x00010000), "nlz(0x00010000)");
            Asserts.assertEquals(16, nlz_int(0x0000FFFF), "nlz(0x0000FFFF)");

            // --- Integer.numberOfTrailingZeros corner cases ---
            Asserts.assertEquals(32, ntz_int(0), "ntz(0)");
            Asserts.assertEquals(0, ntz_int(-1), "ntz(-1) = all bits set");
            Asserts.assertEquals(0, ntz_int(1), "ntz(1)");
            Asserts.assertEquals(31, ntz_int(Integer.MIN_VALUE), "ntz(MIN_VALUE) = high bit only");
            Asserts.assertEquals(1, ntz_int(2), "ntz(2)");
            Asserts.assertEquals(16, ntz_int(0x00010000), "ntz(0x00010000)");
            Asserts.assertEquals(8, ntz_int(0x0000FF00), "ntz(0x0000FF00)");

            // --- Long.numberOfLeadingZeros corner cases ---
            Asserts.assertEquals(64, nlz_long(0L), "nlz(0L)");
            Asserts.assertEquals(0, nlz_long(-1L), "nlz(-1L) = all bits set");
            Asserts.assertEquals(63, nlz_long(1L), "nlz(1L)");
            Asserts.assertEquals(0, nlz_long(Long.MIN_VALUE), "nlz(MIN_VALUEL) = high bit set");
            Asserts.assertEquals(1, nlz_long(Long.MAX_VALUE), "nlz(MAX_VALUEL)");
            Asserts.assertEquals(31, nlz_long(0x0000000100000000L), "nlz(bit 32)");
            Asserts.assertEquals(32, nlz_long(0x00000000FFFFFFFFL), "nlz(low 32 bits)");

            // --- Long.numberOfTrailingZeros corner cases ---
            Asserts.assertEquals(64, ntz_long(0L), "ntz(0L)");
            Asserts.assertEquals(0, ntz_long(-1L), "ntz(-1L) = all bits set");
            Asserts.assertEquals(0, ntz_long(1L), "ntz(1L)");
            Asserts.assertEquals(63, ntz_long(Long.MIN_VALUE), "ntz(MIN_VALUEL) = high bit only");
            Asserts.assertEquals(1, ntz_long(2L), "ntz(2L)");
            Asserts.assertEquals(32, ntz_long(0x0000000100000000L), "ntz(bit 32)");
            Asserts.assertEquals(31, ntz_long(0x0000000080000000L), "ntz(bit 31)");

            // Random int values, cross-checked against an independent reference.
            for (int i = 0; i < 1000; i++) {
                int v = random.nextInt();
                Asserts.assertEquals(refNlzInt(v), nlz_int(v), "nlz int mismatch for " + v);
                Asserts.assertEquals(refNtzInt(v), ntz_int(v), "ntz int mismatch for " + v);
            }

            // Random long values, cross-checked against an independent reference.
            for (int i = 0; i < 1000; i++) {
                long v = random.nextLong();
                Asserts.assertEquals(refNlzLong(v), nlz_long(v), "nlz long mismatch for " + v);
                Asserts.assertEquals(refNtzLong(v), ntz_long(v), "ntz long mismatch for " + v);
            }

            System.out.println("TestCountZeros PASSED");
        }

        public static int nlz_int(int a) {
            return Integer.numberOfLeadingZeros(a);
        }

        public static int nlz_long(long a) {
            return Long.numberOfLeadingZeros(a);
        }

        public static int ntz_int(int a) {
            return Integer.numberOfTrailingZeros(a);
        }

        public static int ntz_long(long a) {
            return Long.numberOfTrailingZeros(a);
        }

        // Independent references: scan bits without using the intrinsified methods.
        static int refNlzInt(int v) {
            for (int b = 31; b >= 0; b--) {
                if ((v & (1 << b)) != 0) return 31 - b;
            }
            return 32;
        }

        static int refNtzInt(int v) {
            for (int b = 0; b < 32; b++) {
                if ((v & (1 << b)) != 0) return b;
            }
            return 32;
        }

        static int refNlzLong(long v) {
            for (int b = 63; b >= 0; b--) {
                if ((v & (1L << b)) != 0) return 63 - b;
            }
            return 64;
        }

        static int refNtzLong(long v) {
            for (int b = 0; b < 64; b++) {
                if ((v & (1L << b)) != 0) return b;
            }
            return 64;
        }
    }
}
