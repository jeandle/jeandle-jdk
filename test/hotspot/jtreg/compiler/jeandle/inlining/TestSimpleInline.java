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

/**
 * @test TestSimpleInline.java
 * @summary Test simple static method inlining in Jeandle compiler, verify via LLVM IR
 * @library /test/lib /
 * @build compiler.jeandle.fileCheck.FileCheck
 * @build jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 *
 * @run main/othervm -XX:-TieredCompilation -Xcomp
 *      -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *      -XX:CompileCommand=compileonly,compiler.jeandle.inlining.TestSimpleInline::main
 *      -XX:CompileCommand=compileonly,compiler.jeandle.inlining.TestSimpleInline::add
 *      -XX:+UseJeandleCompiler -XX:-JeandleInline -XX:+JeandleDumpIR
 *      compiler.jeandle.inlining.TestSimpleInline no-inline
 *
 * @run main/othervm -XX:-TieredCompilation -Xcomp
 *      -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *      -XX:CompileCommand=compileonly,compiler.jeandle.inlining.TestSimpleInline::main
 *      -XX:CompileCommand=compileonly,compiler.jeandle.inlining.TestSimpleInline::add
 *      -XX:+UseJeandleCompiler -XX:+JeandleInline -XX:+JeandleDumpIR
 *      compiler.jeandle.inlining.TestSimpleInline with-inline
 */

package compiler.jeandle.inlining;

import jdk.test.lib.Asserts;
import jdk.test.whitebox.WhiteBox;
import compiler.jeandle.fileCheck.FileCheck;

public class TestSimpleInline {
    private static WhiteBox wb = WhiteBox.getWhiteBox();

    static volatile int va = 3;
    static volatile int vb = 5;

    public static void main(String[] args) throws Exception {
        int a = va;
        int b = vb;
        int c = add(a, b);
        Asserts.assertEquals(c, a + b, "add(a, b) should return a + b");

        String currentDir = System.getProperty("user.dir");
        String mode = (args.length > 0) ? args[0] : "with-inline";

        if ("no-inline".equals(mode)) {
            // Without inlining: main's IR must contain a call to add()
            FileCheck fileCheck = new FileCheck(currentDir,
                                                TestSimpleInline.class.getDeclaredMethod("main", String[].class),
                                                false);
            fileCheck.checkPattern("define hotspotcc .* @.*TestSimpleInline_main.*");
            fileCheck.checkPattern("invoke .* @.*TestSimpleInline_add.*");
        } else {
            // With inlining: main's IR should NOT contain a call to add()
            // fileIndex=1 to skip the IR file from the previous no-inline run
            FileCheck fileCheck = new FileCheck(currentDir,
                                                TestSimpleInline.class.getDeclaredMethod("main", String[].class),
                                                false, 1);
            fileCheck.checkPattern("define hotspotcc .* @.*TestSimpleInline_main.*");
            fileCheck.checkNotPattern("invoke .* @.*TestSimpleInline_add.*");
        }
    }

    static int add(int x, int y) {
        return x + y;
    }
}
