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
 */

/*
 * @test
 * @summary Coarsened synchronized chains must remain mutually exclusive and
 *          must not deadlock under concurrent execution
 * @library /test/lib
 * @build compiler.jeandle.lock.LockCoarseningTestUtils jdk.test.lib.Asserts
 * @run driver compiler.jeandle.lock.TestLockCoarseningConcurrency
 */

package compiler.jeandle.lock;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;

public class TestLockCoarseningConcurrency {
    private static final int THREADS = 8;
    private static final int ITERATIONS = 20_000;
    private static final long JOIN_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(1);

    private static volatile int sink;
    private int counter;

    private synchronized int bump(int input) {
        counter++;
        return input + 1;
    }

    private int chain() {
        int result = 0;
        result = bump(result);
        result = bump(result);
        result = bump(result);
        result = bump(result);
        result = bump(result);
        result = bump(result);
        result = bump(result);
        return bump(result);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 0 && args[0].equals("child")) {
            runChild();
            return;
        }

        Path dumpDirectory = LockCoarseningTestUtils.createDumpDirectory();
        OutputAnalyzer output = LockCoarseningTestUtils.runChildWithDump(
                TestLockCoarseningConcurrency.class, "chain", 1,
                dumpDirectory, "bump");
        output.shouldHaveExitValue(0);
        LockCoarseningTestUtils.assertOneCoarsenedRegion(
                dumpDirectory, TestLockCoarseningConcurrency.class, "chain");
    }

    private static void runChild() throws Exception {
        TestLockCoarseningConcurrency test = new TestLockCoarseningConcurrency();
        Throwable[] failures = new Throwable[THREADS];
        Thread[] threads = new Thread[THREADS];
        for (int i = 0; i < THREADS; i++) {
            final int index = i;
            threads[i] = new Thread(() -> {
                try {
                    int result = 0;
                    for (int iteration = 0; iteration < ITERATIONS; iteration++) {
                        result = test.chain();
                    }
                    Asserts.assertEquals(result, 8,
                            "incorrect chain result in worker " + index);
                } catch (Throwable t) {
                    failures[index] = t;
                }
            }, "lock-coarsening-worker-" + i);
            threads[i].start();
        }
        for (Thread thread : threads) {
            thread.join(JOIN_TIMEOUT_MS);
            Asserts.assertFalse(thread.isAlive(),
                    thread.getName() + " did not finish; possible monitor leak");
        }
        for (Throwable failure : failures) {
            if (failure != null) {
                throw new AssertionError("worker failed", failure);
            }
        }
        Asserts.assertEquals(test.counter, THREADS * ITERATIONS * 8,
                "coarsened critical sections were not mutually exclusive");
        sink = test.counter;
    }
}
