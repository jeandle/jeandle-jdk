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
 * @summary Test intrinsic implementation of Math.incrementExact(int) and Math.incrementExact(long)
 * @library /test/lib /
 * @run main/othervm compiler.jeandle.intrinsic.TestIncrementExact
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

public class TestIncrementExact {
    public static void main(String[] args) throws Exception {
        String dumpPath = Files.createTempDirectory("jeandle_increment_exact").toString();

        ArrayList<String> commandArgs = new ArrayList<>(List.of(
                "-Xbatch",
                "-XX:-TieredCompilation",
                "-XX:+UseJeandleCompiler",
                "-Xcomp",
                "-Xlog:jeandle=debug",
                "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dumpPath,
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::increment_int",
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::increment_long",
                TestWrapper.class.getName()));

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(commandArgs);
        OutputAnalyzer output = ProcessTools.executeCommand(pb);

        output.shouldHaveExitValue(0)
              .shouldContain("Method `static jint java.lang.Math.incrementExact(jint)` is parsed as intrinsic")
              .shouldContain("Method `static jlong java.lang.Math.incrementExact(jlong)` is parsed as intrinsic");

        // Verify LLVM IR for the int variant
        FileCheck intCheck = new FileCheck(dumpPath, TestWrapper.class.getMethod("increment_int", int.class), false);
        intCheck.checkPattern("define hotspotcc i32 @\"compiler_jeandle_intrinsic_TestIncrementExact\\$TestWrapper_increment_int");
        intCheck.checkPattern("call \\{ i32, i1 \\} @llvm.sadd.with.overflow.i32");
        intCheck.checkPattern("extractvalue \\{ i32, i1 \\}");
        intCheck.checkPattern("br i1");
        intCheck.checkPattern("incrementExactI_ok");
        intCheck.checkPattern("incrementExactI_overflow");

        // Verify LLVM IR for the long variant
        FileCheck longCheck = new FileCheck(dumpPath, TestWrapper.class.getMethod("increment_long", long.class), false);
        longCheck.checkPattern("define hotspotcc i64 @\"compiler_jeandle_intrinsic_TestIncrementExact\\$TestWrapper_increment_long");
        longCheck.checkPattern("call \\{ i64, i1 \\} @llvm.sadd.with.overflow.i64");
        longCheck.checkPattern("extractvalue \\{ i64, i1 \\}");
        longCheck.checkPattern("br i1");
        longCheck.checkPattern("incrementExactL_ok");
        longCheck.checkPattern("incrementExactL_overflow");
    }

    static class TestWrapper {
        static final int INT_DUMMY   = Math.incrementExact(0);  // force load java.lang.Math
        static final long LONG_DUMMY = Math.incrementExact(0L);

        public static void main(String[] args) {
            // --- int variant ---
            Asserts.assertEquals(6,                 increment_int(5));
            Asserts.assertEquals(0,                 increment_int(-1));
            Asserts.assertEquals(Integer.MIN_VALUE + 1, increment_int(Integer.MIN_VALUE));
            Asserts.assertEquals(Integer.MAX_VALUE, increment_int(Integer.MAX_VALUE - 1));

            // overflow: MAX_VALUE + 1
            try {
                increment_int(Integer.MAX_VALUE);
                Asserts.fail("expected ArithmeticException for int overflow");
            } catch (ArithmeticException e) {
                // expected
            }

            // --- long variant ---
            Asserts.assertEquals(6L,             increment_long(5L));
            Asserts.assertEquals(0L,             increment_long(-1L));
            Asserts.assertEquals(Long.MAX_VALUE, increment_long(Long.MAX_VALUE - 1L));

            // overflow: Long.MAX_VALUE + 1
            try {
                increment_long(Long.MAX_VALUE);
                Asserts.fail("expected ArithmeticException for long overflow");
            } catch (ArithmeticException e) {
                // expected
            }

            // ---- Random fuzzing across the full int/long range, checked
            //      against the interpreter-run Math.incrementExact (main and
            //      the fuzz helpers are not in the compileonly list, so the
            //      expected values come from the plain Java implementation). ----
            var random = Utils.getRandomInstance();
            for (int i = 0; i < 5000; i++) {
                fuzz_int(random.nextInt());
                fuzz_long(random.nextLong());
            }
        }

        static void fuzz_int(int a) {
            int expected = 0;
            boolean overflow = false;
            try {
                expected = Math.incrementExact(a);
            } catch (ArithmeticException e) {
                overflow = true;
            }
            try {
                int actual = increment_int(a);
                Asserts.assertFalse(overflow, "missing int overflow for " + a);
                Asserts.assertEquals(expected, actual, "incrementExact(int) mismatch for " + a);
            } catch (ArithmeticException e) {
                Asserts.assertTrue(overflow, "unexpected int overflow for " + a);
            }
        }

        static void fuzz_long(long a) {
            long expected = 0L;
            boolean overflow = false;
            try {
                expected = Math.incrementExact(a);
            } catch (ArithmeticException e) {
                overflow = true;
            }
            try {
                long actual = increment_long(a);
                Asserts.assertFalse(overflow, "missing long overflow for " + a);
                Asserts.assertEquals(expected, actual, "incrementExact(long) mismatch for " + a);
            } catch (ArithmeticException e) {
                Asserts.assertTrue(overflow, "unexpected long overflow for " + a);
            }
        }

        public static int increment_int(int a) {
            return Math.incrementExact(a);
        }

        public static long increment_long(long a) {
            return Math.incrementExact(a);
        }
    }
}
