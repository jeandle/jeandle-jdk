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
 * @summary PEA predecessor merges preserve complete state across mixed,
 *          nested, critical, incompatible, and exceptional control flow
 * @library /test/lib /
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.pea.TestPEAComplexPredecessorMerge
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jdk.test.lib.Asserts;

public class TestPEAComplexPredecessorMerge {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestPEAComplexPredecessorMerge$TestWrapper";
    private static final String CASE_C_FIELD_PHI = "pea.casec.field.phi";
    private static final String LLVM_NAME =
            "(?:[-A-Za-z$._0-9]+|\"(?:[^\"\\\\]|\\\\.)*\")";
    private static final Pattern BLOCK_LABEL = Pattern.compile(
            "^(" + LLVM_NAME + "):(?:\\s*;.*)?$");
    private static final Pattern BLOCK_WITH_PREDECESSORS = Pattern.compile(
            "^(" + LLVM_NAME + "):\\s*; preds = (.+)$");
    private static final Pattern CONDITIONAL_BRANCH = Pattern.compile(
            "^br i1 .* label %(" + LLVM_NAME + "), label %(" + LLVM_NAME + ").*$");
    private static final Pattern LLVM_BLOCK_REFERENCE = Pattern.compile(
            "%(" + LLVM_NAME + ")");
    private static final Pattern PHI_INCOMING_BLOCK = Pattern.compile(
            ",\\s*%(" + LLVM_NAME + ")\\s*\\]");

    public static void main(String[] args) throws Exception {
        assertPhiParserContracts();

        Method mixed = TestWrapper.class.getMethod("virtualMaterializedMix",
                boolean.class, int.class);
        Method critical = TestWrapper.class.getMethod("criticalEdgeMerge",
                boolean.class, boolean.class);
        Method nested = TestWrapper.class.getMethod("nestedChildEscape",
                boolean.class, int.class);
        Method incompatible = TestWrapper.class.getMethod("incompatibleConcreteClass",
                boolean.class, int.class);
        Method exceptional = TestWrapper.class.getMethod("normalThrowingPredecessor",
                boolean.class, int.class);
        Method consume = TestWrapper.class.getMethod("consume", TestWrapper.Node.class);
        Method mutateOrThrow = TestWrapper.class.getMethod("mutateOrThrow",
                TestWrapper.Node.class, boolean.class, int.class);
        Method[] targets = {mixed, critical, nested, incompatible, exceptional};

        behaviorBuilder(targets, consume, mutateOrThrow).runPEAOnOffEquivalent();

        try (PEATestUtils.RunResult run =
                shapeBuilder(targets, consume, mutateOrThrow).run()) {
            assertVirtualMaterializedMix(run, mixed, consume);
            assertCriticalEdge(run, critical, consume);
            assertNestedChild(run, nested, consume);
            assertIncompatibleClass(run, incompatible);
            assertNormalThrowing(run, exceptional, mutateOrThrow);
        }
    }

    private static PEATestUtils.RunBuilder behaviorBuilder(
            Method[] targets, Method consume, Method mutateOrThrow) {
        return PEATestUtils.behaviorRun(WRAPPER, targets)
                .dontinline(consume)
                .dontinline(mutateOrThrow);
    }

    private static PEATestUtils.RunBuilder shapeBuilder(
            Method[] targets, Method consume, Method mutateOrThrow) {
        return PEATestUtils.shapeRun(WRAPPER, targets)
                .dontinline(consume)
                .dontinline(mutateOrThrow);
    }

    private static void assertVirtualMaterializedMix(PEATestUtils.RunResult run,
                                                      Method target, Method consume)
            throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();
        assertDistinctAllocations(before, 1, target);
        assertMaterializationsPerRound(report, 2, target);
        Asserts.assertEquals(after.allocationBCIs(), before.allocationBCIs(),
                target + ": retained source allocation identity");
        after.assertLineCount("store atomic i32", 2);
        after.assertPresent("load atomic i32");
        assertReplayBeforeCall(after, consume, "store atomic i32", target);
        assertVerifierShape(run, report, target);
    }

    private static void assertCriticalEdge(PEATestUtils.RunResult run, Method target,
                                           Method consume) throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();
        assertDistinctAllocations(before, 1, target);
        before.assertPresent("br i1");
        assertMaterializationsPerRound(report, 3, target);
        Asserts.assertEquals(after.allocationBCIs(), before.allocationBCIs(),
                target + ": critical-edge merge retains the source allocation");
        assertStoreBlockHasCriticalEdge(before, "store atomic i32 41", target);
        after.assertLineCount("store atomic i32", 3);
        assertReplayBeforeCall(after, consume, "store atomic i32", target);
        assertVerifierShape(run, report, target);
    }

    private static void assertNestedChild(PEATestUtils.RunResult run, Method target,
                                          Method consume) throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();
        assertDistinctAllocations(before, 2, target);
        Asserts.assertEquals(report.round(0).effectCount("Materialize", "[VO=1]"), 2L,
                target + ": child materializes once on each predecessor");
        Asserts.assertEquals(report.round(0).effectCount("Materialize", "[VO=0]"), 0L,
                target + ": outer remains virtual");
        assertMaterializationsPerRound(report, 2, target);
        Asserts.assertEquals(after.allocationBCIs(), List.of(before.allocationBCIs().get(1)),
                target + ": only the child's source allocation remains");
        after.assertLineCount("store atomic i32", 2);
        assertReplayBeforeCall(after, consume, "store atomic i32", target);
        assertVerifierShape(run, report, target);
    }

    private static void assertIncompatibleClass(PEATestUtils.RunResult run,
                                                Method target) throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();
        assertDistinctAllocations(before, 2, target);
        assertMaterializationsPerRound(report, 2, target);
        Asserts.assertEquals(report.round(0).effectCount("CreatePHI"), 0L,
                target + ": incompatible classes do not synthesize Case C state");
        Asserts.assertEquals(after.allocationBCIs(), before.allocationBCIs(),
                target + ": both source identities are retained");
        after.assertAbsent(CASE_C_FIELD_PHI);
        after.assertLineCount("store atomic i32", 3);
        assertVerifierShape(run, report, target);
    }

    private static void assertNormalThrowing(PEATestUtils.RunResult run, Method target,
                                             Method callee) throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();
        assertDistinctAllocations(before, 1, target);
        report.round(0).uniqueEffect("Materialize", "[VO=0]");
        assertMaterializationsPerRound(report, 1, target);
        Asserts.assertEquals(after.allocationBCIs(), before.allocationBCIs(),
                target + ": invoke argument keeps its source allocation");
        after.assertLineCount("store atomic i32", 2);
        after.assertPresent("landingpad");
        after.assertPresent("load atomic i32");
        before.assertPresent("to label ");
        before.assertPresent(" unwind label ");
        before.assertPresent("storemerge = phi i32");
        assertReplayBeforeCall(after, callee, "store atomic i32", target);
        assertVerifierShape(run, report, target);
    }

    private static void assertReplayBeforeCall(PEATestUtils.IRBody body, Method callee,
                                               String replay, Method target) {
        String calleeName = PEATestUtils.MethodId.of(callee).llvmFunctionName();
        PEATestUtils.IRBlock callBlock = body.blockContaining(calleeName, 0);
        Asserts.assertTrue(callBlock.occurrenceCount(replay) >= 1,
                target + ": missing replay before opaque call");
        callBlock.assertBefore(replay, 0, calleeName, 0);
    }

    private static void assertMaterializationsPerRound(PEATestUtils.PEAReport report,
                                                       int expected, Method target) {
        for (PEATestUtils.PEARound round : report.rounds()) {
            Asserts.assertEquals(round.effectCount("Materialize"), (long) expected,
                    target + ": exact live-predecessor materializations in round "
                            + round.iteration());
        }
    }

    private static void assertStoreBlockHasCriticalEdge(PEATestUtils.IRBody body,
                                                        String store, Method target) {
        Map<String, Integer> predecessorCounts = new HashMap<>();
        String currentBlock = null;
        String storeBlock = null;
        String[] storeBlockSuccessors = null;
        for (String line : body.lines()) {
            Matcher block = BLOCK_LABEL.matcher(line);
            if (block.matches()) {
                currentBlock = block.group(1);
                Matcher withPredecessors = BLOCK_WITH_PREDECESSORS.matcher(line);
                if (withPredecessors.matches()) {
                    predecessorCounts.put(currentBlock,
                            withPredecessors.group(2).split(",\\s*").length);
                }
                continue;
            }
            if (line.contains(store)) {
                storeBlock = currentBlock;
            }
            Matcher branch = CONDITIONAL_BRANCH.matcher(line);
            if (branch.matches() && currentBlock != null && currentBlock.equals(storeBlock)) {
                storeBlockSuccessors = new String[] {branch.group(1), branch.group(2)};
            }
        }
        Asserts.assertNotNull(storeBlock, target + ": source store block not found");
        Asserts.assertNotNull(storeBlockSuccessors,
                target + ": source store block lacks a conditional branch");
        boolean critical = false;
        for (String successor : storeBlockSuccessors) {
            if (predecessorCounts.getOrDefault(successor, 0) > 1) {
                critical = true;
                break;
            }
        }
        Asserts.assertTrue(critical,
                target + ": source block must have a direct edge to a multi-predecessor merge");
    }

    private static void assertVerifierShape(PEATestUtils.RunResult run,
                                            PEATestUtils.PEAReport report,
                                            Method target) throws Exception {
        for (PEATestUtils.PEARound round : report.rounds()) {
            round.after().assertAbsent("poison");
            assertCompletePhis(round.after(), target);
        }
        PEATestUtils.IRBody finalIR = run.finalIR(target);
        finalIR.assertAbsent("poison");
        assertCompletePhis(finalIR, target);
    }

    private static void assertCompletePhis(PEATestUtils.IRBody body, Method target) {
        validateCompletePhis(body.lines(), target.toString());
    }

    private static void validateCompletePhis(List<String> lines, String context) {
        Map<String, Integer> currentPredecessors = null;
        String currentBlock = null;
        for (String line : lines) {
            Matcher anyBlock = BLOCK_LABEL.matcher(line);
            if (anyBlock.matches()) {
                currentBlock = anyBlock.group(1);
                currentPredecessors = null;
            }
            Matcher block = BLOCK_WITH_PREDECESSORS.matcher(line);
            if (block.matches()) {
                currentPredecessors = blockReferences(block.group(2));
                continue;
            }
            if (!line.contains(" = phi ")) {
                continue;
            }
            if (currentPredecessors == null) {
                throw new IllegalStateException(context
                        + ": PHI outside a block with printed predecessors: " + line);
            }
            Map<String, Integer> incomingBlocks = new HashMap<>();
            Matcher incoming = PHI_INCOMING_BLOCK.matcher(line);
            while (incoming.find()) {
                incomingBlocks.merge(incoming.group(1), 1, Integer::sum);
            }
            if (!incomingBlocks.equals(currentPredecessors)) {
                throw new IllegalStateException(context + ": PHI in block " + currentBlock
                        + " has incoming predecessors " + incomingBlocks
                        + ", expected " + currentPredecessors + ": " + line);
            }
        }
    }

    private static Map<String, Integer> blockReferences(String text) {
        Map<String, Integer> result = new HashMap<>();
        Matcher reference = LLVM_BLOCK_REFERENCE.matcher(text);
        while (reference.find()) {
            result.merge(reference.group(1), 1, Integer::sum);
        }
        if (result.isEmpty()) {
            throw new IllegalStateException("Printed predecessor list has no block reference: "
                    + text);
        }
        return result;
    }

    private static void assertPhiParserContracts() {
        List<String> complete = List.of(
                "merge: ; preds = %left, %left, %\"right path\"",
                "%value = phi i32 [ 1, %left ], [ 2, %\"right path\" ], [ 3, %left ]");
        validateCompletePhis(complete, "complete synthetic PHI");

        List<String> duplicateOneMissingOne = List.of(
                "merge: ; preds = %left, %left, %\"right path\"",
                "%value = phi i32 [ 1, %left ], [ 2, %left ], [ 3, %left ]");
        boolean rejected = false;
        try {
            validateCompletePhis(duplicateOneMissingOne,
                    "duplicate-one-missing-one synthetic PHI");
        } catch (IllegalStateException expected) {
            rejected = true;
        }
        Asserts.assertTrue(rejected,
                "PHI parser must reject equal-size predecessor multisets with a missing block");

        List<String> missingPrintedPredecessors = List.of(
                "with_preds: ; preds = %left, %right",
                "%good = phi i32 [ 1, %left ], [ 2, %right ]",
                "plain:",
                "%stale = phi i32 [ 1, %left ], [ 2, %right ]");
        rejected = false;
        try {
            validateCompletePhis(missingPrintedPredecessors,
                    "new-block predecessor reset synthetic PHI");
        } catch (IllegalStateException expected) {
            rejected = true;
        }
        Asserts.assertTrue(rejected,
                "PHI parser must reset predecessor information at every block label");
    }

    private static void assertDistinctAllocations(PEATestUtils.IRBody body,
                                                  int expected, Method target) {
        List<Integer> bcis = body.allocationBCIs();
        Asserts.assertEquals(bcis.size(), expected, target + ": source allocation count");
        Set<Integer> distinct = new HashSet<>(bcis);
        Asserts.assertEquals(distinct.size(), expected,
                target + ": every source allocation has a distinct BCI");
    }

    public static class TestWrapper {
        private static final String EXPECTED_DIGEST = "adf595eefb3b6ee8";

        public static class Node {
            int x;
        }

        public static class Outer {
            int tag;
            Node child;
        }

        public static class BaseNode {
            int x;
            int y;
        }

        public static class LeftNode extends BaseNode {}

        public static class RightNode extends BaseNode {}

        public static class TestException extends RuntimeException {
            private static final long serialVersionUID = 1L;
        }

        public static void main(String[] args) throws Exception {
            new Node();
            new Outer();
            new LeftNode();
            new RightNode();
            PEATestUtils.compileConfiguredTargetsAtLevel4();

            long digest = 0x243F6A8885A308D3L;
            for (boolean flag : new boolean[] {false, true}) {
                for (int seed : new int[] {0, 7}) {
                    int value = virtualMaterializedMix(flag, seed);
                    Asserts.assertEquals(value, flag
                            ? (seed + 11) * 32 : (seed + 11) * 31 - 3);
                    digest = mix(digest, value);
                }
            }
            for (boolean direct : new boolean[] {false, true}) {
                for (boolean escape : new boolean[] {false, true}) {
                    int value = criticalEdgeMerge(direct, escape);
                    int expected = direct ? 1276 : (escape ? 1376 : 1457);
                    Asserts.assertEquals(value, expected);
                    digest = mix(digest, value);
                }
            }
            for (boolean flag : new boolean[] {false, true}) {
                for (int seed : new int[] {0, 5}) {
                    int value = nestedChildEscape(flag, seed);
                    Asserts.assertEquals(value, (seed + 17) * 31 + 3
                            + (flag ? seed + 17 : 0));
                    digest = mix(digest, value);
                }
            }
            for (boolean left : new boolean[] {false, true}) {
                for (int seed : new int[] {0, 4}) {
                    int value = incompatibleConcreteClass(left, seed);
                    int expected = left ? (seed + 19) * 31 + 30
                            : (seed + 23) * 31 + 31;
                    Asserts.assertEquals(value, expected);
                    digest = mix(digest, value);
                }
            }
            for (boolean doThrow : new boolean[] {false, true}) {
                for (int seed : new int[] {0, 6}) {
                    int value = normalThrowingPredecessor(doThrow, seed);
                    Asserts.assertEquals(value, seed + (doThrow ? 76 : 74));
                    digest = mix(digest, value);
                }
            }
            String payload = Long.toUnsignedString(digest, 16);
            Asserts.assertEquals(payload, EXPECTED_DIGEST,
                    "hard-coded complex predecessor behavior digest");
            System.out.println("PEA-RESULT:" + payload);
        }

        public static int virtualMaterializedMix(boolean escape, int seed) {
            Node value = new Node();
            value.x = seed + 11;
            int observed = escape ? consume(value) : -3;
            return value.x * 31 + observed;
        }

        public static int criticalEdgeMerge(boolean direct, boolean escape) {
            Node value = new Node();
            value.x = 41;
            int observed;
            if (!direct) {
                if (escape) {
                    value.x = 43;
                    observed = consume(value);
                } else {
                    value.x = 47;
                    observed = 0;
                }
            } else {
                observed = 5;
            }
            return value.x * 31 + observed;
        }

        public static int nestedChildEscape(boolean escape, int seed) {
            Outer outer = new Outer();
            Node child = new Node();
            outer.tag = 3;
            child.x = seed + 17;
            outer.child = child;
            int observed = escape ? consume(child) : 0;
            return outer.child.x * 31 + outer.tag + observed;
        }

        public static int incompatibleConcreteClass(boolean left, int seed) {
            BaseNode selected;
            if (left) {
                LeftNode value = new LeftNode();
                value.x = seed + 19;
                selected = value;
            } else {
                RightNode value = new RightNode();
                value.x = seed + 23;
                selected = value;
            }
            selected.y = 29;
            return selected.x * 31 + selected.y + (selected instanceof LeftNode ? 1 : 2);
        }

        public static int normalThrowingPredecessor(boolean doThrow, int seed) {
            Node value = new Node();
            value.x = seed + 31;
            try {
                mutateOrThrow(value, doThrow, seed);
                value.x += 3;
            } catch (TestException expected) {
                value.x += 5;
            }
            return value.x;
        }

        public static int consume(Node value) {
            return value.x;
        }

        public static void mutateOrThrow(Node value, boolean doThrow, int seed) {
            value.x = seed + 71;
            if (doThrow) {
                throw new TestException();
            }
        }

        private static long mix(long digest, int value) {
            return Long.rotateLeft(digest ^ Integer.toUnsignedLong(value), 17)
                    * 0x9E3779B97F4A7C15L;
        }
    }
}
