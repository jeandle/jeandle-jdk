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
 * @summary PEA reconstructs complete inherited instance state and identity at
 *          an exact active-frame deoptimization
 * @library /test/lib /
 * @modules java.base/jdk.internal.misc
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      -XX:-UseCompressedOops -XX:-UseCompressedClassPointers
 *      compiler.jeandle.pea.TestPEADeoptReconstructInstance
 */

package compiler.jeandle.pea;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jdk.internal.misc.Unsafe;
import jdk.test.lib.Asserts;

public class TestPEADeoptReconstructInstance {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestPEADeoptReconstructInstance$TestWrapper";
    private static final Unsafe UNSAFE = Unsafe.getUnsafe();

    public static void main(String[] args) throws Exception {
        Method never = TestWrapper.class.getMethod(
                "testNever", Object.class, float.class, double.class);
        Method partialFalse = TestWrapper.class.getMethod(
                "testPartialFalse", boolean.class, Object.class,
                float.class, double.class);
        Method partialTrue = TestWrapper.class.getMethod(
                "testPartialTrue", boolean.class, Object.class,
                float.class, double.class);
        Method requestDeopt = TestWrapper.class.getDeclaredMethod("requestDeopt");
        Method sink = TestWrapper.class.getDeclaredMethod(
                "sink", TestWrapper.Value.class);
        Method initialStateFailure = TestWrapper.class.getDeclaredMethod(
                "initialStateFailure", TestWrapper.Value.class,
                TestWrapper.Value.class, TestWrapper.Holder.class,
                Object.class, float.class, double.class);
        Method hasInitialFields = TestWrapper.class.getDeclaredMethod(
                "hasInitialFields", TestWrapper.Value.class,
                Object.class, float.class, double.class);
        Method defaultFieldsFailure = TestWrapper.class.getDeclaredMethod(
                "defaultFieldsFailure", TestWrapper.Value.class);
        Method mutateAndReread = TestWrapper.class.getDeclaredMethod(
                "mutateAndReread", TestWrapper.Value.class,
                TestWrapper.Value.class, TestWrapper.Holder.class);
        Method reconstructedResult = TestWrapper.class.getDeclaredMethod(
                "reconstructedResult", TestWrapper.Value.class,
                TestWrapper.Value.class);
        Method mix = TestWrapper.class.getDeclaredMethod(
                "mix", long.class, long.class);
        Method[] targets = {never, partialFalse, partialTrue};
        Method[] inlineHelpers = {
                initialStateFailure, hasInitialFields, defaultFieldsFailure,
                mutateAndReread, reconstructedResult, mix};

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
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();
        List<Integer> sourceBCIs = before.allocationBCIs();

        Asserts.assertEquals(sourceBCIs.size(), 3,
                target + ": two values and one holder enter PEA");
        Asserts.assertEquals(new HashSet<>(sourceBCIs).size(), 3,
                target + ": allocations have distinct source BCIs");

        PEATestUtils.PEARound firstRound = report.round(0);
        if (partial) {
            Asserts.assertEquals(firstRound.neverEscapes(), 0,
                    target + ": no member of the published graph remains NeverEscapes");
            Asserts.assertEquals(firstRound.partiallyEscapes(), 3,
                    target + ": reference mutation publishes the complete graph");
            Asserts.assertEquals(new HashSet<>(after.allocationBCIs()),
                    new HashSet<>(sourceBCIs),
                    target + ": partial graph reuses all three OrigAllocs");
            Asserts.assertEquals(after.peaAllocCount(), 3,
                    target + ": exact retained PEA allocation count");
            Asserts.assertEquals(run.finalIR(target).loweredAllocCount(), 3,
                    target + ": exact retained lowered OrigAlloc count");
        } else {
            Asserts.assertEquals(firstRound.neverEscapes(), 3,
                    target + ": complete graph never escapes");
            Asserts.assertEquals(firstRound.partiallyEscapes(), 0,
                    target + ": no partial allocations");
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
        assertValueDescriptor(bundle, 0, 0, 2);
        assertValueDescriptor(bundle, 1, 1, 2);
        assertHolderDescriptor(bundle.virtualObject(2));

        PEATestUtils.VirtualObjectDescriptor first = bundle.virtualObject(0);
        PEATestUtils.VirtualObjectDescriptor second = bundle.virtualObject(1);
        Asserts.assertEquals(first.klassOperand(), second.klassOperand(),
                target + ": equal-state values use the same klass");
        Asserts.assertNotEquals(first.klassOperand(),
                bundle.virtualObject(2).klassOperand(),
                target + ": holder has a distinct klass");

        assertSameScalarState(first, second, target);
        Set<Integer> rootedIds = new HashSet<>();
        collectVORefs(rootedIds, bundle.rootScope().locals());
        collectVORefs(rootedIds, bundle.rootScope().stack());
        Asserts.assertTrue(rootedIds.containsAll(Set.of(0, 1)),
                target + ": both equal-state values are live in the target frame");
    }

    private static void assertValueDescriptor(
            PEATestUtils.DeoptBundle bundle, int id, int selfId, int holderId)
            throws Exception {
        PEATestUtils.VirtualObjectDescriptor value = bundle.virtualObject(id);
        Asserts.assertEquals(value.kind(), PEATestUtils.DescriptorKind.INSTANCE,
                "value descriptor kind");

        Set<Integer> expectedOffsets = Set.of(
                offset(TestWrapper.Base.class, "z"),
                offset(TestWrapper.Base.class, "b"),
                offset(TestWrapper.Base.class, "s"),
                offset(TestWrapper.Base.class, "c"),
                offset(TestWrapper.Base.class, "i"),
                offset(TestWrapper.Base.class, "l"),
                offset(TestWrapper.Base.class, "f"),
                offset(TestWrapper.Base.class, "d"),
                offset(TestWrapper.Base.class, "ref"),
                offset(TestWrapper.Value.class, "aliasOne"),
                offset(TestWrapper.Value.class, "aliasTwo"),
                offset(TestWrapper.Value.class, "holder"));
        Asserts.assertEquals(value.fields().keySet(), expectedOffsets,
                "exact touched-field descriptor topology for VO " + id);

        assertBooleanScalar(value, TestWrapper.Base.class, "z", true);
        assertScalar(value, TestWrapper.Base.class, "b",
                PEATestUtils.DeoptBasicType.INT, "i8 -37");
        assertScalar(value, TestWrapper.Base.class, "s",
                PEATestUtils.DeoptBasicType.INT, "i16 -12345");
        assertScalar(value, TestWrapper.Base.class, "c",
                PEATestUtils.DeoptBasicType.INT, "i16 23100");
        assertScalar(value, TestWrapper.Base.class, "i",
                PEATestUtils.DeoptBasicType.INT, "i32 324508639");
        assertScalar(value, TestWrapper.Base.class, "l",
                PEATestUtils.DeoptBasicType.LONG,
                "i64 1311768467463790320");
        assertScalarPrefix(value, TestWrapper.Base.class, "f",
                PEATestUtils.DeoptBasicType.FLOAT, "float ");
        assertScalarPrefix(value, TestWrapper.Base.class, "d",
                PEATestUtils.DeoptBasicType.DOUBLE, "double ");
        assertMaterializedOop(value, TestWrapper.Base.class, "ref");

        bundle.assertVORef(id, offset(TestWrapper.Value.class, "aliasOne"), selfId);
        bundle.assertVORef(id, offset(TestWrapper.Value.class, "aliasTwo"), selfId);
        bundle.assertVORef(id, offset(TestWrapper.Value.class, "holder"), holderId);

        assertAbsent(value, TestWrapper.Base.class, "defaultZ");
        assertAbsent(value, TestWrapper.Base.class, "defaultB");
        assertAbsent(value, TestWrapper.Base.class, "defaultS");
        assertAbsent(value, TestWrapper.Base.class, "defaultC");
        assertAbsent(value, TestWrapper.Base.class, "defaultI");
        assertAbsent(value, TestWrapper.Base.class, "defaultL");
        assertAbsent(value, TestWrapper.Base.class, "defaultF");
        assertAbsent(value, TestWrapper.Base.class, "defaultD");
        assertAbsent(value, TestWrapper.Base.class, "defaultRef");
        assertAbsent(value, TestWrapper.Value.class, "mutation");
    }

    private static void assertHolderDescriptor(
            PEATestUtils.VirtualObjectDescriptor holder) throws Exception {
        Asserts.assertEquals(holder.kind(), PEATestUtils.DescriptorKind.INSTANCE,
                "holder descriptor kind");
        int markerOffset = offset(TestWrapper.Holder.class, "marker");
        Asserts.assertEquals(holder.fields().keySet(), Set.of(markerOffset),
                "holder has one exact touched field");
        PEATestUtils.VirtualObjectEntry marker = holder.fields().get(markerOffset);
        Asserts.assertEquals(marker.basicType(), PEATestUtils.DeoptBasicType.INT);
        Asserts.assertEquals(marker.value().kind(),
                PEATestUtils.DeoptValueKind.SCALAR);
        Asserts.assertEquals(marker.value().operand(), "i32 73");
    }

    private static void assertSameScalarState(
            PEATestUtils.VirtualObjectDescriptor first,
            PEATestUtils.VirtualObjectDescriptor second, Method target)
            throws Exception {
        for (String field : List.of("z", "b", "s", "c", "i", "l", "f", "d", "ref")) {
            int offset = offset(TestWrapper.Base.class, field);
            PEATestUtils.VirtualObjectEntry left = first.fields().get(offset);
            PEATestUtils.VirtualObjectEntry right = second.fields().get(offset);
            Asserts.assertEquals(left.basicType(), right.basicType(),
                    target + ": equal-state field type " + field);
            Asserts.assertEquals(left.value().kind(), right.value().kind(),
                    target + ": equal-state field value kind " + field);
            Asserts.assertEquals(left.value().operand(), right.value().operand(),
                    target + ": equal-state field value " + field);
        }
    }

    private static void collectVORefs(
            Set<Integer> ids, Map<Integer, PEATestUtils.DeoptValue> values) {
        for (PEATestUtils.DeoptValue value : values.values()) {
            if (value.kind() == PEATestUtils.DeoptValueKind.VO_REF) {
                ids.add(value.virtualObjectId());
            }
        }
    }

    private static void assertScalar(
            PEATestUtils.VirtualObjectDescriptor descriptor,
            Class<?> holder, String field, PEATestUtils.DeoptBasicType type,
            String operand) throws Exception {
        PEATestUtils.VirtualObjectEntry entry =
                descriptor.fields().get(offset(holder, field));
        Asserts.assertNotNull(entry, "missing scalar field " + field);
        Asserts.assertEquals(entry.basicType(), type, field + " basic type");
        Asserts.assertEquals(entry.value().kind(),
                PEATestUtils.DeoptValueKind.SCALAR, field + " scalar kind");
        Asserts.assertEquals(entry.value().operand(), operand, field + " operand");
    }

    private static void assertBooleanScalar(
            PEATestUtils.VirtualObjectDescriptor descriptor,
            Class<?> holder, String field, boolean expected) throws Exception {
        PEATestUtils.VirtualObjectEntry entry =
                descriptor.fields().get(offset(holder, field));
        Asserts.assertNotNull(entry, "missing boolean field " + field);
        Asserts.assertEquals(entry.basicType(), PEATestUtils.DeoptBasicType.INT,
                field + " basic type");
        Asserts.assertEquals(entry.value().kind(),
                PEATestUtils.DeoptValueKind.SCALAR, field + " scalar kind");
        Asserts.assertEquals(booleanValue(entry.value().operand()), expected,
                field + " logical value");
    }

    private static boolean booleanValue(String operand) {
        return switch (operand) {
            case "i1 true", "i1 1", "i8 1" -> true;
            case "i1 false", "i1 0", "i8 0" -> false;
            default -> throw new AssertionError(
                    "invalid normalized boolean operand: " + operand);
        };
    }

    private static void assertScalarPrefix(
            PEATestUtils.VirtualObjectDescriptor descriptor,
            Class<?> holder, String field, PEATestUtils.DeoptBasicType type,
            String operandPrefix) throws Exception {
        PEATestUtils.VirtualObjectEntry entry =
                descriptor.fields().get(offset(holder, field));
        Asserts.assertNotNull(entry, "missing scalar field " + field);
        Asserts.assertEquals(entry.basicType(), type, field + " basic type");
        Asserts.assertEquals(entry.value().kind(),
                PEATestUtils.DeoptValueKind.SCALAR, field + " scalar kind");
        Asserts.assertTrue(entry.value().operand().startsWith(operandPrefix),
                field + " typed operand");
    }

    private static void assertMaterializedOop(
            PEATestUtils.VirtualObjectDescriptor descriptor,
            Class<?> holder, String field) throws Exception {
        PEATestUtils.VirtualObjectEntry entry =
                descriptor.fields().get(offset(holder, field));
        Asserts.assertNotNull(entry, "missing oop field " + field);
        Asserts.assertEquals(entry.basicType(), PEATestUtils.DeoptBasicType.OBJECT);
        Asserts.assertEquals(entry.value().kind(),
                PEATestUtils.DeoptValueKind.MATERIALIZED_OOP);
        Asserts.assertTrue(entry.value().operand().startsWith("ptr "),
                field + " typed oop operand");
    }

    private static void assertAbsent(
            PEATestUtils.VirtualObjectDescriptor descriptor,
            Class<?> holder, String field) throws Exception {
        Asserts.assertFalse(descriptor.fields().containsKey(offset(holder, field)),
                field + " remains an untouched default");
    }

    private static int offset(Class<?> holder, String name) throws Exception {
        Field field = holder.getDeclaredField(name);
        return Math.toIntExact(UNSAFE.objectFieldOffset(field));
    }

    public static class TestWrapper {
        private static final int FLOAT_BITS = 0x7FC12345;
        private static final long DOUBLE_BITS = 0x7FF8123456789ABCL;
        private static final long ESCAPE_MARK = 0x6A09E667F3BCC909L;

        private static final Method NEVER_TARGET = target(
                "testNever", Object.class, float.class, double.class);
        private static final Method PARTIAL_FALSE_TARGET = target(
                "testPartialFalse", boolean.class, Object.class,
                float.class, double.class);
        private static final Method PARTIAL_TRUE_TARGET = target(
                "testPartialTrue", boolean.class, Object.class,
                float.class, double.class);

        private static Method deoptTarget;
        private static Value global;

        public static class Base {
            public boolean z;
            public byte b;
            public short s;
            public char c;
            public int i;
            public long l;
            public float f;
            public double d;
            public Object ref;

            public boolean defaultZ;
            public byte defaultB;
            public short defaultS;
            public char defaultC;
            public int defaultI;
            public long defaultL;
            public float defaultF;
            public double defaultD;
            public Object defaultRef;
        }

        public static class Value extends Base {
            public Value aliasOne;
            public Value aliasTwo;
            public Holder holder;
            public int mutation;
        }

        public static class Holder {
            public int marker;
        }

        public static void main(String[] args) throws Exception {
            new Value();
            new Holder();
            PEATestUtils.compileConfiguredTargetsAtLevel4();

            Object external = new Object();
            float f = Float.intBitsToFloat(FLOAT_BITS);
            double d = Double.longBitsToDouble(DOUBLE_BITS);

            global = null;
            deoptTarget = NEVER_TARGET;
            long never = testNever(external, f, d);
            requireSuccess("never", never);
            if (global != null) {
                throw new AssertionError("never target escaped");
            }

            global = null;
            deoptTarget = PARTIAL_FALSE_TARGET;
            long partialFalse = testPartialFalse(false, external, f, d);
            requireSuccess("partial-false", partialFalse);
            if (global != null) {
                throw new AssertionError("false partial branch escaped");
            }
            if (never != partialFalse) {
                throw new AssertionError("equivalent reconstructed state differs");
            }

            global = null;
            deoptTarget = PARTIAL_TRUE_TARGET;
            long partialTrue = testPartialTrue(true, external, f, d);
            requireSuccess("partial-true", partialTrue);
            if (global == null) {
                throw new AssertionError("true partial branch did not publish its value");
            }
            if (partialTrue != (partialFalse ^ ESCAPE_MARK)) {
                throw new AssertionError("true partial result mismatch: false="
                        + Long.toUnsignedString(partialFalse, 16) + ", true="
                        + Long.toUnsignedString(partialTrue, 16));
            }

            long payload = mix(mix(never, partialFalse), partialTrue);
            System.out.println("PEA-RESULT:" + Long.toUnsignedString(payload, 16));
        }

        public static long testNever(Object external, float f, double d) {
            Value first = new Value();
            first.z = true;
            first.b = -37;
            first.s = -12345;
            first.c = '\u5A3C';
            first.i = 0x13579BDF;
            first.l = 0x123456789ABCDEF0L;
            first.f = f;
            first.d = d;
            first.ref = external;
            first.aliasOne = first;
            first.aliasTwo = first;

            Value second = new Value();
            second.z = true;
            second.b = -37;
            second.s = -12345;
            second.c = '\u5A3C';
            second.i = 0x13579BDF;
            second.l = 0x123456789ABCDEF0L;
            second.f = f;
            second.d = d;
            second.ref = external;
            second.aliasOne = second;
            second.aliasTwo = second;

            Holder holder = new Holder();
            holder.marker = 73;
            first.holder = holder;
            second.holder = holder;

            requestDeopt();

            int initialFailure =
                    initialStateFailure(first, second, holder, external, f, d);
            if (initialFailure != 0) {
                return Long.MIN_VALUE + initialFailure;
            }
            if (!mutateAndReread(first, second, holder)) {
                return Long.MIN_VALUE + 50;
            }
            return reconstructedResult(first, second);
        }

        public static long testPartialFalse(
                boolean escape, Object external, float f, double d) {
            Value first = new Value();
            first.z = true;
            first.b = -37;
            first.s = -12345;
            first.c = '\u5A3C';
            first.i = 0x13579BDF;
            first.l = 0x123456789ABCDEF0L;
            first.f = f;
            first.d = d;
            first.ref = external;
            first.aliasOne = first;
            first.aliasTwo = first;

            Value second = new Value();
            second.z = true;
            second.b = -37;
            second.s = -12345;
            second.c = '\u5A3C';
            second.i = 0x13579BDF;
            second.l = 0x123456789ABCDEF0L;
            second.f = f;
            second.d = d;
            second.ref = external;
            second.aliasOne = second;
            second.aliasTwo = second;

            Holder holder = new Holder();
            holder.marker = 73;
            first.holder = holder;
            second.holder = holder;

            requestDeopt();
            if (escape) {
                sink(first);
            }

            int initialFailure =
                    initialStateFailure(first, second, holder, external, f, d);
            if (initialFailure != 0) {
                return Long.MIN_VALUE + initialFailure;
            }
            if (escape != (global == first)) {
                return Long.MIN_VALUE + 40;
            }
            if (!mutateAndReread(first, second, holder)) {
                return Long.MIN_VALUE + 50;
            }
            return reconstructedResult(first, second)
                    ^ (global == first ? ESCAPE_MARK : 0L);
        }

        public static long testPartialTrue(
                boolean escape, Object external, float f, double d) {
            Value first = new Value();
            first.z = true;
            first.b = -37;
            first.s = -12345;
            first.c = '\u5A3C';
            first.i = 0x13579BDF;
            first.l = 0x123456789ABCDEF0L;
            first.f = f;
            first.d = d;
            first.ref = external;
            first.aliasOne = first;
            first.aliasTwo = first;

            Value second = new Value();
            second.z = true;
            second.b = -37;
            second.s = -12345;
            second.c = '\u5A3C';
            second.i = 0x13579BDF;
            second.l = 0x123456789ABCDEF0L;
            second.f = f;
            second.d = d;
            second.ref = external;
            second.aliasOne = second;
            second.aliasTwo = second;

            Holder holder = new Holder();
            holder.marker = 73;
            first.holder = holder;
            second.holder = holder;

            requestDeopt();
            if (escape) {
                sink(first);
            }

            int initialFailure =
                    initialStateFailure(first, second, holder, external, f, d);
            if (initialFailure != 0) {
                return Long.MIN_VALUE + initialFailure;
            }
            if (escape != (global == first)) {
                return Long.MIN_VALUE + 40;
            }
            if (!mutateAndReread(first, second, holder)) {
                return Long.MIN_VALUE + 50;
            }
            return reconstructedResult(first, second)
                    ^ (global == first ? ESCAPE_MARK : 0L);
        }

        private static int initialStateFailure(
                Value first, Value second, Holder holder,
                Object external, float f, double d) {
            if (first == second
                    || first.aliasOne != first || first.aliasTwo != first
                    || second.aliasOne != second || second.aliasTwo != second
                    || first.holder != holder || second.holder != holder
                    || first.holder != second.holder || holder.marker != 73
                    || first.ref != external || second.ref != external) {
                return 1;
            }
            if (!hasInitialFields(first, external, f, d)) {
                return 2;
            }
            if (!hasInitialFields(second, external, f, d)) {
                return 3;
            }
            int defaultFailure = defaultFieldsFailure(first);
            if (defaultFailure != 0) {
                return defaultFailure;
            }
            defaultFailure = defaultFieldsFailure(second);
            if (defaultFailure != 0) {
                return defaultFailure + 20;
            }
            return 0;
        }

        private static boolean hasInitialFields(
                Value value, Object external, float f, double d) {
            return value.z
                    && value.b == -37
                    && value.s == -12345
                    && value.c == '\u5A3C'
                    && value.i == 0x13579BDF
                    && value.l == 0x123456789ABCDEF0L
                    && Float.floatToRawIntBits(value.f)
                            == Float.floatToRawIntBits(f)
                    && Double.doubleToRawLongBits(value.d)
                            == Double.doubleToRawLongBits(d)
                    && value.ref == external;
        }

        private static int defaultFieldsFailure(Value value) {
            if (value.defaultZ) {
                return 4;
            }
            if (value.defaultB != 0) {
                return 5;
            }
            if (value.defaultS != 0) {
                return 6;
            }
            if (value.defaultC != 0) {
                return 7;
            }
            if (value.defaultI != 0) {
                return 8;
            }
            if (value.defaultL != 0L) {
                return 9;
            }
            if (Float.floatToRawIntBits(value.defaultF) != 0) {
                return 10;
            }
            if (Double.doubleToRawLongBits(value.defaultD) != 0L) {
                return 11;
            }
            if (value.defaultRef != null) {
                return 12;
            }
            if (value.mutation != 0) {
                return 13;
            }
            return 0;
        }

        private static boolean mutateAndReread(
                Value first, Value second, Holder holder) {
            first.z = false;
            first.b = 101;
            first.s = 23456;
            first.c = '\u03A9';
            first.i = -2023406815;
            first.l = 0x0FEDCBA987654321L;
            first.f = Float.intBitsToFloat(0x80000000);
            first.d = Double.longBitsToDouble(0xFFF0000000000000L);
            first.ref = second;
            first.defaultZ = true;
            first.defaultB = -1;
            first.defaultS = -2;
            first.defaultC = '\u0001';
            first.defaultI = -3;
            first.defaultL = -4L;
            first.defaultF = Float.intBitsToFloat(0x7FA54321);
            first.defaultD = Double.longBitsToDouble(0x7FF123456789ABCDL);
            first.defaultRef = holder;
            first.aliasOne = second;
            first.mutation = 91;
            holder.marker = 97;

            if (first.z || first.b != 101 || first.s != 23456
                    || first.c != '\u03A9' || first.i != -2023406815
                    || first.l != 0x0FEDCBA987654321L
                    || Float.floatToRawIntBits(first.f) != 0x80000000
                    || Double.doubleToRawLongBits(first.d)
                            != 0xFFF0000000000000L
                    || first.ref != second || !first.defaultZ
                    || first.defaultB != -1 || first.defaultS != -2
                    || first.defaultC != '\u0001' || first.defaultI != -3
                    || first.defaultL != -4L
                    || Float.floatToRawIntBits(first.defaultF) != 0x7FA54321
                    || Double.doubleToRawLongBits(first.defaultD)
                            != 0x7FF123456789ABCDL
                    || first.defaultRef != holder || first.aliasOne != second
                    || first.aliasTwo != first || first.mutation != 91
                    || holder.marker != 97) {
                return false;
            }
            return second.z && second.b == -37
                    && second.l == 0x123456789ABCDEF0L
                    && second.aliasOne == second && second.aliasTwo == second
                    && second.mutation == 0;
        }

        private static long reconstructedResult(Value first, Value second) {
            long result = first.l ^ second.l;
            result = mix(result, Integer.toUnsignedLong(
                    Float.floatToRawIntBits(first.f)));
            result = mix(result, Double.doubleToRawLongBits(first.d));
            result = mix(result, Integer.toUnsignedLong(
                    Float.floatToRawIntBits(first.defaultF)));
            result = mix(result, Double.doubleToRawLongBits(first.defaultD));
            result = mix(result, first.i);
            result = mix(result, first.mutation);
            return result;
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

        private static void sink(Value value) {
            global = value;
        }

        private static void requireSuccess(String scenario, long result) {
            if (result >= Long.MIN_VALUE + 1 && result <= Long.MIN_VALUE + 50) {
                throw new AssertionError(
                        scenario + " reconstruction failed with sentinel " + result);
            }
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
    }
}
