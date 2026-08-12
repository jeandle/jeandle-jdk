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
 * @summary Verify strip mining across canonical Java loop shapes
 * @library /test/lib /
 * @build jdk.test.lib.Asserts compiler.jeandle.utils.IRDumpParser
 * @run driver TestStripMinedLoopShapes structural
 */
/*
 * @test
 * @summary Verify strip-mining shape decisions via pass trace
 * @requires vm.debug
 * @library /test/lib /
 * @build jdk.test.lib.Asserts compiler.jeandle.utils.IRDumpParser
 * @run driver TestStripMinedLoopShapes trace
 */

import java.util.ArrayList;
import java.util.List;

import compiler.jeandle.utils.IRDumpParser;
import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestStripMinedLoopShapes {
    private static final String CLASS = "TestStripMinedLoopShapes";
    private static final int BUDGET = 8;
    private static final String STRUCTURAL_OPTIONS =
        "--print-after=safepoint-strip-mining " +
        "--print-after=safepoint-poll-elimination";
    private static final String TRACE_OPTIONS =
        "--debug-only=safepoint-strip-mining,safepoint-poll-elimination";
    private static final String[] SCENARIOS = {
        "exclusiveLess", "decreasingArray", "notEqualIncreasing",
        "strideTwoConstant", "arrayLength", "twoRecurrences",
        "withContinue", "withBreak", "withEarlyReturn", "exactBudget",
        "overBudget", "exclusiveDoWhile", "inclusiveDoWhile"
    };

    static long exclusiveLess(int limit) {
        long sum = 0;
        for (int i = 0; i < limit; i++) {
            sum += i;
        }
        return sum;
    }

    static long decreasingArray(int[] values) {
        long sum = 0;
        for (int i = values.length - 1; i >= 0; i--) {
            sum += values[i];
        }
        return sum;
    }

    static long notEqualIncreasing(int limit) {
        if (limit < 0) {
            return 0;
        }
        long sum = 0;
        for (int i = 0; i != limit; i++) {
            sum += i;
        }
        return sum;
    }

    static long strideTwoConstant(int seed) {
        long sum = seed;
        for (int i = 0; i < 42; i += 2) {
            sum = sum * 31 + (i ^ seed);
        }
        return sum;
    }

    static long arrayLength(int[] values) {
        long sum = 0;
        for (int i = 0; i < values.length; i++) {
            sum += values[i];
        }
        return sum;
    }

    static long twoRecurrences(int n) {
        long sum = 3;
        long hash = 5;
        for (int i = 0; i < n; i++) {
            sum += i * 7L;
            hash = hash * 31 + i;
        }
        return sum ^ hash;
    }

    static long withContinue(int n) {
        long sum = 0;
        for (int i = 0; i < n; i++) {
            if ((i & 3) == 0) {
                continue;
            }
            sum += i;
        }
        return sum;
    }

    static long withBreak(int n, int stop) {
        long sum = 0;
        for (int i = 0; i < n; i++) {
            if (i == stop) {
                break;
            }
            sum += i;
        }
        return sum;
    }

    static long withEarlyReturn(int n, int stop) {
        long sum = 0;
        for (int i = 0; i < n; i++) {
            if (i == stop) {
                return sum - i;
            }
            sum += i;
        }
        return sum;
    }

    static long exactBudget(int seed) {
        long sum = seed;
        for (int i = 0; i < BUDGET; i++) {
            sum = sum * 31 + (i ^ seed);
        }
        return sum;
    }

    static long overBudget(int seed) {
        long sum = seed;
        for (int i = 0; i < BUDGET + 1; i++) {
            sum = sum * 31 + (i ^ seed);
        }
        return sum;
    }

    static long exclusiveDoWhile(int limit) {
        long sum = 0;
        int count = 0;
        int i = 0;
        do {
            sum += i;
            count++;
            i++;
        } while (i < limit);
        return (sum << 32) ^ count;
    }

    static long inclusiveDoWhile(int limit) {
        if (limit > 1_000_000) {
            limit = 1_000_000;
        }
        long sum = 0;
        int count = 0;
        int i = 0;
        do {
            sum += i;
            count++;
            i++;
        } while (i <= limit);
        return (sum << 32) ^ count;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("expected one mode argument");
        }
        switch (args[0]) {
            case "structural" -> runStructural();
            case "trace" -> runTrace();
            case "child" -> runChild();
            default -> throw new IllegalArgumentException("unknown mode: " + args[0]);
        }
    }

    private static void runChild() {
        int[] values = new int[37];
        for (int i = 0; i < values.length; i++) {
            values[i] = i * 11 - 7;
        }
        check("exclusiveLess", exclusiveLess(37), refExclusiveLess(37));
        check("decreasingArray", decreasingArray(values),
              refDecreasingArray(values));
        check("notEqualIncreasing", notEqualIncreasing(31),
              refNotEqualIncreasing(31));
        check("strideTwoConstant", strideTwoConstant(17),
              refStrideTwoConstant(17));
        check("arrayLength", arrayLength(values), refArrayLength(values));
        check("twoRecurrences", twoRecurrences(73), refTwoRecurrences(73));
        check("withContinue", withContinue(61), refWithContinue(61));
        check("withBreak", withBreak(80, 53), refWithBreak(80, 53));
        check("withEarlyReturn", withEarlyReturn(80, 53),
              refWithEarlyReturn(80, 53));
        check("exactBudget", exactBudget(17), refExactBudget(17));
        check("overBudget", overBudget(17), refOverBudget(17));
        check("exclusiveDoWhile", exclusiveDoWhile(37),
              (666L << 32) ^ 37L);
        check("exclusiveDoWhileOneTrip", exclusiveDoWhile(-5), 1L);
        check("inclusiveDoWhile", inclusiveDoWhile(37),
              (703L << 32) ^ 38L);
        check("inclusiveDoWhileOneTrip", inclusiveDoWhile(-5), 1L);

        check("exclusiveLessZeroTrip", exclusiveLess(0), 0);
        check("exclusiveLessOneTrip", exclusiveLess(1), 0);
        check("notEqualZeroTrip", notEqualIncreasing(0), 0);
    }

    private static void check(String name, long actual, long expected) {
        if (actual != expected) {
            throw new RuntimeException(name + ": expected " + expected + ", got " + actual);
        }
        System.out.println("RESULT " + name + " " + actual);
    }

    private static long refExclusiveLess(int limit) {
        long sum = 0;
        for (int i = 0; i < limit; i++) sum += i;
        return sum;
    }

    private static long refDecreasingArray(int[] values) {
        long sum = 0;
        for (int i = values.length - 1; i >= 0; i--) sum += values[i];
        return sum;
    }

    private static long refNotEqualIncreasing(int limit) {
        long sum = 0;
        for (int i = 0; i != limit; i++) sum += i;
        return sum;
    }

    private static long refStrideTwoConstant(int seed) {
        long sum = seed;
        for (int i = 0; i < 42; i += 2) sum = sum * 31 + (i ^ seed);
        return sum;
    }

    private static long refExactBudget(int seed) {
        long sum = seed;
        for (int i = 0; i < BUDGET; i++) sum = sum * 31 + (i ^ seed);
        return sum;
    }

    private static long refOverBudget(int seed) {
        long sum = seed;
        for (int i = 0; i < BUDGET + 1; i++) sum = sum * 31 + (i ^ seed);
        return sum;
    }

    private static long refArrayLength(int[] values) {
        long sum = 0;
        for (int value : values) sum += value;
        return sum;
    }

    private static long refTwoRecurrences(int n) {
        long sum = 3;
        long hash = 5;
        for (int i = 0; i < n; i++) {
            sum += i * 7L;
            hash = hash * 31 + i;
        }
        return sum ^ hash;
    }

    private static long refWithContinue(int n) {
        long sum = 0;
        for (int i = 0; i < n; i++) if ((i & 3) != 0) sum += i;
        return sum;
    }

    private static long refWithBreak(int n, int stop) {
        long sum = 0;
        for (int i = 0; i < n && i != stop; i++) sum += i;
        return sum;
    }

    private static long refWithEarlyReturn(int n, int stop) {
        long sum = 0;
        for (int i = 0; i < n; i++) {
            if (i == stop) return sum - i;
            sum += i;
        }
        return sum;
    }

    private static OutputAnalyzer runChild(String llvmOptions) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("-Dtest.classes=" + System.getProperty("test.classes", "."));
        cmd.addAll(List.of("-Xcomp", "-Xbatch", "-XX:-TieredCompilation",
                           "-XX:+UnlockDiagnosticVMOptions", "-XX:+UseJeandleCompiler",
                           "-XX:JeandleLoopStripMiningIter=" + BUDGET));
        for (String scenario : SCENARIOS) {
            cmd.add("-XX:CompileCommand=compileonly," + CLASS + "::" + scenario);
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
            if (section.contains(".outer.latch")) {
                return section;
            }
        }
        return "";
    }

    private static void assertMined(String output, String method) {
        String section = minedSection(output, method);
        Asserts.assertFalse(section.isEmpty(), method + ": expected a strip-mined outer loop");
        // The per-batch inner limit must stay SCEV-friendly regardless of which
        // clamp a loop takes: signed loops use a residual-distance chunk and
        // unsigned loops use uadd/usub_sat, but neither uses the SCEV-opaque
        // sadd_sat/ssub_sat (review finding 5.C.1).
        IRDumpParser.assertNotContains(section, "sadd.sat",
            method + ": signed clamp must not use the SCEV-opaque sadd_sat");
        IRDumpParser.assertNotContains(section, "ssub.sat",
            method + ": signed clamp must not use the SCEV-opaque ssub_sat");
        IRDumpParser.assertContains(section, IRDumpParser.POLL_CALL + " #",
            method + ": the relocated outer poll must carry the marker attribute");
    }

    private static void assertUnminedWithPoll(String output, String method) {
        Asserts.assertTrue(minedSection(output, method).isEmpty(),
            method + ": unsupported shape must remain unwrapped");
        String afterElimination = IRDumpParser.extractNthSection(
            output, "After", "SafepointPollElimination", suffix(method), 1);
        Asserts.assertEquals(IRDumpParser.countPolls(afterElimination), 2,
            method + ": the original loop poll and return poll must survive");
    }

    private static void runStructural() throws Exception {
        OutputAnalyzer out = runChild(STRUCTURAL_OPTIONS);
        out.shouldHaveExitValue(0);
        String output = out.getOutput();

        assertMined(output, "exclusiveLess");
        assertMined(output, "notEqualIncreasing");
        assertMined(output, "strideTwoConstant");
        assertMined(output, "twoRecurrences");
        assertMined(output, "withContinue");
        assertMined(output, "withEarlyReturn");
        assertUnminedWithPoll(output, "decreasingArray");
        assertUnminedWithPoll(output, "arrayLength");
        assertUnminedWithPoll(output, "withBreak");

        Asserts.assertTrue(minedSection(output, "exactBudget").isEmpty(),
            "exactBudget: a loop of exactly N iterations is within budget");
        String afterShortLoop = IRDumpParser.extractNthSection(
            output, "After", "SafepointPollElimination", suffix("exactBudget"), 1);
        Asserts.assertEquals(IRDumpParser.countPolls(afterShortLoop), 1,
            "exactBudget: short-loop elimination must leave only the return poll");
        assertMined(output, "overBudget");
        assertMined(output, "exclusiveDoWhile");
        assertMined(output, "inclusiveDoWhile");
    }

    private static void assertTrace(String output, String method, String needle) {
        Asserts.assertTrue(IRDumpParser.traceChunkContains(output, suffix(method), needle),
            method + ": expected trace decision: " + needle + ", trace was: " +
            String.join(" | ", IRDumpParser.extractTraceChunk(output, suffix(method))));
    }

    private static void runTrace() throws Exception {
        OutputAnalyzer out = runChild(TRACE_OPTIONS);
        out.shouldHaveExitValue(0);
        String output = out.getOutput();

        for (String method : List.of("exclusiveLess", "notEqualIncreasing",
                                     "strideTwoConstant", "twoRecurrences",
                                     "withContinue",
                                     "withEarlyReturn", "overBudget",
                                     "exclusiveDoWhile", "inclusiveDoWhile")) {
            assertTrace(output, method, "strip-mine: wrapped loop");
        }
        assertTrace(output, "strideTwoConstant", "batch-stride=14");
        assertTrace(output, "decreasingArray", "inclusive loop not versionable");
        assertTrace(output, "arrayLength", "no canonical integer induction");
        assertTrace(output, "withBreak", "no supported latch compare");
        assertTrace(output, "exactBudget", "within budget (short loop)");
    }
}
