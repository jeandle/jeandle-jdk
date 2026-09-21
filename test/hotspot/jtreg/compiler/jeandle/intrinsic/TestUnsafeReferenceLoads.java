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
 * @summary Test Jeandle Unsafe plain, opaque, acquire, and volatile reference loads
 * @requires (os.arch=="amd64" | os.arch=="x86_64" | os.arch=="aarch64")
 *           & os.family=="linux"
 * @modules java.base/jdk.internal.misc
 *          java.base/jdk.internal.org.objectweb.asm
 * @library /test/lib /
 * @build compiler.jeandle.fileCheck.FileCheck jdk.test.lib.Asserts
 *        jdk.test.lib.ByteCodeLoader
 * @run main/othervm compiler.jeandle.intrinsic.TestUnsafeReferenceLoads
 */

package compiler.jeandle.intrinsic;

import compiler.jeandle.fileCheck.FileCheck;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import jdk.internal.misc.Unsafe;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.MethodVisitor;
import jdk.internal.org.objectweb.asm.Opcodes;
import jdk.test.lib.Asserts;
import jdk.test.lib.ByteCodeLoader;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestUnsafeReferenceLoads {
    private static final String[] IDS = {
            "_getReference", "_getReferenceOpaque",
            "_getReferenceAcquire", "_getReferenceVolatile"
    };

    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            TestMethods.runSemantics();
            System.out.println("REFERENCE LOAD TEST PASSED");
            return;
        }

        runEnabled("-XX:+UseG1GC");
        runEnabled("-XX:+UseG1GC", "-XX:-UseCompressedOops");
        runEnabled("-XX:+UseSerialGC");
        runDisabled(false);
        runDisabled(true);
    }

    private static void runEnabled(String... gcFlags) throws Exception {
        String dumpPath = Files.createTempDirectory("unsafe_reference_loads_on").toString();
        OutputAnalyzer output = runChild(dumpPath, List.of(gcFlags), false, false);
        output.shouldHaveExitValue(0).shouldContain("REFERENCE LOAD TEST PASSED");
        checkLogs(output.getOutput(), true);
        boolean g1 = List.of(gcFlags).contains("-XX:+UseG1GC");
        boolean compressed = !List.of(gcFlags).contains("-XX:-UseCompressedOops");
        checkEnabledIR(dumpPath, g1, compressed);
        checkDynamicBaseFallback(dumpPath);
    }

    private static void runDisabled(boolean inlineUnsafeOff) throws Exception {
        String dumpPath = Files.createTempDirectory("unsafe_reference_loads_off").toString();
        OutputAnalyzer output = runChild(dumpPath, List.of("-XX:+UseG1GC"),
                                         true, inlineUnsafeOff);
        output.shouldHaveExitValue(0).shouldContain("REFERENCE LOAD TEST PASSED");
        checkLogs(output.getOutput(), false);
        for (String method : List.of("plain", "opaque", "acquire", "vol")) {
            checker(dumpPath, method, Object.class, long.class)
                    .checkNot("call hotspotcc ptr addrspace(1) @jeandle.unsafe_get_reference");
        }
    }

    private static OutputAnalyzer runChild(String dumpPath, List<String> gcFlags,
                                           boolean disabled,
                                           boolean inlineUnsafeOff) throws Exception {
        ArrayList<String> command = new ArrayList<>(List.of(
                "--add-exports", "java.base/jdk.internal.misc=ALL-UNNAMED",
                "--add-exports", "java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED",
                "--add-opens", "java.base/java.lang.ref=ALL-UNNAMED",
                "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
                "-XX:+UnlockDiagnosticVMOptions", "-Xlog:jeandle=debug",
                "-XX:+JeandleDumpIR", "-XX:+JeandleDumpObjects",
                "-XX:JeandleDumpDirectory=" + dumpPath,
                "-XX:CompileCommand=compileonly," + TestMethods.class.getName() + "::*",
                "-XX:CompileCommand=compileonly,compiler.jeandle.intrinsic.NullUnsafeReferenceLoad::*"));
        command.addAll(gcFlags);
        if (disabled && !inlineUnsafeOff) {
            command.add("-XX:ControlIntrinsic=" + String.join(",", prefix(IDS, "-")));
        }
        if (inlineUnsafeOff) {
            command.add("-XX:-InlineUnsafeOps");
        }
        command.add(TestUnsafeReferenceLoads.class.getName());
        command.add("child");
        return ProcessTools.executeCommand(
                ProcessTools.createLimitedTestJavaProcessBuilder(command));
    }

    private static String[] prefix(String[] values, String prefix) {
        String[] result = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = prefix + values[i];
        }
        return result;
    }

    private static void checkLogs(String output, boolean expected) {
        for (String id : IDS) {
            Pattern pattern = Pattern.compile("(?m)Unsafe(?:\\.|::)" + id.substring(1)
                    + "[^\\r\\n]*is parsed as intrinsic");
            Asserts.assertEquals(expected, pattern.matcher(output).find(),
                    "unexpected intrinsic log state for " + id);
        }
    }

    private static FileCheck checker(String dumpPath, String method,
                                     Class<?>... params) throws Exception {
        return new FileCheck(dumpPath,
                TestMethods.class.getDeclaredMethod(method, params), false);
    }

    private static void checkEnabledIR(String dumpPath, boolean g1,
                                       boolean compressed) throws Exception {
        checker(dumpPath, "plain", Object.class, long.class)
                .checkPattern("load atomic .* unordered");
        checker(dumpPath, "opaque", Object.class, long.class)
                .checkPattern("load atomic .* monotonic");
        checker(dumpPath, "opaque", Object.class, long.class)
                .checkPattern("fence syncscope\\(\"singlethread\"\\) seq_cst");
        checker(dumpPath, "acquire", Object.class, long.class)
                .checkPattern("load atomic .* acquire");
        checker(dumpPath, "vol", Object.class, long.class)
                .checkPattern("load atomic .* seq_cst");

        FileCheck plain = checker(dumpPath, "plain", Object.class, long.class);
        if (g1) {
            plain.checkPattern("call hotspotcc void @jeandle.g1_pre_barrier_loaded");
        } else {
            plain.checkNot("call hotspotcc void @jeandle.g1_pre_barrier_loaded");
        }
        if (compressed) {
            plain.checkPattern("load atomic ptr addrspace\\(3\\).* unordered");
        } else {
            plain.checkPattern("load atomic ptr addrspace\\(1\\).* unordered");
        }
    }

    private static void checkDynamicBaseFallback(String dumpPath) throws Exception {
        // A compile-time null base makes lowering return false before touching
        // the JVM stack.  That wrapper therefore has no Jeandle artifact.  A
        // dynamically nullable base stays inline on the heap arm and captures
        // a reexecuting uncommon trap for the raw arm.
        FileCheck dynamic = checker(dumpPath, "dynamicBase", Object.class, long.class);
        dynamic.checkPattern("unsafe_reference_load_raw:");
        dynamic.checkPattern("llvm.experimental.deoptimize");
    }

    static class Holder {
        Object value;
    }

    static class TestMethods {
        static final Unsafe U = Unsafe.getUnsafe();
        static final long VALUE_OFFSET;
        static final long REFERENT_OFFSET;

        static {
            try {
                VALUE_OFFSET = U.objectFieldOffset(Holder.class.getDeclaredField("value"));
                Field referent = Reference.class.getDeclaredField("referent");
                REFERENT_OFFSET = U.objectFieldOffset(referent);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        static Object plain(Object base, long offset) {
            return U.getReference(base, offset);
        }

        static Object opaque(Object base, long offset) {
            return U.getReferenceOpaque(base, offset);
        }

        static Object acquire(Object base, long offset) {
            return U.getReferenceAcquire(base, offset);
        }

        static Object vol(Object base, long offset) {
            return U.getReferenceVolatile(base, offset);
        }

        static Object constantNullZero() {
            return U.getReference(null, 0L);
        }

        static Object dynamicBase(Object base, long offset) {
            return U.getReference(base, offset);
        }

        static void runSemantics() {
            Holder holder = new Holder();
            for (int i = 0; i < 20_000; i++) {
                Object marker = new Object();
                U.putReference(holder, VALUE_OFFSET, marker);
                Asserts.assertSame(marker, plain(holder, VALUE_OFFSET));
                Asserts.assertSame(marker, opaque(holder, VALUE_OFFSET));
                Asserts.assertSame(marker, acquire(holder, VALUE_OFFSET));
                Asserts.assertSame(marker, vol(holder, VALUE_OFFSET));
            }
            U.putReference(holder, VALUE_OFFSET, null);
            Asserts.assertNull(plain(holder, VALUE_OFFSET));
            Asserts.assertNull(opaque(holder, VALUE_OFFSET));
            Asserts.assertNull(acquire(holder, VALUE_OFFSET));
            Asserts.assertNull(vol(holder, VALUE_OFFSET));

            Object referent = new Object();
            WeakReference<Object> reference = new WeakReference<>(referent);
            Asserts.assertSame(referent, plain(reference, REFERENT_OFFSET));
            Asserts.assertSame(referent, opaque(reference, REFERENT_OFFSET));
            Asserts.assertSame(referent, acquire(reference, REFERENT_OFFSET));
            Asserts.assertSame(referent, vol(reference, REFERENT_OFFSET));

            // Exercise the non-null arm of a dynamically nullable base.  The
            // null arm is verified in IR to deopt and reexecute the invoke.
            Asserts.assertNull(dynamicBase(holder, VALUE_OFFSET));

            try {
                Class<?> raw = ByteCodeLoader.load(
                        "compiler.jeandle.intrinsic.NullUnsafeReferenceLoad",
                        NullReceiverClass.generate());
                raw.getMethod("run", Object.class, long.class)
                        .invoke(null, holder, VALUE_OFFSET);
                throw new AssertionError("null Unsafe receiver did not throw");
            } catch (java.lang.reflect.InvocationTargetException expected) {
                Asserts.assertTrue(expected.getCause() instanceof NullPointerException,
                        "unexpected null-receiver exception: " + expected.getCause());
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }
    }

    static class NullReceiverClass implements Opcodes {
        static byte[] generate() {
            String owner = "compiler/jeandle/intrinsic/NullUnsafeReferenceLoad";
            ClassWriter cw = new ClassWriter(0);
            cw.visit(V21, ACC_PUBLIC | ACC_SUPER, owner, null,
                     "java/lang/Object", null);
            MethodVisitor mv = cw.visitMethod(
                    ACC_PUBLIC | ACC_STATIC, "run",
                    "(Ljava/lang/Object;J)Ljava/lang/Object;", null, null);
            mv.visitCode();
            mv.visitInsn(ACONST_NULL);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(LLOAD, 1);
            mv.visitMethodInsn(INVOKEVIRTUAL, "jdk/internal/misc/Unsafe",
                    "getReference", "(Ljava/lang/Object;J)Ljava/lang/Object;", false);
            mv.visitInsn(ARETURN);
            mv.visitMaxs(4, 3);
            mv.visitEnd();
            cw.visitEnd();
            return cw.toByteArray();
        }
    }
}
