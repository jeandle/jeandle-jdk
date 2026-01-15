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
 * @summary test aaload and aastore bytecodes
 * @library /test/lib
 * @build jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *      -XX:-TieredCompilation -Xcomp -XX:+UseJeandleCompiler
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.array.TestObjectArray::testLoadStore
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.array.TestObjectArray::testStoreIncompatibleType
 *      compiler.jeandle.bytecodeTranslate.array.TestObjectArray
 */

public class TestObjectArray {
    private final static WhiteBox wb = WhiteBox.getWhiteBox();
    private static Object obj = new Object();
    private static Object newObj = new Object();
    private static Object[] objArr = new Object[]{obj, obj, obj};
    private static TestObjectArray[] testArr = new TestObjectArray[3];

    public static void main(String[] args) throws Exception {
        var objVal = testLoadStore();
        Asserts.assertEquals(objArr[0], obj);
        Asserts.assertEquals(objVal, newObj);
        Asserts.assertEquals(objArr[2], obj);

        var loadMethod = TestObjectArray.class.getDeclaredMethod("testLoadStore");
        if (!wb.isMethodCompiled(loadMethod)) {
            throw new Exception("Method testLoadStore should be compiled");
        }

        Asserts.assertThrows(ArrayStoreException.class, () -> testStoreIncompatibleType());
    }

    public static Object testLoadStore() {
        objArr[1] = newObj;
        var objVal = objArr[1];
        return objVal;
    }

    public static void testStoreIncompatibleType() {
        Object[] array = (Object[])testArr;
        array[0] = "Hello";
    }
}
