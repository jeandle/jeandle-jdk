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
 * @summary PEA tracks null, materialized, shared, overwritten, and nested
 *          references in Object[] virtual state and replays partial arrays
 * @library /test/lib /
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.pea.TestPEAObjectArrayScalarReplacement
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;
import java.util.List;

import jdk.test.lib.Asserts;

public class TestPEAObjectArrayScalarReplacement {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestPEAObjectArrayScalarReplacement$TestWrapper";
    private static final String DEOPTIMIZE = "@llvm.experimental.deoptimize";
    private static final String LOWERED_DEOPTIMIZE = "@__llvm_deoptimize";

    public static void main(String[] args) throws Exception {
        Method nullExternal = TestWrapper.class.getMethod(
                "testNullAndExternal", Object.class);
        Method virtualChild = TestWrapper.class.getMethod(
                "testVirtualChild", int.class);
        Method materializedChild = TestWrapper.class.getMethod(
                "testMaterializedChild", int.class);
        Method sharedSlots = TestWrapper.class.getMethod(
                "testSharedSlots", int.class);
        Method deadOverwrite = TestWrapper.class.getMethod(
                "testDeadOverwrittenChild", int.class);
        Method outer = TestWrapper.class.getMethod(
                "testOuterHoldsArray", int.class);
        Method partial = TestWrapper.class.getMethod(
                "testPartialArray", int.class);
        Method materialize = TestWrapper.class.getMethod(
                "materializeChild", TestWrapper.Child.class);
        Method publish = TestWrapper.class.getMethod(
                "publishArray", Object[].class);
        Method[] targets = {nullExternal, virtualChild, materializedChild,
                sharedSlots, deadOverwrite, outer, partial};

        configure(PEATestUtils.behaviorRun(WRAPPER, targets), materialize, publish)
                .runPEAOnOffEquivalent();

        try (PEATestUtils.RunResult run =
                configure(PEATestUtils.shapeRun(WRAPPER, targets), materialize, publish)
                        .run()) {
            assertNeverEscape(run, nullExternal, 1);
            assertNeverEscape(run, virtualChild, 2);
            assertNeverEscape(run, sharedSlots, 2);
            assertNeverEscape(run, deadOverwrite, 2);
            assertNeverEscape(run, outer, 3);
            assertMaterializedChild(run, materializedChild, materialize);
            assertPartialArray(run, partial, publish);
        }
    }

    private static PEATestUtils.RunBuilder configure(PEATestUtils.RunBuilder builder,
                                                      Method materialize, Method publish) {
        return builder.dontinline(materialize).dontinline(publish);
    }

    private static void assertNeverEscape(PEATestUtils.RunResult run, Method target,
                                          int allocationCount) throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.PEARound first = report.round(0);
        PEATestUtils.IRBody before = first.before();
        PEATestUtils.IRBody after = report.finalAfter();

        Asserts.assertEquals(before.allocations().size(), allocationCount,
                target + ": source allocation count");
        Asserts.assertEquals(first.neverEscapes(), allocationCount,
                target + ": every reachable source allocation is NeverEscapes");
        Asserts.assertEquals(first.partiallyEscapes(), 0, target + ": no partial allocation");
        Asserts.assertEquals(first.alwaysEscapes(), 0, target + ": no escaping allocation");
        Asserts.assertEquals(first.effectCount("EliminateAllocation"),
                (long) allocationCount, target + ": exact allocation elimination effects");

        int sourceStores = before.lineCount("store atomic");
        // Element stores are not folded pre-PEA, so a non-zero store count robustly
        // confirms the array's virtual state is exercised. Element loads are NOT a
        // stable signal: GVN legitimately folds simple load-after-store patterns
        // (e.g. array[0]=X; array[0]==X) before PEA runs, so sourceLoads may be 0
        // even though the source reads the array. The ReplaceLoad assertion below
        // still verifies PEA replaces every load that reaches it.
        Asserts.assertTrue(sourceStores > 0, target + ": object-array state is written");
        int sourceLoads = before.lineCount("load atomic");
        Asserts.assertEquals(first.effectCount("EliminateStore", "store atomic"),
                (long) sourceStores, target + ": all virtual-state stores are eliminated");
        Asserts.assertEquals(first.effectCount("ReplaceLoad", "load atomic"),
                (long) sourceLoads, target + ": all virtual-state loads that reach PEA are replaced");
        after.assertRetainsExactlyOriginalAllocations(before);
        after.assertAbsent("store atomic");
        after.assertAbsent("load atomic");
        Asserts.assertEquals(run.finalIR(target).loweredAllocCount(), 0,
                target + ": no lowered allocation");
        run.finalIR(target).assertAbsent("store atomic");
        run.finalIR(target).assertAbsent("load atomic");
        after.assertAbsent(DEOPTIMIZE);
        run.finalIR(target).assertAbsent(LOWERED_DEOPTIMIZE);
    }

    private static void assertMaterializedChild(PEATestUtils.RunResult run, Method target,
                                                Method consumer) throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.PEARound first = report.round(0);
        PEATestUtils.IRBody before = first.before();
        PEATestUtils.IRBody after = report.finalAfter();

        Asserts.assertEquals(before.allocations().size(), 2,
                target + ": child and Object[] source allocations");
        PEATestUtils.AllocationKey child = onlyKeyOfKind(
                before, PEATestUtils.AllocationKind.INSTANCE);
        PEATestUtils.AllocationKey array = onlyKeyOfKind(
                before, PEATestUtils.AllocationKind.ARRAY);
        Asserts.assertNotEquals(child, array, target + ": typed child and array identities");
        Asserts.assertEquals(first.neverEscapes(), 1,
                target + ": Object[] remains virtual around a materialized child");
        Asserts.assertEquals(first.partiallyEscapes(), 1,
                target + ": child materializes at its exact identity consumer");
        Asserts.assertEquals(first.effectCount("Materialize"), 1L,
                target + ": one child materialization");
        Asserts.assertEquals(first.effectCount("EliminateStore", "store atomic ptr"), 1L,
                target + ": Object[] element store enters virtual state");
        // GVN folds the checkcast element load (array[0]=child has no intervening
        // clobber before that read), so exactly one Object[] element load — the
        // array[0]==observedChild read — reaches PEA and is replaced with the
        // materialized child. (The observedChild static-field load is also a
        // "load atomic ptr" in the before-IR but is not a virtual-array access,
        // so PEA correctly leaves it alone and it is not counted here.)
        Asserts.assertEquals(first.effectCount("ReplaceLoad", "load atomic ptr"), 1L,
                target + ": the surviving Object[] element load is replaced");
        after.assertRetainsExactlyOriginalAllocations(before, child);
        after.assertAbsent("store atomic ptr");

        String callee = PEATestUtils.MethodId.of(consumer).llvmFunctionName();
        PEATestUtils.IRBlock block = after.blockContaining(callee, 0);
        block.assertOccurrenceCount("store atomic i32", 1);
        block.assertBefore("store atomic i32", 0, callee, 0);
        block.assertAbsent(DEOPTIMIZE);
        after.blockContaining("load atomic i32", 0).assertAbsent(DEOPTIMIZE);
        PEATestUtils.IRBody finalIR = run.finalIR(target);
        finalIR.blockContaining(callee, 0).assertAbsent(LOWERED_DEOPTIMIZE);
        finalIR.blockContaining("load atomic i32", 0).assertAbsent(LOWERED_DEOPTIMIZE);
    }

    private static void assertPartialArray(PEATestUtils.RunResult run, Method target,
                                           Method consumer) throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.PEARound first = report.round(0);
        PEATestUtils.IRBody before = first.before();
        PEATestUtils.IRBody after = report.finalAfter();

        Asserts.assertEquals(before.allocations().size(), 2,
                target + ": Object[] and nested child source allocations");
        List<PEATestUtils.AllocationKey> sourceKeys =
                before.allocations().stream().map(PEATestUtils.AllocationSite::key).toList();
        Asserts.assertEquals(sourceKeys.stream()
                        .filter(key -> key.kind() == PEATestUtils.AllocationKind.ARRAY).count(),
                1L, target + ": one source Object[]");
        Asserts.assertEquals(sourceKeys.stream()
                        .filter(key -> key.kind() == PEATestUtils.AllocationKind.INSTANCE).count(),
                1L, target + ": one source child");
        Asserts.assertEquals(first.partiallyEscapes(), 2,
                target + ": array and reachable child materialize together");
        Asserts.assertEquals(first.effectCount("Materialize"), 2L,
                target + ": exact nested materialization effects");
        after.assertRetainsExactlyOriginalAllocations(
                before, sourceKeys.toArray(PEATestUtils.AllocationKey[]::new));

        String callee = PEATestUtils.MethodId.of(consumer).llvmFunctionName();
        PEATestUtils.IRBlock block = after.blockContaining(callee, 0);
        block.assertOccurrenceCount("store atomic", 3);
        block.assertBefore("store atomic", 0, callee, 0);
        block.assertBefore("store atomic", 1, callee, 0);
        block.assertBefore("store atomic", 2, callee, 0);
        block.assertAbsent(DEOPTIMIZE);
        run.finalIR(target).blockContaining(callee, 0).assertAbsent(LOWERED_DEOPTIMIZE);
    }

    private static PEATestUtils.AllocationKey onlyKeyOfKind(
            PEATestUtils.IRBody body, PEATestUtils.AllocationKind kind) {
        List<PEATestUtils.AllocationKey> matches = body.allocations().stream()
                .map(PEATestUtils.AllocationSite::key)
                .filter(key -> key.kind() == kind)
                .toList();
        Asserts.assertEquals(matches.size(), 1,
                body.methodId() + ": exact allocation count for " + kind);
        return matches.get(0);
    }

    public static class TestWrapper {
        private static Child observedChild;
        private static Object[] observedArray;

        public static class Child {
            int value;
        }

        public static class Outer {
            Object[] array;
            int marker;
        }

        public static void main(String[] args) throws Exception {
            new Child();
            new Outer();
            PEATestUtils.compileConfiguredTargetsAtLevel4();

            Object external = new Object();
            long digest = 0x13198A2E03707344L;
            long externalState = testNullAndExternal(external);
            long nullState = testNullAndExternal(null);
            Asserts.assertEquals(externalState, (3L << 32) | 15,
                    "external oop, null, default, and shared identity");
            Asserts.assertEquals(nullState, (3L << 32) | 15,
                    "null external oop is still the exact shared value");
            digest = mix(digest, externalState);
            digest = mix(digest, nullState);
            for (int value : new int[] {0, -17, 0x13579BDF}) {
                long virtual = testVirtualChild(value);
                long shared = testSharedSlots(value);
                long overwritten = testDeadOverwrittenChild(value);
                long outer = testOuterHoldsArray(value);
                Asserts.assertEquals(virtual, ((long) value << 8) ^ 0x11,
                        "virtual child value and identity");
                Asserts.assertEquals(shared, ((long) (value + value) << 8) ^ 0x33,
                        "two Object[] slots share one virtual child");
                Asserts.assertEquals(overwritten, (1L << 32) ^ 0x44,
                        "dead overwritten child leaves a null slot");
                Asserts.assertEquals(outer,
                        ((long) (value + value + (value ^ 0x55AA55AA)) << 8) ^ 0x66,
                        "outer virtual object retains the nested array");
                digest = mix(digest, virtual);
                digest = mix(digest, shared);
                digest = mix(digest, overwritten);
                digest = mix(digest, outer);

                observedChild = null;
                long materialized = testMaterializedChild(value);
                Asserts.assertNotNull(observedChild, "materialized child is published");
                Asserts.assertEquals(observedChild.value, value,
                        "materialized child field replay");
                Asserts.assertEquals(materialized, ((long) value << 8) ^ 0x5A);
                digest = mix(digest, materialized);

                observedArray = null;
                long partial = testPartialArray(value);
                Asserts.assertNotNull(observedArray, "partial Object[] is published");
                Asserts.assertTrue(observedArray[0] == observedArray[1],
                        "shared child identity survives array replay");
                Child child = (Child) observedArray[0];
                Asserts.assertEquals(child.value, value + 1,
                        "post-materialization child mutation is visible through both slots");
                Asserts.assertEquals(partial, ((long) (value + 1) << 8) ^ 0xA5);
                digest = mix(digest, partial);
            }
            System.out.println("PEA-RESULT:" + Long.toUnsignedString(digest, 16));
        }

        public static long testNullAndExternal(Object external) {
            Object[] array = new Object[3];
            Object untouched = array[2];
            array[0] = external;
            array[1] = null;
            array[2] = external;
            int identity = array[0] == external ? 1 : 0;
            identity |= array[1] == null ? 2 : 0;
            identity |= untouched == null ? 4 : 0;
            identity |= array[0] == array[2] ? 8 : 0;
            return ((long) array.length << 32) | identity;
        }

        public static long testVirtualChild(int value) {
            Object[] array = new Object[1];
            Child child = new Child();
            child.value = value;
            array[0] = child;
            Child loaded = (Child) array[0];
            return ((long) loaded.value << 8) ^ (loaded == child ? 0x11 : 0);
        }

        public static long testMaterializedChild(int value) {
            Child child = new Child();
            child.value = value;
            materializeChild(child);
            Object[] array = new Object[1];
            array[0] = child;
            return ((long) ((Child) array[0]).value << 8)
                    ^ (array[0] == observedChild ? 0x5A : 0);
        }

        public static long testSharedSlots(int value) {
            Object[] array = new Object[2];
            Child child = new Child();
            child.value = value;
            array[0] = child;
            array[1] = child;
            Child first = (Child) array[0];
            Child second = (Child) array[1];
            return ((long) (first.value + second.value) << 8)
                    ^ (first == second && first == child ? 0x33 : 0);
        }

        public static long testDeadOverwrittenChild(int value) {
            Object[] array = new Object[1];
            Child dead = new Child();
            dead.value = value;
            array[0] = dead;
            array[0] = null;
            return ((long) array.length << 32) ^ (array[0] == null ? 0x44 : 0);
        }

        public static long testOuterHoldsArray(int value) {
            Outer outer = new Outer();
            Object[] array = new Object[2];
            Child child = new Child();
            child.value = value;
            array[0] = child;
            array[1] = child;
            outer.array = array;
            outer.marker = value ^ 0x55AA55AA;
            Child first = (Child) outer.array[0];
            Child second = (Child) outer.array[1];
            return ((long) (first.value + second.value + outer.marker) << 8)
                    ^ (outer.array == array && first == second ? 0x66 : 0);
        }

        public static long testPartialArray(int value) {
            Object[] array = new Object[2];
            Child child = new Child();
            child.value = value;
            array[0] = child;
            array[1] = child;
            publishArray(array);
            child.value++;
            return ((long) ((Child) observedArray[1]).value << 8)
                    ^ (observedArray == array && observedArray[0] == child ? 0xA5 : 0);
        }

        public static void materializeChild(Child child) {
            observedChild = child;
        }

        public static void publishArray(Object[] array) {
            observedArray = array;
        }

        private static long mix(long accumulator, long value) {
            return Long.rotateLeft(accumulator ^ value, 17)
                    * 0x9E3779B97F4A7C15L;
        }
    }
}
