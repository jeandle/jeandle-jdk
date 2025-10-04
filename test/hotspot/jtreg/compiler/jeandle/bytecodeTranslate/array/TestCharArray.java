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

package compiler.jeandle.bytecodeTranslate.array;

import java.lang.reflect.Method;

import jdk.test.lib.Asserts;
import jdk.test.whitebox.WhiteBox;

/*
 * @test
 * @summary test caload and castore bytecodes
 * @library /test/lib
 * @build jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *      -XX:-TieredCompilation -Xcomp -XX:+UseJeandleCompiler
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.array.TestCharArray::testLoadStore
 *      compiler.jeandle.bytecodeTranslate.array.TestCharArray
 */

public class TestCharArray {
    private final static WhiteBox wb = WhiteBox.getWhiteBox();
    private static char[] charArr = new char[]{'a', 'b', 'c'};

    public static void main(String[] args) throws Exception {
        var charVal = testLoadStore();
        Asserts.assertEquals(charArr[0], 'a');
        Asserts.assertEquals(charArr[1], 'b');
        Asserts.assertEquals(charVal, 'y');

        var loadMethod = TestCharArray.class.getDeclaredMethod("testLoadStore");
        if (!wb.isMethodCompiled(loadMethod)) {
            throw new Exception("Method testLoadStore should be compiled");
        }
    }

    public static char testLoadStore() {
        charArr[2] = 'y';
        var charVal = charArr[2];
        return charVal;
    }
}
