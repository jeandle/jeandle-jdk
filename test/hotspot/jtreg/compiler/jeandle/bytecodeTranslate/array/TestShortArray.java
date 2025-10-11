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
 * @summary test saload and sastore bytecodes
 * @library /test/lib
 * @build jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *      -XX:-TieredCompilation -Xcomp -XX:+UseJeandleCompiler
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.array.TestShortArray::testLoadStore
 *      compiler.jeandle.bytecodeTranslate.array.TestShortArray
 */

public class TestShortArray {
    private final static WhiteBox wb = WhiteBox.getWhiteBox();
    private static short[] shortArr = new short[]{10, 11, 12};

    public static void main(String[] args) throws Exception {
        var shortVal = testLoadStore();
        Asserts.assertEquals(shortArr[0], (short) 10);
        Asserts.assertEquals(shortArr[1], (short) 11);
        Asserts.assertEquals(shortVal, (short) 101);

        var loadMethod = TestShortArray.class.getDeclaredMethod("testLoadStore");
        if (!wb.isMethodCompiled(loadMethod)) {
            throw new Exception("Method testLoadStore should be compiled");
        }
    }

    public static short testLoadStore() {
        shortArr[2] = 101;
        var shortVal = shortArr[2];
        return shortVal;
    }
}
