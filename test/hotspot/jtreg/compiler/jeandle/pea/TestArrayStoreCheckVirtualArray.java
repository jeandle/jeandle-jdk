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
 * @summary PEA folds array store checks only when the runtime array component
 *          type proves the value compatible
 * @library /test/lib /
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.pea.TestArrayStoreCheckVirtualArray
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jdk.test.lib.Asserts;

public class TestArrayStoreCheckVirtualArray {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestArrayStoreCheckVirtualArray$TestWrapper";
    private static final String ARRAY_STORE_CHECK = "@jeandle.array_store_check";
    private static final String NEW_ARRAY = "@jeandle.new_array";
    private static final String NEW_INSTANCE = "@jeandle.new_instance";
    private static final String LOWERED_NEW_ARRAY = "@new_array";
    private static final String LOWERED_NEW_INSTANCE = "@new_instance";
    private static final Pattern DEOPT_BCI = Pattern.compile(
            "\\\"deopt\\\"\\(i64 0, i32 (-?\\d+), i32 \\1,");
    private static final Pattern ARRAY_CHECK_PASS_BCI = Pattern.compile(
            "label %bci_(\\d+)_array_store_check_pass");

    public static void main(String[] args) throws Exception {
        Method stringAndNull = TestWrapper.class.getMethod("testStringAndNull");
        Method component = TestWrapper.class.getMethod("testComponentArray",
                TestWrapper.Impl.class);
        Method covariance = TestWrapper.class.getMethod("testCompatibleCovariance",
                String.class);
        Method incompatible = TestWrapper.class.getMethod("testExactIncompatible");
        Method unknown = TestWrapper.class.getMethod("testUnknownValue", Object.class);
        Method materialized = TestWrapper.class.getMethod("testMaterializedArray", Object.class);
        Method primitive = TestWrapper.class.getMethod("testPrimitiveArray");
        Method[] targets = {stringAndNull, component, covariance, incompatible,
                unknown, materialized, primitive};

        try (PEATestUtils.RunResult run = PEATestUtils.shapeRun(WRAPPER, targets).run()) {
            assertCompatibleVirtual(run, stringAndNull, 1, 3, 3);
            assertCompatibleVirtual(run, component, 1, 1, 1);
            assertCompatibleVirtual(run, covariance, 1, 2, 2);
            assertConservative(run, incompatible, 2, 2, 1);
            assertConservative(run, unknown, 1, 2, 1);
            assertConservative(run, materialized, 1, 2, 1);
            assertPrimitiveControl(run, primitive);
        }

        PEATestUtils.assertPEAOnOffEquivalent(WRAPPER, targets);
    }

    private static void assertCompatibleVirtual(PEATestUtils.RunResult run, Method target,
                                                int allocations, int sourceChecks,
                                                int sourceStores)
            throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.PEARound first = report.round(0);
        assertRound0Stats(report, target, allocations, 0, 0);
        Asserts.assertEquals(first.before().peaAllocCount(), allocations,
                target + ": source allocations");
        first.before().assertLineCount(ARRAY_STORE_CHECK, sourceChecks);
        first.before().assertLineCount("store atomic", sourceStores);
        Asserts.assertEquals(effectCount(first, "EliminateAllocation"), allocations,
                target + ": allocation elimination effects");
        Asserts.assertEquals(effectTargetCount(first, "ReplaceCall", ARRAY_STORE_CHECK),
                sourceChecks,
                target + ": store-check replacement effects");
        Asserts.assertEquals(effectTargetCount(first, "EliminateStore", "store atomic"),
                sourceStores, target + ": element-store effects");
        // GVN legitimately folds simple array-element load-after-store (e.g.
        // array[0]=X; array[0]==X) before PEA runs when no intervening clobber
        // exists, so the element-load count visible to PEA is not fixed by the
        // source shape. Verify PEA replaces every element load that reaches it.
        int sourceLoads = first.before().lineCount("load atomic");
        Asserts.assertEquals(effectTargetCount(first, "ReplaceLoad", "load atomic"),
                sourceLoads, target + ": element-load effects");

        PEATestUtils.IRBody after = report.finalAfter();
        Asserts.assertEquals(after.peaAllocCount(), 0, target + ": PEA-final allocations");
        after.assertAbsent(ARRAY_STORE_CHECK);
        after.assertAbsent("store atomic");
        after.assertAbsent("load atomic");
        PEATestUtils.IRBody finalIR = run.finalIR(target);
        Asserts.assertEquals(finalIR.loweredAllocCount(), 0,
                target + ": final dump allocations");
        finalIR.assertAbsent(ARRAY_STORE_CHECK);
        finalIR.assertAbsent("store atomic");
        finalIR.assertAbsent("load atomic");
    }

    private static void assertConservative(PEATestUtils.RunResult run, Method target,
                                           int allocations, int sourceChecks,
                                           int finalChecks)
            throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.PEARound first = report.round(0);
        Asserts.assertTrue(first.hasStats(), target + ": missing round-0 PEA stats");
        Asserts.assertEquals(first.neverEscapes() + first.partiallyEscapes()
                        + first.alwaysEscapes(), allocations,
                target + ": classified source allocations");
        Asserts.assertEquals(report.round0Before().peaAllocCount(), allocations,
                target + ": source allocations before PEA");
        report.round0Before().assertLineCount(ARRAY_STORE_CHECK, sourceChecks);
        Asserts.assertEquals(effectCount(first, "EliminateAllocation"), allocations,
                target + ": analyzed source allocations");
        Asserts.assertEquals(effectCount(first, "Materialize"), allocations,
                target + ": use-point materializations");
        Asserts.assertEquals(report.finalAfter().peaAllocCount(), allocations,
                target + ": original allocations retained after PEA");
        report.finalAfter().assertLineCount(ARRAY_STORE_CHECK, finalChecks);
        assertOnlyLastSourceCheckRetained(report.round0Before(), report.finalAfter(), target);
        assertOrigAllocationsRetained(run.frontendIR(target), report.finalAfter(),
                allocations, target);
        assertLoweredOrigAllocationsRetained(run.frontendIR(target), run.finalIR(target),
                allocations, target);
    }

    private static void assertPrimitiveControl(PEATestUtils.RunResult run, Method target)
            throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        assertRound0Stats(report, target, 1, 0, 0);
        Asserts.assertEquals(report.round0Before().peaAllocCount(), 1,
                target + ": primitive source allocation");
        report.round0Before().assertAbsent(ARRAY_STORE_CHECK);
        report.round0Before().assertLineCount("store atomic", 3);
        report.round0Before().assertLineCount("load atomic", 0);
        Asserts.assertEquals(effectCount(report.round(0), "EliminateAllocation"), 1,
                target + ": primitive allocation elimination effect");
        Asserts.assertEquals(effectTargetCount(report.round(0), "EliminateStore",
                "store atomic"), 3, target + ": primitive element-store effects");
        Asserts.assertEquals(report.finalAfter().peaAllocCount(), 0,
                target + ": primitive allocation after PEA");
        report.finalAfter().assertAbsent(ARRAY_STORE_CHECK);
        report.finalAfter().assertAbsent("store atomic");
        report.finalAfter().assertAbsent("load atomic");
        Asserts.assertEquals(run.finalIR(target).loweredAllocCount(), 0,
                target + ": primitive final dump allocation");
        run.finalIR(target).assertAbsent("store atomic");
        run.finalIR(target).assertAbsent("load atomic");
    }

    private static void assertOrigAllocationsRetained(PEATestUtils.IRBody before,
                                                       PEATestUtils.IRBody after,
                                                       int expected, Method target) {
        List<Integer> sourceBCIs = allocationBCIs(before, NEW_ARRAY, NEW_INSTANCE);
        List<Integer> finalBCIs = allocationBCIs(after, NEW_ARRAY, NEW_INSTANCE);
        Asserts.assertEquals(sourceBCIs.size(), expected,
                target + ": source allocation BCI count");
        Asserts.assertEquals(finalBCIs, sourceBCIs,
                target + ": retained allocations must be the source OrigAllocs in source order");
    }

    private static void assertLoweredOrigAllocationsRetained(PEATestUtils.IRBody before,
                                                              PEATestUtils.IRBody lowered,
                                                              int expected, Method target) {
        List<Integer> sourceBCIs = allocationBCIs(before, NEW_ARRAY, NEW_INSTANCE);
        List<Integer> loweredBCIs = allocationBCIs(lowered,
                LOWERED_NEW_ARRAY, LOWERED_NEW_INSTANCE);
        Asserts.assertEquals(sourceBCIs.size(), expected,
                target + ": source allocation BCI count");
        Asserts.assertEquals(loweredBCIs, sourceBCIs,
                target + ": lowered allocations must preserve source BCI and order");
    }

    private static void assertOnlyLastSourceCheckRetained(PEATestUtils.IRBody before,
                                                           PEATestUtils.IRBody after,
                                                           Method target) {
        List<Integer> sourceBCIs = arrayStoreCheckBCIs(before);
        List<Integer> finalBCIs = arrayStoreCheckBCIs(after);
        Asserts.assertFalse(sourceBCIs.isEmpty(), target + ": missing source store check BCI");
        Asserts.assertEquals(finalBCIs, List.of(sourceBCIs.get(sourceBCIs.size() - 1)),
                target + ": PEA must retain the unknown/incompatible store check, "
                        + "not a prior compatible check");
    }

    private static List<Integer> arrayStoreCheckBCIs(PEATestUtils.IRBody body) {
        ArrayList<Integer> result = new ArrayList<>();
        List<String> lines = body.lines();
        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).contains(ARRAY_STORE_CHECK)) {
                continue;
            }
            Integer bci = null;
            for (int j = i; j < Math.min(lines.size(), i + 4); j++) {
                Matcher matcher = ARRAY_CHECK_PASS_BCI.matcher(lines.get(j));
                if (matcher.find()) {
                    bci = Integer.parseInt(matcher.group(1));
                    break;
                }
            }
            if (bci == null) {
                throw new AssertionError(body.methodId()
                        + ": array store check lacks a BCI-labelled pass successor");
            }
            result.add(bci);
        }
        return List.copyOf(result);
    }

    private static List<Integer> allocationBCIs(PEATestUtils.IRBody body, String... callees) {
        ArrayList<Integer> result = new ArrayList<>();
        for (String line : body.lines()) {
            boolean allocation = false;
            for (String callee : callees) {
                allocation |= line.contains(callee);
            }
            if (!allocation) {
                continue;
            }
            Matcher matcher = DEOPT_BCI.matcher(line);
            if (!matcher.find()) {
                throw new AssertionError(body.methodId() + ": allocation lacks a source BCI: "
                        + line);
            }
            result.add(Integer.parseInt(matcher.group(1)));
        }
        return List.copyOf(result);
    }

    private static int effectCount(PEATestUtils.PEARound round, String kind) {
        return (int) round.effects().stream().filter(effect -> effect.kind().equals(kind)).count();
    }

    private static int effectTargetCount(PEATestUtils.PEARound round, String kind,
                                         String targetSubstring) {
        return (int) round.effects().stream()
                .filter(effect -> effect.kind().equals(kind))
                .filter(effect -> effect.detail().contains(targetSubstring))
                .count();
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
        public interface Component {
        }

        public static class Impl implements Component {
        }

        public static class Box {
            public int x;
        }

        private static String[] published;

        public static void main(String[] args) throws Exception {
            new Impl();
            new Box();
            published = null;
            PEATestUtils.compileConfiguredTargetsAtLevel4();

            long digest = 0x13198A2E03707344L;
            int stringAndNull = testStringAndNull();
            Impl implementation = new Impl();
            int component = testComponentArray(implementation);
            int covariance = testCompatibleCovariance("covariant");
            int incompatible = testExactIncompatible();
            int unknownCompatible = testUnknownValue("unknown-compatible");
            int unknownNull = testUnknownValue(null);
            int unknownIncompatible = testUnknownValue(new Box());
            int materializedCompatible = testMaterializedArray("materialized-compatible");
            int materializedNull = testMaterializedArray(null);
            int materializedIncompatible = testMaterializedArray(new Box());
            int primitive = testPrimitiveArray();

            Asserts.assertEquals(stringAndNull, 6);
            Asserts.assertEquals(component, 17);
            Asserts.assertEquals(covariance, 71);
            Asserts.assertEquals(incompatible, 73);
            Asserts.assertEquals(unknownCompatible, 81);
            Asserts.assertEquals(unknownNull, 82);
            Asserts.assertEquals(unknownIncompatible, 83);
            Asserts.assertEquals(materializedCompatible, 91);
            Asserts.assertEquals(materializedNull, 92);
            Asserts.assertEquals(materializedIncompatible, 93);
            Asserts.assertEquals(primitive, 10);

            for (int value : new int[] {stringAndNull, component, covariance, incompatible,
                    unknownCompatible, unknownNull, unknownIncompatible,
                    materializedCompatible, materializedNull, materializedIncompatible,
                    primitive}) {
                digest = mix(digest, value);
            }
            System.out.println("PEA-RESULT:" + Long.toUnsignedString(digest, 16));
        }

        public static int testStringAndNull() {
            String[] array = new String[3];
            array[0] = "aa";
            array[1] = null;
            array[2] = "b";
            return array[0].length() + (array[1] == null ? 3 : 0) + array[2].length();
        }

        public static int testComponentArray(Impl value) {
            Component[] array = new Component[1];
            array[0] = value;
            return array[0] == value ? 17 : -17;
        }

        public static int testCompatibleCovariance(String value) {
            Object[] array = new String[1];
            array[0] = "sentinel";
            array[0] = value;
            return array[0] == value ? 71 : -71;
        }

        public static int testExactIncompatible() {
            Object[] array = new String[1];
            Object sentinel = "exact-sentinel";
            array[0] = sentinel;
            Box incompatible = new Box();
            try {
                array[0] = incompatible;
                return -73;
            } catch (ArrayStoreException expected) {
                return array[0] == sentinel ? 73 : -730;
            }
        }

        public static int testUnknownValue(Object value) {
            Object[] array = new String[1];
            Object sentinel = "unknown-sentinel";
            array[0] = sentinel;
            try {
                array[0] = value;
                if (array[0] != value) {
                    return -810;
                }
                return value == null ? 82 : 81;
            } catch (ArrayStoreException expected) {
                return array[0] == sentinel ? 83 : -830;
            }
        }

        public static int testMaterializedArray(Object value) {
            String[] concrete = new String[1];
            Object[] array = concrete;
            Object sentinel = "materialized-sentinel";
            array[0] = sentinel;
            published = concrete;
            try {
                array[0] = value;
                if (published[0] != value) {
                    return -910;
                }
                return value == null ? 92 : 91;
            } catch (ArrayStoreException expected) {
                return published[0] == sentinel ? 93 : -930;
            }
        }

        public static int testPrimitiveArray() {
            int[] array = new int[3];
            array[0] = 2;
            array[1] = 3;
            array[2] = 5;
            return array[0] + array[1] + array[2];
        }

        private static long mix(long digest, int value) {
            return Long.rotateLeft(digest ^ (value & 0xFFFF_FFFFL), 7)
                    * 0x9E3779B97F4A7C15L;
        }
    }
}
