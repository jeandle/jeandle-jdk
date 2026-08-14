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
 * @summary PEA replays interleaved virtual locks independently on alternative
 *          predecessor paths in global bytecode-depth order
 * @library /test/lib /
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.pea.TestPEALockReplayAcrossPredecessors
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jdk.test.lib.Asserts;

public class TestPEALockReplayAcrossPredecessors {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestPEALockReplayAcrossPredecessors$TestWrapper";
    private static final String MONITOR_ENTER =
            "jeandle.monitorenter_with_lightweight_lock";
    private static final String LOCAL =
            "%((?:\\\"(?:\\\\[0-9A-Fa-f]{2}|[^\\\"\\\\])*\\\")|[-A-Za-z$._0-9]+)";
    private static final Pattern BLOCK_LABEL =
            Pattern.compile("^([^\\s:]+):(?:\\s*;.*)?$");
    private static final Pattern LABEL_REFERENCE =
            Pattern.compile("label %((?:\\\"(?:\\\\[0-9A-Fa-f]{2}|[^\\\"\\\\])*\\\")|[^,\\]\\s]+)");
    private static final Pattern MONITOR_RECEIVER = Pattern.compile(
            "@jeandle\\.monitorenter_with_lightweight_lock\\(ptr addrspace\\(1\\)"
                    + "(?:\\s+(?:nonnull|noundef))*\\s+(" + LOCAL + ")");
    private static final Pattern PHI_INCOMING = Pattern.compile(
            "\\[\\s*" + LOCAL + ",\\s*%((?:\\\"(?:\\\\[0-9A-Fa-f]{2}|[^\\\"\\\\])*\\\")|[^,\\]\\s]+)\\s*\\]");

    public static void main(String[] args) throws Exception {
        Method crossPred = TestWrapper.class.getMethod(
                "crossPred", boolean.class, boolean.class);
        Method threePred = TestWrapper.class.getMethod(
                "threePred", int.class, boolean.class, int.class);
        Method criticalEdge = TestWrapper.class.getMethod(
                "criticalEdge", boolean.class, boolean.class, boolean.class, int.class);
        Method samePredMultiEdge = TestWrapper.class.getMethod(
                "samePredMultiEdge", int.class, boolean.class, int.class);
        Method sink = TestWrapper.class.getMethod("dontinlineSink", TestWrapper.Box.class);
        Method throwMarker = TestWrapper.class.getMethod("throwMarker");
        Method[] targets = {crossPred, threePred, criticalEdge, samePredMultiEdge};

        for (int lockingMode : new int[] {1, 2}) {
            builder(false, lockingMode, targets, sink, throwMarker)
                    .runPEAOnOffEquivalent();
        }

        try (PEATestUtils.RunResult run =
                builder(true, 2, targets, sink, throwMarker).run()) {
            assertAlternativeReplay(run, crossPred, sink,
                    List.of(List.of(0, 1, 0), List.of(0, 1, 0)), 2);
            assertAlternativeReplay(run, threePred, sink,
                    List.of(List.of(0, 1, 0), List.of(0, 1, 0),
                            List.of(0, 1, 0)), 3);
            assertCriticalEdgeReplay(run, criticalEdge, sink,
                    List.of(List.of(0, 1, 0), List.of(0, 1, 0)));
            assertSamePredecessorMultiEdgeReplay(run, samePredMultiEdge, sink,
                    List.of(List.of(0, 1, 0), List.of(0, 1, 0)));
        }
    }

    private static PEATestUtils.RunBuilder builder(boolean shape, int lockingMode,
                                                    Method[] targets, Method sink,
                                                    Method throwMarker) {
        PEATestUtils.RunBuilder builder = shape
                ? PEATestUtils.shapeRun(WRAPPER, targets)
                : PEATestUtils.behaviorRun(WRAPPER, targets);
        return builder.dontinline(sink)
                .dontinline(throwMarker)
                .lockingMode(lockingMode);
    }

    private static ConsumerMerge assertAlternativeReplay(
            PEATestUtils.RunResult run, Method target, Method sink,
            List<List<Integer>> expectedReceivers, int expectedPredecessors) {
        PEATestUtils.PEAReport report = run.report(target);
        report.assertFinalTransformIdle();
        PEATestUtils.IRBody input = report.round0Before();
        List<Integer> allocationBCIs = input.allocationBCIs();
        Asserts.assertEquals(allocationBCIs.size(), expectedPredecessors + 1,
                target + ": exact lock-owner allocations in round-0 input");
        Asserts.assertEquals(new HashSet<>(allocationBCIs).size(), allocationBCIs.size(),
                target + ": allocation BCIs identify distinct receiver VOs");
        Asserts.assertEquals(input.lineCount(MONITOR_ENTER), 4,
                target + ": source contains a@0,b@1,a@2,r@3");

        String sinkName = PEATestUtils.MethodId.of(sink).llvmFunctionName();
        CFG cfg = CFG.parse(input.lines(), target);
        ConsumerMerge consumerMerge = cfg.consumerMerge(sinkName, expectedPredecessors);
        Asserts.assertEquals(consumerMerge.incomingPredecessors().size(),
                expectedPredecessors,
                target + ": exact source predecessors at the receiver merge");

        for (PEATestUtils.PEARound round : report.rounds()) {
            assertReplayPaths(round, target, expectedReceivers, expectedPredecessors);
        }
        PEATestUtils.IRBody finalBody = report.finalAfter();
        CFG finalCFG = CFG.parse(finalBody.lines(), target);
        ConsumerMerge finalMerge = finalCFG.consumerMerge(
                sinkName, expectedPredecessors);
        Asserts.assertEquals(ownerAllocationBCIs(finalBody, finalMerge, target),
                ownerAllocationBCIs(input, consumerMerge, target),
                target + ": final receiver phi preserves the selected allocation sites");
        finalCFG.assertRuntimeLockPlacement(finalMerge, sinkName);
        return consumerMerge;
    }

    private static Set<Integer> ownerAllocationBCIs(
            PEATestUtils.IRBody body, ConsumerMerge merge, Method target) {
        Map<String, Integer> allocations = body.allocationBCIsByResult();
        LinkedHashSet<Integer> result = new LinkedHashSet<>();
        for (String receiver : merge.incomingReceivers()) {
            Integer bci = allocations.get("%" + receiver);
            Asserts.assertNotNull(bci,
                    target + ": receiver phi input is an allocation result: " + receiver);
            Asserts.assertTrue(result.add(bci),
                    target + ": receiver phi allocation sites are distinct: " + bci);
        }
        return Set.copyOf(result);
    }

    private static void assertCriticalEdgeReplay(PEATestUtils.RunResult run,
                                                 Method target, Method sink,
                                                 List<List<Integer>> expectedReceivers) {
        ConsumerMerge consumerMerge = assertAlternativeReplay(
                run, target, sink, expectedReceivers, 2);
        CFG cfg = CFG.parse(run.report(target).round0Before().lines(), target);
        boolean hasCriticalEdge = consumerMerge.incomingPredecessors().stream()
                .anyMatch(predecessor -> cfg.distinctSuccessorsOf(predecessor).size() > 1);
        Asserts.assertTrue(hasCriticalEdge,
                target + ": round-0 input must retain a real critical edge into the common consumer");
    }

    private static void assertSamePredecessorMultiEdgeReplay(
            PEATestUtils.RunResult run, Method target, Method sink,
            List<List<Integer>> expectedReceivers) {
        ConsumerMerge consumerMerge = assertAlternativeReplay(
                run, target, sink, expectedReceivers, 2);
        CFG cfg = CFG.parse(run.report(target).round0Before().lines(), target);
        boolean repeatedEdge = cfg.blocks().stream().anyMatch(block ->
                consumerMerge.incomingPredecessors().stream().anyMatch(source ->
                        cfg.successorsOf(block).stream()
                                .filter(source::equals).count() > 1));
        Asserts.assertTrue(repeatedEdge,
                target + ": round-0 input must retain multiple edges from one predecessor"
                        + " to the same successor");
    }

    private static void assertReplayPaths(PEATestUtils.PEARound round,
                                          Method target,
                                          List<List<Integer>> expectedReceivers,
                                          int expectedSources) {
        Map<Integer, List<PEATestUtils.PEALockReplayPhysicalGroup>> byLogical =
                new LinkedHashMap<>();
        for (PEATestUtils.PEALockReplayGroup group
                : round.lockReplayGroups().keySet()) {
            byLogical.computeIfAbsent(group.logicalEscape(), ignored -> new ArrayList<>())
                    .add(new PEATestUtils.PEALockReplayPhysicalGroup(
                            group.batch(), group.source()));
        }

        List<Integer> matchingLogical = new ArrayList<>();
        for (Map.Entry<Integer, List<PEATestUtils.PEALockReplayPhysicalGroup>> entry
                : byLogical.entrySet()) {
            LinkedHashSet<PEATestUtils.PEALockReplayPhysicalGroup> groups =
                    new LinkedHashSet<>(entry.getValue());
            long sourceCount = groups.stream()
                    .map(PEATestUtils.PEALockReplayPhysicalGroup::source)
                    .distinct().count();
            if (sourceCount != expectedSources || groups.size() != expectedSources) {
                continue;
            }
            List<List<Integer>> actual = groups.stream()
                    .map(group -> physicalReceivers(round, target, group))
                    .toList();
            if (sameSequencesIgnoringPathOrder(actual, expectedReceivers)) {
                matchingLogical.add(entry.getKey());
            }
        }
        Asserts.assertEquals(matchingLogical.size(), 1,
                target + ": exactly one common logical escape must own " + expectedSources
                        + " source-specific physical batches with strict a,b,a order;"
                        + " candidates=" + byLogical);
    }

    private static List<Integer> physicalReceivers(
            PEATestUtils.PEARound round, Method target,
            PEATestUtils.PEALockReplayPhysicalGroup group) {
        List<PEATestUtils.PEALockReplay> rows =
                round.lockReplayPhysicalGroups().get(group);
        Asserts.assertNotNull(rows, target + ": logical group refers to a physical batch");
        LinkedHashMap<Integer, PEATestUtils.PEALockReplay> byOrdinal =
                new LinkedHashMap<>();
        for (PEATestUtils.PEALockReplay row : rows) {
            PEATestUtils.PEALockReplay previous = byOrdinal.putIfAbsent(
                    row.ordinal(), row);
            if (previous != null) {
                Asserts.assertEquals(row.receiverVO(), previous.receiverVO(),
                        target + ": logical alias does not change physical receiver");
                Asserts.assertEquals(row.depth(), previous.depth(),
                        target + ": logical alias does not change physical depth");
            }
        }
        Asserts.assertEquals(new ArrayList<>(byOrdinal.keySet()), List.of(0, 1, 2),
                target + ": each physical ordinal occurs exactly once after alias folding");
        Asserts.assertEquals(byOrdinal.values().stream()
                        .map(PEATestUtils.PEALockReplay::depth).toList(),
                List.of(0, 1, 2),
                target + ": physical replay follows global bytecode lock depth");
        return byOrdinal.values().stream()
                .map(PEATestUtils.PEALockReplay::receiverVO).toList();
    }

    private static boolean sameSequencesIgnoringPathOrder(List<List<Integer>> actual,
                                                          List<List<Integer>> expected) {
        return actual.size() == expected.size()
                && new HashSet<>(actual).equals(new HashSet<>(expected));
    }

    private record Definition(String block, String line) { }

    private record ConsumerMerge(String consumerBlock, String mergeBlock,
                                 String receiver,
                                 Set<String> incomingPredecessors,
                                 Set<String> incomingReceivers) { }

    private static final class CFG {
        private final Method method;
        private final Map<String, List<String>> blockLines;
        private final Map<String, List<String>> successors;
        private final Map<String, Definition> definitions;

        private CFG(Method method, Map<String, List<String>> blockLines,
                    Map<String, List<String>> successors,
                    Map<String, Definition> definitions) {
            this.method = method;
            this.blockLines = blockLines;
            this.successors = successors;
            this.definitions = definitions;
        }

        static CFG parse(List<String> lines, Method method) {
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
            if (blocks.isEmpty()) {
                throw new AssertionError(method + ": no labeled blocks in round-0 input");
            }
            LinkedHashMap<String, List<String>> successors = new LinkedHashMap<>();
            HashMap<String, Definition> definitions = new HashMap<>();
            for (Map.Entry<String, List<String>> block : blocks.entrySet()) {
                ArrayList<String> targets = new ArrayList<>();
                for (String line : block.getValue()) {
                    Matcher reference = LABEL_REFERENCE.matcher(line);
                    while (reference.find()) {
                        targets.add(reference.group(1));
                    }
                    Matcher definition = Pattern.compile(
                            "^\\s*(" + LOCAL + ")\\s*=.*$").matcher(line);
                    if (definition.matches()) {
                        Definition previous = definitions.putIfAbsent(
                                definition.group(1),
                                new Definition(block.getKey(), line));
                        Asserts.assertNull(previous,
                                method + ": duplicate SSA definition "
                                        + definition.group(1));
                    }
                }
                successors.put(block.getKey(), List.copyOf(targets));
            }
            return new CFG(method, Map.copyOf(blocks), Map.copyOf(successors),
                    Map.copyOf(definitions));
        }

        Set<String> blocks() {
            return blockLines.keySet();
        }

        String uniqueBlockContaining(String text) {
            List<String> matches = blockLines.entrySet().stream()
                    .filter(entry -> entry.getValue().stream()
                            .anyMatch(line -> line.contains(text)))
                    .map(Map.Entry::getKey).toList();
            Asserts.assertEquals(matches.size(), 1,
                    method + ": exact common consumer block for " + text);
            return matches.get(0);
        }

        ConsumerMerge consumerMerge(String sinkName, int expectedPredecessors) {
            String consumer = uniqueBlockContaining(sinkName);
            List<String> monitorLines = blockLines.get(consumer).stream()
                    .filter(line -> line.contains(MONITOR_ENTER)).toList();
            Asserts.assertEquals(monitorLines.size(), 1,
                    method + ": common consumer has exactly one selected-receiver enter");
            Matcher receiverMatcher = MONITOR_RECEIVER.matcher(monitorLines.get(0));
            Asserts.assertTrue(receiverMatcher.find(),
                    method + ": common consumer monitor has an SSA receiver");
            String receiver = receiverMatcher.group(1);

            Definition receiverDefinition = definitions.get(receiver);
            Asserts.assertNotNull(receiverDefinition,
                    method + ": selected receiver has one SSA definition");
            Asserts.assertTrue(receiverDefinition.line().contains("= phi ptr addrspace(1)"),
                    method + ": selected receiver is a pointer phi, got "
                            + receiverDefinition.line());

            LinkedHashSet<String> phiPredecessors = new LinkedHashSet<>();
            LinkedHashSet<String> phiReceivers = new LinkedHashSet<>();
            Matcher incoming = PHI_INCOMING.matcher(receiverDefinition.line());
            int incomingCount = 0;
            while (incoming.find()) {
                incomingCount++;
                phiReceivers.add(incoming.group(1));
                phiPredecessors.add(incoming.group(2));
            }
            Asserts.assertEquals(incomingCount, expectedPredecessors,
                    method + ": receiver phi has one incoming value per source path");
            Asserts.assertEquals(phiReceivers.size(), expectedPredecessors,
                    method + ": receiver phi selects a distinct owner on every source path");
            Asserts.assertEquals(phiPredecessors.size(), expectedPredecessors,
                    method + ": receiver phi incoming labels are distinct");
            Asserts.assertEquals(predecessorsOf(receiverDefinition.block()),
                    phiPredecessors,
                    method + ": receiver phi covers every CFG predecessor exactly");

            assertTransparentReceiverPath(
                    receiverDefinition.block(), consumer, receiver, new HashSet<>());
            return new ConsumerMerge(consumer, receiverDefinition.block(), receiver,
                    Set.copyOf(phiPredecessors), Set.copyOf(phiReceivers));
        }

        void assertRuntimeLockPlacement(ConsumerMerge merge, String sinkName) {
            List<String> consumerLines = blockLines.get(merge.consumerBlock());
            List<String> realEnters = consumerLines.stream()
                    .filter(line -> line.contains(MONITOR_ENTER))
                    .toList();
            Asserts.assertEquals(realEnters.size(), 1,
                    method + ": common consumer retains one real selected-receiver enter");
            Matcher receiver = MONITOR_RECEIVER.matcher(realEnters.get(0));
            Asserts.assertTrue(receiver.find(),
                    method + ": surviving consumer enter has an SSA receiver");
            Asserts.assertEquals(receiver.group(1), merge.receiver(),
                    method + ": surviving consumer enter locks the receiver phi");
            int enterIndex = consumerLines.indexOf(realEnters.get(0));
            int sinkIndex = -1;
            for (int i = 0; i < consumerLines.size(); i++) {
                if (consumerLines.get(i).contains(sinkName)) {
                    Asserts.assertEquals(sinkIndex, -1,
                            method + ": common consumer contains one sink");
                    sinkIndex = i;
                }
            }
            Asserts.assertTrue(enterIndex < sinkIndex,
                    method + ": surviving receiver enter executes before the sink");

            for (String predecessor : merge.incomingPredecessors()) {
                List<String> replayEnters = blockLines.get(predecessor).stream()
                        .filter(line -> line.contains(MONITOR_ENTER))
                        .toList();
                Asserts.assertEquals(replayEnters.size(), 3,
                        method + ": each incoming edge replays exactly a,b,a before merge: "
                                + predecessor);
                List<String> receivers = replayEnters.stream().map(line -> {
                    Matcher matcher = MONITOR_RECEIVER.matcher(line);
                    Asserts.assertTrue(matcher.find(),
                            method + ": replay enter has an SSA receiver: " + line);
                    return matcher.group(1);
                }).toList();
                Asserts.assertEquals(receivers.get(0), receivers.get(2),
                        method + ": incoming edge preserves reentrant a identity");
                Asserts.assertTrue(!receivers.get(0).equals(receivers.get(1)),
                        method + ": incoming edge preserves distinct middle b owner");
            }
        }

        private void assertTransparentReceiverPath(String block, String consumer,
                                                   String receiver,
                                                   Set<String> visited) {
            Asserts.assertTrue(visited.add(block),
                    method + ": receiver path to common consumer is acyclic");
            if (block.equals(consumer)) {
                return;
            }
            Asserts.assertFalse(blockLines.get(block).stream()
                            .anyMatch(line -> line.contains(MONITOR_ENTER)),
                    method + ": receiver path has no earlier monitorenter in " + block);
            Asserts.assertFalse(blockLines.get(block).stream()
                            .anyMatch(line -> line.contains("dontinlineSink")),
                    method + ": receiver path has no earlier sink in " + block);

            List<String> liveSuccessors = distinctSuccessorsOf(block).stream()
                    .filter(successor -> reaches(successor, consumer, new HashSet<>()))
                    .toList();
            Asserts.assertEquals(liveSuccessors.size(), 1,
                    method + ": exactly one receiver-preserving path reaches the consumer from "
                            + block);
            String next = liveSuccessors.get(0);
            Asserts.assertEquals(predecessorsOf(next), Set.of(block),
                    method + ": transparent receiver path does not merge another state at "
                            + next);
            Asserts.assertTrue(blockLines.get(block).stream()
                            .anyMatch(line -> line.contains(receiver)),
                    method + ": transparent block keeps the selected receiver live: " + block);
            assertTransparentReceiverPath(next, consumer, receiver, visited);
        }

        private boolean reaches(String start, String target, Set<String> visited) {
            if (start.equals(target)) {
                return true;
            }
            if (!visited.add(start)) {
                return false;
            }
            return distinctSuccessorsOf(start).stream()
                    .anyMatch(successor -> reaches(successor, target, visited));
        }

        List<String> successorsOf(String block) {
            return successors.getOrDefault(block, List.of());
        }

        Set<String> distinctSuccessorsOf(String block) {
            return new LinkedHashSet<>(successorsOf(block));
        }

        Set<String> predecessorsOf(String target) {
            LinkedHashSet<String> result = new LinkedHashSet<>();
            for (Map.Entry<String, List<String>> entry : successors.entrySet()) {
                if (entry.getValue().contains(target)) {
                    result.add(entry.getKey());
                }
            }
            return result;
        }
    }

    public static class TestWrapper {
        private static final int ITERATIONS = 80;

        public static Box saved;

        public static class Box {
            public int id;
            public int x;
            public Box next;

            Box(int id, int x) {
                this.id = id;
                this.x = x;
            }

        }

        public static class MarkerException extends RuntimeException {
            private static final long serialVersionUID = 1L;
        }

        public static void main(String[] args) throws Exception {
            new Box(0, 0);
            new MarkerException();
            PEATestUtils.compileConfiguredTargetsAtLevel4();

            long digest = 0x243F6A8885A308D3L;
            for (int iteration = 0; iteration < ITERATIONS; iteration++) {
                int seed = iteration & 15;
                for (boolean doThrow : new boolean[] {false, true}) {
                    for (boolean left : new boolean[] {false, true}) {
                        saved = null;
                        long result = crossPred(left, doThrow);
                        assertPublished(result, left ? 2 : 3,
                                left ? 21 : 31, doThrow, "two predecessors");
                        digest = mix(digest, result);
                    }
                    for (int selector = 0; selector < 3; selector++) {
                        saved = null;
                        long result = threePred(selector, doThrow, seed);
                        int id = selector + 2;
                        assertPublished(result, id, seed + id * 10 + 1,
                                doThrow, "three predecessors");
                        digest = mix(digest, result);
                    }
                    for (boolean left : new boolean[] {false, true}) {
                        for (boolean early : new boolean[] {false, true}) {
                            saved = null;
                            long result = criticalEdge(left, early, doThrow, seed);
                            if (left && early) {
                                Asserts.assertNull(saved,
                                        "critical-edge early path does not publish");
                                Asserts.assertEquals(result, earlyResult(seed),
                                        "critical-edge early result");
                            } else {
                                assertPublished(result, left ? 2 : 3,
                                        seed + (left ? 21 : 31), doThrow,
                                        "critical edge");
                            }
                            digest = mix(digest, result);
                        }
                    }
                    for (int selector = 0; selector < 3; selector++) {
                        saved = null;
                        long result = samePredMultiEdge(selector, doThrow, seed);
                        int id = selector < 2 ? 2 : 3;
                        assertPublished(result, id, seed + id * 10 + 1,
                                doThrow, "same predecessor multiple edges");
                        digest = mix(digest, result);
                    }
                }
            }
            System.out.println("PEA-RESULT:" + Long.toUnsignedString(digest, 16));
        }

        public static long crossPred(boolean left, boolean doThrow) {
            Box a = new Box(1, 10);
            Box b = new Box(2, 20);
            Box c = new Box(3, 30);
            b.next = a;
            Box r;
            int caught = 0;
            try {
                synchronized (a) {
                    synchronized (b) {
                        synchronized (b.next) {
                            if (left) {
                                b.x = 21;
                                r = b;
                            } else {
                                c.x = 31;
                                r = c;
                            }
                            synchronized (r) {
                                dontinlineSink(r);
                                if (saved != r) {
                                    return Long.MIN_VALUE;
                                }
                                if (doThrow) {
                                    throwMarker();
                                }
                            }
                        }
                    }
                }
            } catch (MarkerException expected) {
                caught = 1;
            }
            return encode(a, b, c, saved, caught);
        }

        public static long threePred(int selector, boolean doThrow, int seed) {
            Box a = new Box(1, seed + 10);
            Box b = new Box(2, seed + 20);
            Box c = new Box(3, seed + 30);
            Box d = new Box(4, seed + 40);
            b.next = a;
            Box r;
            int caught = 0;
            try {
                synchronized (a) {
                    synchronized (b) {
                        synchronized (b.next) {
                            if (selector == 0) {
                                b.x = seed + 21;
                                r = b;
                            } else if (selector == 1) {
                                c.x = seed + 31;
                                r = c;
                            } else {
                                d.x = seed + 41;
                                r = d;
                            }
                            synchronized (r) {
                                dontinlineSink(r);
                                if (saved != r) {
                                    return Long.MIN_VALUE;
                                }
                                if (doThrow) {
                                    throwMarker();
                                }
                            }
                        }
                    }
                }
            } catch (MarkerException expected) {
                caught = 1;
            }
            return encode(a, b, c, saved, caught) ^ ((long) d.x << 8);
        }

        public static long criticalEdge(boolean left, boolean early,
                                        boolean doThrow, int seed) {
            Box a = new Box(1, seed + 10);
            Box b = new Box(2, seed + 20);
            Box c = new Box(3, seed + 30);
            b.next = a;
            Box r;
            int caught = 0;
            try {
                synchronized (a) {
                    synchronized (b) {
                        synchronized (b.next) {
                            if (left) {
                                b.x = seed + 21;
                                r = b;
                                if (early) {
                                    return earlyResult(seed);
                                }
                            } else {
                                c.x = seed + 31;
                                r = c;
                            }
                            synchronized (r) {
                                dontinlineSink(r);
                                if (saved != r) {
                                    return Long.MIN_VALUE;
                                }
                                if (doThrow) {
                                    throwMarker();
                                }
                            }
                        }
                    }
                }
            } catch (MarkerException expected) {
                caught = 1;
            }
            return encode(a, b, c, saved, caught);
        }

        public static long samePredMultiEdge(int selector, boolean doThrow, int seed) {
            Box a = new Box(1, seed + 10);
            Box b = new Box(2, seed + 20);
            Box c = new Box(3, seed + 30);
            b.next = a;
            Box r;
            int caught = 0;
            try {
                synchronized (a) {
                    synchronized (b) {
                        synchronized (b.next) {
                            switch (selector) {
                                case 0:
                                case 1:
                                    b.x = seed + 21;
                                    r = b;
                                    break;
                                default:
                                    c.x = seed + 31;
                                    r = c;
                            }
                            synchronized (r) {
                                dontinlineSink(r);
                                if (saved != r) {
                                    return Long.MIN_VALUE;
                                }
                                if (doThrow) {
                                    throwMarker();
                                }
                            }
                        }
                    }
                }
            } catch (MarkerException expected) {
                caught = 1;
            }
            return encode(a, b, c, saved, caught);
        }

        public static void dontinlineSink(Box value) {
            saved = value;
        }

        public static void throwMarker() {
            throw new MarkerException();
        }

        private static void assertPublished(long result, int id, int x,
                                            boolean doThrow, String context) {
            Asserts.assertNotEquals(result, Long.MIN_VALUE,
                    context + ": sink observes the selected identity");
            Box value = saved;
            Asserts.assertNotNull(value, context + ": selected object is published");
            Asserts.assertEquals(value.id, id, context + ": escaped id");
            Asserts.assertEquals(value.x, x, context + ": escaped field value");
            Asserts.assertFalse(Thread.holdsLock(value),
                    context + ": escaped monitor was fully released");
            synchronized (value) {
                Asserts.assertEquals(value.id, id,
                        context + ": escaped identity is reacquirable");
                Asserts.assertEquals(value.x, x,
                        context + ": escaped field survives reacquire");
            }
            int caught = (int) (result & 1L);
            Asserts.assertEquals(caught, doThrow ? 1 : 0,
                    context + ": exact normal/throw completion");
        }

        private static long encode(Box a, Box b, Box c, Box r, int caught) {
            return ((long) a.x << 48) ^ ((long) b.x << 32)
                    ^ ((long) c.x << 16) ^ ((long) r.id << 1) ^ caught;
        }

        private static long earlyResult(int seed) {
            return 0x7A11_0000_0000_0000L ^ seed;
        }

        private static long mix(long digest, long value) {
            return Long.rotateLeft(digest ^ value, 13) * 0x9E3779B97F4A7C15L;
        }
    }
}
