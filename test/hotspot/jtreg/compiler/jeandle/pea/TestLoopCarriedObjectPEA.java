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
 * version 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 */

/*
 * @test
 * @summary PEA loop-carried object across back edges: same-identity carry (Case B),
 *          conditional in-loop replacement by a fresh VO (Case C at the header,
 *          identity-unobservable), identity-observed replacement (must stay
 *          conservative), offset-0 wrapper carry, and identity compares folding
 * @library /test/lib /
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.pea.TestLoopCarriedObjectPEA
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;

import jdk.test.lib.Asserts;

public class TestLoopCarriedObjectPEA {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestLoopCarriedObjectPEA$TestWrapper";

    public static void main(String[] args) throws Exception {
        PEATestUtils.assertPhiParserContracts();

        Method same = TestWrapper.class.getMethod("sameVOCarry", int.class);
        Method replace = TestWrapper.class.getMethod("conditionalReplacement", int.class);
        Method idObs = TestWrapper.class.getMethod("replacementIdentityObserved", int.class);
        Method oneBranch = TestWrapper.class.getMethod("oneBranchKeepsAlias",
                int.class, boolean.class);
        Method idCmp = TestWrapper.class.getMethod("identityCompareInLoop", int.class);
        Method cast = TestWrapper.class.getMethod("carryViaCast", int.class);
        Method arrElem = TestWrapper.class.getMethod("arrayElementDerivedAccess", int.class);
        Method[] targets = {same, replace, idObs, oneBranch, idCmp, cast, arrElem};

        PEATestUtils.behaviorRun(WRAPPER, targets).runPEAOnOffEquivalent();

        // peaIterations(4): the carried Case-C shape (conditionalReplacement) needs the
        // PEAStableRounds 2-round stable window after its merge-flow work, so the strict
        // fixpoint is first observed at 4 rounds. The pure Case-B and derived-carry shapes
        // converge within the default 2 and are unaffected by the extra idle rounds. The
        // behavior run keeps the default to validate production settings.
        try (PEATestUtils.RunResult run = PEATestUtils.shapeRun(WRAPPER, targets).peaIterations(4).run()) {
            assertNeverEscapeCarry(run, same, 1);
            assertNeverEscapeCarry(run, idCmp, 1);
            assertNeverEscapeCarry(run, cast, 1);
            assertNeverEscapeCarry(run, arrElem, 2);
            assertNeverEscapeCarry(run, oneBranch, 2);
            assertConditionalReplacement(run, replace);
            assertIdentityObservedConservative(run, idObs);
        }
    }

    private static void assertNeverEscapeCarry(PEATestUtils.RunResult run, Method target,
                                               int sourceCount) throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();
        Asserts.assertEquals(before.peaAllocCount(), sourceCount,
                target + ": source allocation count");
        Asserts.assertTrue(after.allocationBCIs().isEmpty(),
                target + ": NeverEscape carry eliminates every allocation");
        after.assertAbsent("jeandle.new_instance");
        after.assertAbsent("poison");
        Asserts.assertTrue(report.maxNeverEscapes() >= 1,
                target + ": classified NeverEscape in some round");
        Asserts.assertTrue(report.effects("EliminateAllocation").size() >= sourceCount,
                target + ": every source allocation eliminated by PEA");
        report.assertFinalTransformIdle();
        assertVerifierShape(run, report, target);
    }

    // Graal virtualPhiLoop shape: the carried local is conditionally replaced by a
    // fresh VO inside the loop, identity-unobservable. Graal (with removeIdentity)
    // synthesizes one merged VO and eliminates both allocations. Jeandle matches:
    // the header Case C merges the preheader VO with the in-loop join synthetic,
    // the phi-keyed Case-C cache keeps the merged ObjectID stable across loop
    // fixpoint passes, and both allocations are eliminated. The field state must
    // reset on the replace edge, not carry an ever-incrementing chain.
    private static void assertConditionalReplacement(PEATestUtils.RunResult run, Method target)
            throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();
        Asserts.assertEquals(before.peaAllocCount(), 2,
                target + ": two source allocations (initial + in-loop replacement)");
        Asserts.assertTrue(after.allocationBCIs().isEmpty(),
                target + ": header Case C merges the carried VO; both allocations eliminated");
        after.assertAbsent("jeandle.new_instance");
        after.assertAbsent("poison");
        Asserts.assertTrue(report.maxNeverEscapes() >= 2,
                target + ": both allocations classified NeverEscape in some round");
        Asserts.assertTrue(report.effects("EliminateAllocation").size() >= 2,
                target + ": every source allocation eliminated by PEA");
        report.assertFinalTransformIdle();
        assertVerifierShape(run, report, target);
    }

    // The replacement is identity-observable (p == first), so Case C must be refused
    // and the carried object stays conservative rather than being merged into a
    // synthetic VO.
    private static void assertIdentityObservedConservative(PEATestUtils.RunResult run,
                                                           Method target) throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.IRBody after = report.finalAfter();
        after.assertAbsent("poison");
        Asserts.assertTrue(report.maxPartiallyEscapes() >= 1
                        || report.maxAlwaysEscapes() >= 1
                        || !after.allocationBCIs().isEmpty(),
                target + ": identity-observed replacement stays conservative");
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
        private static final String EXPECTED_DIGEST = "1db7df4ee2fcd925";

        public static class Point {
            int x;
        }

        public static void main(String[] args) throws Exception {
            new Point();
            PEATestUtils.compileConfiguredTargetsAtLevel4();

            long digest = 0x9E3779B97F4A7C15L;
            for (int n : new int[] {0, 1, 10}) {
                digest = mix(digest, sameVOCarry(n));
                digest = mix(digest, identityCompareInLoop(n));
                digest = mix(digest, carryViaCast(n));
                digest = mix(digest, arrayElementDerivedAccess(n));
            }
            for (int n : new int[] {0, 1, 99, 250}) {
                digest = mix(digest, conditionalReplacement(n));
                digest = mix(digest, replacementIdentityObserved(n));
            }
            for (int n : new int[] {0, 1, 6}) {
                for (boolean replace : new boolean[] {false, true}) {
                    digest = mix(digest, oneBranchKeepsAlias(n, replace));
                }
            }

            String payload = Long.toUnsignedString(digest, 16);
            if (EXPECTED_DIGEST != null) {
                Asserts.assertEquals(payload, EXPECTED_DIGEST, "behavior digest");
            }
            System.out.println("PEA-RESULT:" + payload);
        }

        public static int sameVOCarry(int n) {
            Point p = new Point();
            p.x = 0;
            int sum = 0;
            for (int i = 0; i < n; i++) {
                p.x += i;
                sum += p.x;
            }
            return sum + p.x;
        }

        public static int conditionalReplacement(int trips) {
            Point p = new Point();
            p.x = 0;
            for (int i = 0; i < trips; i++) {
                p.x++;
                if (p.x >= 100) {
                    p = new Point();
                }
            }
            return p.x;
        }

        public static int replacementIdentityObserved(int trips) {
            Point p = new Point();
            p.x = 0;
            Point first = p;
            int same = 0;
            for (int i = 0; i < trips; i++) {
                p.x++;
                if (p.x >= 100) {
                    p = new Point();
                }
                if (p == first) {
                    same++;
                }
            }
            return p.x + same;
        }

        public static int oneBranchKeepsAlias(int n, boolean replace) {
            Point p = new Point();
            p.x = 0;
            int sum = 0;
            for (int i = 0; i < n; i++) {
                Point q = p;
                if (replace) {
                    q = new Point();
                    q.x = 7;
                } else {
                    q.x += i;
                }
                sum += q.x;
            }
            return sum + p.x;
        }

        public static int identityCompareInLoop(int n) {
            Point p = new Point();
            p.x = 0;
            Point alias = p;
            int same = 0;
            for (int i = 0; i < n; i++) {
                p.x += i;
                if (p == alias) {
                    same++;
                }
            }
            return p.x + same;
        }

        public static int carryViaCast(int n) {
            Point p = new Point();
            p.x = 0;
            Object o = p;
            int sum = 0;
            for (int i = 0; i < n; i++) {
                Point q = (Point) o;
                q.x += i;
                sum += q.x;
            }
            return sum;
        }

        public static int arrayElementDerivedAccess(int n) {
            Point[] arr = new Point[1];
            arr[0] = new Point();
            arr[0].x = 0;
            int sum = 0;
            for (int i = 0; i < n; i++) {
                arr[0].x += i;
                sum += arr[0].x;
            }
            return sum;
        }

        private static long mix(long digest, int value) {
            return Long.rotateLeft(digest ^ Integer.toUnsignedLong(value), 17)
                    * 0x9E3779B97F4A7C15L;
        }
    }
}
