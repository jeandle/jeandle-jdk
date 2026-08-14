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
 * You should have received a copy of the GNU General Public License
 * along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 */

/*
 * @test
 * @summary PEA allocation invokes describe live virtual state in their own deopt bundles
 * @library /test/lib /
 * @modules java.base/jdk.internal.misc
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      -XX:-UseCompressedOops -XX:-UseCompressedClassPointers
 *      compiler.jeandle.pea.TestPEADeoptAtAllocation
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jdk.internal.misc.Unsafe;
import jdk.test.lib.Asserts;

/** Structural Stage A coverage for allocation-invoke frame state only. */
public class TestPEADeoptAtAllocation {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestPEADeoptAtAllocation$TestWrapper";
    private static final Unsafe UNSAFE = Unsafe.getUnsafe();

    private enum Scenario {
        INSTANCE("instanceFirst", 1, 1, false),
        PRIMITIVE_ARRAY("primitiveArrayFirst", 1, 1, false),
        OBJECT_ARRAY("objectArrayFirst", 1, 1, false),
        NESTED_GRAPH("nestedGraphFirst", 2, 2, false),
        PARTIAL_FIRST("partialFirst", 1, 1, true),
        LOCK_OWNER("lockOwnerFirst", 1, 1, false);

        private final String methodName;
        private final int firstAllocationCount;
        private final int descriptorCount;
        private final boolean retainFirstOrigAlloc;

        Scenario(String methodName, int firstAllocationCount, int descriptorCount,
                 boolean retainFirstOrigAlloc) {
            this.methodName = methodName;
            this.firstAllocationCount = firstAllocationCount;
            this.descriptorCount = descriptorCount;
            this.retainFirstOrigAlloc = retainFirstOrigAlloc;
        }
    }

    public static void main(String[] args) throws Exception {
        for (Scenario scenario : Scenario.values()) {
            Method target = TestWrapper.class.getMethod(scenario.methodName, Object.class);
            try (PEATestUtils.RunResult run = PEATestUtils.shapeRun(WRAPPER, target)
                    .dontinline(TestWrapper.class.getDeclaredMethod("retain", Object.class))
                    .run()) {
                assertAllocationShape(run, target, scenario);
            }
        }
    }

    private static void assertAllocationShape(
            PEATestUtils.RunResult run, Method target, Scenario scenario) throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        report.assertFinalTransformIdle();
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();
        List<Integer> sourceBCIs = before.allocationBCIs();
        Asserts.assertEquals(sourceBCIs.size(), scenario.firstAllocationCount + 1,
                target + ": first virtual allocations and one second allocation");
        Asserts.assertEquals(new HashSet<>(sourceBCIs).size(), sourceBCIs.size(),
                target + ": every source allocation has a distinct BCI");

        int secondBCI = sourceBCIs.get(sourceBCIs.size() - 1);
        String secondResult = exactAllocationResult(after, secondBCI, target);
        assertSecondAllocationCallee(after, secondResult, target);
        PEATestUtils.DeoptBundle bundle = after.deoptBundleAtAllocation(secondResult);
        Asserts.assertEquals(bundle.rootScope().bci(), secondBCI,
                target + ": selected second allocation has its source BCI");
        Asserts.assertEquals(bundle.rootScope().duplicateBCI(), secondBCI,
                target + ": selected second allocation duplicates only its own BCI");
        Asserts.assertEquals(bundle.scopes().size(), 1,
                target + ": one active Java scope at the second allocation");
        Asserts.assertEquals(bundle.virtualObjects().size(), scenario.descriptorCount,
                target + ": exact live virtual-object closure at the second allocation");

        List<Integer> expectedRetained = scenario.retainFirstOrigAlloc
                ? List.of(sourceBCIs.get(0), secondBCI) : List.of(secondBCI);
        Asserts.assertEquals(after.allocationBCIs(), expectedRetained,
                target + ": only required source OrigAllocs remain");
        Asserts.assertEquals(after.peaAllocCount(), expectedRetained.size(),
                target + ": no replacement allocation is introduced");
        Asserts.assertEquals(run.finalIR(target).loweredAllocCount(), expectedRetained.size(),
                target + ": final code retains exactly the source OrigAllocs");

        switch (scenario) {
            case INSTANCE -> assertInstance(bundle, target, 17);
            case PRIMITIVE_ARRAY -> assertPrimitiveArray(bundle, target);
            case OBJECT_ARRAY -> assertObjectArray(bundle, target);
            case NESTED_GRAPH -> assertNestedGraph(bundle, target);
            case PARTIAL_FIRST -> assertInstance(bundle, target, 61);
            case LOCK_OWNER -> assertLockOwner(bundle, target);
        }
        assertRootClosure(bundle, target, scenario);
    }

    private static String exactAllocationResult(
            PEATestUtils.IRBody body, int bci, Method target) {
        List<String> matches = body.allocationBCIsByResult().entrySet().stream()
                .filter(entry -> entry.getValue() == bci)
                .map(Map.Entry::getKey).toList();
        Asserts.assertEquals(matches.size(), 1,
                target + ": exact SSA result for second allocation BCI " + bci);
        return matches.get(0);
    }

    private static void assertSecondAllocationCallee(
            PEATestUtils.IRBody body, String result, Method target) {
        List<String> lines = body.lines().stream()
                .filter(line -> line.startsWith(result + " = ")).toList();
        Asserts.assertEquals(lines.size(), 1, target + ": one second allocation SSA definition");
        Asserts.assertTrue(lines.get(0).contains("@jeandle.new_instance"),
                target + ": exact selected allocation callee is jeandle.new_instance");
    }

    private static void assertInstance(
            PEATestUtils.DeoptBundle bundle, Method target, int expectedValue)
            throws Exception {
        bundle.assertVirtualObjectIds(0);
        PEATestUtils.VirtualObjectDescriptor descriptor = bundle.virtualObject(0);
        Asserts.assertEquals(descriptor.kind(), PEATestUtils.DescriptorKind.INSTANCE,
                target + ": first object is an instance descriptor");
        int valueOffset = offset(TestWrapper.Cell.class, "value");
        Asserts.assertEquals(descriptor.fields().keySet(), Set.of(valueOffset),
                target + ": instance descriptor has one exact field");
        PEATestUtils.VirtualObjectEntry value = descriptor.fields().get(valueOffset);
        Asserts.assertNotNull(value, target + ": instance value is in closure");
        Asserts.assertEquals(value.basicType(), PEATestUtils.DeoptBasicType.INT,
                target + ": instance value type");
        Asserts.assertEquals(value.value().kind(), PEATestUtils.DeoptValueKind.SCALAR,
                target + ": instance value is scalar");
        Asserts.assertEquals(value.value().operand(), "i32 " + expectedValue,
                target + ": instance value is exact");
    }

    private static void assertPrimitiveArray(PEATestUtils.DeoptBundle bundle, Method target) {
        bundle.assertVirtualObjectIds(0);
        PEATestUtils.VirtualObjectDescriptor descriptor = bundle.virtualObject(0);
        Asserts.assertEquals(descriptor.kind(), PEATestUtils.DescriptorKind.ARRAY,
                target + ": first object is a primitive-array descriptor");
        int base = Unsafe.ARRAY_INT_BASE_OFFSET;
        int second = base + Unsafe.ARRAY_INT_INDEX_SCALE;
        Asserts.assertEquals(descriptor.elements().keySet(), Set.of(base, second),
                target + ": primitive array has exact initialized byte offsets");
        assertScalar(descriptor.elements().get(base), 31,
                target + ": primitive element zero");
        assertScalar(descriptor.elements().get(second), 37,
                target + ": primitive element one");
    }

    private static void assertObjectArray(PEATestUtils.DeoptBundle bundle, Method target) {
        bundle.assertVirtualObjectIds(0);
        PEATestUtils.VirtualObjectDescriptor descriptor = bundle.virtualObject(0);
        Asserts.assertEquals(descriptor.kind(), PEATestUtils.DescriptorKind.ARRAY,
                target + ": first object is an object-array descriptor");
        int base = Unsafe.ARRAY_OBJECT_BASE_OFFSET;
        Asserts.assertEquals(descriptor.elements().keySet(), Set.of(base),
                target + ": object array has one exact initialized byte offset");
        PEATestUtils.VirtualObjectEntry entry = descriptor.elements().get(base);
        Asserts.assertEquals(entry.basicType(), PEATestUtils.DeoptBasicType.OBJECT,
                target + ": object array element type");
        Asserts.assertEquals(entry.value().kind(), PEATestUtils.DeoptValueKind.MATERIALIZED_OOP,
                target + ": external array element remains materialized");
        PEATestUtils.DeoptValue external = bundle.rootScope().locals().get(0);
        Asserts.assertNotNull(external, target + ": external argument root local");
        Asserts.assertEquals(external.kind(), PEATestUtils.DeoptValueKind.MATERIALIZED_OOP,
                target + ": external argument root kind");
        Asserts.assertEquals(entry.value().operand(), external.operand(),
                target + ": object-array element points to the external root");
    }

    private static void assertNestedGraph(PEATestUtils.DeoptBundle bundle, Method target)
            throws Exception {
        bundle.assertVirtualObjectIds(0, 1);
        int nextOffset = offset(TestWrapper.Node.class, "next");
        int valueOffset = offset(TestWrapper.Node.class, "value");
        PEATestUtils.VirtualObjectDescriptor root = bundle.virtualObject(0);
        PEATestUtils.VirtualObjectDescriptor child = bundle.virtualObject(1);
        Asserts.assertEquals(root.kind(), PEATestUtils.DescriptorKind.INSTANCE,
                target + ": root descriptor kind");
        Asserts.assertEquals(root.fields().keySet(), Set.of(valueOffset, nextOffset),
                target + ": root field offsets");
        assertScalar(root.fields().get(valueOffset), 47, target + ": root value");
        bundle.assertVORef(root.id(), nextOffset, child.id());
        Asserts.assertEquals(child.kind(), PEATestUtils.DescriptorKind.INSTANCE,
                target + ": child descriptor kind");
        Asserts.assertEquals(child.fields().keySet(), Set.of(valueOffset),
                target + ": child field offsets");
        assertScalar(child.fields().get(valueOffset), 53, target + ": child value");
    }

    private static void assertLockOwner(PEATestUtils.DeoptBundle bundle, Method target)
            throws Exception {
        bundle.assertVirtualObjectIds(0);
        PEATestUtils.VirtualObjectDescriptor descriptor = bundle.virtualObject(0);
        Asserts.assertEquals(descriptor.kind(), PEATestUtils.DescriptorKind.INSTANCE,
                target + ": virtual lock owner descriptor kind");
        int valueOffset = offset(TestWrapper.LockState.class, "value");
        Asserts.assertEquals(descriptor.fields().keySet(), Set.of(valueOffset),
                target + ": virtual lock owner field offsets");
        assertScalar(descriptor.fields().get(valueOffset), 71,
                target + ": virtual lock owner value");
        Asserts.assertEquals(bundle.rootScope().monitors().size(), 1,
                target + ": one virtual monitor is live at the second allocation");
        PEATestUtils.DeoptMonitor monitor = bundle.rootScope().monitors().get(0);
        Asserts.assertTrue(monitor.eliminated(), target + ": monitor remains virtual");
        Asserts.assertEquals(monitor.owner().kind(), PEATestUtils.DeoptValueKind.VO_REF,
                target + ": monitor owner is a virtual-object reference");
        Asserts.assertEquals(monitor.owner().virtualObjectId(), descriptor.id(),
                target + ": monitor owner is within descriptor closure");
    }

    private static void assertRootClosure(
            PEATestUtils.DeoptBundle bundle, Method target, Scenario scenario) {
        Map<Integer, Integer> expectedLocals = switch (scenario) {
            case INSTANCE, PRIMITIVE_ARRAY, OBJECT_ARRAY, NESTED_GRAPH,
                    PARTIAL_FIRST -> Map.of(1, 0);
            case LOCK_OWNER -> Map.of(1, 0, 2, 0);
        };
        Map<Integer, Integer> expectedMonitors = scenario == Scenario.LOCK_OWNER
                ? Map.of(0, 0) : Map.of();
        assertRootVORefs(bundle.rootScope().locals(), expectedLocals,
                target + ": root local VORefs");
        assertRootVORefs(bundle.rootScope().stack(), Map.of(),
                target + ": root stack VORefs");
        Map<Integer, Integer> actualMonitors = bundle.rootScope().monitors().stream()
                .filter(monitor -> monitor.owner().kind() == PEATestUtils.DeoptValueKind.VO_REF)
                .collect(java.util.stream.Collectors.toMap(
                        PEATestUtils.DeoptMonitor::depth,
                        monitor -> monitor.owner().virtualObjectId()));
        Asserts.assertEquals(actualMonitors, expectedMonitors,
                target + ": root monitor VORefs");

        Set<Integer> reached = new HashSet<>();
        List<Integer> work = new ArrayList<>();
        expectedLocals.values().forEach(id -> { if (reached.add(id)) work.add(id); });
        expectedMonitors.values().forEach(id -> { if (reached.add(id)) work.add(id); });
        for (int at = 0; at < work.size(); at++) {
            PEATestUtils.VirtualObjectDescriptor descriptor = bundle.virtualObject(work.get(at));
            for (PEATestUtils.VirtualObjectEntry entry : descriptor.entries().values()) {
                if (entry.value().kind() == PEATestUtils.DeoptValueKind.VO_REF
                        && reached.add(entry.value().virtualObjectId())) {
                    work.add(entry.value().virtualObjectId());
                }
            }
        }
        Asserts.assertEquals(reached, bundle.virtualObjects().keySet(),
                target + ": every descriptor is transitively rooted by the allocation state");
    }

    private static void assertRootVORefs(
            Map<Integer, PEATestUtils.DeoptValue> values, Map<Integer, Integer> expected,
            String message) {
        Map<Integer, Integer> actual = values.entrySet().stream()
                .filter(entry -> entry.getValue().kind() == PEATestUtils.DeoptValueKind.VO_REF)
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey, entry -> entry.getValue().virtualObjectId()));
        Asserts.assertEquals(actual, expected, message);
    }

    private static void assertScalar(
            PEATestUtils.VirtualObjectEntry entry, int expected, String message) {
        Asserts.assertNotNull(entry, message + " exists");
        Asserts.assertEquals(entry.basicType(), PEATestUtils.DeoptBasicType.INT,
                message + " type");
        Asserts.assertEquals(entry.value().kind(), PEATestUtils.DeoptValueKind.SCALAR,
                message + " kind");
        Asserts.assertEquals(entry.value().operand(), "i32 " + expected,
                message + " value");
    }

    private static int offset(Class<?> holder, String field) throws Exception {
        return Math.toIntExact(UNSAFE.objectFieldOffset(holder.getDeclaredField(field)));
    }

    public static class TestWrapper {
        private static Object escaped;

        public static class Cell { int value; }
        public static class Node { int value; Node next; }
        public static class LockState { int value; }
        public static class Retained { int value; }

        public static void main(String[] args) throws Exception {
            new Cell(); new Node(); new LockState(); new Retained();
            PEATestUtils.compileConfiguredTargetsAtLevel4();
            Object external = new Object();
            int result = instanceFirst(external)
                    + primitiveArrayFirst(external)
                    + objectArrayFirst(external)
                    + nestedGraphFirst(external)
                    + partialFirst(external)
                    + lockOwnerFirst(external);
            if (result != 620) {
                throw new AssertionError("allocation bundle result " + result);
            }
            System.out.println("PEA-RESULT:" + result);
        }

        public static int instanceFirst(Object external) {
            Cell first = new Cell();
            first.value = 17;
            Retained second = new Retained();
            second.value = 19;
            retain(second);
            return first.value + second.value + (external == null ? 1 : 0);
        }

        public static int primitiveArrayFirst(Object external) {
            int[] first = new int[2];
            first[0] = 31;
            first[1] = 37;
            Retained second = new Retained();
            second.value = 41;
            retain(second);
            return first[0] + first[1] + second.value + (external == null ? 1 : 0);
        }

        public static int objectArrayFirst(Object external) {
            Object[] first = new Object[1];
            first[0] = external;
            Retained second = new Retained();
            second.value = 43;
            retain(second);
            return (first[0] == external ? 1 : 0) + second.value;
        }

        public static int nestedGraphFirst(Object external) {
            Node root = new Node();
            Node child = new Node();
            root.value = 47;
            child.value = 53;
            root.next = child;
            Retained second = new Retained();
            second.value = 59;
            retain(second);
            return root.value + root.next.value + second.value + (external == null ? 1 : 0);
        }

        public static int partialFirst(Object external) {
            Cell first = new Cell();
            first.value = 61;
            Retained second = new Retained();
            second.value = 67;
            retain(second);
            retain(first);
            return first.value + second.value + (external == null ? 1 : 0);
        }

        public static int lockOwnerFirst(Object external) {
            LockState first = new LockState();
            synchronized (first) {
                first.value = 71;
                Retained second = new Retained();
                second.value = 73;
                retain(second);
                return first.value + second.value + (external == null ? 1 : 0);
            }
        }

        private static void retain(Object object) {
            escaped = object;
        }
    }
}
