/*
 * Copyright (c) 2025, 2026, the Jeandle-JDK Authors. All Rights Reserved.
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
 * @library /test/lib /
 * @build jdk.test.lib.Asserts
 * @run main/othervm compiler.jeandle.intrinsic.TestStringEquals
 */

package compiler.jeandle.intrinsic;

import compiler.jeandle.fileCheck.FileCheck;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;


public class TestStringEquals {
    public static void main(String[] args) throws Exception {
        String dump_path = Files.createTempDirectory("jeandle_test_string_equals").toString();
        ArrayList<String> command_args = new ArrayList<String>(List.of(
                "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
                "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dump_path,
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::arrayEqualsB",
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::arrayEqualsC",

                TestWrapper.class.getName()));

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(command_args);
        OutputAnalyzer output = ProcessTools.executeCommand(pb);

        output.shouldHaveExitValue(0)
                .shouldContain("Method `static jboolean java.util.Arrays.equals(jobject, jobject)` is parsed as intrinsic");

        // Verify the llvm intrinsic is used — only check the intrinsic call, not
        // control flow
        FileCheck equalsB_Checker = new FileCheck(dump_path,
                TestWrapper.class.getMethod("arrayEqualsB", byte[].class, byte[].class), false);
        equalsB_Checker.checkPattern("call hotspotcc i32 @jeandle.memcmp");

        FileCheck equalsC_Checker = new FileCheck(dump_path,
                TestWrapper.class.getMethod("arrayEqualsC", char[].class, char[].class), false);
        equalsC_Checker.checkPattern("call hotspotcc i32 @jeandle.memcmp");
    }

    static public class TestWrapper {
        // Force load java.lang.Arrays                              
        static String DUMMY = "hello";
        static boolean DUMMY_FLAG1 = Arrays.equals(DUMMY.getBytes(), DUMMY.getBytes()); 
        static boolean DUMMY_FLAG2 = Arrays.equals(DUMMY.toCharArray(), DUMMY.toCharArray()); 

        public static void main(String[] args) {
            String str1 = "abcde";
            String str2 = "abcde";
            String str3 = "abbde";
            String str4 = "abc";
            String str5 = "abcdef";

            byte[] b1 = str1.getBytes();
            byte[] b2 = str2.getBytes();
            byte[] b3 = str3.getBytes();
            byte[] b4 = str4.getBytes();
            byte[] b5 = str5.getBytes();
            Asserts.assertTrue(arrayEqualsB(b1, b1));
            Asserts.assertTrue(arrayEqualsB(b1, b2));
            Asserts.assertFalse(arrayEqualsB(b1, b3));
            Asserts.assertFalse(arrayEqualsB(b1, b4));
            Asserts.assertFalse(arrayEqualsB(b1, b5));
            Asserts.assertFalse(arrayEqualsB(b1, null));
            Asserts.assertFalse(arrayEqualsB(null, b1));
            Asserts.assertTrue(arrayEqualsB(null, null));

            char[] c1 = str1.toCharArray();
            char[] c2 = str2.toCharArray();
            char[] c3 = str3.toCharArray();
            char[] c4 = str4.toCharArray();
            char[] c5 = str5.toCharArray();
            Asserts.assertTrue(arrayEqualsC(c1, c1));
            Asserts.assertTrue(arrayEqualsC(c1, c2));
            Asserts.assertFalse(arrayEqualsC(c1, c3));
            Asserts.assertFalse(arrayEqualsC(c1, c4));
            Asserts.assertFalse(arrayEqualsC(c1, c5));
            Asserts.assertFalse(arrayEqualsC(c1, null));
            Asserts.assertFalse(arrayEqualsC(null, c1));
            Asserts.assertTrue(arrayEqualsC(null, null));
        }

        public static boolean arrayEqualsB(byte[] a, byte[] b) {
            return Arrays.equals(a, b);
        }

        public static boolean arrayEqualsC(char[] a, char[] b) {
            return Arrays.equals(a, b);
        }

    }
}
