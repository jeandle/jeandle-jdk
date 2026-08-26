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
 * @summary Verify Unsafe.getAndSet{Byte,Short,Int,Long} semantics and intrinsic lowering
 * @modules java.base/jdk.internal.misc
 * @library /test/lib /
 * @build compiler.jeandle.fileCheck.FileCheck
 * @run driver TestUnsafeGetAndSet
 */

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import compiler.jeandle.fileCheck.FileCheck;
import jdk.internal.misc.Unsafe;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestUnsafeGetAndSet {
    private static final Unsafe U = Unsafe.getUnsafe();
    private static final int JOIN_TIMEOUT_MS = 30_000;
    private static final int CONCURRENT_THREADS = 4;
    private static final int TOKENS_PER_CONCURRENT_WORKER = 63;
    private static final int CONCURRENT_TOKEN_COUNT =
            CONCURRENT_THREADS * TOKENS_PER_CONCURRENT_WORKER;
    private static final String BYTE_INTRINSIC_LOG =
            "Method `virtual jbyte jdk.internal.misc.Unsafe.getAndSetByte"
                    + "(jobject, jlong, jbyte)` is parsed as intrinsic";
    private static final String SHORT_INTRINSIC_LOG =
            "Method `virtual jshort jdk.internal.misc.Unsafe.getAndSetShort"
                    + "(jobject, jlong, jshort)` is parsed as intrinsic";
    private static final String INT_INTRINSIC_LOG =
            "Method `virtual jint jdk.internal.misc.Unsafe.getAndSetInt"
                    + "(jobject, jlong, jint)` is parsed as intrinsic";
    private static final String LONG_INTRINSIC_LOG =
            "Method `virtual jlong jdk.internal.misc.Unsafe.getAndSetLong"
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

    // The zero address is compiled but never dereferenced by the semantic run.
    private static volatile boolean zeroAddressBranch;
    private static final Holder CROSS_METHOD_HOLDER = new Holder();

    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            if (args[0].equals("cross")) {
                runCrossMethodZeroAddressSemantics();
            } else {
                runSemantics();
            }
            return;
        }
        runCase("enabled", true, null);
        runCase("control_intrinsic_disabled", false,
                "-XX:ControlIntrinsic=-_getAndSetByte,-_getAndSetShort,"
                        + "-_getAndSetInt,-_getAndSetLong");
        runCase("inline_unsafe_ops_disabled", false, "-XX:-InlineUnsafeOps");
        runCase("inline_natives_disabled", true, "-XX:-InlineNatives");
        runCrossMethodZeroAddressCase();
    }

    private static void runCase(String name, boolean intrinsicEnabled,
                                String additionalVmOption) throws Exception {
        Path dumpPath = Files.createTempDirectory("jeandle_getset_" + name + "_ir");
        List<String> command = new ArrayList<>(List.of(
                "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
                "-Xbatch", "-Xcomp", "-XX:-TieredCompilation", "-XX:-BackgroundCompilation",
                "-XX:+UseJeandleCompiler", "-Xlog:jeandle=debug,jit+compilation=debug",
                "-XX:CompileCommand=compileonly,TestUnsafeGetAndSet::*",
                "-XX:CompileCommand=dontinline,TestUnsafeGetAndSet::*",
                "-XX:+UnlockDiagnosticVMOptions", "-XX:+CIPrintCompilerName",
                "-XX:+JeandleDumpIR", "-XX:+JeandleDumpObjects",
                "-XX:JeandleDumpDirectory=" + dumpPath));
        if (additionalVmOption != null) {
            command.add(additionalVmOption);
        }
        command.add(TestUnsafeGetAndSet.class.getName());
        command.add("child");

        OutputAnalyzer output = ProcessTools.executeCommand(
                ProcessTools.createLimitedTestJavaProcessBuilder(command));
        output.shouldHaveExitValue(0);
        if (intrinsicEnabled) {
            output.shouldContain(BYTE_INTRINSIC_LOG).shouldContain(SHORT_INTRINSIC_LOG)
                    .shouldContain(INT_INTRINSIC_LOG).shouldContain(LONG_INTRINSIC_LOG);
            checkInstalledByJeandle(output, "setByte");
            checkInstalledByJeandle(output, "setShort");
            checkInstalledByJeandle(output, "setInt");
            checkInstalledByJeandle(output, "setLong");
            checkInstalledByJeandle(output, "publish");
            checkInstalledByJeandle(output, "compileZeroAddressBranch");
            checkInstalledByJeandle(output, "compileLateZeroAddressBranch");
        } else {
            output.shouldNotContain(BYTE_INTRINSIC_LOG).shouldNotContain(SHORT_INTRINSIC_LOG)
                    .shouldNotContain(INT_INTRINSIC_LOG).shouldNotContain(LONG_INTRINSIC_LOG);
        }
        checkIntrinsicIR(dumpPath, intrinsicEnabled);
        if (intrinsicEnabled) {
            checkZeroAddressIR(dumpPath);
            checkLateZeroAddressIR(dumpPath);
            checkZeroAddressObject(dumpPath);
        }
    }

    private static void checkInstalledByJeandle(OutputAnalyzer output, String method) {
        output.shouldMatch("(?s).*Jeandle:.*TestUnsafeGetAndSet::" + method + ".*");
    }

    private static void checkIntrinsicIR(Path dumpPath, boolean intrinsicEnabled) throws Exception {
        checkAtomicRmw(dumpPath, "setByte", byte.class, "i8", intrinsicEnabled);
        checkAtomicRmw(dumpPath, "setShort", short.class, "i16", intrinsicEnabled);
        checkAtomicRmw(dumpPath, "setInt", int.class, "i32", intrinsicEnabled);
        checkAtomicRmw(dumpPath, "setLong", long.class, "i64", intrinsicEnabled);

        FileCheck raw = new FileCheck(dumpPath.toString(),
                TestUnsafeGetAndSet.class.getDeclaredMethod("nativeAccesses"), false);
        FileCheck dynamic = new FileCheck(dumpPath.toString(),
                TestUnsafeGetAndSet.class.getDeclaredMethod(
                        "setIntDynamic", Object.class, long.class, int.class), false);
        FileCheck publish = new FileCheck(dumpPath.toString(),
                TestUnsafeGetAndSet.class.getDeclaredMethod("publish", Holder.class), false);
        if (intrinsicEnabled) {
            raw.checkPatternAnywhere("inttoptr i64");
            raw.checkPatternAnywhere("atomicrmw xchg ptr (?!addrspace\\(1\\)).* i8 .* seq_cst");
            raw.checkPatternAnywhere("atomicrmw xchg ptr (?!addrspace\\(1\\)).* i16 .* seq_cst");
            raw.checkPatternAnywhere("atomicrmw xchg ptr (?!addrspace\\(1\\)).* i32 .* seq_cst");
            raw.checkPatternAnywhere("atomicrmw xchg ptr (?!addrspace\\(1\\)).* i64 .* seq_cst");
            dynamic.checkPatternAnywhere("atomicrmw xchg ptr addrspace\\(1\\).* i32 .* seq_cst");
            dynamic.checkPatternAnywhere("inttoptr i64 .* to ptr");
            dynamic.checkPatternAnywhere(
                    "atomicrmw xchg ptr (?!addrspace\\(1\\)).* i32 .* seq_cst");
            dynamic.checkPatternAnywhere("phi i32");
            publish.checkPatternAnywhere("atomicrmw xchg ptr addrspace\\(1\\).* i32 .* seq_cst");
        } else {
            raw.checkNotPattern("atomicrmw xchg");
            dynamic.checkNotPattern("atomicrmw xchg");
            publish.checkNotPattern("atomicrmw xchg");
        }
    }

    private static void checkAtomicRmw(Path dumpPath, String name, Class<?> argumentType,
                                       String llvmType, boolean intrinsicEnabled) throws Exception {
        FileCheck check = new FileCheck(dumpPath.toString(),
                TestUnsafeGetAndSet.class.getDeclaredMethod(name, Holder.class, argumentType), false);
        if (intrinsicEnabled) {
            check.checkPattern("atomicrmw xchg ptr addrspace\\(1\\).* " + llvmType + ".* seq_cst");
        } else {
            check.checkNotPattern("atomicrmw xchg");
        }
    }

    private static void checkZeroAddressIR(Path dumpPath) throws Exception {
        FileCheck check = new FileCheck(dumpPath.toString(),
                TestUnsafeGetAndSet.class.getDeclaredMethod("compileZeroAddressBranch"), true);
        check.checkNotPattern("unsafe_get_set_");
        check.checkNotPattern("atomicrmw xchg");
        check.checkNotPattern("atomicrmw xchg ptr null");
    }

    private static void checkLateZeroAddressIR(Path dumpPath) throws Exception {
        FileCheck check = new FileCheck(dumpPath.toString(),
                TestUnsafeGetAndSet.class.getDeclaredMethod("compileLateZeroAddressBranch"), true);
        check.checkPatternAnywhere("unsafe_raw_zero_address");
        check.checkPatternAnywhere("@__llvm_deoptimize");
        check.checkNotPattern("atomicrmw xchg ptr null");
    }

    private static void checkZeroAddressObject(Path dumpPath) throws Exception {
        Path object;
        try (Stream<Path> files = Files.list(dumpPath)) {
            object = files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith(
                            TestUnsafeGetAndSet.class.getName().replace('.', '_')
                                    + "_compileZeroAddressBranch"))
                    .filter(path -> path.getFileName().toString().endsWith(".o"))
                    .max(Comparator.comparing(path -> path.getFileName().toString()))
                    .orElseThrow(() -> new AssertionError(
                            "No object dump for compileZeroAddressBranch"));
        }
        OutputAnalyzer output;
        try {
            output = ProcessTools.executeCommand("objdump", "-dr", object.toString());
        } catch (java.io.IOException e) {
            if (e.getMessage() != null && e.getMessage().contains("Cannot run program \"objdump\"")) {
                System.out.println("Skipping object-code check: system objdump is unavailable");
                return;
            }
            throw e;
        }
        output.shouldHaveExitValue(0);
        output.shouldContain("TestUnsafeGetAndSet_compileZeroAddressBranch");
        output.shouldNotMatch("(?m).*\\b(?:swp\\w*|ld(?:a)?xr\\w*|stl?xr\\w*).*" );
    }

    private static void runCrossMethodZeroAddressCase() throws Exception {
        Path dumpPath = Files.createTempDirectory("jeandle_getset_cross_zero_ir");
        List<String> command = new ArrayList<>(List.of(
                "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
                "-Xbatch", "-Xcomp", "-XX:-TieredCompilation", "-XX:-BackgroundCompilation",
                "-XX:+UseJeandleCompiler", "-Xlog:jeandle=debug,jit+compilation=debug",
                "-XX:CompileCommand=compileonly,TestUnsafeGetAndSet::compileCrossMethodZeroAddressBranch",
                "-XX:CompileCommand=compileonly,TestUnsafeGetAndSet::crossMethodZeroAddressAccess",
                "-XX:CompileCommand=inline,TestUnsafeGetAndSet::crossMethodZeroAddressAccess",
                "-XX:+UnlockDiagnosticVMOptions", "-XX:+CIPrintCompilerName",
                "-XX:+JeandleDumpIR", "-XX:JeandleDumpDirectory=" + dumpPath));
        command.add(TestUnsafeGetAndSet.class.getName());
        command.add("cross");

        OutputAnalyzer output = ProcessTools.executeCommand(
                ProcessTools.createLimitedTestJavaProcessBuilder(command));
        output.shouldHaveExitValue(0);
        checkInstalledByJeandle(output, "compileCrossMethodZeroAddressBranch");

        var method = TestUnsafeGetAndSet.class.getDeclaredMethod(
                "compileCrossMethodZeroAddressBranch");
        FileCheck caller = new FileCheck(dumpPath.toString(), method, true);
        caller.checkPatternAnywhere("unsafe_raw_zero_address");
        caller.checkPatternAnywhere("@__llvm_deoptimize");
        caller.checkPatternAnywhere(
                "atomicrmw xchg ptr addrspace\\(1\\).* i32 .* seq_cst");
        caller.checkNotPattern("atomicrmw xchg ptr null");
    }

    private static void runSemantics() throws Exception {
        heapAccesses();
        nativeAccesses();
        dynamicBaseAccesses();
        concurrentAccesses();
        memoryOrdering();
        receiverNull();
        compileZeroAddressBranch();
        compileLateZeroAddressBranch();
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static byte setByte(Holder holder, byte update) {
        return U.getAndSetByte(holder, BYTE_OFFSET, update);
    }

    private static short setShort(Holder holder, short update) {
        return U.getAndSetShort(holder, SHORT_OFFSET, update);
    }

    private static int setInt(Holder holder, int update) {
        return U.getAndSetInt(holder, INT_OFFSET, update);
    }

    private static int setIntDynamic(Object base, long offset, int update) {
        return U.getAndSetInt(base, offset, update);
    }

    private static long setLong(Holder holder, long update) {
        return U.getAndSetLong(holder, LONG_OFFSET, update);
    }

    private static void heapAccesses() {
        Holder holder = new Holder();
        U.putByte(holder, BYTE_OFFSET, (byte) -120);
        check(setByte(holder, (byte) 119) == -120, "byte old value");
        check(U.getByteVolatile(holder, BYTE_OFFSET) == 119, "byte new value");
        U.putShort(holder, SHORT_OFFSET, (short) -32760);
        check(setShort(holder, (short) 32760) == -32760, "short old value");
        check(U.getShortVolatile(holder, SHORT_OFFSET) == 32760, "short new value");
        U.putInt(holder, INT_OFFSET, Integer.MIN_VALUE);
        check(setInt(holder, Integer.MAX_VALUE) == Integer.MIN_VALUE, "int old value");
        check(U.getIntVolatile(holder, INT_OFFSET) == Integer.MAX_VALUE, "int new value");
        U.putLong(holder, LONG_OFFSET, Long.MIN_VALUE);
        check(setLong(holder, Long.MAX_VALUE) == Long.MIN_VALUE, "long old value");
        check(U.getLongVolatile(holder, LONG_OFFSET) == Long.MAX_VALUE, "long new value");
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
            check(U.getAndSetByte(null, byteAddress, (byte) 9) == -7, "native byte old value");
            check(U.getByte(null, byteAddress) == 9, "native byte value");
            U.putShort(null, shortAddress, (short) -13);
            check(U.getAndSetShort(null, shortAddress, (short) 21) == -13, "native short old value");
            check(U.getShort(null, shortAddress) == 21, "native short value");
            U.putInt(null, intAddress, -19);
            check(U.getAndSetInt(null, intAddress, 31) == -19, "native int old value");
            check(U.getInt(null, intAddress) == 31, "native int value");
            U.putLong(null, longAddress, -23L);
            check(U.getAndSetLong(null, longAddress, 37L) == -23L, "native long old value");
            check(U.getLong(null, longAddress) == 37L, "native long value");
        } finally {
            U.freeMemory(memory);
        }
    }

    private static void dynamicBaseAccesses() {
        Holder holder = new Holder();
        U.putInt(holder, INT_OFFSET, 17);
        check(setIntDynamic(holder, INT_OFFSET, 20) == 17, "dynamic heap old value");
        check(U.getIntVolatile(holder, INT_OFFSET) == 20, "dynamic heap value");

        long memory = U.allocateMemory(8);
        try {
            U.putInt(null, memory, 23);
            check(setIntDynamic(null, memory, 28) == 23, "dynamic raw old value");
            check(U.getInt(null, memory) == 28, "dynamic raw value");
        } finally {
            U.freeMemory(memory);
        }
    }

    private static void concurrentAccesses() throws Exception {
        Holder holder = new Holder();
        long[] byteOldValues = new long[CONCURRENT_TOKEN_COUNT];
        long[] shortOldValues = new long[CONCURRENT_TOKEN_COUNT];
        long[] intOldValues = new long[CONCURRENT_TOKEN_COUNT];
        long[] longOldValues = new long[CONCURRENT_TOKEN_COUNT];
        runWorkers(TOKENS_PER_CONCURRENT_WORKER, (worker, iteration) -> {
            int token = worker * TOKENS_PER_CONCURRENT_WORKER + iteration + 1;
            int index = token - 1;
            byteOldValues[index] = Byte.toUnsignedInt(setByte(holder, (byte) token));
            shortOldValues[index] = setShort(holder, (short) token);
            intOldValues[index] = setInt(holder, token);
            longOldValues[index] = setLong(holder, token);
        });
        checkExchangeChain(byteOldValues, Byte.toUnsignedInt(holder.byteValue), "byte");
        checkExchangeChain(shortOldValues, holder.shortValue, "short");
        checkExchangeChain(intOldValues, holder.intValue, "int");
        checkExchangeChain(longOldValues, holder.longValue, "long");
    }

    private static void checkExchangeChain(long[] oldValues, long finalValue, String type) {
        boolean[] seen = new boolean[CONCURRENT_TOKEN_COUNT + 1];
        for (long oldValue : oldValues) {
            check(oldValue >= 0 && oldValue <= CONCURRENT_TOKEN_COUNT,
                    type + " returned invalid old value: " + oldValue);
            int value = (int) oldValue;
            check(!seen[value], type + " returned duplicate old value: " + value);
            seen[value] = true;
        }
        check(finalValue >= 1 && finalValue <= CONCURRENT_TOKEN_COUNT,
                type + " has invalid final value: " + finalValue);
        int finalToken = (int) finalValue;
        check(!seen[finalToken], type + " final value was also returned: " + finalToken);
        seen[finalToken] = true;
        for (int token = 0; token <= CONCURRENT_TOKEN_COUNT; token++) {
            check(seen[token], type + " missing token: " + token);
        }
    }

    @FunctionalInterface
    private interface IndexedTask {
        void run(int worker, int iteration) throws Exception;
    }

    private static void runWorkers(int iterations, IndexedTask task) throws Exception {
        CountDownLatch ready = new CountDownLatch(CONCURRENT_THREADS);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread[] workers = new Thread[CONCURRENT_THREADS];
        for (int i = 0; i < CONCURRENT_THREADS; i++) {
            int workerIndex = i;
            workers[i] = new Thread(() -> {
                try {
                    ready.countDown();
                    start.await();
                    for (int j = 0; j < iterations; j++) {
                        task.run(workerIndex, j);
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            });
            workers[i].setDaemon(true);
            workers[i].start();
        }
        ready.await();
        start.countDown();
        joinAndCleanup(workers, "concurrent worker timed out");
        if (failure.get() != null) {
            throw new AssertionError("concurrent worker failed", failure.get());
        }
    }

    private static void joinAndCleanup(Thread[] workers, String timeoutMessage)
            throws Exception {
        for (Thread worker : workers) {
            worker.join(JOIN_TIMEOUT_MS);
        }
        boolean timedOut = false;
        for (Thread worker : workers) {
            if (worker.isAlive()) {
                timedOut = true;
                worker.interrupt();
            }
        }
        if (timedOut) {
            for (Thread worker : workers) {
                worker.join(1_000);
            }
        }
        for (Thread worker : workers) {
            check(!worker.isAlive(), timeoutMessage);
        }
    }

    private static void memoryOrdering() throws Exception {
        for (int i = 0; i < 100; i++) {
            Holder holder = new Holder();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread producer = new Thread(() -> publish(holder));
            Thread consumer = new Thread(() -> observePublication(holder, failure));
            producer.setDaemon(true);
            consumer.setDaemon(true);
            consumer.start();
            producer.start();
            joinAndCleanup(new Thread[] { producer, consumer },
                    "publication worker timed out");
            if (failure.get() != null) {
                throw new AssertionError("memory ordering failed", failure.get());
            }
        }
    }

    private static void publish(Holder holder) {
        U.putInt(holder, INT_OFFSET, 42);
        U.getAndSetInt(holder, SIGNAL_OFFSET, 1);
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
            ((Unsafe) null).getAndSetByte(holder, BYTE_OFFSET, (byte) 1);
            throw new RuntimeException("null Unsafe receiver did not throw for byte getAndSet");
        } catch (NullPointerException expected) {
            // Expected.
        }
        try {
            ((Unsafe) null).getAndSetShort(holder, SHORT_OFFSET, (short) 1);
            throw new RuntimeException("null Unsafe receiver did not throw for short getAndSet");
        } catch (NullPointerException expected) {
            // Expected.
        }
        try {
            ((Unsafe) null).getAndSetInt(holder, INT_OFFSET, 1);
            throw new RuntimeException("null Unsafe receiver did not throw for int getAndSet");
        } catch (NullPointerException expected) {
            // Expected.
        }
        try {
            ((Unsafe) null).getAndSetLong(holder, LONG_OFFSET, 1L);
            throw new RuntimeException("null Unsafe receiver did not throw for long getAndSet");
        } catch (NullPointerException expected) {
            // Expected.
        }
    }

    private static void compileZeroAddressBranch() {
        if (zeroAddressBranch) {
            U.getAndSetInt(null, 0L, 1);
        }
    }

    private static void compileLateZeroAddressBranch() {
        boolean execute = zeroAddressBranch;
        long offset = execute ? 0L : 1L;
        if (execute) {
            U.getAndSetInt(null, offset, 1);
        }
    }

    private static void compileCrossMethodZeroAddressBranch() {
        boolean zero = zeroAddressBranch;
        Object base = zero ? null : CROSS_METHOD_HOLDER;
        long offset = zero ? 0L : INT_OFFSET;
        crossMethodZeroAddressAccess(base, offset);
    }

    private static void crossMethodZeroAddressAccess(Object base, long offset) {
        U.getAndSetInt(base, offset, 1);
    }

    private static void runCrossMethodZeroAddressSemantics() {
        zeroAddressBranch = false;
        CROSS_METHOD_HOLDER.intValue = 0;
        compileCrossMethodZeroAddressBranch();
        check(CROSS_METHOD_HOLDER.intValue == 1, "cross-method non-zero access");
    }
}
