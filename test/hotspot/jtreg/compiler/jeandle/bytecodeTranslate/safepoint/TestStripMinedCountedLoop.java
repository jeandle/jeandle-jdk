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
 * @summary Verify counted loops are strip-mined and short loops drop polls (structural IR checks)
 * @library /test/lib /
 * @build jdk.test.lib.Asserts compiler.jeandle.utils.IRDumpParser
 * @run driver TestStripMinedCountedLoop structural
 */
/*
 * @test
 * @summary Verify strip-mining and poll-elimination decisions via pass trace
 * @requires vm.debug
 * @library /test/lib /
 * @build jdk.test.lib.Asserts compiler.jeandle.utils.IRDumpParser
 * @run driver TestStripMinedCountedLoop trace
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
 * Drives one child JVM per observation mode and checks the SafepointStripMining /
 * SafepointPollElimination behavior on a set of counted loops:
 *
 *  - structural: slices "--print-before/after=<pass>" IR dumps out of the child's
 *    stderr. SafepointPollElimination runs 3 times per method with strip mining on
 *    (early / after-strip-mining / loop-deletion-prep), SafepointStripMining twice
 *    (inclusive-loop-versioning / strip-mining); runs appear as dump sections in
 *    pipeline order, distinguished by section index.
 *  - trace: matches "-debug-only" lines of the two passes (fastdebug only).
 *
 * The driver also compares every scenario's computed sum against a reference
 * loop: the strip-mining clamp rewrites the loop bound, so a CFG-surgery
 * miscompile would show up as a wrong sum.
 */
public class TestStripMinedCountedLoop {

    private static final String CLASS = "TestStripMinedCountedLoop";
    private static final int N = 100000;
    private static final long NL = 100000L;
    private static final int SEED = 17;

    private static final String STRUCTURAL_OPTIONS =
        "--print-before=safepoint-poll-elimination --print-after=safepoint-poll-elimination " +
        "--print-before=safepoint-strip-mining --print-after=safepoint-strip-mining";
    private static final String TRACE_OPTIONS =
        "--debug-only=safepoint-poll-elimination,safepoint-strip-mining";

    // The relocated outer-latch poll carries the "jeandle.strip-mined-poll"
    // call-site attribute. Function-scope IR dumps print call-site attribute
    // groups as "#N" (the group definition is module-level and not printed), so
    // the marked poll is recognizable as a "#"-suffixed poll call.
    private static final String MARKED_POLL = IRDumpParser.POLL_CALL + " #";

    private static final String[] SCENARIOS = {
        "countedIntRuntimeBound", "countedShortConstant", "countedOverBudgetConstant",
        "countedLongRuntimeBound",
        "countedDecreasing", "countedStride3ConstBound", "countedStride3RuntimeBound",
        "emptyCountedLoop"
    };

    // =========================================================================
    // Scenarios under test.
    // =========================================================================

    static int countedIntRuntimeBound(int n) {
        int s = 0;
        for (int i = 0; i < n; i++) s += i;
        return s;
    }

    static int countedShortConstant(int seed) {
        int s = seed;
        for (int i = 0; i < 1000; i++) s = s * 31 + (i ^ seed);
        return s;
    }

    static int countedOverBudgetConstant(int seed) {
        int s = seed;
        for (int i = 0; i < 1001; i++) s = s * 31 + (i ^ seed);
        return s;
    }

    static long countedLongRuntimeBound(long n) {
        long s = 0;
        for (long i = 0; i < n; i++) s += i;
        return s;
    }

    static int countedDecreasing(int n) {
        int s = 0;
        for (int i = n; i > 0; i--) s += i;
        return s;
    }

    static int countedStride3ConstBound(int seed) {
        int s = seed;
        for (int i = 0; i < 300000; i += 3) s = s * 31 + (i ^ seed);
        return s;
    }

    static int countedStride3RuntimeBound(int n) {
        int s = 0;
        for (int i = 0; i < n; i += 3) s += i;
        return s;
    }

    static void emptyCountedLoop() {
        for (int i = 0; i < 5000; i++) { }
    }

    // =========================================================================
    // Reference implementations (never Jeandle-compiled: not covered by the
    // child's compileonly commands). Bodies mirror the scenarios so Java
    // overflow semantics match exactly.
    // =========================================================================

    static int refCountedIntRuntimeBound(int n) {
        int s = 0;
        for (int i = 0; i < n; i++) s += i;
        return s;
    }

    static int refCountedShortConstant(int seed) {
        int s = seed;
        for (int i = 0; i < 1000; i++) s = s * 31 + (i ^ seed);
        return s;
    }

    static int refCountedOverBudgetConstant(int seed) {
        int s = seed;
        for (int i = 0; i < 1001; i++) s = s * 31 + (i ^ seed);
        return s;
    }

    static long refCountedLongRuntimeBound(long n) {
        long s = 0;
        for (long i = 0; i < n; i++) s += i;
        return s;
    }

    static int refCountedDecreasing(int n) {
        int s = 0;
        for (int i = n; i > 0; i--) s += i;
        return s;
    }

    static int refCountedStride3ConstBound(int seed) {
        int s = seed;
        for (int i = 0; i < 300000; i += 3) s = s * 31 + (i ^ seed);
        return s;
    }

    static int refCountedStride3RuntimeBound(int n) {
        int s = 0;
        for (int i = 0; i < n; i += 3) s += i;
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
        System.out.println("RESULT countedShortConstant " + countedShortConstant(SEED));
        System.out.println("RESULT countedOverBudgetConstant " + countedOverBudgetConstant(SEED));
        System.out.println("RESULT countedLongRuntimeBound " + countedLongRuntimeBound(NL));
        System.out.println("RESULT countedDecreasing " + countedDecreasing(N));
        System.out.println("RESULT countedStride3ConstBound " + countedStride3ConstBound(SEED));
        System.out.println("RESULT countedStride3RuntimeBound " + countedStride3RuntimeBound(N));
        emptyCountedLoop();
        System.out.println("RESULT emptyCountedLoop done");
    }

    private static OutputAnalyzer runChild(String llvmOptions, String... extraVmFlags) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("-Dtest.classes=" + System.getProperty("test.classes", "."));
        cmd.addAll(List.of("-Xcomp", "-Xbatch", "-XX:-TieredCompilation",
                           "-XX:+UnlockDiagnosticVMOptions", "-XX:+UseJeandleCompiler",
                           // These tests unit-test safepoint-pass decisions on
                           // frontend-shaped IR. The pre-PEA loop canonicalization
                           // legitimately changes those decisions, so pin the
                           // pipeline to PEA-off (same treatment as the LLVM-side
                           // SafepointElimination/pipeline-position.ll test).
                           "-XX:-JeandleDoPEA",
                           // Pin the default so a default change does not
                           // silently flip expectations.
                           "-XX:JeandleLoopStripMiningIter=1000"));
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
        OutputAnalyzer out = runChild(STRUCTURAL_OPTIONS);
        out.shouldHaveExitValue(0);
        verifyResults(out.getStdout());
        String dump = out.getOutput();
        checkCountedIntRuntimeBoundStructural(dump);
        checkCountedShortConstantStructural(dump);
        checkCountedOverBudgetConstantStructural(dump);
        checkCountedLongRuntimeBoundStructural(dump);
        checkCountedDecreasingStructural(dump);
        checkCountedStride3ConstBoundStructural(dump);
        checkCountedStride3RuntimeBoundStructural(dump);
        checkEmptyCountedLoopStructural(dump);
        System.out.println("Structural checks passed.");
    }

    private static void runTrace() throws Exception {
        OutputAnalyzer out = runChild(TRACE_OPTIONS);
        out.shouldHaveExitValue(0);
        verifyResults(out.getStdout());
        String trace = out.getOutput();
        checkCountedIntRuntimeBoundTrace(trace);
        checkCountedShortConstantTrace(trace);
        checkCountedOverBudgetConstantTrace(trace);
        checkCountedLongRuntimeBoundTrace(trace);
        checkCountedDecreasingTrace(trace);
        checkCountedStride3ConstBoundTrace(trace);
        checkCountedStride3RuntimeBoundTrace(trace);
        checkEmptyCountedLoopTrace(trace);
        System.out.println("Trace checks passed.");
    }

    private static void verifyResults(String stdout) {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("countedIntRuntimeBound", Integer.toString(refCountedIntRuntimeBound(N)));
        expected.put("countedShortConstant", Integer.toString(refCountedShortConstant(SEED)));
        expected.put("countedOverBudgetConstant", Integer.toString(refCountedOverBudgetConstant(SEED)));
        expected.put("countedLongRuntimeBound", Long.toString(refCountedLongRuntimeBound(NL)));
        expected.put("countedDecreasing", Integer.toString(refCountedDecreasing(N)));
        expected.put("countedStride3ConstBound", Integer.toString(refCountedStride3ConstBound(SEED)));
        expected.put("countedStride3RuntimeBound", Integer.toString(refCountedStride3RuntimeBound(N)));
        expected.put("emptyCountedLoop", "done");
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
                e.getKey() + ": compiled result differs from reference (strip-mining clamp miscompile?)");
        }
    }

    // =========================================================================
    // Structural helpers.
    // =========================================================================

    /** Body of the first SafepointStripMining after-section containing needle, or null. */
    private static String findStripMiningAfter(String out, String suffix, String needle) {
        int n = IRDumpParser.countSections(out, "After", "SafepointStripMining", suffix);
        for (int i = 0; i < n; i++) {
            String section = IRDumpParser.extractNthSection(out, "After", "SafepointStripMining", suffix, i);
            if (section.contains(needle)) {
                return section;
            }
        }
        return null;
    }

    private static String pollElimAfter(String out, String suffix, int n) {
        return IRDumpParser.extractNthSection(out, "After", "SafepointPollElimination", suffix, n);
    }

    /** Strip mining enabled: poll-elimination runs 3x, strip-mining 2x per method. */
    private static void checkPipelineShape(String out, String suffix) {
        Asserts.assertEquals(
            IRDumpParser.countSections(out, "Before", "SafepointPollElimination", suffix), 3,
            suffix + ": poll-elimination must run 3 times (early, after-strip-mining, loop-deletion-prep)");
        Asserts.assertEquals(
            IRDumpParser.countSections(out, "After", "SafepointStripMining", suffix), 2,
            suffix + ": strip-mining must run 2 times (inclusive-loop-versioning, strip-mining)");
    }

    // =========================================================================
    // countedIntRuntimeBound: canonical runtime-bound counted loop. Strip-mined
    // into an outer loop polling every 1000-iteration batch; the inner loop runs
    // poll-free under the SCEV-friendly clamped limit.
    // =========================================================================

    private static void checkCountedIntRuntimeBoundStructural(String out) {
        String suffix = CLASS + "_countedIntRuntimeBound";
        checkPipelineShape(out, suffix);
        String wrapped = findStripMiningAfter(out, suffix, ".outer.latch");
        Asserts.assertNotNull(wrapped, suffix + ": expected a strip-mined (.outer.latch) loop nest");
        IRDumpParser.assertContains(wrapped, "llvm.smin",
            suffix + ": outer batch limit must be clamped with an SCEV-friendly min");
        IRDumpParser.assertContains(wrapped, MARKED_POLL,
            suffix + ": poll relocated to the outer latch must carry the strip-mined-poll marker");
        // after-strip-mining keeps the relocated outer-latch poll plus the return poll.
        Asserts.assertEquals(IRDumpParser.countPolls(pollElimAfter(out, suffix, 1)), 2,
            suffix + ": after-strip-mining must keep exactly the outer-latch and return polls");
    }

    private static void checkCountedIntRuntimeBoundTrace(String out) {
        String suffix = CLASS + "_countedIntRuntimeBound";
        Asserts.assertTrue(IRDumpParser.traceChunkContains(out, suffix, "strip-mine: wrapped loop"),
            suffix + ": expected the loop to be strip-mined");
        Asserts.assertTrue(IRDumpParser.traceChunkContains(out, suffix, "(N=1000, inclusive=0, batch-stride=1000"),
            suffix + ": expected default batch size 1000, exclusive bound");
        Asserts.assertTrue(IRDumpParser.traceChunkContains(out, suffix, "keep-one (keeper in "),
            suffix + ": after-strip-mining must keep the relocated poll");
        Asserts.assertTrue(IRDumpParser.traceChunkContains(out, suffix, ".outer.latch), erased 0 of 1 poll(s)"),
            suffix + ": the single relocated poll in the outer latch must survive");
    }

    // =========================================================================
    // countedShortConstant: exactly 1000 iterations fit within the
    // strip-mining budget, so the loop is left alone and after-strip-mining
    // deletes its poll.
    // =========================================================================

    private static void checkCountedShortConstantStructural(String out) {
        String suffix = CLASS + "_countedShortConstant";
        checkPipelineShape(out, suffix);
        Asserts.assertNull(findStripMiningAfter(out, suffix, ".outer"),
            suffix + ": a within-budget loop must not be strip-mined");
        Asserts.assertEquals(IRDumpParser.countPolls(pollElimAfter(out, suffix, 1)), 1,
            suffix + ": within-budget loop poll must be deleted, only the return poll remains");
    }

    private static void checkCountedShortConstantTrace(String out) {
        String suffix = CLASS + "_countedShortConstant";
        Asserts.assertTrue(IRDumpParser.traceChunkContains(out, suffix, "within budget (short loop)"),
            suffix + ": strip-mining must reject the short loop as within budget");
        Asserts.assertTrue(IRDumpParser.traceChunkContains(out, suffix, "delete-all (within-budget), erased 1 of 1 poll(s)"),
            suffix + ": after-strip-mining must delete the within-budget loop poll");
        Asserts.assertFalse(IRDumpParser.traceChunkContains(out, suffix, "wrapped loop"),
            suffix + ": a within-budget loop must not be wrapped");
    }

    // =========================================================================
    // countedOverBudgetConstant: 1001 iterations exceed a 1000-iteration
    // budget. Max-backedge-count and trip-count differ by one, so this case
    // must be wrapped instead of being classified as a short loop.
    // =========================================================================

    private static void checkCountedOverBudgetConstantStructural(String out) {
        String suffix = CLASS + "_countedOverBudgetConstant";
        checkPipelineShape(out, suffix);
        String wrapped = findStripMiningAfter(out, suffix, ".outer.latch");
        Asserts.assertNotNull(wrapped,
            suffix + ": 1001 iterations must exceed the 1000-iteration budget");
        IRDumpParser.assertContains(wrapped, MARKED_POLL,
            suffix + ": the over-budget loop must retain outer-latch coverage");
        Asserts.assertEquals(IRDumpParser.countPolls(pollElimAfter(out, suffix, 1)), 2,
            suffix + ": outer-latch and return polls must survive");
    }

    private static void checkCountedOverBudgetConstantTrace(String out) {
        String suffix = CLASS + "_countedOverBudgetConstant";
        Asserts.assertTrue(IRDumpParser.traceChunkContains(out, suffix, "strip-mine: wrapped loop"),
            suffix + ": expected the 1001-iteration loop to be strip-mined");
        Asserts.assertFalse(IRDumpParser.traceChunkContains(out, suffix, "within budget (short loop)"),
            suffix + ": max backedge count must not be mistaken for trip count");
    }

    // =========================================================================
    // countedLongRuntimeBound: long-IV variant of the canonical case.
    // =========================================================================

    private static void checkCountedLongRuntimeBoundStructural(String out) {
        String suffix = CLASS + "_countedLongRuntimeBound";
        checkPipelineShape(out, suffix);
        String wrapped = findStripMiningAfter(out, suffix, ".outer.latch");
        Asserts.assertNotNull(wrapped, suffix + ": expected a strip-mined (.outer.latch) loop nest");
        IRDumpParser.assertContains(wrapped, "llvm.smin",
            suffix + ": outer batch limit must be clamped with an SCEV-friendly min");
        Asserts.assertEquals(IRDumpParser.countPolls(pollElimAfter(out, suffix, 1)), 2,
            suffix + ": after-strip-mining must keep exactly the outer-latch and return polls");
    }

    private static void checkCountedLongRuntimeBoundTrace(String out) {
        String suffix = CLASS + "_countedLongRuntimeBound";
        Asserts.assertTrue(IRDumpParser.traceChunkContains(out, suffix, "strip-mine: wrapped loop"),
            suffix + ": expected the long counted loop to be strip-mined");
    }

    // =========================================================================
    // countedDecreasing: a decreasing IV cannot prove exclusive no-wrap up
    // front, so inclusive-loop-versioning clones a slow path behind a no-wrap
    // guard; the provably-no-wrap fast path is then strip-mined with an
    // inclusive bound (batch-stride 999 = (N-1) * stride 1). Poll elimination
    // keeps one poll per loop: outer latch, slow-path clone, plus the return
    // poll (3 in total).
    // =========================================================================

    private static void checkCountedDecreasingStructural(String out) {
        String suffix = CLASS + "_countedDecreasing";
        checkPipelineShape(out, suffix);
        String wrapped = findStripMiningAfter(out, suffix, ".outer.latch");
        Asserts.assertNotNull(wrapped, suffix + ": expected the versioned fast path to be strip-mined");
        IRDumpParser.assertContains(wrapped, "llvm.smin",
            suffix + ": decreasing IV must be clamped with an SCEV-friendly min");
        Asserts.assertEquals(IRDumpParser.countPolls(pollElimAfter(out, suffix, 1)), 3,
            suffix + ": outer-latch poll + slow-path poll + return poll must survive");
    }

    private static void checkCountedDecreasingTrace(String out) {
        String suffix = CLASS + "_countedDecreasing";
        Asserts.assertTrue(IRDumpParser.traceChunkContains(out, suffix, "inclusive-versioning: versioned"),
            suffix + ": decreasing IV needs inclusive loop versioning before strip-mining");
        Asserts.assertTrue(IRDumpParser.traceChunkContains(out, suffix, "strip-mine: wrapped loop"),
            suffix + ": the versioned fast path must be strip-mined");
    }

    // =========================================================================
    // countedStride3ConstBound: constant bound makes no-wrap provable without
    // versioning. The bound is an exact multiple of the stride, so the wrapped
    // loop is inclusive and one batch spans (N-1) * stride = 999 * 3 = 2997.
    // =========================================================================

    private static void checkCountedStride3ConstBoundStructural(String out) {
        String suffix = CLASS + "_countedStride3ConstBound";
        checkPipelineShape(out, suffix);
        String wrapped = findStripMiningAfter(out, suffix, ".outer.latch");
        Asserts.assertNotNull(wrapped, suffix + ": expected a strip-mined (.outer.latch) loop nest");
        IRDumpParser.assertContains(wrapped, MARKED_POLL,
            suffix + ": poll relocated to the outer latch must carry the strip-mined-poll marker");
    }

    private static void checkCountedStride3ConstBoundTrace(String out) {
        String suffix = CLASS + "_countedStride3ConstBound";
        Asserts.assertTrue(IRDumpParser.traceChunkContains(out, suffix, "strip-mine: wrapped loop"),
            suffix + ": expected the stride-3 constant-bound loop to be strip-mined");
        Asserts.assertTrue(IRDumpParser.traceChunkContains(out, suffix, "batch-stride=2997"),
            suffix + ": inclusive batch must span (N-1)*stride = 999*3 = 2997");
    }

    // =========================================================================
    // countedStride3RuntimeBound: Java int addition has no nsw guarantee, so
    // with a runtime bound SCEV cannot prove the strided IV does not wrap and
    // both strip-mining runs reject the loop. Poll elimination falls back to
    // keep-one: the original back-edge poll survives (plus the return poll).
    // =========================================================================

    private static void checkCountedStride3RuntimeBoundStructural(String out) {
        String suffix = CLASS + "_countedStride3RuntimeBound";
        checkPipelineShape(out, suffix);
        Asserts.assertNull(findStripMiningAfter(out, suffix, ".outer"),
            suffix + ": unprovable no-wrap must leave the loop unwrapped");
        Asserts.assertEquals(IRDumpParser.countPolls(pollElimAfter(out, suffix, 1)), 2,
            suffix + ": keep-one must leave the back-edge poll plus the return poll");
    }

    private static void checkCountedStride3RuntimeBoundTrace(String out) {
        String suffix = CLASS + "_countedStride3RuntimeBound";
        Asserts.assertTrue(IRDumpParser.traceChunkContains(out, suffix, "cannot prove exclusive no-wrap"),
            suffix + ": strip-mining must reject the strided runtime-bound loop");
        Asserts.assertTrue(IRDumpParser.traceChunkContains(out, suffix, "keep-one"),
            suffix + ": poll elimination must keep the surviving back-edge poll");
        Asserts.assertFalse(IRDumpParser.traceChunkContains(out, suffix, "wrapped loop"),
            suffix + ": unprovable no-wrap must leave the loop unwrapped");
    }

    // =========================================================================
    // emptyCountedLoop: no deopt-only live-out is needed. Strip mining defers
    // the candidate, then LoopDeletionPrep removes the poll and loop together.
    // =========================================================================

    private static void checkEmptyCountedLoopStructural(String out) {
        String suffix = CLASS + "_emptyCountedLoop";
        checkPipelineShape(out, suffix);
        Asserts.assertNull(findStripMiningAfter(out, suffix, ".outer.latch"),
            suffix + ": empty-loop deletion candidates must not be strip-mined");
        Asserts.assertEquals(IRDumpParser.countPolls(pollElimAfter(out, suffix, 1)), 2,
            suffix + ": deletion prep must receive the loop and return polls intact");
        Asserts.assertEquals(IRDumpParser.countPolls(pollElimAfter(out, suffix, 2)), 1,
            suffix + ": loop-deletion-prep must leave only the return poll");
    }

    private static void checkEmptyCountedLoopTrace(String out) {
        String suffix = CLASS + "_emptyCountedLoop";
        Asserts.assertTrue(IRDumpParser.traceChunkContains(out, suffix, "empty-loop deletion candidate"),
            suffix + ": strip mining must defer the empty loop");
        Asserts.assertTrue(IRDumpParser.traceChunkContains(out, suffix, "loop-deletion-prep: deleting"),
            suffix + ": deletion prep must delete a loop with no deopt live-out");
        Asserts.assertFalse(IRDumpParser.traceChunkContains(out, suffix, "strip-mine: wrapped loop"),
            suffix + ": an empty-loop deletion candidate must not be wrapped");
    }
}
