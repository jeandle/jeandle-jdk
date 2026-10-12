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
 * @key randomness
 * @summary Test naturally aligned Jeandle Unsafe primitive volatile get/put intrinsics
 * @requires (os.arch=="amd64" | os.arch=="x86_64" | os.arch=="aarch64")
 *           & os.family=="linux"
 * @modules java.base/jdk.internal.misc
 *          java.base/jdk.internal.org.objectweb.asm
 * @library /test/lib /
 * @build jdk.test.lib.Asserts jdk.test.lib.ByteCodeLoader
 * @run main/othervm compiler.jeandle.intrinsic.TestUnsafePrimitiveVolatile
 */

package compiler.jeandle.intrinsic;

import compiler.jeandle.fileCheck.FileCheck;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import jdk.internal.misc.Unsafe;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.MethodVisitor;
import jdk.internal.org.objectweb.asm.Opcodes;
import jdk.test.lib.Asserts;
import jdk.test.lib.ByteCodeLoader;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestUnsafePrimitiveVolatile {
    private static final String RAW_CLASS =
            "compiler.jeandle.intrinsic.RawUnsafePrimitiveVolatile";
    private static final String[] IDS = {
            "_getBooleanVolatile", "_getByteVolatile", "_getShortVolatile",
            "_getCharVolatile", "_getIntVolatile", "_getLongVolatile",
            "_getFloatVolatile", "_getDoubleVolatile",
            "_putBooleanVolatile", "_putByteVolatile", "_putShortVolatile",
            "_putCharVolatile", "_putIntVolatile", "_putLongVolatile",
            "_putFloatVolatile", "_putDoubleVolatile"
    };

    public static void main(String[] args) throws Exception {
        String enabledDump = Files.createTempDirectory("jeandle_unsafe_volatile_on").toString();
        OutputAnalyzer enabled = runChild(enabledDump, true);
        enabled.shouldHaveExitValue(0).shouldContain("TestWrapper PASSED");
        assertIntrinsicLogs(enabled.getOutput(), true);
        checkEnabledIR(enabledDump);
        checkZeroAddressIR(enabledDump);

        String sigbusDump = Files.createTempDirectory("jeandle_unsafe_volatile_sigbus").toString();
        OutputAnalyzer sigbus = runChild(sigbusDump, true, false, "sigbus");
        sigbus.shouldHaveExitValue(0)
                .shouldContain("SIGBUS correctly converted to InternalError");
        Asserts.assertTrue(Pattern.compile("(?m)Unsafe(?:\\.|::)getByteVolatile"
                + ".*is parsed as intrinsic").matcher(sigbus.getOutput()).find(),
                "SIGBUS path did not compile getByteVolatile as an intrinsic");

        String disabledDump = Files.createTempDirectory("jeandle_unsafe_volatile_control_off").toString();
        OutputAnalyzer disabled = runChild(disabledDump, false, false);
        disabled.shouldHaveExitValue(0).shouldContain("TestWrapper PASSED");
        assertIntrinsicLogs(disabled.getOutput(), false);
        checkDisabledIR(disabledDump);

        String inlineUnsafeDisabledDump = Files.createTempDirectory("jeandle_unsafe_volatile_inline_unsafe_off").toString();
        OutputAnalyzer inlineUnsafeDisabled = runChild(inlineUnsafeDisabledDump, false, true);
        inlineUnsafeDisabled.shouldHaveExitValue(0).shouldContain("TestWrapper PASSED");
        assertIntrinsicLogs(inlineUnsafeDisabled.getOutput(), false);
        checkDisabledIR(inlineUnsafeDisabledDump);
    }

    private static OutputAnalyzer runChild(String dumpPath, boolean enabled) throws Exception {
        return runChild(dumpPath, enabled, false, null);
    }

    private static OutputAnalyzer runChild(String dumpPath, boolean enabled, boolean inlineUnsafeOff)
            throws Exception {
        return runChild(dumpPath, enabled, inlineUnsafeOff, null);
    }

    private static OutputAnalyzer runChild(String dumpPath, boolean enabled, boolean inlineUnsafeOff,
                                           String childMode) throws Exception {
        ArrayList<String> args = new ArrayList<>(List.of(
                "--add-exports", "java.base/jdk.internal.misc=ALL-UNNAMED",
                "--add-exports", "java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED",
                "--add-opens", "java.base/java.nio=ALL-UNNAMED",
                "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
                "-XX:+UnlockDiagnosticVMOptions", "-Xlog:jeandle=debug",
                "-XX:+JeandleDumpIR", "-XX:+JeandleDumpObjects",
                "-XX:JeandleDumpDirectory=" + dumpPath,
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::*",
                "-XX:CompileCommand=compileonly," + RAW_CLASS + "::*"));
        if (!enabled && !inlineUnsafeOff) {
            args.add("-XX:ControlIntrinsic=" + String.join(",", prefix(IDS, "-")));
        }
        if (inlineUnsafeOff) {
            args.add("-XX:-InlineUnsafeOps");
        }
        args.add(TestWrapper.class.getName());
        if (childMode != null) {
            args.add(childMode);
        }
        return ProcessTools.executeCommand(ProcessTools.createLimitedTestJavaProcessBuilder(args));
    }

    private static String[] prefix(String[] values, String prefix) {
        String[] result = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = prefix + values[i];
        }
        return result;
    }

    private static void assertIntrinsicLogs(String output, boolean expected) {
        for (String id : IDS) {
            String method = id.substring(1);
            Pattern pattern = Pattern.compile("(?m)Unsafe(?:\\.|::)" + method
                    + ".*is parsed as intrinsic");
            Asserts.assertEquals(expected, pattern.matcher(output).find(),
                    "unexpected intrinsic log state for " + id);
        }
    }

    private static FileCheck checker(String dumpPath, String name, Class<?>... params)
            throws Exception {
        return new FileCheck(dumpPath, TestWrapper.class.getMethod(name, params), false);
    }

    private static void checkEnabledIR(String dumpPath) throws Exception {
        checker(dumpPath, "getBoolean", Object.class, long.class)
                .checkPattern("load atomic i8, ptr addrspace\\(1\\).*seq_cst");
        checker(dumpPath, "getBoolean", Object.class, long.class)
                .checkPattern("icmp ne i8 .*0");
        checker(dumpPath, "getByte", Object.class, long.class)
                .checkPattern("load atomic i8, ptr addrspace\\(1\\).*seq_cst");
        checker(dumpPath, "getShort", Object.class, long.class)
                .checkPattern("load atomic i16, ptr addrspace\\(1\\).*seq_cst");
        checker(dumpPath, "getChar", Object.class, long.class)
                .checkPattern("load atomic i16, ptr addrspace\\(1\\).*seq_cst");
        checker(dumpPath, "getInt", Object.class, long.class)
                .checkPattern("load atomic i32, ptr addrspace\\(1\\).*seq_cst");
        checker(dumpPath, "getLong", Object.class, long.class)
                .checkPattern("load atomic i64, ptr addrspace\\(1\\).*seq_cst");
        checker(dumpPath, "getFloat", Object.class, long.class)
                .checkPattern("load atomic i32, ptr addrspace\\(1\\).*seq_cst");
        checker(dumpPath, "getDouble", Object.class, long.class)
                .checkPattern("load atomic i64, ptr addrspace\\(1\\).*seq_cst");

        checker(dumpPath, "putBoolean", Object.class, long.class, boolean.class)
                .checkPattern("store atomic i8 .*ptr addrspace\\(1\\).*seq_cst");
        checker(dumpPath, "putByte", Object.class, long.class, byte.class)
                .checkPattern("store atomic i8 .*ptr addrspace\\(1\\).*seq_cst");
        checker(dumpPath, "putShort", Object.class, long.class, short.class)
                .checkPattern("store atomic i16 .*ptr addrspace\\(1\\).*seq_cst");
        checker(dumpPath, "putChar", Object.class, long.class, char.class)
                .checkPattern("store atomic i16 .*ptr addrspace\\(1\\).*seq_cst");
        checker(dumpPath, "putInt", Object.class, long.class, int.class)
                .checkPattern("store atomic i32 .*ptr addrspace\\(1\\).*seq_cst");
        checker(dumpPath, "putLong", Object.class, long.class, long.class)
                .checkPattern("store atomic i64 .*ptr addrspace\\(1\\).*seq_cst");
        checker(dumpPath, "putFloat", Object.class, long.class, float.class)
                .checkPattern("store atomic i32 .*ptr addrspace\\(1\\).*seq_cst");
        checker(dumpPath, "putDouble", Object.class, long.class, double.class)
                .checkPattern("store atomic i64 .*ptr addrspace\\(1\\).*seq_cst");

        Class<?> raw = ByteCodeLoader.load(RAW_CLASS, RawClass.generate());
        for (String name : RawClass.PUT_METHODS) {
            Method method = raw.getMethod(name, Unsafe.class, Object.class, long.class, int.class);
            FileCheck rawChecker = new FileCheck(dumpPath, method, false);
            rawChecker.checkPattern("store atomic i(?:8|16) .*seq_cst");
        }
    }

    private static void checkZeroAddressIR(String dumpPath) throws Exception {
        checkZeroAddressIR(dumpPath, "getByteMaybeZero", "Unsafe_getByteVolatile",
                Holder.class);
        checkZeroAddressIR(dumpPath, "getIntMaybeZero", "Unsafe_getIntVolatile",
                Holder.class);
        checkZeroAddressIR(dumpPath, "getLongMaybeZero", "Unsafe_getLongVolatile",
                Holder.class);
        checkZeroAddressIR(dumpPath, "putIntMaybeZero", "Unsafe_putIntVolatile",
                Holder.class, int.class);
        checkZeroAddressIR(dumpPath, "putLongMaybeZero", "Unsafe_putLongVolatile",
                Holder.class, long.class);
    }

    private static void checkZeroAddressIR(String dumpPath, String method,
                                           String fallback, Class<?>... params)
            throws Exception {
        FileCheck checker = new FileCheck(dumpPath,
                TestWrapper.class.getMethod(method, params), false);
        // The compiled-but-unexecuted constant null+0 call must retain normal
        // Unsafe fallback; emitting inttoptr(0) would be undefined LLVM IR.
        checker.checkPattern(fallback);
        checker.checkNotPattern("inttoptr i64 0 to ptr");
    }

    private static void checkDisabledIR(String dumpPath) throws Exception {
        String[] names = {"getBoolean", "getByte", "getShort", "getChar",
                          "getInt", "getLong", "getFloat", "getDouble"};
        for (String name : names) {
            FileCheck check = checker(dumpPath, name, Object.class, long.class);
            check.checkNot("unsafe_volatile_addr");
        }
        checker(dumpPath, "putBoolean", Object.class, long.class, boolean.class)
                .checkNot("unsafe_volatile_addr");
        checker(dumpPath, "putByte", Object.class, long.class, byte.class)
                .checkNot("unsafe_volatile_addr");
        checker(dumpPath, "putShort", Object.class, long.class, short.class)
                .checkNot("unsafe_volatile_addr");
        checker(dumpPath, "putChar", Object.class, long.class, char.class)
                .checkNot("unsafe_volatile_addr");
        checker(dumpPath, "putInt", Object.class, long.class, int.class)
                .checkNot("unsafe_volatile_addr");
        checker(dumpPath, "putLong", Object.class, long.class, long.class)
                .checkNot("unsafe_volatile_addr");
        checker(dumpPath, "putFloat", Object.class, long.class, float.class)
                .checkNot("unsafe_volatile_addr");
        checker(dumpPath, "putDouble", Object.class, long.class, double.class)
                .checkNot("unsafe_volatile_addr");
    }

    public static class TestWrapper {
        private static final Unsafe U = Unsafe.getUnsafe();
        // Compiled but never set: both paths must remain under -Xcomp.
        private static volatile boolean zeroAddressBranch;

        public static boolean getBoolean(Object base, long offset) {
            return U.getBooleanVolatile(base, offset);
        }
        public static byte getByte(Object base, long offset) {
            return U.getByteVolatile(base, offset);
        }
        public static byte getByteMaybeZero(Holder holder) throws Exception {
            if (zeroAddressBranch) return U.getByteVolatile(null, 0L);
            return U.getByteVolatile(holder, offset("b"));
        }
        public static int getIntMaybeZero(Holder holder) throws Exception {
            if (zeroAddressBranch) return U.getIntVolatile(null, 0L);
            return U.getIntVolatile(holder, offset("i"));
        }
        public static long getLongMaybeZero(Holder holder) throws Exception {
            if (zeroAddressBranch) return U.getLongVolatile(null, 0L);
            return U.getLongVolatile(holder, offset("l"));
        }
        public static void putIntMaybeZero(Holder holder, int value) throws Exception {
            if (zeroAddressBranch) U.putIntVolatile(null, 0L, value);
            else U.putIntVolatile(holder, offset("i"), value);
        }
        public static void putLongMaybeZero(Holder holder, long value) throws Exception {
            if (zeroAddressBranch) U.putLongVolatile(null, 0L, value);
            else U.putLongVolatile(holder, offset("l"), value);
        }
        public static short getShort(Object base, long offset) {
            return U.getShortVolatile(base, offset);
        }
        public static char getChar(Object base, long offset) {
            return U.getCharVolatile(base, offset);
        }
        public static int getInt(Object base, long offset) {
            return U.getIntVolatile(base, offset);
        }
        public static long getLong(Object base, long offset) {
            return U.getLongVolatile(base, offset);
        }
        public static float getFloat(Object base, long offset) {
            return U.getFloatVolatile(base, offset);
        }
        public static double getDouble(Object base, long offset) {
            return U.getDoubleVolatile(base, offset);
        }
        public static void putBoolean(Object base, long offset, boolean value) {
            U.putBooleanVolatile(base, offset, value);
        }
        public static void putByte(Object base, long offset, byte value) {
            U.putByteVolatile(base, offset, value);
        }
        public static void putShort(Object base, long offset, short value) {
            U.putShortVolatile(base, offset, value);
        }
        public static void putChar(Object base, long offset, char value) {
            U.putCharVolatile(base, offset, value);
        }
        public static void putInt(Object base, long offset, int value) {
            U.putIntVolatile(base, offset, value);
        }
        public static void putLong(Object base, long offset, long value) {
            U.putLongVolatile(base, offset, value);
        }
        public static void putFloat(Object base, long offset, float value) {
            U.putFloatVolatile(base, offset, value);
        }
        public static void putDouble(Object base, long offset, double value) {
            U.putDoubleVolatile(base, offset, value);
        }

        public static void main(String[] args) throws Exception {
            if (args.length != 0 && args[0].equals("sigbus")) {
                testSigbus();
                return;
            }
            testHeapValues();
            testNativeValues();
            testRawNarrowArguments();
            testMessagePassing();
            Holder zeroGuardHolder = new Holder();
            Asserts.assertEquals((byte) 0, getByteMaybeZero(zeroGuardHolder));
            Asserts.assertEquals(0, getIntMaybeZero(zeroGuardHolder));
            Asserts.assertEquals(0L, getLongMaybeZero(zeroGuardHolder));
            putIntMaybeZero(zeroGuardHolder, 0x12345678);
            putLongMaybeZero(zeroGuardHolder, 0x123456789abcdef0L);
            Asserts.assertEquals(0x12345678, zeroGuardHolder.i);
            Asserts.assertEquals(0x123456789abcdef0L, zeroGuardHolder.l);
            System.out.println("TestWrapper PASSED");
        }

        private static long offset(String name) throws Exception {
            Field field = Holder.class.getDeclaredField(name);
            return U.objectFieldOffset(field);
        }

        private static void testHeapValues() throws Exception {
            Holder h = new Holder();
            long zo = offset("z");
            long bo = offset("b");
            long so = offset("s");
            long co = offset("c");
            long io = offset("i");
            long lo = offset("l");
            long fo = offset("f");
            long d = offset("d");
            Random random = new Random(0x5eed5eedL);
            for (int i = 0; i < 1_000; i++) {
                boolean z = random.nextBoolean();
                byte b = (byte) random.nextInt();
                short s = (short) random.nextInt();
                char c = (char) random.nextInt();
                int n = random.nextInt();
                long l = random.nextLong();
                float f = Float.intBitsToFloat(random.nextInt());
                double x = Double.longBitsToDouble(random.nextLong());

                h.z = z; h.b = b; h.s = s; h.c = c; h.i = n; h.l = l; h.f = f; h.d = x;
                Asserts.assertEquals(z, getBoolean(h, zo));
                Asserts.assertEquals(b, getByte(h, bo));
                Asserts.assertEquals(s, getShort(h, so));
                Asserts.assertEquals(c, getChar(h, co));
                Asserts.assertEquals(n, getInt(h, io));
                Asserts.assertEquals(l, getLong(h, lo));
                Asserts.assertEquals(Float.floatToRawIntBits(f),
                        Float.floatToRawIntBits(getFloat(h, fo)));
                Asserts.assertEquals(Double.doubleToRawLongBits(x),
                        Double.doubleToRawLongBits(getDouble(h, d)));

                putBoolean(h, zo, !z);
                putByte(h, bo, (byte) ~b);
                putShort(h, so, (short) ~s);
                putChar(h, co, (char) ~c);
                putInt(h, io, ~n);
                putLong(h, lo, ~l);
                putFloat(h, fo, Float.intBitsToFloat(~Float.floatToRawIntBits(f)));
                putDouble(h, d, Double.longBitsToDouble(~Double.doubleToRawLongBits(x)));
                Asserts.assertEquals(!z, h.z);
                Asserts.assertEquals((byte) ~b, h.b);
                Asserts.assertEquals((short) ~s, h.s);
                Asserts.assertEquals((char) ~c, h.c);
                Asserts.assertEquals(~n, h.i);
                Asserts.assertEquals(~l, h.l);
                Asserts.assertEquals(~Float.floatToRawIntBits(f), Float.floatToRawIntBits(h.f));
                Asserts.assertEquals(~Double.doubleToRawLongBits(x), Double.doubleToRawLongBits(h.d));
            }
        }

        private static void testNativeValues() {
            long address = U.allocateMemory(16);
            try {
                U.setMemory(address, 16, (byte) 0);
                U.putByte(address, (byte) 2);
                Asserts.assertTrue(getBoolean(null, address), "non-zero native boolean");
                putBoolean(null, address, false);
                Asserts.assertFalse(getBoolean(null, address), "native boolean false");
                putBoolean(null, address, true);
                Asserts.assertTrue(getBoolean(null, address), "native boolean true");
                putByte(null, address, (byte) 0x81);
                Asserts.assertEquals((byte) 0x81, U.getByte(address));
                Asserts.assertEquals((byte) 0x81, getByte(null, address));
                putShort(null, address, (short) 0x8123);
                Asserts.assertEquals((short) 0x8123, U.getShort(address));
                Asserts.assertEquals((short) 0x8123, getShort(null, address));
                putChar(null, address, (char) 0xfedc);
                Asserts.assertEquals((char) 0xfedc, U.getChar(address));
                Asserts.assertEquals((char) 0xfedc, getChar(null, address));
                putInt(null, address, 0x81234567);
                Asserts.assertEquals(0x81234567, U.getInt(address));
                Asserts.assertEquals(0x81234567, getInt(null, address));
                putLong(null, address, 0x8123456789abcdefL);
                Asserts.assertEquals(0x8123456789abcdefL, U.getLong(address));
                Asserts.assertEquals(0x8123456789abcdefL, getLong(null, address));
                putFloat(null, address, Float.intBitsToFloat(0x7fc01234));
                Asserts.assertEquals(0x7fc01234, Float.floatToRawIntBits(U.getFloat(address)));
                Asserts.assertEquals(0x7fc01234,
                        Float.floatToRawIntBits(getFloat(null, address)));
                putDouble(null, address, Double.longBitsToDouble(0x7ff8000012345678L));
                Asserts.assertEquals(0x7ff8000012345678L,
                        Double.doubleToRawLongBits(U.getDouble(address)));
                Asserts.assertEquals(0x7ff8000012345678L,
                        Double.doubleToRawLongBits(getDouble(null, address)));
            } finally {
                U.freeMemory(address);
            }
        }

        private static void testSigbus() throws Exception {
            java.nio.file.Path path = Files.createTempFile("jeandle-unsafe-volatile-", ".bin");
            try {
                Files.write(path, new byte[4096]);
                java.nio.MappedByteBuffer mapping;
                try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(path.toFile(), "rw");
                     java.nio.channels.FileChannel channel = file.getChannel()) {
                    mapping = channel.map(java.nio.channels.FileChannel.MapMode.READ_WRITE, 0, 4096);
                }
                Field addressField = java.nio.Buffer.class.getDeclaredField("address");
                addressField.setAccessible(true);
                long address = (long) addressField.get(mapping);
                try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(path.toFile(), "rw")) {
                    file.setLength(0);
                }
                try {
                    byte value = getByte(null, address);
                    throw new RuntimeException("Expected InternalError, got: " + value);
                } catch (InternalError expected) {
                    System.out.println("SIGBUS correctly converted to InternalError: "
                            + expected.getMessage());
                } finally {
                    java.lang.ref.Reference.reachabilityFence(mapping);
                }
            } finally {
                Files.deleteIfExists(path);
            }
        }

        private static void testRawNarrowArguments() throws Exception {
            Class<?> raw = ByteCodeLoader.load(RAW_CLASS, RawClass.generate());
            Holder h = new Holder();
            long booleanOffset = offset("z");
            // A Z descriptor has JVM int computational input. These values
            // distinguish the required input & 1 from an incorrect byte truncation.
            for (int value : new int[] {2, 3, -1}) {
                invokeRaw(raw, "putBooleanRaw", h, booleanOffset, value);
                Asserts.assertEquals((byte) (value & 1), U.getByte(h, booleanOffset));
                Asserts.assertEquals((value & 1) != 0, h.z);
            }
            long address = U.allocateMemory(8);
            try {
                for (int value : new int[] {2, 3, -1}) {
                    invokeRaw(raw, "putBooleanRaw", null, address, value);
                    Asserts.assertEquals((byte) (value & 1), U.getByte(address));
                    Asserts.assertEquals((value & 1) != 0, getBoolean(null, address));
                }
            } finally {
                U.freeMemory(address);
            }
            int value = 0x1234abcd;
            invokeRaw(raw, "putByteRaw", h, offset("b"), value);
            Asserts.assertEquals((byte) value, h.b);
            invokeRaw(raw, "putShortRaw", h, offset("s"), value);
            Asserts.assertEquals((short) value, h.s);
            invokeRaw(raw, "putCharRaw", h, offset("c"), value);
            Asserts.assertEquals((char) value, h.c);
        }

        private static void invokeRaw(Class<?> raw, String name, Object base,
                                      long offset, int value) throws Exception {
            raw.getMethod(name, Unsafe.class, Object.class, long.class, int.class)
                    .invoke(null, U, base, offset, value);
        }

        private static void testMessagePassing() throws Exception {
            long flagOffset = U.objectFieldOffset(Pair.class.getDeclaredField("flag"));
            for (int i = 0; i < 100; i++) {
                Pair pair = new Pair();
                AtomicReference<Throwable> failure = new AtomicReference<>();
                Thread writer = new Thread(() -> {
                    pair.payload = 42;
                    putByte(pair, flagOffset, (byte) 1);
                });
                Thread reader = new Thread(() -> {
                    try {
                        while (getByte(pair, flagOffset) == 0) {
                            Thread.onSpinWait();
                        }
                        if (pair.payload != 42) {
                            throw new AssertionError("volatile ordering failure");
                        }
                    } catch (Throwable t) {
                        failure.set(t);
                    }
                });
                writer.start();
                reader.start();
                writer.join();
                reader.join();
                if (failure.get() != null) {
                    throw new AssertionError("message-passing worker failed", failure.get());
                }
            }
        }
    }

    static class Holder {
        boolean z;
        byte b;
        short s;
        char c;
        int i;
        long l;
        float f;
        double d;
    }

    static class Pair {
        int payload;
        byte flag;
    }

    static class RawClass implements Opcodes {
        static final String[] PUT_METHODS = {
                "putBooleanRaw", "putByteRaw", "putShortRaw", "putCharRaw"
        };
        private static final String INTERNAL_NAME = RAW_CLASS.replace('.', '/');
        private static final String UNSAFE = "jdk/internal/misc/Unsafe";

        static byte[] generate() {
            ClassWriter cw = new ClassWriter(0);
            cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, INTERNAL_NAME,
                    null, "java/lang/Object", null);
            emitPut(cw, "putBooleanRaw", "putBooleanVolatile", "Z");
            emitPut(cw, "putByteRaw", "putByteVolatile", "B");
            emitPut(cw, "putShortRaw", "putShortVolatile", "S");
            emitPut(cw, "putCharRaw", "putCharVolatile", "C");
            cw.visitEnd();
            return cw.toByteArray();
        }

        private static void emitPut(ClassWriter cw, String methodName,
                                    String unsafeName, String narrowDescriptor) {
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, methodName,
                    "(Ljdk/internal/misc/Unsafe;Ljava/lang/Object;JI)V", null, null);
            mv.visitCode();
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(LLOAD, 2);
            mv.visitVarInsn(ILOAD, 4);
            mv.visitMethodInsn(INVOKEVIRTUAL, UNSAFE, unsafeName,
                    "(Ljava/lang/Object;J" + narrowDescriptor + ")V", false);
            mv.visitInsn(RETURN);
            mv.visitMaxs(5, 5);
            mv.visitEnd();
        }
    }
}
