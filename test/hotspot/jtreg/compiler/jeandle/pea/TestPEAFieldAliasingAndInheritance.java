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
 * @summary PEA scalar-replaces inherited and hidden fields through aliases,
 *          and materializes the original allocation for an identity consumer
 * @library /test/lib /
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      -XX:-UseCompressedOops -XX:-UseCompressedClassPointers
 *      compiler.jeandle.pea.TestPEAFieldAliasingAndInheritance
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;

import jdk.test.lib.Asserts;

public class TestPEAFieldAliasingAndInheritance {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestPEAFieldAliasingAndInheritance$TestWrapper";

    public static void main(String[] args) throws Exception {
        Method direct = TestWrapper.class.getMethod("testDirectInheritedAlias",
                int.class, int.class, long.class, byte.class, Object.class);
        Method branch = TestWrapper.class.getMethod("testBranchMergedAlias",
                boolean.class, int.class, int.class);
        Method helper = TestWrapper.class.getMethod("testHelperReturnedAlias",
                int.class, long.class, Object.class);
        Method derivedValue = TestWrapper.class.getMethod("testNonzeroDerivedFieldValue",
                int.class);
        Method identity = TestWrapper.class.getMethod("testDerivedFieldIdentityUse",
                int.class, long.class, Object.class);
        Method aliasHelper = TestWrapper.class.getMethod("resolvableAlias",
                TestWrapper.Derived.class);
        Method identityConsumer = TestWrapper.class.getMethod("consumeIdentity",
                TestWrapper.Derived.class);
        Method fieldConsumer = TestWrapper.class.getMethod("consumeFieldValue", int.class);
        Method[] targets = {direct, branch, helper, derivedValue, identity};

        behaviorBuilder(targets, aliasHelper, identityConsumer, fieldConsumer)
                .runPEAOnOffEquivalent();

        try (PEATestUtils.RunResult run =
                shapeBuilder(targets, aliasHelper, identityConsumer, fieldConsumer).run()) {
            assertDirectAliasVirtualized(run, direct);
            assertBranchAliasVirtualized(run, branch);
            assertHelperAliasVirtualized(run, helper, aliasHelper);
            assertNonzeroDerivedFieldValueVirtualized(run, derivedValue, fieldConsumer);
            assertIdentityUseRetainsOriginalAllocation(run, identity, identityConsumer);
        }
    }

    private static PEATestUtils.RunBuilder shapeBuilder(Method[] targets,
                                                          Method aliasHelper,
                                                          Method identityConsumer,
                                                          Method fieldConsumer) {
        return configure(PEATestUtils.shapeRun(WRAPPER, targets), aliasHelper,
                identityConsumer, fieldConsumer);
    }

    private static PEATestUtils.RunBuilder behaviorBuilder(Method[] targets,
                                                            Method aliasHelper,
                                                            Method identityConsumer,
                                                            Method fieldConsumer) {
        return configure(PEATestUtils.behaviorRun(WRAPPER, targets), aliasHelper,
                identityConsumer, fieldConsumer);
    }

    private static PEATestUtils.RunBuilder configure(PEATestUtils.RunBuilder builder,
                                                      Method aliasHelper,
                                                      Method identityConsumer,
                                                      Method fieldConsumer) {
        return builder
                .inline(aliasHelper)
                .dontinline(identityConsumer)
                .dontinline(fieldConsumer);
    }

    private static PEATestUtils.PEARound assertOneAllocationVirtualized(
            PEATestUtils.RunResult run, Method target) throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.PEARound first = report.round(0);
        PEATestUtils.IRBody before = first.before();
        PEATestUtils.IRBody after = report.finalAfter();

        Asserts.assertEquals(first.neverEscapes(), 1, target + ": virtual alias state");
        Asserts.assertEquals(first.partiallyEscapes(), 0, target + ": partial aliases");
        Asserts.assertEquals(first.alwaysEscapes(), 0, target + ": escaping aliases");
        Asserts.assertEquals(before.allocations().size(), 1,
                target + ": one source Derived allocation");
        Asserts.assertEquals(before.allocations().get(0).key().kind(),
                PEATestUtils.AllocationKind.INSTANCE, target + ": Derived allocation kind");
        Asserts.assertEquals(first.effectCount("EliminateAllocation"), 1L,
                target + ": allocation elimination");
        after.assertRetainsExactlyOriginalAllocations(before);
        return first;
    }

    private static void assertDirectAliasVirtualized(PEATestUtils.RunResult run, Method target)
            throws Exception {
        PEATestUtils.PEARound first = assertOneAllocationVirtualized(run, target);
        Asserts.assertEquals(first.effectCount("EliminateStore", "store atomic"), 5L,
                target + ": exact inherited, hidden, adjacent, and reference stores");
    }

    private static void assertBranchAliasVirtualized(PEATestUtils.RunResult run, Method target)
            throws Exception {
        PEATestUtils.PEARound first = assertOneAllocationVirtualized(run, target);
        first.before().assertLineCount(" = phi ptr addrspace(1) ", 0);
        first.before().assertLineCount(" = select i1 ", 0);
        first.before().assertOccurrenceCount("store atomic i32", 2);
        Asserts.assertEquals(first.effectCount("EliminateStore", "store atomic i32"), 2L,
                target + ": one field definition per branch");
        Asserts.assertEquals(first.effectCount("CreatePHI"), 1L,
                target + ": branch-specific field state merges through one scalar phi");
        first.after().assertLineCount(" = phi i32 ", 0);
        first.after().assertLineCount(" = select i1 ", 1);
    }

    private static void assertHelperAliasVirtualized(PEATestUtils.RunResult run, Method target,
                                                      Method helper) throws Exception {
        PEATestUtils.PEARound first = assertOneAllocationVirtualized(run, target);
        String callee = PEATestUtils.MethodId.of(helper).llvmFunctionName();
        run.frontendIR(target).assertOccurrenceCount("@\"" + callee + "\"(", 1);
        first.before().assertAbsent(callee);
        Asserts.assertEquals(first.effectCount("EliminateStore", "store atomic"), 3L,
                target + ": exact helper-alias field stores");
    }

    private static void assertNonzeroDerivedFieldValueVirtualized(
            PEATestUtils.RunResult run, Method target, Method consumer) throws Exception {
        PEATestUtils.PEARound first = assertOneAllocationVirtualized(run, target);
        PEATestUtils.AllocationSite allocation = first.before().allocations().get(0);
        String derivedAddress = "getelementptr inbounds nuw i8, ptr addrspace(1) "
                + allocation.result() + ", i64 28";
        first.before().assertOccurrenceCount(derivedAddress, 1);
        Asserts.assertEquals(first.effectCount("EliminateStore", "store atomic i32"), 1L,
                target + ": nonzero derived field store is scalarized exactly once");
        Asserts.assertEquals(first.effectCount("Materialize"), 0L,
                target + ": passing the field value does not escape the object address");
        PEATestUtils.IRBody after = first.after();
        after.assertAbsent(derivedAddress);
        after.assertOccurrenceCount(
                PEATestUtils.MethodId.of(consumer).llvmFunctionName(), 1);
    }

    private static void assertIdentityUseRetainsOriginalAllocation(PEATestUtils.RunResult run,
                                                                     Method target,
                                                                     Method consumer)
            throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.IRBody before = report.round(0).before();
        PEATestUtils.IRBody after = report.finalAfter();

        Asserts.assertEquals(before.allocations().size(), 1,
                target + ": one source allocation before the identity use");
        PEATestUtils.AllocationSite allocation = before.allocations().get(0);
        PEATestUtils.AllocationKey original = allocation.key();
        Asserts.assertEquals(original.kind(), PEATestUtils.AllocationKind.INSTANCE,
                target + ": identity consumer allocation kind");
        for (int offset : new int[] {16, 28, 40}) {
            before.assertOccurrenceCount(
                    "getelementptr inbounds nuw i8, ptr addrspace(1) "
                            + allocation.result() + ", i64 " + offset,
                    1);
        }
        after.assertRetainsExactlyOriginalAllocations(before, original);
        Asserts.assertEquals(report.round(0).effectCount("Materialize"), 1L,
                target + ": exactly one materialization at the identity consumer");
        Asserts.assertEquals(report.round(0).effectCount("EliminateStore", "store atomic"), 3L,
                target + ": exact initial field stores replayed at materialization");

        PEATestUtils.IRBlock consumerBlock = after.blockContaining(
                PEATestUtils.MethodId.of(consumer).llvmFunctionName(), 0);
        consumerBlock.assertOccurrenceCount("store atomic i32", 1);
        consumerBlock.assertOccurrenceCount("store atomic i64", 1);
        consumerBlock.assertOccurrenceCount("store atomic ptr addrspace(1)", 1);
        consumerBlock.assertBefore("store atomic i32", 0,
                PEATestUtils.MethodId.of(consumer).llvmFunctionName(), 0);
        consumerBlock.assertBefore("store atomic i64", 0,
                PEATestUtils.MethodId.of(consumer).llvmFunctionName(), 0);
        consumerBlock.assertBefore("store atomic ptr addrspace(1)", 0,
                PEATestUtils.MethodId.of(consumer).llvmFunctionName(), 0);
    }

    public static class TestWrapper {
        private static Derived observed;

        public static class Base {
            int inherited;
            long baseLong;
        }

        public static class Derived extends Base {
            int inherited;
            byte adjacent;
            Object reference;
        }

        public static void main(String[] args) throws Exception {
            new Derived();
            PEATestUtils.compileConfiguredTargetsAtLevel4();

            Object first = new Object();
            Object second = new Object();
            long digest = 0x9E3779B97F4A7C15L;
            for (int base : new int[] {0, -17, Integer.MAX_VALUE}) {
                int hidden = base * 3 + 11;
                long baseLong = 0x0123456789ABCDEFL ^ base;
                byte adjacent = (byte) (base ^ 0x5A);
                long direct = testDirectInheritedAlias(base, hidden, baseLong,
                        adjacent, first);
                Asserts.assertEquals(direct,
                        checksum(base, hidden, baseLong, adjacent, first, 3),
                        "direct aliases preserve inherited and hidden field state");
                digest = mix(digest, direct);

                long helper = testHelperReturnedAlias(hidden, baseLong, second);
                Asserts.assertEquals(helper,
                        checksum(hidden, hidden, baseLong, (byte) 0, second, 1),
                        "helper alias observes the same object state");
                digest = mix(digest, helper);

                long derived = testNonzeroDerivedFieldValue(hidden);
                Asserts.assertEquals(derived,
                        ((long) consumeFieldValue(hidden) << 32)
                                ^ (0x6A09E667F3BCC909L ^ hidden),
                        "nonzero derived field address exposes only the scalar value");
                digest = mix(digest, derived);

                for (boolean secondArm : new boolean[] {false, true}) {
                    int branch = testBranchMergedAlias(secondArm, hidden, base);
                    Asserts.assertEquals(branch, secondArm ? hidden : base,
                            "same-object aliases merge branch-specific field state");
                    digest = mix(digest, branch);
                }
            }

            for (int value : new int[] {0, -41, 0x13579BDF}) {
                observed = null;
                Object reference = (value & 1) == 0 ? first : second;
                long actual = testDerivedFieldIdentityUse(value,
                        0x1122334455667788L ^ value, reference);
                Asserts.assertNotEquals(observed, null,
                        "identity consumer receives the materialized object");
                Asserts.assertEquals(observed.inherited, value + 7,
                        "hidden field mutation remains visible through the identity alias");
                Asserts.assertEquals(observed.baseLong,
                        (0x1122334455667788L ^ value) + 9,
                        "inherited long field does not share the hidden field slot");
                Asserts.assertTrue(observed.reference == reference,
                        "reference field identity survives materialization");
                Asserts.assertEquals(actual, expectedIdentityUse(value,
                        0x1122334455667788L ^ value),
                        "identity use result");
                digest = mix(digest, actual);
            }
            System.out.println("PEA-RESULT:" + Long.toUnsignedString(digest, 16));
        }

        public static long testDirectInheritedAlias(int base, int hidden, long baseLong,
                                                     byte adjacent, Object reference) {
            Derived allocation = new Derived();
            Derived directAlias = allocation;
            Base inheritedAlias = allocation;
            directAlias.inherited = hidden;
            inheritedAlias.inherited = base;
            inheritedAlias.baseLong = baseLong;
            allocation.adjacent = adjacent;
            allocation.reference = reference;
            int identities = (allocation == directAlias ? 1 : 0)
                    | (allocation == inheritedAlias ? 2 : 0);
            return checksum(inheritedAlias.inherited, directAlias.inherited,
                    inheritedAlias.baseLong, directAlias.adjacent, directAlias.reference,
                    identities);
        }

        public static int testBranchMergedAlias(boolean secondArm, int left, int right) {
            Derived value = new Derived();
            Derived alias = value;
            if (secondArm) {
                alias.inherited = left;
            } else {
                value.inherited = right;
            }
            Derived merged = secondArm ? value : alias;
            return merged.inherited;
        }

        public static long testHelperReturnedAlias(int hidden, long baseLong,
                                                    Object reference) {
            Derived value = new Derived();
            value.inherited = hidden;
            value.baseLong = baseLong;
            value.reference = reference;
            Derived alias = resolvableAlias(value);
            return checksum(alias.inherited, alias.inherited, alias.baseLong,
                    (byte) 0, alias.reference, alias == value ? 1 : 0);
        }

        public static long testNonzeroDerivedFieldValue(int hidden) {
            Derived value = new Derived();
            value.inherited = hidden;
            int consumed = consumeFieldValue(value.inherited);
            value.baseLong = 0x6A09E667F3BCC909L ^ hidden;
            return ((long) consumed << 32) ^ value.baseLong;
        }

        public static Derived resolvableAlias(Derived value) {
            return value;
        }

        public static int consumeFieldValue(int value) {
            return Integer.rotateLeft(value ^ 0x5A5A5A5A, 7);
        }

        public static long testDerivedFieldIdentityUse(int hidden, long baseLong,
                                                        Object reference) {
            Derived value = new Derived();
            value.inherited = hidden;
            value.baseLong = baseLong;
            value.reference = reference;
            int snapshot = value.inherited;
            int identity = consumeIdentity(value);
            value.inherited = snapshot + 7;
            value.baseLong += 9;
            boolean stableIdentity = identity == System.identityHashCode(value);
            return checksum(value.inherited, value.inherited, value.baseLong, (byte) 0,
                    value.reference, stableIdentity && observed == value ? 7 : 0);
        }

        public static int consumeIdentity(Derived value) {
            observed = value;
            return System.identityHashCode(value);
        }

        private static long expectedIdentityUse(int hidden, long baseLong) {
            // The hash value is deliberately not part of the oracle; its stable
            // same-object comparison forces an identity-sensitive use.
            return checksum(hidden + 7, hidden + 7, baseLong + 9, (byte) 0,
                    observed.reference, 7);
        }

        private static long checksum(int base, int hidden, long baseLong, byte adjacent,
                                     Object reference, int identities) {
            long result = base * 0x9E3779B9L + hidden;
            result = Long.rotateLeft(result ^ baseLong, 19);
            result = result * 31 + adjacent;
            result = result * 31 + (reference == null ? 0 : 1);
            return result * 31 + identities;
        }

        private static long mix(long accumulator, long value) {
            return Long.rotateLeft(accumulator ^ value, 13) * 0xD6E8FEB86659FD93L;
        }
    }
}
