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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * @test High concurrency object allocation stress test
 * @summary Stress test object allocation under high concurrency scenarios
 * @run main/othervm -Xcomp -XX:-TieredCompilation -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.alloc.NewObjectStressTest::main
 *      -XX:+UseJeandleCompiler compiler.jeandle.bytecodeTranslate.alloc.NewObjectStressTest
 */

public class NewObjectStressTest {
    private static final int THREAD_COUNT = Runtime.getRuntime().availableProcessors();
    private static final long ITERATIONS = 100000;

    public static void main(String[] args) throws InterruptedException {
        System.out.println("Starting TLAB Stress Test with " + THREAD_COUNT + " threads...");
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < THREAD_COUNT; i++) {
            executor.execute(() -> {
                for (long j = 0; j < ITERATIONS; j++) {
                    // Create small objects that quickly become garbage
                    Object obj = new Object();
                }
                latch.countDown();
            });
        }

        latch.await();
        long endTime = System.currentTimeMillis();
        System.out.println("Finished. Time: " + (endTime - startTime) + "ms");
        executor.shutdown();
    }
}
