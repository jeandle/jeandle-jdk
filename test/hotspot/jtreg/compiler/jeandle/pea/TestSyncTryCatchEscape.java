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
 * @summary PEA preserves try/catch/finally semantics while materializing a
 *          graph of virtual lock owners and replaying conditional reentrant locks
 * @library /test/lib /
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.pea.TestSyncTryCatchEscape
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jdk.test.lib.Asserts;

public class TestSyncTryCatchEscape {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestSyncTryCatchEscape$TestWrapper";
    private static final String MONITOR_ENTER = "@jeandle.monitorenter";
    private static final String MONITOR_EXIT = "@jeandle.monitorexit";
    private static final String INT_STORE = "store atomic i32";
    private static final String REF_STORE = "store atomic ptr addrspace(1)";
    private static final String LOCAL =
            "%((?:\\\"(?:\\\\[0-9A-Fa-f]{2}|[^\\\"\\\\])*\\\")|[-A-Za-z$._0-9]+)";
    private static final String LABEL =
            "((?:\\\"(?:\\\\[0-9A-Fa-f]{2}|[^\\\"\\\\])*\\\")|[^,\\]\\s]+)";
    private static final Pattern BLOCK_LABEL =
            Pattern.compile("^([^\\s:]+):(?:\\s*;.*)?$");
    private static final Pattern LABEL_REFERENCE =
            Pattern.compile("label %" + LABEL);
    private static final Pattern MONITOR_OPERATION_RECEIVER = Pattern.compile(
            "@jeandle\\.monitor(?:enter|exit)_with_lightweight_lock"
                    + "\\(ptr addrspace\\(1\\)(?:\\s+(?:nonnull|noundef))*\\s+("
                    + LOCAL + ")");

    public static void main(String[] args) throws Exception {
        Method target = TestWrapper.class.getMethod("syncTryCatchEscape",
                boolean.class, boolean.class, boolean.class, int.class);
        Method sink = TestWrapper.class.getMethod("sink",
                TestWrapper.Graph.class, TestWrapper.Node.class);
        Method maybeThrow = TestWrapper.class.getMethod("maybeThrow", boolean.class);
        Method[] targets = {target};

        for (int lockingMode : new int[] {1, 2}) {
            builder(false, lockingMode, targets, sink, maybeThrow)
                    .runPEAOnOffEquivalent();
        }

        try (PEATestUtils.RunResult run =
                builder(true, 2, targets, sink, maybeThrow).run()) {
            assertShape(run, target, sink, maybeThrow);
        }
    }

    private static PEATestUtils.RunBuilder builder(boolean shape, int lockingMode,
                                                    Method[] targets, Method sink,
                                                    Method maybeThrow) {
        PEATestUtils.RunBuilder builder = shape
                ? PEATestUtils.shapeRun(WRAPPER, targets)
                : PEATestUtils.behaviorRun(WRAPPER, targets);
        return builder.dontinline(sink)
                .dontinline(maybeThrow)
                .lockingMode(lockingMode);
    }

    private static void assertShape(PEATestUtils.RunResult run, Method target,
                                    Method sink, Method maybeThrow) throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        report.assertFinalTransformIdle();
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();
        List<Integer> allocationBCIs = before.allocationBCIs();

        Asserts.assertEquals(allocationBCIs.size(), 3,
                target + ": graph and two lock-owner allocations in round-0 input");
        Asserts.assertEquals(new HashSet<>(allocationBCIs).size(), 3,
                target + ": source allocations have distinct BCIs");
        MonitorShape inputMonitors = assertMonitorShape(before, target, false);
        Asserts.assertTrue(before.lineCount(MONITOR_EXIT) >= 6,
                target + ": every source monitor has normal/exception cleanup");
        before.assertLineCount("@\"" + PEATestUtils.MethodId.of(sink)
                .llvmFunctionName() + "\"", 1);
        before.assertLineCount("@\"" + PEATestUtils.MethodId.of(maybeThrow)
                .llvmFunctionName() + "\"", 1);
        Asserts.assertTrue(before.lineCount("br i1") >= 3,
                target + ": escape, throw and conditional-receiver branches survive");
        before.assertPresent("landingpad");
        Asserts.assertEquals(inputMonitors.bodyReceivers().get(0),
                inputMonitors.bodyReceivers().get(2),
                target + ": source lock capture is reentrant a,b,a");
        Asserts.assertTrue(!inputMonitors.bodyReceivers().get(0)
                        .equals(inputMonitors.bodyReceivers().get(1)),
                target + ": source lock capture keeps the middle owner distinct");

        PEATestUtils.PEARound first = report.round(0);
        Asserts.assertTrue(first.hasStats(), target + ": round-0 PEA stats");
        Asserts.assertEquals(first.neverEscapes(), 0, target + ": NeverEscapes");
        Asserts.assertEquals(first.partiallyEscapes(), 3,
                target + ": graph and both owners are partial escapes");
        Asserts.assertEquals(first.alwaysEscapes(), 0, target + ": AlwaysEscapes");
        Asserts.assertEquals(first.effectCount("EliminateAllocation"), 3L,
                target + ": PEA analyzes every source allocation");
        Asserts.assertEquals(effectCountForVO(
                        first, "ReplaceCall", 0, MONITOR_ENTER), 2L,
                target + ": outer and conditional reentrant a enters are folded");
        Asserts.assertEquals(effectCountForVO(
                        first, "ReplaceCall", 1, MONITOR_ENTER), 1L,
                target + ": interleaved b enter is folded");

        for (PEATestUtils.PEARound round : report.rounds()) {
            Asserts.assertEquals(round.effectCount("Materialize"), 6L,
                    target + ": all graph members materialize at both conditional escapes in round "
                            + round.iteration());
            for (int object = 0; object < 3; object++) {
                Asserts.assertEquals(round.effectCount(
                                "Materialize", "[VO=" + object + "]"), 2L,
                        target + ": VO " + object
                                + " materializes once at each conditional escape in round "
                                + round.iteration());
            }
            assertPhysicalReplay(round, target);
        }

        Asserts.assertEquals(after.allocationBCIs(), allocationBCIs,
                target + ": materialization reuses each source OrigAlloc exactly once");
        assertMonitorShape(after, target, true);
        Asserts.assertTrue(after.lineCount(MONITOR_EXIT) >= 6,
                target + ": final PEA IR retains balanced normal/exception exits");

        PEATestUtils.IRBlock replayBlock = after.blockContaining(
                "@\"" + PEATestUtils.MethodId.of(sink).llvmFunctionName() + "\"", 0);
        Asserts.assertEquals(replayBlock.occurrenceCount(INT_STORE), 3,
                target + ": final scalar fields replay in the escape block");
        Asserts.assertEquals(replayBlock.occurrenceCount(REF_STORE), 3,
                target + ": graph owner references replay in the escape block");
        replayBlock.assertBefore(INT_STORE, 2, MONITOR_ENTER, 0);
        replayBlock.assertBefore(REF_STORE, 2, MONITOR_ENTER, 0);
        replayBlock.assertBefore(MONITOR_ENTER, 2,
                "@\"" + PEATestUtils.MethodId.of(sink).llvmFunctionName() + "\"", 0);
    }

    private static void assertPhysicalReplay(PEATestUtils.PEARound round,
                                             Method target) {
        Asserts.assertEquals(round.lockReplayGroups().size(), 2,
                target + ": two logical conditional escapes in round "
                        + round.iteration());
        Asserts.assertEquals(round.lockReplayGroups().keySet().stream()
                        .map(PEATestUtils.PEALockReplayGroup::logicalEscape)
                        .distinct().count(), 2L,
                target + ": sink and invoke-state escape have distinct identities");
        Asserts.assertEquals(round.lockReplayPhysicalGroups().size(), 2,
                target + ": one physical replay batch per conditional escape in round "
                        + round.iteration());
        for (List<PEATestUtils.PEALockReplay> rows
                : round.lockReplayPhysicalGroups().values()) {
            Map<Integer, PEATestUtils.PEALockReplay> physical = new LinkedHashMap<>();
            for (PEATestUtils.PEALockReplay row : rows) {
                PEATestUtils.PEALockReplay previous = physical.putIfAbsent(
                        row.ordinal(), row);
                if (previous != null) {
                    Asserts.assertEquals(row.receiverVO(), previous.receiverVO(),
                            target + ": logical alias preserves physical receiver");
                    Asserts.assertEquals(row.depth(), previous.depth(),
                            target + ": logical alias preserves physical depth");
                }
            }
            Asserts.assertEquals(new ArrayList<>(physical.keySet()), List.of(0, 1, 2),
                    target + ": physical replay retains every captured a,b,a enter");
            Asserts.assertEquals(physical.values().stream()
                            .map(PEATestUtils.PEALockReplay::depth).toList(),
                    List.of(0, 1, 2),
                    target + ": physical replay follows outer-to-inner lock depth");
            Asserts.assertEquals(physical.values().stream()
                            .map(PEATestUtils.PEALockReplay::receiverVO).toList(),
                    List.of(0, 1, 0),
                    target + ": physical replay preserves reentrant a,b,a identity");
        }
    }

    private static MonitorShape assertMonitorShape(PEATestUtils.IRBody body,
                                                    Method target,
                                                    boolean transformed) {
        ReceiverCFG cfg = ReceiverCFG.parse(body.lines(), target);
        List<String> threeEnterBlocks = cfg.blocks.entrySet().stream()
                .filter(entry -> lineCount(entry.getValue(), MONITOR_ENTER) == 3)
                .map(Map.Entry::getKey).toList();
        List<String> probeBlocks = threeEnterBlocks.stream()
                .filter(block -> lineCount(cfg.block(block), MONITOR_EXIT) == 3)
                .toList();
        Asserts.assertEquals(probeBlocks.size(), 2,
                target + ": normal and exceptional finally clones each probe a,b,a");
        for (String block : probeBlocks) {
            assertEnterExitProbe(cfg.block(block), target, block);
        }
        Asserts.assertEquals(probeBlocks.stream()
                        .filter(block -> cfg.reachesLine(block,
                                "@install_exceptional_return"))
                        .count(), 1L,
                target + ": exactly one finally clone continues exceptional return");
        Asserts.assertEquals(probeBlocks.stream()
                        .filter(block -> cfg.block(block).stream()
                                .anyMatch(line -> line.contains("@jeandle.safepoint_poll")))
                        .count(), 1L,
                target + ": exactly one finally clone completes the normal return");

        if (transformed) {
            HashSet<String> probeBlockSet = new HashSet<>(probeBlocks);
            List<String> replayBlocks = threeEnterBlocks.stream()
                    .filter(block -> !probeBlockSet.contains(block))
                    .toList();
            Asserts.assertEquals(replayBlocks.size(), 2,
                    target + ": sink and invoke-state paths each have one replay block");
            for (String block : replayBlocks) {
                List<String> receivers = monitorReceivers(cfg.block(block));
                Asserts.assertEquals(receivers.size(), 3,
                        target + ": replay block emits every captured a,b,a enter: " + block);
                Asserts.assertEquals(receivers.get(0), receivers.get(2),
                        target + ": replay block preserves reentrant a identity: " + block);
                Asserts.assertTrue(!receivers.get(0).equals(receivers.get(1)),
                        target + ": replay block preserves distinct middle b owner: " + block);
            }
            return new MonitorShape(List.of());
        }

        List<String> bodyBlocks = threeEnterBlocks.stream()
                .filter(block -> lineCount(cfg.block(block), MONITOR_EXIT) == 0)
                .toList();
        Asserts.assertEquals(bodyBlocks.size(), 1,
                target + ": exactly one source block captures nested a,b,a locks");
        List<String> receivers = monitorReceivers(cfg.block(bodyBlocks.get(0)));
        Asserts.assertEquals(receivers.size(), 3,
                target + ": source block contains exactly three captured enters");
        return new MonitorShape(receivers);
    }

    private static void assertEnterExitProbe(List<String> lines, Method target,
                                             String block) {
        ArrayList<String> operations = new ArrayList<>();
        for (String line : lines) {
            if (line.contains(MONITOR_ENTER)) {
                operations.add("enter:" + monitorReceiver(line, target));
            } else if (line.contains(MONITOR_EXIT)) {
                operations.add("exit:" + monitorReceiver(line, target));
            }
        }
        Asserts.assertEquals(operations.size(), 6,
                target + ": finally probe has three balanced lock pairs in " + block);
        for (int i = 0; i < operations.size(); i += 2) {
            Asserts.assertTrue(operations.get(i).startsWith("enter:"),
                    target + ": finally probe acquires before release in " + block);
            Asserts.assertEquals(operations.get(i + 1),
                    "exit:" + operations.get(i).substring("enter:".length()),
                    target + ": finally probe releases the same receiver in " + block);
        }
        List<String> receivers = monitorReceivers(lines);
        Asserts.assertEquals(receivers.get(0), receivers.get(2),
                target + ": finally probe checks reentrant owner a twice");
        Asserts.assertTrue(!receivers.get(0).equals(receivers.get(1)),
                target + ": finally probe checks distinct middle owner b");
    }

    private static int lineCount(List<String> lines, String needle) {
        return (int) lines.stream().filter(line -> line.contains(needle)).count();
    }

    private static List<String> monitorReceivers(List<String> lines) {
        return lines.stream().filter(line -> line.contains(MONITOR_ENTER))
                .map(line -> monitorReceiver(line, null)).toList();
    }

    private static String monitorReceiver(String line, Method target) {
        Matcher matcher = MONITOR_OPERATION_RECEIVER.matcher(line);
        Asserts.assertTrue(matcher.find(),
                (target == null ? "monitor operation" : target)
                        + ": monitor operation has an SSA receiver: " + line);
        return matcher.group(1);
    }

    private record MonitorShape(List<String> bodyReceivers) { }

    private static long effectCountForVO(PEATestUtils.PEARound round, String kind,
                                         int objectId, String... detailParts) {
        String objectToken = "[VO=" + objectId + "]";
        return round.effects().stream()
                .filter(effect -> effect.kind().equals(kind))
                .filter(effect -> List.of(effect.detail().split("\\s+"))
                        .contains(objectToken))
                .filter(effect -> List.of(detailParts).stream()
                        .allMatch(effect.detail()::contains))
                .count();
    }

    private static final class ReceiverCFG {
        private final Method method;
        private final Map<String, List<String>> blocks;
        private final Map<String, List<String>> successors;

        private ReceiverCFG(Method method, Map<String, List<String>> blocks,
                            Map<String, List<String>> successors) {
            this.method = method;
            this.blocks = blocks;
            this.successors = successors;
        }

        static ReceiverCFG parse(List<String> lines, Method method) {
            LinkedHashMap<String, List<String>> blocks = new LinkedHashMap<>();
            String current = null;
            for (String line : lines) {
                Matcher label = BLOCK_LABEL.matcher(line);
                if (label.matches()) {
                    current = label.group(1);
                    blocks.put(current, new ArrayList<>());
                } else if (current != null) {
                    blocks.get(current).add(line);
                }
            }
            Asserts.assertFalse(blocks.isEmpty(), method + ": round-0 CFG blocks");

            LinkedHashMap<String, List<String>> successors = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> block : blocks.entrySet()) {
                ArrayList<String> targets = new ArrayList<>();
                for (String line : block.getValue()) {
                    Matcher reference = LABEL_REFERENCE.matcher(line);
                    while (reference.find()) {
                        targets.add(reference.group(1));
                    }
                }
                successors.put(block.getKey(), List.copyOf(targets));
            }
            return new ReceiverCFG(method, blocks, successors);
        }

        List<String> block(String label) {
            List<String> block = blocks.get(label);
            Asserts.assertNotNull(block, method + ": missing block " + label);
            return block;
        }

        boolean reachesLine(String start, String token) {
            ArrayDeque<String> work = new ArrayDeque<>();
            HashSet<String> visited = new HashSet<>();
            work.add(start);
            while (!work.isEmpty()) {
                String block = work.removeFirst();
                if (!visited.add(block)) {
                    continue;
                }
                if (block(block).stream().anyMatch(line -> line.contains(token))) {
                    return true;
                }
                work.addAll(successors.getOrDefault(block, List.of()));
            }
            return false;
        }
    }

    public static class TestWrapper {
        private static final int ITERATIONS = 200;

        public static class Node {
            int value;

            Node(int value) {
                this.value = value;
            }
        }

        public static class Graph {
            Node left;
            Node right;
            Node alias;
            int tag;
        }

        public static class Marker extends RuntimeException {
            private static final long serialVersionUID = 1L;
        }

        private static Graph escapedGraph;
        private static Node escapedReceiver;
        private static int finallyCount;

        public static void main(String[] args) throws Exception {
            new Node(0);
            new Graph();
            new Marker();
            PEATestUtils.compileConfiguredTargetsAtLevel4();

            long digest = 0x243f6a8885a308d3L;
            int expectedFinally = 0;
            for (int iteration = 0; iteration < ITERATIONS; iteration++) {
                int seed = iteration & 31;
                for (boolean escape : new boolean[] {false, true}) {
                    for (boolean doThrow : new boolean[] {false, true}) {
                        for (boolean chooseLeft : new boolean[] {false, true}) {
                            escapedGraph = null;
                            escapedReceiver = null;
                            long result = syncTryCatchEscape(
                                    escape, doThrow, chooseLeft, seed);
                            assertResult(result, doThrow, chooseLeft, seed);
                            assertEscape(escape, doThrow, chooseLeft, seed);
                            expectedFinally++;
                            Asserts.assertEquals(finallyCount, expectedFinally,
                                    "finally executes exactly once per matrix element");
                            digest = mix(digest, result);
                            digest = mix(digest, escape ? 1 : 0);
                        }
                    }
                }
            }
            System.out.println("PEA-RESULT:" + Long.toUnsignedString(digest, 16)
                    + ":" + finallyCount);
        }

        public static long syncTryCatchEscape(boolean escape, boolean doThrow,
                                              boolean chooseLeft, int seed) {
            Node a = new Node(seed + 1);
            Node b = new Node(seed + 10);
            Graph graph = new Graph();
            graph.left = a;
            graph.right = b;
            graph.alias = a;
            graph.tag = seed + 100;

            // Loading through the virtual graph keeps the Java receiver
            // conditional without exposing a known redundant nested monitor
            // to the bytecode monitor-map gate.
            Node receiver = chooseLeft ? graph.left : graph.alias;
            int path = 0;
            try {
                try {
                    synchronized (a) {
                        synchronized (b) {
                            synchronized (receiver) {
                                a.value += 1;
                                b.value += 2;
                                graph.tag += 3;
                                if (escape) {
                                    sink(graph, receiver);
                                }
                                maybeThrow(doThrow);
                                path = 7;
                            }
                        }
                    }
                } catch (Marker expected) {
                    a.value += 10;
                    b.value += 20;
                    graph.tag += 30;
                    path = 11;
                }
                return encode(a.value, b.value, graph.tag,
                        receiver == a ? 1 : 2, path);
            } finally {
                a.value += 100;
                b.value += 200;
                graph.tag += 300;
                finallyCount++;
                // Both virtual and materialized paths must leave every monitor
                // in an acquirable state. The return value is evaluated before
                // these finally actions, exercising Java return-through-finally.
                synchronized (a) { }
                synchronized (b) { }
                synchronized (receiver) { }
            }
        }

        public static void sink(Graph graph, Node receiver) {
            escapedGraph = graph;
            escapedReceiver = receiver;
        }

        public static void maybeThrow(boolean doThrow) {
            if (doThrow) {
                throw new Marker();
            }
        }

        private static void assertResult(long actual, boolean doThrow,
                                         boolean chooseLeft, int seed) {
            int a = seed + (doThrow ? 12 : 2);
            int b = seed + (doThrow ? 32 : 12);
            int tag = seed + (doThrow ? 133 : 103);
            long expected = encode(a, b, tag, 1,
                    doThrow ? 11 : 7);
            Asserts.assertEquals(actual, expected,
                    "normal/exception return value evaluated before finally");
        }

        private static void assertEscape(boolean escape, boolean doThrow,
                                         boolean chooseLeft, int seed) {
            if (!escape) {
                Asserts.assertNull(escapedGraph,
                        "non-escape matrix entry does not publish the graph");
                Asserts.assertNull(escapedReceiver,
                        "non-escape matrix entry does not publish the receiver");
                return;
            }
            Asserts.assertNotNull(escapedGraph, "escape publishes the graph");
            Asserts.assertNotNull(escapedReceiver, "escape publishes the receiver");
            Asserts.assertSame(escapedReceiver,
                    chooseLeft ? escapedGraph.left : escapedGraph.alias,
                    "conditional receiver identity survives materialization");
            Asserts.assertSame(escapedGraph.left, escapedGraph.alias,
                    "shared virtual-field alias retains identity");
            Asserts.assertNE(escapedGraph.left, escapedGraph.right,
                    "distinct virtual owners retain distinct identities");
            Asserts.assertEquals(escapedGraph.left.value,
                    seed + (doThrow ? 112 : 102),
                    "escaped left field includes catch/finally updates");
            Asserts.assertEquals(escapedGraph.right.value,
                    seed + (doThrow ? 232 : 212),
                    "escaped right field includes catch/finally updates");
            Asserts.assertEquals(escapedGraph.tag,
                    seed + (doThrow ? 433 : 403),
                    "escaped graph field includes catch/finally updates");
            Asserts.assertFalse(Thread.holdsLock(escapedGraph.left),
                    "escaped left lock is fully released");
            synchronized (escapedGraph.left) { }
            Asserts.assertFalse(Thread.holdsLock(escapedGraph.right),
                    "escaped right lock is fully released");
            synchronized (escapedGraph.right) { }
            Asserts.assertFalse(Thread.holdsLock(escapedReceiver),
                    "escaped conditional receiver lock is fully released");
            synchronized (escapedReceiver) { }
        }

        private static long encode(int a, int b, int tag, int receiver, int path) {
            long value = a;
            value = value * 1000 + b;
            value = value * 1000 + tag;
            value = value * 10 + receiver;
            return value * 100 + path;
        }

        private static long mix(long digest, long value) {
            return Long.rotateLeft(digest ^ value, 17) * 0x9e3779b97f4a7c15L;
        }
    }
}
