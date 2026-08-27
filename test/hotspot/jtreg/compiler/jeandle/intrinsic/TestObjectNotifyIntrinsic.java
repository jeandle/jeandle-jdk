/*
 * Copyright (c) 2026, the Jeandle-JDK Authors. All Rights Reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 */

/*
 * @test
 * @summary Test Jeandle lowering of Object.notify and notifyAll
 * @library /test/lib /
 * @build compiler.jeandle.fileCheck.FileCheck
 * @run main/othervm compiler.jeandle.intrinsic.TestObjectNotifyIntrinsic
 */

package compiler.jeandle.intrinsic;

import compiler.jeandle.fileCheck.FileCheck;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestObjectNotifyIntrinsic {
    private static final long WAIT_TIMEOUT_MS = 10_000;
    private static final String NOTIFY_LOG =
            "Method `virtual void java.lang.Object.notify()` is parsed as intrinsic";
    private static final String NOTIFY_ALL_LOG =
            "Method `virtual void java.lang.Object.notifyAll()` is parsed as intrinsic";

    public static void main(String[] args) throws Exception {
        Path enabledDump = Files.createTempDirectory("jeandle_object_notify_enabled");
        OutputAnalyzer enabled = runChild(enabledDump);
        enabled.shouldHaveExitValue(0).shouldContain("TEST PASSED")
                .shouldContain(NOTIFY_LOG).shouldContain(NOTIFY_ALL_LOG);

        FileCheck notifyCheck = new FileCheck(enabledDump.toString(),
                TestMethods.class.getDeclaredMethod("notifyOne", Object.class), false);
        notifyCheck.checkPattern("invoke.*@monitor_notify\\(");
        FileCheck notifyAllCheck = new FileCheck(enabledDump.toString(),
                TestMethods.class.getDeclaredMethod("notifyAllWaiters", Object.class), false);
        notifyAllCheck.checkPattern("invoke.*@monitor_notify_all\\(");

        Path controlDisabledDump = Files.createTempDirectory(
                "jeandle_object_notify_control_disabled");
        OutputAnalyzer controlDisabled = runChild(controlDisabledDump,
                "-XX:+UnlockDiagnosticVMOptions",
                "-XX:ControlIntrinsic=-_notify,-_notifyAll");
        checkFallback(controlDisabledDump, controlDisabled);

        Path inlineNativesDisabledDump = Files.createTempDirectory(
                "jeandle_object_notify_inline_natives_disabled");
        OutputAnalyzer inlineNativesDisabled = runChild(inlineNativesDisabledDump,
                "-XX:+UnlockDiagnosticVMOptions", "-XX:-InlineNatives");
        checkFallback(inlineNativesDisabledDump, inlineNativesDisabled);
    }

    private static void checkFallback(Path dumpPath, OutputAnalyzer output) throws Exception {
        output.shouldHaveExitValue(0).shouldContain("TEST PASSED")
                .shouldNotContain(NOTIFY_LOG).shouldNotContain(NOTIFY_ALL_LOG);

        FileCheck disabledNotify = new FileCheck(dumpPath.toString(),
                TestMethods.class.getDeclaredMethod("notifyOne", Object.class), false);
        disabledNotify.checkNotPattern("monitor_notify");
        FileCheck disabledNotifyAll = new FileCheck(dumpPath.toString(),
                TestMethods.class.getDeclaredMethod("notifyAllWaiters", Object.class), false);
        disabledNotifyAll.checkNotPattern("monitor_notify");
    }

    private static OutputAnalyzer runChild(Path dumpPath, String... extraOptions) throws Exception {
        ArrayList<String> command = new ArrayList<>(List.of(
                "-Xbatch", "-Xcomp", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler",
                "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR", "-XX:+JeandleDumpObjects",
                "-XX:JeandleDumpDirectory=" + dumpPath,
                "-XX:CompileCommand=compileonly," + TestMethods.class.getName() + "::notifyOne",
                "-XX:CompileCommand=compileonly," + TestMethods.class.getName()
                        + "::notifyAllWaiters",
                "-XX:CompileCommand=compileonly," + TestMethods.class.getName()
                        + "::notifyNoWaiters",
                "-XX:CompileCommand=compileonly," + TestMethods.class.getName()
                        + "::notifyWithoutOwnership",
                "-XX:CompileCommand=compileonly," + TestMethods.class.getName()
                        + "::notifyAllWithoutOwnership",
                "-XX:CompileCommand=compileonly," + TestMethods.class.getName() + "::notifyNull",
                "-XX:CompileCommand=compileonly," + TestMethods.class.getName()
                        + "::notifyAllNull"));
        command.addAll(List.of(extraOptions));
        command.add(TestMethods.class.getName());
        return ProcessTools.executeProcess(
                ProcessTools.createLimitedTestJavaProcessBuilder(command));
    }

    static class TestMethods {
        private static final class Waiter extends Thread {
            private final Object lock;
            private final CountDownLatch ready;
            volatile boolean awakened;
            volatile boolean cancel;
            volatile Throwable failure;

            Waiter(Object lock, CountDownLatch ready) {
                this.lock = lock;
                this.ready = ready;
                setDaemon(true);
            }

            @Override
            public void run() {
                try {
                    synchronized (lock) {
                        ready.countDown();
                        lock.wait();
                        awakened = true;
                    }
                } catch (InterruptedException e) {
                    if (!cancel) {
                        failure = e;
                    }
                } catch (Throwable t) {
                    failure = t;
                }
            }
        }

        static void notifyOne(Object lock) {
            synchronized (lock) {
                lock.notify();
            }
        }

        static void notifyAllWaiters(Object lock) {
            synchronized (lock) {
                lock.notifyAll();
            }
        }

        static void notifyNoWaiters(Object lock) {
            synchronized (lock) {
                lock.notify();
                lock.notifyAll();
            }
        }

        static void notifyWithoutOwnership(Object lock) {
            lock.notify();
        }

        static void notifyAllWithoutOwnership(Object lock) {
            lock.notifyAll();
        }

        static void notifyNull() {
            Object lock = null;
            lock.notify();
        }

        static void notifyAllNull() {
            Object lock = null;
            lock.notifyAll();
        }

        private static void testNoWaiters() {
            Object lock = new Object();
            for (int i = 0; i < 10_000; i++) {
                notifyNoWaiters(lock);
            }
        }

        private static void awaitReady(CountDownLatch ready) throws Exception {
            Asserts.assertTrue(ready.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS),
                    "waiter did not start");
        }

        private static void awaitWaiting(Waiter... waiters) throws Exception {
            long deadline = System.nanoTime()
                    + TimeUnit.MILLISECONDS.toNanos(WAIT_TIMEOUT_MS);
            for (Waiter waiter : waiters) {
                while (waiter.getState() != Thread.State.WAITING) {
                    if (System.nanoTime() >= deadline) {
                        throw new AssertionError("waiter did not reach wait()");
                    }
                    Thread.onSpinWait();
                }
            }
        }

        private static void awaitAwakened(Waiter... waiters) throws Exception {
            long deadline = System.nanoTime()
                    + TimeUnit.MILLISECONDS.toNanos(WAIT_TIMEOUT_MS);
            while (System.nanoTime() < deadline) {
                for (Waiter waiter : waiters) {
                    if (waiter.awakened) {
                        return;
                    }
                }
                Thread.onSpinWait();
            }
            throw new AssertionError("no waiter was notified");
        }

        private static void cleanup(Waiter... waiters) throws Exception {
            for (Waiter waiter : waiters) {
                if (waiter.isAlive()) {
                    waiter.cancel = true;
                    waiter.interrupt();
                }
            }
            for (Waiter waiter : waiters) {
                waiter.join(1_000);
                Asserts.assertFalse(waiter.isAlive(), "waiter did not terminate");
                if (waiter.failure != null) {
                    throw new AssertionError("waiter failed", waiter.failure);
                }
            }
        }

        private static void testNotify() throws Exception {
            Object lock = new Object();
            CountDownLatch ready = new CountDownLatch(2);
            Waiter first = new Waiter(lock, ready);
            Waiter second = new Waiter(lock, ready);
            first.start();
            second.start();
            awaitReady(ready);
            awaitWaiting(first, second);
            try {
                notifyOne(lock);
                awaitAwakened(first, second);
                Asserts.assertTrue(first.awakened ^ second.awakened,
                        "notify must wake exactly one waiter");
            } finally {
                cleanup(first, second);
            }
        }

        private static void testNotifyAll() throws Exception {
            Object lock = new Object();
            CountDownLatch ready = new CountDownLatch(2);
            Waiter first = new Waiter(lock, ready);
            Waiter second = new Waiter(lock, ready);
            first.start();
            second.start();
            awaitReady(ready);
            awaitWaiting(first, second);
            try {
                notifyAllWaiters(lock);
                awaitAwakened(first, second);
                long deadline = System.nanoTime()
                        + TimeUnit.MILLISECONDS.toNanos(WAIT_TIMEOUT_MS);
                while ((!first.awakened || !second.awakened)
                        && System.nanoTime() < deadline) {
                    Thread.onSpinWait();
                }
                Asserts.assertTrue(first.awakened && second.awakened,
                        "notifyAll must wake all waiters");
            } finally {
                cleanup(first, second);
            }
        }

        private static void testExceptions() {
            try {
                notifyWithoutOwnership(new Object());
                throw new AssertionError("missing IllegalMonitorStateException");
            } catch (IllegalMonitorStateException expected) {
            }
            try {
                notifyAllWithoutOwnership(new Object());
                throw new AssertionError("missing IllegalMonitorStateException for notifyAll");
            } catch (IllegalMonitorStateException expected) {
            }
            try {
                notifyNull();
                throw new AssertionError("missing NullPointerException");
            } catch (NullPointerException expected) {
            }
            try {
                notifyAllNull();
                throw new AssertionError("missing NullPointerException for notifyAll");
            } catch (NullPointerException expected) {
            }
        }

        public static void main(String[] args) throws Exception {
            testNoWaiters();
            testNotify();
            testNotifyAll();
            testExceptions();
            System.out.println("TEST PASSED");
        }
    }
}
