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
 * @summary test float field access.
 * @library /test/lib
 * @build jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *       -XX:-TieredCompilation -Xcomp -XX:+UseJeandleCompiler
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.field.TestFloatFieldAccess::testStaticFieldOps
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.field.TestFloatFieldAccess::testInstanceFieldOps
 *      compiler.jeandle.bytecodeTranslate.field.TestFloatFieldAccess
 */

package compiler.jeandle.bytecodeTranslate.field;

import java.lang.reflect.Method;

import jdk.test.lib.Asserts;
import jdk.test.whitebox.WhiteBox;

public class TestFloatFieldAccess {
    private final static WhiteBox wb = WhiteBox.getWhiteBox();
    static float sa = 10.0f;
    static float sb = 20.0f;

    // Static field operations
    static float testStaticFieldOps() {
        sa = 32.0f; // putstatic
        float sum = sa + sb; // getstatic
        return sum;
    }

    // Instance field operations
    static float testInstanceFieldOps(MyClass a) {
        a.ia = 33.0f; // putfield
        float sum = a.ia + a.ib; // getfield
        return sum;
    }

    public static void main(String[] args) throws Exception {
        // Test static field operations.
        float staticField = testStaticFieldOps();
        Asserts.assertEquals(staticField, 52.0f);

        // Test instance field operations.
        MyClass obj = new MyClass();
        float instanceField = testInstanceFieldOps(obj);
        Asserts.assertEquals(instanceField, 53.0f);

        var staticFieldMethod = TestFloatFieldAccess.class.getDeclaredMethod("testStaticFieldOps");
        if (!wb.isMethodCompiled(staticFieldMethod)) {
            throw new Exception("Method testStaticFieldOps should be compiled");
        }
        var instanceFieldMethod = TestFloatFieldAccess.class.getDeclaredMethod("testInstanceFieldOps", MyClass.class);
        if (!wb.isMethodCompiled(instanceFieldMethod)) {
            throw new Exception("Method testInstanceFieldOps should be compiled");
        }
    }
}

class MyClass {
    public float ia = 10.0f;
    public float ib = 20.0f;
}
