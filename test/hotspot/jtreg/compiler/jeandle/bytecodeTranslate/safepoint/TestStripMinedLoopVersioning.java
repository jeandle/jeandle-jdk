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

/*
 * @test
 * @summary Verify runtime-versioned inclusive loops and their guarded slow paths
 * @library /test/lib /
 * @build jdk.test.lib.Asserts compiler.jeandle.utils.IRDumpParser
 * @run driver TestStripMinedLoopVersioning structural
 */
/*
 * @test
 * @summary Verify inclusive-loop versioning decisions via pass trace
 * @requires vm.debug
 * @library /test/lib /
 * @build jdk.test.lib.Asserts compiler.jeandle.utils.IRDumpParser
 * @run driver TestStripMinedLoopVersioning trace
 */
/*
 * @test
 * @summary Verify disabling inclusive-loop versioning safely retains loop polls
 * @requires vm.debug
 * @library /test/lib /
 * @build jdk.test.lib.Asserts compiler.jeandle.utils.IRDumpParser
 * @run driver TestStripMinedLoopVersioning versioningDisabled
 */
/*
 * @test
 * @summary Verify a strip-mining budget below two safely retains loop polls
 * @requires vm.debug
 * @library /test/lib /
 * @build jdk.test.lib.Asserts compiler.jeandle.utils.IRDumpParser
 * @run driver TestStripMinedLoopVersioning budgetOne
 */

import java.util.ArrayList;
import java.util.List;

import compiler.jeandle.utils.IRDumpParser;
import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestStripMinedLoopVersioning {
    private static final String CLASS = "TestStripMinedLoopVersioning";
    private static final int BUDGET = 8;
    private static final String STRUCTURAL_OPTIONS =
        "--print-after=safepoint-strip-mining " +
        "--print-after=safepoint-poll-elimination";
    private static final String TRACE_OPTIONS =
        "--debug-only=safepoint-strip-mining,safepoint-poll-elimination";
    private static final List<String> METHODS =
        List.of("inclusiveIncreasing", "inclusiveDecreasing");

    static int inclusiveIncreasing(int start, int limit, int seed) {
        int hash = seed;
        for (int i = start; i <= limit; i++) {
            hash = hash * 31 + (i ^ seed);
        }
        return hash;
    }

    static int inclusiveDecreasing(int start, int limit, int seed) {
        int hash = seed;
        for (int i = start; i >= limit; i--) {
            hash = hash * 31 + (i ^ seed);
        }
        return hash;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("expected one mode argument");
        }
        switch (args[0]) {
            case "structural" -> runStructural();
            case "trace" -> runTrace();
            case "versioningDisabled" -> runVersioningDisabled();
            case "budgetOne" -> runBudgetOne();
            case "child" -> runChild();
            default -> throw new IllegalArgumentException("unknown mode: " + args[0]);
        }
    }

    private static void runChild() {
        check("increasing", inclusiveIncreasing(3, 40, 17),
              refIncreasing(3, 40, 17));
        check("decreasing", inclusiveDecreasing(40, 3, 17),
              refDecreasing(40, 3, 17));
        check("increasingZeroTrip", inclusiveIncreasing(10, 5, 17), 17);
        check("decreasingZeroTrip", inclusiveDecreasing(5, 10, 17), 17);

        check("increasingNearMax",
              inclusiveIncreasing(Integer.MAX_VALUE - 5,
                                  Integer.MAX_VALUE - 2, 17),
              refIncreasing(Integer.MAX_VALUE - 5,
                            Integer.MAX_VALUE - 2, 17));
        check("decreasingNearMin",
              inclusiveDecreasing(Integer.MIN_VALUE + 5,
                                  Integer.MIN_VALUE + 2, 17),
              refDecreasing(Integer.MIN_VALUE + 5,
                            Integer.MIN_VALUE + 2, 17));

        // These limits fail the no-wrap guard. The first-iteration guard sends
        // the zero-trip invocation to the original polling loop without ever
        // executing the wrapping increment/decrement.
        check("increasingRiskLimitZeroTrip",
              inclusiveIncreasing(Integer.MAX_VALUE,
                                  Integer.MAX_VALUE - 1, 17), 17);
        check("decreasingRiskLimitZeroTrip",
              inclusiveDecreasing(Integer.MIN_VALUE,
                                  Integer.MIN_VALUE + 1, 17), 17);
    }

    private static int refIncreasing(int start, int limit, int seed) {
        int hash = seed;
        for (int i = start; i <= limit; i++) hash = hash * 31 + (i ^ seed);
        return hash;
    }

    private static int refDecreasing(int start, int limit, int seed) {
        int hash = seed;
        for (int i = start; i >= limit; i--) hash = hash * 31 + (i ^ seed);
        return hash;
    }

    private static void check(String name, int actual, int expected) {
        if (actual != expected) {
            throw new RuntimeException(name + ": expected " + expected +
                                       ", got " + actual);
        }
        System.out.println("RESULT " + name + " " + actual);
    }

    private static OutputAnalyzer runChild(String llvmOptions, int budget)
            throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("-Dtest.classes=" + System.getProperty("test.classes", "."));
        cmd.addAll(List.of("-Xcomp", "-Xbatch", "-XX:-TieredCompilation",
                           "-XX:+UnlockDiagnosticVMOptions",
                           "-XX:+UseJeandleCompiler",
                           "-XX:JeandleLoopStripMiningIter=" + budget));
        for (String method : METHODS) {
            cmd.add("-XX:CompileCommand=compileonly," + CLASS + "::" + method);
        }
        cmd.add("-XX:JeandleLLVMOptions=" + llvmOptions);
        cmd.add(CLASS);
        cmd.add("child");
        return ProcessTools.executeProcess(
            ProcessTools.createLimitedTestJavaProcessBuilder(cmd));
    }

    private static String suffix(String method) {
        return CLASS + "_" + method;
    }

    private static String minedSection(String output, String method) {
        for (String section : IRDumpParser.extractSections(
                output, "After", "SafepointStripMining", suffix(method))) {
            if (section.contains(".outer.latch")) return section;
        }
        return "";
    }

    private static void assertVersionedAndMined(String output, String method,
                                                String intrinsic) {
        String section = minedSection(output, method);
        Asserts.assertFalse(section.isEmpty(),
            method + ": expected the guarded fast loop to be strip-mined");
        IRDumpParser.assertContains(section, ".inclusive.slow",
            method + ": the original polling loop must remain as a slow path");
        IRDumpParser.assertContains(section, "inclusive.no_wrap",
            method + ": the fast path must be protected by a no-wrap guard");
        IRDumpParser.assertContains(section, intrinsic,
            method + ": the outer batch limit must use an SCEV-friendly clamped min");
        IRDumpParser.assertContains(section, IRDumpParser.POLL_CALL + " #",
            method + ": the relocated outer poll must carry its marker");

        String afterElimination = IRDumpParser.extractNthSection(
            output, "After", "SafepointPollElimination", suffix(method), 1);
        Asserts.assertEquals(IRDumpParser.countPolls(afterElimination), 3,
            method + ": outer, slow-path and return polls must survive");
    }

    private static void assertTrace(String output, String method, String needle) {
        Asserts.assertTrue(IRDumpParser.traceChunkContains(
                output, suffix(method), needle),
            method + ": expected trace decision: " + needle + ", trace was: " +
            String.join(" | ", IRDumpParser.extractTraceChunk(
                output, suffix(method))));
    }

    private static void assertUnminedTrace(String output, String method,
                                           String reason) {
        assertTrace(output, method, reason);
        Asserts.assertFalse(IRDumpParser.traceChunkContains(
                output, suffix(method), "strip-mine: wrapped loop"),
            method + ": rejected loop must remain unwrapped");
        assertTrace(output, method, "keep-one");
    }

    private static void runStructural() throws Exception {
        OutputAnalyzer out = runChild(STRUCTURAL_OPTIONS, BUDGET);
        out.shouldHaveExitValue(0);
        assertVersionedAndMined(out.getOutput(), "inclusiveIncreasing",
                               "llvm.smin");
        assertVersionedAndMined(out.getOutput(), "inclusiveDecreasing",
                               "llvm.smin");
    }

    private static void runTrace() throws Exception {
        OutputAnalyzer out = runChild(TRACE_OPTIONS, BUDGET);
        out.shouldHaveExitValue(0);
        for (String method : METHODS) {
            assertTrace(out.getOutput(), method,
                        "inclusive-versioning: versioned");
            assertTrace(out.getOutput(), method, "strip-mine: wrapped loop");
            assertTrace(out.getOutput(), method,
                        "N=8, inclusive=1, batch-stride=7");
        }
    }

    private static void runVersioningDisabled() throws Exception {
        OutputAnalyzer out = runChild(
            TRACE_OPTIONS + " -jeandle-enable-inclusive-loop-versioning=false",
            BUDGET);
        out.shouldHaveExitValue(0);
        for (String method : METHODS) {
            assertUnminedTrace(out.getOutput(), method,
                               "inclusive loop not versionable");
        }
    }

    private static void runBudgetOne() throws Exception {
        OutputAnalyzer out = runChild(TRACE_OPTIONS, 1);
        out.shouldHaveExitValue(0);
        for (String method : METHODS) {
            assertUnminedTrace(out.getOutput(), method,
                               "chunk budget below 2");
        }
    }
}
