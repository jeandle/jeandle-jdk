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
 * @summary PEA reconstructs object identity, aliases, sharing, and cyclic
 *          topology at an exact active-frame deoptimization
 * @library /test/lib /
 * @modules java.base/jdk.internal.misc
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      -XX:-UseCompressedOops -XX:-UseCompressedClassPointers
 *      compiler.jeandle.pea.TestPEADeoptReconstructIdentityGraph
 */

package compiler.jeandle.pea;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jdk.internal.misc.Unsafe;
import jdk.test.lib.Asserts;

public class TestPEADeoptReconstructIdentityGraph {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestPEADeoptReconstructIdentityGraph$TestWrapper";
    private static final Unsafe UNSAFE = Unsafe.getUnsafe();

    public static void main(String[] args) throws Exception {
        Method target = TestWrapper.class.getMethod("testIdentityGraph");
        Method requestDeopt = TestWrapper.class.getDeclaredMethod("requestDeopt");
        Method stackAliasToken = TestWrapper.class.getDeclaredMethod(
                "stackAliasToken", TestWrapper.Node.class,
                TestWrapper.Node.class, int.class);
        Method hasInitialTopology = TestWrapper.class.getDeclaredMethod(
                "hasInitialTopology", nodeParameterTypes(12));
        Method mutateAndReread = TestWrapper.class.getDeclaredMethod(
                "mutateAndReread", nodeParameterTypes(12));
        Method reconstructedResult = TestWrapper.class.getDeclaredMethod(
                "reconstructedResult", nodeParameterTypes(12));
        Method[] targets = {target};
        Method[] inlineHelpers = {
                stackAliasToken, hasInitialTopology,
                mutateAndReread, reconstructedResult};

        runBuilder(false, targets, requestDeopt, inlineHelpers)
                .runPEAOnOffEquivalent();

        try (PEATestUtils.RunResult run =
                runBuilder(true, targets, requestDeopt, inlineHelpers).run()) {
            assertShape(run, target, requestDeopt);
        }
    }

    private static PEATestUtils.RunBuilder runBuilder(
            boolean shape, Method[] targets, Method requestDeopt,
            Method[] inlineHelpers) {
        PEATestUtils.RunBuilder builder = shape
                ? PEATestUtils.shapeRun(WRAPPER, targets)
                : PEATestUtils.behaviorRun(WRAPPER, targets);
        builder.dontinline(requestDeopt);
        for (Method helper : inlineHelpers) {
            builder.inline(helper);
        }
        return builder;
    }

    private static Class<?>[] nodeParameterTypes(int count) {
        Class<?>[] parameterTypes = new Class<?>[count];
        for (int i = 0; i < count; i++) {
            parameterTypes[i] = TestWrapper.Node.class;
        }
        return parameterTypes;
    }

    private static void assertShape(
            PEATestUtils.RunResult run, Method target, Method requestDeopt)
            throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.PEARound first = report.round(0);
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();

        Asserts.assertEquals(before.peaAllocCount(), 12,
                target + ": exact logical object count enters PEA");
        Asserts.assertEquals(first.neverEscapes(), 12,
                target + ": complete identity graph never escapes");
        Asserts.assertEquals(first.partiallyEscapes(), 0,
                target + ": no identity-graph allocation partially escapes");
        Asserts.assertEquals(first.alwaysEscapes(), 0,
                target + ": no identity-graph allocation always escapes");
        Asserts.assertEquals(after.peaAllocCount(), 0,
                target + ": every graph allocation is eliminated");
        Asserts.assertEquals(run.finalIR(target).loweredAllocCount(), 0,
                target + ": no lowered allocation remains");

        String callee = PEATestUtils.MethodId.of(requestDeopt).llvmFunctionName();
        PEATestUtils.DeoptBundle source = before.deoptBundleAtCall(callee, 0);
        PEATestUtils.DeoptBundle reconstructed = after.deoptBundleAtCall(callee, 0);
        Asserts.assertEquals(source.virtualObjects().size(), 0,
                target + ": frontend call has no PEA descriptors");
        Asserts.assertEquals(source.rootScope().duplicateBCI(),
                source.rootScope().bci(),
                target + ": frontend helper call has duplicated BCI");
        Asserts.assertEquals(reconstructed.rootScope().bci(), source.rootScope().bci(),
                target + ": descriptor rewrite preserves helper-call BCI");
        Asserts.assertEquals(reconstructed.rootScope().duplicateBCI(),
                reconstructed.rootScope().bci(),
                target + ": descriptor rewrite preserves duplicate helper-call BCI");
        assertIdentityGraph(reconstructed, target);
    }

    private static void assertIdentityGraph(
            PEATestUtils.DeoptBundle bundle, Method target) throws Exception {
        bundle.assertVirtualObjectIds(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11);
        int leftOffset = offset("left");
        int rightOffset = offset("right");
        int payloadOffset = offset("payload");
        Map<Integer, List<PEATestUtils.VirtualObjectDescriptor>> byPayload =
                new HashMap<>();

        for (PEATestUtils.VirtualObjectDescriptor descriptor
                : bundle.virtualObjects().values()) {
            Asserts.assertEquals(descriptor.kind(),
                    PEATestUtils.DescriptorKind.INSTANCE,
                    target + ": every logical object is an instance");
            int payload = scalarPayload(descriptor, payloadOffset, target);
            byPayload.computeIfAbsent(payload, unused -> new ArrayList<>())
                    .add(descriptor);
        }

        for (int payload : List.of(11, 21, 22, 31, 32, 33, 41, 42, 43, 44)) {
            Asserts.assertEquals(byPayload.getOrDefault(payload, List.of()).size(), 1,
                    target + ": one descriptor for logical payload " + payload);
        }
        List<PEATestUtils.VirtualObjectDescriptor> equalState =
                byPayload.getOrDefault(55, List.of());
        Asserts.assertEquals(equalState.size(), 2,
                target + ": two equal-state but distinct descriptors");
        Asserts.assertNotEquals(equalState.get(0).id(), equalState.get(1).id(),
                target + ": equal state does not merge object identity");
        Asserts.assertEquals(byPayload.keySet(),
                Set.of(11, 21, 22, 31, 32, 33, 41, 42, 43, 44, 55),
                target + ": exact logical payload catalog");

        List<DescriptorEdge> actualEdges = new ArrayList<>();
        for (Map.Entry<Integer, List<PEATestUtils.VirtualObjectDescriptor>> group
                : byPayload.entrySet()) {
            for (PEATestUtils.VirtualObjectDescriptor descriptor : group.getValue()) {
                Set<Integer> expectedOffsets = expectedOffsets(
                        group.getKey(), leftOffset, rightOffset, payloadOffset);
                Asserts.assertEquals(descriptor.fields().keySet(), expectedOffsets,
                        target + ": exact descriptor fields for payload " + group.getKey());
                collectEdge(bundle, byPayload, actualEdges, descriptor,
                        group.getKey(), "left", leftOffset);
                collectEdge(bundle, byPayload, actualEdges, descriptor,
                        group.getKey(), "right", rightOffset);
            }
        }

        List<DescriptorEdge> expectedEdges = new ArrayList<>(List.of(
                new DescriptorEdge(11, "left", 11),
                new DescriptorEdge(21, "left", 22),
                new DescriptorEdge(22, "left", 21),
                new DescriptorEdge(31, "left", 32),
                new DescriptorEdge(32, "left", 33),
                new DescriptorEdge(33, "left", 31),
                new DescriptorEdge(41, "left", 42),
                new DescriptorEdge(41, "right", 43),
                new DescriptorEdge(42, "left", 44),
                new DescriptorEdge(43, "right", 44),
                new DescriptorEdge(55, "left", 44),
                new DescriptorEdge(55, "left", 44)));
        actualEdges.sort(DescriptorEdge.ORDER);
        expectedEdges.sort(DescriptorEdge.ORDER);
        Asserts.assertEquals(actualEdges, expectedEdges,
                target + ": exact order-independent identity-graph edges");

        PEATestUtils.VirtualObjectDescriptor shared =
                byPayload.get(44).get(0);
        long localAliases = voRefCount(bundle.rootScope().locals(), shared.id());
        long stackAliases = voRefCount(bundle.rootScope().stack(), shared.id());
        Asserts.assertTrue(localAliases >= 3,
                target + ": shared child has multiple live local aliases");
        Asserts.assertEquals(stackAliases, 2L,
                target + ": shared child has two live operand-stack aliases");
        Asserts.assertEquals(bundle.rootScope().stack().size(), 2,
                target + ": exact operand stack at no-argument deopt helper");
    }

    private static Set<Integer> expectedOffsets(
            int payload, int leftOffset, int rightOffset, int payloadOffset) {
        return switch (payload) {
            case 11, 21, 22, 31, 32, 33, 42, 55 ->
                    Set.of(payloadOffset, leftOffset);
            case 41 -> Set.of(payloadOffset, leftOffset, rightOffset);
            case 43 -> Set.of(payloadOffset, rightOffset);
            case 44 -> Set.of(payloadOffset);
            default -> throw new AssertionError("unexpected payload " + payload);
        };
    }

    private static void collectEdge(
            PEATestUtils.DeoptBundle bundle,
            Map<Integer, List<PEATestUtils.VirtualObjectDescriptor>> byPayload,
            List<DescriptorEdge> edges,
            PEATestUtils.VirtualObjectDescriptor owner,
            int ownerPayload, String field, int fieldOffset) {
        PEATestUtils.VirtualObjectEntry entry = owner.fields().get(fieldOffset);
        if (entry == null) {
            return;
        }
        Asserts.assertEquals(entry.basicType(), PEATestUtils.DeoptBasicType.OBJECT,
                "reference edge basic type");
        Asserts.assertEquals(entry.value().kind(), PEATestUtils.DeoptValueKind.VO_REF,
                "reference edge value kind");
        int targetId = entry.value().virtualObjectId();
        bundle.virtualObject(targetId);
        int targetPayload = payloadForId(byPayload, targetId);
        edges.add(new DescriptorEdge(ownerPayload, field, targetPayload));
    }

    private static int payloadForId(
            Map<Integer, List<PEATestUtils.VirtualObjectDescriptor>> byPayload,
            int id) {
        for (Map.Entry<Integer, List<PEATestUtils.VirtualObjectDescriptor>> entry
                : byPayload.entrySet()) {
            if (entry.getValue().stream()
                    .anyMatch(descriptor -> descriptor.id() == id)) {
                return entry.getKey();
            }
        }
        throw new AssertionError("edge targets unknown descriptor " + id);
    }

    private static int scalarPayload(
            PEATestUtils.VirtualObjectDescriptor descriptor,
            int payloadOffset, Method target) {
        PEATestUtils.VirtualObjectEntry payload =
                descriptor.fields().get(payloadOffset);
        Asserts.assertNotNull(payload,
                target + ": descriptor " + descriptor.id() + " has a payload");
        Asserts.assertEquals(payload.basicType(), PEATestUtils.DeoptBasicType.INT,
                target + ": payload basic type");
        Asserts.assertEquals(payload.value().kind(),
                PEATestUtils.DeoptValueKind.SCALAR,
                target + ": payload scalar kind");
        String operand = payload.value().operand();
        Asserts.assertTrue(operand.startsWith("i32 "),
                target + ": typed payload operand");
        return Integer.parseInt(operand.substring("i32 ".length()));
    }

    private static long voRefCount(
            Map<Integer, PEATestUtils.DeoptValue> values, int id) {
        return values.values().stream()
                .filter(value -> value.kind() == PEATestUtils.DeoptValueKind.VO_REF)
                .filter(value -> value.virtualObjectId() == id)
                .count();
    }

    private static int offset(String fieldName) throws Exception {
        Field field = TestWrapper.Node.class.getDeclaredField(fieldName);
        return Math.toIntExact(UNSAFE.objectFieldOffset(field));
    }

    private record DescriptorEdge(
            int ownerPayload, String field, int targetPayload) {
        private static final Comparator<DescriptorEdge> ORDER =
                Comparator.comparingInt(DescriptorEdge::ownerPayload)
                        .thenComparing(DescriptorEdge::field)
                        .thenComparingInt(DescriptorEdge::targetPayload);
    }

    public static class TestWrapper {
        private static final Method TARGET = target();

        public static void main(String[] args) throws Exception {
            new Node();
            PEATestUtils.compileConfiguredTargetsAtLevel4();
            long result = testIdentityGraph();
            if (result < 0) {
                throw new AssertionError("identity-graph reconstruction failed: " + result);
            }
            System.out.println("PEA-RESULT:" + Long.toUnsignedString(result, 16));
        }

        public static long testIdentityGraph() {
            Node self = new Node();
            self.payload = 11;
            self.left = self;

            Node pairA = new Node();
            pairA.payload = 21;
            Node pairB = new Node();
            pairB.payload = 22;
            pairA.left = pairB;
            pairB.left = pairA;

            Node threeA = new Node();
            threeA.payload = 31;
            Node threeB = new Node();
            threeB.payload = 32;
            Node threeC = new Node();
            threeC.payload = 33;
            threeA.left = threeB;
            threeB.left = threeC;
            threeC.left = threeA;

            Node diamondRoot = new Node();
            diamondRoot.payload = 41;
            Node diamondLeft = new Node();
            diamondLeft.payload = 42;
            Node diamondRight = new Node();
            diamondRight.payload = 43;
            Node shared = new Node();
            shared.payload = 44;
            diamondRoot.left = diamondLeft;
            diamondRoot.right = diamondRight;
            diamondLeft.left = shared;
            diamondRight.right = shared;

            Node equalOne = new Node();
            equalOne.payload = 55;
            Node equalTwo = new Node();
            equalTwo.payload = 55;
            equalOne.left = shared;
            equalTwo.left = shared;

            Node aliasOne = shared;
            Node aliasTwo = shared;
            int aliasToken = stackAliasToken(aliasOne, aliasTwo, requestDeopt());
            if (aliasToken != 73 || aliasOne != shared || aliasTwo != shared) {
                return Long.MIN_VALUE + 1;
            }
            if (!hasInitialTopology(
                    self, pairA, pairB, threeA, threeB, threeC,
                    diamondRoot, diamondLeft, diamondRight, shared,
                    equalOne, equalTwo)) {
                return Long.MIN_VALUE + 2;
            }
            if (!mutateAndReread(
                    self, pairA, pairB, threeA, threeB, threeC,
                    diamondRoot, diamondLeft, diamondRight, shared,
                    equalOne, equalTwo)) {
                return Long.MIN_VALUE + 3;
            }
            return reconstructedResult(
                    self, pairA, pairB, threeA, threeB, threeC,
                    diamondRoot, diamondLeft, diamondRight, shared,
                    equalOne, equalTwo);
        }

        private static int stackAliasToken(Node first, Node second, int token) {
            return first == second && first.payload == 44 ? token : -1;
        }

        private static boolean hasInitialTopology(
                Node self, Node pairA, Node pairB,
                Node threeA, Node threeB, Node threeC,
                Node diamondRoot, Node diamondLeft, Node diamondRight,
                Node shared, Node equalOne, Node equalTwo) {
            return self.left == self
                    && self.right == null
                    && pairA != pairB
                    && pairA.left == pairB && pairB.left == pairA
                    && pairA.right == null && pairB.right == null
                    && threeA != threeB && threeA != threeC && threeB != threeC
                    && threeA.left == threeB
                    && threeB.left == threeC
                    && threeC.left == threeA
                    && threeA.right == null
                    && threeB.right == null
                    && threeC.right == null
                    && diamondRoot.left == diamondLeft
                    && diamondRoot.right == diamondRight
                    && diamondLeft != diamondRight
                    && diamondLeft.left == shared
                    && diamondLeft.right == null
                    && diamondRight.left == null
                    && diamondRight.right == shared
                    && diamondLeft.left == diamondRight.right
                    && shared.left == null
                    && shared.right == null
                    && equalOne != equalTwo
                    && equalOne.payload == 55
                    && equalTwo.payload == 55
                    && equalOne.left == shared
                    && equalTwo.left == shared
                    && equalOne.right == null
                    && equalTwo.right == null
                    && self.payload == 11
                    && pairA.payload == 21 && pairB.payload == 22
                    && threeA.payload == 31
                    && threeB.payload == 32
                    && threeC.payload == 33
                    && diamondRoot.payload == 41
                    && diamondLeft.payload == 42
                    && diamondRight.payload == 43
                    && shared.payload == 44;
        }

        private static boolean mutateAndReread(
                Node self, Node pairA, Node pairB,
                Node threeA, Node threeB, Node threeC,
                Node diamondRoot, Node diamondLeft, Node diamondRight,
                Node shared, Node equalOne, Node equalTwo) {
            self.payload = 111;
            self.left = null;
            self.right = self;

            pairA.payload = 121;
            pairB.payload = 122;
            pairA.left = pairA;
            pairA.right = pairB;
            pairB.left = pairB;
            pairB.right = pairA;

            threeA.payload = 131;
            threeB.payload = 132;
            threeC.payload = 133;
            threeA.left = threeC;
            threeB.left = threeA;
            threeC.left = threeB;

            diamondRoot.payload = 141;
            diamondLeft.payload = 142;
            diamondRight.payload = 143;
            shared.payload = 144;
            diamondRoot.left = diamondRight;
            diamondRoot.right = diamondLeft;
            diamondLeft.left = null;
            diamondLeft.right = shared;
            diamondRight.left = shared;
            diamondRight.right = null;
            shared.left = diamondRoot;

            equalOne.payload = 155;
            equalTwo.payload = 255;
            equalOne.left = equalTwo;
            equalTwo.left = equalOne;
            equalOne.right = shared;
            equalTwo.right = shared;

            return self.left == null && self.right == self
                    && pairA.left == pairA && pairA.right == pairB
                    && pairB.left == pairB && pairB.right == pairA
                    && threeA.left == threeC
                    && threeB.left == threeA
                    && threeC.left == threeB
                    && diamondRoot.left == diamondRight
                    && diamondRoot.right == diamondLeft
                    && diamondLeft.left == null
                    && diamondLeft.right == shared
                    && diamondRight.left == shared
                    && diamondRight.right == null
                    && shared.left == diamondRoot
                    && equalOne != equalTwo
                    && equalOne.left == equalTwo
                    && equalTwo.left == equalOne
                    && equalOne.right == shared
                    && equalTwo.right == shared
                    && self.payload == 111
                    && pairA.payload == 121 && pairB.payload == 122
                    && threeA.payload == 131
                    && threeB.payload == 132
                    && threeC.payload == 133
                    && diamondRoot.payload == 141
                    && diamondLeft.payload == 142
                    && diamondRight.payload == 143
                    && shared.payload == 144
                    && equalOne.payload == 155
                    && equalTwo.payload == 255;
        }

        private static long reconstructedResult(
                Node self, Node pairA, Node pairB,
                Node threeA, Node threeB, Node threeC,
                Node diamondRoot, Node diamondLeft, Node diamondRight,
                Node shared, Node equalOne, Node equalTwo) {
            long result = 0x6A09E667F3BCC909L;
            result = mix(result, self.payload);
            result = mix(result, pairA.payload);
            result = mix(result, pairB.payload);
            result = mix(result, threeA.payload);
            result = mix(result, threeB.payload);
            result = mix(result, threeC.payload);
            result = mix(result, diamondRoot.payload);
            result = mix(result, diamondLeft.payload);
            result = mix(result, diamondRight.payload);
            result = mix(result, shared.payload);
            result = mix(result, equalOne.payload);
            result = mix(result, equalTwo.payload);
            result = mix(result, self.right == self ? 1 : 0);
            result = mix(result, pairA.right == pairB ? 2 : 0);
            result = mix(result, threeA.left.left.left == threeA ? 4 : 0);
            result = mix(result, diamondLeft.right == diamondRight.left ? 8 : 0);
            result = mix(result, shared.left == diamondRoot ? 16 : 0);
            result = mix(result, equalOne.left == equalTwo ? 32 : 0);
            result = mix(result, equalTwo.left == equalOne ? 64 : 0);
            return result & Long.MAX_VALUE;
        }

        private static int requestDeopt() {
            PEATestUtils.ActiveFrameDeoptEvidence evidence =
                    PEATestUtils.deoptimizeActiveFrame(TARGET, 2);
            if (!evidence.frameDeoptimized()
                    || evidence.compilationLevel() != 4
                    || evidence.markedNMethods() != 1) {
                throw new AssertionError("exact active-frame deopt evidence");
            }
            return 73;
        }

        private static Method target() {
            try {
                return TestWrapper.class.getMethod("testIdentityGraph");
            } catch (ReflectiveOperationException failure) {
                throw new ExceptionInInitializerError(failure);
            }
        }

        private static long mix(long accumulator, long value) {
            return Long.rotateLeft(accumulator ^ value, 17)
                    * 0x9E3779B97F4A7C15L;
        }

        public static class Node {
            public Node left;
            public Node right;
            public int payload;
        }
    }
}
