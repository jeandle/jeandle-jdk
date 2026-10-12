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
 * 2 along with this work; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 */

/*
 * @test
 * @summary Test Jeandle Unsafe weak reference CAS barriers and orderings
 * @modules java.base/jdk.internal.misc
 * @library /test/lib /
 * @build compiler.jeandle.fileCheck.FileCheck
 * @run main/othervm compiler.jeandle.intrinsic.TestUnsafeWeakReferenceCompareAndSet
 */
package compiler.jeandle.intrinsic;

import compiler.jeandle.fileCheck.FileCheck;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import jdk.internal.misc.Unsafe;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestUnsafeWeakReferenceCompareAndSet {
    private static final Unsafe U = Unsafe.getUnsafe();
    private static final long VALUE_OFFSET;
    private static final String[] IDS = {
            "_weakCompareAndSetReferencePlain", "_weakCompareAndSetReferenceAcquire",
            "_weakCompareAndSetReferenceRelease", "_weakCompareAndSetReference"};

    static {
        try {
            VALUE_OFFSET = U.objectFieldOffset(Box.class.getDeclaredField("value"));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    static final class Box { volatile Object value; }

    static boolean weakPlain(Box box, Object expected, Object update) {
        return U.weakCompareAndSetReferencePlain(box, VALUE_OFFSET, expected, update);
    }
    static boolean weakAcquire(Box box, Object expected, Object update) {
        return U.weakCompareAndSetReferenceAcquire(box, VALUE_OFFSET, expected, update);
    }
    static boolean weakRelease(Box box, Object expected, Object update) {
        return U.weakCompareAndSetReferenceRelease(box, VALUE_OFFSET, expected, update);
    }
    static boolean weakVolatile(Box box, Object expected, Object update) {
        return U.weakCompareAndSetReference(box, VALUE_OFFSET, expected, update);
    }
    static boolean weakDynamic(Object base, long offset, Object expected, Object update) {
        return U.weakCompareAndSetReference(base, offset, expected, update);
    }
    static boolean weakKnownRaw(Object expected, Object update) {
        return U.weakCompareAndSetReference(null, 0L, expected, update);
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("child")) {
            semantics();
            return;
        }
        runCase("g1_enabled", true, "-XX:+UseG1GC", null);
        runCase("g1_uncompressed", true, "-XX:+UseG1GC", "-XX:-UseCompressedOops");
        runCase("serial_enabled", true, "-XX:+UseSerialGC", null);
        runCase("control_disabled", false, "-XX:+UseG1GC",
                "-XX:ControlIntrinsic=-" + String.join(",-", IDS));
        runCase("unsafe_ops_disabled", false, "-XX:+UseG1GC", "-XX:-InlineUnsafeOps");
    }

    private static void runCase(String name, boolean enabled, String gc, String option)
            throws Exception {
        String dump = Files.createTempDirectory("jeandle_reference_cax_" + name).toString();
        String wrapper = TestUnsafeWeakReferenceCompareAndSet.class.getName();
        ArrayList<String> command = new ArrayList<>(List.of(
                "--add-exports", "java.base/jdk.internal.misc=ALL-UNNAMED",
                "-Dtest.jdk=" + System.getProperty("test.jdk"),
                "-Xbatch", "-Xcomp", "-XX:-TieredCompilation", "-XX:-BackgroundCompilation",
                "-XX:+UseJeandleCompiler", "-XX:+UnlockDiagnosticVMOptions", gc,
                "-Xlog:jeandle=debug,jit+compilation=debug", "-XX:+CIPrintCompilerName",
                "-XX:CompileCommand=compileonly," + wrapper + "::*",
                "-XX:CompileCommand=dontinline," + wrapper + "::*",
                "-XX:+JeandleDumpIR", "-XX:JeandleDumpDirectory=" + dump));
        if (option != null) command.add(option);
        command.add(wrapper);
        command.add("child");
        boolean g1 = gc.equals("-XX:+UseG1GC");
        OutputAnalyzer output = ProcessTools.executeCommand(
                ProcessTools.createLimitedTestJavaProcessBuilder(command));
        output.shouldHaveExitValue(0).shouldContain("TestUnsafeWeakReferenceCompareAndSet PASSED");
        String prefix = "Method `virtual jboolean jdk.internal.misc.Unsafe.";
        String plainMarker = prefix + "weakCompareAndSetReferencePlain(jobject, jlong, jobject, jobject)` is parsed as intrinsic";
        String acquireMarker = prefix + "weakCompareAndSetReferenceAcquire(jobject, jlong, jobject, jobject)` is parsed as intrinsic";
        String releaseMarker = prefix + "weakCompareAndSetReferenceRelease(jobject, jlong, jobject, jobject)` is parsed as intrinsic";
        String volatileMarker = prefix + "weakCompareAndSetReference(jobject, jlong, jobject, jobject)` is parsed as intrinsic";
        if (enabled) {
            output.shouldContain(plainMarker).shouldContain(acquireMarker)
                    .shouldContain(releaseMarker).shouldContain(volatileMarker);
            checkIR(dump, "weakPlain", "unsafe_weak_compare_and_set_reference_plain",
                    "monotonic monotonic", g1);
            checkIR(dump, "weakAcquire", "unsafe_weak_compare_and_set_reference_acquire",
                    "acquire acquire", g1);
            checkIR(dump, "weakRelease", "unsafe_weak_compare_and_set_reference_release",
                    "release monotonic", g1);
            checkIR(dump, "weakVolatile", "unsafe_weak_compare_and_set_reference",
                    "seq_cst seq_cst", g1);
            FileCheck dynamic = new FileCheck(dump,
                    TestUnsafeWeakReferenceCompareAndSet.class.getDeclaredMethod(
                            "weakDynamic", Object.class, long.class, Object.class, Object.class), false);
            dynamic.checkPattern("deopt");
            dynamic.checkPattern("unsafe_reference_cax_raw");
        } else {
            output.shouldNotContain(plainMarker).shouldNotContain(acquireMarker)
                    .shouldNotContain(releaseMarker).shouldNotContain(volatileMarker);
        }
    }

    private static void checkIR(String dump, String method, String javaOp,
                                String ordering, boolean g1) throws Exception {
        FileCheck mapping = new FileCheck(dump,
                TestUnsafeWeakReferenceCompareAndSet.class.getDeclaredMethod(
                        method, Box.class, Object.class, Object.class), false);
        mapping.checkPattern("call hotspotcc i32 @jeandle\\." + javaOp + "\\(");
        FileCheck check = new FileCheck(dump,
                TestUnsafeWeakReferenceCompareAndSet.class.getDeclaredMethod(
                        method, Box.class, Object.class, Object.class), false);
        check.checkPattern("define private hotspotcc .*@jeandle\\." + javaOp);
        if (g1) {
            check.checkPattern("call hotspotcc void @jeandle\\.g1_pre_barrier_loaded"
                    + "\\(ptr addrspace\\(1\\) %" + javaOp + "\\.expected\\)");
        }
        check.checkPattern("cmpxchg weak ptr addrspace\\(1\\).*" + ordering);
        check.checkPattern("jeandle\\.post_barrier");
    }

    private static void eventually(BooleanSupplier operation, String message) {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (operation.getAsBoolean()) {
                return;
            }
        }
        throw new AssertionError(message + ": weak CAS spuriously failed 100 times");
    }

    private static void semantics() {
        Box box = new Box();
        Object a = new Object(), b = new Object(), c = new Object();
        box.value = a;
        eventually(() -> weakPlain(box, a, b), "plain success");
        if (box.value != b) throw new AssertionError("plain update");
        if (weakPlain(box, a, c) || box.value != b) throw new AssertionError("plain failure");
        eventually(() -> weakAcquire(box, b, c), "acquire");
        if (box.value != c) throw new AssertionError("acquire update");
        eventually(() -> weakRelease(box, c, null), "release null");
        if (box.value != null) throw new AssertionError("release update");
        eventually(() -> weakVolatile(box, null, a), "volatile null expected");
        if (box.value != a) throw new AssertionError("volatile update");
        eventually(() -> weakDynamic(box, VALUE_OFFSET, a, b), "dynamic heap base");
        if (box.value != b) throw new AssertionError("dynamic heap update");
        System.out.println("TestUnsafeWeakReferenceCompareAndSet PASSED");
    }

}
