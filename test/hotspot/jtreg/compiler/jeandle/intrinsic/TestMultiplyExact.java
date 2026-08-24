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
 * @requires os.arch=="amd64" | os.arch=="x86_64" | os.arch=="aarch64"
 * @summary Test intrinsic implementation of Math.multiplyExact(int,int) and Math.multiplyExact(long,long)
 * @library /test/lib /
 * @run main/othervm compiler.jeandle.intrinsic.TestMultiplyExact
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

public class TestMultiplyExact {
    public static void main(String[] args) throws Exception {
        String dumpPath = Files.createTempDirectory("jeandle_multiply_exact").toString();

        ArrayList<String> commandArgs = new ArrayList<>(List.of(
                "-Xbatch",
                "-XX:-TieredCompilation",
                "-XX:+UseJeandleCompiler",
                "-Xcomp",
                "-Xlog:jeandle=debug",
                "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dumpPath,
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::multiply_int",
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::multiply_long",
                TestWrapper.class.getName()));

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(commandArgs);
        OutputAnalyzer output = ProcessTools.executeCommand(pb);

        output.shouldHaveExitValue(0)
              .shouldContain("Method `static jint java.lang.Math.multiplyExact(jint, jint)` is parsed as intrinsic")
              .shouldContain("Method `static jlong java.lang.Math.multiplyExact(jlong, jlong)` is parsed as intrinsic");

        // Verify LLVM IR for the int variant
        FileCheck intCheck = new FileCheck(dumpPath, TestWrapper.class.getMethod("multiply_int", int.class, int.class), false);
        intCheck.checkPattern("define hotspotcc i32 @\"compiler_jeandle_intrinsic_TestMultiplyExact\\$TestWrapper_multiply_int");
        intCheck.checkPattern("call \\{ i32, i1 \\} @llvm.smul.with.overflow.i32");
        intCheck.checkPattern("extractvalue \\{ i32, i1 \\}");
        intCheck.checkPattern("br i1");
        intCheck.checkPattern("multiplyExactI_ok");
        intCheck.checkPattern("multiplyExactI_overflow");

        // Verify LLVM IR for the long variant
        FileCheck longCheck = new FileCheck(dumpPath, TestWrapper.class.getMethod("multiply_long", long.class, long.class), false);
        longCheck.checkPattern("define hotspotcc i64 @\"compiler_jeandle_intrinsic_TestMultiplyExact\\$TestWrapper_multiply_long");
        longCheck.checkPattern("call \\{ i64, i1 \\} @llvm.smul.with.overflow.i64");
        longCheck.checkPattern("extractvalue \\{ i64, i1 \\}");
        longCheck.checkPattern("br i1");
        longCheck.checkPattern("multiplyExactL_ok");
        longCheck.checkPattern("multiplyExactL_overflow");
    }

    static class TestWrapper {
        static final int INT_DUMMY   = Math.multiplyExact(1, 1);  // force load java.lang.Math
        static final long LONG_DUMMY = Math.multiplyExact(1L, 1L);

        public static void main(String[] args) {
            // --- int variant ---
            Asserts.assertEquals(12,                multiply_int(3, 4));
            Asserts.assertEquals(0,                 multiply_int(0, Integer.MAX_VALUE));
            Asserts.assertEquals(Integer.MAX_VALUE, multiply_int(Integer.MAX_VALUE, 1));
            Asserts.assertEquals(Integer.MIN_VALUE, multiply_int(Integer.MIN_VALUE, 1));
            Asserts.assertEquals(-Integer.MAX_VALUE, multiply_int(Integer.MAX_VALUE, -1));

            // positive overflow: MAX_VALUE * 2
            try {
                multiply_int(Integer.MAX_VALUE, 2);
                Asserts.fail("expected ArithmeticException for int positive overflow");
            } catch (ArithmeticException e) {
                // expected
            }

            // negative overflow: MIN_VALUE * -1 (can't be represented, -MIN_VALUE overflows)
            try {
                multiply_int(Integer.MIN_VALUE, -1);
                Asserts.fail("expected ArithmeticException for int MIN_VALUE * -1 overflow");
            } catch (ArithmeticException e) {
                // expected
            }

            // --- long variant ---
            Asserts.assertEquals(12L,             multiply_long(3L, 4L));
            Asserts.assertEquals(0L,              multiply_long(0L, Long.MAX_VALUE));
            Asserts.assertEquals(Long.MAX_VALUE,  multiply_long(Long.MAX_VALUE, 1L));
            Asserts.assertEquals(Long.MIN_VALUE,  multiply_long(Long.MIN_VALUE, 1L));
            Asserts.assertEquals(-Long.MAX_VALUE, multiply_long(Long.MAX_VALUE, -1L));

            // positive overflow: Long.MAX_VALUE * 2
            try {
                multiply_long(Long.MAX_VALUE, 2L);
                Asserts.fail("expected ArithmeticException for long positive overflow");
            } catch (ArithmeticException e) {
                // expected
            }

            // negative overflow: Long.MIN_VALUE * -1
            try {
                multiply_long(Long.MIN_VALUE, -1L);
                Asserts.fail("expected ArithmeticException for long MIN_VALUE * -1 overflow");
            } catch (ArithmeticException e) {
                // expected
            }

            // ---- Random fuzzing, checked against the interpreter-run
            //      Math.multiplyExact (main and the fuzz helpers are not in
            //      the compileonly list, so the expected values come from the
            //      plain Java implementation). Full-range operands overflow
            //      nearly always, so also fuzz small windows whose products
            //      cannot overflow, keeping the ok path as exercised as the
            //      trap path. ----
            var random = Utils.getRandomInstance();
            for (int i = 0; i < 5000; i++) {
                fuzz_int(random.nextInt(), random.nextInt());
                fuzz_int(random.nextInt(1 << 16) - (1 << 15), random.nextInt(1 << 16) - (1 << 15));
                fuzz_long(random.nextLong(), random.nextLong());
                fuzz_long(random.nextInt(), random.nextInt());
            }
        }

        static void fuzz_int(int a, int b) {
            int expected = 0;
            boolean overflow = false;
            try {
                expected = Math.multiplyExact(a, b);
            } catch (ArithmeticException e) {
                overflow = true;
            }
            try {
                int actual = multiply_int(a, b);
                Asserts.assertFalse(overflow, "missing int overflow for " + a + " * " + b);
                Asserts.assertEquals(expected, actual, "multiplyExact(int) mismatch for " + a + " * " + b);
            } catch (ArithmeticException e) {
                Asserts.assertTrue(overflow, "unexpected int overflow for " + a + " * " + b);
            }
        }

        static void fuzz_long(long a, long b) {
            long expected = 0L;
            boolean overflow = false;
            try {
                expected = Math.multiplyExact(a, b);
            } catch (ArithmeticException e) {
                overflow = true;
            }
            try {
                long actual = multiply_long(a, b);
                Asserts.assertFalse(overflow, "missing long overflow for " + a + " * " + b);
                Asserts.assertEquals(expected, actual, "multiplyExact(long) mismatch for " + a + " * " + b);
            } catch (ArithmeticException e) {
                Asserts.assertTrue(overflow, "unexpected long overflow for " + a + " * " + b);
            }
        }

        public static int multiply_int(int a, int b) {
            return Math.multiplyExact(a, b);
        }

        public static long multiply_long(long a, long b) {
            return Math.multiplyExact(a, b);
        }
    }
}
