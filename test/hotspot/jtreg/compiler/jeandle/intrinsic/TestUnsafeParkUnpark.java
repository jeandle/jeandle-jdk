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
 *
 */

/*
 * @test
 * @summary Verify Unsafe.park/unpark semantics, Jeandle lowering, and disabled fallback
 * @modules java.base/jdk.internal.misc
 *          java.base/jdk.internal.org.objectweb.asm
 * @library /test/lib /
 * @build compiler.jeandle.fileCheck.FileCheck
 * @run main/othervm -XX:-TieredCompilation -XX:-UseJeandleCompiler
 *      -XX:CompileCommand=exclude,jdk.internal.org.objectweb.asm.*::* TestUnsafeParkUnpark
 */

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import compiler.jeandle.fileCheck.FileCheck;
import jdk.internal.misc.Unsafe;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.MethodVisitor;
import jdk.internal.org.objectweb.asm.Opcodes;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestUnsafeParkUnpark {
    private static final Unsafe U = Unsafe.getUnsafe();
    private static final long JOIN_TIMEOUT_MS = 30_000;
    private static final String PARK_LOG =
            "Method `virtual void jdk.internal.misc.Unsafe.park(jboolean, jlong)` is parsed as intrinsic";
    private static final String UNPARK_LOG =
            "Method `virtual void jdk.internal.misc.Unsafe.unpark(jobject)` is parsed as intrinsic";

    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            runSemantics();
            return;
        }
        runCase("enabled", true, null);
        runCase("control-disabled", false,
                "-XX:ControlIntrinsic=-_park,-_unpark");
        runCase("unsafe-disabled", false, "-XX:-InlineUnsafeOps");
        runCase("inline-natives-disabled", false, "-XX:-InlineNatives");
    }

    private static void runCase(String name, boolean enabled, String intrinsicOption)
            throws Exception {
        Path dumpPath = Files.createTempDirectory("jeandle_park_unpark_" + name + "_ir");
        List<String> command = new ArrayList<>(List.of(
                "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
                "--add-exports=java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED",
                "-Xbatch", "-Xcomp", "-XX:-TieredCompilation", "-XX:-BackgroundCompilation",
                "-XX:+UseJeandleCompiler", "-Xlog:jeandle=debug,jit+compilation=debug",
                "-XX:CompileCommand=compileonly,TestUnsafeParkUnpark::park",
                "-XX:CompileCommand=compileonly,TestUnsafeParkUnpark::unpark",
                "-XX:CompileCommand=compileonly,TestUnsafeParkUnpark::parkWithReceiver",
                "-XX:CompileCommand=compileonly,TestUnsafeParkUnpark::unparkWithReceiver",
                "-XX:CompileCommand=compileonly,RawBooleanPark::park",
                "-XX:CompileCommand=dontinline,TestUnsafeParkUnpark::park",
                "-XX:CompileCommand=dontinline,TestUnsafeParkUnpark::unpark",
                "-XX:CompileCommand=dontinline,TestUnsafeParkUnpark::parkWithReceiver",
                "-XX:CompileCommand=dontinline,TestUnsafeParkUnpark::unparkWithReceiver",
                "-XX:CompileCommand=dontinline,RawBooleanPark::park",
                "-XX:+UnlockDiagnosticVMOptions", "-XX:+CIPrintCompilerName",
                "-XX:+JeandleDumpIR", "-XX:JeandleDumpDirectory=" + dumpPath));
        if (intrinsicOption != null) {
            command.add(intrinsicOption);
        }
        command.add(TestUnsafeParkUnpark.class.getName());
        command.add("child");

        ProcessBuilder child = ProcessTools.createLimitedTestJavaProcessBuilder(command);
        // Some Jeandle development images enable Jeandle for javac and the jtreg
        // harness. The outer test may use _JAVA_OPTIONS=-XX:-UseJeandleCompiler
        // to keep that infrastructure stable; do not leak it into the child whose
        // compiler mode is the subject of this test.
        child.environment().remove("_JAVA_OPTIONS");
        OutputAnalyzer output = ProcessTools.executeCommand(child);
        output.shouldHaveExitValue(0);
        if (enabled) {
            output.shouldContain(PARK_LOG).shouldContain(UNPARK_LOG);
            output.shouldMatch("(?s).*Jeandle:.*TestUnsafeParkUnpark::park.*");
            output.shouldMatch("(?s).*Jeandle:.*TestUnsafeParkUnpark::unpark.*");
            output.shouldMatch("(?s).*Jeandle:.*RawBooleanPark::park.*");
        } else {
            output.shouldNotContain(PARK_LOG).shouldNotContain(UNPARK_LOG);
        }

        FileCheck park = new FileCheck(dumpPath.toString(),
                TestUnsafeParkUnpark.class.getDeclaredMethod("park", boolean.class, long.class), true);
        FileCheck unpark = new FileCheck(dumpPath.toString(),
                TestUnsafeParkUnpark.class.getDeclaredMethod("unpark", Object.class), true);
        if (enabled) {
            // The deopt bundle must describe the post-invoke state: operands
            // are consumed before the callsite is created, so the Unsafe
            // receiver is no longer pinned live across the call. park has no
            // other live oop (no gc-live bundle); unpark keeps only the
            // thread argument alive in gc-live.
            park.checkPattern("@unsafe_park.*\\[ \"deopt\"");
            park.checkNotPattern("@unsafe_park.*\"gc-live\"");
            unpark.checkPattern("@unsafe_unpark.*\\[ \"deopt\".*\"gc-live\""
                    + "\\(ptr addrspace\\(1\\) [^,)]*\\)");
        } else {
            park.checkNotPattern("@unsafe_park");
            unpark.checkNotPattern("@unsafe_unpark");
        }
        checkRawBooleanDump(dumpPath, enabled);
    }

    private static void checkRawBooleanDump(Path dumpPath, boolean enabled) throws Exception {
        List<Path> files;
        try (var paths = Files.list(dumpPath)) {
            files = paths.filter(path -> path.getFileName().toString()
                            .startsWith("RawBooleanPark_park_"))
                    .filter(path -> path.getFileName().toString().endsWith("_optimized.ll"))
                    .toList();
        }
        check(files.size() == 1, "expected one optimized RawBooleanPark::park dump: " + files);
        boolean containsIntrinsic = Files.readString(files.get(0)).contains("@unsafe_park");
        check(containsIntrinsic == enabled,
                "RawBooleanPark::park intrinsic path mismatch; enabled=" + enabled);
    }

    private static void runSemantics() throws Exception {
        nullTargetIsNoop();
        availablePermitReturnsImmediately();
        timeoutAndNonBlockingCases();
        interruptReturnsImmediatelyAndRemainsSet();
        rawBooleanUsesJniLowByteConvention();
        crossThreadUnparkWakesParker();
        unstartedThreadIsNoop();
        terminatedThreadIsNoop();
        nullReceiverThrows();
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void park(boolean isAbsolute, long time) {
        U.park(isAbsolute, time);
    }

    private static void unpark(Object thread) {
        U.unpark(thread);
    }

    private static void parkWithReceiver(Unsafe unsafe, boolean isAbsolute, long time) {
        unsafe.park(isAbsolute, time);
    }

    private static void unparkWithReceiver(Unsafe unsafe, Object thread) {
        unsafe.unpark(thread);
    }

    private static void nullTargetIsNoop() {
        unpark(null);
    }

    private static void availablePermitReturnsImmediately() {
        for (int i = 0; i < 1_000; i++) {
            unpark(Thread.currentThread());
            long start = System.nanoTime();
            park(false, 0L);
            check(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5),
                    "park did not consume an available permit promptly");
        }
    }

    private static void timeoutAndNonBlockingCases() {
        long start = System.nanoTime();
        park(false, -1L);
        park(true, 0L);
        park(false, 1L);
        check(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5),
                "non-blocking/timed park took too long");
    }

    private static void interruptReturnsImmediatelyAndRemainsSet() {
        Thread.currentThread().interrupt();
        try {
            park(false, 0L);
            check(Thread.currentThread().isInterrupted(), "park cleared interrupt status");
        } finally {
            Thread.interrupted();
        }
    }

    private static void rawBooleanUsesJniLowByteConvention() throws Exception {
        Class<?> probe = java.lang.invoke.MethodHandles.lookup().defineClass(rawBooleanProbeBytes());
        java.lang.reflect.Method park = probe.getMethod(
                "park", Unsafe.class, int.class, long.class);
        for (int i = 0; i < 5; i++) {
            // Resolve reflection and force Xcomp compilation before timing.
            park.invoke(null, U, 0, -1L);
        }
        boolean observedRelativeWait = false;
        for (int i = 0; i < 3; i++) {
            long start = System.nanoTime();
            // 256 has a zero low byte. JNI jboolean conversion must therefore
            // select relative mode and wait for roughly 100 ms. Treating the
            // full jint as true would interpret 100,000,000 as an absolute
            // epoch-millisecond deadline and return immediately.
            park.invoke(null, U, 256, TimeUnit.MILLISECONDS.toNanos(100));
            observedRelativeWait |= System.nanoTime() - start >= TimeUnit.MILLISECONDS.toNanos(20);
        }
        check(observedRelativeWait, "raw boolean argument was not narrowed to JNI jboolean");
    }

    private static byte[] rawBooleanProbeBytes() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                "RawBooleanPark", null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "park", "(Ljdk/internal/misc/Unsafe;IJ)V", null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitVarInsn(Opcodes.ILOAD, 1);
        method.visitVarInsn(Opcodes.LLOAD, 2);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "jdk/internal/misc/Unsafe",
                "park", "(ZJ)V", false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void crossThreadUnparkWakesParker() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread parker = new Thread(() -> {
            try {
                ready.countDown();
                park(false, 0L);
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "unsafe-park-test");
        parker.start();
        check(ready.await(10, TimeUnit.SECONDS), "parker did not start");
        awaitThreadState(parker, Thread.State.WAITING, 10, TimeUnit.SECONDS);
        unpark(parker);
        parker.join(JOIN_TIMEOUT_MS);
        check(!parker.isAlive(), "unpark did not wake target thread");
        if (failure.get() != null) {
            throw new AssertionError("parker failed", failure.get());
        }
    }

    private static void awaitThreadState(Thread thread, Thread.State expected,
                                         long timeout, TimeUnit unit) {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (thread.getState() != expected && System.nanoTime() < deadline) {
            check(thread.isAlive(), "parker terminated before entering " + expected);
            Thread.onSpinWait();
        }
        check(thread.getState() == expected,
                "parker did not enter " + expected + "; state=" + thread.getState());
    }

    private static void unstartedThreadIsNoop() {
        unpark(new Thread(() -> { }));
    }

    private static void terminatedThreadIsNoop() throws Exception {
        Thread thread = new Thread(() -> { });
        thread.start();
        thread.join(JOIN_TIMEOUT_MS);
        check(!thread.isAlive(), "test thread did not terminate");
        unpark(thread);
    }

    private static void nullReceiverThrows() {
        try {
            parkWithReceiver(null, false, -1L);
            throw new AssertionError("null Unsafe receiver did not throw for park");
        } catch (NullPointerException expected) {
            // Expected.
        }
        try {
            unparkWithReceiver(null, null);
            throw new AssertionError("null Unsafe receiver did not throw for unpark");
        } catch (NullPointerException expected) {
            // Expected.
        }
    }
}
