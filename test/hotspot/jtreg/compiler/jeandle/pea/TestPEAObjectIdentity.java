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
 * @summary PEA preserves Java object identity for aliases, distinct virtual
 *          objects, null, external references, and materialized objects
 * @library /test/lib /
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      -XX:-UseCompressedOops -XX:-UseCompressedClassPointers
 *      compiler.jeandle.pea.TestPEAObjectIdentity
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;

import jdk.test.lib.Asserts;

public class TestPEAObjectIdentity {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestPEAObjectIdentity$TestWrapper";
    private static final String JEANDLE_NEW_INSTANCE = "@jeandle.new_instance";
    private static final String LOWERED_NEW_INSTANCE = "@new_instance";

    public static void main(String[] args) throws Exception {
        Method selfAlias = TestWrapper.class.getMethod("testSelfAndAlias",
                boolean.class, int.class, int.class);
        Method distinct = TestWrapper.class.getMethod("testDistinctEqualState",
                boolean.class, int.class, int.class);
        Method nullValue = TestWrapper.class.getMethod("testVsNull",
                boolean.class, int.class, int.class);
        Method external = TestWrapper.class.getMethod("testVsExternal",
                boolean.class, Object.class, int.class, int.class);
        Method virtualMaterialized = TestWrapper.class.getMethod("testVirtualVsMaterialized",
                boolean.class, int.class, int.class);
        Method[] targets = {selfAlias, distinct, nullValue, external, virtualMaterialized};

        try (PEATestUtils.RunResult run = PEATestUtils.shapeRun(WRAPPER, targets).run()) {
            assertBaselineShape(run, selfAlias);
            assertFoldedIdentityShape(run, distinct, 2, 6, 2, 2,
                    "icmp ne ptr addrspace(1)", 1);
            assertNullBaselineShape(run, nullValue);
            assertFoldedIdentityShape(run, external, 1, 3, 0, 1,
                    "icmp ne ptr addrspace(1)", 1);
            assertVirtualMaterializedShape(run, virtualMaterialized);
        }

        PEATestUtils.assertPEAOnOffEquivalent(WRAPPER, targets);
    }

    private static void assertBaselineShape(PEATestUtils.RunResult run, Method target)
            throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        assertRound0Stats(report, target, 1, 0, 0);
        Asserts.assertEquals(report.round0Before().peaAllocCount(), 1,
                target + ": allocation before");
        report.round0Before().assertLineCount("icmp eq ptr addrspace(1)", 1);
        assertNonNullIdentityCompareCount(report.round0Before(), "icmp eq ptr addrspace(1)", 0,
                target);
        assertNonNullIdentityCompareCount(report.round0Before(), "icmp ne ptr addrspace(1)", 0,
                target);
        report.round0Before().assertLineCount("store atomic", 3);
        report.round0Before().assertLineCount("load atomic", 0);
        Asserts.assertEquals(effectCount(report.round(0), "EliminateAllocation"), 1,
                target + ": allocation effects");
        Asserts.assertEquals(effectCount(report.round(0), "EliminateStore"), 3,
                target + ": store effects");
        Asserts.assertEquals(effectCount(report.round(0), "ReplaceLoad"), 1,
                target + ": allocation-null guard replacement");
        Asserts.assertEquals(effectCount(report.round(0), "CreatePHI"), 1,
                target + ": merged field state");
        Asserts.assertEquals(report.finalAfter().peaAllocCount(), 0,
                target + ": allocation after");
        report.finalAfter().assertAbsent("store atomic");
        report.finalAfter().assertAbsent("load atomic");
        Asserts.assertEquals(run.finalIR(target).loweredAllocCount(), 0,
                target + ": final dump allocation");
    }

    private static void assertFoldedIdentityShape(PEATestUtils.RunResult run, Method target,
                                                   int allocations, int stores, int loads,
                                                   int fieldPhis, String sourceCompare,
                                                   int sourceCompareCount)
            throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        assertRound0Stats(report, target, allocations, 0, 0);
        Asserts.assertEquals(report.round0Before().peaAllocCount(), allocations,
                target + ": allocation before");
        assertNonNullIdentityCompareCount(report.round0Before(), sourceCompare,
                sourceCompareCount, target);
        report.round0Before().assertLineCount("icmp eq ptr addrspace(1)", allocations);
        report.round0Before().assertLineCount("store atomic", stores);
        report.round0Before().assertLineCount("load atomic", loads);
        Asserts.assertEquals(effectCount(report.round(0), "EliminateAllocation"), allocations,
                target + ": allocation effects");
        Asserts.assertEquals(effectCount(report.round(0), "EliminateStore"), stores,
                target + ": store effects");
        Asserts.assertEquals(effectCount(report.round(0), "ReplaceLoad"),
                loads + allocations + sourceCompareCount,
                target + ": fields, allocation guards, and identity compare replacements");
        Asserts.assertEquals(effectCount(report.round(0), "CreatePHI"), fieldPhis,
                target + ": merged field states");
        Asserts.assertEquals(report.finalAfter().peaAllocCount(), 0,
                target + ": allocation after");
        report.finalAfter().assertAbsent("icmp eq ptr");
        report.finalAfter().assertAbsent("icmp ne ptr");
        report.finalAfter().assertAbsent("store atomic");
        report.finalAfter().assertAbsent("load atomic");
        Asserts.assertEquals(run.finalIR(target).loweredAllocCount(), 0,
                target + ": final dump allocation");
    }

    private static void assertNullBaselineShape(PEATestUtils.RunResult run, Method target)
            throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        assertRound0Stats(report, target, 1, 0, 0);
        Asserts.assertEquals(report.round0Before().peaAllocCount(), 1,
                target + ": allocation before");
        report.round0Before().assertLineCount("icmp eq ptr addrspace(1)", 1);
        assertNonNullIdentityCompareCount(report.round0Before(), "icmp eq ptr addrspace(1)", 0,
                target);
        assertNonNullIdentityCompareCount(report.round0Before(), "icmp ne ptr addrspace(1)", 0,
                target);
        report.round0Before().assertLineCount("store atomic", 3);
        report.round0Before().assertLineCount("load atomic", 0);
        Asserts.assertEquals(effectCount(report.round(0), "EliminateAllocation"), 1,
                target + ": allocation effects");
        Asserts.assertEquals(effectCount(report.round(0), "EliminateStore"), 3,
                target + ": store effects");
        Asserts.assertEquals(effectCount(report.round(0), "ReplaceLoad"), 1,
                target + ": indistinguishable allocation/source null comparison");
        Asserts.assertEquals(effectCount(report.round(0), "CreatePHI"), 1,
                target + ": merged field state");
        Asserts.assertEquals(report.finalAfter().peaAllocCount(), 0,
                target + ": allocation after");
        report.finalAfter().assertAbsent("icmp eq ptr");
        report.finalAfter().assertAbsent("icmp ne ptr");
        report.finalAfter().assertAbsent("store atomic");
        report.finalAfter().assertAbsent("load atomic");
        Asserts.assertEquals(run.finalIR(target).loweredAllocCount(), 0,
                target + ": final dump allocation");
    }

    private static void assertVirtualMaterializedShape(PEATestUtils.RunResult run, Method target)
            throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.IRBody frontend = run.frontendIR(target);
        PEATestUtils.IRBody round0After = report.round(0).after();
        PEATestUtils.IRBody finalAfter = report.finalAfter();
        PEATestUtils.IRBody loweredFinal = run.finalIR(target);
        assertRound0Stats(report, target, 1, 1, 0);
        Asserts.assertEquals(report.round0Before().peaAllocCount(), 2,
                target + ": allocations before");
        report.round0Before().assertLineCount("icmp eq ptr addrspace(1)", 2);
        assertNonNullIdentityCompareCount(report.round0Before(),
                "icmp ne ptr addrspace(1)", 1, target);
        report.round0Before().assertLineCount("store atomic", 8);
        report.round0Before().assertLineCount("load atomic", 1);
        Asserts.assertEquals(effectCount(report.round(0), "EliminateAllocation"), 2,
                target + ": both allocations are analyzed");
        Asserts.assertEquals(effectCount(report.round(0), "EliminateStore"), 5,
                target + ": virtual and replayed materialized stores");
        Asserts.assertEquals(effectCount(report.round(0), "ReplaceLoad"), 3,
                target + ": allocation guards and identity compare replacements");
        Asserts.assertEquals(effectCount(report.round(0), "CreatePHI"), 2,
                target + ": merged field states");
        Asserts.assertEquals(effectCount(report.round(0), "Materialize"), 1,
                target + ": materialized escape");
        Asserts.assertEquals(round0After.peaAllocCount(), 1,
                target + ": retained allocation after round 0");
        round0After.assertLineCount("store atomic", 4);
        round0After.assertLineCount("load atomic", 1);
        Asserts.assertEquals(finalAfter.peaAllocCount(), 1,
                target + ": OrigAlloc for materialized object retained");
        finalAfter.assertAbsent("icmp eq ptr");
        finalAfter.assertAbsent("icmp ne ptr");
        finalAfter.assertLineCount("store atomic", 4);
        finalAfter.assertLineCount("load atomic", 1);

        Asserts.assertEquals(frontend.peaAllocCount(), 2, target + ": frontend allocations");
        assertCallWithDeoptBCI(frontend, JEANDLE_NEW_INSTANCE, 0, 1, target);
        assertCallWithDeoptBCI(frontend, JEANDLE_NEW_INSTANCE, 37, 1, target);

        assertCallWithDeoptBCI(round0After, JEANDLE_NEW_INSTANCE, 0, 1, target);
        assertCallWithDeoptBCI(round0After, JEANDLE_NEW_INSTANCE, 37, 0, target);
        PEATestUtils.assertStructuralSoundness(
                round0After, target + ": structurally sound round-0 result");

        assertCallWithDeoptBCI(finalAfter, JEANDLE_NEW_INSTANCE, 0, 1, target);
        assertCallWithDeoptBCI(finalAfter, JEANDLE_NEW_INSTANCE, 37, 0, target);
        PEATestUtils.assertStructuralSoundness(
                finalAfter, target + ": structurally sound fixed point");
        finalAfter.assertBefore(JEANDLE_NEW_INSTANCE, 0, "store atomic i32", 0);
        finalAfter.assertBefore("store atomic i32", 0,
                "store atomic ptr addrspace(1)", 0);
        finalAfter.assertAbsentBetween("store atomic i32", 0, JEANDLE_NEW_INSTANCE,
                "store atomic ptr addrspace(1)", 0);

        loweredFinal.assertLineCount(LOWERED_NEW_INSTANCE, 1);
        assertCallWithDeoptBCI(loweredFinal, LOWERED_NEW_INSTANCE, 0, 1, target);
        assertCallWithDeoptBCI(loweredFinal, LOWERED_NEW_INSTANCE, 37, 0, target);
        loweredFinal.assertLineCount("alloc_fast_path.i: ; preds =", 1);
        loweredFinal.assertLineCount("alloc_slow_path.i: ; preds =", 1);
        loweredFinal.assertBefore(LOWERED_NEW_INSTANCE, 0, "store atomic i32", 0);
        loweredFinal.assertBetween("jeandle.pre_barrier.exit: ; preds =", 0,
                "store atomic ptr addrspace(1)", 1,
                "jeandle.post_barrier.exit: ; preds =", 0);
        loweredFinal.assertBefore("store atomic i32", 0,
                "store atomic ptr addrspace(1)", 1);
        loweredFinal.assertAbsentBetween("store atomic i32", 0, LOWERED_NEW_INSTANCE,
                "store atomic ptr addrspace(1)", 1);
    }

    private static void assertCallWithDeoptBCI(PEATestUtils.IRBody body, String callee,
                                               int bci, int expected, Method target) {
        String marker = "\"deopt\"(i64 0, i32 " + bci + ", i32 " + bci + ",";
        long actual = body.lines().stream()
                .filter(line -> line.contains(callee) && line.contains(marker))
                .count();
        Asserts.assertEquals(actual, (long) expected,
                target + ": " + callee + " calls carrying source BCI " + bci);
    }

    private static int effectCount(PEATestUtils.PEARound round, String kind) {
        return (int) round.effects().stream().filter(effect -> effect.kind().equals(kind)).count();
    }

    private static void assertNonNullIdentityCompareCount(PEATestUtils.IRBody body,
                                                           String predicate, int expected,
                                                           Method target) {
        long actual = body.lines().stream()
                .filter(line -> line.contains(predicate))
                .filter(line -> !line.contains(", null") && !line.contains("null,"))
                .count();
        Asserts.assertEquals(actual, (long) expected,
                target + ": non-null object identity compares for '" + predicate + "'");
    }

    private static void assertRound0Stats(PEATestUtils.PEAReport report, Method target,
                                          int never, int partial, int always) {
        PEATestUtils.PEARound first = report.round(0);
        Asserts.assertTrue(first.hasStats(), target + ": missing round-0 PEA stats");
        Asserts.assertEquals(first.neverEscapes(), never, target + ": NeverEscapes");
        Asserts.assertEquals(first.partiallyEscapes(), partial, target + ": PartiallyEscapes");
        Asserts.assertEquals(first.alwaysEscapes(), always, target + ": AlwaysEscapes");
    }

    public static class TestWrapper {
        public static class P {
            public int x;
        }

        private static P sink;

        public static void main(String[] args) throws Exception {
            new P();
            sink = null;
            PEATestUtils.compileConfiguredTargetsAtLevel4();

            long digest = 0x6A09E667F3BCC909L;
            for (boolean notEqual : new boolean[] {false, true}) {
                int self = testSelfAndAlias(notEqual, 7, 19);
                int distinct = testDistinctEqualState(notEqual, 11, 23);
                int nullValue = testVsNull(notEqual, 13, 29);
                int external = testVsExternal(notEqual, new Object(), 17, 31);
                int externalNull = testVsExternal(notEqual, null, 37, 41);
                int virtualMaterialized = testVirtualVsMaterialized(notEqual, 43, 47);

                boolean selfRelation = !notEqual;
                boolean distinctRelation = notEqual;
                Asserts.assertEquals(self, pack(notEqual ? 7 : 8,
                        selfRelation, selfRelation, notEqual ? 19 : 20));
                Asserts.assertEquals(distinct, pack(notEqual ? 22 : 24,
                        distinctRelation, distinctRelation, notEqual ? 46 : 48));
                Asserts.assertEquals(nullValue, pack(notEqual ? 13 : 14,
                        distinctRelation, distinctRelation, notEqual ? 29 : 30));
                Asserts.assertEquals(external, pack(notEqual ? 17 : 18,
                        distinctRelation, distinctRelation, notEqual ? 31 : 32));
                Asserts.assertEquals(externalNull, pack(notEqual ? 37 : 38,
                        distinctRelation, distinctRelation, notEqual ? 41 : 42));
                Asserts.assertEquals(virtualMaterialized, pack(notEqual ? 88 : 90,
                        distinctRelation, distinctRelation, notEqual ? 95 : 97));

                digest = mix(digest, self);
                digest = mix(digest, distinct);
                digest = mix(digest, nullValue);
                digest = mix(digest, external);
                digest = mix(digest, externalNull);
                digest = mix(digest, virtualMaterialized);
            }
            System.out.println("PEA-RESULT:" + Long.toUnsignedString(digest, 16));
        }

        public static int testSelfAndAlias(boolean notEqual, int first, int second) {
            P p = new P();
            P alias = p;
            if (notEqual) {
                p.x = first;
            } else {
                p.x = first + 1;
            }
            int before = alias.x;
            boolean beforeRelation = notEqual ? p != p : p == p;
            if (notEqual) {
                alias.x = second;
            } else {
                alias.x = second + 1;
            }
            boolean afterRelation = notEqual ? p != alias : p == alias;
            int after = p.x;
            return pack(before, beforeRelation, afterRelation, after);
        }

        public static int testDistinctEqualState(boolean notEqual, int first, int second) {
            P a = new P();
            P b = new P();
            if (notEqual) {
                a.x = first;
                b.x = first;
            } else {
                a.x = first + 1;
                b.x = first + 1;
            }
            int before = a.x + b.x;
            boolean beforeRelation = notEqual ? a != b : a == b;
            if (notEqual) {
                a.x = second;
                b.x = second;
            } else {
                a.x = second + 1;
                b.x = second + 1;
            }
            boolean afterRelation = notEqual ? a != b : a == b;
            int after = a.x + b.x;
            return pack(before, beforeRelation, afterRelation, after);
        }

        public static int testVsNull(boolean notEqual, int first, int second) {
            P p = new P();
            if (notEqual) {
                p.x = first;
            } else {
                p.x = first + 1;
            }
            int before = p.x;
            boolean beforeRelation = notEqual ? p != null : p == null;
            if (notEqual) {
                p.x = second;
            } else {
                p.x = second + 1;
            }
            boolean afterRelation = notEqual ? p != null : p == null;
            int after = p.x;
            return pack(before, beforeRelation, afterRelation, after);
        }

        public static int testVsExternal(boolean notEqual, Object external,
                                         int first, int second) {
            P p = new P();
            if (notEqual) {
                p.x = first;
            } else {
                p.x = first + 1;
            }
            int before = p.x;
            boolean beforeRelation = notEqual ? p != external : p == external;
            if (notEqual) {
                p.x = second;
            } else {
                p.x = second + 1;
            }
            boolean afterRelation = notEqual ? p != external : p == external;
            int after = p.x;
            return pack(before, beforeRelation, afterRelation, after);
        }

        public static int testVirtualVsMaterialized(boolean notEqual, int first, int second) {
            P materialized = new P();
            if (notEqual) {
                materialized.x = first;
            } else {
                materialized.x = first + 1;
            }
            int beforeMaterialized = materialized.x;
            sink = materialized;

            P virtual = new P();
            if (notEqual) {
                virtual.x = first + 2;
            } else {
                virtual.x = first + 3;
            }
            int before = beforeMaterialized + virtual.x;
            boolean beforeRelation = notEqual
                    ? materialized != virtual : materialized == virtual;
            if (notEqual) {
                materialized.x = second;
                virtual.x = second + 1;
            } else {
                materialized.x = second + 1;
                virtual.x = second + 2;
            }
            boolean afterRelation = notEqual
                    ? materialized != virtual : materialized == virtual;
            int after = materialized.x + virtual.x;
            return pack(before, beforeRelation, afterRelation, after);
        }

        private static int pack(int before, boolean firstRelation,
                                boolean secondRelation, int after) {
            return (before & 0xff)
                    | (firstRelation ? 1 << 8 : 0)
                    | (secondRelation ? 1 << 9 : 0)
                    | ((after & 0x3fffff) << 10);
        }

        private static long mix(long accumulator, long value) {
            return Long.rotateLeft(accumulator ^ value, 13) * 0x9E3779B97F4A7C15L;
        }
    }
}
