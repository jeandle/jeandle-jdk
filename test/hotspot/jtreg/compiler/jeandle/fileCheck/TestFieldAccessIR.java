/*
 * Copyright (c) 2025, the Jeandle-JDK Authors. All Rights Reserved.
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

/**
 * @test
 * @library /test/lib
 * @build jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *      -XX:CompileCommand=compileonly,compiler.jeandle.fileCheck.TestFieldAccessIR::test
 *      -Xcomp -XX:-TieredCompilation -XX:+JeandleDumpIR -XX:+UseJeandleCompiler
 *      compiler.jeandle.fileCheck.TestFieldAccessIR
 */

package compiler.jeandle.fileCheck;

import java.lang.reflect.Method;
import java.io.*;
import java.nio.file.*;
import java.util.regex.*;

import jdk.test.lib.Asserts;
import jdk.test.whitebox.WhiteBox;

public class TestFieldAccessIR {
    private final static WhiteBox wb = WhiteBox.getWhiteBox();

    private static boolean flag = true;
    private static int counter = 0;

    public static void main(String[] args) throws Exception {
        Method m = TestFieldAccessIR.class.getDeclaredMethod("test");

        wb.enqueueMethodForCompilation(m, 4);

        // Wait until method is compiled.
        while (!wb.isMethodCompiled(m)) {
            Thread.sleep(100);
        }

        String dir = ".";
        String fileRegex = "compiler_jeandle_fileCheck_TestFieldAccessIR_test_.*\\.ll";
        String content = findFileAndReadContent(dir, fileRegex);

        assertContains(content, "load volatile i32");
        assertContains(content, "store volatile i32");
        assertDoesNotContain(content, "load i32");
        assertDoesNotContain(content, "store i32");
    }

    private static void test() {
        for (int i = 0; i < 50000; i++) {
            while (!flag) {
            }
            counter++;
            flag = false;
        }
    }

    public static String findFileAndReadContent(String dirPath,
                                                String filenameRegex) throws IOException {
        Pattern filePattern = Pattern.compile(filenameRegex);
        File dir = new File(dirPath);
        if (!dir.isDirectory()) throw new IllegalArgumentException("Not a directory: " + dirPath);

        // Find file.
        File matchedFile = null;
        for (File file : dir.listFiles()) {
            if (file.isFile() && filePattern.matcher(file.getName()).matches()) {
                matchedFile = file;
                break;
            }
        }
        if (matchedFile == null) return "";

        return new String(Files.readAllBytes(matchedFile.toPath()), "UTF-8");
    }

    /**
     * Tests that s1 contains s2.
     */
    private static void assertContains(String s1, String s2) {
        Asserts.assertTrue(s1.contains(s2), s2 + " not found!!!");
    }

    /**
     * Tests that s1 does not contain s2.
     */
    private static void assertDoesNotContain(String s1, String s2) {
        Asserts.assertFalse(s1.contains(s2), s2 + " found!!");
    }
}
