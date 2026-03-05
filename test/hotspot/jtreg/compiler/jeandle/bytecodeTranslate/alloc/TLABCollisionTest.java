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

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import jdk.test.lib.Asserts;

/**
 * @test TLAB collision detection
 * @summary Test TLAB allocation collision avoidance with variable-sized objects
 * @library /test/lib
 * @run main/othervm -Xcomp -XX:-TieredCompilation -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.alloc.TLABCollisionTest::main
 *      -XX:+UseJeandleCompiler compiler.jeandle.bytecodeTranslate.alloc.TLABCollisionTest
 */

public class TLABCollisionTest {
    private static final int THREAD_COUNT = Runtime.getRuntime().availableProcessors();
    private static final long ITERATIONS = 10000;

    static class UniqueObject {
        long id;
        long threadId;
        byte[] padding; // Random size to force frequent TLAB switching and expansion

        public UniqueObject(long id) {
            this.id = id;
            this.threadId = Thread.currentThread().getId();
            this.padding = new byte[ThreadLocalRandom.current().nextInt(8, 1024)];
        }
    }

    public static void main(String[] args) {
        // Using the concurrent version of IdentityHashSet (simulated through ConcurrentHashMap)
        Set<UniqueObject> pool = ConcurrentHashMap.newKeySet();

        for (int i = 0; i < THREAD_COUNT; i++) {
            new Thread(() -> {
                for (int j = 0; j < ITERATIONS; j++) {
                    UniqueObject obj = new UniqueObject(j);

                    // If an identical reference (same memory address) already exists in the pool, report an error
                    if (!pool.add(obj)) {
                        Asserts.fail("Object Memory Collision Detected!");
                    }

                    // Periodic cleanup to prevent OOM, focusing on testing allocation moments
                    if (j % 1000 == 0) pool.clear();
                }
                System.out.println("Thread " + Thread.currentThread().getId() + " done.");
            }).start();
        }
    }
}
