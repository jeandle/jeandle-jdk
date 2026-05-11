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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import jdk.test.lib.Asserts;

/**
 * @test
 * @summary Verify the release fence after object initialization prevents readers from
 *          observing partially constructed objects (uninitialized fields)
 * @library /test/lib
 * @run main/othervm -Xcomp -XX:-TieredCompilation
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.alloc.TestAllocInitBarrier::*
 *      -XX:+UseJeandleCompiler compiler.jeandle.bytecodeTranslate.alloc.TestAllocInitBarrier
 */
public class TestAllocInitBarrier {
    private static final int READER_COUNT = Math.min(Runtime.getRuntime().availableProcessors(), 4);
    private static final long DURATION_MS = 3000;

    static class InitObject {
        int x;
        int y;
        long z;

        InitObject() {
            this.x = 42;
            this.y = 84;
            this.z = 126L;
        }

        boolean isConsistent() {
            return x == 42 && y == 84 && z == 126L;
        }
    }

    static volatile InitObject shared = null;
    static volatile boolean running = true;
    static AtomicInteger inconsistencies = new AtomicInteger(0);

    public static void main(String[] args) throws InterruptedException {
        CountDownLatch doneLatch = new CountDownLatch(READER_COUNT + 1);

        // Writer thread: continuously creates and publishes new objects
        new Thread(() -> {
            while (running) {
                shared = new InitObject();
            }
            doneLatch.countDown();
        }).start();

        // Reader threads: read the shared reference and verify consistency
        for (int i = 0; i < READER_COUNT; i++) {
            new Thread(() -> {
                while (running) {
                    InitObject local = shared;
                    if (local != null && !local.isConsistent()) {
                        inconsistencies.incrementAndGet();
                    }
                }
                doneLatch.countDown();
            }).start();
        }

        Thread.sleep(DURATION_MS);
        running = false;
        doneLatch.await();

        Asserts.assertEquals(inconsistencies.get(), 0,
            "Detected " + inconsistencies.get() + " partially constructed objects - release fence may be broken");
        System.out.println("TestAllocInitBarrier passed.");
    }
}
