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
 *
 */

/*
 * @test
 * @summary Pre-PEA unswitching specializes loop-invariant object choices while
 *          PEA Case C merges the remaining compatible outside-loop state
 * @library /test/lib /
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.pea.TestInLoopMergeCaseCPEA
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;

import jdk.test.lib.Asserts;

public class TestInLoopMergeCaseCPEA {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestInLoopMergeCaseCPEA$TestWrapper";
    private static final String CASE_C_FIELD_PHI = "pea.casec.field.phi";

    public static void main(String[] args) throws Exception {
        Method compatible = TestWrapper.class.getMethod(
                "compatibleInLoop", int.class, boolean.class);
        Method identity = TestWrapper.class.getMethod(
                "identityObservableInLoop", int.class, boolean.class);
        Method differentClass = TestWrapper.class.getMethod(
                "differentClassInLoop", int.class, boolean.class);
        Method incompatibleState = TestWrapper.class.getMethod(
                "incompatibleStateInLoop", int.class, boolean.class);
        Method outsideLoop = TestWrapper.class.getMethod(
                "compatibleOutsideLoop", int.class, boolean.class);
        Method[] targets = {
                compatible, identity, differentClass, incompatibleState, outsideLoop};

        try (PEATestUtils.RunResult run =
                PEATestUtils.shapeRun(WRAPPER, targets).peaIterations(4).run()) {
            // Pre-PEA unswitching specializes the loop-invariant selector, so
            // the in-loop form no longer needs a Case-C pointer merge.
            assertCompatibleCaseC(run, compatible, 0, false, 0);
            assertCompatibleCaseC(run, identity, 0, false, 0);
            assertCompatibleCaseC(run, differentClass, 0, false, 0);
            assertCompatibleCaseC(run, incompatibleState, 0, true, 2);
            assertCompatibleCaseC(run, outsideLoop, 1, true, 0);
        }

        PEATestUtils.behaviorRun(WRAPPER, targets)
                .peaIterations(4)
                .runPEAOnOffEquivalent();
    }

    private static void assertCompatibleCaseC(PEATestUtils.RunResult run,
                                               Method target,
                                               long expectedCreatePhis,
                                               boolean expectedSourcePointerPhi,
                                               int expectedFirstMaterializations)
            throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.IRBody before = report.round0Before();
        Asserts.assertTrue(before.peaAllocCount() >= 2,
                target + ": two source allocations, plus any strip-mined clone, before PEA");
        Asserts.assertEquals(report.round(0).effectCount("EliminateAllocation"),
                (long) before.peaAllocCount(),
                target + ": every unswitched source-allocation copy is virtualized");
        Asserts.assertEquals(report.round(0).effectCount("CreatePHI"), expectedCreatePhis,
                target + ": exact Case-C merged field-value count");
        // A source-level reference merge is independent of the field-value
        // PHI that Case C may synthesize while virtualizing the allocation.
        if (expectedSourcePointerPhi) {
            before.assertPresent(" = phi ptr addrspace(1) ");
        } else {
            before.assertAbsent(" = phi ptr addrspace(1) ");
        }

        PEATestUtils.IRBody firstAfter = report.round(0).after();
        Asserts.assertEquals(firstAfter.peaAllocCount(), expectedFirstMaterializations,
                target + ": exact materializations after Case C");
        long createPhis = 0;
        for (PEATestUtils.PEARound round : report.rounds()) {
            createPhis += round.effectCount("CreatePHI");
            Asserts.assertTrue(round.after().occurrenceCount(CASE_C_FIELD_PHI) <= 2,
                    target + ": no duplicate Case-C field phi in round "
                            + round.iteration());
        }
        Asserts.assertEquals(createPhis, expectedCreatePhis,
                target + ": Case C field merge is synthesized exactly as required");
        PEATestUtils.IRBody finalAfter = report.finalAfter();
        Asserts.assertEquals(finalAfter.peaAllocCount(), 0,
                target + ": later PEA rounds remove transient materializations");
        finalAfter.assertAbsent("poison");
        Asserts.assertEquals(run.finalIR(target).loweredAllocCount(), 0,
                target + ": no lowered allocation remains");

    }

    public static class TestWrapper {
        public static class Point {
            int x;
            int y;
        }

        public static class BasePoint {
            int x;
            int y;
        }

        public static class LeftPoint extends BasePoint {}

        public static class RightPoint extends BasePoint {}

        private static final int[] TRIPS = {0, 1, 10};
        private static final int[][] COMPATIBLE = {{0, 0}, {38, 34}, {470, 430}};
        private static final int[][] IDENTITY = {{0, 0}, {99, 68}, {1215, 905}};
        private static final int[][] DIFFERENT_CLASS = {{0, 0}, {46, 42}, {550, 510}};
        private static final int[][] INCOMPATIBLE_STATE = {{0, 0}, {79, 44}, {835, 485}};
        private static final int[][] OUTSIDE_LOOP = {{60, 54}, {62, 56}, {80, 74}};

        public static void main(String[] args) throws Exception {
            new Point();
            new LeftPoint();
            new RightPoint();
            new int[1].clone();
            PEATestUtils.compileConfiguredTargetsAtLevel4();

            long digest = 0x9e3779b97f4a7c15L;
            for (int tripIndex = 0; tripIndex < TRIPS.length; tripIndex++) {
                int trips = TRIPS[tripIndex];
                for (int choiceIndex = 0; choiceIndex < 2; choiceIndex++) {
                    boolean chooseLeft = choiceIndex != 0;
                    int compatible = compatibleInLoop(trips, chooseLeft);
                    int identity = identityObservableInLoop(trips, chooseLeft);
                    int differentClass = differentClassInLoop(trips, chooseLeft);
                    int incompatibleState = incompatibleStateInLoop(trips, chooseLeft);
                    int outsideLoop = compatibleOutsideLoop(trips, chooseLeft);

                    Asserts.assertEquals(compatible,
                            COMPATIBLE[tripIndex][choiceIndex]);
                    Asserts.assertEquals(identity,
                            IDENTITY[tripIndex][choiceIndex]);
                    Asserts.assertEquals(differentClass,
                            DIFFERENT_CLASS[tripIndex][choiceIndex]);
                    Asserts.assertEquals(incompatibleState,
                            INCOMPATIBLE_STATE[tripIndex][choiceIndex]);
                    Asserts.assertEquals(outsideLoop,
                            OUTSIDE_LOOP[tripIndex][choiceIndex]);

                    digest = mix(digest, compatible);
                    digest = mix(digest, identity);
                    digest = mix(digest, differentClass);
                    digest = mix(digest, incompatibleState);
                    digest = mix(digest, outsideLoop);
                }
            }
            String payload = Long.toUnsignedString(digest, 16);
            Asserts.assertEquals(payload, "7c8a8c6e56b6ca13",
                    "hard-coded Case-C behavior digest");
            System.out.println("PEA-RESULT:" + payload);
        }

        public static int compatibleInLoop(int trips, boolean chooseLeft) {
            int sum = 0;
            for (int i = 0; i < trips; i++) {
                Point selected;
                if (chooseLeft) {
                    Point left = new Point();
                    left.x = 3 + i;
                    selected = left;
                } else {
                    Point right = new Point();
                    right.x = 7 + i;
                    selected = right;
                }
                selected.y = 31 + i;
                sum += selected.x + selected.y;
            }
            return sum;
        }

        public static int identityObservableInLoop(int trips, boolean chooseLeft) {
            int sum = 0;
            for (int i = 0; i < trips; i++) {
                Point retainedAlias = new Point();
                retainedAlias.x = 11 + i;
                Point selected;
                if (chooseLeft) {
                    selected = retainedAlias;
                } else {
                    Point right = new Point();
                    right.x = 19 + i;
                    selected = right;
                }
                selected.y = 23 + i;
                sum += selected.x * 4 + selected.y
                        + (retainedAlias == selected ? 1 : 0);
            }
            return sum;
        }

        public static int differentClassInLoop(int trips, boolean chooseLeft) {
            int sum = 0;
            for (int i = 0; i < trips; i++) {
                BasePoint selected;
                if (chooseLeft) {
                    LeftPoint left = new LeftPoint();
                    left.x = 5 + i;
                    selected = left;
                } else {
                    RightPoint right = new RightPoint();
                    right.x = 9 + i;
                    selected = right;
                }
                selected.y = 37 + i;
                sum += selected.x + selected.y;
            }
            return sum;
        }

        public static int incompatibleStateInLoop(int trips, boolean chooseLeft) {
            int sum = 0;
            for (int i = 0; i < trips; i++) {
                int[] selected;
                if (chooseLeft) {
                    int[] left = new int[1];
                    left[0] = 13 + i;
                    selected = left;
                } else {
                    int[] right = new int[2];
                    right[0] = 17 + i;
                    selected = right;
                }
                sum += selected[0] + selected.length * 31;
            }
            return sum;
        }

        public static int compatibleOutsideLoop(int seed, boolean chooseLeft) {
            Point selected;
            if (chooseLeft) {
                Point left = new Point();
                left.x = 23 + seed;
                selected = left;
            } else {
                Point right = new Point();
                right.x = 29 + seed;
                selected = right;
            }
            selected.y = 31 + seed;
            return selected.x + selected.y;
        }

        private static long mix(long digest, int value) {
            return (digest ^ Integer.toUnsignedLong(value)) * 0x100000001b3L;
        }
    }
}
