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
 * @test id=g1
 * @summary Strip mine an inlined invoke poll without losing its deopt or unwind state
 * @requires vm.compiler2.enabled & vm.gc.G1
 * @library /test/lib /
 * @build jdk.test.whitebox.WhiteBox jdk.test.lib.Asserts compiler.jeandle.utils.IRDumpParser
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run driver TestStripMinedInvokePoll G1
 */

/*
 * @test id=serial
 * @summary Verify inlined invoke poll relocation with Serial and explicitly enabled strip mining
 * @requires vm.compiler2.enabled & vm.gc.Serial
 * @library /test/lib /
 * @build jdk.test.whitebox.WhiteBox jdk.test.lib.Asserts compiler.jeandle.utils.IRDumpParser
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run driver TestStripMinedInvokePoll Serial
 */

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import compiler.jeandle.utils.IRDumpParser;
import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;
import jdk.test.whitebox.WhiteBox;

/**
 * A real Java producer and finite semantic checks, not an active-deopt/GC or
 * time-to-safepoint test. The structural oracle pairs before/after snapshots of
 * the same strip-mining occurrence. It proves that a particular body invoke
 * moved, rather than accepting an unrelated outer poll elsewhere in the dump.
 * Serial's default strip-mining budget is not under test: both children use 8.
 */
public class TestStripMinedInvokePoll {
    private static final String CLASS = TestStripMinedInvokePoll.class.getName();
    private static final int MARKER = 123456789;
    private static final int CAUGHT = 0x13579;
    private static final String ID = "[-a-zA-Z$._0-9]+";
    private static final Pattern BLOCK = Pattern.compile("(?m)^(" + ID + "):[^\\n]*\\n");
    private static final Pattern INVOKE_POLL = Pattern.compile(
            "(?s)\\binvoke\\s+hotspotcc\\s+void\\s+@jeandle\\.safepoint_poll\\(\\)"
            + ".*?\\bto label %(" + ID + ") unwind label %(" + ID + ")");
    private static final String POLL_NAME = "@jeandle.safepoint_poll(";
    private static volatile boolean active;
    private static int finalized;

    private static final class Marker extends RuntimeException { }

    static int sum(int[] values) {
        int result = 0;
        int length = values.length;
        if (length <= 0) {
            return result;
        }
        // Keep the conditional backedge explicit. The community preparation
        // does not rotate a header-tested loop whose latch is an invoke.
        int index = 0;
        do {
            int value = values[index];
            if (value == MARKER) {
                throw new Marker();
            }
            result += value;
            index++;
        } while (index < length);
        return result;
    }

    static int caller(int[] values, int repeats) {
        active = true;
        try {
            int result = 0;
            for (int i = 0; i < repeats; i++) {
                result += sum(values);
            }
            return result;
        } catch (Marker expected) {
            return CAUGHT;
        } finally {
            finalized++;
            active = false;
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("child")) {
            runChild();
            return;
        }
        Asserts.assertEquals(args.length, 1, "expected one collector argument");
        Asserts.assertTrue(args[0].equals("G1") || args[0].equals("Serial"));
        List<String> command = new ArrayList<>(List.of(
                "-Xms128m", "-Xmx128m", "-Xshare:off", "-Xbatch",
                "-XX:-TieredCompilation", "-XX:-UseOnStackReplacement",
                "-XX:+UnlockDiagnosticVMOptions", "-XX:+UnlockExperimentalVMOptions",
                "-XX:+UseJeandleCompiler", "-XX:+JeandleUseProfile", "-XX:-VMContinuations",
                "-XX:+Use" + args[0] + "GC", "-XX:JeandleLoopStripMiningIter=8",
                "-XX:+WhiteBoxAPI", "-Xbootclasspath/a:.",
                "-XX:CompileCommand=compileonly," + CLASS + "::caller",
                "-XX:CompileCommand=inline," + CLASS + "::sum",
                "-XX:JeandleLLVMOptions=--jeandle-pre-pea-full-unroll-max-count=0 "
                    + "--print-before=safepoint-strip-mining "
                    + "--print-after=safepoint-strip-mining "
                    + "--jeandle-verify-safepoint-coverage=fatal",
                CLASS, "child"));
        // ProcessTools selects test.jdk, supplied by jtreg -jdk; it does not use
        // the PATH or the JDK launching the jtreg harness as the child JDK.
        OutputAnalyzer output = ProcessTools.executeProcess(
                ProcessTools.createLimitedTestJavaProcessBuilder(command));
        output.shouldHaveExitValue(0).shouldContain("SEMANTICS PASSED ordinaryLevel=4");
        checkRelocation(output.getOutput());
    }

    private static void runChild() throws Exception {
        WhiteBox wb = WhiteBox.getWhiteBox();
        Asserts.assertEquals(wb.getBooleanVMFlag("UseJeandleCompiler"), Boolean.TRUE);
        Asserts.assertEquals(wb.getBooleanVMFlag("UseOnStackReplacement"), Boolean.FALSE);
        int[] values = new int[8192];
        for (int i = 0; i < values.length; i++) {
            values[i] = i * 31 - 999;
        }
        int[] exceptional = values.clone();
        exceptional[37] = MARKER;
        // Bounded warmup supplies branch/call profile; compilation may already
        // occur during it. Do not use -Xcomp, which can change that profile.
        for (int i = 0; i < 128; i++) {
            check(values, 1 + (i & 3));
        }
        check(new int[0], 1);
        check(values, 0);
        check(exceptional, 1);
        Method target = TestStripMinedInvokePoll.class.getDeclaredMethod(
                "caller", int[].class, int.class);
        wb.deoptimizeMethod(target);
        // LLVM dumps to stderr; keep the marker on the same ordered stream.
        System.err.println("TARGET COMPILATION START");
        Asserts.assertTrue(wb.enqueueMethodForCompilation(target, 4), "enqueue ordinary L4");
        // -Xbatch makes the enqueue synchronous. OSR is disabled in the child.
        Asserts.assertEquals(wb.getMethodCompilationLevel(target), 4, "ordinary L4 not installed");
        for (int length : new int[] {1, 7, 8, 9, 15, 16, 17, 8192}) {
            int[] input = java.util.Arrays.copyOf(values, length);
            check(input, 3);
        }
        check(values, 257); // observable Java int wraparound, with a partial final batch
        // These are semantic boundary checks. They are not claimed to all run
        // in the original nmethod: a boundary can legitimately deoptimize it.
        check(new int[0], 3);
        check(null, 0);
        check(exceptional, 2);
        int before = finalized;
        try {
            caller(null, 1);
            throw new AssertionError("expected NullPointerException");
        } catch (NullPointerException expected) {
            Asserts.assertEquals(finalized, before + 1, "finally on uncaught exception");
            Asserts.assertFalse(active, "finally must clear activity on uncaught exception");
        }
        System.out.println("SEMANTICS PASSED ordinaryLevel=4");
    }

    private static void check(int[] values, int repeats) {
        int expected = 0;
        for (int iteration = 0; iteration < repeats; iteration++) {
            for (int value : values) {
                if (value == MARKER) {
                    expected = CAUGHT;
                    iteration = repeats;
                    break;
                }
                expected += value;
            }
        }
        int before = finalized;
        Asserts.assertEquals(caller(values, repeats), expected, "sum/catch result");
        Asserts.assertEquals(finalized, before + 1, "finally must execute exactly once");
        Asserts.assertFalse(active, "finally must clear activity");
    }

    private static Map<String, String> blocks(String section) {
        Map<String, String> blocks = new LinkedHashMap<>();
        Matcher labels = BLOCK.matcher(section);
        String name = null;
        int start = 0;
        while (labels.find()) {
            if (name != null) {
                blocks.put(name, section.substring(start, labels.start()));
            }
            name = labels.group(1);
            start = labels.end();
        }
        if (name != null) {
            blocks.put(name, section.substring(start));
        }
        return blocks;
    }

    private static List<String> normalSuccessors(String block) {
        if (block == null) {
            return List.of();
        }
        Matcher branch = Pattern.compile("(?m)^\\s*br ([^\\n]+)").matcher(block);
        if (branch.find()) {
            List<String> successors = new ArrayList<>();
            Matcher labels = Pattern.compile("\\blabel %(" + ID + ")").matcher(branch.group(1));
            while (labels.find()) {
                successors.add(labels.group(1));
            }
            return successors;
        }
        Matcher invoke = Pattern.compile("\\bto label %(" + ID + ") unwind label %").matcher(block);
        return invoke.find() ? List.of(invoke.group(1)) : List.of();
    }

    private static boolean branchesTo(String block, String target) {
        return block != null && Pattern.compile("(?m)^\\s*br label %"
                + Pattern.quote(target) + "\\s*(?:,.*)?$").matcher(block).find();
    }

    private static boolean reaches(Map<String, String> blocks, String from, String target) {
        List<String> pending = new ArrayList<>(List.of(from));
        HashSet<String> visited = new HashSet<>();
        while (!pending.isEmpty()) {
            String name = pending.remove(pending.size() - 1);
            if (name.equals(target)) {
                return true;
            }
            if (visited.add(name)) {
                pending.addAll(normalSuccessors(blocks.get(name)));
            }
        }
        return false;
    }

    private static void checkRelocation(String output) {
        String suffix = CLASS + "_caller";
        int compilation = output.lastIndexOf("TARGET COMPILATION START");
        Asserts.assertTrue(compilation >= 0, "missing explicit compilation marker");
        output = output.substring(compilation);
        List<String> before = IRDumpParser.extractSections(
                output, "Before", "SafepointStripMining", suffix);
        List<String> after = IRDumpParser.extractSections(
                output, "After", "SafepointStripMining", suffix);
        Asserts.assertFalse(before.isEmpty(), "no target before-pass IR dump");
        Asserts.assertEquals(after.size(), before.size(), "unpaired strip-mining snapshots");
        for (int i = 0; i < before.size(); i++) {
            Map<String, String> oldBlocks = blocks(before.get(i));
            Map<String, String> newBlocks = blocks(after.get(i));
            for (Map.Entry<String, String> oldBlock : oldBlocks.entrySet()) {
                Matcher oldPoll = INVOKE_POLL.matcher(oldBlock.getValue());
                if (!oldPoll.find()) {
                    continue;
                }
                String oldNormal = oldPoll.group(1);
                String unwind = oldPoll.group(2);
                String oldBodyAfter = newBlocks.get(oldBlock.getKey());
                if (oldBodyAfter == null || oldBodyAfter.contains(POLL_NAME)
                        || !branchesTo(oldBodyAfter, oldNormal)) {
                    continue;
                }
                for (Map.Entry<String, String> newBlock : newBlocks.entrySet()) {
                    if (!newBlock.getKey().contains(".outer.latch")
                            || oldBlocks.containsKey(newBlock.getKey())) {
                        continue;
                    }
                    Matcher newPoll = INVOKE_POLL.matcher(newBlock.getValue());
                    if (!newPoll.find() || !newPoll.group(2).equals(unwind)
                            || !newBlock.getValue().contains("\"deopt\"(")) {
                        continue;
                    }
                    String outerHeader = newBlocks.get(newPoll.group(1));
                    String landingpad = newBlocks.get(unwind);
                    List<String> latchSuccessors = normalSuccessors(newBlocks.get(oldNormal));
                    if (latchSuccessors.size() != 2
                            || !latchSuccessors.contains(newBlock.getKey())) {
                        continue;
                    }
                    String innerHeader = latchSuccessors.get(
                            latchSuccessors.get(0).equals(newBlock.getKey()) ? 1 : 0);
                    boolean connectedEntry = normalSuccessors(outerHeader).stream()
                            .anyMatch(entry -> branchesTo(newBlocks.get(entry), innerHeader));
                    boolean outerPhiBackedge = outerHeader != null
                            && Pattern.compile(",\\s*%" + Pattern.quote(newBlock.getKey())
                                    + "\\s*\\]").matcher(outerHeader).find();
                    if (connectedEntry && outerPhiBackedge
                            && normalSuccessors(oldBlocks.get(oldNormal)).contains(innerHeader)
                            && reaches(oldBlocks, innerHeader, oldBlock.getKey())
                            && outerHeader != null && outerHeader.contains(" = phi ")
                            && outerHeader.contains("br i1 ") && landingpad != null
                            && landingpad.contains("landingpad ")) {
                        System.out.println("STRUCTURE PASSED pairedOccurrence=" + i
                                + " oldPollBlock=" + oldBlock.getKey()
                                + " newPollBlock=" + newBlock.getKey());
                        return;
                    }
                }
            }
        }
        throw new AssertionError("No paired target snapshot moves an existing body invoke poll "
                + "to an outer latch with deopt, original unwind, and outer-header continuation");
    }
}
