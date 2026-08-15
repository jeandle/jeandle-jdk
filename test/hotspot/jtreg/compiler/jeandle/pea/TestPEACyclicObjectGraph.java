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
 * @summary PEA preserves cyclic object-graph identity when scalar replacing,
 *          publishing, pruning overwritten children, and reconstructing a
 *          live six-object graph during same-activation deoptimization
 * @library /test/lib /
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      -XX:-UseCompressedOops -XX:-UseCompressedClassPointers
 *      compiler.jeandle.pea.TestPEACyclicObjectGraph
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jdk.test.lib.Asserts;
import jdk.test.whitebox.WhiteBox;

public class TestPEACyclicObjectGraph {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestPEACyclicObjectGraph$TestWrapper";
    private static final String JEANDLE_NEW_INSTANCE = "@jeandle.new_instance";

    public static void main(String[] args) throws Exception {
        Method self = TestWrapper.class.getMethod("testSelfCycleReadOnly");
        Method pair = TestWrapper.class.getMethod("testABCycleReadOnly");
        Method three = TestWrapper.class.getMethod("testThreeNodeCycleReadOnly");
        Method escape = TestWrapper.class.getMethod("testCycleEscapesFromRoot");
        Method overwritten = TestWrapper.class.getMethod("testDeadOverwrittenChild");
        Method deopt = TestWrapper.class.getMethod("testCycleDeoptReconstruction");
        Method helper = TestWrapper.class.getDeclaredMethod("requestDeopt");
        Method[] targets = {self, pair, three, escape, overwritten, deopt};

        try (PEATestUtils.RunResult run = PEATestUtils.shapeRun(WRAPPER, targets)
                .dontinline(helper).run()) {
            assertNeverEscapeCycle(run, self, 1);
            assertNeverEscapeCycle(run, pair, 2);
            assertNeverEscapeCycle(run, three, 3);
            assertEscapingCycle(run, escape);
            assertDeadOverwrittenChild(run, overwritten);
            assertDeoptCycle(run, deopt, helper);
        }

        assertBehaviorEquivalent(targets, helper);
    }

    private static void assertNeverEscapeCycle(PEATestUtils.RunResult run, Method target,
                                                int allocations) throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        assertRound0Stats(report, target, allocations, 0, 0);
        Asserts.assertEquals(report.round0Before().peaAllocCount(), allocations,
                target + ": exact source allocation count");
        Asserts.assertEquals(effectCount(report.round(0), "EliminateAllocation"), allocations,
                target + ": exact allocation-elimination effects");
        Asserts.assertEquals(report.finalAfter().peaAllocCount(), 0,
                target + ": no allocation after PEA");
        Asserts.assertEquals(run.finalIR(target).loweredAllocCount(), 0,
                target + ": no allocation in the lowered final dump");
    }

    private static void assertEscapingCycle(PEATestUtils.RunResult run, Method target)
            throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.PEARound first = report.round(0);
        assertRound0Stats(report, target, 0, 2, 0);
        Asserts.assertEquals(first.before().peaAllocCount(), 2,
                target + ": exact source allocations");
        Asserts.assertEquals(effectCount(first, "EliminateAllocation"), 2,
                target + ": both allocations analyzed");
        Asserts.assertEquals(effectCount(first, "Materialize"), 2,
                target + ": reachable A-B closure materialized once");

        PEATestUtils.IRBody source = first.before();
        source.assertLineCount("store atomic i32 101,", 1);
        source.assertLineCount("store atomic i32 202,", 1);
        Asserts.assertEquals(instanceUnorderedReferenceStoreCount(source), 2L,
                target + ": exact source cycle-edge stores");
        Asserts.assertEquals(effectTargetCount(first, "EliminateStore",
                "target=  store atomic i32 101,", " unordered,"), 1,
                target + ": source payload 101 eliminated once");
        Asserts.assertEquals(effectTargetCount(first, "EliminateStore",
                "target=  store atomic i32 202,", " unordered,"), 1,
                target + ": source payload 202 eliminated once");
        Asserts.assertEquals(effectTargetCount(first, "EliminateStore",
                "target=  store atomic ptr addrspace(1)", " unordered,"), 2,
                target + ": both source cycle-edge stores eliminated once");

        String sourcePublication = publishedSinkStore(source);
        String publicationBlock = containingBlock(source, sourcePublication);
        String materializeAtPublication = "block=%" + publicationBlock + " target=";
        Asserts.assertEquals(effectTargetCount(first, "Materialize",
                materializeAtPublication, JEANDLE_NEW_INSTANCE), 2,
                target + ": both source allocations materialize at publication");
        for (int sourceBCI : allocationBCIs(source, JEANDLE_NEW_INSTANCE)) {
            String allocationBCI = "\"deopt\"(i64 0, i32 " + sourceBCI
                    + ", i32 " + sourceBCI + ",";
            Asserts.assertEquals(effectTargetCount(first, "Materialize",
                    materializeAtPublication, JEANDLE_NEW_INSTANCE, allocationBCI), 1,
                    target + ": source allocation BCI " + sourceBCI
                            + " materialized exactly once");
        }

        PEATestUtils.IRBody frontend = run.frontendIR(target);
        PEATestUtils.IRBody after = report.finalAfter();
        PEATestUtils.IRBody lowered = run.finalIR(target);
        assertOrigAllocationsRetained(frontend, after, 2, target);
        assertLoweredAllocationCount(frontend, lowered, 2, target);

        after.assertLineCount("store atomic i32 101", 1);
        after.assertLineCount("store atomic i32 202", 1);
        Asserts.assertEquals(instanceUnorderedReferenceStoreCount(after), 2L,
                target + ": each final cycle edge replayed exactly once");
        String publication = publishedSinkStore(after);
        after.assertBefore("store atomic i32 101", 0, publication, 0);
        after.assertBefore("store atomic i32 202", 0, publication, 0);
        for (String replay : instanceUnorderedReferenceStoreLines(after)) {
            Asserts.assertTrue(position(after, replay) < position(after, publication),
                    target + ": reference replay must precede publication");
        }
        String firstReplay = firstReplayLine(after);
        after.assertAbsentBetween(firstReplay, 0, JEANDLE_NEW_INSTANCE, publication, 0);
    }

    private static void assertDeadOverwrittenChild(PEATestUtils.RunResult run, Method target)
            throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.PEARound first = report.round(0);
        assertRound0Stats(report, target, 1, 1, 0);
        Asserts.assertEquals(first.before().peaAllocCount(), 2,
                target + ": source parent and child allocations");
        Asserts.assertEquals(effectCount(first, "EliminateAllocation"), 2,
                target + ": both allocations analyzed");
        Asserts.assertEquals(effectCount(first, "Materialize"), 1,
                target + ": only the published parent materializes");

        PEATestUtils.IRBody frontend = run.frontendIR(target);
        PEATestUtils.IRBody after = report.finalAfter();
        PEATestUtils.IRBody lowered = run.finalIR(target);
        List<Integer> sourceBCIs = allocationBCIs(frontend, JEANDLE_NEW_INSTANCE);
        List<Integer> afterBCIs = allocationBCIs(after, JEANDLE_NEW_INSTANCE);
        Asserts.assertEquals(sourceBCIs.size(), 2, target + ": source allocation BCIs");
        Asserts.assertEquals(afterBCIs, List.of(sourceBCIs.get(0)),
                target + ": only source parent OrigAlloc retained");
        Asserts.assertEquals(lowered.loweredAllocCount(), 1,
                target + ": lowering preserves only the parent allocation");
        after.assertLineCount("store atomic i32 303", 1);
        after.assertAbsent("store atomic i32 404");
    }

    private static void assertDeoptCycle(PEATestUtils.RunResult run, Method target,
                                         Method helper) throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        assertRound0Stats(report, target, 6, 0, 0);
        Asserts.assertEquals(report.round0Before().peaAllocCount(), 6,
                target + ": six source allocations");
        Asserts.assertEquals(effectCount(report.round(0), "EliminateAllocation"), 6,
                target + ": six exact allocation-elimination effects");
        Asserts.assertEquals(report.finalAfter().peaAllocCount(), 0,
                target + ": no allocation after PEA");
        Asserts.assertEquals(run.finalIR(target).loweredAllocCount(), 0,
                target + ": no allocation in lowered final dump");

        String helperName = PEATestUtils.MethodId.of(helper).llvmFunctionName();
        PEATestUtils.DeoptBundle beforeBundle =
                exactDeoptBundleAtCall(report.round0Before(), helperName);
        PEATestUtils.DeoptBundle round0Bundle =
                exactDeoptBundleAtCall(report.round(0).after(), helperName);
        PEATestUtils.DeoptBundle finalBundle =
                exactDeoptBundleAtCall(report.finalAfter(), helperName);
        int sourceBCI = beforeBundle.rootScope().bci();
        Asserts.assertEquals(beforeBundle.rootScope().duplicateBCI(), sourceBCI,
                target + ": frontend helper call has duplicated BCI");
        Asserts.assertEquals(round0Bundle.rootScope().bci(), sourceBCI,
                target + ": descriptor rewrite preserves helper-call BCI");
        Asserts.assertEquals(round0Bundle.rootScope().duplicateBCI(), sourceBCI,
                target + ": descriptor rewrite preserves duplicate helper-call BCI");
        Asserts.assertEquals(finalBundle.rootScope().bci(), sourceBCI,
                target + ": final PEA helper-call BCI");
        Asserts.assertEquals(finalBundle.rootScope().duplicateBCI(), sourceBCI,
                target + ": final duplicate PEA helper-call BCI");
        Asserts.assertEquals(beforeBundle.virtualObjects().size(), 0,
                target + ": frontend call has no PEA descriptors");
        assertSixNodeCycleDescriptors(round0Bundle, target);
        assertSixNodeCycleDescriptors(finalBundle, target);
    }

    private static PEATestUtils.DeoptBundle exactDeoptBundleAtCall(
            PEATestUtils.IRBody body, String callee) {
        Asserts.assertEquals(body.occurrenceCount("@\"" + callee + "\"("), 1,
                body.methodId() + ": exact helper safepoint call");
        return body.deoptBundleAtCall(callee, 0);
    }

    private static void assertSixNodeCycleDescriptors(
            PEATestUtils.DeoptBundle bundle, Method target) {
        Asserts.assertEquals(bundle.virtualObjects().size(), 6,
                target + ": six virtual-object descriptors at helper safepoint");
        Set<Integer> ids = new HashSet<>();
        Map<Integer, Descriptor> byPayload = new HashMap<>();
        for (PEATestUtils.VirtualObjectDescriptor typed
                : bundle.virtualObjects().values()) {
            Asserts.assertEquals(typed.kind(), PEATestUtils.DescriptorKind.INSTANCE,
                    target + ": Node descriptor kind");
            Asserts.assertTrue(ids.add(typed.id()),
                    target + ": duplicate descriptor id " + typed.id());
            Asserts.assertEquals(typed.fields().size(), 2,
                    target + ": Node descriptor field count");
            Integer payload = null;
            Integer referenceId = null;
            for (PEATestUtils.VirtualObjectEntry field : typed.fields().values()) {
                if (field.basicType() == PEATestUtils.DeoptBasicType.INT
                        && field.value().kind()
                                == PEATestUtils.DeoptValueKind.SCALAR) {
                    String operand = field.value().operand();
                    Asserts.assertTrue(operand.startsWith("i32 "),
                            target + ": typed Node payload operand");
                    payload = Integer.parseInt(operand.substring("i32 ".length()));
                } else if (field.basicType() == PEATestUtils.DeoptBasicType.OBJECT
                        && field.value().kind()
                                == PEATestUtils.DeoptValueKind.VO_REF) {
                    referenceId = field.value().virtualObjectId();
                } else {
                    throw new AssertionError(target
                            + ": unexpected typed Node descriptor entry");
                }
            }
            Asserts.assertNotNull(payload,
                    target + ": descriptor " + typed.id() + " missing int payload");
            Asserts.assertNotNull(referenceId,
                    target + ": descriptor " + typed.id() + " missing VORef edge");
            Descriptor descriptor =
                    new Descriptor(typed.id(), payload, referenceId);
            Asserts.assertTrue(byPayload.put(descriptor.payload, descriptor) == null,
                    target + ": duplicate descriptor payload " + descriptor.payload);
        }
        Asserts.assertEquals(ids.size(), 6, target + ": six unique descriptor ids");

        assertDescriptorEdge(byPayload, 11, 11, target);
        assertDescriptorEdge(byPayload, 21, 22, target);
        assertDescriptorEdge(byPayload, 22, 21, target);
        assertDescriptorEdge(byPayload, 31, 32, target);
        assertDescriptorEdge(byPayload, 32, 33, target);
        assertDescriptorEdge(byPayload, 33, 31, target);
    }

    private static void assertDescriptorEdge(Map<Integer, Descriptor> byPayload,
                                             int fromPayload, int toPayload, Method target) {
        Descriptor from = byPayload.get(fromPayload);
        Descriptor to = byPayload.get(toPayload);
        Asserts.assertNotNull(from, target + ": missing payload descriptor " + fromPayload);
        Asserts.assertNotNull(to, target + ": missing payload descriptor " + toPayload);
        Asserts.assertEquals(from.referenceId, to.id,
                target + ": descriptor edge " + fromPayload + " -> " + toPayload);
    }

    private static void assertOrigAllocationsRetained(PEATestUtils.IRBody before,
                                                       PEATestUtils.IRBody after,
                                                       int expected, Method target) {
        List<Integer> sourceBCIs = allocationBCIs(before, JEANDLE_NEW_INSTANCE);
        List<Integer> finalBCIs = allocationBCIs(after, JEANDLE_NEW_INSTANCE);
        Asserts.assertEquals(sourceBCIs.size(), expected,
                target + ": source allocation BCI count");
        Asserts.assertEquals(finalBCIs, sourceBCIs,
                target + ": retained allocations are source OrigAllocs in source order");
    }

    private static void assertLoweredAllocationCount(PEATestUtils.IRBody before,
                                                      PEATestUtils.IRBody lowered,
                                                      int expected, Method target) {
        List<Integer> sourceBCIs = allocationBCIs(before, JEANDLE_NEW_INSTANCE);
        Asserts.assertEquals(sourceBCIs.size(), expected,
                target + ": source allocation BCI count");
        Asserts.assertEquals(lowered.loweredAllocCount(), expected,
                target + ": exact lowered allocation count");
    }

    private static List<Integer> allocationBCIs(PEATestUtils.IRBody body, String callee) {
        ArrayList<Integer> result = new ArrayList<>();
        int count = callee.equals(JEANDLE_NEW_INSTANCE)
                ? body.peaAllocCount() : body.loweredAllocCount();
        String exactCallee = callee.substring(1);
        for (int occurrence = 0; occurrence < count; occurrence++) {
            PEATestUtils.DeoptScope root =
                    body.deoptBundleAtCall(exactCallee, occurrence).rootScope();
            Asserts.assertEquals(root.duplicateBCI(), root.bci(),
                    body.methodId() + ": allocation carries duplicate source BCI");
            result.add(root.bci());
        }
        return List.copyOf(result);
    }

    private static long instanceUnorderedReferenceStoreCount(PEATestUtils.IRBody body) {
        return instanceUnorderedReferenceStoreLines(body).size();
    }

    private static List<String> instanceUnorderedReferenceStoreLines(
            PEATestUtils.IRBody body) {
        return body.lines().stream()
                .filter(line -> line.contains("store atomic ptr addrspace(1)"))
                .filter(line -> line.contains(" unordered,"))
                .toList();
    }

    private static String publishedSinkStore(PEATestUtils.IRBody body) {
        List<String> lines = body.lines().stream()
                .filter(line -> line.contains("store atomic ptr addrspace(1)"))
                .filter(line -> line.contains(" seq_cst,"))
                .toList();
        Asserts.assertEquals(lines.size(), 1,
                body.methodId() + ": one publication to sink");
        return lines.get(0);
    }

    private static String containingBlock(PEATestUtils.IRBody body, String line) {
        int linePosition = position(body, line);
        for (int i = linePosition - 1; i >= 0; i--) {
            String candidate = body.lines().get(i).trim();
            int colon = candidate.indexOf(':');
            if (colon > 0 && candidate.substring(0, colon).chars()
                    .noneMatch(Character::isWhitespace)) {
                return candidate.substring(0, colon);
            }
        }
        throw new AssertionError(body.methodId() + ": instruction has no containing block: "
                + line);
    }

    private static String firstReplayLine(PEATestUtils.IRBody body) {
        List<String> candidates = new ArrayList<>();
        candidates.addAll(body.lines().stream()
                .filter(line -> line.contains("store atomic i32 101")
                        || line.contains("store atomic i32 202"))
                .toList());
        candidates.addAll(instanceUnorderedReferenceStoreLines(body));
        Asserts.assertFalse(candidates.isEmpty(), body.methodId() + ": missing replay stores");
        return candidates.stream().min((left, right) ->
                Integer.compare(position(body, left), position(body, right))).orElseThrow();
    }

    private static int position(PEATestUtils.IRBody body, String line) {
        int position = body.lines().indexOf(line);
        Asserts.assertTrue(position >= 0, body.methodId() + ": line not in body: " + line);
        return position;
    }

    private static int effectCount(PEATestUtils.PEARound round, String kind) {
        return (int) round.effects().stream()
                .filter(effect -> effect.kind().equals(kind)).count();
    }

    private static int effectTargetCount(PEATestUtils.PEARound round, String kind,
                                         String... detailSubstrings) {
        return (int) round.effects().stream()
                .filter(effect -> effect.kind().equals(kind))
                .filter(effect -> containsAll(effect.detail(), detailSubstrings))
                .count();
    }

    private static boolean containsAll(String detail, String... substrings) {
        for (String substring : substrings) {
            if (!detail.contains(substring)) {
                return false;
            }
        }
        return true;
    }

    private static void assertRound0Stats(PEATestUtils.PEAReport report, Method target,
                                          int never, int partial, int always) {
        PEATestUtils.PEARound first = report.round(0);
        Asserts.assertTrue(first.hasStats(), target + ": missing round-0 PEA stats");
        Asserts.assertEquals(first.neverEscapes(), never, target + ": NeverEscapes");
        Asserts.assertEquals(first.partiallyEscapes(), partial, target + ": PartiallyEscapes");
        Asserts.assertEquals(first.alwaysEscapes(), always, target + ": AlwaysEscapes");
    }

    private static void assertBehaviorEquivalent(Method[] targets, Method helper)
            throws Exception {
        String onPayload;
        String offPayload;
        try (PEATestUtils.RunResult on = PEATestUtils.behaviorRun(WRAPPER, targets)
                .dontinline(helper).run()) {
            onPayload = exactResultPayload(on.output().getStdout());
        }
        try (PEATestUtils.RunResult off = PEATestUtils.behaviorRun(WRAPPER, targets)
                .dontinline(helper).peaOff().run()) {
            offPayload = exactResultPayload(off.output().getStdout());
        }
        Asserts.assertEquals(onPayload, offPayload, "PEA-on/off result payload mismatch");
    }

    private static String exactResultPayload(String stdout) {
        List<String> results = stdout.lines()
                .filter(line -> line.startsWith("PEA-RESULT:"))
                .toList();
        Asserts.assertEquals(results.size(), 1, "exactly one stable PEA result");
        return results.get(0).substring("PEA-RESULT:".length());
    }

    private static final class Descriptor {
        final int id;
        final int payload;
        final int referenceId;

        Descriptor(int id, int payload, int referenceId) {
            this.id = id;
            this.payload = payload;
            this.referenceId = referenceId;
        }
    }

    public static class TestWrapper {
        public static class Node {
            public Node left;
            public int x;
        }

        private static final WhiteBox WB = WhiteBox.getWhiteBox();
        private static final Method DEOPT_TARGET = deoptTarget();
        private static volatile Node sink;
        private static boolean deoptArmed;
        private static int deoptRequests;
        private static int markedNMethods;
        private static boolean frameDeoptimizedAtDepth2;

        public static void main(String[] args) throws Exception {
            new Node();
            sink = null;
            PEATestUtils.compileConfiguredTargetsAtLevel4();

            int self = testSelfCycleReadOnly();
            int pair = testABCycleReadOnly();
            int three = testThreeNodeCycleReadOnly();
            int escape = testCycleEscapesFromRoot();
            int overwritten = testDeadOverwrittenChild();
            Asserts.assertEquals(self, 544);
            Asserts.assertEquals(pair, 313);
            Asserts.assertEquals(three, 4141);
            Asserts.assertEquals(escape, 111222);
            Asserts.assertEquals(overwritten, 303);

            deoptArmed = true;
            deoptRequests = 0;
            markedNMethods = 0;
            frameDeoptimizedAtDepth2 = false;
            int deopt = testCycleDeoptReconstruction();
            Asserts.assertEquals(deopt, 1410);
            Asserts.assertEquals(deoptRequests, 1);
            Asserts.assertEquals(markedNMethods, 1);
            Asserts.assertTrue(frameDeoptimizedAtDepth2);
            Asserts.assertFalse(WB.isMethodCompiled(DEOPT_TARGET));

            long digest = 0x9E3779B97F4A7C15L;
            digest = mix(digest, self);
            digest = mix(digest, pair);
            digest = mix(digest, three);
            digest = mix(digest, escape);
            digest = mix(digest, overwritten);
            digest = mix(digest, deopt);
            digest = mix(digest, deoptRequests);
            digest = mix(digest, markedNMethods);
            digest = mix(digest, frameDeoptimizedAtDepth2 ? 1 : 0);
            System.out.println("PEA-RESULT:" + Long.toUnsignedString(digest, 16));
        }

        public static int testSelfCycleReadOnly() {
            Node n = new Node();
            n.x = 17;
            n.left = n;
            Node alias = n.left;
            if (alias != n || alias.left != n) {
                return -1;
            }
            return alias.x * 31 + alias.left.x;
        }

        public static int testABCycleReadOnly() {
            Node a = new Node();
            a.x = 23;
            Node b = new Node();
            b.x = 29;
            a.left = b;
            b.left = a;
            if (a == b || a.left != b || b.left != a || a.left.left != a
                    || b.left.left != b) {
                return -2;
            }
            return a.left.x * 10 + a.left.left.x;
        }

        public static int testThreeNodeCycleReadOnly() {
            Node a = new Node();
            a.x = 31;
            Node b = new Node();
            b.x = 37;
            Node c = new Node();
            c.x = 41;
            a.left = b;
            b.left = c;
            c.left = a;
            if (a == b || a == c || b == c || a.left != b || b.left != c
                    || c.left != a || a.left.left.left != a) {
                return -3;
            }
            return a.left.x * 100 + a.left.left.x * 10 + a.left.left.left.x;
        }

        public static int testCycleEscapesFromRoot() {
            Node a = new Node();
            a.x = 101;
            Node b = new Node();
            b.x = 202;
            a.left = b;
            b.left = a;
            sink = a;

            Node root = sink;
            if (root == null || root.left == root || root.left.left != root
                    || root.x != 101 || root.left.x != 202) {
                return -4;
            }
            root.x = 111;
            root.left.x = 222;
            if (sink != root || sink.left.left != sink) {
                return -5;
            }
            return sink.left.left.x * 1000 + sink.left.x;
        }

        public static int testDeadOverwrittenChild() {
            Node a = new Node();
            a.x = 303;
            Node b = new Node();
            b.x = 404;
            a.left = b;
            a.left = null;
            sink = a;

            Node root = sink;
            if (root == null || root.left != null || root.x != 303) {
                return -6;
            }
            return root.x;
        }

        public static int testCycleDeoptReconstruction() {
            Node self = new Node();
            self.x = 11;
            self.left = self;

            Node a = new Node();
            a.x = 21;
            Node b = new Node();
            b.x = 22;
            a.left = b;
            b.left = a;

            Node c = new Node();
            c.x = 31;
            Node d = new Node();
            d.x = 32;
            Node e = new Node();
            e.x = 33;
            c.left = d;
            d.left = e;
            e.left = c;

            requestDeopt();

            if (self.left != self || self.left.x != 11) {
                return -10;
            }
            if (a == b || a.left != b || b.left != a || a.left.left != a
                    || a.x != 21 || a.left.x != 22) {
                return -11;
            }
            if (c == d || c == e || d == e || c.left != d || d.left != e
                    || e.left != c || c.left.left.left != c
                    || c.x != 31 || c.left.x != 32 || c.left.left.x != 33) {
                return -12;
            }
            if (self == a || self == b || self == c || self == d || self == e
                    || a == c || a == d || a == e || b == c || b == d || b == e) {
                return -13;
            }

            self.x = 110;
            self.left = null;
            if (self.left != null) {
                return -20;
            }
            self.left = self;
            if (self.left != self) {
                return -21;
            }

            a.x = 210;
            b.x = 220;
            a.left = a;
            if (a.left != a || b.left != a) {
                return -22;
            }
            a.left = b;
            if (a.left.left != a) {
                return -23;
            }

            c.x = 280;
            d.x = 290;
            e.x = 300;
            c.left = e;
            if (c.left != e || e.left != c || d.left != e) {
                return -24;
            }
            c.left = d;
            if (c.left.left.left != c) {
                return -25;
            }

            return self.left.x + a.left.left.x + a.left.x
                    + c.left.x + c.left.left.x + c.left.left.left.x;
        }

        private static void requestDeopt() {
            Asserts.assertTrue(deoptArmed, "deopt helper re-entered");
            deoptArmed = false;
            deoptRequests++;
            markedNMethods = WB.deoptimizeMethod(DEOPT_TARGET);
            frameDeoptimizedAtDepth2 = WB.isFrameDeoptimized(2);
            Asserts.assertTrue(frameDeoptimizedAtDepth2,
                    "compiled target frame is not marked at WhiteBox depth 2");
        }

        private static Method deoptTarget() {
            try {
                return TestWrapper.class.getMethod("testCycleDeoptReconstruction");
            } catch (ReflectiveOperationException failure) {
                throw new ExceptionInInitializerError(failure);
            }
        }

        private static long mix(long digest, int value) {
            digest ^= Integer.toUnsignedLong(value) + 0x9E3779B97F4A7C15L
                    + (digest << 6) + (digest >>> 2);
            return Long.rotateLeft(digest, 17) * 0x94D049BB133111EBL;
        }
    }
}
