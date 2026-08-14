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
 * @summary PEA reconstructs object-array identity and cyclic topology at an
 *          exact active-frame deoptimization
 * @library /test/lib /
 * @modules java.base/jdk.internal.misc
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      -XX:-UseCompressedOops -XX:-UseCompressedClassPointers
 *      compiler.jeandle.pea.TestPEADeoptReconstructObjectArrays
 */

package compiler.jeandle.pea;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jdk.internal.misc.Unsafe;
import jdk.test.lib.Asserts;

public class TestPEADeoptReconstructObjectArrays {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestPEADeoptReconstructObjectArrays$TestWrapper";
    private static final Unsafe UNSAFE = Unsafe.getUnsafe();

    public static void main(String[] args) throws Exception {
        Method never = TestWrapper.class.getMethod(
                "testNever", Object.class, TestWrapper.Materialized.class);
        Method partialFalse = TestWrapper.class.getMethod(
                "testPartialFalse", boolean.class, Object.class,
                TestWrapper.Materialized.class);
        Method partialTrue = TestWrapper.class.getMethod(
                "testPartialTrue", boolean.class, Object.class,
                TestWrapper.Materialized.class);
        Method requestDeopt = TestWrapper.class.getDeclaredMethod("requestDeopt");
        Method sink = TestWrapper.class.getDeclaredMethod(
                "sink", Object[].class);
        Method observeInitialTopology = TestWrapper.class.getDeclaredMethod(
                "observeInitialTopology", Object[].class, TestWrapper.Child.class,
                TestWrapper.Cycle.class, Object.class,
                TestWrapper.Materialized.class);
        Method mutateAndObserve = TestWrapper.class.getDeclaredMethod(
                "mutateAndObserve", Object[].class, TestWrapper.Child.class,
                TestWrapper.Cycle.class, Object.class,
                TestWrapper.Materialized.class);
        Method mix = TestWrapper.class.getDeclaredMethod(
                "mix", long.class, long.class);
        Method[] targets = {never, partialFalse, partialTrue};
        Method[] inlineHelpers = {
                observeInitialTopology, mutateAndObserve, mix};

        runBuilder(false, targets, requestDeopt, sink, inlineHelpers)
                .runPEAOnOffEquivalent();

        try (PEATestUtils.RunResult run =
                runBuilder(true, targets, requestDeopt, sink, inlineHelpers).run()) {
            assertShape(run, never, requestDeopt, false);
            assertShape(run, partialFalse, requestDeopt, true);
            assertShape(run, partialTrue, requestDeopt, true);
        }
    }

    private static PEATestUtils.RunBuilder runBuilder(
            boolean shape, Method[] targets, Method requestDeopt, Method sink,
            Method[] inlineHelpers) {
        PEATestUtils.RunBuilder builder = shape
                ? PEATestUtils.shapeRun(WRAPPER, targets)
                : PEATestUtils.behaviorRun(WRAPPER, targets);
        builder.dontinline(requestDeopt).dontinline(sink);
        for (Method helper : inlineHelpers) {
            builder.inline(helper);
        }
        return builder;
    }

    private static void assertShape(
            PEATestUtils.RunResult run, Method target,
            Method requestDeopt, boolean partial) throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        report.assertFinalTransformIdle();
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();
        List<Integer> sourceBCIs = before.allocationBCIs();

        Asserts.assertEquals(sourceBCIs.size(), 3,
                target + ": array, child, and cycle enter PEA");
        Asserts.assertEquals(new HashSet<>(sourceBCIs).size(), 3,
                target + ": allocations have distinct source BCIs");

        PEATestUtils.PEARound firstRound = report.round(0);
        if (partial) {
            Asserts.assertEquals(firstRound.neverEscapes(), 0,
                    target + ": no allocation remains NeverEscapes");
            Asserts.assertEquals(firstRound.partiallyEscapes(), 3,
                    target + ": the complete array graph partially escapes");
            Asserts.assertEquals(new HashSet<>(after.allocationBCIs()),
                    new HashSet<>(sourceBCIs),
                    target + ": only source OrigAlloc allocations remain");
            Asserts.assertEquals(after.peaAllocCount(), 3,
                    target + ": exact retained PEA OrigAlloc count");
            Asserts.assertEquals(run.finalIR(target).loweredAllocCount(), 3,
                    target + ": exact retained lowered OrigAlloc count");
        } else {
            Asserts.assertEquals(firstRound.neverEscapes(), 3,
                    target + ": the complete array graph never escapes");
            Asserts.assertEquals(firstRound.partiallyEscapes(), 0,
                    target + ": no allocation partially escapes");
            Asserts.assertEquals(after.allocationBCIs(), List.of(),
                    target + ": every NeverEscapes allocation is eliminated");
            Asserts.assertEquals(after.peaAllocCount(), 0,
                    target + ": no PEA allocation remains");
            Asserts.assertEquals(run.finalIR(target).loweredAllocCount(), 0,
                    target + ": no lowered allocation remains");
        }
        Asserts.assertEquals(firstRound.alwaysEscapes(), 0,
                target + ": no allocation always escapes");

        String callee = PEATestUtils.MethodId.of(requestDeopt).llvmFunctionName();
        PEATestUtils.DeoptBundle bundle = after.deoptBundleAtCall(callee, 0);
        bundle.assertVirtualObjectIds(0, 1, 2);
        DescriptorGraph graph = identifyGraph(bundle);
        assertArrayDescriptor(bundle, graph);
        assertChildDescriptor(graph.child());
        assertCycleDescriptor(bundle, graph);
    }

    private static DescriptorGraph identifyGraph(
            PEATestUtils.DeoptBundle bundle) throws Exception {
        int childValueOffset = offset(TestWrapper.Child.class, "value");
        Set<Integer> cycleOffsets = Set.of(
                offset(TestWrapper.Cycle.class, "array"),
                offset(TestWrapper.Cycle.class, "marker"));
        PEATestUtils.VirtualObjectDescriptor array = null;
        PEATestUtils.VirtualObjectDescriptor child = null;
        PEATestUtils.VirtualObjectDescriptor cycle = null;

        for (PEATestUtils.VirtualObjectDescriptor descriptor
                : bundle.virtualObjects().values()) {
            if (descriptor.kind() == PEATestUtils.DescriptorKind.ARRAY) {
                Asserts.assertNull(array, "one logical object-array descriptor");
                array = descriptor;
            } else if (descriptor.fields().keySet().equals(
                    Set.of(childValueOffset))) {
                Asserts.assertNull(child, "one logical child descriptor");
                child = descriptor;
            } else if (descriptor.fields().keySet().equals(cycleOffsets)) {
                Asserts.assertNull(cycle, "one logical cycle descriptor");
                cycle = descriptor;
            } else {
                throw new AssertionError(
                        "unexpected logical object descriptor " + descriptor.id());
            }
        }

        Asserts.assertNotNull(array, "object-array descriptor");
        Asserts.assertNotNull(child, "child descriptor");
        Asserts.assertNotNull(cycle, "cycle descriptor");
        Asserts.assertNotEquals(child.klassOperand(), cycle.klassOperand(),
                "child and cycle use distinct klasses");
        return new DescriptorGraph(array, child, cycle);
    }

    private static void assertArrayDescriptor(
            PEATestUtils.DeoptBundle bundle, DescriptorGraph graph) {
        PEATestUtils.VirtualObjectDescriptor array = graph.array();
        Asserts.assertEquals(array.kind(), PEATestUtils.DescriptorKind.ARRAY,
                "object-array descriptor kind");
        int base = Unsafe.ARRAY_OBJECT_BASE_OFFSET;
        int scale = Unsafe.ARRAY_OBJECT_INDEX_SCALE;
        Set<Integer> offsets = Set.of(
                base, base + scale, base + 2 * scale,
                base + 3 * scale, base + 4 * scale, base + 5 * scale);
        Asserts.assertEquals(array.elements().keySet(), offsets,
                "exact object-array element offsets");

        assertElementKind(array, base, PEATestUtils.DeoptValueKind.NULL,
                "null element");
        assertElementKind(array, base + scale,
                PEATestUtils.DeoptValueKind.MATERIALIZED_OOP,
                "external oop element");
        bundle.assertVORef(array.id(), base + 2 * scale, graph.child().id());
        assertElementKind(array, base + 3 * scale,
                PEATestUtils.DeoptValueKind.MATERIALIZED_OOP,
                "already materialized sibling element");
        bundle.assertVORef(array.id(), base + 4 * scale, graph.child().id());
        bundle.assertVORef(array.id(), base + 5 * scale, graph.cycle().id());
    }

    private static void assertElementKind(
            PEATestUtils.VirtualObjectDescriptor array, int offset,
            PEATestUtils.DeoptValueKind kind, String detail) {
        PEATestUtils.VirtualObjectEntry element = array.elements().get(offset);
        Asserts.assertNotNull(element, "missing " + detail);
        Asserts.assertEquals(element.basicType(),
                PEATestUtils.DeoptBasicType.OBJECT, detail + " basic type");
        Asserts.assertEquals(element.value().kind(), kind, detail + " kind");
        Asserts.assertTrue(element.value().operand().startsWith("ptr "),
                detail + " typed operand");
    }

    private static void assertChildDescriptor(
            PEATestUtils.VirtualObjectDescriptor child) throws Exception {
        Asserts.assertEquals(child.kind(), PEATestUtils.DescriptorKind.INSTANCE,
                "child descriptor kind");
        int valueOffset = offset(TestWrapper.Child.class, "value");
        Asserts.assertEquals(child.fields().keySet(), Set.of(valueOffset),
                "child has one exact touched field");
        PEATestUtils.VirtualObjectEntry value = child.fields().get(valueOffset);
        Asserts.assertEquals(value.basicType(), PEATestUtils.DeoptBasicType.INT,
                "child value basic type");
        Asserts.assertEquals(value.value().kind(),
                PEATestUtils.DeoptValueKind.SCALAR, "child value scalar");
        Asserts.assertEquals(value.value().operand(), "i32 17",
                "child value operand");
    }

    private static void assertCycleDescriptor(
            PEATestUtils.DeoptBundle bundle, DescriptorGraph graph)
            throws Exception {
        PEATestUtils.VirtualObjectDescriptor cycle = graph.cycle();
        Asserts.assertEquals(cycle.kind(), PEATestUtils.DescriptorKind.INSTANCE,
                "cycle descriptor kind");
        int arrayOffset = offset(TestWrapper.Cycle.class, "array");
        int markerOffset = offset(TestWrapper.Cycle.class, "marker");
        Asserts.assertEquals(cycle.fields().keySet(),
                Set.of(arrayOffset, markerOffset),
                "cycle has exact owner and marker fields");
        bundle.assertVORef(cycle.id(), arrayOffset, graph.array().id());
        PEATestUtils.VirtualObjectEntry marker = cycle.fields().get(markerOffset);
        Asserts.assertEquals(marker.basicType(), PEATestUtils.DeoptBasicType.INT,
                "cycle marker basic type");
        Asserts.assertEquals(marker.value().kind(),
                PEATestUtils.DeoptValueKind.SCALAR, "cycle marker scalar");
        Asserts.assertEquals(marker.value().operand(), "i32 41",
                "cycle marker operand");
    }

    private static int offset(Class<?> holder, String name) throws Exception {
        Field field = holder.getDeclaredField(name);
        return Math.toIntExact(UNSAFE.objectFieldOffset(field));
    }

    private record DescriptorGraph(
            PEATestUtils.VirtualObjectDescriptor array,
            PEATestUtils.VirtualObjectDescriptor child,
            PEATestUtils.VirtualObjectDescriptor cycle) {}

    public static class TestWrapper {
        private static final long ESCAPE_MARK = 0x6A09E667F3BCC909L;

        private static final Method NEVER_TARGET = target(
                "testNever", Object.class, Materialized.class);
        private static final Method PARTIAL_FALSE_TARGET = target(
                "testPartialFalse", boolean.class, Object.class,
                Materialized.class);
        private static final Method PARTIAL_TRUE_TARGET = target(
                "testPartialTrue", boolean.class, Object.class,
                Materialized.class);

        private static Method deoptTarget;
        private static Object[] global;

        public static void main(String[] args) throws Exception {
            new Child();
            new Cycle();
            new Materialized();
            PEATestUtils.compileConfiguredTargetsAtLevel4();

            Object external = new Object();
            Materialized sibling = new Materialized();
            sibling.marker = 59;

            global = null;
            deoptTarget = NEVER_TARGET;
            long never = testNever(external, sibling);
            long expected = expectedResult();
            if (global != null || never != expected) {
                throw new AssertionError("never target escaped");
            }

            global = null;
            deoptTarget = PARTIAL_FALSE_TARGET;
            long partialFalse = testPartialFalse(false, external, sibling);
            if (global != null || partialFalse != expected) {
                throw new AssertionError("false partial branch escaped");
            }

            global = null;
            deoptTarget = PARTIAL_TRUE_TARGET;
            long partialTrue = testPartialTrue(true, external, sibling);
            if (global == null || partialTrue != (partialFalse ^ ESCAPE_MARK)) {
                throw new AssertionError("true partial branch was not observed");
            }
            if (never != partialFalse) {
                throw new AssertionError("equivalent reconstructed state differs");
            }

            long payload = mix(mix(never, partialFalse), partialTrue);
            System.out.println("PEA-RESULT:" + Long.toUnsignedString(payload, 16));
        }

        public static long testNever(Object external, Materialized sibling) {
            Object[] array = new Object[6];
            Child child = new Child();
            Cycle cycle = new Cycle();
            child.value = 17;
            cycle.array = array;
            cycle.marker = 41;
            array[0] = null;
            array[1] = external;
            array[2] = child;
            array[3] = sibling;
            array[4] = child;
            array[5] = cycle;

            requestDeopt();

            long initial =
                    observeInitialTopology(array, child, cycle, external, sibling);
            long mutated = mutateAndObserve(
                    array, child, cycle, external, sibling);
            return mix(initial, mutated);
        }

        public static long testPartialFalse(
                boolean escape, Object external, Materialized sibling) {
            Object[] array = new Object[6];
            Child child = new Child();
            Cycle cycle = new Cycle();
            child.value = 17;
            cycle.array = array;
            cycle.marker = 41;
            array[0] = null;
            array[1] = external;
            array[2] = child;
            array[3] = sibling;
            array[4] = child;
            array[5] = cycle;

            requestDeopt();
            if (escape) {
                sink(array);
            }

            long initial =
                    observeInitialTopology(array, child, cycle, external, sibling);
            long mutated = mutateAndObserve(
                    array, child, cycle, external, sibling);
            return mix(initial, mutated) ^ (escape ? ESCAPE_MARK : 0L);
        }

        public static long testPartialTrue(
                boolean escape, Object external, Materialized sibling) {
            Object[] array = new Object[6];
            Child child = new Child();
            Cycle cycle = new Cycle();
            child.value = 17;
            cycle.array = array;
            cycle.marker = 41;
            array[0] = null;
            array[1] = external;
            array[2] = child;
            array[3] = sibling;
            array[4] = child;
            array[5] = cycle;

            requestDeopt();
            if (escape) {
                sink(array);
            }

            long initial =
                    observeInitialTopology(array, child, cycle, external, sibling);
            long mutated = mutateAndObserve(
                    array, child, cycle, external, sibling);
            return mix(initial, mutated) ^ (escape ? ESCAPE_MARK : 0L);
        }

        private static long observeInitialTopology(
                Object[] array, Child child, Cycle cycle,
                Object external, Materialized sibling) {
            long result = array.length;
            result = mix(result, child.value);
            result = mix(result, cycle.marker);
            result = mix(result, sibling.marker);
            result = mix(result, array[0] == null ? 1 : 0);
            result = mix(result, array[1] == external ? 2 : 0);
            result = mix(result, array[2] == child ? 4 : 0);
            result = mix(result, array[3] == sibling ? 8 : 0);
            result = mix(result, array[4] == child ? 16 : 0);
            result = mix(result, array[2] == array[4] ? 32 : 0);
            result = mix(result, array[5] == cycle ? 64 : 0);
            result = mix(result, cycle.array == array ? 128 : 0);
            return mix(result, external != sibling ? 256 : 0);
        }

        private static long mutateAndObserve(
                Object[] array, Child child, Cycle cycle,
                Object external, Materialized sibling) {
            array[0] = sibling;
            array[1] = child;
            array[2] = cycle;
            array[3] = external;
            array[4] = array;
            array[5] = child;
            child.value = 83;
            cycle.marker = 97;
            cycle.array = null;
            long result = child.value;
            result = mix(result, cycle.marker);
            result = mix(result, sibling.marker);
            result = mix(result, array[0] == sibling ? 1 : 0);
            result = mix(result, array[1] == child ? 2 : 0);
            result = mix(result, array[2] == cycle ? 4 : 0);
            result = mix(result, array[3] == external ? 8 : 0);
            result = mix(result, array[4] == array ? 16 : 0);
            result = mix(result, array[5] == child ? 32 : 0);
            result = mix(result, cycle.array == null ? 64 : 0);
            cycle.array = array;
            result = mix(result, cycle.array == array ? 128 : 0);
            return mix(result, array[4] == cycle.array ? 256 : 0);
        }

        private static long expectedResult() {
            long initial = 6;
            initial = mix(initial, 17);
            initial = mix(initial, 41);
            initial = mix(initial, 59);
            for (long value : new long[] {1, 2, 4, 8, 16, 32, 64, 128, 256}) {
                initial = mix(initial, value);
            }
            long mutated = 83;
            mutated = mix(mutated, 97);
            mutated = mix(mutated, 59);
            for (long value : new long[] {1, 2, 4, 8, 16, 32, 64, 128, 256}) {
                mutated = mix(mutated, value);
            }
            return mix(initial, mutated);
        }

        private static void requestDeopt() {
            PEATestUtils.ActiveFrameDeoptEvidence evidence =
                    PEATestUtils.deoptimizeActiveFrame(deoptTarget, 2);
            if (!evidence.frameDeoptimized()
                    || evidence.compilationLevel() != 4
                    || evidence.markedNMethods() != 1) {
                throw new AssertionError("exact active-frame deopt evidence");
            }
        }

        private static void sink(Object[] value) {
            global = value;
        }

        private static Method target(String name, Class<?>... parameterTypes) {
            try {
                return TestWrapper.class.getMethod(name, parameterTypes);
            } catch (ReflectiveOperationException failure) {
                throw new ExceptionInInitializerError(failure);
            }
        }

        private static long mix(long accumulator, long value) {
            return Long.rotateLeft(accumulator ^ value, 17)
                    * 0x9E3779B97F4A7C15L;
        }

        public static class Child {
            public int value;
        }

        public static class Cycle {
            public Object[] array;
            public int marker;
        }

        public static class Materialized {
            public int marker;
        }
    }
}
