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
 *                   -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.TestMonitor::SyncAddCounter
 *                   -XX:+UnlockExperimentalVMOptions -XX:LockingMode=0
 *                   -XX:+UseJeandleCompiler compiler.jeandle.bytecodeTranslate.TestMonitor
 * @run main/othervm -Xcomp -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI -XX:-TieredCompilation
 *                   -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.TestMonitor::addCounter
 *                   -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.TestMonitor::SyncAddCounter
 *                   -XX:+UnlockExperimentalVMOptions -XX:LockingMode=1
 *                   -XX:+UseJeandleCompiler compiler.jeandle.bytecodeTranslate.TestMonitor
 * @run main/othervm -Xcomp -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI -XX:-TieredCompilation
 *                   -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.TestMonitor::addCounter
 *                   -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.TestMonitor::SyncAddCounter
 *                   -XX:+UnlockExperimentalVMOptions -XX:LockingMode=2
 *                   -XX:+UseJeandleCompiler compiler.jeandle.bytecodeTranslate.TestMonitor
 */

package compiler.jeandle.bytecodeTranslate;

import java.lang.reflect.Method;

import jdk.test.lib.Asserts;
import jdk.test.whitebox.WhiteBox;

public class TestMonitor {
    private static WhiteBox wb = WhiteBox.getWhiteBox();
    private static final Object lock = new Object();
    private static int counter = 0;
    private static final int loopCount = 1000000;
    private static final int threadCount = 20;

    public static void main(String[] args) throws Exception {
        testSynchronized();
    }

    static void addCounter() {
        for (int j = 0; j < loopCount; j++) {
            synchronized (lock) {
                counter++;
            }
        }
    }

    synchronized static void SyncAddCounter() {
                counter++;
    }

    static void testSynchronized() throws Exception {
        Method method = TestMonitor.class.getDeclaredMethod("addCounter");
        if (!wb.enqueueMethodForCompilation(method, 4)) {
            throw new RuntimeException("Enqueue compilation of addCounter failed");
        }
        while (!wb.isMethodCompiled(method)) {
            Thread.yield();
        }

        Thread[] threadsA = new Thread[threadCount];

        for (int i = 0; i < threadCount; i++) {
            threadsA[i] = new Thread(() -> {
                addCounter();
            });
        }

        for (Thread thread : threadsA) {
            thread.start();
        }

        for (Thread thread : threadsA) {
            thread.join();
        }

        Asserts.assertEquals(counter, threadCount * loopCount, "counter is not " + (threadCount * loopCount));

        // reset counter
        counter = 0;

        Thread[] threadsB = new Thread[threadCount];

        for (int i = 0; i < threadCount; i++) {
            threadsB[i] = new Thread(() -> {
                for (int j = 0; j < loopCount; j++) {
                    SyncAddCounter();
                }
            });
        }

        for (Thread thread : threadsB) {
            thread.start();
        }

        for (Thread thread : threadsB) {
            thread.join();
        }

        Asserts.assertEquals(counter, threadCount * loopCount, "counter is not " + (threadCount * loopCount));
    }
}
