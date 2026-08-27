/*
 * Copyright (c) 2026, the Jeandle-JDK Authors. All Rights Reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 */

/*
 * @test id=narrow
 * @summary Verify Unsafe.getAndSetByte/Short accepts raw non-canonical int update values
 * @modules java.base/jdk.internal.misc java.base/jdk.internal.org.objectweb.asm
 * @library /test/lib /
 * @build compiler.jeandle.fileCheck.FileCheck
 * @run driver compiler.jeandle.intrinsic.TestUnsafeGetAndSetNarrow
 */

package compiler.jeandle.intrinsic;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import compiler.jeandle.fileCheck.FileCheck;
import jdk.internal.misc.Unsafe;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.MethodVisitor;
import jdk.internal.org.objectweb.asm.Opcodes;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestUnsafeGetAndSetNarrow implements Opcodes {
    private static final Unsafe U = Unsafe.getUnsafe();
    private static final String BYTE_INTRINSIC_LOG =
            "Method `virtual jbyte jdk.internal.misc.Unsafe.getAndSetByte"
                    + "(jobject, jlong, jbyte)` is parsed as intrinsic";
    private static final String SHORT_INTRINSIC_LOG =
            "Method `virtual jshort jdk.internal.misc.Unsafe.getAndSetShort"
                    + "(jobject, jlong, jshort)` is parsed as intrinsic";

    private static final class Holder {
        byte byteValue;
        short shortValue;
    }

    private static final long BYTE_OFFSET = U.objectFieldOffset(Holder.class, "byteValue");
    private static final long SHORT_OFFSET = U.objectFieldOffset(Holder.class, "shortValue");

    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            runSemantics();
            return;
        }
        runCase(true);
        runCase(false);
    }

    private static void runCase(boolean intrinsicEnabled) throws Exception {
        Path dumpPath = Files.createTempDirectory(intrinsicEnabled
                ? "jeandle_getset_narrow_enabled_ir" : "jeandle_getset_narrow_disabled_ir");
        List<String> command = new ArrayList<>(List.of(
                "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
                "--add-exports=java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED",
                "-Xbatch", "-Xcomp", "-XX:-TieredCompilation", "-XX:-BackgroundCompilation",
                "-XX:+UseJeandleCompiler", "-Xlog:jeandle=debug,jit+compilation=debug",
                "-XX:CompileCommand=compileonly,GeneratedNarrowGetAndSet::*",
                "-XX:+UnlockDiagnosticVMOptions", "-XX:+CIPrintCompilerName",
                "-XX:+JeandleDumpIR", "-XX:JeandleDumpDirectory=" + dumpPath));
        if (!intrinsicEnabled) {
            command.add("-XX:ControlIntrinsic=-_getAndSetByte,-_getAndSetShort");
        }
        command.add(TestUnsafeGetAndSetNarrow.class.getName());
        command.add("child");
        OutputAnalyzer output = ProcessTools.executeCommand(
                ProcessTools.createLimitedTestJavaProcessBuilder(command));
        output.shouldHaveExitValue(0);
        if (intrinsicEnabled) {
            output.shouldContain(BYTE_INTRINSIC_LOG).shouldContain(SHORT_INTRINSIC_LOG);
            checkInstalledByJeandle(output, "applyByte");
            checkInstalledByJeandle(output, "applyShort");
        } else {
            output.shouldNotContain(BYTE_INTRINSIC_LOG).shouldNotContain(SHORT_INTRINSIC_LOG);
        }
        checkIR(dumpPath, intrinsicEnabled);
    }

    private static void checkInstalledByJeandle(OutputAnalyzer output, String method) {
        output.shouldMatch("(?s).*Jeandle:.*GeneratedNarrowGetAndSet::" + method + ".*");
    }

    private static void checkIR(Path dumpPath, boolean intrinsicEnabled) throws Exception {
        Class<?> generated = new Loader().define(makeClass());
        FileCheck byteCheck = new FileCheck(dumpPath.toString(),
                generated.getMethod("applyByte", Unsafe.class, Object.class, long.class, int.class), false);
        FileCheck shortCheck = new FileCheck(dumpPath.toString(),
                generated.getMethod("applyShort", Unsafe.class, Object.class, long.class, int.class), false);
        if (intrinsicEnabled) {
            byteCheck.checkPatternAnywhere("trunc i32 .* to i8");
            byteCheck.checkPatternAnywhere("atomicrmw xchg ptr addrspace\\(1\\).* i8 .* seq_cst");
            byteCheck.checkPatternAnywhere("inttoptr i64 .* to ptr");
            byteCheck.checkPatternAnywhere(
                    "atomicrmw xchg ptr (?!addrspace\\(1\\)).* i8 .* seq_cst");
            byteCheck.checkPatternAnywhere("sext i8 .* to i32");
            shortCheck.checkPatternAnywhere("trunc i32 .* to i16");
            shortCheck.checkPatternAnywhere("atomicrmw xchg ptr addrspace\\(1\\).* i16 .* seq_cst");
            shortCheck.checkPatternAnywhere("inttoptr i64 .* to ptr");
            shortCheck.checkPatternAnywhere(
                    "atomicrmw xchg ptr (?!addrspace\\(1\\)).* i16 .* seq_cst");
            shortCheck.checkPatternAnywhere("sext i16 .* to i32");
        } else {
            byteCheck.checkNotPattern("atomicrmw xchg");
            shortCheck.checkNotPattern("atomicrmw xchg");
        }
    }

    private static void runSemantics() throws Exception {
        Class<?> generated = new Loader().define(makeClass());
        Method byteApply = generated.getMethod(
                "applyByte", Unsafe.class, Object.class, long.class, int.class);
        Method shortApply = generated.getMethod(
                "applyShort", Unsafe.class, Object.class, long.class, int.class);
        Holder holder = new Holder();
        U.putByte(holder, BYTE_OFFSET, (byte) -7);
        check((int) byteApply.invoke(null, U, holder, BYTE_OFFSET, 0x0001_0001) == -7,
                "byte old value");
        check(U.getByteVolatile(holder, BYTE_OFFSET) == 1, "byte low-width update");
        check((int) byteApply.invoke(null, U, holder, BYTE_OFFSET, 0x1234_5600) == 1,
                "byte old value zero update");
        check(U.getByteVolatile(holder, BYTE_OFFSET) == 0, "byte zero low-width update");
        long memory = U.allocateMemory(8);
        try {
            long byteAddress = memory;
            long shortAddress = (memory + 1) & ~1L;
            U.putByte(null, byteAddress, (byte) -9);
            check((int) byteApply.invoke(null, U, null, byteAddress, 0x0001_0001) == -9,
                    "native byte old value");
            check(U.getByteVolatile(null, byteAddress) == 1, "native byte low-width update");
            U.putShort(null, shortAddress, (short) -13);
            check((int) shortApply.invoke(null, U, null, shortAddress, 0x0001_0001) == -13,
                    "native short old value");
            check(U.getShortVolatile(null, shortAddress) == 1, "native short low-width update");
        } finally {
            U.freeMemory(memory);
        }
        U.putShort(holder, SHORT_OFFSET, (short) -11);
        check((int) shortApply.invoke(null, U, holder, SHORT_OFFSET, 0x0001_0001) == -11,
                "short old value");
        check(U.getShortVolatile(holder, SHORT_OFFSET) == 1, "short low-width update");
        check((int) shortApply.invoke(null, U, holder, SHORT_OFFSET, 0x1234_0000) == 1,
                "short old value zero update");
        check(U.getShortVolatile(holder, SHORT_OFFSET) == 0, "short zero low-width update");
    }

    private static byte[] makeClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL, "GeneratedNarrowGetAndSet", null, "java/lang/Object", null);
        makeApply(cw, "applyByte", "(Ljava/lang/Object;JB)B");
        makeApply(cw, "applyShort", "(Ljava/lang/Object;JS)S");
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void makeApply(ClassWriter cw, String name, String descriptor) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name,
                "(Ljdk/internal/misc/Unsafe;Ljava/lang/Object;JI)I", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(LLOAD, 2);
        mv.visitVarInsn(ILOAD, 4);
        mv.visitMethodInsn(INVOKEVIRTUAL, "jdk/internal/misc/Unsafe",
                "getAndSet" + name.substring(5), descriptor, false);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class Loader extends ClassLoader {
        Class<?> define(byte[] bytes) {
            return defineClass(null, bytes, 0, bytes.length);
        }
    }
}
