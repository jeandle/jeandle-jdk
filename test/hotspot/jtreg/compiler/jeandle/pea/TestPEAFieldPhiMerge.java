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
 * @summary PEA turns a never-escaping allocation's per-field uses into scalar
 *          PHIs across 2/3/4-way merges, including reference fields (null vs
 *          oop) and nested VORef fields (Case C); every PHI stays complete
 * @library /test/lib /
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      -XX:-UseCompressedOops -XX:-UseCompressedClassPointers
 *      compiler.jeandle.pea.TestPEAFieldPhiMerge
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;

import jdk.test.lib.Asserts;

public class TestPEAFieldPhiMerge {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestPEAFieldPhiMerge$TestWrapper";

    public static void main(String[] args) throws Exception {
        PEATestUtils.assertPhiParserContracts();

        Method scalar = TestWrapper.class.getMethod("scalarFieldPhi",
                boolean.class, int.class, int.class, long.class, long.class);
        Method ref = TestWrapper.class.getMethod("refFieldPhi",
                boolean.class, TestWrapper.Node.class, TestWrapper.Node.class);
        Method nullOop = TestWrapper.class.getMethod("nullVsOopRefPhi", boolean.class);
        Method sw3 = TestWrapper.class.getMethod("switchThreeFieldPhi", int.class);
        Method sw4 = TestWrapper.class.getMethod("switchFourFieldPhi", int.class);
        Method multi = TestWrapper.class.getMethod("multiFieldIndependentPhi",
                boolean.class, int.class, int.class, int.class, int.class);
        Method nested = TestWrapper.class.getMethod("nestedVORefPhi", boolean.class);
        Method[] targets = {scalar, ref, nullOop, sw3, sw4, multi, nested};

        PEATestUtils.behaviorRun(WRAPPER, targets).runPEAOnOffEquivalent();

        assertNestedDefaultIterations(nested);

        // Four configured rounds leave enough budget to observe the first
        // unchanged complete round after the two productive Case-C rounds.
        try (PEATestUtils.RunResult run =
                PEATestUtils.shapeRun(WRAPPER, targets).peaIterations(4).run()) {
            assertNeverEscapeMerge(run, scalar);
            assertNeverEscapeMerge(run, ref);
            assertNullVsOop(run, nullOop);
            assertNeverEscapeMerge(run, sw3);
            assertNeverEscapeMerge(run, sw4);
            assertNeverEscapeMerge(run, multi);
            assertNeverEscapeMerge(run, nested);
            PEATestUtils.PEAReport nestedReport = run.report(nested);
            Asserts.assertEquals(nestedReport.roundCount(), 3,
                    nested + ": diagnostic run reaches the exact fixpoint");
            Asserts.assertEquals(nestedReport.transformChangedRoundCount(), 2,
                    nested + ": diagnostic cap does not add productive rounds");
            Asserts.assertEquals(nestedReport.transformIdleRoundCount(), 1,
                    nested + ": fixpoint needs one unchanged complete round");
            nestedReport.assertStoppedAtFixpoint();
        }
    }

    private static void assertNestedDefaultIterations(Method target) throws Exception {
        try (PEATestUtils.RunResult run = PEATestUtils.shapeRun(WRAPPER, target).run()) {
            PEATestUtils.PEAReport report = run.report(target);
            Asserts.assertEquals(report.round0Before().allocationBCIs().size(), 3,
                    target + ": holder and two nested source allocations");
            Asserts.assertEquals(report.roundCount(), 2,
                    target + ": default PEA cap");
            Asserts.assertEquals(report.transformChangedRoundCount(), 2,
                    target + ": both default rounds are productive");
            Asserts.assertEquals(report.transformIdleRoundCount(), 0,
                    target + ": default cap has no trailing idle probe");
            report.assertStoppedAtIterationCap();

            PEATestUtils.PEARound mergeRound = report.round(0);
            Asserts.assertEquals(mergeRound.neverEscapes(), 1,
                    target + ": holder is eliminated in the first round");
            Asserts.assertEquals(mergeRound.partiallyEscapes(), 2,
                    target + ": nested children materialize at predecessor exits");
            Asserts.assertEquals(mergeRound.alwaysEscapes(), 0,
                    target + ": no source allocation always escapes");
            Asserts.assertEquals(mergeRound.effectCount("Materialize"), 2L,
                    target + ": both nested children materialize");
            mergeRound.uniqueEffect("Materialize", "[VO=1]");
            mergeRound.uniqueEffect("Materialize", "[VO=2]");
            Asserts.assertEquals(mergeRound.effectCount("Materialize", "[VO=0]"), 0L,
                    target + ": holder remains virtual");
            mergeRound.uniqueEffect("EliminateAllocation", "[VO=0]");

            PEATestUtils.PEARound caseCRound = report.round(1);
            Asserts.assertEquals(caseCRound.neverEscapes(), 2,
                    target + ": explicit object PHI enables Case C");
            Asserts.assertEquals(caseCRound.partiallyEscapes(), 0,
                    target + ": Case C keeps both children virtual");
            Asserts.assertEquals(caseCRound.alwaysEscapes(), 0,
                    target + ": Case C has no escaping allocation");
            Asserts.assertEquals(caseCRound.effectCount("EliminateAllocation"), 2L,
                    target + ": Case C eliminates both child allocations");
            caseCRound.uniqueEffect("EliminateAllocation", "[VO=0]");
            caseCRound.uniqueEffect("EliminateAllocation", "[VO=1]");
            Asserts.assertEquals(caseCRound.effectCount("CreatePHI"), 1L,
                    target + ": Case C creates the scalar field PHI");
            Asserts.assertEquals(caseCRound.effectCount("Materialize"), 0L,
                    target + ": Case C does not materialize either child");

            report.finalAfter().assertAbsent("jeandle.new_instance");
            assertVerifierShape(run, report, target);
        }
    }

    private static void assertNullVsOop(PEATestUtils.RunResult run, Method target)
            throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();
        // The RefHolder stays NeverEscape (eliminated). Its `ref` field is a
        // [VirtualRef(n), null] merge; merging a virtual ref with a non-virtual
        // (null) materializes the inner Node n at the predecessor. This matches
        // Graal's mergeObjectEntry, which ensureMaterialized's the inner virtual
        // rather than keeping both objects virtual (as Case C would for two
        // compatible virtuals — see nestedVORefPhi).
        Asserts.assertEquals(before.allocationBCIs().size(), 2,
                target + ": holder plus inner node source allocations");
        Asserts.assertEquals(after.allocationBCIs().size(), 1,
                target + ": holder eliminated, inner node retained");
        Asserts.assertTrue(before.allocationBCIs().containsAll(after.allocationBCIs()),
                target + ": retained allocation is a source allocation");
        Asserts.assertTrue(report.round(0).neverEscapes() >= 1,
                target + ": holder classified NeverEscape");
        Asserts.assertTrue(report.round(0).partiallyEscapes() >= 1,
                target + ": inner node materialized at the field merge");
        after.assertAbsent("poison");
        report.assertFinalTransformIdle();
        assertVerifierShape(run, report, target);
    }

    private static void assertNeverEscapeMerge(PEATestUtils.RunResult run,
                                               Method target) throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();
        Asserts.assertTrue(!before.allocationBCIs().isEmpty(),
                target + ": method has at least one source allocation");
        int sourceCount = before.allocationBCIs().size();
        Asserts.assertTrue(after.allocationBCIs().isEmpty(),
                target + ": NeverEscape field-PHI merge eliminates all allocations");
        after.assertAbsent("jeandle.new_instance");
        after.assertAbsent("poison");
        Asserts.assertEquals(report.round(0).effectCount("EliminateAllocation"),
                (long) sourceCount, target + ": every source allocation eliminated");
        report.assertFinalTransformIdle();
        assertVerifierShape(run, report, target);
    }

    private static void assertVerifierShape(PEATestUtils.RunResult run,
                                            PEATestUtils.PEAReport report,
                                            Method target) throws Exception {
        for (PEATestUtils.PEARound round : report.rounds()) {
            round.after().assertAbsent("poison");
            PEATestUtils.assertCompletePhis(round.after(), target.toString());
        }
        PEATestUtils.IRBody finalIR = run.finalIR(target);
        finalIR.assertAbsent("poison");
        PEATestUtils.assertCompletePhis(finalIR, target.toString());
    }

    public static class TestWrapper {
        private static final String EXPECTED_DIGEST = "6bac4ea7fed7154c";

        public static class Node {
            int x;
        }

        public static class ScalarHolder {
            int i;
            long j;
        }

        public static class RefHolder {
            Node ref;
        }

        public static class TwoField {
            int a;
            int b;
        }

        public static class Wrapper {
            Node child;
        }

        public static void main(String[] args) throws Exception {
            new Node();
            new ScalarHolder();
            new RefHolder();
            new TwoField();
            new Wrapper();
            PEATestUtils.compileConfiguredTargetsAtLevel4();

            long digest = 0x254F0D7CDB10CBF1L;
            for (int seed : new int[] {0, 9}) {
                int ai = seed + 1;
                int bi = seed + 2;
                long aj = seed + 100L;
                long bj = seed + 200L;
                int a1 = seed + 3;
                int a2 = seed + 4;
                int b1 = seed + 5;
                int b2 = seed + 6;

                Node extA = new Node();
                extA.x = seed + 11;
                Node extB = new Node();
                extB.x = seed + 22;

                for (boolean which : new boolean[] {false, true}) {
                    int r = scalarFieldPhi(which, ai, bi, aj, bj);
                    int exp = (which ? ai : bi) * 31 + (int) (which ? aj : bj);
                    Asserts.assertEquals(r, exp, "scalarFieldPhi");
                    digest = mix(digest, r);
                }
                for (boolean which : new boolean[] {false, true}) {
                    int r = refFieldPhi(which, extA, extB);
                    Asserts.assertEquals(r, which ? extA.x : extB.x, "refFieldPhi");
                    digest = mix(digest, r);
                }
                for (boolean which : new boolean[] {false, true}) {
                    int r = nullVsOopRefPhi(which);
                    Asserts.assertEquals(r, which ? 7 : -1, "nullVsOopRefPhi");
                    digest = mix(digest, r);
                }
                for (int sel : new int[] {0, 1, 2}) {
                    int r = switchThreeFieldPhi(sel);
                    Asserts.assertEquals(r, sel == 0 ? 10 : sel == 1 ? 20 : 30,
                            "switchThreeFieldPhi");
                    digest = mix(digest, r);
                }
                for (int sel : new int[] {0, 1, 2, 3}) {
                    int r = switchFourFieldPhi(sel);
                    Asserts.assertEquals(r, sel == 0 ? 11 : sel == 1 ? 22
                            : sel == 2 ? 33 : 44, "switchFourFieldPhi");
                    digest = mix(digest, r);
                }
                for (boolean which : new boolean[] {false, true}) {
                    int r = multiFieldIndependentPhi(which, a1, a2, b1, b2);
                    Asserts.assertEquals(r, which ? a1 * 31 + a2 : b1 * 31 + b2,
                            "multiFieldIndependentPhi");
                    digest = mix(digest, r);
                }
                for (boolean which : new boolean[] {false, true}) {
                    int r = nestedVORefPhi(which);
                    Asserts.assertEquals(r, which ? 1 : 2, "nestedVORefPhi");
                    digest = mix(digest, r);
                }
            }

            String payload = Long.toUnsignedString(digest, 16);
            Asserts.assertEquals(payload, EXPECTED_DIGEST, "behavior digest");
            System.out.println("PEA-RESULT:" + payload);
        }

        public static int scalarFieldPhi(boolean which, int ai, int bi, long aj, long bj) {
            ScalarHolder h = new ScalarHolder();
            if (which) {
                h.i = ai;
                h.j = aj;
            } else {
                h.i = bi;
                h.j = bj;
            }
            return h.i * 31 + (int) h.j;
        }

        public static int refFieldPhi(boolean which, Node a, Node b) {
            RefHolder h = new RefHolder();
            if (which) {
                h.ref = a;
            } else {
                h.ref = b;
            }
            return h.ref.x;
        }

        public static int nullVsOopRefPhi(boolean which) {
            RefHolder h = new RefHolder();
            if (which) {
                Node n = new Node();
                n.x = 7;
                h.ref = n;
            } else {
                h.ref = null;
            }
            return h.ref == null ? -1 : h.ref.x;
        }

        public static int switchThreeFieldPhi(int sel) {
            ScalarHolder h = new ScalarHolder();
            switch (sel) {
                case 0:
                    h.i = 10;
                    break;
                case 1:
                    h.i = 20;
                    break;
                default:
                    h.i = 30;
            }
            return h.i;
        }

        public static int switchFourFieldPhi(int sel) {
            ScalarHolder h = new ScalarHolder();
            switch (sel) {
                case 0:
                    h.i = 11;
                    break;
                case 1:
                    h.i = 22;
                    break;
                case 2:
                    h.i = 33;
                    break;
                default:
                    h.i = 44;
            }
            return h.i;
        }

        public static int multiFieldIndependentPhi(boolean which,
                                                   int a1, int a2, int b1, int b2) {
            TwoField h = new TwoField();
            if (which) {
                h.a = a1;
                h.b = a2;
            } else {
                h.a = b1;
                h.b = b2;
            }
            return h.a * 31 + h.b;
        }

        public static int nestedVORefPhi(boolean which) {
            Wrapper w = new Wrapper();
            if (which) {
                Node a = new Node();
                a.x = 1;
                w.child = a;
            } else {
                Node b = new Node();
                b.x = 2;
                w.child = b;
            }
            return w.child.x;
        }

        private static long mix(long digest, int value) {
            return Long.rotateLeft(digest ^ Integer.toUnsignedLong(value), 23)
                    * 0x9E3779B97F4A7C15L;
        }
    }
}
