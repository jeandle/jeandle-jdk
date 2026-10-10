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
 * @summary Test Jeandle Unsafe plain, opaque, release, and volatile reference stores
 * @requires (os.arch=="amd64" | os.arch=="x86_64" | os.arch=="aarch64")
 *           & os.family=="linux"
 * @modules java.base/jdk.internal.misc
 *          java.base/jdk.internal.org.objectweb.asm
 * @library /test/lib /
 * @build compiler.jeandle.fileCheck.FileCheck jdk.test.lib.Asserts
 *        jdk.test.lib.ByteCodeLoader
 * @run main/othervm compiler.jeandle.intrinsic.TestUnsafeReferenceStores
 */

package compiler.jeandle.intrinsic;

import compiler.jeandle.fileCheck.FileCheck;
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

public class TestUnsafeReferenceStores {
    private static final String[] IDS = {
            "_putReference", "_putReferenceOpaque",
            "_putReferenceRelease", "_putReferenceVolatile"
    };

    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            TestMethods.runSemantics();
            System.out.println("REFERENCE STORE TEST PASSED");
            return;
        }
        runEnabled("-XX:+UseG1GC");
        runEnabled("-XX:+UseG1GC", "-XX:-UseCompressedOops");
        runEnabled("-XX:+UseSerialGC");
        runDisabled(false);
        runDisabled(true);
    }

    private static void runEnabled(String... gcFlags) throws Exception {
        String dumpPath = Files.createTempDirectory("unsafe_reference_stores_on").toString();
        OutputAnalyzer output = runChild(dumpPath, List.of(gcFlags), false, false);
        output.shouldHaveExitValue(0).shouldContain("REFERENCE STORE TEST PASSED");
        checkLogs(output.getOutput(), true);
        boolean g1 = List.of(gcFlags).contains("-XX:+UseG1GC");
        boolean compressed = !List.of(gcFlags).contains("-XX:-UseCompressedOops");
        checkEnabledIR(dumpPath, g1, compressed);
        checkDynamicBaseFallback(dumpPath);
    }

    private static void runDisabled(boolean inlineUnsafeOff) throws Exception {
        String dumpPath = Files.createTempDirectory("unsafe_reference_stores_off").toString();
        OutputAnalyzer output = runChild(dumpPath, List.of("-XX:+UseG1GC"),
                                         true, inlineUnsafeOff);
        output.shouldHaveExitValue(0).shouldContain("REFERENCE STORE TEST PASSED");
        checkLogs(output.getOutput(), false);
        for (String method : List.of("plain", "opaque", "release", "vol")) {
            checker(dumpPath, method, Object.class, long.class, Object.class)
                    .checkNot("call hotspotcc void @jeandle.unsafe_put_reference");
        }
    }

    private static OutputAnalyzer runChild(String dumpPath, List<String> gcFlags,
                                           boolean disabled,
                                           boolean inlineUnsafeOff) throws Exception {
        ArrayList<String> command = new ArrayList<>(List.of(
                "--add-exports", "java.base/jdk.internal.misc=ALL-UNNAMED",
                "--add-exports", "java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED",
                "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
                "-XX:+UnlockDiagnosticVMOptions", "-Xlog:jeandle=debug",
                "-XX:+JeandleDumpIR", "-XX:+JeandleDumpObjects",
                "-XX:JeandleDumpDirectory=" + dumpPath,
                "-XX:CompileCommand=compileonly," + TestMethods.class.getName() + "::*",
                "-XX:CompileCommand=compileonly,compiler.jeandle.intrinsic.NullUnsafeReferenceStore::*"));
        command.addAll(gcFlags);
        if (disabled && !inlineUnsafeOff) {
            command.add("-XX:ControlIntrinsic=" + String.join(",", prefix(IDS, "-")));
        }
        if (inlineUnsafeOff) command.add("-XX:-InlineUnsafeOps");
        command.add(TestUnsafeReferenceStores.class.getName());
        command.add("child");
        return ProcessTools.executeCommand(
                ProcessTools.createLimitedTestJavaProcessBuilder(command));
    }

    private static String[] prefix(String[] values, String prefix) {
        String[] result = new String[values.length];
        for (int i = 0; i < values.length; i++) result[i] = prefix + values[i];
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
        checker(dumpPath, "plain", Object.class, long.class, Object.class)
                .checkPattern("store atomic .* unordered");
        checker(dumpPath, "opaque", Object.class, long.class, Object.class)
                .checkPattern("store atomic .* monotonic");
        checker(dumpPath, "opaque", Object.class, long.class, Object.class)
                .checkPattern("fence syncscope\\(\"singlethread\"\\) seq_cst");
        checker(dumpPath, "release", Object.class, long.class, Object.class)
                .checkPattern("store atomic .* release");
        checker(dumpPath, "vol", Object.class, long.class, Object.class)
                .checkPattern("store atomic .* seq_cst");

        FileCheck plain = checker(dumpPath, "plain", Object.class, long.class, Object.class);
        if (g1) {
            plain.checkPattern("call hotspotcc void @jeandle.g1_pre_barrier");
            plain.checkPattern("call hotspotcc void @jeandle.g1_post_barrier");
        } else {
            plain.checkNot("call hotspotcc void @jeandle.g1_pre_barrier");
            plain.checkNot("call hotspotcc void @jeandle.g1_post_barrier");
            plain.checkPattern("call hotspotcc void @jeandle.card_table_barrier");
        }
        FileCheck storage = checker(dumpPath, "plain",
                Object.class, long.class, Object.class);
        if (compressed) {
            storage.checkPattern("store atomic ptr addrspace\\(3\\).* unordered");
        } else {
            storage.checkPattern("store atomic ptr addrspace\\(1\\).* unordered");
        }
    }

    private static void checkDynamicBaseFallback(String dumpPath) throws Exception {
        // `plain` itself receives a dynamically nullable base and therefore
        // proves both the inlined heap arm and the reexecuting raw arm.
        FileCheck dynamic = checker(dumpPath, "plain",
                Object.class, long.class, Object.class);
        dynamic.checkPattern("unsafe_reference_store_raw:");
        dynamic.checkPattern("llvm.experimental.deoptimize");
    }

    static class Holder { Object value; }

    static class TestMethods {
        static final Unsafe U = Unsafe.getUnsafe();
        static final long VALUE_OFFSET;
        static {
            try {
                VALUE_OFFSET = U.objectFieldOffset(Holder.class.getDeclaredField("value"));
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        static void plain(Object base, long offset, Object value) {
            U.putReference(base, offset, value);
        }
        static void opaque(Object base, long offset, Object value) {
            U.putReferenceOpaque(base, offset, value);
        }
        static void release(Object base, long offset, Object value) {
            U.putReferenceRelease(base, offset, value);
        }
        static void vol(Object base, long offset, Object value) {
            U.putReferenceVolatile(base, offset, value);
        }
        static void constantNullZero(Object value) {
            U.putReference(null, 0L, value);
        }
        static void dynamicBase(Object base, long offset, Object value) {
            U.putReference(base, offset, value);
        }

        static void runSemantics() {
            Holder holder = new Holder();
            for (int i = 0; i < 20_000; i++) {
                Object p = new Object();
                plain(holder, VALUE_OFFSET, p);
                Asserts.assertSame(p, U.getReference(holder, VALUE_OFFSET));
                Object o = new Object();
                opaque(holder, VALUE_OFFSET, o);
                Asserts.assertSame(o, U.getReferenceOpaque(holder, VALUE_OFFSET));
                Object r = new Object();
                release(holder, VALUE_OFFSET, r);
                Asserts.assertSame(r, U.getReferenceAcquire(holder, VALUE_OFFSET));
                Object v = new Object();
                vol(holder, VALUE_OFFSET, v);
                Asserts.assertSame(v, U.getReferenceVolatile(holder, VALUE_OFFSET));
            }
            plain(holder, VALUE_OFFSET, null);
            Asserts.assertNull(U.getReference(holder, VALUE_OFFSET));
            dynamicBase(holder, VALUE_OFFSET, null);
            Asserts.assertNull(holder.value);
            try {
                Class<?> raw = ByteCodeLoader.load(
                        "compiler.jeandle.intrinsic.NullUnsafeReferenceStore",
                        NullReceiverClass.generate());
                raw.getMethod("run", Object.class, long.class, Object.class)
                        .invoke(null, holder, VALUE_OFFSET, new Object());
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
            String owner = "compiler/jeandle/intrinsic/NullUnsafeReferenceStore";
            ClassWriter cw = new ClassWriter(0);
            cw.visit(V21, ACC_PUBLIC | ACC_SUPER, owner, null, "java/lang/Object", null);
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "run",
                    "(Ljava/lang/Object;JLjava/lang/Object;)V", null, null);
            mv.visitCode();
            mv.visitInsn(ACONST_NULL);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(LLOAD, 1);
            mv.visitVarInsn(ALOAD, 3);
            mv.visitMethodInsn(INVOKEVIRTUAL, "jdk/internal/misc/Unsafe",
                    "putReference", "(Ljava/lang/Object;JLjava/lang/Object;)V", false);
            mv.visitInsn(RETURN);
            mv.visitMaxs(5, 4);
            mv.visitEnd();
            cw.visitEnd();
            return cw.toByteArray();
        }
    }
}
