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
 * @summary PEA honors the configured array-length cap and preserves dynamic,
 *          negative, multidimensional, bounds, and array type semantics
 * @library /test/lib /
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.pea.TestPEAArrayLengthBoundsAndTypes
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;

import jdk.test.lib.Asserts;

public class TestPEAArrayLengthBoundsAndTypes {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestPEAArrayLengthBoundsAndTypes$TestWrapper";
    private static final int ARRAY_CAP = 128;
    private static final String ARRAY_LENGTH = "@jeandle.arraylength";
    private static final String GET_CLASS = "@jeandle.get_class";
    private static final String DEOPTIMIZE = "@llvm.experimental.deoptimize";
    private static final String DEOPTIMIZE_I64 = "llvm.experimental.deoptimize.i64";
    private static final String DEOPTIMIZE_I64_CALL = "@" + DEOPTIMIZE_I64;
    private static final String LOWERED_DEOPTIMIZE = "@__llvm_deoptimize";
    private static final String BOUNDS_COMPARE = "icmp ult i32";

    public static void main(String[] args) throws Exception {
        Method length0 = TestWrapper.class.getMethod("testLength0");
        Method length1 = TestWrapper.class.getMethod("testLength1");
        Method length127 = TestWrapper.class.getMethod("testLength127");
        Method length128 = TestWrapper.class.getMethod("testLength128");
        Method length129 = TestWrapper.class.getMethod("testLength129");
        Method lengthPhi = TestWrapper.class.getMethod(
                "testArrayLengthPhi", boolean.class);
        Method dynamicLength = TestWrapper.class.getMethod(
                "testDynamicLength", int.class);
        Method negative = TestWrapper.class.getMethod("testNegativeConstantLength");
        Method multi = TestWrapper.class.getMethod(
                "testMultiArray", int.class, int.class);
        Method constantBounds = TestWrapper.class.getMethod("testConstantBounds");
        Method constantLowerOutOfBounds = TestWrapper.class.getMethod(
                "testConstantLowerOutOfBounds");
        Method constantUpperOutOfBounds = TestWrapper.class.getMethod(
                "testConstantUpperOutOfBounds");
        Method dynamicBounds = TestWrapper.class.getMethod(
                "testDynamicBounds", int.class, int.class, int.class);
        Method primitiveTypes = TestWrapper.class.getMethod("testPrimitiveArrayTypes");
        Method objectTypes = TestWrapper.class.getMethod(
                "testObjectArrayTypes", String.class);
        Method failedCheckcast = TestWrapper.class.getMethod("testFailedArrayCheckcast");
        Method[] targets = {length0, length1, length127, length128, length129, lengthPhi,
                dynamicLength, negative, multi, constantBounds,
                constantLowerOutOfBounds, constantUpperOutOfBounds, dynamicBounds,
                primitiveTypes, objectTypes, failedCheckcast};

        PEATestUtils.behaviorRun(WRAPPER, targets)
                .maxArrayLength(ARRAY_CAP)
                .runPEAOnOffEquivalent();

        try (PEATestUtils.RunResult run = PEATestUtils.shapeRun(WRAPPER, targets)
                .maxArrayLength(ARRAY_CAP)
                .run()) {
            for (Method target : targets) {
                run.report(target).assertFinalTransformIdle();
            }
            for (Method target : new Method[] {
                    length0, length1, length127, length128, constantBounds,
                    primitiveTypes, objectTypes}) {
                assertNeverEscapeArray(run, target);
            }
            assertArrayLengthPhi(run, lengthPhi);
            for (Method target : new Method[] {
                    length129, dynamicLength, negative, dynamicBounds}) {
                assertOriginalArrayRetained(run, target);
            }
            assertConstantOutOfBounds(run, constantLowerOutOfBounds, 11);
            assertConstantOutOfBounds(run, constantUpperOutOfBounds, 14);
            assertDynamicBoundsFallbacks(run, dynamicBounds);
            assertMultiArrayConservative(run, multi);
            assertGetClassFolded(run, primitiveTypes);
            assertGetClassFolded(run, objectTypes);
            assertFailedCheckcast(run, failedCheckcast);
        }
    }

    private static void assertNeverEscapeArray(PEATestUtils.RunResult run, Method target)
            throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.PEARound first = report.round(0);
        PEATestUtils.IRBody before = first.before();
        PEATestUtils.IRBody after = report.finalAfter();

        Asserts.assertEquals(before.allocations().size(), 1,
                target + ": one constant one-dimensional source array");
        Asserts.assertEquals(before.allocations().get(0).key().kind(),
                PEATestUtils.AllocationKind.ARRAY, target + ": source allocation kind");
        Asserts.assertEquals(first.neverEscapes(), 1,
                target + ": cap-eligible constant array is NeverEscapes");
        Asserts.assertEquals(first.partiallyEscapes(), 0, target + ": no partial array");
        Asserts.assertEquals(first.alwaysEscapes(), 0, target + ": no escaping array");
        Asserts.assertEquals(first.effectCount("EliminateAllocation"), 1L,
                target + ": exact allocation elimination");
        after.assertRetainsExactlyOriginalAllocations(before);
        after.assertAbsent("store atomic");
        after.assertAbsent("load atomic");
        after.assertAbsent(ARRAY_LENGTH);
        Asserts.assertEquals(run.finalIR(target).loweredAllocCount(), 0,
                target + ": no lowered cap-eligible allocation");
        after.assertAbsent(DEOPTIMIZE);
        run.finalIR(target).assertAbsent(LOWERED_DEOPTIMIZE);
    }

    private static void assertArrayLengthPhi(
            PEATestUtils.RunResult run, Method target) throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.PEARound first = report.round(0);
        PEATestUtils.IRBody before = first.before();
        PEATestUtils.IRBody after = report.finalAfter();
        Asserts.assertEquals(before.allocations().size(), 1,
                target + ": one source array feeds the nullable length phi");
        int sourceLengths = before.lineCount(ARRAY_LENGTH);
        Asserts.assertTrue(sourceLengths > 0,
                target + ": source retains an explicit arraylength call into PEA");
        Asserts.assertEquals(first.effectCount("ReplaceCall", ARRAY_LENGTH),
                (long) sourceLengths,
                target + ": every source arraylength call is replaced");
        Asserts.assertEquals(first.neverEscapes(), 1,
                target + ": the non-null phi input remains virtual");
        Asserts.assertEquals(first.effectCount("EliminateAllocation"), 1L,
                target + ": the phi input allocation is eliminated");
        after.assertRetainsExactlyOriginalAllocations(before);
        after.assertAbsent(ARRAY_LENGTH);
        Asserts.assertEquals(run.finalIR(target).loweredAllocCount(), 0,
                target + ": no phi input allocation reaches lowering");
    }

    private static void assertOriginalArrayRetained(PEATestUtils.RunResult run, Method target)
            throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();

        Asserts.assertEquals(before.allocations().size(), 1,
                target + ": one conservative source array");
        PEATestUtils.AllocationKey key = before.allocations().get(0).key();
        Asserts.assertEquals(key.kind(), PEATestUtils.AllocationKind.ARRAY,
                target + ": conservative allocation kind");
        after.assertRetainsExactlyOriginalAllocations(before, key);
        Asserts.assertEquals(run.finalIR(target).loweredAllocCount(), 1,
                target + ": exactly the original conservative allocation is lowered");
    }

    private static void assertMultiArrayConservative(PEATestUtils.RunResult run, Method target)
            throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();

        Asserts.assertEquals(before.allocations().size(), 0,
                target + ": frontend multianewarray is not a PEA allocation form");
        before.assertOccurrenceCount("@multianewarray2", 1);
        after.assertOccurrenceCount("@multianewarray2", 1);
        Asserts.assertEquals(report.round(0).effectCount("EliminateAllocation"), 0L,
                target + ": PEA does not claim unsupported multiarray elimination");
        Asserts.assertEquals(report.round(0).effectCount("Materialize"), 0L,
                target + ": PEA does not synthesize a multiarray materialization");
    }

    private static void assertConstantOutOfBounds(
            PEATestUtils.RunResult run, Method target, int expectedBCI) throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.PEARound first = report.round(0);
        PEATestUtils.IRBody before = first.before();
        PEATestUtils.IRBody after = report.finalAfter();

        Asserts.assertEquals(before.allocations().size(), 1,
                target + ": one source array reaches the constant failing bounds check");
        Asserts.assertEquals(first.neverEscapes(), 1,
                target + ": the exact exception state keeps the array virtual");
        Asserts.assertEquals(first.effectCount("EliminateAllocation"), 1L,
                target + ": constant failing bounds do not force materialization");
        after.assertRetainsExactlyOriginalAllocations(before);
        Asserts.assertEquals(run.finalIR(target).loweredAllocCount(), 0,
                target + ": no allocation survives the exact deoptimization path");
        assertUnconditionalFallback(after, expectedBCI);
        assertLoweredFallback(run.finalIR(target), 1);

        PEATestUtils.DeoptBundle bundle = after.deoptBundleAtCall(DEOPTIMIZE_I64, 0);
        Asserts.assertEquals(bundle.virtualObjects().size(), 1,
                target + ": exact failing access carries one virtual array descriptor");
        bundle.assertVirtualObjectIds(0);
        PEATestUtils.VirtualObjectDescriptor descriptor = bundle.virtualObject(0);
        Asserts.assertEquals(descriptor.kind(),
                PEATestUtils.DescriptorKind.ARRAY,
                target + ": deoptimization reconstructs the array before throwing");
        Asserts.assertEquals(descriptor.elements().size(), 1,
                target + ": exact one-element array state is reconstructed");
        PEATestUtils.VirtualObjectEntry element = descriptor.elements().values()
                .iterator().next();
        Asserts.assertEquals(element.basicType(), PEATestUtils.DeoptBasicType.INT,
                target + ": exact int[] element kind");
        Asserts.assertEquals(element.value().kind(), PEATestUtils.DeoptValueKind.SCALAR,
                target + ": exact preserved old element value");
        Asserts.assertEquals(element.value().operand(), "i32 66",
                target + ": failing access preserves the old element");
    }

    private static void assertDynamicBoundsFallbacks(
            PEATestUtils.RunResult run, Method target) throws Exception {
        PEATestUtils.IRBody after = run.report(target).finalAfter();
        PEATestUtils.IRBlock firstSuccess =
                after.blockContaining(BOUNDS_COMPARE, 1);
        PEATestUtils.IRBlock secondSuccess =
                after.blockContaining("_arrayChecksum", 0);
        assertConditionalBoundsFallback(after, 0, 26, firstSuccess);
        assertConditionalBoundsFallback(after, 1, 32, secondSuccess);
        PEATestUtils.IRBody finalIR = run.finalIR(target);
        assertFinalConditionalBoundsFallback(finalIR, 0,
                finalIR.blockContaining(BOUNDS_COMPARE, 1));
        assertFinalConditionalBoundsFallback(finalIR, 1,
                finalIR.blockContaining("_arrayChecksum", 0));
        assertLoweredFallback(finalIR, 2);

        PEATestUtils.DeoptBundle first =
                after.deoptBundleAtCall(DEOPTIMIZE_I64, 0);
        first.assertVirtualObjectIds(0);
        Asserts.assertEquals(first.virtualObject(0).kind(),
                PEATestUtils.DescriptorKind.ARRAY,
                target + ": first bounds fallback carries virtual array state");
        PEATestUtils.DeoptBundle second =
                after.deoptBundleAtCall(DEOPTIMIZE_I64, 1);
        Asserts.assertEquals(second.virtualObjects().size(), 0,
                target + ": second bounds fallback uses the first access materialization");

        firstSuccess.assertAbsent(DEOPTIMIZE);
        firstSuccess.assertOccurrenceCount("store atomic i32", 4);
        secondSuccess.assertAbsent(DEOPTIMIZE);
        secondSuccess.assertOccurrenceCount("store atomic i32", 1);
    }

    private static void assertGetClassFolded(
            PEATestUtils.RunResult run, Method target) throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.PEARound first = report.round(0);
        first.before().assertOccurrenceCount(GET_CLASS, 1);
        Asserts.assertEquals(first.effectCount("ReplaceCall", GET_CLASS), 1L,
                target + ": exact getClass call is replaced");
        Asserts.assertEquals(first.effectCount("Materialize"), 0L,
                target + ": getClass folding does not materialize the array");
        report.finalAfter().assertAbsent(GET_CLASS);
        run.finalIR(target).assertAbsent(GET_CLASS);
    }

    private static void assertFailedCheckcast(PEATestUtils.RunResult run, Method target)
            throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.PEARound first = report.round(0);
        PEATestUtils.IRBody before = first.before();
        PEATestUtils.IRBody after = report.finalAfter();
        Asserts.assertEquals(before.allocations().size(), 1,
                target + ": one source array reaches the incompatible checkcast");
        Asserts.assertEquals(first.neverEscapes(), 1,
                target + ": exact incompatible cast keeps the source array virtual");
        Asserts.assertEquals(first.effectCount("EliminateAllocation"), 1L,
                target + ": exact incompatible type eliminates the allocation");
        Asserts.assertEquals(first.effectCount("Materialize"), 0L,
                target + ": incompatible cast uses deoptimization reconstruction");
        after.assertRetainsExactlyOriginalAllocations(before);
        Asserts.assertEquals(run.finalIR(target).loweredAllocCount(), 0,
                target + ": no allocation survives the isolated cast fallback");
        assertUnconditionalFallback(after, 5);
        assertLoweredFallback(run.finalIR(target), 1);

        PEATestUtils.DeoptBundle bundle = after.deoptBundleAtCall(DEOPTIMIZE_I64, 0);
        bundle.assertVirtualObjectIds(0);
        Asserts.assertEquals(bundle.virtualObject(0).kind(),
                PEATestUtils.DescriptorKind.ARRAY,
                target + ": ClassCastException fallback reconstructs the exact array");
    }

    private static void assertUnconditionalFallback(
            PEATestUtils.IRBody body, int expectedBCI) {
        body.assertOccurrenceCount(DEOPTIMIZE_I64_CALL, 1);
        Asserts.assertEquals(body.callOccurrencesAtBCI(DEOPTIMIZE_I64, expectedBCI),
                List.of(0), body.methodId() + ": exact unconditional fallback BCI");
        PEATestUtils.IRBlock fallback = body.blockContaining(DEOPTIMIZE_I64_CALL, 0);
        fallback.assertOccurrenceCount(DEOPTIMIZE_I64_CALL, 1);
        fallback.assertOccurrenceCount("ret i64", 1);
        fallback.assertAbsent("br i1");
    }

    private static void assertConditionalBoundsFallback(
            PEATestUtils.IRBody body, int compareOccurrence, int expectedBCI,
            PEATestUtils.IRBlock success) {
        Asserts.assertEquals(body.callOccurrencesAtBCI(DEOPTIMIZE_I64, expectedBCI).size(),
                1, body.methodId() + ": exact conditional bounds fallback BCI");
        int deoptOccurrence = body.callOccurrencesAtBCI(
                DEOPTIMIZE_I64, expectedBCI).get(0);
        PEATestUtils.IRBlock fallback = body.blockContaining(
                DEOPTIMIZE_I64_CALL, deoptOccurrence);
        fallback.assertOccurrenceCount(DEOPTIMIZE_I64_CALL, 1);
        fallback.assertOccurrenceCount("ret i64", 1);
        fallback.assertAbsent("store atomic");
        fallback.assertAbsent("load atomic");
        assertConditionalBoundsStructure(body, compareOccurrence, success, fallback);
    }

    private static void assertConditionalBoundsStructure(
            PEATestUtils.IRBody body, int compareOccurrence,
            PEATestUtils.IRBlock success, PEATestUtils.IRBlock fallback) {
        PEATestUtils.IRBlock bounds =
                body.blockContaining(BOUNDS_COMPARE, compareOccurrence);
        bounds.assertOccurrenceCount(BOUNDS_COMPARE, 1);
        bounds.assertOccurrenceCount("br i1", 1);
        bounds.assertAbsent(DEOPTIMIZE);
        bounds.assertAbsent(LOWERED_DEOPTIMIZE);
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

    private static void assertFinalConditionalBoundsFallback(
            PEATestUtils.IRBody body, int occurrence, PEATestUtils.IRBlock success) {
        PEATestUtils.IRBlock fallback =
                body.blockContaining(LOWERED_DEOPTIMIZE, occurrence);
        assertConditionalBoundsStructure(body, occurrence, success, fallback);
    }

    private static void assertLoweredFallback(
            PEATestUtils.IRBody body, int expectedCount) {
        body.assertOccurrenceCount(LOWERED_DEOPTIMIZE, expectedCount);
        for (int occurrence = 0; occurrence < expectedCount; occurrence++) {
            PEATestUtils.IRBlock fallback =
                    body.blockContaining(LOWERED_DEOPTIMIZE, occurrence);
            fallback.assertOccurrenceCount(LOWERED_DEOPTIMIZE, 1);
            fallback.assertAbsent("store atomic");
            fallback.assertAbsent("load atomic");
        }
    }

    public static class TestWrapper {
        public static void main(String[] args) throws Exception {
            PEATestUtils.compileConfiguredTargetsAtLevel4();

            long digest = 0x082EFA98EC4E6C89L;
            digest = check(digest, testLength0(), lengthResult(0, 0, 0), "length 0");
            digest = check(digest, testLength1(), lengthResult(1, 0, 0), "length 1");
            digest = check(digest, testLength127(), lengthResult(127, 126, 0x127),
                    "length 127");
            digest = check(digest, testLength128(), lengthResult(128, 127, 0x128),
                    "length 128");
            digest = check(digest, testLength129(), lengthResult(129, 128, 0x129),
                    "length 129");
            digest = check(digest, testArrayLengthPhi(true),
                    lengthResult(3, 11, 0), "length phi true");
            digest = check(digest, testArrayLengthPhi(false),
                    0x4E554C4CL, "length phi false");

            for (int length : new int[] {0, 1, 127, 128, 129, -1, Integer.MIN_VALUE}) {
                long actual = testDynamicLength(length);
                long expected = length < 0 ? 0x4E415345L : lengthResult(length,
                        length == 0 ? 0 : length - 1, length == 0 ? 0 : 0x5A);
                digest = check(digest, actual, expected, "dynamic length " + length);
            }
            digest = check(digest, testNegativeConstantLength(), 0x4E454741L,
                    "constant negative length");

            int[][] dimensions = {{0, 3}, {2, 3}, {2, 0}, {-1, 3}, {2, -1}};
            for (int[] dimension : dimensions) {
                long actual = testMultiArray(dimension[0], dimension[1]);
                long expected = dimension[0] < 0 || dimension[1] < 0
                        ? 0x4D4E4153L
                        : ((long) dimension[0] << 32)
                                ^ ((long) (dimension[0] == 0 ? 0 : dimension[1]) << 16)
                                ^ 0x4D41;
                digest = check(digest, actual, expected,
                        "multiarray " + dimension[0] + "x" + dimension[1]);
            }

            digest = check(digest, testConstantBounds(), 0x434F4E53L,
                    "constant valid bounds");
            digest = check(digest, testConstantLowerOutOfBounds(), 0x4C4F5745L,
                    "constant lower bound");
            digest = check(digest, testConstantUpperOutOfBounds(), 0x55505045L,
                    "constant upper bound");
            int[][] bounds = {
                    {0, 0, 101}, {2, 2, 202}, {3, 1, 303},
                    {-1, 0, 404}, {4, 0, 505}, {0, -1, 606}, {0, 4, 707}
            };
            for (int[] value : bounds) {
                long actual = testDynamicBounds(value[0], value[1], value[2]);
                long expected = expectedDynamicBounds(value[0], value[1], value[2]);
                digest = check(digest, actual, expected,
                        "dynamic bounds " + value[0] + "," + value[1]);
            }

            digest = check(digest, testPrimitiveArrayTypes(), 0x5052494DL,
                    "primitive array types");
            digest = check(digest, testObjectArrayTypes("array-value"), 0x4F424A54L,
                    "Object array types");
            digest = check(digest, testFailedArrayCheckcast(), 0x43434521L,
                    "exact ClassCastException");
            System.out.println("PEA-RESULT:" + Long.toUnsignedString(digest, 16));
        }

        public static long testLength0() {
            int[] array = new int[0];
            return lengthResult(array.length, 0, 0);
        }

        public static long testLength1() {
            int[] array = new int[1];
            return lengthResult(array.length, array[0], 0);
        }

        public static long testLength127() {
            int[] array = new int[127];
            array[126] = 0x127;
            return lengthResult(array.length, 126, array[126]);
        }

        public static long testLength128() {
            int[] array = new int[128];
            array[127] = 0x128;
            return lengthResult(array.length, 127, array[127]);
        }

        public static long testLength129() {
            int[] array = new int[129];
            array[128] = 0x129;
            return lengthResult(array.length, 128, array[128]);
        }

        public static long testArrayLengthPhi(boolean choose) {
            int[] array = new int[3];
            array[0] = 11;
            int[] selected = choose ? array : null;
            try {
                return lengthResult(selected.length, array[0], 0);
            } catch (NullPointerException expected) {
                return 0x4E554C4CL;
            }
        }

        public static long testDynamicLength(int length) {
            try {
                int[] array = new int[length];
                if (length != 0) {
                    array[length - 1] = 0x5A;
                }
                return lengthResult(array.length, length == 0 ? 0 : length - 1,
                        length == 0 ? 0 : array[length - 1]);
            } catch (NegativeArraySizeException expected) {
                return 0x4E415345L;
            }
        }

        public static long testNegativeConstantLength() {
            try {
                int[] array = new int[-1];
                return array.length;
            } catch (NegativeArraySizeException expected) {
                return 0x4E454741L;
            }
        }

        public static long testMultiArray(int first, int second) {
            try {
                int[][] array = new int[first][second];
                return ((long) array.length << 32)
                        ^ ((long) (array.length == 0 ? 0 : array[0].length) << 16)
                        ^ 0x4D41;
            } catch (NegativeArraySizeException expected) {
                return 0x4D4E4153L;
            }
        }

        public static long testConstantBounds() {
            int[] array = new int[2];
            array[0] = 0x43;
            array[1] = 0x4F;
            return array[-1 + 1] == 0x43 && array[1] == 0x4F
                    && array.length == 2 ? 0x434F4E53L : 0;
        }

        public static long testConstantLowerOutOfBounds() {
            int[] array = new int[1];
            array[0] = 0x42;
            try {
                int ignored = array[-1];
                return ignored;
            } catch (ArrayIndexOutOfBoundsException expected) {
                return array[0] == 0x42 ? 0x4C4F5745L : 0;
            }
        }

        public static long testConstantUpperOutOfBounds() {
            int[] array = new int[1];
            array[0] = 0x42;
            try {
                array[array.length] = 0x55;
                return 0;
            } catch (ArrayIndexOutOfBoundsException expected) {
                return array[0] == 0x42 ? 0x55505045L : 0;
            }
        }

        public static long testDynamicBounds(int loadIndex, int storeIndex, int value) {
            int[] array = new int[4];
            array[0] = 11;
            array[1] = 22;
            array[2] = 33;
            array[3] = 44;
            try {
                int old = array[loadIndex];
                array[storeIndex] = value;
                return ((long) old << 32)
                        ^ arrayChecksum(array[0], array[1], array[2], array[3], 0xDB);
            } catch (ArrayIndexOutOfBoundsException expected) {
                return arrayChecksum(array[0], array[1], array[2], array[3], 0xBD);
            }
        }

        public static long testPrimitiveArrayTypes() {
            Object value = new int[3];
            int[] checked = (int[]) value;
            int flags = value instanceof int[] ? 1 : 0;
            flags |= value instanceof Object[] ? 2 : 0;
            flags |= value.getClass() == int[].class ? 4 : 0;
            flags |= checked.length == 3 ? 8 : 0;
            return flags == 13 ? 0x5052494DL : flags;
        }

        public static long testObjectArrayTypes(String element) {
            Object value = new String[2];
            Object[] objects = (Object[]) value;
            String[] strings = (String[]) value;
            strings[0] = element;
            int flags = value instanceof String[] ? 1 : 0;
            flags |= value instanceof Object[] ? 2 : 0;
            flags |= value instanceof int[] ? 4 : 0;
            flags |= value.getClass() == String[].class ? 8 : 0;
            flags |= objects.length == 2 && strings.length == 2 ? 16 : 0;
            flags |= objects[0] == element ? 32 : 0;
            return flags == 59 ? 0x4F424A54L : flags;
        }

        public static long testFailedArrayCheckcast() {
            Object value = new int[2];
            try {
                Object[] wrong = (Object[]) value;
                return wrong.length;
            } catch (ClassCastException expected) {
                return 0x43434521L;
            }
        }

        private static long expectedDynamicBounds(int loadIndex, int storeIndex, int value) {
            int[] expected = {11, 22, 33, 44};
            if (loadIndex < 0 || loadIndex >= expected.length) {
                return arrayChecksum(expected[0], expected[1], expected[2], expected[3], 0xBD);
            }
            int old = expected[loadIndex];
            if (storeIndex < 0 || storeIndex >= expected.length) {
                return arrayChecksum(expected[0], expected[1], expected[2], expected[3], 0xBD);
            }
            expected[storeIndex] = value;
            return ((long) old << 32)
                    ^ arrayChecksum(expected[0], expected[1], expected[2], expected[3], 0xDB);
        }

        private static long lengthResult(int length, int index, int value) {
            return ((long) length << 48) ^ ((long) index << 24)
                    ^ Integer.toUnsignedLong(value);
        }

        private static long arrayChecksum(int first, int second, int third, int fourth,
                                          int marker) {
            long result = marker;
            result = result * 257 + first;
            result = result * 257 + second;
            result = result * 257 + third;
            return result * 257 + fourth;
        }

        private static long check(long digest, long actual, long expected, String label) {
            Asserts.assertEquals(actual, expected, label);
            return Long.rotateLeft(digest ^ actual, 17)
                    * 0x9E3779B97F4A7C15L;
        }
    }
}
