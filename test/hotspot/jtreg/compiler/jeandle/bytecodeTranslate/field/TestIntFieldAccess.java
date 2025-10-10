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
 * @summary test int field access.
 * @library /test/lib
 * @build jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *       -XX:-TieredCompilation -Xcomp -XX:+UseJeandleCompiler
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.field.TestIntFieldAccess::testStaticFieldOps
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.field.TestIntFieldAccess::testInstanceFieldOps
 *      compiler.jeandle.bytecodeTranslate.field.TestIntFieldAccess
 */

package compiler.jeandle.bytecodeTranslate.field;

import java.lang.reflect.Method;

import jdk.test.lib.Asserts;
import jdk.test.whitebox.WhiteBox;

public class TestIntFieldAccess {
    private final static WhiteBox wb = WhiteBox.getWhiteBox();
    static int sa = 10;
    static int sb = 20;

    // Static field operations
    static int testStaticFieldOps() {
        sa = 32; // putstatic
        int sum = sa + sb; // getstatic
        return sum;
    }

    // Instance field operations
    static int testInstanceFieldOps(MyClass a) {
        a.ia = 33; // putfield
        int sum = a.ia + a.ib; // getfield
        return sum;
    }

    public static void main(String[] args) throws Exception {
        // Test static field operations.
        int staticField = testStaticFieldOps();
        Asserts.assertEquals(staticField, 52);

        // Test instance field operations.
        MyClass obj = new MyClass();
        int instanceField = testInstanceFieldOps(obj);
        Asserts.assertEquals(instanceField, 53);

        var staticFieldMethod = TestIntFieldAccess.class.getDeclaredMethod("testStaticFieldOps");
        if (!wb.isMethodCompiled(staticFieldMethod)) {
            throw new Exception("Method testStaticFieldOps should be compiled");
        }
        var instanceFieldMethod = TestIntFieldAccess.class.getDeclaredMethod("testInstanceFieldOps", MyClass.class);
        if (!wb.isMethodCompiled(instanceFieldMethod)) {
            throw new Exception("Method testInstanceFieldOps should be compiled");
        }
    }
}

class MyClass {
    public int ia = 10;
    public int ib = 20;
}
