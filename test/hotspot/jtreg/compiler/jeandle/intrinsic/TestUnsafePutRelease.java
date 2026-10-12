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
 * @summary Verify the jdk.internal.misc.Unsafe put*Release intrinsic family
 * @modules java.base/jdk.internal.misc
 *          java.base/jdk.internal.org.objectweb.asm
 * @library /test/lib /
 * @run main/othervm compiler.jeandle.intrinsic.TestUnsafePutRelease
 */

package compiler.jeandle.intrinsic;

import compiler.jeandle.fileCheck.FileCheck;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import jdk.internal.misc.Unsafe;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.MethodVisitor;
import jdk.internal.org.objectweb.asm.Opcodes;
import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestUnsafePutRelease {
    private static final String[] INTRINSICS = {
            "_putBooleanRelease", "_putByteRelease",
            "_putShortRelease", "_putCharRelease", "_putIntRelease",
            "_putLongRelease", "_putFloatRelease", "_putDoubleRelease"
    };

    public static void main(String[] args) throws Exception {
        runEnabled();
        runDisabled("-XX:ControlIntrinsic=-" + String.join(",-", INTRINSICS));
        runDisabled("-XX:-InlineUnsafeOps");
    }

    private static ArrayList<String> commonCommand() {
        ArrayList<String> command = new ArrayList<>(List.of(
                "--add-exports", "java.base/jdk.internal.misc=ALL-UNNAMED",
                "--add-exports", "java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED",
                "-XX:+UnlockDiagnosticVMOptions",
                "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
                "-Xlog:jeandle=debug",
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::put*",
                "-XX:CompileCommand=compileonly,compiler.jeandle.intrinsic.RawUnsafePutReleaseProbe::raw*",
                "-XX:CompileCommand=exclude,compiler.jeandle.intrinsic.RawUnsafePutReleaseProbe::resolve"));
        return command;
    }

    private static void runEnabled() throws Exception {
        String dumpPath = Files.createTempDirectory("jeandle_put_release").toString();
        ArrayList<String> command = commonCommand();

        command.add("-XX:+JeandleDumpIR");
        command.add("-XX:+JeandleDumpObjects");
        command.add("-XX:JeandleDumpDirectory=" + dumpPath);
        command.add(TestWrapper.class.getName());
        OutputAnalyzer output = ProcessTools.executeCommand(
                ProcessTools.createLimitedTestJavaProcessBuilder(command));
        output.shouldHaveExitValue(0).shouldContain("TestUnsafePutRelease PASSED");
        for (String intrinsic : INTRINSICS) {
            output.shouldMatch("(?s).*Unsafe(?:\\.|::)" + intrinsic.substring(1)
                    + ".*is parsed as intrinsic.*");
        }
        {
            checkIR(dumpPath, "putBooleanRelease", boolean.class, "store atomic i8.*release");
            checkIR(dumpPath, "putByteRelease", byte.class, "store atomic i8.*release");
            checkIR(dumpPath, "putShortRelease", short.class, "store atomic i16.*release");
            checkIR(dumpPath, "putCharRelease", char.class, "store atomic i16.*release");
            checkIR(dumpPath, "putIntRelease", int.class, "store atomic i32.*release");
            checkIR(dumpPath, "putLongRelease", long.class, "store atomic i64.*release");
            checkIR(dumpPath, "putFloatRelease", float.class, "store atomic i32.*release");
            checkIR(dumpPath, "putDoubleRelease", double.class, "store atomic i64.*release");
            checkRawIR(dumpPath, "rawBoolean", "store atomic i8.*release");
            checkRawIR(dumpPath, "rawByte", "store atomic i8.*release");
            checkRawIR(dumpPath, "rawShort", "store atomic i16.*release");
            checkRawIR(dumpPath, "rawChar", "store atomic i16.*release");
            FileCheck zeroRaw = new FileCheck(dumpPath,
                    TestWrapper.class.getDeclaredMethod("putByteMaybeZero", Holder.class), false);
            zeroRaw.checkPattern("jdk_internal_misc_Unsafe_putByteRelease");
            FileCheck zeroOptimized = new FileCheck(dumpPath,
                    TestWrapper.class.getDeclaredMethod("putByteMaybeZero", Holder.class), true);
            zeroOptimized.checkNotPattern("inttoptr i64 0 to ptr");
        }
    }

    private static void checkIR(String dumpPath, String method, Class<?> valueType,
                                String pattern) throws Exception {
        FileCheck check = new FileCheck(dumpPath,
                TestWrapper.class.getDeclaredMethod(method,
                        Object.class, long.class, valueType), false);
        check.checkPattern(pattern);
    }

    private static void checkRawIR(String dumpPath, String method, String pattern)
            throws Exception {
        String prefix = "compiler_jeandle_intrinsic_RawUnsafePutReleaseProbe_" + method;
        List<Path> matches;
        try (var files = Files.list(Path.of(dumpPath))) {
            matches = files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith(prefix))
                    .filter(path -> path.getFileName().toString().endsWith(".ll"))
                    .filter(path -> !path.getFileName().toString().endsWith("_optimized.ll"))
                    .toList();
        }
        Asserts.assertFalse(matches.isEmpty(), "missing raw intrinsic dump for " + method);
        boolean found = false;
        for (Path path : matches) {
            if (Files.readAllLines(path).stream().anyMatch(line -> line.matches(".*" + pattern + ".*"))) {
                found = true;
                break;
            }
        }
        Asserts.assertTrue(found, "raw intrinsic lowering not found for " + method);
    }

    private static void runDisabled(String option) throws Exception {
        ArrayList<String> command = commonCommand();
        command.add(option);
        command.add(TestWrapper.class.getName());
        OutputAnalyzer output = ProcessTools.executeCommand(
                ProcessTools.createLimitedTestJavaProcessBuilder(command));
        output.shouldHaveExitValue(0).shouldContain("TestUnsafePutRelease PASSED");
        for (String intrinsic : INTRINSICS) {
            output.shouldNotContain("Unsafe." + intrinsic.substring(1));
        }
    }

    static final class Holder {
        boolean booleanValue;
        byte byteValue;
        short shortValue;
        char charValue;
        int intValue;
        long longValue;
        float floatValue;
        double doubleValue;
        int data;
        int flag;
    }

    public static final class TestWrapper {
        private static final Unsafe U = Unsafe.getUnsafe();
        private static final long BOOLEAN_OFFSET = offset("booleanValue");
        private static final long BYTE_OFFSET = offset("byteValue");
        private static final long SHORT_OFFSET = offset("shortValue");
        private static final long CHAR_OFFSET = offset("charValue");
        private static final long INT_OFFSET = offset("intValue");
        private static final long LONG_OFFSET = offset("longValue");
        private static final long FLOAT_OFFSET = offset("floatValue");
        private static final long DOUBLE_OFFSET = offset("doubleValue");
        private static final long DATA_OFFSET = offset("data");
        private static final long FLAG_OFFSET = offset("flag");
        // Compiled but never set: static null+zero must preserve native fallback.
        private static volatile boolean zeroAddressBranch;

        public static void main(String[] args) throws Exception {
            testValues();
            testOffHeapPrimitives();
            testRawNarrowInputs();
            testReleaseAcquirePublication();
            putByteMaybeZero(new Holder());
            System.out.println("TestUnsafePutRelease PASSED");
        }

        public static void putBooleanRelease(Object base, long offset, boolean v) {
            U.putBooleanRelease(base, offset, v);
        }
        public static void putByteRelease(Object base, long offset, byte v) {
            U.putByteRelease(base, offset, v);
        }
        public static void putByteMaybeZero(Holder h) {
            if (zeroAddressBranch) U.putByteRelease(null, 0L, (byte) 1);
            else U.putByteRelease(h, BYTE_OFFSET, (byte) 0);
        }
        public static void putShortRelease(Object base, long offset, short v) {
            U.putShortRelease(base, offset, v);
        }
        public static void putCharRelease(Object base, long offset, char v) {
            U.putCharRelease(base, offset, v);
        }
        public static void putIntRelease(Object base, long offset, int v) {
            U.putIntRelease(base, offset, v);
        }
        public static void putLongRelease(Object base, long offset, long v) {
            U.putLongRelease(base, offset, v);
        }
        public static void putFloatRelease(Object base, long offset, float v) {
            U.putFloatRelease(base, offset, v);
        }
        public static void putDoubleRelease(Object base, long offset, double v) {
            U.putDoubleRelease(base, offset, v);
        }

        private static void testValues() {
            Holder h = new Holder();
            putBooleanRelease(h, BOOLEAN_OFFSET, true);
            putByteRelease(h, BYTE_OFFSET, (byte) 0x81);
            putShortRelease(h, SHORT_OFFSET, (short) 0x8123);
            putCharRelease(h, CHAR_OFFSET, (char) 0x9123);
            putIntRelease(h, INT_OFFSET, 0x87654321);
            putLongRelease(h, LONG_OFFSET, 0x8877665544332211L);
            putFloatRelease(h, FLOAT_OFFSET, Float.intBitsToFloat(0x7fc12345));
            putDoubleRelease(h, DOUBLE_OFFSET, Double.longBitsToDouble(0x7ff8123456789abCL));
            Asserts.assertTrue(U.getBoolean(h, BOOLEAN_OFFSET));
            Asserts.assertEQ(U.getByte(h, BYTE_OFFSET), (byte) 0x81);
            Asserts.assertEQ(U.getShort(h, SHORT_OFFSET), (short) 0x8123);
            Asserts.assertEQ(U.getChar(h, CHAR_OFFSET), (char) 0x9123);
            Asserts.assertEQ(U.getInt(h, INT_OFFSET), 0x87654321);
            Asserts.assertEQ(U.getLong(h, LONG_OFFSET), 0x8877665544332211L);
            Asserts.assertEQ(Float.floatToRawIntBits(U.getFloat(h, FLOAT_OFFSET)), 0x7fc12345);
            Asserts.assertEQ(Double.doubleToRawLongBits(U.getDouble(h, DOUBLE_OFFSET)),
                    0x7ff8123456789abCL);
        }

        private static void testOffHeapPrimitives() {
            long address = U.allocateMemory(64);
            try {
                U.setMemory(address, 64, (byte) 0);
                putBooleanRelease(null, address, true);
                putByteRelease(null, address + 1, (byte) 0x81);
                putShortRelease(null, address + 2, (short) 0x8123);
                putCharRelease(null, address + 4, (char) 0x9123);
                putIntRelease(null, address + 8, 0x87654321);
                putLongRelease(null, address + 16, 0x8877665544332211L);
                putFloatRelease(null, address + 24, 1.25f);
                putDoubleRelease(null, address + 32, 1.25d);
                Asserts.assertTrue(U.getBoolean(null, address));
                Asserts.assertEQ(U.getByte(null, address + 1), (byte) 0x81);
                Asserts.assertEQ(U.getShort(null, address + 2), (short) 0x8123);
                Asserts.assertEQ(U.getChar(null, address + 4), (char) 0x9123);
                Asserts.assertEQ(U.getInt(null, address + 8), 0x87654321);
                Asserts.assertEQ(U.getLong(null, address + 16), 0x8877665544332211L);
                Asserts.assertEQ(U.getFloat(null, address + 24), 1.25f);
                Asserts.assertEQ(U.getDouble(null, address + 32), 1.25d);
            } finally {
                U.freeMemory(address);
            }
        }

        private static void testRawNarrowInputs() throws Exception {
            Class<?> probe = MethodHandles.lookup().defineClass(makeRawProbe());
            Holder h = new Holder();
            probe.getDeclaredMethod("resolve", Unsafe.class, Object.class, long.class)
                    .invoke(null, U, h, BYTE_OFFSET);
            for (int raw : new int[] {2, 3, -1}) {
                invokeRaw(probe, "rawBoolean", h, BOOLEAN_OFFSET, raw);
                Asserts.assertEQ(U.getByte(h, BOOLEAN_OFFSET), (byte) (raw & 1));
                Asserts.assertEQ(U.getBoolean(h, BOOLEAN_OFFSET), (raw & 1) != 0);
            }
            long address = U.allocateMemory(8);
            try {
                for (int raw : new int[] {2, 3, -1}) {
                    invokeRaw(probe, "rawBoolean", null, address, raw);
                    Asserts.assertEQ(U.getByte(address), (byte) (raw & 1));
                    Asserts.assertEQ(U.getBoolean(null, address), (raw & 1) != 0);
                }
            } finally {
                U.freeMemory(address);
            }
            int raw = 0x1234abcd;
            invokeRaw(probe, "rawByte", h, BYTE_OFFSET, raw);
            invokeRaw(probe, "rawShort", h, SHORT_OFFSET, raw);
            invokeRaw(probe, "rawChar", h, CHAR_OFFSET, raw);
            Asserts.assertEQ(U.getByte(h, BYTE_OFFSET), (byte) raw);
            Asserts.assertEQ(U.getShort(h, SHORT_OFFSET), (short) raw);
            Asserts.assertEQ(U.getChar(h, CHAR_OFFSET), (char) raw);
        }

        private static void invokeRaw(Class<?> probe, String name, Holder h,
                                      long offset, int raw) throws Exception {
            probe.getDeclaredMethod(name, Unsafe.class, Object.class, long.class, int.class)
                    .invoke(null, U, h, offset, raw);
        }

        private static void testReleaseAcquirePublication() throws Exception {
            Holder h = new Holder();
            final int iterations = 10_000;
            AtomicBoolean stop = new AtomicBoolean();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread writer = new Thread(() -> {
                try {
                    for (int i = 1; i <= iterations; i++) {
                        while (U.getIntVolatile(h, FLAG_OFFSET) != 0 && !stop.get()) {
                            Thread.onSpinWait();
                        }
                        if (stop.get()) {
                            return;
                        }
                        U.putInt(h, DATA_OFFSET, i);
                        putIntRelease(h, FLAG_OFFSET, 1);
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                    stop.set(true);
                    U.putIntVolatile(h, FLAG_OFFSET, 1);
                }
            });
            Thread reader = new Thread(() -> {
                try {
                    for (int i = 1; i <= iterations; i++) {
                        while (U.getIntAcquire(h, FLAG_OFFSET) == 0 && !stop.get()) {
                            Thread.onSpinWait();
                        }
                        if (stop.get()) {
                            return;
                        }
                        int observed = U.getInt(h, DATA_OFFSET);
                        if (observed != i) {
                            throw new AssertionError(
                                    "release/acquire violation: " + observed + " != " + i);
                        }
                        U.putIntVolatile(h, FLAG_OFFSET, 0);
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                    stop.set(true);
                    U.putIntVolatile(h, FLAG_OFFSET, 0);
                }
            });
            writer.start();
            reader.start();
            writer.join();
            reader.join();
            if (failure.get() != null) {
                throw new AssertionError("publication worker failed", failure.get());
            }
        }

        private static long offset(String name) {
            try {
                Field field = Holder.class.getDeclaredField(name);
                return U.objectFieldOffset(field);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        private static byte[] makeRawProbe() {
            String owner = "compiler/jeandle/intrinsic/RawUnsafePutReleaseProbe";
            String unsafe = "jdk/internal/misc/Unsafe";
            ClassWriter cw = new ClassWriter(0);
            cw.visit(Opcodes.V21, Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, owner, null,
                    "java/lang/Object", null);
            MethodVisitor resolve = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                    "resolve", "(Ljdk/internal/misc/Unsafe;Ljava/lang/Object;J)V", null, null);
            resolve.visitCode();
            emitResolveCall(resolve, unsafe, "putBooleanRelease", "Z");
            emitResolveCall(resolve, unsafe, "putByteRelease", "B");
            emitResolveCall(resolve, unsafe, "putShortRelease", "S");
            emitResolveCall(resolve, unsafe, "putCharRelease", "C");
            resolve.visitInsn(Opcodes.RETURN);
            resolve.visitMaxs(5, 4);
            resolve.visitEnd();
            emitRaw(cw, unsafe, "rawBoolean", "putBooleanRelease", "Z");
            emitRaw(cw, unsafe, "rawByte", "putByteRelease", "B");
            emitRaw(cw, unsafe, "rawShort", "putShortRelease", "S");
            emitRaw(cw, unsafe, "rawChar", "putCharRelease", "C");
            cw.visitEnd();
            return cw.toByteArray();
        }

        private static void emitResolveCall(MethodVisitor mv, String unsafe,
                                            String name, String descriptor) {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitVarInsn(Opcodes.LLOAD, 2);
            mv.visitInsn(Opcodes.ICONST_1);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, unsafe, name,
                    "(Ljava/lang/Object;J" + descriptor + ")V", false);
        }

        private static void emitRaw(ClassWriter cw, String unsafe, String methodName,
                                    String targetName, String descriptor) {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                    methodName, "(Ljdk/internal/misc/Unsafe;Ljava/lang/Object;JI)V", null, null);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitVarInsn(Opcodes.LLOAD, 2);
            mv.visitVarInsn(Opcodes.ILOAD, 4);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, unsafe, targetName,
                    "(Ljava/lang/Object;J" + descriptor + ")V", false);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(5, 5);
            mv.visitEnd();
        }
    }
}
