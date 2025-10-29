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
 * @summary https://github.com/jeandle/jeandle-jdk/issues/173
 * @library /test/lib /
 * @build jdk.test.lib.Asserts
 * @run main/othervm -Xbatch -Xcomp -XX:-TieredCompilation -XX:+UseJeandleCompiler -XX:CompileCommand=compileonly,TestIssue173::addCounter TestIssue173
 */

import jdk.test.lib.Asserts;

public class TestIssue173 {
    private static int counter = 0;
    private static final Object lock = new Object();

    public static void main(String[] args) throws Exception {
        testSynchronized();
    }

    static void addOne() {
        synchronized (lock) {
            counter++;
        }
    }

    static void addCounter() {
        for (int j = 0; j < 100000; j++) {
            addOne();
        }
    }

    static void testSynchronized() throws Exception {
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

        Asserts.assertEquals(counter, 300000);
    }
}
