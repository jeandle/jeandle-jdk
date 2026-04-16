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
import java.util.concurrent.atomic.AtomicLong;

import jdk.test.lib.Asserts;

/**
 * @test
 * @summary Multi-threaded stress test for TLAB fast path allocation correctness
 * @library /test/lib
 * @run main/othervm -Xcomp -XX:-TieredCompilation
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.alloc.TestAllocStress::*
 *      -XX:+UseJeandleCompiler compiler.jeandle.bytecodeTranslate.alloc.TestAllocStress
 * @run main/othervm -Xcomp -XX:-TieredCompilation -Xmx32m -Xmn16m
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.alloc.TestAllocStress::*
 *      -XX:+UseJeandleCompiler compiler.jeandle.bytecodeTranslate.alloc.TestAllocStress
 */
public class TestAllocStress {
    private static final int THREAD_COUNT = Math.min(Runtime.getRuntime().availableProcessors(), 8);
    private static final int ITERATIONS = 50_000;

    static class SmallObj {
        int val;
        SmallObj(int v) { this.val = v; }
    }

    static class MediumObj {
        long a, b, c, d;
        int x;
        MediumObj(int v) {
            this.a = v;
            this.b = v + 1;
            this.c = v + 2;
            this.d = v + 3;
            this.x = v;
        }
    }

    static AtomicLong totalErrors = new AtomicLong(0);

    public static void main(String[] args) throws InterruptedException {
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(THREAD_COUNT);

        for (int t = 0; t < THREAD_COUNT; t++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                } catch (InterruptedException e) {
                    return;
                }
                long errors = allocateAndVerify();
                totalErrors.addAndGet(errors);
                doneLatch.countDown();
            }).start();
        }

        startLatch.countDown(); // All threads start simultaneously
        doneLatch.await();

        Asserts.assertEquals(totalErrors.get(), 0L,
            "Found " + totalErrors.get() + " allocation errors across " + THREAD_COUNT + " threads");
        System.out.println("TestAllocStress passed with " + THREAD_COUNT + " threads, "
            + ITERATIONS + " iterations each.");
    }

    static long allocateAndVerify() {
        long errors = 0;
        for (int i = 0; i < ITERATIONS; i++) {
            // Alternate between small and medium objects to vary TLAB consumption
            if (i % 2 == 0) {
                SmallObj obj = new SmallObj(i);
                if (obj.val != i) errors++;
            } else {
                MediumObj obj = new MediumObj(i);
                if (obj.a != i) errors++;
                if (obj.b != i + 1) errors++;
                if (obj.c != i + 2) errors++;
                if (obj.d != i + 3) errors++;
                if (obj.x != i) errors++;
            }
        }
        return errors;
    }
}
