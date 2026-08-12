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
 * @summary Verify call coverage and conservative safepoint poll elimination
 * @library /test/lib /
 * @build jdk.test.lib.Asserts compiler.jeandle.utils.IRDumpParser
 * @run driver TestSafepointLoopCoverage structural
 */
/*
 * @test
 * @summary Verify loop safepoint coverage decisions via pass trace
 * @requires vm.debug
 * @library /test/lib /
 * @build jdk.test.lib.Asserts compiler.jeandle.utils.IRDumpParser
 * @run driver TestSafepointLoopCoverage trace
 */

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import compiler.jeandle.utils.IRDumpParser;
import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestSafepointLoopCoverage {
    private static final String CLASS = "TestSafepointLoopCoverage";
    private static final int N = 5000;
    private static final String STRUCTURAL_OPTIONS =
        "--print-after=safepoint-poll-elimination";
    private static final String TRACE_OPTIONS =
        "--debug-only=safepoint-poll-elimination";

    private static final String[] SCENARIOS = {
        "unconditionalCall", "conditionalCall", "allocationOnly",
        "callsOnBothBranches", "multipleBackedges", "nestedDoWhileCall"
    };

    private static volatile int callSink;
    private static volatile Object objectSink;

    static int guaranteedSafepoint(int value) {
        callSink = value;
        return value + 1;
    }

    static int unconditionalCall(int n) {
        int sum = 0;
        for (int i = 0; i < n; i++) {
            sum += guaranteedSafepoint(i);
        }
        return sum;
    }

    static int conditionalCall(int n) {
        int sum = 0;
        for (int i = 0; i < n; i++) {
            if ((i & 1) == 0) {
                guaranteedSafepoint(i);
            }
            sum += i;
        }
        return sum;
    }

    static int allocationOnly(int n) {
        int sum = 0;
        for (int i = 0; i < n; i++) {
            objectSink = new int[1];
            sum += i;
        }
        return sum;
    }

    static int callsOnBothBranches(int n) {
        int sum = 0;
        for (int i = 0; i < n; i++) {
            if ((i & 1) == 0) {
                sum += guaranteedSafepoint(i);
            } else {
                sum += guaranteedSafepoint(-i);
            }
        }
        return sum;
    }

    static int multipleBackedges(int n) {
        int sum = 0;
        for (int i = 0; i < n;) {
            if ((i & 1) == 0) {
                sum += i++;
                continue;
            }
            sum += i++;
        }
        return sum;
    }

    static int nestedDoWhileCall(int outer, int inner) {
        int sum = 0;
        for (int i = 0; i < outer; i++) {
            int j = 0;
            do {
                sum += guaranteedSafepoint(i + j);
                j++;
            } while (j < inner);
        }
        return sum;
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
        System.out.println("RESULT unconditionalCall " + unconditionalCall(N));
        System.out.println("RESULT conditionalCall " + conditionalCall(N));
        System.out.println("RESULT allocationOnly " + allocationOnly(N));
        System.out.println("RESULT callsOnBothBranches " + callsOnBothBranches(N));
        System.out.println("RESULT multipleBackedges " + multipleBackedges(N));
        System.out.println("RESULT nestedDoWhileCall " + nestedDoWhileCall(101, 3));
    }

    private static OutputAnalyzer runChild(String llvmOptions) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("-Dtest.classes=" + System.getProperty("test.classes", "."));
        cmd.addAll(List.of("-Xcomp", "-Xbatch", "-XX:-TieredCompilation",
                           "-XX:+UnlockDiagnosticVMOptions", "-XX:+UseJeandleCompiler",
                           "-XX:JeandleLoopStripMiningIter=0",
                           "-XX:CompileCommand=dontinline," + CLASS + "::guaranteedSafepoint"));
        for (String scenario : SCENARIOS) {
            cmd.add("-XX:CompileCommand=compileonly," + CLASS + "::" + scenario);
        }
        cmd.add("-XX:JeandleLLVMOptions=" + llvmOptions);
        cmd.add(CLASS);
        cmd.add("child");
        return ProcessTools.executeProcess(
            ProcessTools.createLimitedTestJavaProcessBuilder(cmd));
    }

    private static void verifyResults(String stdout) {
        int arithmetic = N * (N - 1) / 2;
        int branchCalls = 0;
        for (int i = 0; i < N; i++) {
            branchCalls += (i & 1) == 0 ? i + 1 : -i + 1;
        }
        int nested = 0;
        for (int i = 0; i < 101; i++) {
            nested += 3 * i + 6;
        }
        Map<String, Integer> expected = new LinkedHashMap<>();
        expected.put("unconditionalCall", N * (N + 1) / 2);
        expected.put("conditionalCall", arithmetic);
        expected.put("allocationOnly", arithmetic);
        expected.put("callsOnBothBranches", branchCalls);
        expected.put("multipleBackedges", arithmetic);
        expected.put("nestedDoWhileCall", nested);
        for (Map.Entry<String, Integer> entry : expected.entrySet()) {
            String prefix = "RESULT " + entry.getKey() + " ";
            Asserts.assertTrue(stdout.contains(prefix + entry.getValue()),
                entry.getKey() + ": compiled result differs from the hand-computed result");
        }
    }

    private static String earlySection(String output, String method) {
        return IRDumpParser.extractNthSection(
            output, "After", "SafepointPollElimination", CLASS + "_" + method, 0);
    }

    private static void assertEarlyPolls(String output, String method, int expected) {
        String section = earlySection(output, method);
        Asserts.assertFalse(section.isEmpty(), method + ": missing early elimination IR dump");
        Asserts.assertEquals(IRDumpParser.countPolls(section), expected,
            method + ": unexpected poll count after early elimination");
    }

    private static void runStructural() throws Exception {
        OutputAnalyzer out = runChild(STRUCTURAL_OPTIONS);
        out.shouldHaveExitValue(0);
        verifyResults(out.getStdout());
        String output = out.getOutput();

        assertEarlyPolls(output, "unconditionalCall", 1);
        assertEarlyPolls(output, "conditionalCall", 2);
        assertEarlyPolls(output, "allocationOnly", 2);
        assertEarlyPolls(output, "callsOnBothBranches", 2);
        assertEarlyPolls(output, "multipleBackedges", 3);
        assertEarlyPolls(output, "nestedDoWhileCall", 1);
    }

    private static void assertTrace(String output, String method, String needle) {
        Asserts.assertTrue(IRDumpParser.traceChunkContains(
                output, CLASS + "_" + method, needle),
            method + ": expected trace decision: " + needle);
    }

    private static void runTrace() throws Exception {
        OutputAnalyzer out = runChild(TRACE_OPTIONS);
        out.shouldHaveExitValue(0);
        verifyResults(out.getStdout());
        String output = out.getOutput();

        assertTrace(output, "unconditionalCall", "delete-all (call-covered)");
        assertTrace(output, "conditionalCall", "keep-one (keeper in");
        assertTrace(output, "allocationOnly", "keep-one (keeper in");
        assertTrace(output, "callsOnBothBranches", "keep-one (keeper in");
        assertTrace(output, "multipleBackedges", "keep-all (no dominating keeper)");
        assertTrace(output, "nestedDoWhileCall", "delete-all (call-covered)");
    }
}
