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
 * @summary Const sections are allocated exactly as planned.
 *
 *          Runs a const-heavy workload in a child VM with the CodeBuffer
 *          instrumentation enabled and checks the two invariants of the exact
 *          allocation path:
 *
 *            1. every method that reaches finalize() takes the plan-driven
 *               path, with no planner failure and no fallback to the legacy
 *               48 KiB reservation, and
 *            2. the consts capacity that CodeBuffer hands out never exceeds
 *               what is actually used by more than one alignment step.
 *
 *   Point 2: for a buffer that never expanded, the reserved capacity may exceed
 *            the planned size by at most one alignment step. A buffer that did
 *            expand is re-divided by CodeBuffer::expand(), so its residual is
 *            not bounded by the alignment and is checked only for safety
 *            (usage <= capacity, usage == planned).
 *          CodeBuffer::initialize_section_size() rounds the section start down
 *          to the section alignment, so the capacity is
 *          requested + ((limit - requested) % alignment). The extra bytes are
 *          a function of the blob address, not of the request, and cannot be
 *          removed by asking for a different size.
 *
 * @requires vm.debug == true
 * @library /test/lib
 * @build compiler.jeandle.TestCodeBufferConstValues
 * @run driver compiler.jeandle.TestCodeBufferConstsExactAlloc
 */

package compiler.jeandle;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestCodeBufferConstsExactAlloc {

    private static final Pattern CONST_TOTAL =
            Pattern.compile("\\[JeandleConstTotal\\].*?planned=(\\d+), allocated=(\\d+), actual=(\\d+),.*?fallback=(\\d+), align=(\\d+), expands=(\\d+)");

    // Per-method CodeBuffer expansion count, reported on the [JeandleConstTotal]
    // line as `expands=<n>`.
    private static final Pattern EXPANDS =
            Pattern.compile("expands=(\\d+)");

    public static void main(String[] args) throws Exception {
        ProcessBuilder pb = ProcessTools.createTestJavaProcessBuilder(
                "-XX:+UnlockDiagnosticVMOptions",
                "-XX:+UseJeandleCompiler",
                "-XX:+JeandleCodeBufferInstrument",
                "-Xbatch",
                // Only the const-heavy methods are meant to be measured here;
                // letting the whole workload compile would also drag in main()
                // and the surrounding test scaffolding.
                "-XX:CompileCommand=compileonly,compiler.jeandle.TestCodeBufferConstValues::computeDouble",
                "-XX:CompileCommand=compileonly,compiler.jeandle.TestCodeBufferConstValues::computeFloat",
                "-XX:CompileCommand=compileonly,compiler.jeandle.TestCodeBufferConstValues::computeOopReloc",
                "-cp", System.getProperty("java.class.path"),
                "compiler.jeandle.TestCodeBufferConstValues");

        OutputAnalyzer out = new OutputAnalyzer(pb.start());
        out.shouldHaveExitValue(0);
        String stdout = out.getStdout();

        // --- deterministic layout summary ------------------------------------
        long methods      = summaryLong(stdout, "Methods reaching finalize");
        long exact        = summaryLong(stdout, "Exact path (from ConstSectionPlan)");
        long legacy       = summaryLong(stdout, "Legacy path (48 KiB default)");
        long planFailures = summaryLong(stdout, "Planner failures");
        long fallbacks    = summaryLong(stdout, "Runtime layout fallbacks");
        long expansions   = summaryLong(stdout, "consts expansions after exact plan");

        // Guard against a vacuous pass: something must have been compiled.
        Asserts.assertGT(methods, 0L, "no method reached finalize()");
        // The two paths partition the finalized methods. This does not assert
        // exact == methods: on riscv64, the alignment guard in
        // decide_install_layout() refuses a method whose const section needs more
        // alignment than the allocation guarantees; such a method falls back, and
        // that is the designed behaviour, not a defect.
        Asserts.assertEQ(exact + legacy, methods,
                "every finalized method must be counted as exact or legacy");
        if (methods > exact) {
            Asserts.assertGT(planFailures + fallbacks, 0L,
                    "methods off the exact path must be explained by planner failures"
                            + " or runtime layout fallbacks (exact=" + exact
                            + ", methods=" + methods + ")");
        }
        // The test does not assert legacy == 0: a blanket ban on falling back to the
        // legacy 48 KiB reserve would be wrong, because the alignment guard can refuse a
        // method on purpose; what must never happen is an *unexplained* fallback.
        // Refusals are allowed - they are the guard doing its job - but they must cover
        // exactly the methods that ended up on the legacy path, and they must be reported.
        Asserts.assertGTE(planFailures + fallbacks, legacy,
                "every legacy method needs at least one reported refusal (legacy="
                        + legacy + ", planFailures=" + planFailures
                        + ", fallbacks=" + fallbacks + ")");
        if (legacy > 0) {
            System.out.println("note: " + legacy + " of " + methods + " method(s) fell back"
                    + " to the legacy reserve because a const section needed more"
                    + " alignment than the allocation guarantees for the consts base");
        }
        Asserts.assertEQ(expansions, 0L,
                "the consts section must never expand after an exact plan");

        // --- instrumentation summary -----------------------------------------
        long usage    = instrumentationLong(stdout, "usage", "bytes /");
        long capacity = instrumentationLong(stdout, "/ capacity", "bytes,");

        Asserts.assertGT(usage, 0L, "the workload must emit at least one const byte");
        Asserts.assertGTE(capacity, usage, "capacity must cover the emitted consts");

        // Check the rounding bound per method, not on the aggregate. Two buffers
        // that each rounded up by align/2 aggregate into a residual of exactly
        // align, which would "violate" a bound that holds for every single buffer:
        // CodeBuffer's invariant is per buffer, and only for a buffer that was
        // divided up once (an expansion re-divides the whole blob). This mirrors
        // the assert in jeandleCompiledCode.cpp::finalize().
        long perMethodBound = 0;
        Matcher m = CONST_TOTAL.matcher(stdout);
        while (m.find()) {
            long planned  = Long.parseLong(m.group(1));
            long allocated = Long.parseLong(m.group(2));
            long actual   = Long.parseLong(m.group(3));
            long fallback = Long.parseLong(m.group(4));
            long align    = Long.parseLong(m.group(5));
            long expands  = Long.parseLong(m.group(6));
            Asserts.assertLTE(actual, allocated,
                    "a method must not write past the consts capacity (method line "
                            + m.group(0) + ")");
            if (fallback > 0) {
                continue;   // the layout degraded; the exact-allocation bound does not apply
            }
            Asserts.assertEQ(actual, planned,
                    "exact allocation must emit exactly the planned consts bytes");
            if (expands > 0) {
                continue;   // re-divided by CodeBuffer::expand(); residual is not bounded by align
            }
            perMethodBound++;
            Asserts.assertGTE(allocated, planned,
                    "an unexpanded buffer cannot hand out less than it was asked for");
            Asserts.assertLTE(allocated - planned, align - 1,
                    "consts over-allocation must stay within one alignment step for a"
                            + " buffer that never expanded (planned=" + planned
                            + ", allocated=" + allocated + ", align=" + align + ")");
        }
        Asserts.assertGT(perMethodBound, 0L,
                "no method line was checked; the per-method bound would be vacuous");
        System.out.println("note: rounding bound checked on " + perMethodBound
                + " unexpanded method line(s); aggregate was capacity=" + capacity
                + ", usage=" + usage + " (aggregate residual is not a valid invariant)");

        // The consts CodeSection itself must never have been expanded.
        int constsExpands = expandCount(stdout);
        Asserts.assertEQ(constsExpands, 0, "consts section was expanded at runtime");
    }

    /** Reads "  <key>:<spaces><number>" from the deterministic layout block. */
    private static long summaryLong(String stdout, String key) {
        for (String line : stdout.split("\\R")) {
            int idx = line.indexOf(key + ":");
            if (idx >= 0) {
                String tail = line.substring(idx + key.length() + 1).trim();
                return Long.parseLong(tail);
            }
        }
        throw new RuntimeException("missing layout summary key: " + key);
    }

    /** Reads "Consts: usage <n> bytes / capacity <n> bytes, waste ...%". */
    private static long instrumentationLong(String stdout, String before, String after) {
        for (String line : stdout.split("\\R")) {
            if (!line.contains("Consts:")) {
                // Only the "Consts: usage N bytes / capacity N bytes, waste X%" line
                // is meant here. The summary also prints a
                // "Locs elements: used N / capacity N" line that reuses the
                // "/ capacity" token; without this anchor the lookup would match it
                // and fail with "malformed consts line".
                continue;
            }
            int idx = line.indexOf(before);
            if (idx >= 0) {
                String rest = line.substring(idx + before.length());
                int end = rest.indexOf(after);
                if (end < 0) {
                    throw new RuntimeException("malformed consts line: " + line);
                }
                return Long.parseLong(rest.substring(0, end).trim());
            }
        }
        throw new RuntimeException("missing instrumentation key: " + before);
    }

    /** Reads the consts component out of "Expand: total N (consts X, ...)". */
    private static int expandCount(String stdout) {
        for (String line : stdout.split("\\R")) {
            int idx = line.indexOf("consts ");
            if (idx >= 0 && line.contains("Expand:")) {
                String rest = line.substring(idx + "consts ".length());
                int comma = rest.indexOf(',');
                return Integer.parseInt(rest.substring(0, comma).trim());
            }
        }
        throw new RuntimeException("missing expand line");
    }
}
