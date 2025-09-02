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
 *      -XX:CompileCommand=compileonly,TestSimpleFGC::test*
 *      -Xcomp -XX:-TieredCompilation -XX:+UseJeandleCompiler TestSimpleFGC
 */

import java.lang.reflect.Method;

import jdk.test.lib.Asserts;
import jdk.test.whitebox.WhiteBox;

public class TestSimpleFGC {
    private final static WhiteBox wb = WhiteBox.getWhiteBox();

    private static int sa = 10;

    private static volatile boolean stop = false;

    public static void main(String[] args) throws Exception {
        MyClass a = new MyClass();
        test0(a);
        test1(a);
        test2();

        {
            new Thread(() -> {
                test3(a);
            }).start();

            Method method = TestSimpleFGC.class.getDeclaredMethod("test3", MyClass.class);
            while (!wb.isMethodCompiled(method)) {
                Thread.yield();
            }

            Thread.sleep(100);

            wb.fullGC();

            Thread.sleep(100);

            stop = true;
        }
    }

    static MyClass createObj() {
        return new MyClass();
    }

    static void test0(MyClass a) {
        wb.fullGC();
        Asserts.assertEquals(a.getA(), 1);
    }

    static void test1(MyClass a) {
        a.b = 3;
        wb.fullGC();
        Asserts.assertEquals(a.b, 3);
        a.b = 4;
        Asserts.assertEquals(a.b, 4);
    }

    static void test2() {
        sa = 12;
        Asserts.assertEquals(sa, 12);
        sa= 13;
        wb.fullGC();
        Asserts.assertEquals(sa, 13);
    }

    static void test3(MyClass a) {
        while (!stop) {
            Asserts.assertEquals(a.getA(), 1);
        }
    }
}

class MyClass {
    int a = 1;
    int getA() {return a;}
    public int b = 2;
}
