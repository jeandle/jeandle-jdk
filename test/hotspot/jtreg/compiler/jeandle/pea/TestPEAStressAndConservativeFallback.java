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
 * @summary Deterministic PEA graph stress and conservative fallback coverage
 * @library /test/lib /
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm/timeout=600 -XX:-UseJeandleCompiler
 *      -XX:-UseCompressedOops -XX:-UseCompressedClassPointers
 *      compiler.jeandle.pea.TestPEAStressAndConservativeFallback
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import jdk.test.lib.Asserts;

public class TestPEAStressAndConservativeFallback {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestPEAStressAndConservativeFallback$TestWrapper";
    private static final int ARRAY_CAP = 3;
    private static final String MONITOR_ENTER = "@jeandle.monitorenter";
    private static final String MONITOR_EXIT = "@jeandle.monitorexit";

    public static void main(String[] args) throws Exception {
        Method graph = TestWrapper.class.getMethod(
                "graphStress", int.class, int.class, int.class);
        Method lock = TestWrapper.class.getMethod(
                "lockEscapeStress", int.class, boolean.class);
        Method deopt = TestWrapper.class.getMethod(
                "activeDeoptStress", int.class);
        Method constantArray = TestWrapper.class.getMethod(
                "overCapConstantArray", int.class);
        Method dynamicArray = TestWrapper.class.getMethod(
                "overCapDynamicArray", int.class, int.class);
        Method objectArray = TestWrapper.class.getMethod(
                "overCapObjectArray", Object.class);
        Method multiArray = TestWrapper.class.getMethod(
                "multiArray", int.class, int.class);
        Method opaque = TestWrapper.class.getMethod(
                "opaqueFallback", int.class, Object.class);
        Method laterRound = TestWrapper.class.getMethod(
                "laterRoundConvergence", int.class);
        Method consumeGraph = TestWrapper.class.getMethod(
                "consumeGraph", TestWrapper.Node.class, int.class);
        Method consumeLock = TestWrapper.class.getMethod(
                "consumeLock", TestWrapper.Node.class, boolean.class);
        Method consumeOpaque = TestWrapper.class.getMethod(
                "consumeOpaque", TestWrapper.Node.class);
        Method deadEscape = TestWrapper.class.getMethod(
                "deadEscape", TestWrapper.Node.class);
        Method requestDeopt = TestWrapper.class.getMethod("requestDeopt");
        Method[] targets = {
                graph, lock, deopt, constantArray, dynamicArray, objectArray,
                multiArray, opaque, laterRound
        };

        PEATestUtils.assertStructuralParserContracts();
        behaviorBuilder(targets, consumeGraph, consumeLock, consumeOpaque,
                        deadEscape, requestDeopt)
                .peaIterations(4)
                .runPEAOnOffEquivalent();
        behaviorBuilder(targets, consumeGraph, consumeLock, consumeOpaque,
                        deadEscape, requestDeopt)
                .peaIterations(1)
                .runPEAOnOffEquivalent();

        try (PEATestUtils.RunResult run =
                shapeBuilder(targets, consumeGraph, consumeLock, consumeOpaque,
                                deadEscape, requestDeopt)
                        .peaIterations(4)
                        .run()) {
            for (Method target : targets) {
                assertSound(run, target);
                run.report(target).assertStoppedAtFixpoint();
                run.report(target).assertFinalTransformIdle();
            }
            assertGraph(run, graph, consumeGraph);
            assertLock(run, lock, consumeLock);
            assertActiveDeopt(run, deopt, requestDeopt);
            assertOverCapArray(run, constantArray);
            assertOverCapArray(run, dynamicArray);
            assertOverCapArray(run, objectArray);
            assertMultiArray(run, multiArray);
            assertOpaque(run, opaque, consumeOpaque);
            assertLaterRoundHighCap(run, laterRound);
        }

        try (PEATestUtils.RunResult run =
                shapeBuilder(targets,
                        consumeGraph, consumeLock, consumeOpaque, deadEscape,
                        requestDeopt)
                        .peaIterations(1)
                        .run()) {
            for (Method target : targets) {
                assertSound(run, target);
            }
            PEATestUtils.PEAReport report = run.report(laterRound);
            report.assertStoppedAtIterationCap();
            Asserts.assertEquals(report.roundCount(), 1,
                    laterRound + ": low cap executes exactly one productive round");
            Asserts.assertFalse(report.round(0).transformIdle(),
                    laterRound + ": productive transform reaches the configured cap");
            PEATestUtils.IRBody before = report.round0Before();
            Asserts.assertEquals(before.allocations().size(), 2,
                    laterRound + ": guard and candidate allocations before PEA");
            report.finalAfter().assertRetainsExactlyOriginalAllocations(before,
                    before.allocations().get(0).key(),
                    before.allocations().get(1).key());
        }
    }

    private static PEATestUtils.RunBuilder behaviorBuilder(
            Method[] targets, Method consumeGraph, Method consumeLock,
            Method consumeOpaque, Method deadEscape, Method requestDeopt) {
        return PEATestUtils.behaviorRun(WRAPPER, targets)
                .maxArrayLength(ARRAY_CAP)
                .dontinline(consumeGraph)
                .dontinline(consumeLock)
                .dontinline(consumeOpaque)
                .dontinline(deadEscape)
                .dontinline(requestDeopt);
    }

    private static PEATestUtils.RunBuilder shapeBuilder(
            Method[] targets, Method consumeGraph, Method consumeLock,
            Method consumeOpaque, Method deadEscape, Method requestDeopt) {
        return PEATestUtils.shapeRun(WRAPPER, targets)
                .maxArrayLength(ARRAY_CAP)
                .dontinline(consumeGraph)
                .dontinline(consumeLock)
                .dontinline(consumeOpaque)
                .dontinline(deadEscape)
                .dontinline(requestDeopt);
    }

    private static void assertSound(PEATestUtils.RunResult run, Method target)
            throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        for (PEATestUtils.PEARound round : report.rounds()) {
            PEATestUtils.assertStructuralSoundness(round.before(),
                    target + ": round " + round.iteration() + " before");
            PEATestUtils.assertStructuralSoundness(round.after(),
                    target + ": round " + round.iteration() + " after");
        }
        PEATestUtils.assertStructuralSoundness(report.finalAfter(),
                target + ": final PEA IR");
        PEATestUtils.assertStructuralSoundness(run.finalIR(target),
                target + ": final pipeline IR");
    }

    private static void assertGraph(PEATestUtils.RunResult run, Method target,
                                    Method consumer) throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();
        Asserts.assertEquals(before.allocations().size(), 6,
                target + ": five graph nodes plus one scalar-only scratch node");
        List<PEATestUtils.AllocationSite> allocations = before.allocations();
        after.assertRetainsExactlyOriginalAllocations(before,
                allocations.get(0).key(), allocations.get(1).key(),
                allocations.get(2).key(), allocations.get(3).key(),
                allocations.get(4).key());
        Asserts.assertTrue(report.effects("Materialize").size() >= 2,
                target + ": both opaque consumers have materialization coverage");

        String callee = PEATestUtils.MethodId.of(consumer).llvmFunctionName();
        after.assertLineCount("@\"" + callee + "\"", 2);
        PEATestUtils.IRBlock rootCall =
                after.blockContaining("@\"" + callee + "\"", 0);
        rootCall.assertOccurrenceCount("store atomic i32", 4);
        rootCall.assertOccurrenceCount("store atomic ptr", 6);
        rootCall.assertOccurrenceCount("store atomic", 10);
        rootCall.assertBefore("store atomic ptr", 5, "@\"" + callee + "\"", 0);

        PEATestUtils.IRBlock tailCall =
                after.blockContaining("@\"" + callee + "\"", 1);
        tailCall.assertOccurrenceCount("store atomic i32", 1);
        tailCall.assertOccurrenceCount("store atomic ptr", 1);
        tailCall.assertOccurrenceCount("store atomic", 2);
        tailCall.assertBefore("store atomic ptr", 0, "@\"" + callee + "\"", 0);
    }

    private static void assertLock(PEATestUtils.RunResult run, Method target,
                                   Method consumer) throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();
        Asserts.assertEquals(before.allocations().size(), 2,
                target + ": lock owner and cyclic child source allocations");
        after.assertRetainsExactlyOriginalAllocations(before,
                before.allocations().get(0).key(),
                before.allocations().get(1).key());
        before.assertOccurrenceCount(MONITOR_ENTER, 1);
        before.assertOccurrenceCount(MONITOR_EXIT, 3);
        after.assertOccurrenceCount(MONITOR_ENTER, 1);
        after.assertOccurrenceCount(MONITOR_EXIT, 3);

        String callee = PEATestUtils.MethodId.of(consumer).llvmFunctionName();
        PEATestUtils.IRBlock call = after.blockContaining("@\"" + callee + "\"", 0);
        call.assertOccurrenceCount("store atomic i32", 2);
        call.assertOccurrenceCount("store atomic ptr", 3);
        call.assertOccurrenceCount("store atomic", 5);
        call.assertBefore("store atomic ptr", 2, MONITOR_ENTER, 0);
        call.assertBefore(MONITOR_ENTER, 0, "@\"" + callee + "\"", 0);
    }

    private static void assertActiveDeopt(
            PEATestUtils.RunResult run, Method target, Method requestDeopt)
            throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();
        Asserts.assertEquals(before.allocations().size(), 2,
                target + ": deopt owner and shared child source allocations");
        after.assertRetainsExactlyOriginalAllocations(before,
                before.allocations().get(0).key(),
                before.allocations().get(1).key());
        Asserts.assertTrue(report.round(0).partiallyEscapes() >= 1,
                target + ": returned deopt graph retains source allocations");
        Asserts.assertTrue(report.round(0).effectCount("RewriteDeoptPool") >= 1,
                target + ": active deopt rewrites reconstruction state");
        String callee = PEATestUtils.MethodId.of(requestDeopt).llvmFunctionName();
        PEATestUtils.DeoptBundle bundle = after.deoptBundleAtCall(callee, 0);
        bundle.assertVirtualObjectIds(0, 1);
        PEATestUtils.VirtualObjectDescriptor owner = bundle.virtualObject(0);
        PEATestUtils.VirtualObjectDescriptor child = bundle.virtualObject(1);
        Asserts.assertEquals(owner.kind(), PEATestUtils.DescriptorKind.INSTANCE,
                target + ": deopt owner is an instance descriptor");
        Asserts.assertEquals(child.kind(), PEATestUtils.DescriptorKind.INSTANCE,
                target + ": deopt child is an instance descriptor");
        Asserts.assertEquals(voRefCount(owner, 1), 2L,
                target + ": owner left/right fields share the child descriptor");
        Asserts.assertEquals(voRefCount(child, 0), 1L,
                target + ": child field closes the cycle to its owner");
        Asserts.assertEquals(bundle.rootScope().monitors().size(), 1,
                target + ": one lexical monitor at the active deopt point");
        PEATestUtils.DeoptMonitor monitor =
                bundle.rootScope().monitors().get(0);
        Asserts.assertTrue(monitor.eliminated(),
                target + ": virtual owner monitor is marked eliminated");
        Asserts.assertEquals(monitor.owner().kind(),
                PEATestUtils.DeoptValueKind.VO_REF,
                target + ": eliminated monitor owner is a VORef");
        Asserts.assertEquals(monitor.owner().virtualObjectId(), 0,
                target + ": eliminated monitor belongs to the owner descriptor");
        Asserts.assertTrue(after.occurrenceCount(MONITOR_ENTER)
                        <= before.occurrenceCount(MONITOR_ENTER),
                target + ": PEA does not introduce extra monitorenter operations");
    }

    private static long voRefCount(
            PEATestUtils.VirtualObjectDescriptor descriptor, int targetId) {
        return descriptor.entries().values().stream()
                .map(PEATestUtils.VirtualObjectEntry::value)
                .filter(value -> value.kind() == PEATestUtils.DeoptValueKind.VO_REF)
                .filter(value -> value.virtualObjectId() == targetId)
                .count();
    }

    private static void assertOverCapArray(PEATestUtils.RunResult run, Method target)
            throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();
        Asserts.assertEquals(before.allocations().size(), 1,
                target + ": one over-cap source array");
        Asserts.assertEquals(before.allocations().get(0).key().kind(),
                PEATestUtils.AllocationKind.ARRAY,
                target + ": conservative allocation is an array");
        after.assertRetainsExactlyOriginalAllocations(
                before, before.allocations().get(0).key());
        Asserts.assertEquals(report.effects("EliminateAllocation").size(), 0,
                target + ": PEA does not claim over-cap array elimination");
        Asserts.assertEquals(report.effects("Materialize").size(), 0,
                target + ": ineligible array is never treated as a virtual object");
        Asserts.assertEquals(run.finalIR(target).loweredAllocCount(), 1,
                target + ": exactly the original over-cap allocation is lowered");
    }

    private static void assertMultiArray(PEATestUtils.RunResult run, Method target)
            throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();
        Asserts.assertEquals(before.allocations().size(), 0,
                target + ": multianewarray is not a PEA allocation form");
        before.assertOccurrenceCount("@multianewarray2", 1);
        after.assertOccurrenceCount("@multianewarray2", 1);
        Asserts.assertEquals(report.effects("EliminateAllocation").size(), 0,
                target + ": PEA never claims multiarray elimination");
        Asserts.assertEquals(report.effects("Materialize").size(), 0,
                target + ": PEA never materializes an untracked multiarray");
    }

    private static void assertOpaque(PEATestUtils.RunResult run, Method target,
                                     Method consumer) throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();
        Asserts.assertEquals(before.allocations().size(), 2,
                target + ": root and child source allocations");
        after.assertRetainsExactlyOriginalAllocations(before,
                before.allocations().get(0).key(),
                before.allocations().get(1).key());
        String callee = PEATestUtils.MethodId.of(consumer).llvmFunctionName();
        PEATestUtils.IRBlock call = after.blockContaining("@\"" + callee + "\"", 0);
        call.assertOccurrenceCount("store atomic i32", 2);
        call.assertOccurrenceCount("store atomic ptr", 5);
        call.assertOccurrenceCount("store atomic", 7);
        call.assertBefore("store atomic ptr", 4, "@\"" + callee + "\"", 0);
        Asserts.assertTrue(report.effects("Materialize").size() >= 2,
                target + ": opaque call materializes the live object closure");
    }

    private static void assertLaterRoundHighCap(
            PEATestUtils.RunResult run, Method target) throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        Asserts.assertEquals(report.roundCount(), 3,
                target + ": productive second round plus one unchanged complete round");
        Asserts.assertFalse(report.round(0).transformIdle(),
                target + ": first round virtualizes the constant guard");
        Asserts.assertFalse(report.round(1).transformIdle(),
                target + ": second round eliminates the newly non-escaping candidate");
        Asserts.assertTrue(report.round(2).transformIdle(),
                target + ": unchanged complete round reaches the fixpoint");
        PEATestUtils.IRBody before = report.round0Before();
        Asserts.assertEquals(before.allocations().size(), 2,
                target + ": guard and candidate allocation identities");
        report.finalAfter().assertRetainsExactlyOriginalAllocations(before);
        report.finalAfter().assertAbsent("store atomic");
        report.finalAfter().assertAbsent("load atomic");
    }

    public static class TestWrapper {
        private static final Method DEOPT_TARGET = deoptTarget();

        public static class Node {
            int value;
            Node left;
            Node right;
            Object external;

            Node(int value) {
                this.value = value;
            }
        }

        public static class MarkerException extends RuntimeException {
            private static final long serialVersionUID = 1L;
        }

        private static Node firstPublished;
        private static Node secondPublished;
        private static Node lockedPublished;
        private static Node opaquePublished;
        private static int graphConsumeCount;
        private static int lockConsumeCount;
        private static int opaqueConsumeCount;

        public static void main(String[] args) throws Exception {
            new Node(0);
            PEATestUtils.compileConfiguredTargetsAtLevel4();

            long digest = 0x243F6A8885A308D3L;
            int[][] graphCases = {
                    {0, 4, 0}, {1, 5, 1}, {2, 6, 2}, {3, 7, 3}
            };
            for (int[] test : graphCases) {
                resetGraph();
                long actual = graphStress(test[0], test[1], test[2]);
                long expected = referenceGraph(test[0], test[1], test[2]);
                Asserts.assertEquals(actual, expected,
                        "fixed graph reference " + test[0] + "/" + test[1]
                                + "/" + test[2]);
                assertPublishedGraph(test[0], test[1], test[2]);
                digest = mix(digest, actual);
                digest = mix(digest, graphConsumeCount);
            }

            for (boolean doThrow : new boolean[] {false, true}) {
                lockedPublished = null;
                lockConsumeCount = 0;
                long actual = lockEscapeStress(31, doThrow);
                long expected = encodeLock(38, 32, doThrow ? 1 : 0, 1);
                Asserts.assertEquals(actual, expected,
                        "lock escape values, identity, and exception path");
                Asserts.assertNotNull(lockedPublished, "lock owner published");
                Asserts.assertEquals(lockConsumeCount, 1, "one lock consumer");
                Asserts.assertFalse(Thread.holdsLock(lockedPublished),
                        "caller thread does not retain the escaped monitor");
                assertMonitorReacquirable(lockedPublished);
                digest = mix(digest, actual);
            }

            Node deoptimized = activeDeoptStress(47);
            Asserts.assertEquals(deoptimized.value, 120,
                    "post-deopt owner mutation is visible");
            Asserts.assertNotNull(deoptimized.left, "post-deopt child is reconstructed");
            Asserts.assertSame(deoptimized.left.left, deoptimized,
                    "post-deopt cycle preserves owner identity");
            Asserts.assertSame(deoptimized.right, deoptimized.left,
                    "post-deopt repeated reference preserves child identity");
            Asserts.assertEquals(deoptimized.left.value, 48,
                    "post-deopt child payload");
            Asserts.assertFalse(Thread.holdsLock(deoptimized),
                    "deoptimized target releases reconstructed monitor");
            assertMonitorReacquirable(deoptimized);
            digest = mix(digest, deoptimized.value);
            digest = mix(digest, deoptimized.left.left == deoptimized ? 1 : 0);
            digest = mix(digest, deoptimized.left == deoptimized.right ? 1 : 0);

            Asserts.assertEquals(overCapConstantArray(9), 0x0509092D,
                    "constant over-cap array");
            Asserts.assertEquals(overCapDynamicArray(5, 13), 0x050D0D41,
                    "dynamic over-cap array");
            Object external = new Object();
            Asserts.assertEquals(overCapObjectArray(external), 0x05070F03,
                    "over-cap object array identity");
            Asserts.assertEquals(multiArray(2, 4), 0x0204014D,
                    "multi-dimensional array");
            digest = mix(digest, overCapConstantArray(9));
            digest = mix(digest, overCapDynamicArray(5, 13));
            digest = mix(digest, overCapObjectArray(external));
            digest = mix(digest, multiArray(2, 4));

            opaquePublished = null;
            opaqueConsumeCount = 0;
            long opaqueResult = opaqueFallback(61, external);
            Asserts.assertEquals(opaqueResult, encodeOpaque(66, 62, true, true),
                    "opaque fallback replay and identity");
            Asserts.assertSame(opaquePublished.external, external,
                    "opaque consumer sees external reference");
            Asserts.assertSame(opaquePublished.left.left, opaquePublished,
                    "opaque consumer sees cyclic child");
            Asserts.assertEquals(opaqueConsumeCount, 1, "one opaque consumer");
            digest = mix(digest, opaqueResult);

            Asserts.assertEquals(laterRoundConvergence(17), 18,
                    "later-round behavior");
            digest = mix(digest, laterRoundConvergence(17));
            System.out.println("PEA-RESULT:" + Long.toUnsignedString(digest, 16));
        }

        public static long graphStress(int selector, int trips, int escapeMask) {
            Node root = new Node(10);
            Node left = new Node(20);
            Node right = new Node(30);
            Node shared = new Node(40);
            Node tail = new Node(50);
            Node scratch = new Node(60);

            root.left = left;
            root.right = right;
            left.left = shared;
            right.left = shared;
            shared.left = root;
            shared.right = left;
            tail.left = shared;
            int pathSum = 0;
            for (int i = 0; i < trips; i++) {
                if (((selector + i) & 1) == 0) {
                    left.value += i + 1;
                } else {
                    right.value += i + 2;
                }
                switch ((selector + i) % 3) {
                    case 0:
                        shared.value += left.value & 7;
                        pathSum += shared.value;
                        break;
                    case 1:
                        tail.value += right.value & 3;
                        pathSum += tail.value;
                        break;
                    default:
                        root.value += shared.value & 1;
                        pathSum += root.value;
                        break;
                }
                scratch.value += (i ^ selector) & 3;
            }
            if ((escapeMask & 1) != 0) {
                consumeGraph(root, 1);
            }
            if ((escapeMask & 2) != 0) {
                consumeGraph(tail, 2);
            }

            long digest = 0x13198A2E03707344L;
            digest = mix(digest, root.value);
            digest = mix(digest, left.value);
            digest = mix(digest, right.value);
            digest = mix(digest, shared.value);
            digest = mix(digest, tail.value);
            digest = mix(digest, scratch.value);
            digest = mix(digest, pathSum);
            digest = mix(digest, root.left == left ? 1 : 0);
            digest = mix(digest, root.right == right ? 2 : 0);
            digest = mix(digest, left.left == right.left ? 4 : 0);
            digest = mix(digest, shared.left == root ? 8 : 0);
            digest = mix(digest, tail.left == shared ? 16 : 0);
            digest = mix(digest, shared.right == left ? 32 : 0);
            return digest;
        }

        public static long lockEscapeStress(int seed, boolean doThrow) {
            Node owner = new Node(seed);
            Node child = new Node(seed + 1);
            owner.left = child;
            owner.right = child;
            child.left = owner;
            int caught = 0;
            synchronized (owner) {
                try {
                    consumeLock(owner, doThrow);
                } catch (MarkerException expected) {
                    caught = 1;
                }
            }
            return encodeLock(owner.value, child.value, caught,
                    owner.left == owner.right && child.left == owner ? 1 : 0);
        }

        public static Node activeDeoptStress(int seed) {
            Node owner = new Node(seed);
            Node child = new Node(seed + 1);
            owner.left = child;
            owner.right = child;
            child.left = owner;
            synchronized (owner) {
                int token = requestDeopt();
                if (token != 73) {
                    throw new AssertionError("exact level-4 active deopt evidence");
                }
                owner.value += token;
            }
            return owner;
        }

        public static int overCapConstantArray(int value) {
            int[] array = new int[5];
            array[0] = value;
            array[4] = value + 36;
            return (array.length << 24) | (array[0] << 16)
                    | (array[0] << 8) | array[4];
        }

        public static int overCapDynamicArray(int length, int value) {
            int[] array = new int[length];
            array[0] = value;
            array[length - 1] = value + 52;
            return (array.length << 24) | (array[0] << 16)
                    | (array[0] << 8) | array[length - 1];
        }

        public static int overCapObjectArray(Object value) {
            Object[] array = new Object[5];
            array[0] = value;
            array[2] = value;
            array[4] = null;
            int identity = array[0] == array[2] ? 1 : 0;
            int nullBit = array[4] == null ? 2 : 0;
            return (array.length << 24) | (7 << 16) | (15 << 8)
                    | identity | nullBit;
        }

        public static int multiArray(int first, int second) {
            Node[][] array = new Node[first][second];
            return (array.length << 24) | (array[0].length << 16)
                    | (array[1][3] == null ? 0x100 : 0) | 0x4D;
        }

        public static long opaqueFallback(int seed, Object external) {
            Node root = new Node(seed);
            Node child = new Node(seed + 1);
            root.left = child;
            root.right = null;
            root.external = external;
            child.left = root;
            child.right = root;
            consumeOpaque(root);
            root.right = (Node) null;
            long result = encodeOpaque(root.value, child.value,
                    root.left.left == root, opaquePublished == root);
            return result;
        }

        public static int laterRoundConvergence(int value) {
            Node guard = new Node(0);
            Node candidate = new Node(value);
            candidate.right = guard;
            if (guard.value != 0) {
                deadEscape(candidate);
            }
            return candidate.value + (candidate.right == guard ? 1 : 0);
        }

        public static void consumeGraph(Node value, int tag) {
            value.value += tag * 100;
            if (tag == 1) {
                firstPublished = value;
            } else {
                secondPublished = value;
            }
            graphConsumeCount++;
        }

        public static void consumeLock(Node value, boolean doThrow) {
            value.value += 7;
            lockedPublished = value;
            lockConsumeCount++;
            if (doThrow) {
                throw new MarkerException();
            }
        }

        public static void consumeOpaque(Node value) {
            value.value += 5;
            opaquePublished = value;
            opaqueConsumeCount++;
        }

        public static void deadEscape(Node value) {
            firstPublished = value;
        }

        public static int requestDeopt() {
            PEATestUtils.ActiveFrameDeoptEvidence evidence =
                    PEATestUtils.deoptimizeActiveFrame(DEOPT_TARGET, 2);
            if (!evidence.frameDeoptimized()
                    || evidence.compilationLevel() != 4
                    || evidence.markedNMethods() != 1) {
                throw new AssertionError("exact active-frame deopt evidence");
            }
            return 73;
        }

        private static long referenceGraph(int selector, int trips, int escapeMask) {
            int[] state = referenceGraphState(selector, trips, escapeMask);
            long digest = 0x13198A2E03707344L;
            for (int value : state) {
                digest = mix(digest, value);
            }
            digest = mix(digest, 1);
            digest = mix(digest, 2);
            digest = mix(digest, 4);
            digest = mix(digest, 8);
            digest = mix(digest, 16);
            digest = mix(digest, 32);
            return digest;
        }

        private static int[] referenceGraphState(
                int selector, int trips, int escapeMask) {
            int root = 10;
            int left = 20;
            int right = 30;
            int shared = 40;
            int tail = 50;
            int scratch = 60;
            int pathSum = 0;
            for (int i = 0; i < trips; i++) {
                if (((selector + i) & 1) == 0) {
                    left += i + 1;
                } else {
                    right += i + 2;
                }
                switch ((selector + i) % 3) {
                    case 0:
                        shared += left & 7;
                        pathSum += shared;
                        break;
                    case 1:
                        tail += right & 3;
                        pathSum += tail;
                        break;
                    default:
                        root += shared & 1;
                        pathSum += root;
                        break;
                }
                scratch += (i ^ selector) & 3;
            }
            if ((escapeMask & 1) != 0) {
                root += 100;
            }
            if ((escapeMask & 2) != 0) {
                tail += 200;
            }
            return new int[] {
                    root, left, right, shared, tail, scratch, pathSum
            };
        }

        private static void assertPublishedGraph(
                int selector, int trips, int escapeMask) {
            int[] expected = referenceGraphState(selector, trips, escapeMask);
            Asserts.assertEquals(graphConsumeCount, Integer.bitCount(escapeMask & 3),
                    "exact graph consumer count");
            if ((escapeMask & 1) == 0) {
                Asserts.assertNull(firstPublished, "root is not published");
            } else {
                Asserts.assertNotNull(firstPublished, "root is published");
                Asserts.assertEquals(firstPublished.value, expected[0],
                        "root replay precedes first consumer");
                Asserts.assertSame(firstPublished.left.left,
                        firstPublished.right.left,
                        "published diamond retains shared identity");
                Asserts.assertSame(firstPublished.left.left.left, firstPublished,
                        "published graph retains its cycle");
            }
            if ((escapeMask & 2) == 0) {
                Asserts.assertNull(secondPublished, "tail is not published");
            } else {
                Asserts.assertNotNull(secondPublished, "tail is published");
                Asserts.assertEquals(secondPublished.value, expected[4],
                        "tail replay precedes second consumer");
                Asserts.assertNotNull(secondPublished.left,
                        "tail retains shared graph reference");
            }
            if ((escapeMask & 3) == 3) {
                Asserts.assertSame(secondPublished.left, firstPublished.left.left,
                        "both escape points retain one shared identity");
            }
        }

        private static void resetGraph() {
            firstPublished = null;
            secondPublished = null;
            graphConsumeCount = 0;
        }

        private static long encodeLock(
                int owner, int child, int caught, int identity) {
            return ((long) owner << 48) ^ ((long) child << 32)
                    ^ ((long) caught << 16) ^ identity;
        }

        private static long encodeOpaque(
                int root, int child, boolean cycle, boolean identity) {
            return ((long) root << 48) ^ ((long) child << 32)
                    ^ (cycle ? 0x10000L : 0L) ^ (identity ? 1L : 0L);
        }

        private static void assertMonitorReacquirable(Object monitor)
                throws InterruptedException {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread contender = new Thread(() -> {
                try {
                    synchronized (monitor) {
                        if (!Thread.holdsLock(monitor)) {
                            throw new AssertionError(
                                    "contender does not own reacquired monitor");
                        }
                    }
                } catch (Throwable throwable) {
                    failure.set(throwable);
                }
            }, "pea-stress-monitor-reacquire");
            contender.setDaemon(true);
            contender.start();
            contender.join(10_000);
            if (contender.isAlive()) {
                throw new AssertionError(
                        "contender could not reacquire escaped monitor");
            }
            if (failure.get() != null) {
                throw new AssertionError(
                        "contender failed while reacquiring monitor", failure.get());
            }
        }

        private static Method deoptTarget() {
            try {
                return TestWrapper.class.getMethod(
                        "activeDeoptStress", int.class);
            } catch (ReflectiveOperationException failure) {
                throw new ExceptionInInitializerError(failure);
            }
        }

        private static long mix(long accumulator, long value) {
            return Long.rotateLeft(accumulator ^ value, 13)
                    * 0x9E3779B97F4A7C15L;
        }
    }
}
