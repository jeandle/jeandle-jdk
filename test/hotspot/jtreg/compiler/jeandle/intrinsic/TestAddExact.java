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
 * @summary Test intrinsic implementation of Math.addExact(int,int) and Math.addExact(long,long)
 * @library /test/lib /
 * @run main/othervm compiler.jeandle.intrinsic.TestAddExact
 */

package compiler.jeandle.intrinsic;

import compiler.jeandle.fileCheck.FileCheck;
import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class TestAddExact {
    public static void main(String[] args) throws Exception {
        String dumpPath = Files.createTempDirectory("jeandle_add_exact").toString();

        ArrayList<String> commandArgs = new ArrayList<>(List.of(
                "-Xbatch",
                "-XX:-TieredCompilation",
                "-XX:+UseJeandleCompiler",
                "-Xcomp",
                "-Xlog:jeandle=debug",
                "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dumpPath,
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::add_int",
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::add_long",
                TestWrapper.class.getName()));

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(commandArgs);
        OutputAnalyzer output = ProcessTools.executeCommand(pb);

        output.shouldHaveExitValue(0)
              .shouldContain("Method `static jint java.lang.Math.addExact(jint, jint)` is parsed as intrinsic")
              .shouldContain("Method `static jlong java.lang.Math.addExact(jlong, jlong)` is parsed as intrinsic");

        // Verify LLVM IR for the int variant
        FileCheck intCheck = new FileCheck(dumpPath, TestWrapper.class.getMethod("add_int", int.class, int.class), false);
        intCheck.checkPattern("define hotspotcc i32 @\"compiler_jeandle_intrinsic_TestAddExact\\$TestWrapper_add_int");
        intCheck.checkPattern("call \\{ i32, i1 \\} @llvm.sadd.with.overflow.i32");
        intCheck.checkPattern("extractvalue \\{ i32, i1 \\}");
        intCheck.checkPattern("br i1");
        intCheck.checkPattern("addExactI_ok");       // ok block label appears before overflow in IR
        intCheck.checkPattern("addExactI_overflow");

        // Verify LLVM IR for the long variant
        FileCheck longCheck = new FileCheck(dumpPath, TestWrapper.class.getMethod("add_long", long.class, long.class), false);
        longCheck.checkPattern("define hotspotcc i64 @\"compiler_jeandle_intrinsic_TestAddExact\\$TestWrapper_add_long");
        longCheck.checkPattern("call \\{ i64, i1 \\} @llvm.sadd.with.overflow.i64");
        longCheck.checkPattern("extractvalue \\{ i64, i1 \\}");
        longCheck.checkPattern("br i1");
        longCheck.checkPattern("addExactL_ok");       // ok block label appears before overflow in IR
        longCheck.checkPattern("addExactL_overflow");
    }

    static class TestWrapper {
        static final int INT_DUMMY  = Math.addExact(0, 0);  // force load java.lang.Math
        static final long LONG_DUMMY = Math.addExact(0L, 0L);

        public static void main(String[] args) {
            // --- int variant ---
            Asserts.assertEquals(3,                      add_int(1, 2));
            Asserts.assertEquals(0,                      add_int(0, 0));
            Asserts.assertEquals(Integer.MAX_VALUE,      add_int(Integer.MAX_VALUE, 0));
            Asserts.assertEquals(Integer.MIN_VALUE,      add_int(Integer.MIN_VALUE, 0));
            Asserts.assertEquals(Integer.MAX_VALUE,      add_int(Integer.MAX_VALUE - 1, 1));
            Asserts.assertEquals(-1,                     add_int(Integer.MAX_VALUE, Integer.MIN_VALUE));

            // positive overflow: MAX_VALUE + 1
            try {
                add_int(Integer.MAX_VALUE, 1);
                Asserts.fail("expected ArithmeticException for int positive overflow");
            } catch (ArithmeticException e) {
                // expected
            }

            // negative overflow: MIN_VALUE + (-1)
            try {
                add_int(Integer.MIN_VALUE, -1);
                Asserts.fail("expected ArithmeticException for int negative overflow");
            } catch (ArithmeticException e) {
                // expected
            }

            // --- long variant ---
            Asserts.assertEquals(3L,                  add_long(1L, 2L));
            Asserts.assertEquals(0L,                  add_long(0L, 0L));
            Asserts.assertEquals(Long.MAX_VALUE,      add_long(Long.MAX_VALUE, 0L));
            Asserts.assertEquals(Long.MIN_VALUE,      add_long(Long.MIN_VALUE, 0L));
            Asserts.assertEquals(Long.MAX_VALUE,      add_long(Long.MAX_VALUE - 1L, 1L));
            Asserts.assertEquals(-1L,                 add_long(Long.MAX_VALUE, Long.MIN_VALUE));

            // positive overflow: Long.MAX_VALUE + 1
            try {
                add_long(Long.MAX_VALUE, 1L);
                Asserts.fail("expected ArithmeticException for long positive overflow");
            } catch (ArithmeticException e) {
                // expected
            }

            // negative overflow: Long.MIN_VALUE + (-1)
            try {
                add_long(Long.MIN_VALUE, -1L);
                Asserts.fail("expected ArithmeticException for long negative overflow");
            } catch (ArithmeticException e) {
                // expected
            }
        }

        public static int add_int(int a, int b) {
            return Math.addExact(a, b);
        }

        public static long add_long(long a, long b) {
            return Math.addExact(a, b);
        }
    }
}
