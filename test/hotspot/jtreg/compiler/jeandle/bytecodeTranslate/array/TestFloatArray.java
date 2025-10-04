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
 * @summary test faload and fastore bytecodes
 * @library /test/lib
 * @build jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *      -XX:-TieredCompilation -Xcomp -XX:+UseJeandleCompiler
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.array.TestFloatArray::testLoadStore
 *      compiler.jeandle.bytecodeTranslate.array.TestFloatArray
 */

public class TestFloatArray {
    private final static WhiteBox wb = WhiteBox.getWhiteBox();
    private static float[] floatArr = new float[]{40.0f, 41.0f, 42.0f};

    public static void main(String[] args) throws Exception {
        var floatVal = testLoadStore();
        Asserts.assertEquals(floatArr[0], 40.0f);
        Asserts.assertEquals(floatVal, 104.0f);
        Asserts.assertEquals(floatArr[2], 42.0f);

        var loadMethod = TestFloatArray.class.getDeclaredMethod("testLoadStore");
        if (!wb.isMethodCompiled(loadMethod)) {
            throw new Exception("Method testLoadStore should be compiled");
        }
    }

    public static float testLoadStore() {
        floatArr[1] = 104.0f;
        var floatVal = floatArr[1];
        return floatVal;
    }
}
