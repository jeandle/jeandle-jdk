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
 * @summary PEA materializes virtual arrays exactly once at symbolic-index
 *          loads and stores while preserving bounds, replay, and nested state
 * @library /test/lib /
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.pea.TestSymbolicIndexStorePEA
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jdk.test.lib.Asserts;

public class TestSymbolicIndexStorePEA {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestSymbolicIndexStorePEA$TestWrapper";
    private static final String BOUNDS_COMPARE = "icmp ult i32";
    private static final String DEOPTIMIZE = "@llvm.experimental.deoptimize";
    private static final String DEOPTIMIZE_I64 = "llvm.experimental.deoptimize.i64";
    private static final String DEOPTIMIZE_I64_CALL = "@" + DEOPTIMIZE_I64;
    private static final String LOWERED_DEOPTIMIZE = "@__llvm_deoptimize";
    private static final String LLVM_LOCAL =
            "(?:%[-A-Za-z$._0-9]+|%\"(?:[^\"\\\\]|\\\\.)*\")";
    private static final String GEP_FLAGS =
            "(?: inbounds)?(?: nusw)?(?: nuw)?"
            + "(?: inrange\\(-?\\d+, -?\\d+\\))?";

    public static void main(String[] args) throws Exception {
        Method store = TestWrapper.class.getMethod(
                "testSymbolicStore", int.class, int.class);
        Method load = TestWrapper.class.getMethod(
                "testSymbolicLoad", int.class);
        Method loadThenStore = TestWrapper.class.getMethod(
                "testLoadThenStore", int.class, int.class);
        Method twoIndexes = TestWrapper.class.getMethod(
                "testTwoIndexes", int.class, int.class, int.class);
        Method nested = TestWrapper.class.getMethod(
                "testNestedSymbolicStore", int.class, int.class);
        Method[] targets = {store, load, loadThenStore, twoIndexes, nested};

        PEATestUtils.behaviorRun(WRAPPER, targets).runPEAOnOffEquivalent();

        try (PEATestUtils.RunResult run = PEATestUtils.shapeRun(WRAPPER, targets).run()) {
            for (Method target : targets) {
                run.report(target).assertFinalTransformIdle();
            }
            assertSymbolicStore(run, store);
            assertSymbolicLoad(run, load);
            assertLoadThenStore(run, loadThenStore);
            assertTwoIndexes(run, twoIndexes);
            assertNestedStore(run, nested);
        }
    }

    private static void assertSymbolicStore(PEATestUtils.RunResult run, Method target)
            throws Exception {
        PEATestUtils.PEAReport report = assertOriginalMaterialization(run, target, 1);
        PEATestUtils.IRBody after = report.finalAfter();
        PEATestUtils.IRBlock consumer = after.blockContaining("_checksumValues", 0);
        assertIntArrayDescriptor(assertBoundsFallback(after, 0, 27, consumer), target);
        PEATestUtils.IRBody finalIR = run.finalIR(target);
        assertFinalBoundsFallback(finalIR,
                List.of(finalIR.blockContaining("_checksumValues", 0)));
        consumer.assertOccurrenceCount("store atomic", 5);
        for (int replay = 0; replay < 4; replay++) {
            consumer.assertBefore("store atomic", replay, "store atomic", 4);
        }
        assertNoAllocationAtConsumer(consumer);
        assertNormalPathHasNoDeopt(run, target, consumer, "store atomic", 0);
    }

    private static void assertSymbolicLoad(PEATestUtils.RunResult run, Method target)
            throws Exception {
        PEATestUtils.PEAReport report = assertOriginalMaterialization(run, target, 1);
        PEATestUtils.IRBody after = report.finalAfter();
        PEATestUtils.IRBlock consumer = after.blockContaining("_checksumValues", 0);
        assertIntArrayDescriptor(assertBoundsFallback(after, 0, 26, consumer), target);
        PEATestUtils.IRBody finalIR = run.finalIR(target);
        assertFinalBoundsFallback(finalIR,
                List.of(finalIR.blockContaining("_checksumValues", 0)));
        consumer.assertOccurrenceCount("store atomic", 4);
        for (int replay = 0; replay < 4; replay++) {
            consumer.assertBefore("store atomic", replay, "load atomic", 0);
        }
        assertNoAllocationAtConsumer(consumer);
        assertNormalPathHasNoDeopt(run, target, consumer, "store atomic", 0);
    }

    private static void assertLoadThenStore(PEATestUtils.RunResult run, Method target)
            throws Exception {
        PEATestUtils.PEAReport report = assertOriginalMaterialization(run, target, 1);
        PEATestUtils.IRBody after = report.finalAfter();
        PEATestUtils.IRBlock firstConsumer = after.blockContaining("_checksumValues", 0);
        assertIntArrayDescriptor(
                assertBoundsFallback(after, 0, 26, firstConsumer), target);
        PEATestUtils.IRBody finalIR = run.finalIR(target);
        assertFinalBoundsFallback(finalIR,
                List.of(finalIR.blockContaining("_checksumValues", 0)));
        firstConsumer.assertOccurrenceCount("store atomic", 5);
        for (int replay = 0; replay < 4; replay++) {
            firstConsumer.assertBefore("store atomic", replay, "load atomic", 0);
        }
        firstConsumer.assertBefore("load atomic", 0, "store atomic", 4);
        assertNoAllocationAtConsumer(firstConsumer);
        assertNormalPathHasNoDeopt(run, target, firstConsumer, "store atomic", 0);
    }

    private static void assertTwoIndexes(PEATestUtils.RunResult run, Method target)
            throws Exception {
        PEATestUtils.PEAReport report = assertOriginalMaterialization(run, target, 1);
        PEATestUtils.IRBody after = report.finalAfter();
        PEATestUtils.IRBlock loadConsumer =
                after.blockContaining(BOUNDS_COMPARE, 1);
        PEATestUtils.IRBlock storeConsumer =
                after.blockContaining("_checksumValues", 0);
        assertIntArrayDescriptor(
                assertBoundsFallback(after, 0, 26, loadConsumer), target);
        PEATestUtils.DeoptBundle secondFallback =
                assertBoundsFallback(after, 1, 32, storeConsumer);
        Asserts.assertEquals(secondFallback.virtualObjects().size(), 0,
                target + ": second bounds fallback uses the first materialization");
        PEATestUtils.IRBody finalIR = run.finalIR(target);
        assertFinalBoundsFallback(finalIR,
                List.of(finalIR.blockContaining(BOUNDS_COMPARE, 1),
                        finalIR.blockContaining("_checksumValues", 0)));

        loadConsumer.assertOccurrenceCount("store atomic", 4);
        for (int replay = 0; replay < 4; replay++) {
            loadConsumer.assertBefore("store atomic", replay, "load atomic", 0);
        }
        assertNoAllocationAtConsumer(loadConsumer);

        PEATestUtils.IRBlock secondBounds = after.blockContaining(BOUNDS_COMPARE, 1);
        secondBounds.assertOccurrenceCount("store atomic", 4);
        for (int replay = 0; replay < 4; replay++) {
            secondBounds.assertBefore("store atomic", replay, BOUNDS_COMPARE, 0);
        }
        secondBounds.assertAbsent("@jeandle.new_array");
        storeConsumer.assertOccurrenceCount("store atomic", 1);
        assertNoAllocationAtConsumer(storeConsumer);
        loadConsumer.assertAbsent(DEOPTIMIZE);
        storeConsumer.assertAbsent(DEOPTIMIZE);
        finalIR.blockContaining("store atomic", 0).assertAbsent(LOWERED_DEOPTIMIZE);
        finalIR.blockContaining("store atomic", 4).assertAbsent(LOWERED_DEOPTIMIZE);
        after.assertBefore("load atomic", 0, BOUNDS_COMPARE, 1);
        after.assertBefore(BOUNDS_COMPARE, 1, "store atomic", 4);
    }

    private static void assertNestedStore(PEATestUtils.RunResult run, Method target)
            throws Exception {
        PEATestUtils.PEAReport report = assertOriginalMaterialization(run, target, 2);
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();
        PEATestUtils.AllocationSite child =
                uniqueAllocation(before, PEATestUtils.AllocationKind.INSTANCE, target);
        PEATestUtils.AllocationSite array =
                uniqueAllocation(before, PEATestUtils.AllocationKind.ARRAY, target);
        String afterChild = allocationResult(after, child.key());
        String afterArray = allocationResult(after, array.key());
        PEATestUtils.DeoptBundle fallback = boundsFallbackAtBCI(after, 25);
        fallback.assertVirtualObjectIds(0, 1);
        PEATestUtils.VirtualObjectDescriptor arrayDescriptor =
                fallback.virtualObject(0);
        PEATestUtils.VirtualObjectDescriptor childDescriptor =
                fallback.virtualObject(1);
        Asserts.assertEquals(arrayDescriptor.kind(),
                PEATestUtils.DescriptorKind.ARRAY,
                target + ": bounds fallback carries the nested array");
        Asserts.assertEquals(childDescriptor.kind(),
                PEATestUtils.DescriptorKind.INSTANCE,
                target + ": bounds fallback carries the nested child");
        List<PEATestUtils.VirtualObjectEntry> childReferences =
                arrayDescriptor.elements().values().stream()
                        .filter(entry -> entry.value().kind()
                                == PEATestUtils.DeoptValueKind.VO_REF
                                && entry.value().virtualObjectId() == 1)
                        .toList();
        Asserts.assertEquals(childReferences.size(), 1,
                target + ": one exact array element references the nested child");
        PEATestUtils.VirtualObjectEntry arrayElement = childReferences.get(0);
        Asserts.assertEquals(arrayElement.offset(), 24,
                target + ": nested child is replayed into array[0]");
        fallback.assertVORef(0, arrayElement.offset(), 1);
        Asserts.assertEquals(childDescriptor.fields().size(), 1,
                target + ": one exact nested child field");
        PEATestUtils.VirtualObjectEntry childField =
                childDescriptor.fields().values().iterator().next();
        Asserts.assertEquals(childField.basicType(), PEATestUtils.DeoptBasicType.INT,
                target + ": nested child value is an int");
        Asserts.assertEquals(childField.value().kind(),
                PEATestUtils.DeoptValueKind.SCALAR,
                target + ": nested child value is scalar");

        NestedStoreShape shape = findNestedStoreShape(
                after, afterArray, afterChild, arrayElement.offset(),
                childField.offset(), childField.value().operand(), target);
        PEATestUtils.IRBlock bounds =
                findExactBoundsBlock(after, shape.sourceIndex(), target);
        PEATestUtils.IRBlock fallbackBlock =
                after.blockContaining(DEOPTIMIZE_I64_CALL,
                        uniqueCallOccurrenceAtBCI(after, DEOPTIMIZE_I64, 25));
        assertNestedConditionalTargets(
                after, bounds, shape, fallbackBlock, true);

        PEATestUtils.IRBody finalIR = run.finalIR(target);
        NestedStoreShape finalShape = findNestedStoreShape(
                finalIR, null, null, arrayElement.offset(),
                childField.offset(), null, target);
        PEATestUtils.IRBlock finalBounds =
                findExactBoundsBlock(finalIR, finalShape.sourceIndex(), target);
        PEATestUtils.IRBlock finalFallback =
                finalIR.blockContaining(LOWERED_DEOPTIMIZE, 0);
        finalIR.assertOccurrenceCount(LOWERED_DEOPTIMIZE, 1);
        assertNestedConditionalTargets(
                finalIR, finalBounds, finalShape, finalFallback, false);

        assertNoAllocationAtConsumer(shape.consumer());
        shape.consumer().assertAbsent(DEOPTIMIZE);
        finalShape.consumer().assertAbsent(LOWERED_DEOPTIMIZE);
    }

    private record NestedStoreShape(
            String sourceIndex, String childReplay, String arrayReplay,
            String symbolicStore, PEATestUtils.IRBlock childReplayBlock,
            PEATestUtils.IRBlock arrayReplayBlock, PEATestUtils.IRBlock consumer) {}

    private static PEATestUtils.AllocationSite uniqueAllocation(
            PEATestUtils.IRBody body, PEATestUtils.AllocationKind kind,
            Method target) {
        List<PEATestUtils.AllocationSite> sites = body.allocations().stream()
                .filter(site -> site.key().kind() == kind).toList();
        Asserts.assertEquals(sites.size(), 1,
                target + ": one source allocation of kind " + kind);
        return sites.get(0);
    }

    private static String allocationResult(
            PEATestUtils.IRBody body, PEATestUtils.AllocationKey key) {
        List<String> results = body.allocations().stream()
                .filter(site -> site.key().equals(key))
                .map(PEATestUtils.AllocationSite::result)
                .toList();
        Asserts.assertEquals(results.size(), 1,
                body.methodId() + ": one allocation for " + key);
        return results.get(0);
    }

    private static PEATestUtils.DeoptBundle boundsFallbackAtBCI(
            PEATestUtils.IRBody body, int bci) {
        return body.deoptBundleAtCall(
                DEOPTIMIZE_I64,
                uniqueCallOccurrenceAtBCI(body, DEOPTIMIZE_I64, bci));
    }

    private static int uniqueCallOccurrenceAtBCI(
            PEATestUtils.IRBody body, String callee, int bci) {
        List<Integer> occurrences = body.callOccurrencesAtBCI(callee, bci);
        Asserts.assertEquals(occurrences.size(), 1,
                body.methodId() + ": exact fallback BCI " + bci);
        return occurrences.get(0);
    }

    private static NestedStoreShape findNestedStoreShape(
            PEATestUtils.IRBody body, String expectedArray, String expectedChild,
            int arrayOffset, int childOffset, String expectedChildValue,
            Method target) {
        record Candidate(
                String sourceIndex, String array, String child,
                String symbolicStore) {}

        List<String> lines = body.lines();
        ArrayList<Candidate> candidates = new ArrayList<>();
        Pattern indexedGEP = Pattern.compile("^(" + LLVM_LOCAL
                + ") = getelementptr" + GEP_FLAGS + " ptr addrspace\\(1\\), "
                + "ptr addrspace\\(1\\) (" + LLVM_LOCAL + "), "
                + "i(?:32|64) (" + LLVM_LOCAL + ")(?:, .*)?$");
        for (String line : lines) {
            Matcher indexed = indexedGEP.matcher(line);
            if (!indexed.matches()) {
                continue;
            }
            String array = directByteGEPOwner(
                    lines, indexed.group(2), arrayOffset);
            if (array == null
                    || expectedArray != null && !expectedArray.equals(array)) {
                continue;
            }
            String sourceIndex = sourceIndex(lines, indexed.group(3));
            if (findBoundsComparisons(lines, sourceIndex).size() != 1) {
                continue;
            }
            Pattern store = Pattern.compile(
                    "^store atomic ptr addrspace\\(1\\) (" + LLVM_LOCAL
                    + "), ptr addrspace\\(1\\) "
                    + Pattern.quote(indexed.group(1))
                    + " unordered, align \\d+(?:, .*)?$");
            for (String storeLine : lines) {
                Matcher matcher = store.matcher(storeLine);
                if (matcher.matches()
                        && (expectedChild == null
                                || expectedChild.equals(matcher.group(1)))) {
                    candidates.add(new Candidate(
                            sourceIndex, array, matcher.group(1), storeLine));
                }
            }
        }
        Asserts.assertEquals(candidates.size(), 1,
                target + ": one symbolic-index store bound to the nested allocations");
        Candidate candidate = candidates.get(0);

        String childReplay = uniqueReplayStore(
                body, candidate.child(), childOffset,
                expectedChildValue == null ? null : "i32 " + scalarValue(
                        expectedChildValue, "i32", target),
                null, "child.value", target);
        String arrayReplay = uniqueReplayStore(
                body, candidate.array(), arrayOffset,
                "ptr addrspace(1) " + candidate.child(),
                candidate.symbolicStore(), "array[0]", target);
        if (expectedChildValue != null) {
            Asserts.assertTrue(childReplay.startsWith(
                            "store atomic " + expectedChildValue + ","),
                    target + ": child.value replay preserves the descriptor operand");
        }
        PEATestUtils.IRBlock childReplayBlock =
                body.blockContaining(childReplay, 0);
        PEATestUtils.IRBlock arrayReplayBlock =
                body.blockContaining(arrayReplay, 0);
        PEATestUtils.IRBlock consumer =
                body.blockContaining(candidate.symbolicStore(), 0);
        assertOrderInSharedBlock(
                childReplayBlock, childReplay, arrayReplayBlock, arrayReplay,
                target + ": child replay precedes array replay");
        assertOrderInSharedBlock(
                arrayReplayBlock, arrayReplay, consumer, candidate.symbolicStore(),
                target + ": array replay precedes the symbolic store");
        return new NestedStoreShape(
                candidate.sourceIndex(), childReplay, arrayReplay,
                candidate.symbolicStore(),
                childReplayBlock, arrayReplayBlock, consumer);
    }

    private static String scalarValue(
            String typedOperand, String expectedType, Method target) {
        String prefix = expectedType + " ";
        Asserts.assertTrue(typedOperand.startsWith(prefix),
                target + ": scalar descriptor operand has type " + expectedType);
        return typedOperand.substring(prefix.length());
    }

    private static String directByteGEPOwner(
            List<String> lines, String result, int offset) {
        Pattern definition = Pattern.compile("^" + Pattern.quote(result)
                + " = getelementptr" + GEP_FLAGS
                + " i8, ptr addrspace\\(1\\) ("
                + LLVM_LOCAL + "), i(?:32|64) " + offset + "(?:, .*)?$");
        String owner = null;
        for (String line : lines) {
            Matcher matcher = definition.matcher(line);
            if (matcher.matches()) {
                if (owner != null) {
                    throw new AssertionError(
                            "Ambiguous byte-GEP definition for " + result);
                }
                owner = matcher.group(1);
            }
        }
        return owner;
    }

    private static String sourceIndex(List<String> lines, String index) {
        Pattern extension = Pattern.compile("^" + Pattern.quote(index)
                + " = (?:zext(?: nneg)?|sext) i32 (" + LLVM_LOCAL
                + ") to i64(?:, .*)?$");
        String source = null;
        for (String line : lines) {
            Matcher matcher = extension.matcher(line);
            if (matcher.matches()) {
                if (source != null) {
                    throw new AssertionError(
                            "Ambiguous symbolic-index definition for " + index);
                }
                source = matcher.group(1);
            }
        }
        return source == null ? index : source;
    }

    private static List<String> findBoundsComparisons(
            List<String> lines, String sourceIndex) {
        Pattern compare = Pattern.compile("^(" + LLVM_LOCAL
                + ") = icmp ult i32 " + Pattern.quote(sourceIndex)
                + ", 4(?:, .*)?$");
        return lines.stream().filter(line -> compare.matcher(line).matches()).toList();
    }

    private static PEATestUtils.IRBlock findExactBoundsBlock(
            PEATestUtils.IRBody body, String sourceIndex, Method target) {
        List<String> comparisons = findBoundsComparisons(body.lines(), sourceIndex);
        Asserts.assertEquals(comparisons.size(), 1,
                target + ": one bounds comparison for the symbolic source index");
        PEATestUtils.IRBlock bounds = body.blockContaining(comparisons.get(0), 0);
        Matcher result = Pattern.compile("^(" + LLVM_LOCAL + ") = ")
                .matcher(comparisons.get(0));
        Asserts.assertTrue(result.find(),
                target + ": bounds comparison has an SSA result");
        bounds.assertOccurrenceCount(comparisons.get(0), 1);
        bounds.assertOccurrenceCount("br i1 " + result.group(1) + ",", 1);
        return bounds;
    }

    private static String uniqueReplayStore(
            PEATestUtils.IRBody body, String owner, int offset,
            String exactTypedValue, String excludedStore,
            String detail, Method target) {
        Pattern gep = Pattern.compile("^(" + LLVM_LOCAL
                + ") = getelementptr" + GEP_FLAGS
                + " i8, ptr addrspace\\(1\\) "
                + Pattern.quote(owner) + ", i(?:32|64) " + offset
                + "(?:, .*)?$");
        ArrayList<String> matches = new ArrayList<>();
        for (String line : body.lines()) {
            Matcher slot = gep.matcher(line);
            if (!slot.matches()) {
                continue;
            }
            Pattern store = exactTypedValue == null
                    ? Pattern.compile("^store atomic i32 .+, ptr addrspace\\(1\\) "
                            + Pattern.quote(slot.group(1))
                            + " unordered, align \\d+(?:, .*)?$")
                    : Pattern.compile("^store atomic "
                            + Pattern.quote(exactTypedValue)
                            + ", ptr addrspace\\(1\\) "
                            + Pattern.quote(slot.group(1))
                            + " unordered, align \\d+(?:, .*)?$");
            body.lines().stream().filter(candidate -> store.matcher(candidate).matches())
                    .filter(candidate -> !candidate.equals(excludedStore))
                    .forEach(matches::add);
        }
        Asserts.assertEquals(matches.size(), 1,
                target + ": one exact replay store for " + detail);
        return matches.get(0);
    }

    private static void assertOrderInSharedBlock(
            PEATestUtils.IRBlock earlierBlock, String earlier,
            PEATestUtils.IRBlock laterBlock, String later, String detail) {
        if (earlierBlock.label().equals(laterBlock.label())) {
            earlierBlock.assertBefore(earlier, 0, later, 0);
        }
    }

    private static void assertNestedConditionalTargets(
            PEATestUtils.IRBody body, PEATestUtils.IRBlock bounds,
            NestedStoreShape shape, PEATestUtils.IRBlock fallback,
            boolean exactStoresOnly) {
        List<String> targets = bounds.conditionalBranchTargets();
        Asserts.assertNotEquals(targets.get(0), targets.get(1),
                body.methodId() + ": nested bounds targets are distinct");
        bounds.assertAbsent(DEOPTIMIZE);
        bounds.assertAbsent(LOWERED_DEOPTIMIZE);
        fallback.assertAbsent("store atomic");
        fallback.assertAbsent("load atomic");
        assertNestedSuccessPath(
                body, targets.get(0), shape, fallback, exactStoresOnly);
        assertForwardingEdgeReaches(body, targets.get(1), fallback,
                "nested bounds false edge reaches the exact fallback");
    }

    private static void assertNestedSuccessPath(
            PEATestUtils.IRBody body, String start, NestedStoreShape shape,
            PEATestUtils.IRBlock fallback, boolean exactStoresOnly) {
        record State(String label, int milestone) {}
        List<PEATestUtils.IRBlock> milestones = List.of(
                shape.childReplayBlock(), shape.arrayReplayBlock(), shape.consumer());
        Set<String> allowedStores = Set.of(
                shape.childReplay(), shape.arrayReplay(), shape.symbolicStore());

        class AllPaths {
            private final HashSet<State> visiting = new HashSet<>();
            private final HashSet<State> verified = new HashSet<>();
            private boolean completedTermination;

            private void verify(State state) {
                if (verified.contains(state)) {
                    return;
                }
                if (!visiting.add(state)) {
                    if (state.milestone() < milestones.size()) {
                        throw new AssertionError(body.methodId()
                                + ": nested bounds-success path cycles before "
                                + "all replay/store milestones");
                    }
                    return;
                }

                PEATestUtils.IRBlock block = body.blockByLabel(state.label());
                if (block.label().equals(fallback.label())) {
                    throw new AssertionError(body.methodId()
                            + ": nested bounds-success path reaches "
                            + "the exact bounds fallback");
                }
                if (block.lines().stream().anyMatch(
                        line -> line.contains(DEOPTIMIZE)
                                || line.contains(LOWERED_DEOPTIMIZE))) {
                    throw new AssertionError(body.methodId()
                            + ": nested bounds-success path reaches deopt in block "
                            + block.label());
                }
                if (exactStoresOnly && block.lines().stream()
                        .filter(line -> line.startsWith("store "))
                        .anyMatch(line -> !allowedStores.contains(line))) {
                    throw new AssertionError(body.methodId()
                            + ": nested bounds-success path contains an "
                            + "unexpected store in block " + block.label());
                }

                int milestone = state.milestone();
                for (int i = 0; i < milestones.size(); i++) {
                    if (!block.label().equals(milestones.get(i).label())) {
                        continue;
                    }
                    if (i != milestone) {
                        throw new AssertionError(body.methodId()
                                + ": nested replay/store milestone " + i
                                + " is reached out of order in block "
                                + block.label());
                    }
                    milestone++;
                }

                List<String> successors = branchTargets(body, block);
                if (successors.isEmpty()) {
                    if (milestone != milestones.size()) {
                        throw new AssertionError(body.methodId()
                                + ": nested bounds-success path terminates before "
                                + "all replay/store milestones");
                    }
                    completedTermination = true;
                } else {
                    for (String successor : successors) {
                        verify(new State(successor, milestone));
                    }
                }
                visiting.remove(state);
                verified.add(state);
            }
        }

        AllPaths paths = new AllPaths();
        paths.verify(new State(start, 0));
        Asserts.assertTrue(paths.completedTermination,
                body.methodId() + ": nested bounds-success CFG has "
                        + "a completed normal termination");
    }

    private static List<String> branchTargets(
            PEATestUtils.IRBody body, PEATestUtils.IRBlock block) {
        try {
            return block.conditionalBranchTargets();
        } catch (IllegalStateException notConditional) {
            try {
                return List.of(block.unconditionalBranchTarget());
            } catch (IllegalStateException notUnconditional) {
                String terminator = lastSemanticInstruction(block);
                if (terminator.startsWith("ret ")) {
                    return List.of();
                }
                // This jtreg oracle deliberately admits only the current
                // br-based shape instead of silently approximating a partial
                // parser for invoke, switch, and the other LLVM terminators.
                throw new AssertionError(body.methodId() + ": unsupported or malformed "
                        + "terminator in block " + block.label() + ": " + terminator);
            }
        }
    }

    private static String lastSemanticInstruction(PEATestUtils.IRBlock block) {
        List<String> lines = block.lines();
        for (int i = lines.size() - 1; i > 0; i--) {
            String line = stripLLVMComment(lines.get(i)).strip();
            if (!line.isEmpty() && !line.startsWith("#dbg_")) {
                return line;
            }
        }
        throw new AssertionError("Block " + block.label()
                + " contains no terminator");
    }

    private static String stripLLVMComment(String line) {
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (quoted) {
                if (ch == '\\') {
                    i++;
                } else if (ch == '"') {
                    quoted = false;
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ';') {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static PEATestUtils.PEAReport assertOriginalMaterialization(
            PEATestUtils.RunResult run, Method target, int allocationCount) throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.PEARound first = report.round(0);
        PEATestUtils.IRBody before = first.before();
        PEATestUtils.IRBody after = report.finalAfter();

        Asserts.assertEquals(before.allocations().size(), allocationCount,
                target + ": source allocation count");
        Asserts.assertEquals(first.partiallyEscapes(), allocationCount,
                target + ": symbolic use makes each reachable allocation partial");
        Asserts.assertEquals(first.effectCount("Materialize"), (long) allocationCount,
                target + ": one use-point materialization per reachable allocation");
        List<PEATestUtils.AllocationKey> keys =
                before.allocations().stream().map(PEATestUtils.AllocationSite::key).toList();
        after.assertRetainsExactlyOriginalAllocations(
                before, keys.toArray(PEATestUtils.AllocationKey[]::new));
        Asserts.assertEquals(run.finalIR(target).loweredAllocCount(), allocationCount,
                target + ": lowering retains only the original allocations");
        return report;
    }

    private static PEATestUtils.DeoptBundle assertBoundsFallback(
            PEATestUtils.IRBody after, int compareOccurrence, int expectedBCI,
            PEATestUtils.IRBlock success) {
        PEATestUtils.IRBlock bounds =
                after.blockContaining(BOUNDS_COMPARE, compareOccurrence);
        bounds.assertOccurrenceCount(BOUNDS_COMPARE, 1);
        bounds.assertOccurrenceCount("br i1", 1);
        bounds.assertAbsent(DEOPTIMIZE);
        if (compareOccurrence == 0) {
            bounds.assertAbsent("store atomic");
            bounds.assertAbsent("load atomic");
        }

        List<Integer> deopts = after.callOccurrencesAtBCI(
                DEOPTIMIZE_I64, expectedBCI);
        Asserts.assertEquals(deopts.size(), 1,
                after.methodId() + ": exact bounds fallback BCI " + expectedBCI);
        int occurrence = deopts.get(0);
        PEATestUtils.IRBlock fallback =
                after.blockContaining(DEOPTIMIZE_I64_CALL, occurrence);
        fallback.assertOccurrenceCount(DEOPTIMIZE_I64_CALL, 1);
        fallback.assertOccurrenceCount("ret i64", 1);
        fallback.assertAbsent("store atomic");
        fallback.assertAbsent("load atomic");
        assertConditionalTargets(after, bounds, success, fallback);
        after.assertBefore(
                BOUNDS_COMPARE, compareOccurrence, DEOPTIMIZE_I64_CALL, occurrence);
        if (compareOccurrence == 0) {
            after.assertBefore(BOUNDS_COMPARE, 0, "store atomic", 0);
        }
        return after.deoptBundleAtCall(DEOPTIMIZE_I64, occurrence);
    }

    private static void assertFinalBoundsFallback(
            PEATestUtils.IRBody finalIR, List<PEATestUtils.IRBlock> successes) {
        int expectedCount = successes.size();
        finalIR.assertOccurrenceCount(BOUNDS_COMPARE, expectedCount);
        finalIR.assertOccurrenceCount(LOWERED_DEOPTIMIZE, expectedCount);
        for (int occurrence = 0; occurrence < expectedCount; occurrence++) {
            PEATestUtils.IRBlock bounds =
                    finalIR.blockContaining(BOUNDS_COMPARE, occurrence);
            bounds.assertOccurrenceCount(BOUNDS_COMPARE, 1);
            bounds.assertOccurrenceCount("br i1", 1);
            bounds.assertAbsent(LOWERED_DEOPTIMIZE);

            PEATestUtils.IRBlock fallback =
                    finalIR.blockContaining(LOWERED_DEOPTIMIZE, occurrence);
            fallback.assertOccurrenceCount(LOWERED_DEOPTIMIZE, 1);
            fallback.assertAbsent("store atomic");
            fallback.assertAbsent("load atomic");
            assertConditionalTargets(
                    finalIR, bounds, successes.get(occurrence), fallback);
            finalIR.assertBefore(
                    BOUNDS_COMPARE, occurrence, LOWERED_DEOPTIMIZE, occurrence);
        }
    }

    private static void assertConditionalTargets(
            PEATestUtils.IRBody body, PEATestUtils.IRBlock bounds,
            PEATestUtils.IRBlock success, PEATestUtils.IRBlock fallback) {
        List<String> targets = bounds.conditionalBranchTargets();
        Asserts.assertNotEquals(targets.get(0), targets.get(1),
                body.methodId() + ": success and fallback edges are distinct");
        assertForwardingEdgeReaches(body, targets.get(0), success,
                "true bounds edge reaches the success block");
        assertForwardingEdgeReaches(body, targets.get(1), fallback,
                "false bounds edge reaches the exact fallback");
    }

    private static void assertForwardingEdgeReaches(
            PEATestUtils.IRBody body, String start,
            PEATestUtils.IRBlock expected, String detail) {
        HashSet<String> visited = new HashSet<>();
        String label = start;
        while (visited.add(label)) {
            PEATestUtils.IRBlock block = body.blockByLabel(label);
            if (block.label().equals(expected.label())) {
                return;
            }
            label = block.emptyForwardingTarget();
        }
        throw new AssertionError(body.methodId() + ": cyclic forwarding edge: " + detail);
    }

    private static void assertIntArrayDescriptor(
            PEATestUtils.DeoptBundle bundle, Method target) {
        bundle.assertVirtualObjectIds(0);
        PEATestUtils.VirtualObjectDescriptor array = bundle.virtualObject(0);
        Asserts.assertEquals(array.kind(), PEATestUtils.DescriptorKind.ARRAY,
                target + ": bounds fallback carries the virtual int[]");
        Asserts.assertEquals(array.elements().values().stream()
                        .map(entry -> entry.value().operand()).toList(),
                List.of("i32 11", "i32 22", "i32 33", "i32 44"),
                target + ": bounds fallback preserves exact pre-access elements");
    }

    private static void assertNormalPathHasNoDeopt(
            PEATestUtils.RunResult run, Method target,
            PEATestUtils.IRBlock afterConsumer,
            String finalConsumerNeedle, int finalConsumerOccurrence) throws Exception {
        afterConsumer.assertAbsent(DEOPTIMIZE);
        run.finalIR(target)
                .blockContaining(finalConsumerNeedle, finalConsumerOccurrence)
                .assertAbsent(LOWERED_DEOPTIMIZE);
    }

    private static void assertNoAllocationAtConsumer(PEATestUtils.IRBlock consumer) {
        consumer.assertAbsent("@jeandle.new_array");
        consumer.assertAbsent("@jeandle.new_instance");
    }

    public static class TestWrapper {
        private static final int[] INITIAL = {11, 22, 33, 44};

        public static class Node {
            int value;
        }

        public static void main(String[] args) throws Exception {
            new Node();
            PEATestUtils.compileConfiguredTargetsAtLevel4();

            long digest = 0xA4093822299F31D0L;
            for (int index : new int[] {0, 2, 3, -1, 4}) {
                int value = 1000 + index;
                long store = testSymbolicStore(index, value);
                Asserts.assertEquals(store, expectedStore(index, value),
                        "symbolic store index " + index);
                digest = mix(digest, store);

                long load = testSymbolicLoad(index);
                Asserts.assertEquals(load, expectedLoad(index),
                        "symbolic load index " + index);
                digest = mix(digest, load);

                long loadThenStore = testLoadThenStore(index, value);
                Asserts.assertEquals(loadThenStore,
                        expectedTwoIndexes(index, index, value, 0x31),
                        "load-then-store index " + index);
                digest = mix(digest, loadThenStore);

                long nested = testNestedSymbolicStore(index, value);
                Asserts.assertEquals(nested, expectedNested(index, value),
                        "nested symbolic store index " + index);
                digest = mix(digest, nested);
            }

            int[][] pairs = {
                    {0, 0}, {2, 2}, {3, 3},
                    {0, 2}, {2, 3}, {3, 0},
                    {-1, 2}, {2, -1}, {4, 2}, {2, 4}
            };
            for (int[] pair : pairs) {
                int value = 2000 + pair[0] * 17 + pair[1];
                long actual = testTwoIndexes(pair[0], pair[1], value);
                long expected = expectedTwoIndexes(pair[0], pair[1], value, 0x53);
                Asserts.assertEquals(actual, expected,
                        "two symbolic indexes " + pair[0] + "," + pair[1]);
                digest = mix(digest, actual);
            }
            System.out.println("PEA-RESULT:" + Long.toUnsignedString(digest, 16));
        }

        public static long testSymbolicStore(int index, int value) {
            int[] array = new int[4];
            array[0] = 11;
            array[1] = 22;
            array[2] = 33;
            array[3] = 44;
            try {
                array[index] = value;
                return checksumValues(array[0], array[1], array[2], array[3], 0x17);
            } catch (ArrayIndexOutOfBoundsException expected) {
                return checksumValues(array[0], array[1], array[2], array[3], 0x71);
            }
        }

        public static long testSymbolicLoad(int index) {
            int[] array = new int[4];
            array[0] = 11;
            array[1] = 22;
            array[2] = 33;
            array[3] = 44;
            try {
                return ((long) array[index] << 32)
                        ^ checksumValues(array[0], array[1], array[2], array[3], 0x29);
            } catch (ArrayIndexOutOfBoundsException expected) {
                return checksumValues(array[0], array[1], array[2], array[3], 0x92);
            }
        }

        public static long testLoadThenStore(int index, int value) {
            int[] array = new int[4];
            array[0] = 11;
            array[1] = 22;
            array[2] = 33;
            array[3] = 44;
            try {
                int old = array[index];
                array[index] = value;
                return ((long) old << 32)
                        ^ checksumValues(array[0], array[1], array[2], array[3], 0x31);
            } catch (ArrayIndexOutOfBoundsException expected) {
                return checksumValues(array[0], array[1], array[2], array[3], 0x13);
            }
        }

        public static long testTwoIndexes(int first, int second, int value) {
            int[] array = new int[4];
            array[0] = 11;
            array[1] = 22;
            array[2] = 33;
            array[3] = 44;
            try {
                int old = array[first];
                array[second] = value;
                return ((long) old << 32)
                        ^ checksumValues(array[0], array[1], array[2], array[3], 0x53);
            } catch (ArrayIndexOutOfBoundsException expected) {
                return checksumValues(array[0], array[1], array[2], array[3], 0x35);
            }
        }

        public static long testNestedSymbolicStore(int index, int value) {
            Node[] array = new Node[4];
            Node child = new Node();
            child.value = value;
            array[0] = child;
            try {
                array[index] = child;
                int identity = array[index] == child ? 1 : 0;
                identity |= array[0] == child ? 2 : 0;
                return ((long) child.value << 32) ^ ((long) array.length << 16)
                        ^ identity ^ 0x65;
            } catch (ArrayIndexOutOfBoundsException expected) {
                int unchanged = array[0] == child ? 1 : 0;
                unchanged |= array[1] == null ? 2 : 0;
                unchanged |= array[2] == null ? 4 : 0;
                unchanged |= array[3] == null ? 8 : 0;
                return ((long) child.value << 32) ^ ((long) array.length << 16)
                        ^ unchanged ^ 0x56;
            }
        }

        private static long expectedStore(int index, int value) {
            int[] expected = INITIAL.clone();
            if (valid(index)) {
                expected[index] = value;
                return checksum(expected, 0x17);
            }
            return checksum(expected, 0x71);
        }

        private static long expectedLoad(int index) {
            int[] expected = INITIAL.clone();
            if (valid(index)) {
                return ((long) expected[index] << 32) ^ checksum(expected, 0x29);
            }
            return checksum(expected, 0x92);
        }

        private static long expectedTwoIndexes(int first, int second, int value,
                                               int successMarker) {
            int[] expected = INITIAL.clone();
            if (!valid(first) || !valid(second)) {
                return checksum(expected, successMarker == 0x31 ? 0x13 : 0x35);
            }
            int old = expected[first];
            expected[second] = value;
            return ((long) old << 32) ^ checksum(expected, successMarker);
        }

        private static long expectedNested(int index, int value) {
            if (valid(index)) {
                return ((long) value << 32) ^ (4L << 16) ^ 3 ^ 0x65;
            }
            return ((long) value << 32) ^ (4L << 16) ^ 15 ^ 0x56;
        }

        private static boolean valid(int index) {
            return index >= 0 && index < INITIAL.length;
        }

        private static long checksum(int[] array, int marker) {
            return checksumValues(array[0], array[1], array[2], array[3], marker);
        }

        private static long checksumValues(int first, int second, int third, int fourth,
                                           int marker) {
            long value = marker;
            value = value * 257 + first;
            value = value * 257 + second;
            value = value * 257 + third;
            return value * 257 + fourth;
        }

        private static long mix(long accumulator, long value) {
            return Long.rotateLeft(accumulator ^ value, 17)
                    * 0x9E3779B97F4A7C15L;
        }
    }
}
