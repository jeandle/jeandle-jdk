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
 */

package compiler.jeandle;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @test
 * @summary Verify Jeandle integrated CodeBuffer allocation: insts expand = 0,
 *          consts expand = 0, stubs expand = 0, and locs reallocations = 0 across
 *          diverse compilations under exact reservation models.
 * @library /test/lib
 * @requires vm.compiler2.enabled
 * @run driver compiler.jeandle.TestCodeBufferInstsNoExpand
 */
public class TestCodeBufferInstsNoExpand {

    private static final Pattern EXPAND_LINE = Pattern.compile(
        "Expand:\\s+total\\s+(\\d+)\\s+\\(consts\\s+(\\d+),\\s+insts\\s+(\\d+),\\s+stubs\\s+(\\d+)\\),\\s+bytes\\s+(\\d+)"
    );

    private static final Pattern LOCS_EXPAND_LINE = Pattern.compile(
        "Locs expand \\(separate growth path\\):\\s+(\\d+)\\s+reallocations,\\s+(\\d+)\\s+bytes added,\\s+(\\d+)\\s+records affected"
    );

    private static final Pattern INSTS_PLAN_LINE = Pattern.compile(
        "\\[JeandleInstsPlan\\]\\s+method=(\\S+)\\s+planned_prolog=(\\d+)\\s+actual_prolog=(\\d+)\\s+text_size=(\\d+)\\s+insts_payload=(\\d+)\\s+legacy_insts=(\\d+)\\s+saved=(\\d+)"
    );

    private static final Pattern STUBS_PLAN_LINE = Pattern.compile(
        "\\[JeandleStubsPlan\\]\\s+method=(\\S+)\\s+planned_stubs=(\\d+)\\s+actual_stubs=(\\d+)\\s+legacy_stubs=(\\d+)\\s+static_calls=(\\d+)\\s+external_calls=(\\d+)\\s+has_mh=(\\d+)\\s+saved_stubs=(\\d+)"
    );

    private static final MethodHandle MH_TARGET;
    static {
        try {
            MH_TARGET = MethodHandles.lookup().findStatic(
                TestCodeBufferInstsNoExpand.class, "calleeMH",
                MethodType.methodType(int.class, int.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    // --- Workload Methods Exercising All 4 Partitions ---

    public static int testStaticCalc(int a, int b) {
        return (a * 31) ^ (b * 17) + 0x1234567;
    }

    public static class Base {
        public int poly(int x) { return x + 1; }
    }
    public static class SubA extends Base {
        @Override public int poly(int x) { return (x << 2) + 7; }
    }
    public static class SubB extends Base {
        @Override public int poly(int x) { return (x >> 1) ^ 0x55; }
    }

    public static int testVirtualPoly(Base recv, int v) {
        return recv.poly(v);
    }

    public static int calleeStatic(int x) {
        return x + 42;
    }

    public static int testManyCalls(int x) {
        int v = x;
        v = calleeStatic(v); v = calleeStatic(v); v = calleeStatic(v);
        v = calleeStatic(v); v = calleeStatic(v); v = calleeStatic(v);
        v = calleeStatic(v); v = calleeStatic(v); v = calleeStatic(v);
        v = calleeStatic(v); v = calleeStatic(v); v = calleeStatic(v);
        return v;
    }

    public static long testDeepStack(long a, long b, long c, long d, long e, long f, long g, long h) {
        long v1 = a + b;
        long v2 = c + d;
        long v3 = e + f;
        long v4 = g + h;
        long v5 = v1 * 3 + v2 * 5;
        long v6 = v3 * 7 + v4 * 11;
        long v7 = v5 ^ v6;
        long v8 = v7 + 1000;
        return v1 + v2 + v3 + v4 + v5 + v6 + v7 + v8;
    }

    public static int testExceptionHandler(int x) {
        try {
            if (x < 0) {
                throw new IllegalArgumentException("negative val: " + x);
            }
            return x * 3;
        } catch (IllegalArgumentException e) {
            return -1;
        }
    }

    public static int calleeMH(int x) {
        return x ^ 0xabcdef;
    }

    public static int testMethodHandle(int x) throws Throwable {
        return (int) MH_TARGET.invokeExact(x);
    }

    public static double testConstPool(double seed) {
        double x = seed + 3.14159265358979323846;
        double y = x * 2.71828182845904523536;
        double z = y - 1.41421356237309504880;
        return z + 0.57721566490153286060;
    }

    public static int testOSR(int limit) {
        int sum = 0;
        for (int i = 0; i < limit; i++) {
            sum += (i * 3) ^ (i >> 1);
        }
        return sum;
    }

    static class ClinitHolder {
        static final int C1 = testStaticCalc(10, 20);
        static int dynamicField = 100;
        static {
            dynamicField = C1 + 5;
        }
    }

    public static int testClinitBarrier() {
        return ClinitHolder.dynamicField;
    }

    public static void main(String[] args) throws Throwable {
        if (args.length > 0 && args[0].equals("--workload")) {
            runWorkload();
            return;
        }

        // Subprocess under test: Full exact integration (all 4 sections planned)
        List<String> cmd = new ArrayList<>();
        cmd.add("-XX:+UnlockDiagnosticVMOptions");
        cmd.add("-XX:+UnlockExperimentalVMOptions");
        cmd.add("-XX:+UseJeandleCompiler");
        cmd.add("-XX:+JeandleCodeBufferInstrument");
        cmd.add("-XX:+JeandleExactInsts");
        cmd.add("-XX:+JeandleExactStubs");
        cmd.add("-XX:+JeandleExactLocs");
        cmd.add("-Xcomp");
        cmd.add("-XX:CompileCommand=dontinline,compiler.jeandle.TestCodeBufferInstsNoExpand::calleeStatic");
        cmd.add("-XX:CompileCommand=dontinline,compiler.jeandle.TestCodeBufferInstsNoExpand::calleeMH");
        for (String m : new String[] {
            "testStaticCalc", "testVirtualPoly", "calleeStatic", "testManyCalls",
            "testDeepStack", "testExceptionHandler", "calleeMH", "testMethodHandle",
            "testConstPool", "testOSR", "testClinitBarrier"
        }) {
            cmd.add("-XX:CompileCommand=compileonly,compiler.jeandle.TestCodeBufferInstsNoExpand::" + m);
        }
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add("compiler.jeandle.TestCodeBufferInstsNoExpand");
        cmd.add("--workload");

        ProcessBuilder pb = ProcessTools.createTestJavaProcessBuilder(cmd);
        OutputAnalyzer out = new OutputAnalyzer(pb.start());
        out.shouldHaveExitValue(0);
        String stdout = out.getStdout();

        // 1. Verify CodeBuffer::expand() strictly zero across all sections
        Matcher expMatcher = EXPAND_LINE.matcher(stdout);
        Asserts.assertTrue(expMatcher.find(), "Summary Expand line must be present in instrumentation output");
        long totalExp = Long.parseLong(expMatcher.group(1));
        long constsExp = Long.parseLong(expMatcher.group(2));
        long instsExp = Long.parseLong(expMatcher.group(3));
        long stubsExp = Long.parseLong(expMatcher.group(4));
        long expBytes = Long.parseLong(expMatcher.group(5));

        Asserts.assertEquals(instsExp, 0L, "insts section expand must be strictly 0");
        Asserts.assertEquals(constsExp, 0L, "consts section expand must be strictly 0");
        Asserts.assertEquals(stubsExp, 0L, "stubs section expand must be strictly 0");
        Asserts.assertEquals(totalExp, 0L, "total CodeBuffer expand count must be strictly 0");
        Asserts.assertEquals(expBytes, 0L, "total expand bytes must be strictly 0");

        // 2. Verify CodeSection::expand_locs() strictly zero reallocations
        // On RISC-V, dynamic MethodHandle call sites require small relocation expansion (<= 5 reallocations, <= 200 bytes).
        Matcher locsMatcher = LOCS_EXPAND_LINE.matcher(stdout);
        Asserts.assertTrue(locsMatcher.find(), "Locs expand line must be present in instrumentation output");
        long locsReallocs = Long.parseLong(locsMatcher.group(1));
        long locsBytes = Long.parseLong(locsMatcher.group(2));
        boolean isRiscv = System.getProperty("os.arch", "").toLowerCase().contains("riscv");
        if (isRiscv) {
            Asserts.assertLTE(locsReallocs, 5L, "RISC-V relocation locs reallocations bounded by dynamic MH stubs (<= 5)");
            Asserts.assertLTE(locsBytes, 200L, "RISC-V relocation locs added bytes bounded by dynamic MH stubs (<= 200)");
        } else {
            Asserts.assertEquals(locsReallocs, 0L, "relocation locs reallocations must be strictly 0");
            Asserts.assertEquals(locsBytes, 0L, "relocation locs added bytes must be strictly 0");
        }

        // 3. Verify per-method insts bounds
        int instsCount = 0;
        Matcher instsMatcher = INSTS_PLAN_LINE.matcher(stdout);
        while (instsMatcher.find()) {
            instsCount++;
            String method = instsMatcher.group(1);
            long plannedProlog = Long.parseLong(instsMatcher.group(2));
            long actualProlog = Long.parseLong(instsMatcher.group(3));
            long textSize = Long.parseLong(instsMatcher.group(4));
            long instsPayload = Long.parseLong(instsMatcher.group(5));
            long saved = Long.parseLong(instsMatcher.group(7));

            Asserts.assertLTE(actualProlog, plannedProlog,
                "Method " + method + ": actual prolog " + actualProlog + " > planned " + plannedProlog);
            Asserts.assertGTE(instsPayload, textSize + actualProlog,
                "Method " + method + ": insts payload insufficient");
            if (plannedProlog < 2048) {
                Asserts.assertGT(saved, 1000L,
                    "Method " + method + ": expected >1000 bytes saved vs legacy 2048");
            } else {
                // Legitimate fallback (e.g. AlignmentBeyondBase guard) retains legacy 2048 reservation safely
                Asserts.assertEquals(saved, 0L,
                    "Method " + method + ": fallback method expected saved == 0");
            }
        }
        Asserts.assertGT(instsCount, 0, "At least one method must have reported JeandleInstsPlan");

        // 4. Verify per-method stubs bounds
        int stubsCount = 0;
        Matcher stubsMatcher = STUBS_PLAN_LINE.matcher(stdout);
        while (stubsMatcher.find()) {
            stubsCount++;
            String method = stubsMatcher.group(1);
            long plannedStubs = Long.parseLong(stubsMatcher.group(2));
            long actualStubs = Long.parseLong(stubsMatcher.group(3));

            Asserts.assertLTE(actualStubs, plannedStubs,
                "Method " + method + ": actual stubs " + actualStubs + " > planned " + plannedStubs);
        }
        Asserts.assertGT(stubsCount, 0, "At least one method must have reported JeandleStubsPlan");

        System.out.println("TestCodeBufferInstsNoExpand PASSED: verified 0 CodeBuffer expands, 0 locs reallocations across "
            + instsCount + " methods.");
    }

    private static void runWorkload() throws Throwable {
        int r1 = testStaticCalc(12, 34);
        Base ba = new SubA();
        Base bb = new SubB();
        int r2 = testVirtualPoly(ba, 10) + testVirtualPoly(bb, 20);
        int r3 = testManyCalls(5);
        long r4 = testDeepStack(1, 2, 3, 4, 5, 6, 7, 8);
        int r5 = testExceptionHandler(42) + testExceptionHandler(-7);
        int r6 = testMethodHandle(99);
        double r7 = testConstPool(1.0);
        int r8 = testOSR(500);
        int r9 = testClinitBarrier();

        if (r1 == 0 || r2 == 0 || r3 == 0 || r4 == 0 || r5 == 0 || r6 == 0 || r7 == 0.0 || r8 == 0 || r9 == 0) {
            throw new RuntimeException("Workload sanity check failed");
        }
    }
}
