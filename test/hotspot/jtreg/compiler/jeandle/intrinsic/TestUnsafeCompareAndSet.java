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
 */

/*
 * @test
 * @summary Test Jeandle Unsafe primitive compareAndSet intrinsics and disabled fallback
 * @modules java.base/jdk.internal.misc
 * @library /test/lib /
 * @build compiler.jeandle.fileCheck.FileCheck
 * @run main/othervm compiler.jeandle.intrinsic.TestUnsafeCompareAndSet
 */

package compiler.jeandle.intrinsic;

import compiler.jeandle.fileCheck.FileCheck;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import jdk.internal.misc.Unsafe;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestUnsafeCompareAndSet {
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
        runCase("enabled", true, null);
        runCase("control_intrinsic_disabled", false,
                "-XX:ControlIntrinsic=-" + BYTE_ID + ",-" + SHORT_ID
                        + ",-" + INT_ID + ",-" + LONG_ID);
        runCase("inline_unsafe_ops_disabled", false, "-XX:-InlineUnsafeOps");
        runCase("inline_natives_disabled", false, "-XX:-InlineNatives");
    }

    private static void runCase(String name, boolean enabled, String additionalVmOption)
            throws Exception {
        String dumpPath = Files.createTempDirectory("jeandle_cas_" + name).toString();
        String wrapper = TestWrapper.class.getName();

        ArrayList<String> command = new ArrayList<>(List.of(
                "--add-exports", "java.base/jdk.internal.misc=ALL-UNNAMED",
                "-Xbatch", "-Xcomp", "-XX:-TieredCompilation",
                "-XX:-BackgroundCompilation", "-XX:+UseJeandleCompiler",
                "-XX:+UnlockDiagnosticVMOptions",
                "-XX:+JeandleDumpIR", "-XX:JeandleDumpDirectory=" + dumpPath,
                "-Xlog:jeandle=debug,jit+compilation=debug",
                "-XX:+CIPrintCompilerName",
                "-XX:CompileCommand=compileonly," + wrapper + "::casByte",
                "-XX:CompileCommand=compileonly," + wrapper + "::casByteAtOffset",
                "-XX:CompileCommand=compileonly," + wrapper + "::casShort",
                "-XX:CompileCommand=compileonly," + wrapper + "::casShortAtOffset",
                "-XX:CompileCommand=compileonly," + wrapper + "::casInt",
                "-XX:CompileCommand=compileonly," + wrapper + "::casLong",
                "-XX:CompileCommand=compileonly," + wrapper + "::casByteNative",
                "-XX:CompileCommand=compileonly," + wrapper + "::casShortNative",
                "-XX:CompileCommand=compileonly," + wrapper + "::casIntNative",
                "-XX:CompileCommand=compileonly," + wrapper + "::casLongNative",
                "-XX:CompileCommand=compileonly," + wrapper + "::casIntDynamic",
                "-XX:CompileCommand=compileonly," + wrapper + "::nullReceiverByte",
                "-XX:CompileCommand=compileonly," + wrapper + "::nullReceiverShort",
                "-XX:CompileCommand=compileonly," + wrapper + "::nullReceiverInt",
                "-XX:CompileCommand=compileonly," + wrapper + "::nullReceiverLong"));
        if (additionalVmOption != null) {
            command.add(additionalVmOption);
        }
        command.add(wrapper);

        OutputAnalyzer output = ProcessTools.executeCommand(
                ProcessTools.createLimitedTestJavaProcessBuilder(command));
        output.shouldHaveExitValue(0).shouldContain("TestUnsafeCompareAndSet PASSED");
        if (enabled) {
            output.shouldContain(BYTE_INTRINSIC_LOG).shouldContain(SHORT_INTRINSIC_LOG)
                    .shouldContain(INT_INTRINSIC_LOG).shouldContain(LONG_INTRINSIC_LOG);
            checkInstalledByJeandle(output, "casByte");
            checkInstalledByJeandle(output, "casShort");
            checkInstalledByJeandle(output, "casInt");
            checkInstalledByJeandle(output, "casLong");
        } else {
            output.shouldNotContain(BYTE_INTRINSIC_LOG).shouldNotContain(SHORT_INTRINSIC_LOG)
                    .shouldNotContain(INT_INTRINSIC_LOG).shouldNotContain(LONG_INTRINSIC_LOG);
        }

        FileCheck byteCheck = new FileCheck(dumpPath,
                TestWrapper.class.getMethod("casByte", TestWrapper.class, byte.class, byte.class),
                false);
        FileCheck shortCheck = new FileCheck(dumpPath,
                TestWrapper.class.getMethod("casShort", TestWrapper.class, short.class, short.class),
                false);
        FileCheck intCheck = new FileCheck(dumpPath,
                TestWrapper.class.getMethod("casInt", TestWrapper.class, int.class, int.class),
                false);
        FileCheck longCheck = new FileCheck(dumpPath,
                TestWrapper.class.getMethod("casLong", TestWrapper.class, long.class, long.class),
                false);

        if (enabled) {
            byteCheck.checkPattern("trunc i32 .* to i8");
            byteCheck.checkPattern("cmpxchg ptr addrspace\\(1\\).*i8.*seq_cst seq_cst, align 1");
            shortCheck.checkPattern("trunc i32 .* to i16");
            shortCheck.checkPattern("cmpxchg ptr addrspace\\(1\\).*i16.*seq_cst seq_cst, align 2");
            FileCheck shortOffsetCheck = new FileCheck(dumpPath,
                    TestWrapper.class.getMethod("casShortAtOffset", byte[].class, long.class,
                            short.class, short.class), false);
            shortOffsetCheck.checkPattern(
                    "cmpxchg ptr addrspace\\(1\\).*i16.*seq_cst seq_cst, align 2");
            intCheck.checkPattern("cmpxchg ptr addrspace\\(1\\).*i32.*seq_cst seq_cst, align 4");
            longCheck.checkPattern("cmpxchg ptr addrspace\\(1\\).*i64.*seq_cst seq_cst, align 8");

            FileCheck nativeByteCheck = new FileCheck(dumpPath,
                    TestWrapper.class.getMethod("casByteNative", long.class, byte.class, byte.class),
                    false);
            FileCheck nativeShortCheck = new FileCheck(dumpPath,
                    TestWrapper.class.getMethod("casShortNative", long.class, short.class, short.class),
                    false);
            FileCheck nativeIntCheck = new FileCheck(dumpPath,
                    TestWrapper.class.getMethod("casIntNative", long.class, int.class, int.class),
                    false);
            FileCheck nativeLongCheck = new FileCheck(dumpPath,
                    TestWrapper.class.getMethod("casLongNative", long.class, long.class, long.class),
                    false);
            checkRawNativeCas(nativeByteCheck, "i8");
            checkRawNativeCas(nativeShortCheck, "i16");
            checkRawNativeCas(nativeIntCheck, "i32");
            checkRawNativeCas(nativeLongCheck, "i64");

            FileCheck dynamicIntCheck = new FileCheck(dumpPath,
                    TestWrapper.class.getMethod("casIntDynamic", Object.class, long.class,
                            int.class, int.class), false);
            dynamicIntCheck.checkPattern("cmpxchg ptr addrspace\\(1\\).*i32.*seq_cst seq_cst, align 4");
            dynamicIntCheck.checkPattern("inttoptr i64 .* to ptr");
            dynamicIntCheck.checkPattern("cmpxchg ptr .*i32.*seq_cst seq_cst, align 4");
            dynamicIntCheck.checkPattern("phi i1");
        } else {
            byteCheck.checkNotPattern("cmpxchg ptr addrspace\\(1\\).*i8.*seq_cst seq_cst, align 1");
            shortCheck.checkNotPattern("cmpxchg ptr addrspace\\(1\\).*i16.*seq_cst seq_cst, align 2");
            intCheck.checkNotPattern("cmpxchg ptr addrspace\\(1\\).*i32.*seq_cst seq_cst, align 4");
            longCheck.checkNotPattern("cmpxchg ptr addrspace\\(1\\).*i64.*seq_cst seq_cst, align 8");
            byteCheck.checkPattern("invoke .*Unsafe_compareAndSetByte");
            shortCheck.checkPattern("invoke .*Unsafe_compareAndSetShort");
            intCheck.checkPattern("invoke hotspotcc i32.*Unsafe_compareAndSetInt");
            longCheck.checkPattern("invoke hotspotcc i32.*Unsafe_compareAndSetLong");
        }
    }

    private static void checkInstalledByJeandle(OutputAnalyzer output, String method) {
        output.shouldMatch("(?s).*Jeandle:.*TestUnsafeCompareAndSet\\$TestWrapper::"
                + method + ".*");
    }

    private static void checkRawNativeCas(FileCheck check, String type) {
        check.checkPattern("inttoptr i64 .* to ptr");
        check.checkPattern("cmpxchg ptr .*" + type + ".*seq_cst seq_cst");
    }

    public static class TestWrapper {
        private static final Unsafe U = Unsafe.getUnsafe();
        private static final int MAX_CAS_ATTEMPTS = 10_000;
        private static final long WORKER_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(30);
        private static final long BYTE_OFFSET;
        private static final long SHORT_OFFSET;
        private static final long INT_OFFSET;
        private static final long LONG_OFFSET;
        private static final long BYTE_ARRAY_BASE = U.arrayBaseOffset(byte[].class);

        private volatile byte byteValue;
        private volatile short shortValue;
        private volatile int intValue;
        private volatile long longValue;

        static {
            try {
                BYTE_OFFSET = U.objectFieldOffset(TestWrapper.class.getDeclaredField("byteValue"));
                SHORT_OFFSET = U.objectFieldOffset(TestWrapper.class.getDeclaredField("shortValue"));
                INT_OFFSET = U.objectFieldOffset(TestWrapper.class.getDeclaredField("intValue"));
                LONG_OFFSET = U.objectFieldOffset(TestWrapper.class.getDeclaredField("longValue"));
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        public static boolean casByte(TestWrapper holder, byte expected, byte update) {
            return U.compareAndSetByte(holder, BYTE_OFFSET, expected, update);
        }

        public static boolean casByteAtOffset(byte[] array, long offset,
                                              byte expected, byte update) {
            return U.compareAndSetByte(array, offset, expected, update);
        }

        public static boolean casShort(TestWrapper holder, short expected, short update) {
            return U.compareAndSetShort(holder, SHORT_OFFSET, expected, update);
        }

        public static boolean casShortAtOffset(byte[] array, long offset,
                                               short expected, short update) {
            return U.compareAndSetShort(array, offset, expected, update);
        }

        public static boolean casInt(TestWrapper holder, int expected, int update) {
            return U.compareAndSetInt(holder, INT_OFFSET, expected, update);
        }

        public static boolean casLong(TestWrapper holder, long expected, long update) {
            return U.compareAndSetLong(holder, LONG_OFFSET, expected, update);
        }

        public static boolean casIntNative(long address, int expected, int update) {
            return U.compareAndSetInt(null, address, expected, update);
        }

        public static boolean casLongNative(long address, long expected, long update) {
            return U.compareAndSetLong(null, address, expected, update);
        }

        public static boolean casIntDynamic(Object base, long offset, int expected, int update) {
            return U.compareAndSetInt(base, offset, expected, update);
        }

        public static boolean casByteNative(long address, byte expected, byte update) {
            return U.compareAndSetByte(null, address, expected, update);
        }

        public static boolean casShortNative(long address, short expected, short update) {
            return U.compareAndSetShort(null, address, expected, update);
        }

        public static boolean nullReceiverByte(Unsafe unsafe, TestWrapper holder) {
            return unsafe.compareAndSetByte(holder, BYTE_OFFSET, (byte) 0, (byte) 1);
        }

        public static boolean nullReceiverShort(Unsafe unsafe, TestWrapper holder) {
            return unsafe.compareAndSetShort(holder, SHORT_OFFSET, (short) 0, (short) 1);
        }

        public static boolean nullReceiverInt(Unsafe unsafe, TestWrapper holder) {
            return unsafe.compareAndSetInt(holder, INT_OFFSET, 0, 1);
        }

        public static boolean nullReceiverLong(Unsafe unsafe, TestWrapper holder) {
            return unsafe.compareAndSetLong(holder, LONG_OFFSET, 0L, 1L);
        }

        public static void main(String[] args) {
            testObjectByte();
            testObjectShort();
            testArrayNeighborProtection();
            testObjectInt();
            testObjectLong();
            testConcurrentAtomicity();
            testNativeAddress();
            testDynamicBase();
            testNullReceiver();
            System.out.println("TestUnsafeCompareAndSet PASSED");
        }

        private static void testObjectByte() {
            TestWrapper holder = new TestWrapper();
            check(casByte(holder, (byte) 0, (byte) 42), "byte CAS success");
            check(holder.byteValue == 42, "byte CAS update");
            check(!casByte(holder, (byte) 0, (byte) 7), "byte CAS failure");
            check(holder.byteValue == 42, "failed byte CAS must not update");
            check(casByte(holder, (byte) 42, Byte.MIN_VALUE), "byte MIN_VALUE update");
            check(casByte(holder, Byte.MIN_VALUE, Byte.MAX_VALUE), "byte MAX_VALUE update");
            check(casByte(holder, Byte.MAX_VALUE, (byte) -1), "byte negative expected");
        }

        private static void testObjectShort() {
            TestWrapper holder = new TestWrapper();
            check(casShort(holder, (short) 0, (short) 42), "short CAS success");
            check(holder.shortValue == 42, "short CAS update");
            check(!casShort(holder, (short) 0, (short) 7), "short CAS failure");
            check(holder.shortValue == 42, "failed short CAS must not update");
            check(casShort(holder, (short) 42, Short.MIN_VALUE), "short MIN_VALUE update");
            check(casShort(holder, Short.MIN_VALUE, Short.MAX_VALUE), "short MAX_VALUE update");
            check(casShort(holder, Short.MAX_VALUE, (short) -1), "short negative expected");
        }

        private static void testArrayNeighborProtection() {
            long wordAligned = (BYTE_ARRAY_BASE + 3) & ~3L;

            byte[] byteArray = new byte[8];
            long byteOffset = wordAligned + 1;
            U.putByte(byteArray, byteOffset - 1, (byte) 0x5A);
            U.putByte(byteArray, byteOffset, (byte) 0x12);
            U.putByte(byteArray, byteOffset + 1, (byte) 0x6B);
            check(casByteAtOffset(byteArray, byteOffset, (byte) 0x12, (byte) 0x34),
                    "array byte CAS success");
            check(!casByteAtOffset(byteArray, byteOffset, (byte) 0x12, (byte) 0x56),
                    "array byte CAS failure");
            check(U.getByte(byteArray, byteOffset) == (byte) 0x34,
                    "array byte CAS target");
            check(U.getByte(byteArray, byteOffset - 1) == (byte) 0x5A
                            && U.getByte(byteArray, byteOffset + 1) == (byte) 0x6B,
                    "array byte CAS corrupted neighbor");

            byte[] shortArray = new byte[8];
            long shortOffset = wordAligned + 2;
            U.putByte(shortArray, shortOffset - 1, (byte) 0x5A);
            U.putShort(shortArray, shortOffset, (short) 0x1234);
            U.putByte(shortArray, shortOffset + 2, (byte) 0x6B);
            check(casShortAtOffset(shortArray, shortOffset,
                            (short) 0x1234, (short) 0x5678),
                    "array aligned short CAS success");
            check(!casShortAtOffset(shortArray, shortOffset,
                            (short) 0x1234, (short) 0x789A),
                    "array aligned short CAS failure");
            check(U.getShort(shortArray, shortOffset) == (short) 0x5678,
                    "array aligned short CAS target");
            check(U.getByte(shortArray, shortOffset - 1) == (byte) 0x5A
                            && U.getByte(shortArray, shortOffset + 2) == (byte) 0x6B,
                    "array aligned short CAS corrupted neighbor");
        }

        private static void testObjectInt() {
            TestWrapper holder = new TestWrapper();
            check(casInt(holder, 0, 42), "int CAS success");
            check(holder.intValue == 42, "int CAS update");
            check(!casInt(holder, 0, 7), "int CAS failure");
            check(holder.intValue == 42, "failed int CAS must not update");
            check(casInt(holder, 42, Integer.MIN_VALUE), "int MIN_VALUE update");
            check(casInt(holder, Integer.MIN_VALUE, Integer.MAX_VALUE), "int MAX_VALUE update");
        }

        private static void testObjectLong() {
            TestWrapper holder = new TestWrapper();
            check(casLong(holder, 0L, 42L), "long CAS success");
            check(holder.longValue == 42L, "long CAS update");
            check(!casLong(holder, 0L, 7L), "long CAS failure");
            check(holder.longValue == 42L, "failed long CAS must not update");
            check(casLong(holder, 42L, Long.MIN_VALUE), "long MIN_VALUE update");
            check(casLong(holder, Long.MIN_VALUE, Long.MAX_VALUE), "long MAX_VALUE update");
        }

        private static void testConcurrentAtomicity() {
            final int threadCount = 4;
            final int iterations = 20_000;

            TestWrapper intHolder = new TestWrapper();
            runWorkers(threadCount, () -> incrementInt(intHolder, iterations));
            check(intHolder.intValue == threadCount * iterations,
                    "concurrent int CAS lost an update");

            TestWrapper longHolder = new TestWrapper();
            runWorkers(threadCount, () -> incrementLong(longHolder, iterations));
            check(longHolder.longValue == (long) threadCount * iterations,
                    "concurrent long CAS lost an update");
        }

        private static void incrementInt(TestWrapper holder, int iterations) {
            for (int i = 0; i < iterations; i++) {
                boolean updated = false;
                for (int attempt = 0; attempt < MAX_CAS_ATTEMPTS; attempt++) {
                    int current = holder.intValue;
                    if (casInt(holder, current, current + 1)) {
                        updated = true;
                        break;
                    }
                    Thread.onSpinWait();
                }
                check(updated, "concurrent int CAS exceeded retry limit");
            }
        }

        private static void incrementLong(TestWrapper holder, int iterations) {
            for (int i = 0; i < iterations; i++) {
                boolean updated = false;
                for (int attempt = 0; attempt < MAX_CAS_ATTEMPTS; attempt++) {
                    long current = holder.longValue;
                    if (casLong(holder, current, current + 1)) {
                        updated = true;
                        break;
                    }
                    Thread.onSpinWait();
                }
                check(updated, "concurrent long CAS exceeded retry limit");
            }
        }

        private static void runWorkers(int count, Runnable task) {
            Thread[] threads = new Thread[count];
            AtomicReference<Throwable> failure = new AtomicReference<>();
            for (int i = 0; i < count; i++) {
                threads[i] = new Thread(() -> {
                    try {
                        task.run();
                    } catch (Throwable t) {
                        failure.compareAndSet(null, t);
                    }
                });
                threads[i].setDaemon(true);
                threads[i].start();
            }
            long deadline = System.nanoTime() + WORKER_TIMEOUT_NANOS;
            for (Thread thread : threads) {
                try {
                    long remaining = deadline - System.nanoTime();
                    if (remaining > 0L) {
                        thread.join(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining)));
                    }
                } catch (InterruptedException e) {
                    interruptWorkers(threads);
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("interrupted while waiting for CAS workers", e);
                }
            }
            for (Thread thread : threads) {
                if (thread.isAlive()) {
                    interruptWorkers(threads);
                    throw new RuntimeException("CAS workers timed out");
                }
            }
            Throwable thrown = failure.get();
            if (thrown != null) {
                throw new RuntimeException("CAS worker failed", thrown);
            }
        }

        private static void interruptWorkers(Thread[] threads) {
            for (Thread thread : threads) {
                thread.interrupt();
            }
            for (Thread thread : threads) {
                try {
                    thread.join(1_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        private static void testNativeAddress() {
            long address = U.allocateMemory(16);
            try {
                U.putByte(address, (byte) 11);
                check(casByteNative(address, (byte) 11, (byte) 22), "native byte CAS success");
                check(U.getByte(address) == 22, "native byte CAS update");
                check(!casByteNative(address, (byte) 11, (byte) 33), "native byte CAS failure");

                long shortAddress = address + 2;
                U.putShort(shortAddress, (short) 1111);
                check(casShortNative(shortAddress, (short) 1111, (short) 2222),
                        "native short CAS success");
                check(U.getShort(shortAddress) == 2222, "native short CAS update");
                check(!casShortNative(shortAddress, (short) 1111, (short) 3333),
                        "native short CAS failure");

                long intAddress = address + 4;
                U.putInt(intAddress, 11);
                check(casIntNative(intAddress, 11, 22), "native int CAS success");
                check(U.getInt(intAddress) == 22, "native int CAS update");
                check(!casIntNative(intAddress, 11, 33), "native int CAS failure");

                long longAddress = address + 8;
                U.putLong(longAddress, 44L);
                check(casLongNative(longAddress, 44L, 55L), "native long CAS success");
                check(U.getLong(longAddress) == 55L, "native long CAS update");
                check(!casLongNative(longAddress, 44L, 66L), "native long CAS failure");
            } finally {
                U.freeMemory(address);
            }
        }

        private static void testDynamicBase() {
            TestWrapper holder = new TestWrapper();
            check(casIntDynamic(holder, INT_OFFSET, 0, 11), "dynamic heap int CAS success");
            check(holder.intValue == 11, "dynamic heap int CAS update");

            long address = U.allocateMemory(Integer.BYTES);
            try {
                U.putInt(address, 11);
                check(casIntDynamic(null, address, 11, 22), "dynamic native int CAS success");
                check(U.getInt(address) == 22, "dynamic native int CAS update");
            } finally {
                U.freeMemory(address);
            }
        }

        private static void testNullReceiver() {
            TestWrapper holder = new TestWrapper();
            try {
                nullReceiverByte(null, holder);
                throw new RuntimeException("null Unsafe receiver did not throw for byte CAS");
            } catch (NullPointerException expected) {
                // Expected.
            }
            try {
                nullReceiverShort(null, holder);
                throw new RuntimeException("null Unsafe receiver did not throw for short CAS");
            } catch (NullPointerException expected) {
                // Expected.
            }
            try {
                nullReceiverInt(null, holder);
                throw new RuntimeException("null Unsafe receiver did not throw for int CAS");
            } catch (NullPointerException expected) {
                // Expected.
            }
            try {
                nullReceiverLong(null, holder);
                throw new RuntimeException("null Unsafe receiver did not throw for long CAS");
            } catch (NullPointerException expected) {
                // Expected.
            }
        }

        private static void check(boolean condition, String message) {
            if (!condition) {
                throw new RuntimeException(message);
            }
        }
    }
}
