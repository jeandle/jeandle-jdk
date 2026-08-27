/*
 * Copyright (c) 2026, the Jeandle-JDK Authors. All Rights Reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 */

/*
 * @test id=semantic
 * @summary Verify Unsafe.getAndAdd{Byte,Short,Int,Long} semantics and intrinsic lowering
 * @modules java.base/jdk.internal.misc
 * @library /test/lib /
 * @build compiler.jeandle.fileCheck.FileCheck
 * @run driver TestUnsafeGetAndAdd
 */

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import compiler.jeandle.fileCheck.FileCheck;
import jdk.internal.misc.Unsafe;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestUnsafeGetAndAdd {
    private static final Unsafe U = Unsafe.getUnsafe();
    private static final int JOIN_TIMEOUT_MS = 30_000;
    private static final String BYTE_INTRINSIC_LOG =
            "Method `virtual jbyte jdk.internal.misc.Unsafe.getAndAddByte"
                    + "(jobject, jlong, jbyte)` is parsed as intrinsic";
    private static final String SHORT_INTRINSIC_LOG =
            "Method `virtual jshort jdk.internal.misc.Unsafe.getAndAddShort"
                    + "(jobject, jlong, jshort)` is parsed as intrinsic";
    private static final String INT_INTRINSIC_LOG =
            "Method `virtual jint jdk.internal.misc.Unsafe.getAndAddInt"
                    + "(jobject, jlong, jint)` is parsed as intrinsic";
    private static final String LONG_INTRINSIC_LOG =
            "Method `virtual jlong jdk.internal.misc.Unsafe.getAndAddLong"
                    + "(jobject, jlong, jlong)` is parsed as intrinsic";

    private static final class Holder {
        byte byteValue;
        short shortValue;
        int intValue;
        int signal;
        long longValue;
    }

    private static final long BYTE_OFFSET = U.objectFieldOffset(Holder.class, "byteValue");
    private static final long SHORT_OFFSET = U.objectFieldOffset(Holder.class, "shortValue");
    private static final long INT_OFFSET = U.objectFieldOffset(Holder.class, "intValue");
    private static final long SIGNAL_OFFSET = U.objectFieldOffset(Holder.class, "signal");
    private static final long LONG_OFFSET = U.objectFieldOffset(Holder.class, "longValue");

    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            runSemantics();
            return;
        }
        runCase("enabled", true, null);
        runCase("control_intrinsic_disabled", false,
                "-XX:ControlIntrinsic=-_getAndAddByte,-_getAndAddShort,-_getAndAddInt,-_getAndAddLong");
        runCase("inline_unsafe_ops_disabled", false, "-XX:-InlineUnsafeOps");
        runCase("inline_natives_disabled", true, "-XX:-InlineNatives");
    }

    private static void runCase(String name, boolean intrinsicEnabled,
                                String additionalVmOption) throws Exception {
        Path dumpPath = Files.createTempDirectory("jeandle_getadd_" + name + "_ir");
        List<String> command = new ArrayList<>(List.of(
                "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
                "-Xbatch", "-Xcomp", "-XX:-TieredCompilation", "-XX:-BackgroundCompilation",
                "-XX:+UseJeandleCompiler", "-Xlog:jeandle=debug,jit+compilation=debug",
                "-XX:CompileCommand=compileonly,TestUnsafeGetAndAdd::*",
                "-XX:CompileCommand=dontinline,TestUnsafeGetAndAdd::addByte",
                "-XX:CompileCommand=dontinline,TestUnsafeGetAndAdd::addShort",
                "-XX:CompileCommand=dontinline,TestUnsafeGetAndAdd::addInt",
                "-XX:CompileCommand=dontinline,TestUnsafeGetAndAdd::addLong",
                "-XX:CompileCommand=dontinline,TestUnsafeGetAndAdd::addIntDynamic",
                "-XX:CompileCommand=dontinline,TestUnsafeGetAndAdd::publish",
                "-XX:CompileCommand=dontinline,TestUnsafeGetAndAdd::observePublication",
                "-XX:+UnlockDiagnosticVMOptions",
                "-XX:+CIPrintCompilerName",
                "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dumpPath));
        if (additionalVmOption != null) {
            command.add(additionalVmOption);
        }
        command.add(TestUnsafeGetAndAdd.class.getName());
        command.add("child");

        OutputAnalyzer output = ProcessTools.executeCommand(
                ProcessTools.createLimitedTestJavaProcessBuilder(command));
        output.shouldHaveExitValue(0);
        if (intrinsicEnabled) {
            output.shouldContain(BYTE_INTRINSIC_LOG).shouldContain(SHORT_INTRINSIC_LOG)
                  .shouldContain(INT_INTRINSIC_LOG).shouldContain(LONG_INTRINSIC_LOG);
            checkInstalledByJeandle(output, "addByte");
            checkInstalledByJeandle(output, "addShort");
            checkInstalledByJeandle(output, "addInt");
            checkInstalledByJeandle(output, "addLong");
            checkInstalledByJeandle(output, "publish");
        } else {
            output.shouldNotContain(BYTE_INTRINSIC_LOG).shouldNotContain(SHORT_INTRINSIC_LOG)
                  .shouldNotContain(INT_INTRINSIC_LOG).shouldNotContain(LONG_INTRINSIC_LOG);
        }
        checkIntrinsicIR(dumpPath, intrinsicEnabled);
    }

    private static void checkInstalledByJeandle(OutputAnalyzer output, String method) {
        output.shouldMatch("(?s).*Jeandle:.*TestUnsafeGetAndAdd::" + method + ".*");
    }

    private static void checkIntrinsicIR(Path dumpPath, boolean intrinsicEnabled) throws Exception {
        checkAtomicRmw(dumpPath, "addByte", byte.class, "i8", intrinsicEnabled);
        checkAtomicRmw(dumpPath, "addShort", short.class, "i16", intrinsicEnabled);
        checkAtomicRmw(dumpPath, "addInt", int.class, "i32", intrinsicEnabled);
        checkAtomicRmw(dumpPath, "addLong", long.class, "i64", intrinsicEnabled);

        FileCheck raw = new FileCheck(dumpPath.toString(),
                TestUnsafeGetAndAdd.class.getDeclaredMethod("nativeAccesses"), false);
        FileCheck dynamic = new FileCheck(dumpPath.toString(),
                TestUnsafeGetAndAdd.class.getDeclaredMethod("addIntDynamic", Object.class, long.class,
                        int.class), false);
        FileCheck publish = new FileCheck(dumpPath.toString(),
                TestUnsafeGetAndAdd.class.getDeclaredMethod("publish", Holder.class), false);
        if (intrinsicEnabled) {
            raw.checkPattern("inttoptr i64");
            raw.checkPattern("atomicrmw add ptr .* i8 .* seq_cst");
            raw.checkPattern("atomicrmw add ptr .* i16 .* seq_cst");
            raw.checkPattern("atomicrmw add ptr .* i32 .* seq_cst");
            raw.checkPattern("atomicrmw add ptr .* i64 .* seq_cst");
            dynamic.checkPattern("atomicrmw add ptr addrspace\\(1\\).* i32 .* seq_cst");
            dynamic.checkPattern("inttoptr i64 .* to ptr");
            dynamic.checkPattern("atomicrmw add ptr .* i32 .* seq_cst");
            dynamic.checkPattern("phi i32");
            publish.checkPattern("atomicrmw add ptr addrspace\\(1\\).* i32 .* seq_cst");
        } else {
            raw.checkNotPattern("atomicrmw add");
            dynamic.checkNotPattern("atomicrmw add");
            publish.checkNotPattern("atomicrmw add");
        }
    }

    private static void checkAtomicRmw(Path dumpPath, String name, Class<?> argumentType,
                                       String llvmType, boolean intrinsicEnabled) throws Exception {
        FileCheck check = new FileCheck(dumpPath.toString(),
                TestUnsafeGetAndAdd.class.getDeclaredMethod(name, Holder.class, argumentType), false);
        if (intrinsicEnabled) {
            check.checkPattern("atomicrmw add ptr addrspace\\(1\\).* " + llvmType + ".* seq_cst");
        } else {
            check.checkNotPattern("atomicrmw add");
        }
    }

    private static void runSemantics() throws Exception {
        heapAccesses();
        nativeAccesses();
        dynamicBaseAccesses();
        concurrentAccesses();
        memoryOrdering();
        receiverNull();
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static byte addByte(Holder holder, byte delta) {
        return U.getAndAddByte(holder, BYTE_OFFSET, delta);
    }

    private static short addShort(Holder holder, short delta) {
        return U.getAndAddShort(holder, SHORT_OFFSET, delta);
    }

    private static int addInt(Holder holder, int delta) {
        return U.getAndAddInt(holder, INT_OFFSET, delta);
    }

    private static int addIntDynamic(Object base, long offset, int delta) {
        return U.getAndAddInt(base, offset, delta);
    }

    private static long addLong(Holder holder, long delta) {
        return U.getAndAddLong(holder, LONG_OFFSET, delta);
    }

    private static void heapAccesses() {
        Holder holder = new Holder();
        U.putByte(holder, BYTE_OFFSET, (byte) 120);
        check(addByte(holder, (byte) 11) == 120, "byte old value");
        check(U.getByteVolatile(holder, BYTE_OFFSET) == (byte) -125, "byte wrapped value");
        U.putShort(holder, SHORT_OFFSET, (short) 32760);
        check(addShort(holder, (short) 11) == 32760, "short old value");
        check(U.getShortVolatile(holder, SHORT_OFFSET) == (short) -32765, "short wrapped value");
        U.putInt(holder, INT_OFFSET, Integer.MAX_VALUE);
        check(addInt(holder, 3) == Integer.MAX_VALUE, "int old value");
        check(U.getIntVolatile(holder, INT_OFFSET) == Integer.MIN_VALUE + 2, "int wrapped value");
        U.putLong(holder, LONG_OFFSET, Long.MAX_VALUE);
        check(addLong(holder, 3) == Long.MAX_VALUE, "long old value");
        check(U.getLongVolatile(holder, LONG_OFFSET) == Long.MIN_VALUE + 2, "long wrapped value");
    }

    private static long alignUp(long address, int alignment) {
        return (address + alignment - 1) & -alignment;
    }

    private static void nativeAccesses() {
        long memory = U.allocateMemory(32);
        try {
            long byteAddress = memory;
            long shortAddress = alignUp(memory, 2);
            long intAddress = alignUp(memory, 4);
            long longAddress = alignUp(memory, 8);
            U.putByte(null, byteAddress, (byte) -7);
            check(U.getAndAddByte(null, byteAddress, (byte) 9) == -7, "native byte old value");
            check(U.getByte(null, byteAddress) == 2, "native byte value");
            U.putShort(null, shortAddress, (short) -13);
            check(U.getAndAddShort(null, shortAddress, (short) 21) == -13, "native short old value");
            check(U.getShort(null, shortAddress) == 8, "native short value");
            U.putInt(null, intAddress, -19);
            check(U.getAndAddInt(null, intAddress, 31) == -19, "native int old value");
            check(U.getInt(null, intAddress) == 12, "native int value");
            U.putLong(null, longAddress, -23L);
            check(U.getAndAddLong(null, longAddress, 37L) == -23L, "native long old value");
            check(U.getLong(null, longAddress) == 14L, "native long value");
        } finally {
            U.freeMemory(memory);
        }
    }

    private static void dynamicBaseAccesses() {
        Holder holder = new Holder();
        U.putInt(holder, INT_OFFSET, 17);
        check(addIntDynamic(holder, INT_OFFSET, 3) == 17, "dynamic heap old value");
        check(U.getIntVolatile(holder, INT_OFFSET) == 20, "dynamic heap value");

        long memory = U.allocateMemory(8);
        try {
            U.putInt(null, memory, 23);
            check(addIntDynamic(null, memory, 5) == 23, "dynamic raw old value");
            check(U.getInt(null, memory) == 28, "dynamic raw value");
        } finally {
            U.freeMemory(memory);
        }
    }

    private static void concurrentAccesses() throws Exception {
        Holder holder = new Holder();
        runWorkers(20, () -> addByte(holder, (byte) 1));
        check(U.getByteVolatile(holder, BYTE_OFFSET) == 80, "concurrent byte add");
        runWorkers(1_000, () -> addShort(holder, (short) 1));
        check(U.getShortVolatile(holder, SHORT_OFFSET) == 4_000, "concurrent short add");
        runWorkers(10_000, () -> addInt(holder, 1));
        check(U.getIntVolatile(holder, INT_OFFSET) == 40_000, "concurrent int add");
        runWorkers(10_000, () -> addLong(holder, 1));
        check(U.getLongVolatile(holder, LONG_OFFSET) == 40_000L, "concurrent long add");
    }

    private static void runWorkers(int iterations, Runnable task) throws Exception {
        int threads = 4;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread[] workers = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            workers[i] = new Thread(() -> {
                try {
                    ready.countDown();
                    start.await();
                    for (int j = 0; j < iterations; j++) {
                        task.run();
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            });
            workers[i].start();
        }
        ready.await();
        start.countDown();
        for (Thread worker : workers) {
            worker.join(JOIN_TIMEOUT_MS);
            check(!worker.isAlive(), "concurrent worker timed out");
        }
        if (failure.get() != null) {
            throw new AssertionError("concurrent worker failed", failure.get());
        }
    }

    private static void memoryOrdering() throws Exception {
        for (int i = 0; i < 100; i++) {
            Holder holder = new Holder();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread producer = new Thread(() -> publish(holder));
            Thread consumer = new Thread(() -> observePublication(holder, failure));
            consumer.start();
            producer.start();
            producer.join(JOIN_TIMEOUT_MS);
            consumer.join(JOIN_TIMEOUT_MS);
            check(!producer.isAlive() && !consumer.isAlive(), "publication worker timed out");
            if (failure.get() != null) {
                throw new AssertionError("memory ordering failed", failure.get());
            }
        }
    }

    private static void publish(Holder holder) {
        U.putInt(holder, INT_OFFSET, 42);
        U.getAndAddInt(holder, SIGNAL_OFFSET, 1);
    }

    private static void observePublication(Holder holder, AtomicReference<Throwable> failure) {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (U.getIntVolatile(holder, SIGNAL_OFFSET) == 0 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        if (U.getIntVolatile(holder, SIGNAL_OFFSET) == 0) {
            failure.compareAndSet(null, new AssertionError("publication timed out"));
        } else if (U.getInt(holder, INT_OFFSET) != 42) {
            failure.compareAndSet(null, new AssertionError("publication value not visible"));
        }
    }

    private static void receiverNull() {
        Holder holder = new Holder();
        try {
            ((Unsafe) null).getAndAddByte(holder, BYTE_OFFSET, (byte) 1);
            throw new RuntimeException("null Unsafe receiver did not throw for byte getAndAdd");
        } catch (NullPointerException expected) {
            // Expected.
        }
        try {
            ((Unsafe) null).getAndAddShort(holder, SHORT_OFFSET, (short) 1);
            throw new RuntimeException("null Unsafe receiver did not throw for short getAndAdd");
        } catch (NullPointerException expected) {
            // Expected.
        }
        try {
            ((Unsafe) null).getAndAddInt(holder, INT_OFFSET, 1);
            throw new RuntimeException("null Unsafe receiver did not throw for int getAndAdd");
        } catch (NullPointerException expected) {
            // Expected.
        }
        try {
            ((Unsafe) null).getAndAddLong(holder, LONG_OFFSET, 1L);
            throw new RuntimeException("null Unsafe receiver did not throw for long getAndAdd");
        } catch (NullPointerException expected) {
            // Expected.
        }
    }
}
