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
* @test TestFieldAccess.java
* @library /test/lib
* @run main/othervm -XX:-TieredCompilation -Xcomp
*      -XX:CompileCommand=compileonly,TestFieldAccess::testStaticFieldOps
*      -XX:CompileCommand=compileonly,TestFieldAccess::testInstanceFieldOps
*      -XX:+UseJeandleCompiler compiler.jeandle.bytecodeTranslate.TestFieldAccess
*/

package compiler.jeandle.bytecodeTranslate;

public class TestFieldAccess {
    static int sa = 10;
    static int sb = 20;

    // Static field operations
    static int testStaticFieldOps() {
        sb = 22; // putstatic
        int sum = sa + sb; // getstatic
        return sum;
    }

    // Instance field operations
    static int testInstanceFieldOps(MyClass a) {
        a.field = 200; // putfield
        int val = a.field; // getfield
        return val;
    }

    public static void main(String[] args) throws Exception {
        // Test static field operations.
        int staticField = testStaticFieldOps();

        if (staticField == 32) {
            System.out.println("SUCCESS: Static field access is working correctly!");
        } else {
            System.out.println("FAILURE: Static field access is not working correctly! staticField=" + staticField);
        }
        
        // Test instance field operations.
        MyClass obj = new MyClass();
        int instanceField = testInstanceFieldOps(obj);

        if (instanceField == 200) {
            System.out.println("SUCCESS: Instance field access is working correctly!");
        } else {
            System.out.println("FAILURE: Instance field access is not working correctly! instanceField=" + instanceField);
        }
    }
}

class MyClass {
    public int field = 100;
}
