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
 * @summary Verify empty loops without deopt live-outs are deleted atomically
 * @library /test/lib /
 * @build jdk.test.lib.Asserts compiler.jeandle.utils.IRDumpParser
 * @run driver TestEmptyLoopSafepointElimination structural
 */
/*
 * @test
 * @summary Verify nested empty-loop safepoint deletion decisions via pass trace
 * @requires vm.debug
 * @library /test/lib /
 * @build jdk.test.lib.Asserts compiler.jeandle.utils.IRDumpParser
 * @run driver TestEmptyLoopSafepointElimination trace
 */

import java.util.ArrayList;
import java.util.List;

import compiler.jeandle.utils.IRDumpParser;
import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestEmptyLoopSafepointElimination {
    private static final String CLASS = "TestEmptyLoopSafepointElimination";
    private static final String STRUCTURAL_OPTIONS =
        "--print-before=safepoint-poll-elimination --print-after=safepoint-poll-elimination " +
        "--print-before=safepoint-strip-mining --print-after=safepoint-strip-mining";
    private static final String TRACE_OPTIONS =
        "--debug-only=safepoint-poll-elimination,safepoint-strip-mining";
    private static final String[] SCENARIOS = {
        "emptyWithoutLiveout", "nestedEmptyWithoutLiveout",
        "affineDeoptStateOnly", "multipleDeoptStates",
        "observableRecurrence", "observableStore"
    };

    private static final List<String> DELETED = List.of(
        "emptyWithoutLiveout", "nestedEmptyWithoutLiveout",
        "affineDeoptStateOnly", "multipleDeoptStates");

    private static final List<String> RETAINED = List.of(
        "observableRecurrence", "observableStore");

    private static volatile int sink;

    static void emptyWithoutLiveout() {
        for (int i = 0; i < 5000; i++) { }
    }

    static void nestedEmptyWithoutLiveout() {
        for (int i = 0; i < 2002; i++) {
            for (int j = 0; j < 2; j++) { }
        }
    }

    static int affineDeoptStateOnly(int n, int seed) {
        int state = seed;
        for (int i = 0; i < n; i++) {
            state += 3;
        }
        return seed ^ n;
    }

    static int multipleDeoptStates(int n, int seed) {
        int first = seed;
        int second = seed * 7;
        for (int i = 0; i < n; i++) {
            first += 3;
            second -= 5;
        }
        return seed ^ n;
    }

    static int observableRecurrence(int n, int seed) {
        int hash = seed;
        for (int i = 0; i < n; i++) {
            hash = hash * 31 + (i ^ seed);
        }
        return hash;
    }

    static int observableStore(int n) {
        for (int i = 0; i < n; i++) {
            sink = i;
        }
        return sink;
    }

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
        emptyWithoutLiveout();
        System.out.println("RESULT emptyWithoutLiveout done");
        nestedEmptyWithoutLiveout();
        System.out.println("RESULT nestedEmptyWithoutLiveout done");
        check("affineDeoptStateOnly", affineDeoptStateOnly(5001, 17),
              17 ^ 5001);
        check("multipleDeoptStates", multipleDeoptStates(5001, 23),
              23 ^ 5001);
        check("observableRecurrence", observableRecurrence(5001, 29),
              refObservableRecurrence(5001, 29));
        sink = -1;
        check("observableStore", observableStore(5001), 5000);
    }

    private static int refObservableRecurrence(int n, int seed) {
        int hash = seed;
        for (int i = 0; i < n; i++) hash = hash * 31 + (i ^ seed);
        return hash;
    }

    private static void check(String name, int actual, int expected) {
        if (actual != expected) {
            throw new RuntimeException(name + ": expected " + expected +
                                       ", got " + actual);
        }
        System.out.println("RESULT " + name + " " + actual);
    }

    private static OutputAnalyzer runChild(String llvmOptions) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("-Dtest.classes=" + System.getProperty("test.classes", "."));
        cmd.addAll(List.of("-Xcomp", "-Xbatch", "-XX:-TieredCompilation",
                           "-XX:+UnlockDiagnosticVMOptions", "-XX:+UseJeandleCompiler",
                           "-XX:JeandleLoopStripMiningIter=1000"));
        for (String method : SCENARIOS) {
            cmd.add("-XX:CompileCommand=compileonly," + CLASS + "::" + method);
        }
        cmd.add("-XX:JeandleLLVMOptions=" + llvmOptions);
        cmd.add(CLASS);
        cmd.add("child");
        return ProcessTools.executeProcess(
            ProcessTools.createLimitedTestJavaProcessBuilder(cmd));
    }

    private static void runStructural() throws Exception {
        OutputAnalyzer out = runChild(STRUCTURAL_OPTIONS);
        out.shouldHaveExitValue(0);
        String stdout = out.getStdout();
        for (String scenario : DELETED) {
            Asserts.assertTrue(stdout.contains("RESULT " + scenario + " "),
                scenario + ": child did not complete");
            checkDeleted(out.getOutput(), scenario);
        }
        for (String scenario : RETAINED) {
            Asserts.assertTrue(stdout.contains("RESULT " + scenario + " "),
                scenario + ": child did not complete");
            checkRetained(out.getOutput(), scenario);
        }
    }

    private static void checkDeleted(String output, String method) {
        String suffix = CLASS + "_" + method;
        Asserts.assertEquals(
            IRDumpParser.countSections(output, "After", "SafepointPollElimination", suffix), 3,
            suffix + ": expected early, after-strip-mining and loop-deletion-prep runs");
        String afterStripMining = IRDumpParser.extractNthSection(
            output, "After", "SafepointStripMining", suffix, 1);
        Asserts.assertFalse(afterStripMining.contains(".outer.latch"),
            suffix + ": an empty-loop deletion candidate must not be strip-mined");
        String afterDeletion = IRDumpParser.extractNthSection(
            output, "After", "SafepointPollElimination", suffix, 2);
        Asserts.assertEquals(IRDumpParser.countPolls(afterDeletion), 1,
            suffix + ": loop-deletion-prep must leave only the return poll");
        Asserts.assertFalse(afterDeletion.contains("llvm.loop"),
            suffix + ": no loop metadata may survive empty-loop deletion");
    }

    private static void checkRetained(String output, String method) {
        String suffix = CLASS + "_" + method;
        String afterDeletion = IRDumpParser.extractNthSection(
            output, "After", "SafepointPollElimination", suffix, 2);
        Asserts.assertTrue(IRDumpParser.countPolls(afterDeletion) >= 2,
            suffix + ": observable loop must retain loop and return coverage");
    }

    private static void runTrace() throws Exception {
        OutputAnalyzer out = runChild(TRACE_OPTIONS);
        out.shouldHaveExitValue(0);
        String trace = out.getOutput();
        for (String scenario : DELETED) {
            String suffix = CLASS + "_" + scenario;
            Asserts.assertTrue(IRDumpParser.traceChunkContains(
                    trace, suffix, "empty-loop deletion candidate"),
                suffix + ": strip mining must defer the empty loop");
            Asserts.assertTrue(IRDumpParser.traceChunkContains(
                    trace, suffix, "loop-deletion-prep: deleting"),
                suffix + ": deletion prep must remove the empty loop or nest");
            Asserts.assertFalse(IRDumpParser.traceChunkContains(
                    trace, suffix, "strip-mine: wrapped loop"),
                suffix + ": empty loops must not be wrapped");
        }
        for (String scenario : RETAINED) {
            String suffix = CLASS + "_" + scenario;
            Asserts.assertFalse(IRDumpParser.traceChunkContains(
                    trace, suffix, "loop-deletion-prep: deleting"),
                suffix + ": observable loop must not be deleted");
            Asserts.assertTrue(IRDumpParser.traceChunkContains(
                    trace, suffix, "keep-one"),
                suffix + ": observable loop must retain safepoint coverage");
        }
    }
}
