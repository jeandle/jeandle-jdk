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
 * @summary test object field access.
 * @library /test/lib
 * @build jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *       -XX:-TieredCompilation -Xcomp -XX:+UseJeandleCompiler
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.field.TestObjectFieldAccess::testStaticFieldOps
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.field.TestObjectFieldAccess::testInstanceFieldOps
 *      compiler.jeandle.bytecodeTranslate.field.TestObjectFieldAccess
 */

package compiler.jeandle.bytecodeTranslate.field;

import java.lang.reflect.Method;

import jdk.test.lib.Asserts;
import jdk.test.whitebox.WhiteBox;

public class TestObjectFieldAccess {
    private final static WhiteBox wb = WhiteBox.getWhiteBox();
    static Object oldObj = new Object();
    static Object sa = oldObj;
    static Object sb = oldObj;
    static Object newObj = new Object();

    // Static field operations
    static Object testStaticFieldOps() {
        sa = newObj; // putstatic
        return sa; // getstatic
    }

    // Instance field operations
    static Object testInstanceFieldOps(MyClass a) {
        a.ia = newObj; // putfield
        return a.ia; // getfield
    }

    public static void main(String[] args) throws Exception {
        // Test static field operations.
        Object staticField = testStaticFieldOps();
        Asserts.assertEquals(staticField, newObj);
        Asserts.assertEquals(sb, oldObj);

        // Test instance field operations.
        MyClass myObj = new MyClass(oldObj);
        Object instanceField = testInstanceFieldOps(myObj);
        Asserts.assertEquals(instanceField, newObj);
        Asserts.assertEquals(myObj.ib, oldObj);

        var staticFieldMethod = TestObjectFieldAccess.class.getDeclaredMethod("testStaticFieldOps");
        if (!wb.isMethodCompiled(staticFieldMethod)) {
            throw new Exception("Method testStaticFieldOps should be compiled");
        }
        var instanceFieldMethod = TestObjectFieldAccess.class.getDeclaredMethod("testInstanceFieldOps", MyClass.class);
        if (!wb.isMethodCompiled(instanceFieldMethod)) {
            throw new Exception("Method testInstanceFieldOps should be compiled");
        }
    }
}

class MyClass {
    public MyClass(Object oldObj) {
        ia = oldObj;
        ib = oldObj;
    }
    public Object ia;
    public Object ib;
}
