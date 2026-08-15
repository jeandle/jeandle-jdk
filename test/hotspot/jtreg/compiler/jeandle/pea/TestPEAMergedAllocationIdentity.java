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
 * @summary PEA merges compatible branch-local virtual objects only when their
 *          Java identity is unobservable
 * @library /test/lib /
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.pea.TestPEAMergedAllocationIdentity
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jdk.test.lib.Asserts;

public class TestPEAMergedAllocationIdentity {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestPEAMergedAllocationIdentity$TestWrapper";
    private static final String NEW_INSTANCE = "@jeandle.new_instance";
    private static final String LOWERED_NEW_INSTANCE = "@new_instance";
    private static final String CASE_C_FIELD_PHI = "pea.casec.field.phi";
    private static final String LLVM_NAME =
            "(?:[-A-Za-z$._0-9]+|\"(?:[^\"\\\\]|\\\\.)*\")";
    private static final String LLVM_LOCAL = "%" + LLVM_NAME;
    private static final String LLVM_OBJECT_VALUE =
            "(?:" + LLVM_LOCAL + "|null|poison|undef)";
    private static final Pattern BLOCK_LABEL = Pattern.compile(
            "^(" + LLVM_NAME + "):(?: ; preds = (.*))?$");
    private static final Pattern LOCAL_REFERENCE = Pattern.compile(LLVM_LOCAL);
    private static final Pattern SOURCE_ALLOCATION = Pattern.compile(
            "^(" + LLVM_LOCAL + ") = .*@jeandle\\.new_instance\\(");
    private static final Pattern OBJECT_PHI = Pattern.compile(
            "^(" + LLVM_LOCAL + ") = phi ptr addrspace\\(1\\) (.*)$");
    private static final Pattern PHI_INCOMING = Pattern.compile(
            "\\[\\s*(" + LLVM_OBJECT_VALUE + "),\\s*(" + LLVM_LOCAL + ")\\s*\\]");
    private static final Pattern OBJECT_SELECT = Pattern.compile(
            "^(" + LLVM_LOCAL + ") = select i1 [^,]+, ptr addrspace\\(1\\) ("
                    + LLVM_OBJECT_VALUE + "), ptr addrspace\\(1\\) ("
                    + LLVM_OBJECT_VALUE + ")(?:,.*)?$");
    private static final Pattern DEOPT_BCI = Pattern.compile(
            "\\\"deopt\\\"\\(i64 0, i32 (-?\\d+), i32 \\1,");

    public static void main(String[] args) throws Exception {
        Method read = TestWrapper.class.getMethod("testMergeReadField",
                boolean.class, int.class, int.class);
        Method observable = TestWrapper.class.getMethod("testMergeIdentityObservable",
                boolean.class);
        Method alias = TestWrapper.class.getMethod("testMergeOneBranchAlias",
                boolean.class);
        Method transitive = TestWrapper.class.getMethod("testMergeTransitiveSelectAlias",
                boolean.class, boolean.class);
        Method different = TestWrapper.class.getMethod("testMergeDifferentClass",
                boolean.class);
        Method[] targets = {read, observable, alias, transitive, different};

        try (PEATestUtils.RunResult run = PEATestUtils.shapeRun(WRAPPER, targets).run()) {
            assertSuccessfulCaseC(run, read);
            assertIdentityRejected(run, observable, 3,
                    RejectedMergeShape.OBSERVABLE_IDENTITY);
            assertIdentityRejected(run, alias, 2,
                    RejectedMergeShape.ONE_BRANCH_ALIAS);
            assertIdentityRejected(run, transitive, 2,
                    RejectedMergeShape.TRANSITIVE_SELECT_ALIAS);
            assertDifferentClassRejected(run, different);
        }

        PEATestUtils.assertPEAOnOffEquivalent(WRAPPER, targets);
    }

    private static void assertSuccessfulCaseC(PEATestUtils.RunResult run, Method target)
            throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.PEARound first = report.round(0);
        assertRound0Stats(report, target, 2, 0, 0);

        Asserts.assertEquals(first.before().peaAllocCount(), 2,
                target + ": two source allocations before PEA");
        assertSuccessfulAllocationPhi(first.before(), target);
        first.before().assertAbsent(CASE_C_FIELD_PHI);
        first.before().assertLineCount("store atomic", 3);
        first.before().assertLineCount("load atomic", 2);
        Asserts.assertEquals(effectCount(first, "EliminateAllocation"), 2,
                target + ": source allocation effects");
        Asserts.assertEquals(effectTargetCount(first, "EliminateStore", "store atomic"), 3,
                target + ": field-store effects");
        Asserts.assertEquals(effectTargetCount(first, "ReplaceLoad", "load atomic"), 2,
                target + ": field-load effects");
        Asserts.assertEquals(effectCount(first, "CreatePHI"), 1,
                target + ": synthetic field merge effect");

        PEATestUtils.IRBody after = report.finalAfter();
        Asserts.assertEquals(after.peaAllocCount(), 0, target + ": allocations after PEA");
        after.assertOccurrenceCount(CASE_C_FIELD_PHI, 2);
        after.assertAbsent("store atomic");
        after.assertAbsent("load atomic");
        Asserts.assertEquals(run.finalIR(target).loweredAllocCount(), 0,
                target + ": final dump allocations");
        run.finalIR(target).assertAbsent("store atomic");
        run.finalIR(target).assertAbsent("load atomic");
    }

    private static void assertIdentityRejected(PEATestUtils.RunResult run, Method target,
                                               int sourceAllocations,
                                               RejectedMergeShape mergeShape) throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.PEARound first = report.round(0);
        Asserts.assertTrue(first.hasStats(), target + ": missing round-0 PEA stats");
        Asserts.assertEquals(first.neverEscapes() + first.partiallyEscapes()
                        + first.alwaysEscapes(), sourceAllocations,
                target + ": classified source allocations");
        Asserts.assertEquals(report.round0Before().peaAllocCount(), sourceAllocations,
                target + ": source allocations before PEA");
        assertRejectedAllocationMerges(report.round0Before(), mergeShape, target);
        Asserts.assertEquals(effectCount(first, "EliminateAllocation"), sourceAllocations,
                target + ": source allocation effects");
        Asserts.assertEquals(effectCount(first, "CreatePHI"), 0,
                target + ": identity-observable merge must not create a Case-C field PHI");
        report.round0Before().assertAbsent(CASE_C_FIELD_PHI);
        first.after().assertAbsent(CASE_C_FIELD_PHI);
        report.finalAfter().assertAbsent(CASE_C_FIELD_PHI);
        run.finalIR(target).assertAbsent(CASE_C_FIELD_PHI);
    }

    private static void assertDifferentClassRejected(PEATestUtils.RunResult run, Method target)
            throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        assertRound0Stats(report, target, 0, 2, 0);
        Asserts.assertEquals(report.round0Before().peaAllocCount(), 2,
                target + ": source allocations before PEA");
        assertDifferentClassAllocationPhi(report.round0Before(), target);
        Asserts.assertEquals(effectCount(report.round(0), "EliminateAllocation"), 2,
                target + ": analyzed source allocations");
        Asserts.assertEquals(effectCount(report.round(0), "Materialize"), 2,
                target + ": predecessor materializations");
        Asserts.assertEquals(effectCount(report.round(0), "CreatePHI"), 0,
                target + ": incompatible types must not create a Case-C field PHI");
        report.round0Before().assertAbsent(CASE_C_FIELD_PHI);
        report.round(0).after().assertAbsent(CASE_C_FIELD_PHI);
        report.finalAfter().assertAbsent(CASE_C_FIELD_PHI);
        run.finalIR(target).assertAbsent(CASE_C_FIELD_PHI);
        Asserts.assertEquals(report.finalAfter().peaAllocCount(), 2,
                target + ": both source OrigAllocs retained");
        assertOrigAllocationsRetained(run.frontendIR(target), report.finalAfter(), 2, target);
        assertLoweredOrigAllocationsRetained(run.frontendIR(target), run.finalIR(target),
                2, target);
    }

    private static void assertSuccessfulAllocationPhi(PEATestUtils.IRBody body, Method target) {
        ObjectMergeGraph graph = ObjectMergeGraph.parse(body);
        assertSourceAllocationCount(graph, 2, target);
        Asserts.assertEquals(graph.merges().size(), 1,
                target + ": unique round-0 object PHI");
        ObjectMerge merge = graph.merges().get(0);
        Asserts.assertEquals(merge.kind(), MergeKind.PHI,
                target + ": branch-local allocations must meet at a PHI");
        assertAllAllocationsAreDirectInputs(graph, merge, target);
        assertPhiControlPaths(graph, merge, target);
    }

    private static void assertRejectedAllocationMerges(PEATestUtils.IRBody body,
                                                        RejectedMergeShape shape,
                                                        Method target) {
        ObjectMergeGraph graph = ObjectMergeGraph.parse(body);
        switch (shape) {
            case OBSERVABLE_IDENTITY -> assertObservableIdentityMerges(graph, target);
            case ONE_BRANCH_ALIAS -> assertOneBranchAliasMerges(graph, target);
            case TRANSITIVE_SELECT_ALIAS -> assertTransitiveSelectMerges(graph, target);
        }
    }

    private static void assertObservableIdentityMerges(ObjectMergeGraph graph, Method target) {
        assertSourceAllocationCount(graph, 3, target);
        List<ObjectMerge> candidates = graph.allocationMerges();
        Asserts.assertEquals(candidates.size(), 2,
                target + ": two source-allocation identity PHIs");

        HashMap<String, Integer> inputFrequency = new HashMap<>();
        for (ObjectMerge merge : candidates) {
            Asserts.assertEquals(merge.kind(), MergeKind.PHI,
                    target + ": observable identity candidate kind");
            assertDirectAllocationInputCount(graph, merge, 2, 0, target);
            assertPhiControlPaths(graph, merge, target);
            for (String value : directAllocationInputs(graph, merge)) {
                inputFrequency.merge(value, 1, Integer::sum);
            }
        }
        Asserts.assertEquals(inputFrequency.keySet(), graph.allocationValues(),
                target + ": identity PHIs must reference all three source allocations");
        List<Integer> frequencies = new ArrayList<>(inputFrequency.values());
        frequencies.sort(Integer::compareTo);
        Asserts.assertEquals(frequencies, List.of(1, 1, 2),
                target + ": the shared-arm allocation must feed both identity PHIs");
    }

    private static void assertOneBranchAliasMerges(ObjectMergeGraph graph, Method target) {
        assertSourceAllocationCount(graph, 2, target);
        List<ObjectMerge> candidates = graph.allocationMerges();
        Asserts.assertEquals(candidates.size(), 2,
                target + ": selected-object and kept-alias PHIs");

        int bothAllocations = 0;
        int allocationAndNull = 0;
        for (ObjectMerge merge : candidates) {
            Asserts.assertEquals(merge.kind(), MergeKind.PHI,
                    target + ": one-branch alias candidate kind");
            int sourceInputs = directAllocationInputs(graph, merge).size();
            int nullInputs = valueInputCount(merge, "null");
            if (sourceInputs == 2 && nullInputs == 0) {
                assertAllAllocationsAreDirectInputs(graph, merge, target);
                bothAllocations++;
            } else if (sourceInputs == 1 && nullInputs == 1
                    && merge.inputs().size() == 2) {
                allocationAndNull++;
            } else {
                throw new AssertionError(target + ": unexpected one-branch alias merge " + merge);
            }
            assertPhiControlPaths(graph, merge, target);
        }
        Asserts.assertEquals(bothAllocations, 1,
                target + ": selected-object PHI must directly merge both allocations");
        Asserts.assertEquals(allocationAndNull, 1,
                target + ": kept-alias PHI must merge its allocation with null");
    }

    private static void assertTransitiveSelectMerges(ObjectMergeGraph graph, Method target) {
        assertSourceAllocationCount(graph, 2, target);
        List<ObjectMerge> candidates = graph.allocationMerges();
        Asserts.assertEquals(candidates.size(), 2,
                target + ": selected-object and transitive-alias selects");
        for (ObjectMerge merge : candidates) {
            Asserts.assertEquals(merge.kind(), MergeKind.SELECT,
                    target + ": transitive aliases must be direct selects");
            assertAllAllocationsAreDirectInputs(graph, merge, target);
        }
    }

    private static void assertDifferentClassAllocationPhi(PEATestUtils.IRBody body,
                                                            Method target) {
        ObjectMergeGraph graph = ObjectMergeGraph.parse(body);
        assertSourceAllocationCount(graph, 2, target);
        List<ObjectMerge> candidates = graph.allocationMerges();
        Asserts.assertEquals(candidates.size(), 1,
                target + ": different-class source-allocation PHI count");
        ObjectMerge merge = candidates.get(0);
        Asserts.assertEquals(merge.kind(), MergeKind.PHI,
                target + ": different-class allocations must meet at a PHI");
        assertAllAllocationsAreDirectInputs(graph, merge, target);
        assertPhiControlPaths(graph, merge, target);
    }

    private static void assertSourceAllocationCount(ObjectMergeGraph graph, int expected,
                                                    Method target) {
        Asserts.assertEquals(graph.allocations().size(), expected,
                target + ": parsed source allocation SSA count");
    }

    private static void assertAllAllocationsAreDirectInputs(ObjectMergeGraph graph,
                                                             ObjectMerge merge,
                                                             Method target) {
        assertDirectAllocationInputCount(graph, merge, graph.allocations().size(), 0, target);
        Asserts.assertEquals(new HashSet<>(directAllocationInputs(graph, merge)),
                graph.allocationValues(),
                target + ": merge inputs must be exactly the source allocation SSAs");
    }

    private static void assertDirectAllocationInputCount(ObjectMergeGraph graph,
                                                          ObjectMerge merge,
                                                          int sourceInputs,
                                                          int nullInputs,
                                                          Method target) {
        Asserts.assertEquals(directAllocationInputs(graph, merge).size(), sourceInputs,
                target + ": direct source-allocation inputs for " + merge.result());
        Asserts.assertEquals(valueInputCount(merge, "null"), nullInputs,
                target + ": null inputs for " + merge.result());
        Asserts.assertEquals(merge.inputs().size(), sourceInputs + nullInputs,
                target + ": no unrelated values may supplement source-allocation inputs for "
                        + merge.result());
    }

    private static List<String> directAllocationInputs(ObjectMergeGraph graph,
                                                        ObjectMerge merge) {
        return merge.inputs().stream().map(MergeInput::value)
                .filter(graph.allocationValues()::contains).toList();
    }

    private static int valueInputCount(ObjectMerge merge, String value) {
        return (int) merge.inputs().stream().filter(input -> input.value().equals(value)).count();
    }

    private static void assertPhiControlPaths(ObjectMergeGraph graph, ObjectMerge merge,
                                              Method target) {
        Asserts.assertEquals(merge.kind(), MergeKind.PHI,
                target + ": CFG checks require a PHI");
        Set<String> incomingBlocks = new HashSet<>();
        for (MergeInput input : merge.inputs()) {
            Asserts.assertNotNull(input.predecessor(),
                    target + ": PHI input lacks a predecessor for " + merge.result());
            incomingBlocks.add(input.predecessor());
            SourceAllocation allocation = graph.allocation(input.value());
            if (allocation != null) {
                Asserts.assertTrue(graph.predecessorPathContains(
                                input.predecessor(), allocation.block()),
                        target + ": incoming predecessor " + input.predecessor()
                                + " does not contain source allocation " + allocation.value()
                                + " from block " + allocation.block());
            }
        }
        Asserts.assertEquals(incomingBlocks, graph.predecessors(merge.block()),
                target + ": PHI must be in the merge block for its incoming predecessors");
    }

    private enum RejectedMergeShape {
        OBSERVABLE_IDENTITY,
        ONE_BRANCH_ALIAS,
        TRANSITIVE_SELECT_ALIAS
    }

    private enum MergeKind { PHI, SELECT }

    private record SourceAllocation(String value, String block) { }

    private record MergeInput(String value, String predecessor) { }

    private record ObjectMerge(String result, String block, MergeKind kind,
                               List<MergeInput> inputs) { }

    private record ObjectMergeGraph(List<SourceAllocation> allocations,
                                    List<ObjectMerge> merges,
                                    Map<String, List<String>> blockPredecessors,
                                    Map<String, SourceAllocation> allocationsByValue) {
        private static ObjectMergeGraph parse(PEATestUtils.IRBody body) {
            ArrayList<SourceAllocation> allocations = new ArrayList<>();
            ArrayList<ObjectMerge> merges = new ArrayList<>();
            LinkedHashMap<String, List<String>> predecessors = new LinkedHashMap<>();
            String currentBlock = null;

            for (String line : body.lines()) {
                Matcher block = BLOCK_LABEL.matcher(line);
                if (block.matches()) {
                    currentBlock = block.group(1);
                    ArrayList<String> blockPredecessors = new ArrayList<>();
                    if (block.group(2) != null) {
                        Matcher predecessor = LOCAL_REFERENCE.matcher(block.group(2));
                        while (predecessor.find()) {
                            blockPredecessors.add(blockName(predecessor.group()));
                        }
                    }
                    predecessors.put(currentBlock, List.copyOf(blockPredecessors));
                    continue;
                }

                Matcher allocation = SOURCE_ALLOCATION.matcher(line);
                if (allocation.find()) {
                    requireBlock(body, currentBlock, line);
                    allocations.add(new SourceAllocation(allocation.group(1), currentBlock));
                }

                Matcher phi = OBJECT_PHI.matcher(line);
                if (phi.matches()) {
                    requireBlock(body, currentBlock, line);
                    ArrayList<MergeInput> inputs = new ArrayList<>();
                    Matcher incoming = PHI_INCOMING.matcher(phi.group(2));
                    while (incoming.find()) {
                        inputs.add(new MergeInput(incoming.group(1),
                                blockName(incoming.group(2))));
                    }
                    if (inputs.isEmpty()) {
                        throw malformedIR(body, "object PHI has no parseable inputs: " + line);
                    }
                    merges.add(new ObjectMerge(phi.group(1), currentBlock, MergeKind.PHI,
                            List.copyOf(inputs)));
                    continue;
                }

                Matcher select = OBJECT_SELECT.matcher(line);
                if (select.matches()) {
                    requireBlock(body, currentBlock, line);
                    merges.add(new ObjectMerge(select.group(1), currentBlock, MergeKind.SELECT,
                            List.of(new MergeInput(select.group(2), null),
                                    new MergeInput(select.group(3), null))));
                }
            }

            LinkedHashMap<String, SourceAllocation> byValue = new LinkedHashMap<>();
            for (SourceAllocation allocation : allocations) {
                if (byValue.put(allocation.value(), allocation) != null) {
                    throw malformedIR(body,
                            "duplicate source allocation SSA " + allocation.value());
                }
            }
            return new ObjectMergeGraph(List.copyOf(allocations), List.copyOf(merges),
                    Map.copyOf(predecessors), Map.copyOf(byValue));
        }

        private List<ObjectMerge> allocationMerges() {
            return merges.stream()
                    .filter(merge -> merge.inputs().stream()
                            .anyMatch(input -> allocationsByValue.containsKey(input.value())))
                    .toList();
        }

        private Set<String> allocationValues() {
            return allocationsByValue.keySet();
        }

        private SourceAllocation allocation(String value) {
            return allocationsByValue.get(value);
        }

        private Set<String> predecessors(String block) {
            return Set.copyOf(blockPredecessors.getOrDefault(block, List.of()));
        }

        private boolean predecessorPathContains(String predecessor, String allocationBlock) {
            ArrayDeque<String> work = new ArrayDeque<>();
            HashSet<String> visited = new HashSet<>();
            work.add(predecessor);
            while (!work.isEmpty()) {
                String block = work.removeFirst();
                if (!visited.add(block)) {
                    continue;
                }
                if (block.equals(allocationBlock)) {
                    return true;
                }
                work.addAll(blockPredecessors.getOrDefault(block, List.of()));
            }
            return false;
        }

        private static String blockName(String reference) {
            return reference.substring(1);
        }

        private static void requireBlock(PEATestUtils.IRBody body, String block, String line) {
            if (block == null) {
                throw malformedIR(body, "instruction outside a basic block: " + line);
            }
        }

        private static AssertionError malformedIR(PEATestUtils.IRBody body, String detail) {
            return new AssertionError(body.methodId() + ": malformed round-0 IR: " + detail);
        }
    }

    private static void assertOrigAllocationsRetained(PEATestUtils.IRBody before,
                                                       PEATestUtils.IRBody after,
                                                       int expected, Method target) {
        List<Integer> sourceBCIs = allocationBCIs(before, NEW_INSTANCE);
        List<Integer> finalBCIs = allocationBCIs(after, NEW_INSTANCE);
        Asserts.assertEquals(sourceBCIs.size(), expected,
                target + ": source allocation BCI count");
        Asserts.assertEquals(finalBCIs, sourceBCIs,
                target + ": retained allocations must be the source OrigAllocs in source order");
    }

    private static void assertLoweredOrigAllocationsRetained(PEATestUtils.IRBody before,
                                                              PEATestUtils.IRBody lowered,
                                                              int expected, Method target) {
        List<Integer> sourceBCIs = allocationBCIs(before, NEW_INSTANCE);
        List<Integer> loweredBCIs = allocationBCIs(lowered, LOWERED_NEW_INSTANCE);
        Asserts.assertEquals(sourceBCIs.size(), expected,
                target + ": source allocation BCI count");
        Asserts.assertEquals(loweredBCIs, sourceBCIs,
                target + ": lowered allocations must preserve source BCI and order");
    }

    private static List<Integer> allocationBCIs(PEATestUtils.IRBody body, String callee) {
        ArrayList<Integer> result = new ArrayList<>();
        for (String line : body.lines()) {
            if (!line.contains(callee)) {
                continue;
            }
            Matcher matcher = DEOPT_BCI.matcher(line);
            if (!matcher.find()) {
                throw new AssertionError(body.methodId() + ": allocation lacks a source BCI: "
                        + line);
            }
            result.add(Integer.parseInt(matcher.group(1)));
        }
        return List.copyOf(result);
    }

    private static int effectCount(PEATestUtils.PEARound round, String kind) {
        return (int) round.effects().stream().filter(effect -> effect.kind().equals(kind)).count();
    }

    private static int effectTargetCount(PEATestUtils.PEARound round, String kind,
                                         String targetSubstring) {
        return (int) round.effects().stream()
                .filter(effect -> effect.kind().equals(kind))
                .filter(effect -> effect.detail().contains(targetSubstring))
                .count();
    }

    private static void assertRound0Stats(PEATestUtils.PEAReport report, Method target,
                                          int never, int partial, int always) {
        PEATestUtils.PEARound first = report.round(0);
        Asserts.assertTrue(first.hasStats(), target + ": missing round-0 PEA stats");
        Asserts.assertEquals(first.neverEscapes(), never, target + ": NeverEscapes");
        Asserts.assertEquals(first.partiallyEscapes(), partial, target + ": PartiallyEscapes");
        Asserts.assertEquals(first.alwaysEscapes(), always, target + ": AlwaysEscapes");
    }

    public static class TestWrapper {
        public static class P {
            public volatile int x;
            public int y;
        }

        public static class Q {
            public int y;
        }

        public static void main(String[] args) throws Exception {
            new P();
            new Q();
            PEATestUtils.compileConfiguredTargetsAtLevel4();

            long digest = 0x243F6A8885A308D3L;
            digest = mix(digest, testMergeReadField(true, 11, 22));
            digest = mix(digest, testMergeReadField(false, 11, 22));
            digest = mix(digest, testMergeIdentityObservable(true) ? 1 : 0);
            digest = mix(digest, testMergeIdentityObservable(false) ? 1 : 0);
            digest = mix(digest, testMergeOneBranchAlias(true) ? 1 : 0);
            digest = mix(digest, testMergeOneBranchAlias(false) ? 1 : 0);
            for (boolean c : new boolean[] {false, true}) {
                for (boolean d : new boolean[] {false, true}) {
                    boolean actual = testMergeTransitiveSelectAlias(c, d);
                    Asserts.assertEquals(actual, c == d);
                    digest = mix(digest, actual ? 1 : 0);
                }
            }
            digest = mix(digest, testMergeDifferentClass(true));
            digest = mix(digest, testMergeDifferentClass(false));

            Asserts.assertEquals(testMergeReadField(true, 11, 22), 42);
            Asserts.assertEquals(testMergeReadField(false, 11, 22), 53);
            Asserts.assertTrue(testMergeIdentityObservable(true));
            Asserts.assertFalse(testMergeIdentityObservable(false));
            Asserts.assertTrue(testMergeOneBranchAlias(true));
            Asserts.assertFalse(testMergeOneBranchAlias(false));
            Asserts.assertEquals(testMergeDifferentClass(true), 1);
            Asserts.assertEquals(testMergeDifferentClass(false), 2);
            System.out.println("PEA-RESULT:" + Long.toUnsignedString(digest, 16));
        }

        public static int testMergeReadField(boolean condition, int trueValue, int falseValue) {
            P selected;
            if (condition) {
                P first = new P();
                first.x = trueValue;
                selected = first;
            } else {
                P second = new P();
                second.x = falseValue;
                selected = second;
            }
            selected.y = 31;
            return selected.x + selected.y;
        }

        public static boolean testMergeIdentityObservable(boolean condition) {
            P first;
            P second;
            if (condition) {
                P shared = new P();
                shared.x = 41;
                first = shared;
                second = shared;
            } else {
                P left = new P();
                left.x = 42;
                P right = new P();
                right.x = 43;
                first = left;
                second = right;
            }
            first.x = first.x + 1;
            second.x = second.x + 1;
            return first == second;
        }

        public static boolean testMergeOneBranchAlias(boolean condition) {
            P selected;
            P kept;
            if (condition) {
                P first = new P();
                first.x = 51;
                selected = first;
                kept = first;
            } else {
                P second = new P();
                second.x = 52;
                selected = second;
                kept = null;
            }
            selected.x = selected.x + 1;
            if (kept != null) {
                kept.y = 53;
            }
            return kept == selected;
        }

        public static boolean testMergeTransitiveSelectAlias(boolean condition,
                                                             boolean aliasCondition) {
            P first = new P();
            first.x = 61;
            P second = new P();
            second.x = 62;
            P selected = condition ? first : second;
            P alias = aliasCondition ? first : second;
            selected.x = selected.x + 1;
            alias.x = alias.x + 1;
            return alias == selected;
        }

        public static int testMergeDifferentClass(boolean condition) {
            Object selected;
            if (condition) {
                P first = new P();
                first.x = 1;
                selected = first;
            } else {
                Q second = new Q();
                second.y = 2;
                selected = second;
            }
            if (selected instanceof P) {
                return ((P) selected).x;
            }
            return ((Q) selected).y;
        }

        private static long mix(long digest, int value) {
            return Long.rotateLeft(digest ^ (value & 0xFFFF_FFFFL), 11)
                    * 0x9E3779B97F4A7C15L;
        }
    }
}
