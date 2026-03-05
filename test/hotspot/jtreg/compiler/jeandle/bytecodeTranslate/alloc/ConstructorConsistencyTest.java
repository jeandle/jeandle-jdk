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

import jdk.test.lib.Asserts;

/**
 * @test Constructor consistency under multithreading
 * @summary Test object construction consistency when multiple threads access partially constructed objects
 * @library /test/lib
 * @run main/othervm -Xcomp -XX:-TieredCompilation -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.alloc.ConstructorConsistencyTest::main
 *      -XX:+UseJeandleCompiler compiler.jeandle.bytecodeTranslate.alloc.ConstructorConsistencyTest
 */

public class ConstructorConsistencyTest {
    private static final int THREAD_COUNT = Runtime.getRuntime().availableProcessors();
    private static final long ITERATIONS = 10000;

    static class ComplexObject {
        final int sum;
        final int a, b;

        public ComplexObject(int a, int b) {
            this.a = a;
            // Simulate some computation time-consuming to increase competition window
            this.b = b;
            this.sum = this.a + this.b;
        }

        public boolean isValid() {
            return sum == (a + b);
        }
    }

    private static ComplexObject sharedRef;

    public static void main(String[] args) {
        // Write thread: high-speed creation
        new Thread(() -> {
            int i = 0;
            for (long j = 0; j < ITERATIONS; j++) {
                sharedRef = new ComplexObject(i, i + 1);
                i++;
            }
        }).start();

        // Read thread: verify consistency
        for (int t = 0; t < THREAD_COUNT; t++) {
            new Thread(() -> {
                for (long j = 0; j < ITERATIONS; j++) {
                    ComplexObject local = sharedRef;
                    if (local != null) {
                        if (!local.isValid()) {
                            Asserts.assertEquals(local.sum, local.a + local.b, "Invalid State Detected");
                        }
                    }
                }
            }).start();
        }
    }
}
