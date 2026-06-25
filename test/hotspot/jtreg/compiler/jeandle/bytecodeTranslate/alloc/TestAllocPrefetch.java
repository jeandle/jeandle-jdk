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

/**
 * @test
 * @summary Test allocation correctness under all AllocatePrefetchStyle values.
 *          Prefetch is non-functional, so the assertion is "fast path stays
 *          correct" under each style and extreme line counts. Also verifies the
 *          Jeandle-specific warning for unsupported styles 2 and 3.
 * @library /test/lib /
 * @run driver compiler.jeandle.bytecodeTranslate.alloc.TestAllocPrefetch
 */

package compiler.jeandle.bytecodeTranslate.alloc;

import java.util.ArrayList;
import java.util.List;

import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestAllocPrefetch {

    public static class AllocWorker {
        static class Obj {
            int a;
            long b;
            double c;
            String d;

            Obj(int a) {
                this.a = a;
                this.b = a * 2L;
                this.c = a * 3.0;
                this.d = "v" + a;
            }
        }

        public static void main(String[] args) {
            // Mix instance and array allocations so both fast paths get exercised
            // and both AllocateInstancePrefetchLines / AllocatePrefetchLines paths
            // are hit.
            for (int i = 0; i < 10_000; i++) {
                Obj obj = new Obj(i);
                int[] ia = new int[16];
                Object[] oa = new Object[8];
                if (obj.a != i || obj.b != i * 2L || obj.c != i * 3.0 || !obj.d.equals("v" + i)) {
                    throw new RuntimeException("Field mismatch at iteration " + i);
                }
                ia[0] = i;
                oa[0] = obj;
                if (ia[0] != i || oa[0] != obj) {
                    throw new RuntimeException("Array element mismatch at iteration " + i);
                }
            }
            System.out.println("AllocWorker passed.");
        }
    }

    public static void main(String[] args) throws Exception {
        // Default settings (Style 1, default lines/distance/step). Fast path required.
        runFastPath("default", List.of());

        // Style 0 -- prefetch off via flag. Fast path still required (TLAB allocation works).
        runFastPath("style=0", List.of("-XX:AllocatePrefetchStyle=0"));

        // Style 1 -- explicit (same as default but exercises the dispatch).
        runFastPath("style=1", List.of("-XX:AllocatePrefetchStyle=1"));

        // Style 2 -- unsupported. Expect warning + correct allocations + fast path still hit.
        runFastPathWithUnsupportedStyleWarning("style=2", "2",
                List.of("-XX:AllocatePrefetchStyle=2"));

        // Style 3 -- unsupported. Expect warning + correct allocations + fast path still hit.
        runFastPathWithUnsupportedStyleWarning("style=3", "3",
                List.of("-XX:AllocatePrefetchStyle=3"));

        // Extreme lines counts under Style 1. Fast path required.
        // HotSpot's flag validator restricts AllocatePrefetchLines and
        // AllocateInstancePrefetchLines to [1, 64], so 0 is rejected at startup;
        // use Style 0 if you want zero prefetches.
        runFastPath("lines=1",          List.of("-XX:AllocatePrefetchLines=1"));
        runFastPath("lines=8",          List.of("-XX:AllocatePrefetchLines=8"));
        runFastPath("instance lines=1", List.of("-XX:AllocateInstancePrefetchLines=1"));

        // Style 1 + small TLAB -- forces frequent refills; prefetches near
        // tlab_end exercise the _reserve_for_allocation_prefetch tail. Small TLAB
        // may force occasional slow path on refill, so we only check correctness
        // (not fast-path exclusivity) here.
        runOk("style=1 + small TLAB",
                List.of("-XX:AllocatePrefetchStyle=1", "-XX:TLABSize=2k", "-XX:-ResizeTLAB"));

        // -UseTLAB -- the @VMOptions.UseTLAB constant in template.ll is folded
        // to false at compile time, dead-code-eliminating the fast path. All
        // allocations go via the slow path. Jeandle does not emit per-allocation
        // slow-path logs in this configuration, so we only check correctness
        // (AllocWorker completes, exit 0) — the substantive guarantee is that
        // prefetch IR being present in the fast path does not break the
        // dead-elimination of that fast path under -UseTLAB.
        runOk("-UseTLAB + style=1",
                List.of("-XX:-UseTLAB", "-XX:AllocatePrefetchStyle=1"));

        System.out.println("All TestAllocPrefetch tests passed.");
    }

    // Run and assert allocations succeeded AND fast path was taken
    // (no slow-path log for AllocWorker classes).
    static OutputAnalyzer runFastPath(String label, List<String> extraOpts) throws Exception {
        OutputAnalyzer output = run(extraOpts);
        output.shouldHaveExitValue(0);
        output.shouldContain("AllocWorker passed.");
        // Same idiom as TestAllocFastSlowPath: -Xlog:jeandle+alloc=debug emits
        // "Slow path allocation for <class>" when the fast path bails. Absence
        // proves the fast path was taken for every allocation in AllocWorker.
        output.shouldNotContain("Slow path allocation for compiler.jeandle.bytecodeTranslate.alloc.TestAllocPrefetch");
        System.out.println("  [PASS] " + label);
        return output;
    }

    static void runFastPathWithUnsupportedStyleWarning(String label, String styleValue,
                                                       List<String> extraOpts) throws Exception {
        OutputAnalyzer output = runFastPath(label, extraOpts);
        output.shouldContain("Jeandle does not implement AllocatePrefetchStyle=" + styleValue);
    }

    // Run and assert allocations succeeded; do not assert fast/slow split
    // (used for small-TLAB configs where occasional slow path is expected).
    static OutputAnalyzer runOk(String label, List<String> extraOpts) throws Exception {
        OutputAnalyzer output = run(extraOpts);
        output.shouldHaveExitValue(0);
        output.shouldContain("AllocWorker passed.");
        System.out.println("  [PASS] " + label);
        return output;
    }

    static OutputAnalyzer run(List<String> extraOpts) throws Exception {
        ArrayList<String> cmdArgs = new ArrayList<>(List.of(
            "-Xcomp",
            "-Xbatch",
            "-XX:-TieredCompilation",
            "-XX:+UseJeandleCompiler",
            "-Xlog:jeandle+alloc=debug",
            "-XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.alloc.TestAllocPrefetch$AllocWorker::*"
        ));
        cmdArgs.addAll(extraOpts);
        cmdArgs.add(AllocWorker.class.getName());

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(cmdArgs);
        return ProcessTools.executeCommand(pb);
    }
}
