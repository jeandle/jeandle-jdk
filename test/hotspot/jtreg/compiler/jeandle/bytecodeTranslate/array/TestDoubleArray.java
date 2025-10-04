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
 * @summary test daload and dastore bytecodes
 * @library /test/lib
 * @build jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *      -XX:-TieredCompilation -Xcomp -XX:+UseJeandleCompiler
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.array.TestDoubleArray::testLoadStore
 *      compiler.jeandle.bytecodeTranslate.array.TestDoubleArray
 */

public class TestDoubleArray {
    private final static WhiteBox wb = WhiteBox.getWhiteBox();
    private static double[] doubleArr = new double[]{50.0, 51.0, 52.0};

    public static void main(String[] args) throws Exception {
        var doubleVal = testLoadStore();
        Asserts.assertEquals(doubleVal, 105.12);
        Asserts.assertEquals(doubleArr[1], 51.0);
        Asserts.assertEquals(doubleArr[2], 52.0);

        var loadMethod = TestDoubleArray.class.getDeclaredMethod("testLoadStore");
        if (!wb.isMethodCompiled(loadMethod)) {
            throw new Exception("Method testLoadStore should be compiled");
        }
    }

    public static double testLoadStore() {
        doubleArr[0] = 105.12;
        var doubleVal = doubleArr[0];
        return doubleVal;
    }
}
