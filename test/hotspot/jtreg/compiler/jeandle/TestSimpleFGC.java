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

/**
 * @test
 * @library /test/lib
 * @build jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *      -XX:CompileCommand=compileonly,TestSimpleFGC::test0
 *      -XX:CompileCommand=compileonly,TestSimpleFGC::test1
 *      -XX:CompileCommand=compileonly,TestSimpleFGC::test2
 *      -Xcomp -XX:-TieredCompilation -XX:+UseJeandleCompiler TestSimpleFGC
 */

import jdk.test.lib.Asserts;
import jdk.test.whitebox.WhiteBox;

public class TestSimpleFGC {
    private final static WhiteBox wb = WhiteBox.getWhiteBox();

    private static int sa = 10;

    public static void main(String[] args) {
        MyClass a = new MyClass();
        test0(a);
        test1(a);
        test2();
    }

    static MyClass createObj() {
        return new MyClass();
    }

    static void triggerGC() {
        wb.fullGC();
    }

    static void test0(MyClass a) {
        triggerGC();
        Asserts.assertEquals(a.getA(), 1);
    }

    static void test1(MyClass a) {
        a.b = 3;
        triggerGC();
        Asserts.assertEquals(a.b, 3);
        a.b = 4;
        Asserts.assertEquals(a.b, 4);
    }

    static void test2() {
        sa = 12;
        Asserts.assertEquals(sa, 12);
        sa= 13;
        triggerGC();
        Asserts.assertEquals(sa, 13);
    }
}

class MyClass {
    int a = 1;
    int getA() {return a;}
    public int b = 2;
}
