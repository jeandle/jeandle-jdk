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
 * @library /test/lib /
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -Xcomp -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI -XX:-TieredCompilation
 *                   -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.TestMonitor::addCounter
 *                   -XX:+UseJeandleCompiler compiler.jeandle.bytecodeTranslate.TestMonitor
 */

package compiler.jeandle.bytecodeTranslate;

import java.lang.reflect.Method;

import compiler.jeandle.bytecodeTranslate.TestBuildCFG;
import jdk.test.lib.Asserts;
import jdk.test.whitebox.WhiteBox;

public class TestMonitor {
    private static WhiteBox wb = WhiteBox.getWhiteBox();
    private static int counter = 0;
    private static final Object lock = new Object();

    public static void main(String[] args) throws Exception {
        testSynchronized();
    }

    static void addCounter() {
        for (int j = 0; j < 1000000; j++) {
            synchronized (lock) {
                counter++;
            }
        }
    }

    static void testSynchronized() throws Exception {
        Method method = TestMonitor.class.getDeclaredMethod("addCounter");
        if (!wb.enqueueMethodForCompilation(method, 4)) {
            throw new RuntimeException("Enqueue compilation of addCounter failed");
        }
        while (!wb.isMethodCompiled(method)) {
            Thread.yield();
        }

        int threadCount = 3;
        Thread[] threads = new Thread[threadCount];

        for (int i = 0; i < threadCount; i++) {
            threads[i] = new Thread(() -> {
                addCounter();
            });
        }

        for (Thread thread : threads) {
            thread.start();
        }

        for (Thread thread : threads) {
            thread.join();
        }

        Asserts.assertEquals(counter, 3000000, "counter is not 300000");
    }
}
