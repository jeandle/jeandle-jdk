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
 * @summary Test raw JVM int inputs to Unsafe.compareAndExchangeByte/Short descriptors
 * @modules java.base/jdk.internal.misc java.base/jdk.internal.org.objectweb.asm
 * @library /test/lib /
 * @build compiler.jeandle.fileCheck.FileCheck
 * @run main/othervm compiler.jeandle.intrinsic.TestUnsafeCompareAndExchangeNarrow
 */

package compiler.jeandle.intrinsic;

import compiler.jeandle.fileCheck.FileCheck;
import java.io.File;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import jdk.internal.misc.Unsafe;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.MethodVisitor;
import jdk.internal.org.objectweb.asm.Opcodes;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestUnsafeCompareAndExchangeNarrow implements Opcodes {
    private static final String PACKAGE_INTERNAL = "compiler/jeandle/intrinsic/";
    private static final String BYTE_CLASS = "RawByteCaxCall";
    private static final String SHORT_CLASS = "RawShortCaxCall";
    private static final String SUPPORT_INTERNAL = PACKAGE_INTERNAL
            + "TestUnsafeCompareAndExchangeNarrow$RawNarrowState";
    private static final String HOLDER_DESC = "L" + PACKAGE_INTERNAL
            + "TestUnsafeCompareAndExchangeNarrow$Holder;";
    private static final String BYTE_LOG =
            "Method `virtual jbyte jdk.internal.misc.Unsafe.compareAndExchangeByte"
                    + "(jobject, jlong, jbyte, jbyte)` is parsed as intrinsic";
    private static final String SHORT_LOG =
            "Method `virtual jshort jdk.internal.misc.Unsafe.compareAndExchangeShort"
                    + "(jobject, jlong, jshort, jshort)` is parsed as intrinsic";

    public static void main(String[] args) throws Exception {
        runCase(true);
        runCase(false);
    }

    private static void runCase(boolean enabled) throws Exception {
        Path classRoot = Files.createTempDirectory(
                enabled ? "jeandle_raw_narrow_cax_enabled_classes"
                        : "jeandle_raw_narrow_cax_disabled_classes");
        Path packageDir = classRoot.resolve(PACKAGE_INTERNAL);
        Files.createDirectories(packageDir);
        Files.write(packageDir.resolve(BYTE_CLASS + ".class"), generate(
                PACKAGE_INTERNAL + BYTE_CLASS, "compareAndExchangeByte",
                "(Ljava/lang/Object;JBB)B", "BYTE_OFFSET"));
        Files.write(packageDir.resolve(SHORT_CLASS + ".class"), generate(
                PACKAGE_INTERNAL + SHORT_CLASS, "compareAndExchangeShort",
                "(Ljava/lang/Object;JSS)S", "SHORT_OFFSET"));

        String dumpPath = Files.createTempDirectory(
                enabled ? "jeandle_raw_narrow_cax_enabled_ir"
                        : "jeandle_raw_narrow_cax_disabled_ir").toString();
        String byteClassName = PACKAGE_INTERNAL.replace('/', '.') + BYTE_CLASS;
        String shortClassName = PACKAGE_INTERNAL.replace('/', '.') + SHORT_CLASS;
        String classPath = System.getProperty("java.class.path") + File.pathSeparator + classRoot;
        ArrayList<String> command = new ArrayList<>(List.of(
                "--add-exports", "java.base/jdk.internal.misc=ALL-UNNAMED",
                "-cp", classPath,
                "-Xbatch", "-Xcomp", "-XX:-TieredCompilation",
                "-XX:-BackgroundCompilation", "-XX:+UseJeandleCompiler",
                "-XX:+UnlockDiagnosticVMOptions",
                "-XX:+JeandleDumpIR", "-XX:JeandleDumpDirectory=" + dumpPath,
                "-Xlog:jeandle=debug,jit+compilation=debug",
                "-XX:+CIPrintCompilerName",
                "-XX:CompileCommand=compileonly," + byteClassName + "::apply",
                "-XX:CompileCommand=compileonly," + shortClassName + "::apply",
                "-XX:CompileCommand=exclude," + byteClassName + "::resolve",
                "-XX:CompileCommand=exclude," + shortClassName + "::resolve"));
        if (!enabled) {
            command.add("-XX:ControlIntrinsic=-_compareAndExchangeByte,-_compareAndExchangeShort");
        }
        command.add(TestWrapper.class.getName());

        OutputAnalyzer output = ProcessTools.executeCommand(
                ProcessTools.createLimitedTestJavaProcessBuilder(command));
        output.shouldHaveExitValue(0)
                .shouldContain("TestUnsafeCompareAndExchangeNarrow PASSED");
        if (enabled) {
            output.shouldContain(BYTE_LOG).shouldContain(SHORT_LOG);
            checkInstalledByJeandle(output, byteClassName);
            checkInstalledByJeandle(output, shortClassName);
        } else {
            output.shouldNotContain(BYTE_LOG).shouldNotContain(SHORT_LOG);
        }

        try (URLClassLoader loader = new URLClassLoader(
                new URL[] { classRoot.toUri().toURL() },
                TestUnsafeCompareAndExchangeNarrow.class.getClassLoader())) {
            Class<?> byteClass = Class.forName(byteClassName, false, loader);
            Class<?> shortClass = Class.forName(shortClassName, false, loader);
            FileCheck byteCheck = new FileCheck(dumpPath,
                    byteClass.getMethod("apply", int.class, int.class), false);
            FileCheck shortCheck = new FileCheck(dumpPath,
                    shortClass.getMethod("apply", int.class, int.class), false);
            if (enabled) {
                byteCheck.checkPattern("trunc i32 %0 to i8");
                byteCheck.checkPattern("trunc i32 %1 to i8");
                byteCheck.checkPattern(
                        "cmpxchg ptr addrspace\\(1\\).*i8.*seq_cst seq_cst, align 1");
                byteCheck.checkPattern("sext i8 .* to i32");
                shortCheck.checkPattern("trunc i32 %0 to i16");
                shortCheck.checkPattern("trunc i32 %1 to i16");
                shortCheck.checkPattern(
                        "cmpxchg ptr addrspace\\(1\\).*i16.*seq_cst seq_cst, align 2");
                shortCheck.checkPattern("sext i16 .* to i32");
            } else {
                byteCheck.checkNotPattern("cmpxchg ptr addrspace\\(1\\).*i8");
                shortCheck.checkNotPattern("cmpxchg ptr addrspace\\(1\\).*i16");
                byteCheck.checkPattern("invoke .*Unsafe_compareAndExchangeByte");
                shortCheck.checkPattern("invoke .*Unsafe_compareAndExchangeShort");
            }
        }
    }

    private static void checkInstalledByJeandle(OutputAnalyzer output, String className) {
        output.shouldMatch("(?s).*Jeandle:.*" + className.replace(".", "\\.")
                + "::apply.*");
    }

    private static byte[] generate(String className, String targetMethod,
                                   String targetDescriptor, String offsetField) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES
                | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_FINAL, className, null, "java/lang/Object", null);
        emitCall(writer, ACC_PUBLIC | ACC_STATIC, "apply", "(II)I",
                targetMethod, targetDescriptor, offsetField, true);
        emitCall(writer, ACC_PUBLIC | ACC_STATIC, "resolve", "()V",
                targetMethod, targetDescriptor, offsetField, false);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void emitCall(ClassWriter writer, int access, String name, String descriptor,
                                 String targetMethod, String targetDescriptor,
                                 String offsetField, boolean rawArguments) {
        MethodVisitor method = writer.visitMethod(access, name, descriptor, null, null);
        method.visitCode();
        method.visitFieldInsn(GETSTATIC, SUPPORT_INTERNAL, "U", "Ljdk/internal/misc/Unsafe;");
        method.visitFieldInsn(GETSTATIC, SUPPORT_INTERNAL, "HOLDER", HOLDER_DESC);
        method.visitFieldInsn(GETSTATIC, SUPPORT_INTERNAL, offsetField, "J");
        if (rawArguments) {
            // Deliberately omit i2b/i2s. The verifier permits an arbitrary JVM
            // int computational value at these narrow descriptor arguments.
            method.visitVarInsn(ILOAD, 0);
            method.visitVarInsn(ILOAD, 1);
        } else {
            method.visitInsn(ICONST_0);
            method.visitInsn(ICONST_0);
        }
        method.visitMethodInsn(INVOKEVIRTUAL, "jdk/internal/misc/Unsafe", targetMethod,
                targetDescriptor, false);
        if (rawArguments) {
            method.visitInsn(IRETURN);
        } else {
            method.visitInsn(POP);
            method.visitInsn(RETURN);
        }
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    public static class Holder {
        volatile byte byteValue;
        volatile short shortValue;
    }

    public static class RawNarrowState {
        public static final Unsafe U = Unsafe.getUnsafe();
        public static final Holder HOLDER = new Holder();
        public static final long BYTE_OFFSET;
        public static final long SHORT_OFFSET;

        static {
            try {
                BYTE_OFFSET = U.objectFieldOffset(Holder.class.getDeclaredField("byteValue"));
                SHORT_OFFSET = U.objectFieldOffset(Holder.class.getDeclaredField("shortValue"));
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }
    }

    public static class TestWrapper {
        private static final int[] BOUNDARIES = {
                0, 1, -1, Byte.MIN_VALUE, Byte.MAX_VALUE,
                Short.MIN_VALUE, Short.MAX_VALUE, 0x0000_8000, 0x0001_0000,
                0x1234_5678, 0x7fff_0000, 0xffff_0000,
                Integer.MIN_VALUE, Integer.MAX_VALUE
        };

        public static void main(String[] args) throws Throwable {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            Class<?> byteClass = Class.forName(PACKAGE_INTERNAL.replace('/', '.') + BYTE_CLASS);
            Class<?> shortClass = Class.forName(PACKAGE_INTERNAL.replace('/', '.') + SHORT_CLASS);
            lookup.findStatic(byteClass, "resolve", MethodType.methodType(void.class)).invokeExact();
            lookup.findStatic(shortClass, "resolve", MethodType.methodType(void.class)).invokeExact();
            MethodHandle byteCall = lookup.findStatic(byteClass, "apply",
                    MethodType.methodType(int.class, int.class, int.class));
            MethodHandle shortCall = lookup.findStatic(shortClass, "apply",
                    MethodType.methodType(int.class, int.class, int.class));

            Random random = new Random(0xcafe_5eedL);
            for (int i = 0; i < 20_000; i++) {
                checkByte(byteCall, random.nextInt(), random.nextInt());
                checkShort(shortCall, random.nextInt(), random.nextInt());
            }
            for (int value : BOUNDARIES) {
                checkByte(byteCall, value, ~value);
                checkShort(shortCall, value, value ^ 0x5a5a_a5a5);
            }
            System.out.println("TestUnsafeCompareAndExchangeNarrow PASSED");
        }

        private static void checkByte(MethodHandle call, int expected, int update)
                throws Throwable {
            Unsafe u = RawNarrowState.U;
            Holder holder = RawNarrowState.HOLDER;
            byte expectedByte = (byte) expected;
            byte updateByte = (byte) update;
            u.putByteVolatile(holder, RawNarrowState.BYTE_OFFSET, expectedByte);
            int observed = (int) call.invokeExact(expected, update);
            check(observed == expectedByte, "raw byte old value was not sign-extended");
            check(u.getByteVolatile(holder, RawNarrowState.BYTE_OFFSET) == updateByte,
                    "raw byte expected/update were not truncated");

            byte different = (byte) (expectedByte + 1);
            u.putByteVolatile(holder, RawNarrowState.BYTE_OFFSET, different);
            observed = (int) call.invokeExact(expected, update);
            check(observed == different, "failed raw byte CAX returned wrong old value");
            check(u.getByteVolatile(holder, RawNarrowState.BYTE_OFFSET) == different,
                    "failed raw byte CAX updated memory");
        }

        private static void checkShort(MethodHandle call, int expected, int update)
                throws Throwable {
            Unsafe u = RawNarrowState.U;
            Holder holder = RawNarrowState.HOLDER;
            short expectedShort = (short) expected;
            short updateShort = (short) update;
            u.putShortVolatile(holder, RawNarrowState.SHORT_OFFSET, expectedShort);
            int observed = (int) call.invokeExact(expected, update);
            check(observed == expectedShort, "raw short old value was not sign-extended");
            check(u.getShortVolatile(holder, RawNarrowState.SHORT_OFFSET) == updateShort,
                    "raw short expected/update were not truncated");

            short different = (short) (expectedShort + 1);
            u.putShortVolatile(holder, RawNarrowState.SHORT_OFFSET, different);
            observed = (int) call.invokeExact(expected, update);
            check(observed == different, "failed raw short CAX returned wrong old value");
            check(u.getShortVolatile(holder, RawNarrowState.SHORT_OFFSET) == different,
                    "failed raw short CAX updated memory");
        }

        private static void check(boolean condition, String message) {
            if (!condition) {
                throw new AssertionError(message);
            }
        }
    }
}
