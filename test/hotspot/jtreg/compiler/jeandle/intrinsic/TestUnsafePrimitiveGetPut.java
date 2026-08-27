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
 * @summary Verify Unsafe primitive get/put semantics and Jeandle lowering
 * @modules java.base/jdk.internal.misc java.base/jdk.internal.org.objectweb.asm
 * @library /test/lib /
 * @build compiler.jeandle.fileCheck.FileCheck
 * @run driver TestUnsafePrimitiveGetPut
 */

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import compiler.jeandle.fileCheck.FileCheck;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.MethodVisitor;
import jdk.internal.org.objectweb.asm.Opcodes;
import jdk.internal.misc.Unsafe;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestUnsafePrimitiveGetPut {
    private static final Unsafe U = Unsafe.getUnsafe();
    private static final Method RAW_BOOLEAN_PUT = createRawBooleanPut();
    private static final Holder CROSS_METHOD_HOLDER = new Holder();

    private static final class Holder {
        boolean booleanValue;
        byte byteValue;
        short shortValue;
        char charValue;
        int intValue;
        long longValue;
        float floatValue;
        double doubleValue;
    }

    private static final long BOOLEAN_OFFSET = U.objectFieldOffset(Holder.class, "booleanValue");
    private static final long BYTE_OFFSET = U.objectFieldOffset(Holder.class, "byteValue");
    private static final long SHORT_OFFSET = U.objectFieldOffset(Holder.class, "shortValue");
    private static final long CHAR_OFFSET = U.objectFieldOffset(Holder.class, "charValue");
    private static final long INT_OFFSET = U.objectFieldOffset(Holder.class, "intValue");
    private static final long LONG_OFFSET = U.objectFieldOffset(Holder.class, "longValue");
    private static final long FLOAT_OFFSET = U.objectFieldOffset(Holder.class, "floatValue");
    private static final long DOUBLE_OFFSET = U.objectFieldOffset(Holder.class, "doubleValue");

    // Kept volatile so -Xcomp must compile both paths, while the raw zero
    // address is never dereferenced at run time.
    private static volatile boolean zeroAddressBranch;
    private static volatile int crossMethodAddressMode;
    private static volatile long crossMethodRawAddress;

    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            if (args[0].equals("cross")) {
                runCrossMethodZeroAddressSemantics();
                return;
            }
            runSemantics();
            return;
        }
        runCase("enabled", true, null);
        runCase("control_intrinsic_disabled", false,
                "-XX:ControlIntrinsic=-_getBoolean,-_getByte,-_getShort,-_getChar,"
                        + "-_getInt,-_getLong,-_getFloat,-_getDouble,-_putBoolean,-_putByte,"
                        + "-_putShort,-_putChar,-_putInt,-_putLong,-_putFloat,-_putDouble");
        runCase("inline_unsafe_ops_disabled", false, "-XX:-InlineUnsafeOps");
        runCase("inline_natives_disabled", false, "-XX:-InlineNatives");
        runCrossMethodZeroAddressCase();
    }

    private static void runCase(String name, boolean intrinsicEnabled,
                                String additionalVmOption) throws Exception {
        Path dumpPath = Files.createTempDirectory("jeandle_unsafe_plain_" + name + "_ir");
        List<String> command = new ArrayList<>(List.of(
                "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
                "--add-exports=java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED",
                "-Xbatch", "-Xcomp", "-XX:-TieredCompilation", "-XX:-BackgroundCompilation",
                "-XX:+UseJeandleCompiler", "-Xlog:jeandle=debug,jit+compilation=debug",
                "-XX:CompileCommand=compileonly,TestUnsafePrimitiveGetPut::*",
                "-XX:CompileCommand=compileonly,RawBooleanPut::put",
                "-XX:CompileCommand=dontinline,TestUnsafePrimitiveGetPut::*",
                "-XX:CompileCommand=dontinline,RawBooleanPut::put",
                "-XX:+UnlockDiagnosticVMOptions", "-XX:+CIPrintCompilerName",
                "-XX:+JeandleDumpIR", "-XX:+JeandleDumpObjects",
                "-XX:JeandleDumpDirectory=" + dumpPath));
        if (additionalVmOption != null) {
            command.add(additionalVmOption);
        }
        command.add(TestUnsafePrimitiveGetPut.class.getName());
        command.add("child");

        OutputAnalyzer output = ProcessTools.executeCommand(
                ProcessTools.createLimitedTestJavaProcessBuilder(command));
        output.shouldHaveExitValue(0);
        if (intrinsicEnabled) {
            checkInstalledByJeandle(output, "getBoolean");
            checkInstalledByJeandle(output, "getByte");
            checkInstalledByJeandle(output, "getShort");
            checkInstalledByJeandle(output, "getChar");
            checkInstalledByJeandle(output, "getInt");
            checkInstalledByJeandle(output, "getLong");
            checkInstalledByJeandle(output, "getFloat");
            checkInstalledByJeandle(output, "getDouble");
            checkInstalledByJeandle(output, "putBoolean");
            checkInstalledByJeandle(output, "putByte");
            checkInstalledByJeandle(output, "putShort");
            checkInstalledByJeandle(output, "putChar");
            checkInstalledByJeandle(output, "putInt");
            checkInstalledByJeandle(output, "putLong");
            checkInstalledByJeandle(output, "putFloat");
            checkInstalledByJeandle(output, "putDouble");
            checkInstalledByJeandle(output, "compileZeroAddressBranch");
            checkInstalledByJeandle(output, "compileLateZeroAddressBranch");
            checkInstalledByJeandle(output, "RawBooleanPut", "put");
        }
        checkIntrinsicIR(dumpPath, intrinsicEnabled);
        if (intrinsicEnabled) {
            checkZeroAddressIR(dumpPath);
            checkLateZeroAddressIR(dumpPath);
            checkZeroAddressObject(dumpPath);
        }
        checkRawBooleanPutIR(dumpPath, intrinsicEnabled);
    }

    private static void checkInstalledByJeandle(OutputAnalyzer output, String method) {
        output.shouldMatch("(?s).*Jeandle:.*TestUnsafePrimitiveGetPut::" + method + ".*");
    }

    private static void checkInstalledByJeandle(OutputAnalyzer output, String className, String method) {
        output.shouldMatch("(?s).*Jeandle:.*" + className + "::" + method + ".*");
    }

    private static void checkIntrinsicIR(Path dumpPath, boolean intrinsicEnabled) throws Exception {
        checkGetIR(dumpPath, "getBoolean", boolean.class, "i8", intrinsicEnabled);
        checkGetIR(dumpPath, "getByte", byte.class, "i8", intrinsicEnabled);
        checkGetIR(dumpPath, "getShort", short.class, "i16", intrinsicEnabled);
        checkGetIR(dumpPath, "getChar", char.class, "i16", intrinsicEnabled);
        checkGetIR(dumpPath, "getInt", int.class, "i32", intrinsicEnabled);
        checkGetIR(dumpPath, "getLong", long.class, "i64", intrinsicEnabled);
        checkGetIR(dumpPath, "getFloat", float.class, "float", intrinsicEnabled);
        checkGetIR(dumpPath, "getDouble", double.class, "double", intrinsicEnabled);
        checkPutIR(dumpPath, "putBoolean", boolean.class, "i8", intrinsicEnabled);
        checkPutIR(dumpPath, "putByte", byte.class, "i8", intrinsicEnabled);
        checkPutIR(dumpPath, "putShort", short.class, "i16", intrinsicEnabled);
        checkPutIR(dumpPath, "putChar", char.class, "i16", intrinsicEnabled);
        checkPutIR(dumpPath, "putInt", int.class, "i32", intrinsicEnabled);
        checkPutIR(dumpPath, "putLong", long.class, "i64", intrinsicEnabled);
        checkPutIR(dumpPath, "putFloat", float.class, "float", intrinsicEnabled);
        checkPutIR(dumpPath, "putDouble", double.class, "double", intrinsicEnabled);
    }

    private static void checkGetIR(Path dumpPath, String method, Class<?> type,
                                   String llvmType, boolean intrinsicEnabled) throws Exception {
        FileCheck check = new FileCheck(dumpPath.toString(),
                TestUnsafePrimitiveGetPut.class.getDeclaredMethod(method, Object.class, long.class),
                false);
        if (intrinsicEnabled) {
            String typeName = method.substring("get".length()).toLowerCase(Locale.ROOT);
            String prefix = "unsafe_plain_get_" + typeName;
            check.checkPattern(prefix + "_heap_value = load " + llvmType + ", ptr addrspace\\(1\\)");
            check.checkPattern(prefix + "_raw_address = inttoptr i64 .* to ptr");
            check.checkPattern(prefix + "_raw_value = load " + llvmType + ", ptr");
        } else {
            check.checkNotPattern("unsafe_plain_");
        }
    }

    private static void checkPutIR(Path dumpPath, String method, Class<?> type,
                                   String llvmType, boolean intrinsicEnabled) throws Exception {
        FileCheck check = new FileCheck(dumpPath.toString(),
                TestUnsafePrimitiveGetPut.class.getDeclaredMethod(method, Object.class, long.class, type),
                false);
        if (intrinsicEnabled) {
            String typeName = method.substring("put".length()).toLowerCase(Locale.ROOT);
            String prefix = "unsafe_plain_put_" + typeName;
            check.checkPattern(prefix + "_heap:");
            check.checkPattern("store " + llvmType + " .* ptr addrspace\\(1\\)");
            check.checkPattern(prefix + "_raw_address = inttoptr i64 .* to ptr");
            check.checkPattern("store " + llvmType + " .* ptr %" + prefix + "_raw_address");
        } else {
            check.checkNotPattern("unsafe_plain_");
        }
    }

    private static void checkZeroAddressIR(Path dumpPath) throws Exception {
        FileCheck check = new FileCheck(dumpPath.toString(),
                TestUnsafePrimitiveGetPut.class.getDeclaredMethod("compileZeroAddressBranch"), true);
        check.checkNotPattern("unsafe_plain_");
        check.checkNotPattern("unsafe_get_add_");
        check.checkNotPattern("unsafe_cas_");
        check.checkNotPattern("load i32, ptr null");
        check.checkNotPattern("store i32 .*?, ptr null");
        check.checkNotPattern("atomicrmw add ptr null");
        check.checkNotPattern("cmpxchg ptr null");
    }

    private static void checkLateZeroAddressIR(Path dumpPath) throws Exception {
        FileCheck check = new FileCheck(dumpPath.toString(),
                TestUnsafePrimitiveGetPut.class.getDeclaredMethod("compileLateZeroAddressBranch"), true);
        check.checkNotPattern("unsafe_plain_");
        check.checkNotPattern("unsafe_get_add_");
        check.checkNotPattern("unsafe_cas_");
        check.checkNotPattern("load i32, ptr null");
        check.checkNotPattern("store i32 .*?, ptr null");
        check.checkNotPattern("atomicrmw add ptr null");
        check.checkNotPattern("cmpxchg ptr null");
        check.checkPattern("unsafe_raw_zero_address");
        check.checkPattern("@__llvm_deoptimize");
    }

    private static void checkZeroAddressObject(Path dumpPath) throws Exception {
        Path object;
        try (Stream<Path> files = Files.list(dumpPath)) {
            object = files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith(
                            TestUnsafePrimitiveGetPut.class.getName().replace('.', '_')
                                    + "_compileZeroAddressBranch"))
                    .filter(path -> path.getFileName().toString().endsWith(".o"))
                    .max(Comparator.comparing(path -> path.getFileName().toString()))
                    .orElseThrow(() -> new AssertionError("No object dump for compileZeroAddressBranch"));
        }
        OutputAnalyzer output;
        try {
            output = ProcessTools.executeCommand("objdump", "-dr", object.toString());
        } catch (java.io.IOException e) {
            if (e.getMessage().contains("Cannot run program \"objdump\"")) {
                System.out.println("Skipping object-code check: system objdump is unavailable");
                return;
            }
            throw e;
        }
        output.shouldHaveExitValue(0);
        output.shouldContain("TestUnsafePrimitiveGetPut_compileZeroAddressBranch");
        // A direct getAndAdd/CAS lowering would contain one of these AArch64
        // atomic instructions. Plain accesses are checked in optimized IR,
        // where fallback statepoints and the absence of ptr-null loads/stores
        // are visible before object-call patching.
        output.shouldNotMatch("(?m).*\\b(?:cas(?:a|l|al)?|ldadd\\w*|ld(?:a)?xr\\w*|stl?xr\\w*).*" );
    }

    private static void runCrossMethodZeroAddressCase() throws Exception {
        Path dumpPath = Files.createTempDirectory("jeandle_unsafe_cross_zero_ir");
        List<String> command = new ArrayList<>(List.of(
                "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
                "--add-exports=java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED",
                "-Xbatch", "-Xcomp", "-XX:-TieredCompilation", "-XX:-BackgroundCompilation",
                "-XX:+UseJeandleCompiler", "-Xlog:jeandle=debug,jit+compilation=debug",
                "-XX:CompileCommand=compileonly,TestUnsafePrimitiveGetPut::compileCrossMethodZeroAddressBranch",
                "-XX:CompileCommand=compileonly,TestUnsafePrimitiveGetPut::crossMethodZeroAddressAccess",
                "-XX:CompileCommand=inline,TestUnsafePrimitiveGetPut::crossMethodZeroAddressAccess",
                "-XX:+UnlockDiagnosticVMOptions", "-XX:+CIPrintCompilerName",
                "-XX:+JeandleDumpIR", "-XX:JeandleDumpDirectory=" + dumpPath));
        command.add(TestUnsafePrimitiveGetPut.class.getName());
        command.add("cross");

        OutputAnalyzer output = ProcessTools.executeCommand(
                ProcessTools.createLimitedTestJavaProcessBuilder(command));
        output.shouldHaveExitValue(0);
        checkInstalledByJeandle(output, "compileCrossMethodZeroAddressBranch");

        FileCheck caller = new FileCheck(dumpPath.toString(),
                TestUnsafePrimitiveGetPut.class.getDeclaredMethod("compileCrossMethodZeroAddressBranch"), true);
        caller.checkPattern("unsafe_raw_zero_address");
        caller.checkPattern("@__llvm_deoptimize");
        // Prove that both independent operations use a raw pointer without
        // depending on optimizer-generated SSA names or block order.
        caller.checkPatternAnywhere("atomicrmw add ptr (?!addrspace\\()");
        caller.checkPatternAnywhere("cmpxchg ptr (?!addrspace\\()");
        caller.checkNotPattern("load i32, ptr null");
        caller.checkNotPattern("store i32 .*?, ptr null");
        caller.checkNotPattern("atomicrmw add ptr null");
        caller.checkNotPattern("cmpxchg ptr null");
    }

    private static void checkRawBooleanPutIR(Path dumpPath, boolean intrinsicEnabled) throws Exception {
        FileCheck check = new FileCheck(dumpPath.toString(), RAW_BOOLEAN_PUT, false);
        if (intrinsicEnabled) {
            check.checkPattern("unsafe_plain_put_boolean_value = trunc i32 .* to i8");
            check.checkPattern("unsafe_plain_put_boolean_canonical = and i8 .*?, 1");
            check.checkPattern("store i8 %unsafe_plain_put_boolean_canonical, ptr addrspace\\(1\\)");
            check.checkPattern("store i8 %unsafe_plain_put_boolean_canonical, ptr %unsafe_plain_put_boolean_raw_address");
        } else {
            check.checkNotPattern("unsafe_plain_");
        }
    }

    private static void runSemantics() {
        heapAccesses();
        rawAccesses();
        rawBooleanArguments();
        receiverNull();
        concurrentAccesses();
        compileZeroAddressBranch();
        compileLateZeroAddressBranch();
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static boolean getBoolean(Object base, long offset) { return U.getBoolean(base, offset); }
    private static byte getByte(Object base, long offset) { return U.getByte(base, offset); }
    private static short getShort(Object base, long offset) { return U.getShort(base, offset); }
    private static char getChar(Object base, long offset) { return U.getChar(base, offset); }
    private static int getInt(Object base, long offset) { return U.getInt(base, offset); }
    private static long getLong(Object base, long offset) { return U.getLong(base, offset); }
    private static float getFloat(Object base, long offset) { return U.getFloat(base, offset); }
    private static double getDouble(Object base, long offset) { return U.getDouble(base, offset); }

    private static void putBoolean(Object base, long offset, boolean value) { U.putBoolean(base, offset, value); }
    private static void putByte(Object base, long offset, byte value) { U.putByte(base, offset, value); }
    private static void putShort(Object base, long offset, short value) { U.putShort(base, offset, value); }
    private static void putChar(Object base, long offset, char value) { U.putChar(base, offset, value); }
    private static void putInt(Object base, long offset, int value) { U.putInt(base, offset, value); }
    private static void putLong(Object base, long offset, long value) { U.putLong(base, offset, value); }
    private static void putFloat(Object base, long offset, float value) { U.putFloat(base, offset, value); }
    private static void putDouble(Object base, long offset, double value) { U.putDouble(base, offset, value); }

    private static void heapAccesses() {
        Holder holder = new Holder();
        putBoolean(holder, BOOLEAN_OFFSET, true);
        check(getBoolean(holder, BOOLEAN_OFFSET), "heap boolean");
        putByte(holder, BYTE_OFFSET, (byte) -101);
        check(getByte(holder, BYTE_OFFSET) == (byte) -101, "heap byte sign extension");
        putShort(holder, SHORT_OFFSET, (short) -30001);
        check(getShort(holder, SHORT_OFFSET) == (short) -30001, "heap short sign extension");
        putChar(holder, CHAR_OFFSET, '\uffee');
        check(getChar(holder, CHAR_OFFSET) == '\uffee', "heap char zero extension");
        putInt(holder, INT_OFFSET, 0x89abcdef);
        check(getInt(holder, INT_OFFSET) == 0x89abcdef, "heap int");
        putLong(holder, LONG_OFFSET, 0x0123456789abcdefL);
        check(getLong(holder, LONG_OFFSET) == 0x0123456789abcdefL, "heap long");
        putFloat(holder, FLOAT_OFFSET, Float.intBitsToFloat(0x7fc01234));
        check(Float.floatToRawIntBits(getFloat(holder, FLOAT_OFFSET)) == 0x7fc01234, "heap float");
        putDouble(holder, DOUBLE_OFFSET, Double.longBitsToDouble(0x7ff8000012345678L));
        check(Double.doubleToRawLongBits(getDouble(holder, DOUBLE_OFFSET)) == 0x7ff8000012345678L,
                "heap double");

        U.putByte(holder, BOOLEAN_OFFSET, (byte) 2);
        check(getBoolean(holder, BOOLEAN_OFFSET), "non-canonical boolean load");
    }

    private static Method createRawBooleanPut() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                "RawBooleanPut", null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "put",
                "(Ljdk/internal/misc/Unsafe;Ljava/lang/Object;JI)V", null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitVarInsn(Opcodes.ALOAD, 1);
        method.visitVarInsn(Opcodes.LLOAD, 2);
        method.visitVarInsn(Opcodes.ILOAD, 4);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "jdk/internal/misc/Unsafe", "putBoolean",
                "(Ljava/lang/Object;JZ)V", false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();

        try {
            Class<?> rawClass = MethodHandles.lookup().defineClass(writer.toByteArray());
            return rawClass.getDeclaredMethod("put", Unsafe.class, Object.class, long.class, int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static void rawBooleanPut(Object base, long offset, int value) {
        try {
            RAW_BOOLEAN_PUT.invoke(null, U, base, offset, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("raw boolean put invocation failed", e);
        }
    }

    private static void checkRawBooleanValue(Object base, long offset, int value, String path) {
        rawBooleanPut(base, offset, value);
        int expectedByte = value & 1;
        int actualByte = U.getByte(base, offset) & 0xff;
        check(actualByte == expectedByte,
                path + " boolean raw byte for " + value + ": " + actualByte);
        check(getBoolean(base, offset) == (expectedByte != 0),
                path + " boolean readback for " + value);
    }

    private static void rawBooleanArguments() {
        int[] values = {2, 3, -1};
        Holder holder = new Holder();
        for (int value : values) {
            U.putByte(holder, BOOLEAN_OFFSET, (byte) 0);
            checkRawBooleanValue(holder, BOOLEAN_OFFSET, value, "heap");
        }

        long address = U.allocateMemory(1);
        try {
            for (int value : values) {
                U.putByte(null, address, (byte) 0);
                checkRawBooleanValue(null, address, value, "raw");
            }
        } finally {
            U.freeMemory(address);
        }
    }

    private static long alignUp(long address, int alignment) {
        return (address + alignment - 1) & -alignment;
    }

    private static void rawAccesses() {
        long memory = U.allocateMemory(64);
        try {
            long booleanAddress = memory;
            long byteAddress = memory + 1;
            long shortAddress = alignUp(memory + 2, 2);
            long charAddress = alignUp(memory + 4, 2);
            long intAddress = alignUp(memory + 8, 4);
            long floatAddress = alignUp(memory + 12, 4);
            long longAddress = alignUp(memory + 16, 8);
            long doubleAddress = alignUp(memory + 24, 8);
            putBoolean(null, booleanAddress, true);
            check(getBoolean(null, booleanAddress), "raw boolean");
            putByte(null, byteAddress, (byte) -37);
            check(getByte(null, byteAddress) == (byte) -37, "raw byte");
            putShort(null, shortAddress, (short) -12003);
            check(getShort(null, shortAddress) == (short) -12003, "raw short");
            putChar(null, charAddress, '\u8bad');
            check(getChar(null, charAddress) == '\u8bad', "raw char");
            putInt(null, intAddress, 0x87654321);
            check(getInt(null, intAddress) == 0x87654321, "raw int");
            putLong(null, longAddress, 0x1020304050607080L);
            check(getLong(null, longAddress) == 0x1020304050607080L, "raw long");
            putFloat(null, floatAddress, -0.0f);
            check(Float.floatToRawIntBits(getFloat(null, floatAddress))
                    == Float.floatToRawIntBits(-0.0f), "raw float");
            putDouble(null, doubleAddress, Double.longBitsToDouble(0x8000000000000000L));
            check(Double.doubleToRawLongBits(getDouble(null, doubleAddress)) == 0x8000000000000000L,
                    "raw double");
        } finally {
            U.freeMemory(memory);
        }
    }

    private static void receiverNull() {
        try {
            getIntOn(null, new Holder(), INT_OFFSET);
            throw new AssertionError("expected NullPointerException");
        } catch (NullPointerException expected) {
            // Expected: generic invoke lowering performs the receiver null check.
        }
    }

    private static int getIntOn(Unsafe unsafe, Object base, long offset) {
        return unsafe.getInt(base, offset);
    }

    private static void concurrentAccesses() {
        final int threadCount = 8;
        final int iterations = 10_000;
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> workers = new ArrayList<>();

        for (int threadIndex = 0; threadIndex < threadCount; threadIndex++) {
            final int workerId = threadIndex;
            Thread worker = new Thread(() -> {
                Holder holder = new Holder();
                ready.countDown();
                try {
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new AssertionError("concurrent Unsafe start timed out");
                    }
                    for (int i = 0; i < iterations; i++) {
                        boolean booleanValue = ((i + workerId) & 1) == 0;
                        byte byteValue = (byte) (i + workerId);
                        short shortValue = (short) (i * 3 + workerId);
                        char charValue = (char) (i + workerId);
                        int intValue = i * 17 + workerId;
                        long longValue = ((long) intValue << 32) ^ i;
                        float floatValue = Float.intBitsToFloat(0x3f800000 | (i & 0x7ffff));
                        double doubleValue = Double.longBitsToDouble(
                                0x3ff0000000000000L | (i & 0xfffffL));

                        putBoolean(holder, BOOLEAN_OFFSET, booleanValue);
                        putByte(holder, BYTE_OFFSET, byteValue);
                        putShort(holder, SHORT_OFFSET, shortValue);
                        putChar(holder, CHAR_OFFSET, charValue);
                        putInt(holder, INT_OFFSET, intValue);
                        putLong(holder, LONG_OFFSET, longValue);
                        putFloat(holder, FLOAT_OFFSET, floatValue);
                        putDouble(holder, DOUBLE_OFFSET, doubleValue);

                        check(getBoolean(holder, BOOLEAN_OFFSET) == booleanValue, "concurrent boolean");
                        check(getByte(holder, BYTE_OFFSET) == byteValue, "concurrent byte");
                        check(getShort(holder, SHORT_OFFSET) == shortValue, "concurrent short");
                        check(getChar(holder, CHAR_OFFSET) == charValue, "concurrent char");
                        check(getInt(holder, INT_OFFSET) == intValue, "concurrent int");
                        check(getLong(holder, LONG_OFFSET) == longValue, "concurrent long");
                        check(Float.floatToRawIntBits(getFloat(holder, FLOAT_OFFSET))
                                == Float.floatToRawIntBits(floatValue), "concurrent float");
                        check(Double.doubleToRawLongBits(getDouble(holder, DOUBLE_OFFSET))
                                == Double.doubleToRawLongBits(doubleValue), "concurrent double");
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            }, "unsafe-plain-" + workerId);
            worker.setDaemon(true);
            workers.add(worker);
            worker.start();
        }

        try {
            check(ready.await(10, TimeUnit.SECONDS), "concurrent Unsafe workers did not start");
            start.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            for (Thread worker : workers) {
                long remaining = deadline - System.nanoTime();
                if (remaining > 0) {
                    TimeUnit.NANOSECONDS.timedJoin(worker, remaining);
                }
                check(!worker.isAlive(), "concurrent Unsafe worker timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("concurrent Unsafe test interrupted", e);
        } finally {
            start.countDown();
            for (Thread worker : workers) {
                if (worker.isAlive()) {
                    worker.interrupt();
                }
            }
        }
        Throwable thrown = failure.get();
        if (thrown != null) {
            throw new AssertionError("concurrent Unsafe worker failed", thrown);
        }
    }

    private static void compileZeroAddressBranch() {
        if (zeroAddressBranch) {
            int value = U.getInt(null, 0L);
            U.putInt(null, 0L, value);
            int old = U.getAndAddInt(null, 0L, 1);
            U.compareAndSetInt(null, 0L, old, 1);
        }
    }

    private static void compileLateZeroAddressBranch() {
        boolean execute = zeroAddressBranch;
        long offset = execute ? 0L : 1L;
        if (execute) {
            int value = U.getInt(null, offset);
            U.putInt(null, offset, value);
            int old = U.getAndAddInt(null, offset, 1);
            U.compareAndSetInt(null, offset, old, 1);
        }
    }

    private static void compileCrossMethodZeroAddressBranch() {
        int mode = crossMethodAddressMode;
        Object base = mode == 0 ? CROSS_METHOD_HOLDER : null;
        long offset = mode == 0 ? INT_OFFSET
                : (mode == 1 ? 0L : crossMethodRawAddress);
        crossMethodZeroAddressAccess(base, offset);
    }

    private static void runCrossMethodZeroAddressSemantics() {
        crossMethodAddressMode = 0;
        CROSS_METHOD_HOLDER.intValue = 0;
        compileCrossMethodZeroAddressBranch();
        check(CROSS_METHOD_HOLDER.intValue == 1, "cross-method non-zero access");

        long address = U.allocateMemory(Integer.BYTES);
        try {
            U.putInt(null, address, 0);
            crossMethodRawAddress = address;
            crossMethodAddressMode = 2;
            compileCrossMethodZeroAddressBranch();
            check(U.getInt(null, address) == 1, "cross-method raw non-zero access");
        } finally {
            crossMethodAddressMode = 0;
            U.freeMemory(address);
        }
    }

    private static void crossMethodZeroAddressAccess(Object base, long offset) {
        int value = U.getInt(base, offset);
        U.putInt(base, offset, value);
        int old = U.getAndAddInt(base, offset, 1);
        U.compareAndSetInt(base, offset, old, 1);
    }
}
