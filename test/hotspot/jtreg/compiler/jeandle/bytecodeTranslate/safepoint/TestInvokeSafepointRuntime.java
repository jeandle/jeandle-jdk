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
 * @summary Verify nonthrowing inlined polls preserve GC, deoptimization and asynchronous exception delivery
 * @library /test/lib
 * @build jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm/native -agentlib:JeandlePollAsync -Xbatch -Xcomp -XX:-TieredCompilation
 *      -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI -Xbootclasspath/a:.
 *      -XX:+UseJeandleCompiler
 *      -Xlog:exceptions=info
 *      -XX:PerMethodTrapLimit=0
 *      -XX:JeandleLoopStripMiningIter=1000
 *      -XX:+JeandleDumpIR -XX:JeandleDumpDirectory=invoke-poll-dumps
 *      -XX:CompileCommand=inline,compiler.jeandle.bytecodeTranslate.safepoint.TestInvokeSafepointRuntime::spin
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.safepoint.TestInvokeSafepointRuntime::longLoop
 *      compiler.jeandle.bytecodeTranslate.safepoint.TestInvokeSafepointRuntime
 */

package compiler.jeandle.bytecodeTranslate.safepoint;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import jdk.test.whitebox.WhiteBox;

public class TestInvokeSafepointRuntime {
    static {
        System.loadLibrary("JeandlePollAsync");
    }
    private static final WhiteBox WB = WhiteBox.getWhiteBox();
    private static volatile long progress;
    private static volatile Object liveDuringLoop;
    private static volatile Throwable failure;
    private static volatile boolean stop;
    private static volatile boolean finallyCompleted;

    private static native int stopThread(Thread thread, Throwable exception);

    public static void longLoop(Object live) {
        try {
            spin(live);
        } catch (Throwable caught) {
            failure = caught;
        } finally {
            finallyCompleted = true;
        }
    }

    public static void spin(Object live) {
        for (long i = 0; !stop; i++) {
            progress = i;
            liveDuringLoop = live;
        }
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
        Path dumpDir = Path.of("invoke-poll-dumps");
        Files.createDirectories(dumpDir);
        Method method =
                TestInvokeSafepointRuntime.class.getDeclaredMethod(
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

        String prefix = TestInvokeSafepointRuntime.class.getName().replace('.', '_')
                + "_longLoop_";
        List<Path> dumps;
        try (var paths = Files.list(dumpDir)) {
            dumps = paths.filter(p -> p.getFileName().toString().startsWith(prefix)
                    && p.getFileName().toString().endsWith("_optimized.ll")).toList();
        }
        if (dumps.isEmpty()) {
            throw new RuntimeException("missing compiled longLoop IR");
        }
        boolean callPoll = false;
        for (Path dump : dumps) {
            for (String line : Files.readAllLines(dump)) {
                if (line.contains("invoke ") && line.contains("@safepoint_handler")) {
                    throw new RuntimeException("nonthrowing poll retained an exceptional exit");
                }
                callPoll |= line.contains("call hotspotcc token")
                        && line.contains("@safepoint_handler");
            }
        }
        if (!callPoll) {
            throw new RuntimeException("inlined loop did not retain a GC statepoint poll");
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
        if (!WB.isMethodCompiled(method)) {
            throw new RuntimeException("longLoop deoptimized before the GC probe");
        }
        long beforeGC = progress;
        System.gc();
        waitForProgressPast(beforeGC);
        if (!WB.isMethodCompiled(method)) {
            throw new RuntimeException("longLoop deoptimized during the GC probe");
        }
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
        if (WB.isMethodCompiled(method)) {
            throw new RuntimeException("longLoop remained compiled after explicit deoptimization");
        }
        stop = true;
        worker.join(TimeUnit.SECONDS.toMillis(30));
        if (worker.isAlive() || failure != null || !finallyCompleted) {
            throw new RuntimeException("caught loop failed to terminate", failure);
        }

        // A pure compiled loop must defer the asynchronous exception through
        // deoptimization and still execute its Java catch and finally blocks.
        if (!WB.enqueueMethodForCompilation(method, 4) || !WB.isMethodCompiled(method)) {
            throw new RuntimeException("failed to recompile longLoop for the async probe");
        }
        stop = false;
        progress = -1;
        failure = null;
        finallyCompleted = false;
        Throwable expected = new AssertionError("poll asynchronous exception");
        Thread asyncWorker = new Thread(() -> longLoop(live));
        asyncWorker.setDaemon(true);
        asyncWorker.start();
        waitForProgressPast(100_000);
        if (!WB.isMethodCompiled(method)) {
            throw new RuntimeException("longLoop was not compiled at the async probe");
        }
        int error = stopThread(asyncWorker, expected);
        if (error != 0) {
            throw new RuntimeException("JVMTI StopThread failed: " + error);
        }
        asyncWorker.join(TimeUnit.SECONDS.toMillis(30));
        if (asyncWorker.isAlive() || failure != expected || !finallyCompleted) {
            stop = true;
            throw new RuntimeException("asynchronous exception was not caught with finally", failure);
        }
        if (liveDuringLoop != live) {
            throw new RuntimeException("live object lost during asynchronous deoptimization");
        }
    }
}
