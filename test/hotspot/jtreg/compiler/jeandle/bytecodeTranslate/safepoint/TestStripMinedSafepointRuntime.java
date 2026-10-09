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

/*
 * @test
 * @summary Verify a strip-mined counted loop remains responsive to GC and deoptimization
 * @library /test/lib
 * @build jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -Xbatch -Xcomp -XX:-TieredCompilation
 *      -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI -Xbootclasspath/a:.
 *      -XX:+UseJeandleCompiler
 *      -XX:JeandleLoopStripMiningIter=1000
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.safepoint.TestStripMinedSafepointRuntime::longLoop
 *      compiler.jeandle.bytecodeTranslate.safepoint.TestStripMinedSafepointRuntime
 */

package compiler.jeandle.bytecodeTranslate.safepoint;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

import jdk.test.whitebox.WhiteBox;

public class TestStripMinedSafepointRuntime {
    private static final WhiteBox WB = WhiteBox.getWhiteBox();
    private static volatile long progress;
    private static volatile Object liveDuringLoop;
    private static volatile Throwable failure;

    public static void longLoop(Object live) {
        long sum = 0;
        for (long i = 0; i < Long.MAX_VALUE; i++) {
            sum += i;
            progress = i;
            liveDuringLoop = live;
        }
        // Keep the reduction observable without making the test wait for this
        // practically unreachable exit.
        progress = sum;
    }

    private static void waitForProgressPast(long value) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (progress <= value && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        if (progress <= value) {
            throw new RuntimeException("longLoop made no progress");
        }
    }

    public static void main(String[] args) throws Exception {
        Method method =
                TestStripMinedSafepointRuntime.class.getDeclaredMethod(
                        "longLoop", Object.class);
        if (!WB.enqueueMethodForCompilation(method, 4)) {
            throw new RuntimeException("failed to enqueue longLoop for compilation");
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!WB.isMethodCompiled(method) && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        if (!WB.isMethodCompiled(method)) {
            throw new RuntimeException("longLoop was not compiled");
        }

        Object live = new byte[1024 * 1024];
        progress = -1;
        Thread worker = new Thread(() -> {
            try {
                longLoop(live);
            } catch (Throwable t) {
                failure = t;
            }
        });
        worker.setDaemon(true);
        worker.start();

        waitForProgressPast(100_000);
        long beforeGC = progress;
        System.gc();
        waitForProgressPast(beforeGC);
        if (liveDuringLoop != live) {
            throw new RuntimeException("live object was not preserved across GC");
        }

        long beforeDeopt = progress;
        WB.deoptimizeMethod(method);
        waitForProgressPast(beforeDeopt);
        if (failure != null) {
            throw new RuntimeException("longLoop failed", failure);
        }
        if (!worker.isAlive()) {
            throw new RuntimeException("longLoop terminated unexpectedly");
        }
        if (liveDuringLoop != live) {
            throw new RuntimeException(
                    "live object was not preserved across deoptimization");
        }
    }
}
