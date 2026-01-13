/*
 * Copyright (c) 2026, the Jeandle-JDK Authors. All Rights Reserved.
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
 * @test test synchronized method compilation for jeandle compiler
 * @library /test/lib /
 * @build jdk.test.lib.Asserts
 * @run main/othervm -Xcomp -Xbatch -XX:-TieredCompilation
 *                   -XX:CompileCommand=compileonly,compiler.jeandle.TestSynchronizedMethod::incI
 *                   -XX:CompileCommand=compileonly,compiler.jeandle.TestSynchronizedMethod::incS
 *                   -XX:CompileCommand=compileonly,compiler.jeandle.TestSynchronizedMethod::incEx
 *                   -XX:+UseJeandleCompiler compiler.jeandle.TestSynchronizedMethod
 */

package compiler.jeandle;

import jdk.test.lib.Asserts;

public class TestSynchronizedMethod {

    private static int s = 0;
    private int i = 0;

    public synchronized void incI() { ++i; }
    public static synchronized void incS() { ++s; }

    public static void preInit() {
        try {
            throw new RuntimeException("preInit");
        } catch (Exception e) {
            // do nothing
        }
    }

    public static synchronized int incEx(int i) {
        if (i < 0) {
            throw new RuntimeException("unexpected input");
        }

        return  i + 1;
    }

    public static void main(String[] args) throws Exception {
        TestSynchronizedMethod o = new TestSynchronizedMethod();

        int t = 10, ops = 100000;
        Thread[] threads = new Thread[t];

        for (int i = 0; i < t; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < ops; j++) {
                    o.incI();
                    incS();
                }
            });
            threads[i].start();
        }

        for (Thread th : threads) {
            th.join();
        }

        Asserts.assertEquals(s, 1000000, "s is not 1000000");
        Asserts.assertEquals(o.i, 1000000, "o.i is not 1000000");

        // Load RuntimeException to avoid unloaded class in compilation. Thus the "throw" will not be an uncommon trap and a real unlock logic will be generated for the "throw".
        preInit();
        Asserts.assertThrows(RuntimeException.class, () -> incEx(-1));

        // Invoke it again to make sure the monitor has been unlocked.
        Asserts.assertThrows(RuntimeException.class, () -> incEx(-1));
    }
}
