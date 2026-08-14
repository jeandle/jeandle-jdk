/*
 * Copyright (c) 2026, the Jeandle-JDK Authors. All Rights Reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only,
 * as published by the Free Software Foundation.
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
 * @summary PEA outer iterations converge without accumulating materialization
 *          replay or merge PHIs
 * @library /test/lib /
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      -XX:-UseCompressedOops -XX:-UseCompressedClassPointers
 *      compiler.jeandle.pea.TestPartiallyEscapesMaterializeConvergence
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import jdk.test.lib.Asserts;

public class TestPartiallyEscapesMaterializeConvergence {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestPartiallyEscapesMaterializeConvergence$TestWrapper";
    private static final String MATERIALIZED_PHI = "pea.materialized.phi";
    private static final String FIELD_PHI = "pea.field.phi";
    private static final String CASE_C_FIELD_PHI = "pea.casec.field.phi";

    public static void main(String[] args) throws Exception {
        Method conditional = TestWrapper.class.getMethod(
                "conditionalSink", boolean.class, int.class, int.class);
        Method branchDeletion = TestWrapper.class.getMethod(
                "branchDeletion", int.class);
        Method loopRollback = TestWrapper.class.getMethod(
                "loopRollback", int.class, boolean.class, int.class);
        Method sink = TestWrapper.class.getMethod("sink", TestWrapper.Point.class);
        Method[] targets = {conditional, branchDeletion, loopRollback};

        PEATestUtils.behaviorRun(WRAPPER, targets)
                .peaIterations(4)
                .dontinline(sink)
                .runPEAOnOffEquivalent();

        ShapeRun cap1 = runShape(1, targets, sink);
        ShapeRun cap2 = runShape(2, targets, sink);
        ShapeRun cap4 = runShape(4, targets, sink);
        ShapeRun cap16 = runShape(16, targets, sink);

        assertConditionalMerge(cap1.report(conditional), cap2.report(conditional),
                cap4.report(conditional), cap16.report(conditional), conditional);
        assertBranchDeletion(cap1.report(branchDeletion), cap2.report(branchDeletion),
                cap4.report(branchDeletion), cap16.report(branchDeletion), branchDeletion);
        assertLoopRollback(cap1.report(loopRollback), cap2.report(loopRollback),
                cap4.report(loopRollback), cap16.report(loopRollback), loopRollback);

        for (Method target : targets) {
            cap16.report(target).finalAfter().assertCrossProcessExactEquals(
                    cap4.report(target).finalAfter(),
                    target + ": cap 4 and cap 16 reach exact stable final IR");
        }
    }

    private static ShapeRun runShape(int cap, Method[] targets, Method sink)
            throws Exception {
        try (PEATestUtils.RunResult run = PEATestUtils.shapeRun(WRAPPER, targets)
                .peaIterations(cap)
                .dontinline(sink)
                .run()) {
            PEATestUtils.PEAReport[] reports = new PEATestUtils.PEAReport[targets.length];
            for (int i = 0; i < targets.length; i++) {
                Method target = targets[i];
                PEATestUtils.PEAReport report = run.report(target);
                reports[i] = report;
                Asserts.assertTrue(report.roundCount() >= 1 && report.roundCount() <= cap,
                        target + ": outer iteration count respects cap " + cap);
                if (cap >= 4) {
                    Asserts.assertTrue(
                            report.round(report.roundCount() - 1).transformIdle(),
                            target + ": high-cap run ends in an idle transform");
                }
                assertNoAccumulatedEffects(report, target);
            }
            return new ShapeRun(targets, reports);
        }
    }

    private static void assertNoAccumulatedEffects(PEATestUtils.PEAReport report,
                                                    Method target) {
        for (PEATestUtils.PEARound round : report.rounds()) {
            PEATestUtils.IRBody after = round.after();
            PEATestUtils.assertStructuralSoundness(after,
                    target + ": round " + round.iteration() + " after");
            Asserts.assertTrue(after.occurrenceCount(MATERIALIZED_PHI) <= 2,
                    target + ": no accumulated materialized-object PHI");
            Asserts.assertTrue(after.occurrenceCount(FIELD_PHI) <= 2,
                    target + ": no accumulated field PHI");
            Asserts.assertTrue(after.occurrenceCount(CASE_C_FIELD_PHI) <= 2,
                    target + ": no accumulated Case-C field PHI");
        }
        PEATestUtils.assertStructuralSoundness(report.finalAfter(),
                target + ": final post-canonicalization IR");
    }

    private static void assertConditionalMerge(PEATestUtils.PEAReport cap1,
                                               PEATestUtils.PEAReport cap2,
                                               PEATestUtils.PEAReport cap4,
                                               PEATestUtils.PEAReport cap16,
                                               Method target) {
        assertStablePartialCaps(cap1, cap2, cap4, cap16, target);
        PEATestUtils.IRBody before = cap4.round0Before();
        PEATestUtils.IRBody after = cap4.finalAfter();
        Asserts.assertEquals(before.peaAllocCount(), 1,
                target + ": one conditional object before PEA");
        Asserts.assertEquals(after.allocationBCIs(), before.allocationBCIs(),
                target + ": the escaping arm retains the original allocation site");
        after.assertLineCount("store atomic i32", 4);
        after.assertPresent("load atomic i32");
        boolean sawMaterialize = false;
        for (PEATestUtils.PEARound round : cap4.rounds()) {
            long materializations = round.effectCount("Materialize", "[VO=0]");
            sawMaterialize |= materializations == 2;
            Asserts.assertTrue(materializations <= 2,
                    target + ": one replay sequence per each of two escape points");
        }
        Asserts.assertTrue(sawMaterialize,
                target + ": the call arm materializes the virtual object");
    }

    private static void assertBranchDeletion(PEATestUtils.PEAReport cap1,
                                             PEATestUtils.PEAReport cap2,
                                             PEATestUtils.PEAReport cap4,
                                             PEATestUtils.PEAReport cap16,
                                             Method target) {
        Asserts.assertEquals(cap1.roundCount(), 1,
                target + ": cap 1 performs exactly one sound, non-converged round");
        Asserts.assertEquals(cap2.roundCount(), 2,
                target + ": cap 2 performs the enabled follow-up optimization");
        Asserts.assertEquals(cap4.roundCount(), 3,
                target + ": cap 4 reaches fixpoint in one unchanged complete round");
        Asserts.assertEquals(cap16.roundCount(), 3,
                target + ": cap 16 stops at the same fixpoint as cap 4");
        cap1.assertStoppedAtIterationCap();
        cap2.assertStoppedAtIterationCap();
        cap4.assertStoppedAtFixpoint();
        cap16.assertStoppedAtFixpoint();

        PEATestUtils.IRBody before = cap1.round0Before();
        Asserts.assertEquals(before.peaAllocCount(), 2,
                target + ": guard and candidate allocations before PEA");
        Asserts.assertEquals(cap1.finalAfter().peaAllocCount(), 1,
                target + ": cap 1 leaves the candidate soundly materialized");
        Asserts.assertEquals(cap2.finalAfter().peaAllocCount(), 0,
                target + ": deleted branch enables candidate elimination in round 2");
        Asserts.assertFalse(cap1.round(0).transformIdle(),
                target + ": round 1 folds the virtual guard field");
        Asserts.assertFalse(cap2.round(1).transformIdle(),
                target + ": round 2 eliminates the newly non-escaping candidate");
        Asserts.assertTrue(cap4.round(2).transformIdle(),
                target + ": unchanged complete round reaches the fixpoint");
        cap4.finalAfter().assertAbsent("@jeandle.new_instance");
        cap4.finalAfter().assertAbsent("store atomic");
        cap4.finalAfter().assertAbsent("load atomic");
    }

    private static void assertLoopRollback(PEATestUtils.PEAReport cap1,
                                           PEATestUtils.PEAReport cap2,
                                           PEATestUtils.PEAReport cap4,
                                           PEATestUtils.PEAReport cap16,
                                           Method target) {
        assertStablePartialCaps(cap1, cap2, cap4, cap16, target);
        PEATestUtils.IRBody before = cap4.round0Before();
        PEATestUtils.IRBody after = cap4.finalAfter();
        Asserts.assertEquals(before.peaAllocCount(), 1,
                target + ": one loop-carried allocation before PEA");
        Asserts.assertEquals(after.allocationBCIs(), before.allocationBCIs(),
                target + ": possible loop escape retains the source allocation");
        after.assertPresent("br i1");
        after.assertPresent("phi i32");
        after.assertLineCount("store atomic i32", 3);
        for (PEATestUtils.PEARound round : cap4.rounds()) {
            Asserts.assertTrue(round.after().lineCount("store atomic i32") <= 3,
                    target + ": loop retry does not duplicate field replay");
        }
    }

    private static void assertStablePartialCaps(PEATestUtils.PEAReport cap1,
                                                PEATestUtils.PEAReport cap2,
                                                PEATestUtils.PEAReport cap4,
                                                PEATestUtils.PEAReport cap16,
                                                Method target) {
        Asserts.assertEquals(cap1.roundCount(), 1,
                target + ": cap 1 performs one sound transform");
        Asserts.assertEquals(cap2.roundCount(), 2,
                target + ": cap 2 reaches an idle transform");
        Asserts.assertEquals(cap4.roundCount(), 2,
                target + ": cap 4 stops on the first unchanged complete round");
        Asserts.assertEquals(cap16.roundCount(), 2,
                target + ": cap 16 stops at the same exact fixpoint");
        cap1.assertStoppedAtIterationCap();
        cap2.assertStoppedAtFixpoint();
        cap4.assertStoppedAtFixpoint();
        cap16.assertStoppedAtFixpoint();
        Asserts.assertFalse(cap1.round(0).transformIdle(),
                target + ": the initial partial-escape replay mutates IR");
        Asserts.assertTrue(cap2.round(1).transformIdle(),
                target + ": exact replay reuse makes the second transform idle");
        Asserts.assertEquals(ShapeSummary.of(cap2.finalAfter()),
                ShapeSummary.of(cap4.finalAfter()),
                target + ": cap 2 is sound and already has the stable IR shape");
    }

    private static final class ShapeRun {
        private final Method[] targets;
        private final PEATestUtils.PEAReport[] reports;

        private ShapeRun(Method[] targets, PEATestUtils.PEAReport[] reports) {
            this.targets = targets.clone();
            this.reports = reports.clone();
        }

        PEATestUtils.PEAReport report(Method target) {
            return reports[indexOf(target)];
        }

        private int indexOf(Method target) {
            int index = Arrays.asList(targets).indexOf(target);
            if (index < 0) {
                throw new IllegalArgumentException("Unknown target " + target);
            }
            return index;
        }
    }

    private static final class ShapeSummary {
        private final List<Integer> allocationBCIs;
        private final int stores;
        private final int loads;
        private final int materializedPhis;
        private final int fieldPhis;
        private final int caseCPhis;

        private ShapeSummary(List<Integer> allocationBCIs, int stores, int loads,
                             int materializedPhis, int fieldPhis, int caseCPhis) {
            this.allocationBCIs = allocationBCIs;
            this.stores = stores;
            this.loads = loads;
            this.materializedPhis = materializedPhis;
            this.fieldPhis = fieldPhis;
            this.caseCPhis = caseCPhis;
        }

        static ShapeSummary of(PEATestUtils.IRBody body) {
            return new ShapeSummary(body.allocationBCIs(),
                    body.lineCount("store atomic"), body.lineCount("load atomic"),
                    body.occurrenceCount(MATERIALIZED_PHI),
                    body.occurrenceCount(FIELD_PHI),
                    body.occurrenceCount(CASE_C_FIELD_PHI));
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof ShapeSummary summary)) {
                return false;
            }
            return allocationBCIs.equals(summary.allocationBCIs)
                    && stores == summary.stores
                    && loads == summary.loads
                    && materializedPhis == summary.materializedPhis
                    && fieldPhis == summary.fieldPhis
                    && caseCPhis == summary.caseCPhis;
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(new Object[] {allocationBCIs, stores, loads,
                    materializedPhis, fieldPhis, caseCPhis});
        }

        @Override
        public String toString() {
            return "allocBCIs=" + allocationBCIs + ", stores=" + stores
                    + ", loads=" + loads + ", materializedPhis=" + materializedPhis
                    + ", fieldPhis=" + fieldPhis + ", caseCPhis=" + caseCPhis;
        }
    }

    public static class TestWrapper {
        public static class Point {
            int x;
            int y;
        }

        private static final String EXPECTED_PAYLOAD =
                "1122:1123:1718:3:128:129:3881895:3881896";
        static Point global;
        static int sinkCount;

        public static void main(String[] args) throws Exception {
            new Point();
            PEATestUtils.compileConfiguredTargetsAtLevel4();

            resetObservation();
            int noEscape = conditionalSink(false, 11, 22);
            Asserts.assertEquals(noEscape, 1122, "conditional no-escape return");
            Asserts.assertNull(global, "conditional no-escape identity");
            Asserts.assertEquals(sinkCount, 0, "conditional no-escape sink count");

            resetObservation();
            int escape = conditionalSink(true, 11, 22);
            Asserts.assertEquals(escape, 1123, "conditional escape return and identity");
            Asserts.assertNotNull(global, "conditional escaped object");
            Asserts.assertEquals(global.x, 11, "conditional escaped x");
            Asserts.assertEquals(global.y, 22, "conditional escaped y");
            Asserts.assertEquals(sinkCount, 1, "conditional escape sink count");

            resetObservation();
            int branch = branchDeletion(17);
            Asserts.assertEquals(branch, 1718, "branch-deletion result");
            Asserts.assertNull(global, "constant-false escape is deleted");
            Asserts.assertEquals(sinkCount, 0, "constant-false sink count");

            int loop0 = loopRollback(0, false, 3);
            int loop1 = loopRollback(1, false, 3);
            resetObservation();
            int loop1Escape = loopRollback(1, true, 3);
            Asserts.assertEquals(sinkCount, 1, "one-trip loop sink count");
            Asserts.assertNotNull(global, "one-trip escaped identity");
            Asserts.assertEquals(global.x, 4, "one-trip escaped field");
            int loop4 = loopRollback(4, false, 3);
            resetObservation();
            int loop4Escape = loopRollback(4, true, 3);
            Asserts.assertEquals(sinkCount, 1, "four-trip loop sink count");
            Asserts.assertNotNull(global, "four-trip escaped identity");
            Asserts.assertEquals(global.x, 13, "four-trip escaped field");

            String payload = noEscape + ":" + escape + ":" + branch + ":"
                    + loop0 + ":" + loop1 + ":" + loop1Escape + ":"
                    + loop4 + ":" + loop4Escape;
            Asserts.assertEquals(payload, EXPECTED_PAYLOAD, "exact behavior payload");
            System.out.println("PEA-RESULT:" + payload);
        }

        public static int conditionalSink(boolean escape, int x, int y) {
            Point point = new Point();
            point.x = x;
            point.y = y;
            if (escape) {
                sink(point);
            }
            int identity = escape && global == point ? 1 : 0;
            return point.x * 100 + point.y + identity;
        }

        public static int branchDeletion(int value) {
            Point guard = new Point();
            guard.x = 0;
            Point candidate = new Point();
            candidate.x = value;
            candidate.y = value + 1;
            if (guard.x != 0) {
                sink(candidate);
            }
            return candidate.x * 100 + candidate.y;
        }

        public static int loopRollback(int trips, boolean escapeLast, int seed) {
            Point point = new Point();
            point.x = seed;
            int sum = 0;
            for (int i = 0; i < trips; i++) {
                point.x += i + 1;
                if (escapeLast && i + 1 == trips) {
                    sink(point);
                }
                sum = sum * 31 + point.x;
            }
            int identity = escapeLast && trips > 0 && global == point ? 1 : 0;
            return sum * 31 + point.x + identity;
        }

        public static void sink(Point point) {
            sinkCount++;
            global = point;
        }

        private static void resetObservation() {
            global = null;
            sinkCount = 0;
        }
    }
}
