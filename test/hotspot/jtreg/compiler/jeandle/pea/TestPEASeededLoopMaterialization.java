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
 * @summary A partially materialized object carried through an outer append
 *          loop and an inlined inner loop converges from the merged loop state
 *          without duplicate inner-loop replay
 * @library /test/lib /
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.pea.TestPEASeededLoopMaterialization
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;

import jdk.test.lib.Asserts;

public class TestPEASeededLoopMaterialization {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestPEASeededLoopMaterialization$TestWrapper";
    private static final String SAFEPOINT_POLL = "jeandle.safepoint_poll";

    public static void main(String[] args) throws Exception {
        PEATestUtils.assertStructuralParserContracts();

        Method target = TestWrapper.class.getMethod(
                "appendAndWalk", int.class, int.class, int.class);
        Method eliminateScratch = TestWrapper.class.getMethod(
                "eliminateScratch", int.class);
        Method innerWork = TestWrapper.class.getMethod(
                "innerWork", TestWrapper.Buffer.class, int.class, int.class);
        Method publish = TestWrapper.class.getMethod(
                "publish", TestWrapper.Buffer.class);

        Method[] targets = {target, eliminateScratch};
        PEATestUtils.behaviorRun(WRAPPER, targets)
                .inline(innerWork)
                .dontinline(publish)
                .runPEAOnOffEquivalent();

        assertScratchShape(eliminateScratch);
        Shape cap4 = shape(target, innerWork, publish, 4);
        Shape cap16 = shape(target, innerWork, publish, 16);
        assertShape(cap4, target, publish);
        assertShape(cap16, target, publish);
        cap16.after().assertCrossProcessExactEquals(cap4.after(),
                target + ": PEA caps 4 and 16 reach the same seeded-loop fixpoint");
    }

    private static Shape shape(Method target, Method innerWork, Method publish,
                               int cap) throws Exception {
        try (PEATestUtils.RunResult run = PEATestUtils.shapeRun(WRAPPER, target)
                .peaIterations(cap)
                .inline(innerWork)
                .dontinline(publish)
                .run()) {
            PEATestUtils.PEAReport report = run.report(target);
            report.assertStoppedAtFixpoint();
            report.assertFinalTransformIdle();
            return new Shape(report, report.round0Before(), report.finalAfter());
        }
    }

    private static void assertShape(Shape shape, Method target, Method publish) {
        PEATestUtils.PEAReport report = shape.report();
        PEATestUtils.IRBody before = shape.before();
        PEATestUtils.IRBody after = shape.after();

        Asserts.assertEquals(before.peaAllocCount(), 2,
                target + ": Buffer and Scratch allocations reach PEA");
        Asserts.assertEquals(after.peaAllocCount(), 1,
                target + ": Buffer partially escapes while Scratch is eliminated");
        Asserts.assertEquals(report.roundCount(), 2,
                target + ": one active transform and one idle fixpoint round");

        var sourceAllocations = before.allocations();
        var retainedAllocations = after.allocations();
        after.assertRetainsExactlyOriginalAllocations(
                before, sourceAllocations.get(0).key());
        String sourceBuffer = sourceAllocations.get(0).result();
        PEATestUtils.IRBlock sourceSeed =
                before.blockContaining("store atomic ptr addrspace(1)", 0);
        sourceSeed.assertOccurrenceCount("store atomic ptr addrspace(1)", 2);
        sourceSeed.assertPresent("ptr addrspace(1) " + sourceBuffer + ", i64 24");
        sourceSeed.assertPresent("ptr addrspace(1) " + sourceBuffer + ", i64 32");

        String retainedBuffer = retainedAllocations.get(0).result();
        PEATestUtils.IRBlock replay = after.blockContaining("pea.matslot", 0);
        var replaySlotDefinitions = replay.lines().stream()
                .filter(line -> line.contains("pea.matslot")
                        && line.contains("getelementptr"))
                .toList();
        long replayStores = replay.lines().stream()
                .filter(line -> line.contains("store atomic ptr addrspace(1)")
                        && line.contains("pea.matslot"))
                .count();
        Asserts.assertEquals(replaySlotDefinitions.size(), 2,
                target + ": exactly two seeded Buffer fields are replayed");
        Asserts.assertEquals(replayStores, 2L,
                target + ": each seeded field has one replay store");
        long allReplayLines = after.lines().stream()
                .filter(line -> line.contains("pea.matslot"))
                .count();
        long preheaderReplayLines = replay.lines().stream()
                .filter(line -> line.contains("pea.matslot"))
                .count();
        Asserts.assertEquals(allReplayLines, 4L,
                target + ": two replay GEPs and their stores are the only matslot uses");
        Asserts.assertEquals(preheaderReplayLines, allReplayLines,
                target + ": every matslot definition and use is in the replay preheader");
        for (String definition : replaySlotDefinitions) {
            int assignment = definition.indexOf(" = getelementptr");
            Asserts.assertTrue(assignment > 0,
                    target + ": replay slot has an SSA assignment");
            String slot = definition.substring(0, assignment).trim();
            String destination = ", ptr addrspace(1) " + slot + " unordered";
            Asserts.assertEquals(replay.occurrenceCount(destination), 1,
                    target + ": replay slot " + slot + " has one destination store");
            Asserts.assertEquals(after.occurrenceCount(destination), 1,
                    target + ": replay slot " + slot + " is not stored elsewhere");
        }
        String headReplay = "ptr addrspace(1) " + retainedBuffer + ", i64 24";
        String lastReplay = "ptr addrspace(1) " + retainedBuffer + ", i64 32";
        Asserts.assertEquals(replaySlotDefinitions.stream()
                .filter(line -> line.contains(headReplay)).count(), 1L,
                target + ": one matslot definition addresses Buffer.head");
        Asserts.assertEquals(replaySlotDefinitions.stream()
                .filter(line -> line.contains(lastReplay)).count(), 1L,
                target + ": one matslot definition addresses Buffer.last");
        replay.assertOccurrenceCount("store atomic ptr addrspace(1)", 2);

        String effectBlock = "block=%" + replay.label() + " ";
        String effectAllocation = retainedBuffer + " = invoke hotspotcc";
        for (PEATestUtils.PEARound round : report.rounds()) {
            Asserts.assertEquals(round.effectCount("Materialize", "[VO=0]"), 1L,
                    target + ": round " + round.iteration()
                            + " emits one Buffer materialization");
            Asserts.assertEquals(round.effectCount("Materialize"), 1L,
                    target + ": round " + round.iteration()
                            + " emits no other materialization");
            PEATestUtils.PEAEffect effect =
                    round.uniqueEffect("Materialize", "[VO=0]");
            Asserts.assertTrue(effect.detail().contains(effectBlock),
                    target + ": round " + round.iteration()
                            + " materializes in the replay preheader");
            Asserts.assertTrue(effect.detail().contains(effectAllocation),
                    target + ": round " + round.iteration()
                            + " materializes the retained Buffer allocation");
        }

        Asserts.assertTrue(report.maxPartiallyEscapes() >= 1,
                target + ": Buffer is classified PartiallyEscapes");
        Asserts.assertEquals(before.lineCount(SAFEPOINT_POLL), 4,
                target + ": frontend emits two return and two loop polls");
        Asserts.assertEquals(after.lineCount(SAFEPOINT_POLL), 4,
                target + ": return and nested loop polls survive PEA");
        PEATestUtils.IRBlock outerPoll = after.blockContaining(SAFEPOINT_POLL, 0);
        PEATestUtils.IRBlock innerPoll = after.blockContaining(SAFEPOINT_POLL, 2);
        Asserts.assertNotEquals(innerPoll.label(), outerPoll.label(),
                target + ": inner and outer polls are in distinct loop blocks");
        innerPoll.assertPresent("invoke hotspotcc void @jeandle.safepoint_poll");
        outerPoll.assertPresent("call hotspotcc void @jeandle.safepoint_poll");
        innerPoll.assertAbsent("pea.matslot");
        Asserts.assertNotEquals(replay.label(), innerPoll.label(),
                target + ": seeded replay is outside the inner-loop backedge");
        Asserts.assertNotEquals(replay.label(), outerPoll.label(),
                target + ": seeded replay is outside the outer-loop backedge");
        String outerHeader = replay.unconditionalBranchTarget();
        Asserts.assertTrue(outerPoll.conditionalBranchTargets().contains(outerHeader),
                target + ": seeded replay block is the outer-loop forward preheader");
        after.assertAbsent(".pea.replay");

        String publishName = PEATestUtils.MethodId.of(publish).llvmFunctionName();
        Asserts.assertEquals(after.occurrenceCount(publishName), 1,
                target + ": one conditional Buffer escape remains");
        PEATestUtils.IRBlock publishBlock = after.blockContaining(publishName, 0);
        Asserts.assertNotEquals(publishBlock.label(), innerPoll.label(),
                target + ": late Buffer escape is outside the inner-loop backedge");
        Asserts.assertNotEquals(publishBlock.label(), replay.label(),
                target + ": the late escape is after the replay preheader");
        publishBlock.assertPresent("store atomic");
        publishBlock.assertPresent(
                "to label %" + outerPoll.label() + " unwind label");
        after.assertAbsent("jeandle.monitorenter");
        after.assertAbsent("jeandle.monitorexit");

        for (PEATestUtils.PEARound round : report.rounds()) {
            PEATestUtils.assertStructuralSoundness(round.before(),
                    target + ": round " + round.iteration() + " before");
            PEATestUtils.assertStructuralSoundness(round.after(),
                    target + ": round " + round.iteration() + " after");
        }
        PEATestUtils.assertStructuralSoundness(after,
                target + ": final seeded-loop IR");
    }

    private static void assertScratchShape(Method target) throws Exception {
        try (PEATestUtils.RunResult run = PEATestUtils.shapeRun(WRAPPER, target).run()) {
            PEATestUtils.PEAReport report = run.report(target);
            report.assertStoppedAtFixpoint();
            report.assertFinalTransformIdle();
            PEATestUtils.PEARound first = report.round(0);
            PEATestUtils.IRBody before = report.round0Before();
            PEATestUtils.IRBody after = report.finalAfter();

            Asserts.assertEquals(before.peaAllocCount(), 1,
                    target + ": the Scratch allocation reaches PEA");
            Asserts.assertEquals(first.neverEscapes(), 1,
                    target + ": Scratch is classified NeverEscapes");
            Asserts.assertEquals(first.partiallyEscapes(), 0,
                    target + ": Scratch does not partially escape");
            Asserts.assertEquals(first.alwaysEscapes(), 0,
                    target + ": Scratch does not always escape");
            Asserts.assertEquals(first.effectCount(
                    "EliminateAllocation", "[VO=0]"), 1L,
                    target + ": Scratch is scalar-replaced by PEA");
            Asserts.assertEquals(first.effectCount("Materialize", "[VO=0]"), 0L,
                    target + ": Scratch is never materialized");
            Asserts.assertEquals(after.peaAllocCount(), 0,
                    target + ": no Scratch allocation remains after PEA");
            after.assertAbsent("jeandle.monitorenter");
            after.assertAbsent("jeandle.monitorexit");
            for (PEATestUtils.PEARound round : report.rounds()) {
                PEATestUtils.assertStructuralSoundness(round.before(),
                        target + ": round " + round.iteration() + " before");
                PEATestUtils.assertStructuralSoundness(round.after(),
                        target + ": round " + round.iteration() + " after");
            }
        }
    }

    private record Shape(PEATestUtils.PEAReport report,
                         PEATestUtils.IRBody before,
                         PEATestUtils.IRBody after) {}

    public static class TestWrapper {
        private static final Node[] NODES = {
                new Node(-19), new Node(18), new Node(55), new Node(92),
                new Node(129), new Node(166), new Node(203), new Node(240),
                new Node(277), new Node(314), new Node(351), new Node(388),
                new Node(425), new Node(462), new Node(499), new Node(536)
        };
        private static Buffer published;
        private static int publishCount;

        public static class Node {
            final int value;

            Node(int value) {
                this.value = value;
            }
        }

        public static class Buffer {
            Node head;
            Node last;
            int count;
            boolean shared;
        }

        public static class Scratch {
            int left;
            int right;
        }

        public static void main(String[] args) throws Exception {
            new Buffer();
            new Scratch();
            PEATestUtils.compileConfiguredTargetsAtLevel4();

            int[][] cases = {
                    {0, 0, 3},
                    {1, 0, 5},
                    {1, 3, 7},
                    {4, 1, 10},
                    {7, 5, 13}
            };
            int passed = 0;
            long digest = 0x6A09E667F3BCC909L;
            for (int index = 0; index < cases.length; index++) {
                int outerTrips = cases[index][0];
                int innerTrips = cases[index][1];
                int seed = cases[index][2];
                published = null;
                publishCount = 0;

                int actual = appendAndWalk(outerTrips, innerTrips, seed);
                Reference expected = reference(outerTrips, innerTrips, seed);
                Asserts.assertEquals(actual, expected.result(),
                        "case " + index + ": array reference result");
                Asserts.assertEquals(eliminateScratch(seed), 2 * seed + 1,
                        "case " + index + ": eliminated Scratch result");
                Asserts.assertEquals(publishCount, expected.shared() ? 1 : 0,
                        "case " + index + ": exact publish count");
                Asserts.assertEquals(published != null, expected.shared(),
                        "case " + index + ": publish presence");
                if (published != null) {
                    Asserts.assertEquals(published.count, outerTrips,
                            "case " + index + ": escaped count tracks later appends");
                    Asserts.assertEquals(published.head.value, expected.head(),
                            "case " + index + ": escaped head");
                    Asserts.assertEquals(published.last.value, expected.last(),
                            "case " + index + ": escaped last");
                    Asserts.assertTrue(published.shared,
                            "case " + index + ": escaped shared flag");
                }
                passed |= 1 << index;
                digest = Long.rotateLeft(digest ^ Integer.toUnsignedLong(actual), 13)
                        * 0x9E3779B97F4A7C15L;
            }
            Asserts.assertEquals(passed, 0x1f, "all literal workload paths execute");
            System.out.println("PEA-RESULT:" + passed + ":"
                    + Long.toUnsignedString(digest, 16));
        }

        public static int appendAndWalk(int outerTrips, int innerTrips, int seed) {
            if (outerTrips <= 0) {
                int digest = targetMix(seed * 17 + 3, seed ^ 0x5a5a);
                digest = targetMix(digest, 0);
                digest = targetMix(digest, -1);
                digest = targetMix(digest, -1);
                return targetMix(digest, 0);
            }

            Buffer buffer = new Buffer();
            Node sentinel = NODES[(seed ^ 7) & 15];
            buffer.head = sentinel;
            buffer.last = sentinel;
            Scratch scratch = new Scratch();
            scratch.left = seed * 17 + 3;
            scratch.right = seed ^ 0x5a5a;
            int digest = targetMix(scratch.left, scratch.right);
            scratch = null;

            for (int outer = 0; outer < outerTrips; outer++) {
                Node node = NODES[(seed + outer) & 15];
                if (buffer.head == null) {
                    buffer.head = node;
                }
                buffer.last = node;
                buffer.count++;
                digest = targetMix(digest, node.value);
                digest = targetMix(digest, innerWork(buffer, innerTrips, seed + outer));
                if (!buffer.shared && innerTrips != 0 && ((seed + outer) & 1) != 0) {
                    buffer.shared = true;
                    publish(buffer);
                }
            }

            int count = buffer.count;
            int head = buffer.head == null ? -1 : buffer.head.value;
            int last = buffer.last == null ? -1 : buffer.last.value;
            int shared = buffer.shared ? 1 : 0;
            digest = targetMix(digest, count);
            digest = targetMix(digest, head);
            digest = targetMix(digest, last);
            return targetMix(digest, shared);
        }

        public static int innerWork(Buffer buffer, int trips, int seed) {
            int digest = seed;
            for (int inner = 0; inner < trips; inner++) {
                int head = buffer.head == null ? -1 : buffer.head.value;
                int last = buffer.last == null ? -1 : buffer.last.value;
                digest = targetMix(digest, head);
                digest = targetMix(digest, last);
                digest = targetMix(digest, buffer.count * 31 + inner);
                digest = targetMix(digest, buffer.shared ? 1 : 0);
            }
            return digest;
        }

        public static int eliminateScratch(int seed) {
            Scratch scratch = new Scratch();
            synchronized (scratch) {
                scratch.left = seed;
                scratch.right = seed + 1;
            }
            return scratch.left + scratch.right;
        }

        public static void publish(Buffer buffer) {
            publishCount++;
            published = buffer;
        }

        private static int targetMix(int left, int right) {
            return Integer.rotateLeft(left ^ right, 7) * 31 + 17;
        }

        private static Reference reference(int outerTrips, int innerTrips, int seed) {
            int[] values = {
                    -19, 18, 55, 92, 129, 166, 203, 240,
                    277, 314, 351, 388, 425, 462, 499, 536
            };
            int[] appended = new int[outerTrips];
            int digest = Integer.rotateLeft(
                    (seed * 17 + 3) ^ (seed ^ 0x5a5a), 7) * 31 + 17;
            boolean shared = false;

            for (int outer = 0; outer < outerTrips; outer++) {
                appended[outer] = values[(seed + outer) & 15];
                int node = appended[outer];
                digest = Integer.rotateLeft(digest ^ node, 7) * 31 + 17;

                int innerDigest = seed + outer;
                for (int inner = 0; inner < innerTrips; inner++) {
                    int head = values[(seed ^ 7) & 15];
                    int last = node;
                    innerDigest = Integer.rotateLeft(innerDigest ^ head, 7) * 31 + 17;
                    innerDigest = Integer.rotateLeft(innerDigest ^ last, 7) * 31 + 17;
                    innerDigest = Integer.rotateLeft(innerDigest
                            ^ ((outer + 1) * 31 + inner), 7) * 31 + 17;
                    innerDigest = Integer.rotateLeft(innerDigest
                            ^ (shared ? 1 : 0), 7) * 31 + 17;
                }
                if (!shared && innerTrips != 0 && ((seed + outer) & 1) != 0) {
                    shared = true;
                }
                digest = Integer.rotateLeft(digest ^ innerDigest, 7) * 31 + 17;
            }

            int head = outerTrips == 0 ? -1 : values[(seed ^ 7) & 15];
            int last = outerTrips == 0 ? -1 : appended[outerTrips - 1];
            digest = Integer.rotateLeft(digest ^ outerTrips, 7) * 31 + 17;
            digest = Integer.rotateLeft(digest ^ head, 7) * 31 + 17;
            digest = Integer.rotateLeft(digest ^ last, 7) * 31 + 17;
            digest = Integer.rotateLeft(digest ^ (shared ? 1 : 0), 7) * 31 + 17;
            return new Reference(digest, shared, head, last);
        }

        private record Reference(int result, boolean shared, int head, int last) {}
    }
}
