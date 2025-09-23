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

/*
 * @test
 * @library /test/lib /
 * @build compiler.jeandle.fileCheck.FileCheck
 * @run main/othervm -XX:-TieredCompilation -Xcomp -Xbatch
 *      -XX:CompileCommand=compileonly,compiler.jeandle.fileCheck.TestFileCheck::add
 *      -XX:+UseJeandleCompiler -XX:+JeandleDumpIR compiler.jeandle.fileCheck.TestFileCheck
 */

package compiler.jeandle.fileCheck;

public class TestFileCheck {
    public static void main(String[] args) throws Exception {
        // Dump IR
        add(1, 1);

        String currentDir = System.getProperty("user.dir");
        {
            FileCheck fileCheck = new FileCheck(currentDir,
                                                TestFileCheck.class.getDeclaredMethod("add", int.class, int.class),
                                                false);
            fileCheck.check("define hotspotcc i32 @\"compiler_jeandle_fileCheck_TestFileCheck_add_(II)I\"(i32 %0, i32 %1)");
            fileCheck.checkNext("entry:");
            fileCheck.checkNext("br label %bci_0");
        }
        {
            FileCheck fileCheck = new FileCheck(currentDir,
                                                TestFileCheck.class.getDeclaredMethod("add", int.class, int.class),
                                                true);
            fileCheck.check("define hotspotcc i32 @\"compiler_jeandle_fileCheck_TestFileCheck_add_(II)I\"(i32 %0, i32 %1)");
            fileCheck.checkNext("entry:");
            fileCheck.checkNext("%2 = add i32 %1, %0");
            fileCheck.checkNot("define private hotspotcc void @jeandle.safepoint_poll() #2 {");
        }

    }

    static int add(int a, int b) {
        return a + b;
    }
}
