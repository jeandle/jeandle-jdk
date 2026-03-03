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

/**
 * @test
 * @library /test/lib /
 * @run main/othervm -Xcomp -XX:-TieredCompilation -Xbatch -XX:-Inline -XX:-EliminateLocks
 *                   -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.TestMonitorInflation::handleA
 *                   -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.TestMonitorInflation::clearStack
 *                   -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.TestMonitorInflation::reenterTheLock
 *                   -XX:-UseJeandleCompiler compiler.jeandle.bytecodeTranslate.TestMonitorInflation
 */

package compiler.jeandle.bytecodeTranslate;

public class TestMonitorInflation {
    private static volatile boolean lockedByThreadA = false;
    private static Object capture;
    private static final Object lock = new Object();

    public static void blackHole(long a, long b) {
    }

    public static long clearStack(long a, long b) {
        blackHole(a, b);
        return a + b;
    }

    public static void main(String[] args) throws Exception {
        Class.forName("java.lang.Thread");

        // Resolve the callsite
        handleA();

        Thread threadB = new Thread(() -> {
            handleB();
        });
        threadB.start();

        Thread threadA = new Thread(() -> {
            handleA();
        });
        threadA.start();

        threadB.join();
        threadA.join();
    }

    public static void holdTheLock(long millis) {
        synchronized (lock) {
            lockedByThreadA = true;

            try {
                Thread.sleep(500);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        lockedByThreadA = false;
    }

    public static void reenterTheLock() {
        synchronized (lock) {
            capture = null;

            synchronized (lock) {
                capture = lock;
            }
        }
    }

    private static void handleA() {
        // Thread A hold the lock
        holdTheLock(500);

        synchronized (lock) {
            lockedByThreadA = true;

            clearStack(0, 0);

            reenterTheLock();
        }

        lockedByThreadA = false;
    }

    private static void handleB() {
        // Do monitor inflation
        grabTheLock();

        try {
            Thread.sleep(500);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // grab the lock again
        synchronized (lock) {
            capture = null;
        }
    }

    private static void grabTheLock() {
        while (!lockedByThreadA) {
            try {
                Thread.sleep(100);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        synchronized (lock) {
            capture = null;
        }
    }
}
