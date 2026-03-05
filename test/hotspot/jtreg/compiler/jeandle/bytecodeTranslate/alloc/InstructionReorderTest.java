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

package compiler.jeandle.bytecodeTranslate.alloc;

import java.util.concurrent.atomic.AtomicInteger;

import jdk.test.lib.Asserts;

/**
 * @test Instruction reordering prevention
 * @summary Verify that instruction reordering does not occur during object initialization
 * @library /test/lib
 * @run main/othervm -Xcomp -XX:-TieredCompilation -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.alloc.InstructionReorderTest::main
 *      -XX:+UseJeandleCompiler compiler.jeandle.bytecodeTranslate.alloc.InstructionReorderTest
 */

public class InstructionReorderTest {
    private static final int THREAD_COUNT = Runtime.getRuntime().availableProcessors();

    static class ReorderObject {
        int x;
        int y;

        public ReorderObject() {
            this.x = 100;
            this.y = 200;
        }
    }

    static volatile ReorderObject obj = null;
    static volatile boolean running = true;

    // Detector: If x != 100 or y != 200, it indicates instruction reordering or visibility issues
    static AtomicInteger failX = new AtomicInteger(0);
    static AtomicInteger failY = new AtomicInteger(0);

    public static void main(String[] args) {
        // Start 4 writer threads and 4 reader threads
        Thread[] threads = new Thread[THREAD_COUNT];

        for (int i = 0; i < THREAD_COUNT; i++) {
            if (i % 2 == 0) {
                // Writer thread
                threads[i] = new Thread(() -> {
                    while (running) {
                        obj = new ReorderObject();
                    }
                });
            } else {
                // Reader thread
                threads[i] = new Thread(() -> {
                    while (running) {
                        ReorderObject o = obj;
                        if (o != null) {
                            // Key verification point:
                            // In the standard Java memory model, reading threads must see the state after constructor execution is complete
                            // That is, x=100, y=200.
                            // If seeing x=100, y=0 (default value), inappropriate reordering has occurred.
                            if (o.x != 100) failX.incrementAndGet();
                            if (o.y != 200) failY.incrementAndGet();
                        }
                    }
                });
            }
        }

        for (Thread t : threads) t.start();

        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        running = false;

        System.out.println("Test completed:");
        Asserts.assertEquals(failX.get(), 0, "X field validation failures");
        Asserts.assertEquals(failY.get(), 0, "Y field validation failures");
    }
}
