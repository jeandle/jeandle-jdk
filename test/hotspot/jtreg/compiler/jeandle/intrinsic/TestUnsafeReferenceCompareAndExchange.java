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
 * @summary Test Jeandle Unsafe reference atomics, barriers, and orderings
 * @modules java.base/jdk.internal.misc
 * @library /test/lib /
 * @build compiler.jeandle.fileCheck.FileCheck
 * @run main/othervm compiler.jeandle.intrinsic.TestUnsafeReferenceCompareAndExchange
 */
package compiler.jeandle.intrinsic;

import compiler.jeandle.fileCheck.FileCheck;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import jdk.internal.misc.Unsafe;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestUnsafeReferenceCompareAndExchange {
    private static final Unsafe U = Unsafe.getUnsafe();
    private static final long VALUE_OFFSET;
    private static final String[] IDS = {
            "_getAndSetReference",
            "_compareAndSetReference", "_compareAndExchangeReference",
            "_compareAndExchangeReferenceAcquire",
            "_compareAndExchangeReferenceRelease"};

    static {
        try {
            VALUE_OFFSET = U.objectFieldOffset(Box.class.getDeclaredField("value"));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    static final class Box { volatile Object value; }

    static boolean cas(Box box, Object expected, Object update) {
        return U.compareAndSetReference(box, VALUE_OFFSET, expected, update);
    }
    static Object cax(Box box, Object expected, Object update) {
        return U.compareAndExchangeReference(box, VALUE_OFFSET, expected, update);
    }
    static Object caxAcquire(Box box, Object expected, Object update) {
        return U.compareAndExchangeReferenceAcquire(box, VALUE_OFFSET, expected, update);
    }
    static Object caxRelease(Box box, Object expected, Object update) {
        return U.compareAndExchangeReferenceRelease(box, VALUE_OFFSET, expected, update);
    }
    static Object getAndSet(Box box, Object update) {
        return U.getAndSetReference(box, VALUE_OFFSET, update);
    }
    static Object getAndSetDynamic(Object base, long offset, Object update) {
        return U.getAndSetReference(base, offset, update);
    }
    static Object caxDynamic(Object base, long offset, Object expected, Object update) {
        return U.compareAndExchangeReference(base, offset, expected, update);
    }
    static Object caxKnownRaw(Object expected, Object update) {
        return U.compareAndExchangeReference(null, 0L, expected, update);
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
        String wrapper = TestUnsafeReferenceCompareAndExchange.class.getName();
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
        output.shouldHaveExitValue(0).shouldContain("TestUnsafeReferenceCompareAndExchange PASSED");
        String objectPrefix = "Method `virtual jobject jdk.internal.misc.Unsafe.";
        String casMarker = "Method `virtual jboolean jdk.internal.misc.Unsafe."
                + "compareAndSetReference(jobject, jlong, jobject, jobject)` is parsed as intrinsic";
        String caxMarker = objectPrefix
                + "compareAndExchangeReference(jobject, jlong, jobject, jobject)` is parsed as intrinsic";
        String acquireMarker = objectPrefix
                + "compareAndExchangeReferenceAcquire(jobject, jlong, jobject, jobject)` is parsed as intrinsic";
        String releaseMarker = objectPrefix
                + "compareAndExchangeReferenceRelease(jobject, jlong, jobject, jobject)` is parsed as intrinsic";
        String getAndSetMarker = objectPrefix
                + "getAndSetReference(jobject, jlong, jobject)` is parsed as intrinsic";
        if (enabled) {
            output.shouldContain(casMarker).shouldContain(caxMarker)
                    .shouldContain(acquireMarker).shouldContain(releaseMarker)
                    .shouldContain(getAndSetMarker);
            checkGetAndSetIR(dump, g1);
            checkIR(dump, "cas", "unsafe_compare_and_set_reference",
                    "seq_cst seq_cst", g1);
            checkIR(dump, "cax", "unsafe_compare_and_exchange_reference",
                    "seq_cst seq_cst", g1);
            checkIR(dump, "caxAcquire",
                    "unsafe_compare_and_exchange_reference_acquire", "acquire acquire", g1);
            checkIR(dump, "caxRelease",
                    "unsafe_compare_and_exchange_reference_release", "release monotonic", g1);
            FileCheck dynamic = new FileCheck(dump,
                    TestUnsafeReferenceCompareAndExchange.class.getDeclaredMethod(
                            "caxDynamic", Object.class, long.class, Object.class, Object.class), false);
            dynamic.checkPattern("deopt");
            dynamic.checkPattern("unsafe_reference_cax_raw");
            FileCheck getAndSetDynamic = new FileCheck(dump,
                    TestUnsafeReferenceCompareAndExchange.class.getDeclaredMethod(
                            "getAndSetDynamic", Object.class, long.class, Object.class), false);
            getAndSetDynamic.checkPattern("deopt");
            getAndSetDynamic.checkPattern("unsafe_reference_get_and_set_raw");
        } else {
            output.shouldNotContain(casMarker).shouldNotContain(caxMarker)
                    .shouldNotContain(acquireMarker).shouldNotContain(releaseMarker)
                    .shouldNotContain(getAndSetMarker);
        }
    }

    private static void checkIR(String dump, String method, String javaOp,
                                String ordering, boolean g1) throws Exception {
        FileCheck check = new FileCheck(dump,
                TestUnsafeReferenceCompareAndExchange.class.getDeclaredMethod(
                        method, Box.class, Object.class, Object.class), false);
        check.checkPattern("define private hotspotcc .*@jeandle\\." + javaOp);
        if (g1) {
            check.checkPattern("call hotspotcc void @jeandle\\.g1_pre_barrier_loaded"
                    + "\\(ptr addrspace\\(1\\) %" + javaOp + "\\.expected\\)");
        }
        check.checkPattern("cmpxchg ptr addrspace\\(1\\).*" + ordering);
        check.checkPattern("jeandle\\.post_barrier");
    }

    private static void checkGetAndSetIR(String dump, boolean g1) throws Exception {
        FileCheck check = new FileCheck(dump,
                TestUnsafeReferenceCompareAndExchange.class.getDeclaredMethod(
                        "getAndSet", Box.class, Object.class), false);
        check.checkPattern("define private hotspotcc .*@jeandle\\.unsafe_get_and_set_reference");
        check.checkPattern("atomicrmw xchg ptr addrspace\\(1\\).*seq_cst");
        if (g1) {
            check.checkPattern("call hotspotcc void @jeandle\\.g1_pre_barrier_loaded"
                    + "\\(ptr addrspace\\(1\\) %unsafe_get_and_set_reference\\.old\\)");
        }
        check.checkPattern("jeandle\\.post_barrier");
    }

    private static void semantics() {
        Box box = new Box();
        Object a = new Object(), b = new Object(), c = new Object();
        box.value = a;
        if (!cas(box, a, b) || box.value != b) throw new AssertionError("CAS success");
        if (cas(box, a, c) || box.value != b) throw new AssertionError("CAS failure");
        if (cax(box, a, c) != b || box.value != b) throw new AssertionError("CAX failure old");
        if (caxAcquire(box, b, c) != b || box.value != c) throw new AssertionError("CAX acquire");
        if (caxRelease(box, c, null) != c || box.value != null) throw new AssertionError("CAX release null");
        if (!cas(box, null, a) || box.value != a) throw new AssertionError("CAS null expected");
        if (cax(box, a, null) != a || box.value != null) throw new AssertionError("CAX null update");
        if (getAndSet(box, b) != null || box.value != b) throw new AssertionError("getAndSet old null");
        if (getAndSet(box, null) != b || box.value != null) throw new AssertionError("getAndSet old reference");
        if (getAndSetDynamic(box, VALUE_OFFSET, a) != null || box.value != a) {
            throw new AssertionError("getAndSet dynamic heap base");
        }
        if (caxDynamic(box, VALUE_OFFSET, a, b) != a || box.value != b) {
            throw new AssertionError("dynamic heap base");
        }
        System.out.println("TestUnsafeReferenceCompareAndExchange PASSED");
    }

}
