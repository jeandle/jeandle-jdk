/*
 * Copyright (c) 2026, the Jeandle-JDK Authors. All Rights Reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without the implied warranty of MERCHANTABILITY or
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
 * @summary Verify Jeandle and C2 strip-mining flags are independent (structural IR checks)
 * @library /test/lib /
 * @build jdk.test.lib.Asserts compiler.jeandle.utils.IRDumpParser
 * @run driver TestStripMiningDisabled structural
 */
/*
 * @test
 * @summary Verify Jeandle and C2 strip-mining flags are independent via pass trace
 * @requires vm.debug
 * @library /test/lib /
 * @build jdk.test.lib.Asserts compiler.jeandle.utils.IRDumpParser
 * @run driver TestStripMiningDisabled trace
 */

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import compiler.jeandle.utils.IRDumpParser;
import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

/**
 * Drives child JVMs with opposing Jeandle and C2 strip-mining settings and
 * checks that only JeandleLoopStripMiningIter controls the Jeandle pipeline:
 *
 *  - A zero Jeandle budget removes SafepointStripMining from the pipeline,
 *    while a nonzero budget runs it regardless of the C2 flag.
 *  - The strict INT_MAX backedge threshold keeps the runtime-bound pre-test
 *    int loop and the exact-boundary loop polling, while deleting the loop
 *    whose constant maximum is immediately below the threshold.
 *  - The constant loop becomes deopt-state-only and is deleted atomically by
 *    loop-deletion-prep. Runtime-bound long loops keep one poll (keep-one).
 *
 * Each mode (structural / trace) spawns two children: one disables Jeandle
 * strip mining while enabling C2 counted-loop safepoints, and the other enables
 * Jeandle strip mining while disabling the C2 flag.
 */
public class TestStripMiningDisabled {

    private static final String CLASS = "TestStripMiningDisabled";
    private static final int N = 100000;
    private static final long NL = 100000L;
    private static final int SEED = 17;

    private static final String STRUCTURAL_OPTIONS =
        "--print-before=safepoint-poll-elimination --print-after=safepoint-poll-elimination " +
        "--print-before=safepoint-strip-mining --print-after=safepoint-strip-mining";
    private static final String TRACE_OPTIONS =
        "--debug-only=safepoint-poll-elimination,safepoint-strip-mining";

    private static final FlagVariant[] FLAG_VARIANTS = {
        new FlagVariant(false, "Jeandle disabled, C2 enabled",
                        "-XX:JeandleLoopStripMiningIter=0",
                        "-XX:+UseCountedLoopSafepoints"),
        new FlagVariant(true, "Jeandle enabled, C2 disabled",
                        "-XX:JeandleLoopStripMiningIter=1000",
                        "-XX:-UseCountedLoopSafepoints"),
    };

    private record FlagVariant(boolean jeandleEnabled, String description,
                               String... vmFlags) { }

    private static final String[] SCENARIOS = {
        "countedIntRuntimeBound", "countedIntBelowExclusiveLimit",
        "countedIntAtExclusiveLimit", "countedConst5000",
        "countedLongRuntimeBound"
    };

    // =========================================================================
    // Scenarios under test.
    // =========================================================================

    static int countedIntRuntimeBound(int n) {
        int s = 0;
        for (int i = 0; i < n; i++) s += i;
        return s;
    }

    static int countedIntBelowExclusiveLimit(boolean run, int seed) {
        int s = seed;
        if (run) {
            for (int i = 0; i < Integer.MAX_VALUE - 1; i++) {
                s = s * 31 + (i ^ seed);
            }
        }
        return s;
    }

    static int countedIntAtExclusiveLimit(boolean run, int seed) {
        int s = seed;
        if (run) {
            for (int i = 0; i < Integer.MAX_VALUE; i++) {
                s = s * 31 + (i ^ seed);
            }
        }
        return s;
    }

    static int countedConst5000() {
        int s = 0;
        for (int i = 0; i < 5000; i++) s += i;
        return s;
    }

    static long countedLongRuntimeBound(long n) {
        long s = 0;
        for (long i = 0; i < n; i++) s += i;
        return s;
    }

    // =========================================================================
    // Reference implementations (never Jeandle-compiled: not covered by the
    // child's compileonly commands).
    // =========================================================================

    static int refCountedIntRuntimeBound(int n) {
        int s = 0;
        for (int i = 0; i < n; i++) s += i;
        return s;
    }

    static int refCountedConst5000() {
        int s = 0;
        for (int i = 0; i < 5000; i++) s += i;
        return s;
    }

    static long refCountedLongRuntimeBound(long n) {
        long s = 0;
        for (long i = 0; i < n; i++) s += i;
        return s;
    }

    // =========================================================================
    // Driver / child dispatch.
    // =========================================================================

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("expected one mode argument");
        }
        switch (args[0]) {
            case "structural" -> runStructural();
            case "trace"      -> runTrace();
            case "child"      -> runChild();
            default -> throw new IllegalArgumentException("unknown mode: " + args[0]);
        }
    }

    private static void runChild() {
        System.out.println("RESULT countedIntRuntimeBound " + countedIntRuntimeBound(N));
        System.out.println("RESULT countedIntBelowExclusiveLimit " +
                           countedIntBelowExclusiveLimit(false, SEED));
        System.out.println("RESULT countedIntAtExclusiveLimit " +
                           countedIntAtExclusiveLimit(false, SEED));
        System.out.println("RESULT countedConst5000 " + countedConst5000());
        System.out.println("RESULT countedLongRuntimeBound " + countedLongRuntimeBound(NL));
    }

    private static OutputAnalyzer runChild(String llvmOptions, String... extraVmFlags) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("-Dtest.classes=" + System.getProperty("test.classes", "."));
        cmd.addAll(List.of("-Xcomp", "-Xbatch", "-XX:-TieredCompilation",
                           "-XX:+UnlockDiagnosticVMOptions", "-XX:+UseJeandleCompiler"));
        cmd.addAll(Arrays.asList(extraVmFlags));
        for (String m : SCENARIOS) {
            cmd.add("-XX:CompileCommand=compileonly," + CLASS + "::" + m);
        }
        cmd.add("-XX:JeandleLLVMOptions=" + llvmOptions);
        cmd.add(CLASS);
        cmd.add("child");
        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(cmd);
        return ProcessTools.executeProcess(pb);
    }

    private static void runStructural() throws Exception {
        for (FlagVariant variant : FLAG_VARIANTS) {
            OutputAnalyzer out = runChild(STRUCTURAL_OPTIONS, variant.vmFlags());
            out.shouldHaveExitValue(0);
            verifyResults(out.getStdout());
            if (variant.jeandleEnabled()) {
                checkEnabledStructural(out.getOutput(), variant.description());
            } else {
                checkDisabledStructural(out.getOutput(), variant.description());
            }
        }
        System.out.println("Structural checks passed.");
    }

    private static void runTrace() throws Exception {
        for (FlagVariant variant : FLAG_VARIANTS) {
            OutputAnalyzer out = runChild(TRACE_OPTIONS, variant.vmFlags());
            out.shouldHaveExitValue(0);
            verifyResults(out.getStdout());
            if (variant.jeandleEnabled()) {
                checkEnabledTrace(out.getOutput(), variant.description());
            } else {
                checkDisabledTrace(out.getOutput(), variant.description());
            }
        }
        System.out.println("Trace checks passed.");
    }

    private static void verifyResults(String stdout) {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("countedIntRuntimeBound", Integer.toString(refCountedIntRuntimeBound(N)));
        expected.put("countedIntBelowExclusiveLimit", Integer.toString(SEED));
        expected.put("countedIntAtExclusiveLimit", Integer.toString(SEED));
        expected.put("countedConst5000", Integer.toString(refCountedConst5000()));
        expected.put("countedLongRuntimeBound", Long.toString(refCountedLongRuntimeBound(NL)));
        for (Map.Entry<String, String> e : expected.entrySet()) {
            String line = "RESULT " + e.getKey() + " ";
            String value = null;
            for (String l : stdout.split("\\n")) {
                if (l.startsWith(line)) {
                    value = l.substring(line.length()).trim();
                }
            }
            Asserts.assertNotNull(value, "child did not print a result for " + e.getKey());
            Asserts.assertEquals(value, e.getValue(),
                e.getKey() + ": compiled result differs from reference");
        }
    }

    // =========================================================================
    // Checks, run once per disabling mechanism.
    // =========================================================================

    /** Strip mining disabled: SafepointStripMining is not in the pipeline and
     *  poll-elimination runs only early + loop-deletion-prep. */
    private static void checkPipelineShape(String out, String suffix, String variant) {
        Asserts.assertEquals(
            IRDumpParser.countSections(out, "Before", "SafepointStripMining", suffix), 0,
            variant + ": " + suffix + ": strip-mining must not run when disabled");
        Asserts.assertEquals(
            IRDumpParser.countSections(out, "Before", "SafepointPollElimination", suffix), 2,
            variant + ": " + suffix + ": poll-elimination must run exactly twice (early, loop-deletion-prep)");
    }

    private static int pollsInEarlyAfter(String out, String suffix) {
        String early = IRDumpParser.extractNthSection(out, "After", "SafepointPollElimination", suffix, 0);
        return IRDumpParser.countPolls(early);
    }

    private static void checkDisabledStructural(String out, String variant) {
        // Runtime-bound pre-test int loop: its symbolic maximum backedge count
        // is INT_MAX, so it does not satisfy the strict exclusive threshold.
        String intSuffix = CLASS + "_countedIntRuntimeBound";
        checkPipelineShape(out, intSuffix, variant);
        Asserts.assertEquals(pollsInEarlyAfter(out, intSuffix), 2,
            variant + ": " + intSuffix + ": boundary-risk loop must retain its loop poll");

        // A constant maximum immediately below INT_MAX satisfies the strict
        // threshold, while a maximum equal to INT_MAX does not. The false
        // runtime guard keeps these structural tests fast without making the
        // branch constant during compilation.
        String belowSuffix = CLASS + "_countedIntBelowExclusiveLimit";
        checkPipelineShape(out, belowSuffix, variant);
        Asserts.assertEquals(pollsInEarlyAfter(out, belowSuffix), 1,
            variant + ": " + belowSuffix + ": below-limit loop must leave only the return poll");

        String atSuffix = CLASS + "_countedIntAtExclusiveLimit";
        checkPipelineShape(out, atSuffix, variant);
        Asserts.assertEquals(pollsInEarlyAfter(out, atSuffix), 2,
            variant + ": " + atSuffix + ": exact-limit loop must retain its loop poll");

        // Constant-bounded int loop: its live recurrence is needed only by the
        // loop poll's deopt state, so deletion prep removes the loop and poll
        // together. Before deletion prep, the loop and return polls remain.
        String constSuffix = CLASS + "_countedConst5000";
        checkPipelineShape(out, constSuffix, variant);
        Asserts.assertEquals(pollsInEarlyAfter(out, constSuffix), 2,
            variant + ": " + constSuffix + ": early mode must preserve the deletion candidate");
        String afterDeletionPrep = IRDumpParser.extractNthSection(
            out, "After", "SafepointPollElimination", constSuffix, 1);
        Asserts.assertEquals(IRDumpParser.countPolls(afterDeletionPrep), 1,
            variant + ": " + constSuffix + ": deletion prep must leave only the return poll");

        // Runtime-bound long loop: keep-one, same reasoning as the int case.
        String longSuffix = CLASS + "_countedLongRuntimeBound";
        checkPipelineShape(out, longSuffix, variant);
        Asserts.assertEquals(pollsInEarlyAfter(out, longSuffix), 2,
            variant + ": " + longSuffix + ": keep-one must leave the loop poll plus the return poll");
    }

    private static void checkDisabledTrace(String out, String variant) {
        String intSuffix = CLASS + "_countedIntRuntimeBound";
        Asserts.assertTrue(IRDumpParser.traceChunkContains(out, intSuffix, "keep-one (keeper in"),
            variant + ": " + intSuffix + ": exact symbolic limit must retain one loop poll");
        // Match the pass-header prefix, not the bare word: the delete-all reason
        // string "int-counted-no-strip-mining" contains "strip-mining".
        Asserts.assertFalse(IRDumpParser.traceChunkContains(out, intSuffix, "strip-mining<"),
            variant + ": " + intSuffix + ": the strip-mining pass must not run when disabled");

        String belowSuffix = CLASS + "_countedIntBelowExclusiveLimit";
        Asserts.assertTrue(IRDumpParser.traceChunkContains(out, belowSuffix,
                "delete-all (int-counted-no-strip-mining), erased 1 of 1 poll(s)"),
            variant + ": " + belowSuffix + ": below-limit loop must delete its loop poll");

        String atSuffix = CLASS + "_countedIntAtExclusiveLimit";
        Asserts.assertTrue(IRDumpParser.traceChunkContains(out, atSuffix, "keep-one (keeper in"),
            variant + ": " + atSuffix + ": exact-limit loop must retain one loop poll");

        String constSuffix = CLASS + "_countedConst5000";
        Asserts.assertTrue(IRDumpParser.traceChunkContains(out, constSuffix,
                "loop-deletion-prep: deleting"),
            variant + ": " + constSuffix + ": deletion prep must delete the constant loop");

        String longSuffix = CLASS + "_countedLongRuntimeBound";
        Asserts.assertTrue(IRDumpParser.traceChunkContains(out, longSuffix, "keep-one (keeper in"),
            variant + ": " + longSuffix + ": early mode must keep one poll for a runtime-bound loop");
    }

    private static void checkEnabledStructural(String out, String variant) {
        String suffix = CLASS + "_countedIntRuntimeBound";
        Asserts.assertEquals(
            IRDumpParser.countSections(out, "Before", "SafepointStripMining", suffix), 2,
            variant + ": strip-mining must run when the Jeandle budget is nonzero");
        Asserts.assertEquals(
            IRDumpParser.countSections(out, "Before", "SafepointPollElimination", suffix), 3,
            variant + ": all three poll-elimination modes must run");
        boolean wrapped = false;
        for (String section : IRDumpParser.extractSections(
                out, "After", "SafepointStripMining", suffix)) {
            wrapped |= section.contains(".outer.latch");
        }
        Asserts.assertTrue(wrapped,
            variant + ": runtime-bound int loop must be strip-mined despite the C2 flag");
    }

    private static void checkEnabledTrace(String out, String variant) {
        String suffix = CLASS + "_countedIntRuntimeBound";
        Asserts.assertTrue(IRDumpParser.traceChunkContains(out, suffix,
                "strip-mine: wrapped loop"),
            variant + ": trace must show Jeandle strip mining despite the C2 flag");
    }
}
