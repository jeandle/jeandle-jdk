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
 * @summary test boolean field access.
 * @library /test/lib
 * @build jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *       -XX:-TieredCompilation -Xcomp -XX:+UseJeandleCompiler
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.field.TestBoolFieldAccess::testStaticFieldOps
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.field.TestBoolFieldAccess::testInstanceFieldOps
 *      compiler.jeandle.bytecodeTranslate.field.TestBoolFieldAccess
 */

package compiler.jeandle.bytecodeTranslate.field;

import java.lang.reflect.Method;

import jdk.test.lib.Asserts;
import jdk.test.whitebox.WhiteBox;

public class TestBoolFieldAccess {
    private final static WhiteBox wb = WhiteBox.getWhiteBox();
    static boolean sa = true;
    static boolean sb = true;

    // Static field operations
    static boolean testStaticFieldOps() {
        sa = false; // putstatic
        boolean res = sa | sb; // getstatic
        return res;
    }

    // Instance field operations
    static boolean testInstanceFieldOps(MyClass a) {
        a.ia = false; // putfield
        boolean res = a.ia | a.ib; // getfield
        return res;
    }

    public static void main(String[] args) throws Exception {
        // Test static field operations.
        boolean staticField = testStaticFieldOps();
        Asserts.assertEquals(staticField, true);
        Asserts.assertEquals(sa, false);
        Asserts.assertEquals(sb, true);

        // Test instance field operations.
        MyClass obj = new MyClass();
        boolean instanceField = testInstanceFieldOps(obj);
        Asserts.assertEquals(instanceField, true);
        Asserts.assertEquals(obj.ia, false);
        Asserts.assertEquals(obj.ib, true);

        var staticFieldMethod = TestBoolFieldAccess.class.getDeclaredMethod("testStaticFieldOps");
        if (!wb.isMethodCompiled(staticFieldMethod)) {
            throw new Exception("Method testStaticFieldOps should be compiled");
        }
        var instanceFieldMethod = TestBoolFieldAccess.class.getDeclaredMethod("testInstanceFieldOps", MyClass.class);
        if (!wb.isMethodCompiled(instanceFieldMethod)) {
            throw new Exception("Method testInstanceFieldOps should be compiled");
        }
    }
}

class MyClass {
    public boolean ia = true;
    public boolean ib = true;
}
