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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

import jdk.test.lib.Asserts;

/**
 * @test
 * @summary Multi-threaded stress test for TLAB fast path allocation correctness.
 *          Allocator threads produce objects, verifier threads consume and check field values.
 * @library /test/lib
 * @run main/othervm -Xcomp -XX:-TieredCompilation
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.alloc.TestAllocStress::*
 *      -XX:+UseJeandleCompiler compiler.jeandle.bytecodeTranslate.alloc.TestAllocStress
 * @run main/othervm -Xcomp -XX:-TieredCompilation -Xmx32m -Xmn16m
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.alloc.TestAllocStress::*
 *      -XX:+UseJeandleCompiler compiler.jeandle.bytecodeTranslate.alloc.TestAllocStress
 */
public class TestAllocStress {
    private static final int TOTAL_THREADS = Math.min(Runtime.getRuntime().availableProcessors(), 8);
    private static final int ALLOCATOR_COUNT = Math.max(TOTAL_THREADS / 2, 1);
    private static final int VERIFIER_COUNT = Math.max(TOTAL_THREADS - ALLOCATOR_COUNT, 1);
    private static final int ITERATIONS_PER_ALLOCATOR = 50_000;
    private static final int QUEUE_CAPACITY = 4096;

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

    // Wrapper to carry expected value alongside the allocated object
    static class AllocResult {
        static final AllocResult POISON = new AllocResult(null, 0);
        final Object obj;
        final int expected;
        AllocResult(Object obj, int expected) {
            this.obj = obj;
            this.expected = expected;
        }
    }

    static final AtomicLong totalErrors = new AtomicLong(0);
    static final ArrayBlockingQueue<AllocResult> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);

    public static void main(String[] args) throws InterruptedException {
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch allocDone = new CountDownLatch(ALLOCATOR_COUNT);
        CountDownLatch verifyDone = new CountDownLatch(VERIFIER_COUNT);

        // Start allocator threads
        for (int t = 0; t < ALLOCATOR_COUNT; t++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    allocate();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    allocDone.countDown();
                }
            }, "allocator-" + t).start();
        }

        // Start verifier threads
        for (int t = 0; t < VERIFIER_COUNT; t++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    long errors = verify();
                    totalErrors.addAndGet(errors);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    verifyDone.countDown();
                }
            }, "verifier-" + t).start();
        }

        startLatch.countDown(); // All threads start simultaneously

        // Wait for allocators to finish, then send poison pills to verifiers
        allocDone.await();
        for (int t = 0; t < VERIFIER_COUNT; t++) {
            queue.put(AllocResult.POISON);
        }
        verifyDone.await();

        Asserts.assertEquals(totalErrors.get(), 0L,
            "Found " + totalErrors.get() + " allocation errors");
        System.out.println("TestAllocStress passed: " + ALLOCATOR_COUNT + " allocators, "
            + VERIFIER_COUNT + " verifiers, "
            + ITERATIONS_PER_ALLOCATOR + " iterations per allocator.");
    }

    static void allocate() throws InterruptedException {
        for (int i = 0; i < ITERATIONS_PER_ALLOCATOR; i++) {
            // Alternate between small and medium objects to vary TLAB consumption
            if (i % 2 == 0) {
                queue.put(new AllocResult(new SmallObj(i), i));
            } else {
                queue.put(new AllocResult(new MediumObj(i), i));
            }
        }
    }

    static long verify() throws InterruptedException {
        long errors = 0;
        while (true) {
            AllocResult r = queue.take();
            if (r == AllocResult.POISON) break;
            int expected = r.expected;
            if (r.obj instanceof SmallObj) {
                SmallObj obj = (SmallObj) r.obj;
                if (obj.val != expected) errors++;
            } else {
                MediumObj obj = (MediumObj) r.obj;
                if (obj.a != expected) errors++;
                if (obj.b != expected + 1) errors++;
                if (obj.c != expected + 2) errors++;
                if (obj.d != expected + 3) errors++;
                if (obj.x != expected) errors++;
            }
        }
        return errors;
    }
}
