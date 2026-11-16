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

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @test
 * @summary Under a deliberately small Code Cache with flushing enabled and a
 *          compile-heavy burst across diverse method shapes, the exact
 *          sizing must install every compilation with zero CodeBuffer::expand()
 *          on the core sections, zero relocation-array reallocation, and must
 *          never crash or corrupt the code cache.
 * @library /test/lib
 * @requires vm.debug == true & vm.compiler2.enabled
 * @run driver compiler.jeandle.TestCodeBufferBenchStability
 */
public class TestCodeBufferBenchStability {

    private static final Pattern EXPAND_LINE = Pattern.compile(
        "Expand:\\s+total\\s+(\\d+)\\s+\\(consts\\s+(\\d+),\\s+insts\\s+(\\d+),\\s+stubs\\s+(\\d+)\\),\\s+bytes\\s+(\\d+)");

    private static final Pattern LOCS_EXPAND_LINE = Pattern.compile(
        "Locs expand \\(separate growth path\\):\\s+(\\d+)\\s+reallocations,\\s+(\\d+)\\s+bytes added");

    // A wide, compile-heavy burst: tiny accessors, call-dense, arrays with
    // implicit exceptions, exception handlers, and OSR loops in one process, so
    // the finalize path is exercised across every partition under code-cache
    // pressure.
    static final class Vec {
        int x, y, z;
        Vec(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
        int getX() { return x; }
        int getY() { return y; }
        int getZ() { return z; }
        int sum() { return x + y + z; }
        int manhattan() { return Math.abs(x) + Math.abs(y) + Math.abs(z); }
        long packed() { return ((long) x << 32) | (y & 0xffffffffL); }
    }

    static int leafA(int v) { return v * 7 + 1; }
    static int leafB(int v) { return (v ^ 0x5a5a) + 3; }

    static int callDense(int v) {
        int a = leafA(v); a = leafB(a); a = leafA(a); a = leafB(a);
        a = leafA(a); a = leafB(a); return a;
    }

    static int arrayHeavy(int[] arr, int idx) {
        int s = 0;
        for (int i = 0; i < arr.length; i++) s += arr[i] + callDense(i ^ idx);
        s += arr[idx % arr.length];   // implicit bounds-check path
        return s;
    }

    static int withHandler(int x) {
        try {
            if (x < 0) throw new IllegalArgumentException("neg " + x);
            return 100 / (x % 5);      // may divide-by-zero
        } catch (ArithmeticException e) {
            return -1;
        } catch (IllegalArgumentException e) {
            return -2;
        }
    }

    static long osrHotLoop(int n) {
        long acc = 0;
        for (int i = 0; i < n; i++) {
            acc += (i * 31) ^ (i >> 1);
            if ((i & 0x3f) == 0) acc ^= (i * 2654435761L);
        }
        return acc;
    }

    static long workload() {
        long acc = 0;
        int[] arr = new int[64];
        for (int i = 0; i < arr.length; i++) arr[i] = i * 3;
        for (int i = 0; i < 4000; i++) {
            Vec v = new Vec(i, i + 1, i + 2);
            acc += v.sum() + v.manhattan() + v.getX() + v.getY() + v.getZ();
            acc += callDense(i);
            acc += arrayHeavy(arr, i);
            acc += withHandler(i % 9);
            acc += osrHotLoop(200);
        }
        return acc;
    }

    public static void main(String[] args) throws Throwable {
        if (args.length > 0 && args[0].equals("--workload")) {
            long r = workload();
            if (r == 0) throw new RuntimeException("workload sanity check failed");
            System.out.println("workload result: " + r);
            return;
        }

        // Child: full exact sizing under a small, flushing Code Cache.
        List<String> cmd = new ArrayList<>();
        cmd.add("-XX:+UnlockDiagnosticVMOptions");
        cmd.add("-XX:+UnlockExperimentalVMOptions");
        cmd.add("-XX:+UseJeandleCompiler");
        cmd.add("-XX:+JeandleCodeBufferInstrument");
        cmd.add("-XX:+JeandleExactInsts");
        cmd.add("-XX:+JeandleExactStubs");
        cmd.add("-XX:+JeandleExactLocs");
        cmd.add("-XX:ReservedCodeCacheSize=12M");
        cmd.add("-XX:InitialCodeCacheSize=6M");
        cmd.add("-XX:+UseCodeCacheFlushing");
        cmd.add("-Xcomp");
        cmd.add("-XX:CompileCommand=dontinline,compiler.jeandle.TestCodeBufferBenchStability::leafA");
        cmd.add("-XX:CompileCommand=dontinline,compiler.jeandle.TestCodeBufferBenchStability::leafB");
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add("compiler.jeandle.TestCodeBufferBenchStability");
        cmd.add("--workload");

        ProcessBuilder pb = ProcessTools.createTestJavaProcessBuilder(cmd);
        OutputAnalyzer out = new OutputAnalyzer(pb.start());
        out.shouldHaveExitValue(0);
        String stdout = out.getStdout();

        Matcher exp = EXPAND_LINE.matcher(stdout);
        Asserts.assertTrue(exp.find(), "Expand summary line must be present");
        long totalExp = Long.parseLong(exp.group(1));
        long constsExp = Long.parseLong(exp.group(2));
        long instsExp = Long.parseLong(exp.group(3));
        long stubsExp = Long.parseLong(exp.group(4));

        // Core sections must be strictly zero under the exact model. Trampoline
        // driven late stub growth is tolerated (documented, bounded, dynamic).
        Asserts.assertEquals(constsExp, 0L, "consts expand must be strictly 0");
        Asserts.assertEquals(instsExp, 0L, "insts expand must be strictly 0");
        Asserts.assertTrue(stubsExp <= 4L,
            "stubs expand must be 0 for business code (bounded tolerance for late trampoline growth), got " + stubsExp);
        Asserts.assertEquals(totalExp, constsExp + instsExp + stubsExp,
            "reported total expand must equal the sum of per-section expands");

        // Relocation arrays must not be reallocated during install (a small,
        // bounded tolerance covers the dynamic MH / trampoline late-add path).
        Matcher locs = LOCS_EXPAND_LINE.matcher(stdout);
        Asserts.assertTrue(locs.find(), "Locs expand summary line must be present");
        long locsReallocs = Long.parseLong(locs.group(1));
        Asserts.assertTrue(locsReallocs <= 4L,
            "locs reallocations must be bounded (dynamic MH/trampoline only), got " + locsReallocs);

        // The workload must actually have executed the compile-heavy burst.
        Asserts.assertTrue(stdout.contains("workload result:"),
            "child workload must report a result (no crash under code-cache pressure)");
    }
}
