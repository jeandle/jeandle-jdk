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
 * @summary Test Unsafe primitive compareAndSet volatile publication semantics
 * @modules java.base/jdk.internal.misc
 * @library /test/lib /
 * @build compiler.jeandle.fileCheck.FileCheck
 * @run main/othervm compiler.jeandle.intrinsic.TestUnsafeCompareAndSetMemoryOrdering
 */

package compiler.jeandle.intrinsic;

import compiler.jeandle.fileCheck.FileCheck;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import jdk.internal.misc.Unsafe;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestUnsafeCompareAndSetMemoryOrdering {
    private static final String BYTE_ID = "_compareAndSetByte";
    private static final String SHORT_ID = "_compareAndSetShort";
    private static final String INT_ID = "_compareAndSetInt";
    private static final String LONG_ID = "_compareAndSetLong";
    private static final String BYTE_INTRINSIC_LOG =
            "Method `virtual jboolean jdk.internal.misc.Unsafe.compareAndSetByte"
                    + "(jobject, jlong, jbyte, jbyte)` is parsed as intrinsic";
    private static final String SHORT_INTRINSIC_LOG =
            "Method `virtual jboolean jdk.internal.misc.Unsafe.compareAndSetShort"
                    + "(jobject, jlong, jshort, jshort)` is parsed as intrinsic";
    private static final String INT_INTRINSIC_LOG =
            "Method `virtual jboolean jdk.internal.misc.Unsafe.compareAndSetInt"
                    + "(jobject, jlong, jint, jint)` is parsed as intrinsic";
    private static final String LONG_INTRINSIC_LOG =
            "Method `virtual jboolean jdk.internal.misc.Unsafe.compareAndSetLong"
                    + "(jobject, jlong, jlong, jlong)` is parsed as intrinsic";

    public static void main(String[] args) throws Exception {
        runCase(true);
        runCase(false);
    }

    private static void runCase(boolean enabled) throws Exception {
        String dumpPath = Files.createTempDirectory(
                enabled ? "jeandle_cas_publication_enabled" : "jeandle_cas_publication_disabled")
                .toString();
        String wrapper = TestWrapper.class.getName();
        ArrayList<String> command = new ArrayList<>(List.of(
                "--add-exports", "java.base/jdk.internal.misc=ALL-UNNAMED",
                "-Xbatch", "-Xcomp", "-XX:-TieredCompilation",
                "-XX:-BackgroundCompilation", "-XX:+UseJeandleCompiler",
                "-XX:+UnlockDiagnosticVMOptions",
                "-XX:+JeandleDumpIR", "-XX:JeandleDumpDirectory=" + dumpPath,
                "-Xlog:jeandle=debug,jit+compilation=debug",
                "-XX:+CIPrintCompilerName",
                "-XX:CompileCommand=compileonly," + wrapper + "::publishByte",
                "-XX:CompileCommand=compileonly," + wrapper + "::observeByte",
                "-XX:CompileCommand=compileonly," + wrapper + "::publishShort",
                "-XX:CompileCommand=compileonly," + wrapper + "::observeShort",
                "-XX:CompileCommand=compileonly," + wrapper + "::publishInt",
                "-XX:CompileCommand=compileonly," + wrapper + "::observeInt",
                "-XX:CompileCommand=compileonly," + wrapper + "::publishLong",
                "-XX:CompileCommand=compileonly," + wrapper + "::observeLong"));
        if (!enabled) {
            command.add("-XX:ControlIntrinsic=-" + BYTE_ID + ",-" + SHORT_ID
                    + ",-" + INT_ID + ",-" + LONG_ID);
        }
        command.add(wrapper);

        OutputAnalyzer output = ProcessTools.executeCommand(
                ProcessTools.createLimitedTestJavaProcessBuilder(command));
        output.shouldHaveExitValue(0).shouldContain("TestUnsafeCompareAndSetMemoryOrdering PASSED");

        FileCheck byteCheck = new FileCheck(dumpPath,
                TestWrapper.class.getMethod("publishByte", ByteHolder.class, int.class), false);
        FileCheck shortCheck = new FileCheck(dumpPath,
                TestWrapper.class.getMethod("publishShort", ShortHolder.class, int.class), false);
        FileCheck intCheck = new FileCheck(dumpPath,
                TestWrapper.class.getMethod("publishInt", IntHolder.class, int.class), false);
        FileCheck longCheck = new FileCheck(dumpPath,
                TestWrapper.class.getMethod("publishLong", LongHolder.class, long.class), false);
        if (enabled) {
            output.shouldContain(BYTE_INTRINSIC_LOG).shouldContain(SHORT_INTRINSIC_LOG)
                    .shouldContain(INT_INTRINSIC_LOG).shouldContain(LONG_INTRINSIC_LOG);
            checkInstalledByJeandle(output, "publishByte");
            checkInstalledByJeandle(output, "publishShort");
            checkInstalledByJeandle(output, "publishInt");
            checkInstalledByJeandle(output, "publishLong");
            byteCheck.checkPattern("cmpxchg ptr addrspace\\(1\\).*i8.*seq_cst seq_cst, align 1");
            shortCheck.checkPattern("cmpxchg ptr addrspace\\(1\\).*i16.*seq_cst seq_cst, align 2");
            intCheck.checkPattern("cmpxchg ptr addrspace\\(1\\).*i32.*seq_cst seq_cst, align 4");
            longCheck.checkPattern("cmpxchg ptr addrspace\\(1\\).*i64.*seq_cst seq_cst, align 8");
        } else {
            output.shouldNotContain(BYTE_INTRINSIC_LOG).shouldNotContain(SHORT_INTRINSIC_LOG)
                    .shouldNotContain(INT_INTRINSIC_LOG).shouldNotContain(LONG_INTRINSIC_LOG);
            byteCheck.checkPattern("invoke .*Unsafe_compareAndSetByte");
            shortCheck.checkPattern("invoke .*Unsafe_compareAndSetShort");
            intCheck.checkPattern("invoke hotspotcc i32.*Unsafe_compareAndSetInt");
            longCheck.checkPattern("invoke hotspotcc i32.*Unsafe_compareAndSetLong");
        }
    }

    private static void checkInstalledByJeandle(OutputAnalyzer output, String method) {
        output.shouldMatch("(?s).*Jeandle:.*TestUnsafeCompareAndSetMemoryOrdering\\$TestWrapper::"
                + method + ".*");
    }

    static class ByteHolder {
        byte state;
        int payload;
    }

    static class ShortHolder {
        short state;
        int payload;
    }

    static class IntHolder {
        int state;
        int payload;
    }

    static class LongHolder {
        long state;
        long payload;
    }

    public static class TestWrapper {
        private static final Unsafe U = Unsafe.getUnsafe();
        private static final int ROUNDS = 20_000;
        private static final long WAIT_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(10);
        private static final long PAIR_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(30);
        private static final long BYTE_STATE_OFFSET;
        private static final long SHORT_STATE_OFFSET;
        private static final long INT_STATE_OFFSET;
        private static final long LONG_STATE_OFFSET;

        static {
            try {
                BYTE_STATE_OFFSET = U.objectFieldOffset(ByteHolder.class.getDeclaredField("state"));
                SHORT_STATE_OFFSET = U.objectFieldOffset(ShortHolder.class.getDeclaredField("state"));
                INT_STATE_OFFSET = U.objectFieldOffset(IntHolder.class.getDeclaredField("state"));
                LONG_STATE_OFFSET = U.objectFieldOffset(LongHolder.class.getDeclaredField("state"));
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        public static boolean publishByte(ByteHolder holder, int value) {
            holder.payload = value;
            return U.compareAndSetByte(holder, BYTE_STATE_OFFSET, (byte) 0, (byte) 1);
        }

        public static int observeByte(ByteHolder holder) {
            if (!U.compareAndSetByte(holder, BYTE_STATE_OFFSET, (byte) 1, (byte) 1)) {
                return 0;
            }
            return holder.payload;
        }

        public static boolean publishShort(ShortHolder holder, int value) {
            holder.payload = value;
            return U.compareAndSetShort(holder, SHORT_STATE_OFFSET, (short) 0, (short) 1);
        }

        public static int observeShort(ShortHolder holder) {
            if (!U.compareAndSetShort(holder, SHORT_STATE_OFFSET, (short) 1, (short) 1)) {
                return 0;
            }
            return holder.payload;
        }

        public static boolean publishInt(IntHolder holder, int value) {
            holder.payload = value;
            return U.compareAndSetInt(holder, INT_STATE_OFFSET, 0, 1);
        }

        public static int observeInt(IntHolder holder) {
            if (!U.compareAndSetInt(holder, INT_STATE_OFFSET, 1, 1)) {
                return 0;
            }
            return holder.payload;
        }

        public static boolean publishLong(LongHolder holder, long value) {
            holder.payload = value;
            return U.compareAndSetLong(holder, LONG_STATE_OFFSET, 0L, 1L);
        }

        public static long observeLong(LongHolder holder) {
            if (!U.compareAndSetLong(holder, LONG_STATE_OFFSET, 1L, 1L)) {
                return 0L;
            }
            return holder.payload;
        }

        public static void main(String[] args) throws Exception {
            testBytePublication();
            testShortPublication();
            testIntPublication();
            testLongPublication();
            System.out.println("TestUnsafeCompareAndSetMemoryOrdering PASSED");
        }

        private static void testBytePublication() throws Exception {
            ByteHolder holder = new ByteHolder();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicBoolean cancelled = new AtomicBoolean();
            Thread producer = new Thread(() -> {
                try {
                    for (int value = 1; value <= ROUNDS; value++) {
                        waitForByteState(holder, (byte) 0, cancelled);
                        check(publishByte(holder, value), "byte publication CAS failed");
                        waitForByteState(holder, (byte) 2, cancelled);
                        U.putByteVolatile(holder, BYTE_STATE_OFFSET, (byte) 0);
                    }
                } catch (Throwable t) {
                    recordFailure(failure, cancelled, t);
                }
            }, "byte-cas-publisher");
            Thread consumer = new Thread(() -> {
                try {
                    for (int expected = 1; expected <= ROUNDS; expected++) {
                        int observed = waitForBytePublication(holder, cancelled);
                        check(observed == expected, "byte payload publication mismatch");
                        check(U.compareAndSetByte(holder, BYTE_STATE_OFFSET, (byte) 1, (byte) 2),
                                "byte acknowledgement CAS failed");
                    }
                } catch (Throwable t) {
                    recordFailure(failure, cancelled, t);
                }
            }, "byte-cas-consumer");
            runPair(producer, consumer, failure, cancelled);
        }

        private static void testShortPublication() throws Exception {
            ShortHolder holder = new ShortHolder();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicBoolean cancelled = new AtomicBoolean();
            Thread producer = new Thread(() -> {
                try {
                    for (int value = 1; value <= ROUNDS; value++) {
                        waitForShortState(holder, (short) 0, cancelled);
                        check(publishShort(holder, value), "short publication CAS failed");
                        waitForShortState(holder, (short) 2, cancelled);
                        U.putShortVolatile(holder, SHORT_STATE_OFFSET, (short) 0);
                    }
                } catch (Throwable t) {
                    recordFailure(failure, cancelled, t);
                }
            }, "short-cas-publisher");
            Thread consumer = new Thread(() -> {
                try {
                    for (int expected = 1; expected <= ROUNDS; expected++) {
                        int observed = waitForShortPublication(holder, cancelled);
                        check(observed == expected, "short payload publication mismatch");
                        check(U.compareAndSetShort(holder, SHORT_STATE_OFFSET,
                                        (short) 1, (short) 2),
                                "short acknowledgement CAS failed");
                    }
                } catch (Throwable t) {
                    recordFailure(failure, cancelled, t);
                }
            }, "short-cas-consumer");
            runPair(producer, consumer, failure, cancelled);
        }

        private static void testIntPublication() throws Exception {
            IntHolder holder = new IntHolder();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicBoolean cancelled = new AtomicBoolean();
            Thread producer = new Thread(() -> {
                try {
                    for (int value = 1; value <= ROUNDS; value++) {
                        waitForIntState(holder, 0, cancelled);
                        check(publishInt(holder, value), "int publication CAS failed");
                        waitForIntState(holder, 2, cancelled);
                        U.putIntVolatile(holder, INT_STATE_OFFSET, 0);
                    }
                } catch (Throwable t) {
                    recordFailure(failure, cancelled, t);
                }
            }, "int-cas-publisher");
            Thread consumer = new Thread(() -> {
                try {
                    for (int expected = 1; expected <= ROUNDS; expected++) {
                        int observed = waitForIntPublication(holder, cancelled);
                        check(observed == expected,
                                "int payload was not published before CAS: " + observed + " != " + expected);
                        check(U.compareAndSetInt(holder, INT_STATE_OFFSET, 1, 2),
                                "int acknowledgement CAS failed");
                    }
                } catch (Throwable t) {
                    recordFailure(failure, cancelled, t);
                }
            }, "int-cas-consumer");
            runPair(producer, consumer, failure, cancelled);
        }

        private static void testLongPublication() throws Exception {
            LongHolder holder = new LongHolder();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicBoolean cancelled = new AtomicBoolean();
            Thread producer = new Thread(() -> {
                try {
                    for (long value = 1; value <= ROUNDS; value++) {
                        waitForLongState(holder, 0L, cancelled);
                        check(publishLong(holder, value), "long publication CAS failed");
                        waitForLongState(holder, 2L, cancelled);
                        U.putLongVolatile(holder, LONG_STATE_OFFSET, 0L);
                    }
                } catch (Throwable t) {
                    recordFailure(failure, cancelled, t);
                }
            }, "long-cas-publisher");
            Thread consumer = new Thread(() -> {
                try {
                    for (long expected = 1; expected <= ROUNDS; expected++) {
                        long observed = waitForLongPublication(holder, cancelled);
                        check(observed == expected,
                                "long payload was not published before CAS: " + observed + " != " + expected);
                        check(U.compareAndSetLong(holder, LONG_STATE_OFFSET, 1L, 2L),
                                "long acknowledgement CAS failed");
                    }
                } catch (Throwable t) {
                    recordFailure(failure, cancelled, t);
                }
            }, "long-cas-consumer");
            runPair(producer, consumer, failure, cancelled);
        }

        private static void runPair(Thread producer, Thread consumer,
                                    AtomicReference<Throwable> failure,
                                    AtomicBoolean cancelled) throws Exception {
            producer.setDaemon(true);
            consumer.setDaemon(true);
            producer.start();
            consumer.start();
            long deadline = System.nanoTime() + PAIR_TIMEOUT_NANOS;
            joinUntil(producer, deadline);
            joinUntil(consumer, deadline);
            if (producer.isAlive() || consumer.isAlive()) {
                cancelled.set(true);
                producer.interrupt();
                consumer.interrupt();
                producer.join(1_000);
                consumer.join(1_000);
                throw new RuntimeException("CAS publication test timed out");
            }
            Throwable thrown = failure.get();
            if (thrown != null) {
                throw new RuntimeException("CAS publication test failed", thrown);
            }
        }

        private static void waitForByteState(ByteHolder holder, byte expected,
                                             AtomicBoolean cancelled) {
            long deadline = System.nanoTime() + WAIT_TIMEOUT_NANOS;
            while (U.getByteVolatile(holder, BYTE_STATE_OFFSET) != expected) {
                checkProgress(cancelled, deadline, "byte state " + expected);
                Thread.onSpinWait();
            }
        }

        private static void waitForShortState(ShortHolder holder, short expected,
                                              AtomicBoolean cancelled) {
            long deadline = System.nanoTime() + WAIT_TIMEOUT_NANOS;
            while (U.getShortVolatile(holder, SHORT_STATE_OFFSET) != expected) {
                checkProgress(cancelled, deadline, "short state " + expected);
                Thread.onSpinWait();
            }
        }

        private static void waitForIntState(IntHolder holder, int expected,
                                            AtomicBoolean cancelled) {
            long deadline = System.nanoTime() + WAIT_TIMEOUT_NANOS;
            while (U.getIntVolatile(holder, INT_STATE_OFFSET) != expected) {
                checkProgress(cancelled, deadline, "int state " + expected);
                Thread.onSpinWait();
            }
        }

        private static void waitForLongState(LongHolder holder, long expected,
                                             AtomicBoolean cancelled) {
            long deadline = System.nanoTime() + WAIT_TIMEOUT_NANOS;
            while (U.getLongVolatile(holder, LONG_STATE_OFFSET) != expected) {
                checkProgress(cancelled, deadline, "long state " + expected);
                Thread.onSpinWait();
            }
        }

        private static int waitForBytePublication(ByteHolder holder, AtomicBoolean cancelled) {
            long deadline = System.nanoTime() + WAIT_TIMEOUT_NANOS;
            int observed;
            while ((observed = observeByte(holder)) == 0) {
                checkProgress(cancelled, deadline, "byte publication");
                Thread.onSpinWait();
            }
            return observed;
        }

        private static int waitForShortPublication(ShortHolder holder, AtomicBoolean cancelled) {
            long deadline = System.nanoTime() + WAIT_TIMEOUT_NANOS;
            int observed;
            while ((observed = observeShort(holder)) == 0) {
                checkProgress(cancelled, deadline, "short publication");
                Thread.onSpinWait();
            }
            return observed;
        }

        private static int waitForIntPublication(IntHolder holder, AtomicBoolean cancelled) {
            long deadline = System.nanoTime() + WAIT_TIMEOUT_NANOS;
            int observed;
            while ((observed = observeInt(holder)) == 0) {
                checkProgress(cancelled, deadline, "int publication");
                Thread.onSpinWait();
            }
            return observed;
        }

        private static long waitForLongPublication(LongHolder holder, AtomicBoolean cancelled) {
            long deadline = System.nanoTime() + WAIT_TIMEOUT_NANOS;
            long observed;
            while ((observed = observeLong(holder)) == 0L) {
                checkProgress(cancelled, deadline, "long publication");
                Thread.onSpinWait();
            }
            return observed;
        }

        private static void joinUntil(Thread thread, long deadline) throws InterruptedException {
            long remaining = deadline - System.nanoTime();
            if (remaining > 0L) {
                thread.join(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining)));
            }
        }

        private static void checkProgress(AtomicBoolean cancelled, long deadline, String operation) {
            if (cancelled.get()) {
                throw new RuntimeException("CAS publication test cancelled while waiting for " + operation);
            }
            if (System.nanoTime() >= deadline) {
                throw new RuntimeException("CAS publication test timed out while waiting for " + operation);
            }
        }

        private static void recordFailure(AtomicReference<Throwable> failure,
                                          AtomicBoolean cancelled, Throwable thrown) {
            failure.compareAndSet(null, thrown);
            cancelled.set(true);
        }

        private static void check(boolean condition, String message) {
            if (!condition) {
                throw new RuntimeException(message);
            }
        }
    }
}
