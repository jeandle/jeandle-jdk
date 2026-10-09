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
 * @summary PEA Case A/B merges keep a single allocation virtual across two or
 *          three predecessors (field-value PHI), or materialize at a
 *          predecessor when states diverge; PHI incomings stay complete
 * @library /test/lib /
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.pea.TestPEAMergeCaseAB
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jdk.test.lib.Asserts;

public class TestPEAMergeCaseAB {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestPEAMergeCaseAB$TestWrapper";

    public static void main(String[] args) throws Exception {
        PEATestUtils.assertPhiParserContracts();

        Method twoDiff = TestWrapper.class.getMethod("caseATwoPredFieldPhi",
                boolean.class, int.class, int.class);
        Method twoDefault = TestWrapper.class.getMethod("caseATwoPredDefault",
                boolean.class, int.class);
        Method three = TestWrapper.class.getMethod("caseAThreePredFieldPhi",
                int.class, int.class, int.class, int.class);
        Method sameVO = TestWrapper.class.getMethod("valuePhiSameVO",
                boolean.class, int.class, int.class, int.class);
        Method mix = TestWrapper.class.getMethod("materializeAtOnePred",
                boolean.class, int.class, int.class);
        Method eh = TestWrapper.class.getMethod("mergeWithEHPred",
                boolean.class, int.class);
        Method consume = TestWrapper.class.getMethod("consume", TestWrapper.Node.class);
        Method maybeThrow = TestWrapper.class.getMethod("maybeThrow", boolean.class);
        Method[] targets = {twoDiff, twoDefault, three, sameVO, mix, eh};

        behaviorBuilder(targets, consume, maybeThrow).runPEAOnOffEquivalent();

        try (PEATestUtils.RunResult run = shapeBuilder(targets, consume, maybeThrow).run()) {
            assertNeverEscapeMerge(run, twoDiff);
            assertNeverEscapeMerge(run, twoDefault);
            assertNeverEscapeMerge(run, three);
            assertNeverEscapeMerge(run, sameVO);
            assertMaterializeAtOnePred(run, mix, consume);
            assertNeverEscapeMerge(run, eh);
        }
    }

    private static PEATestUtils.RunBuilder behaviorBuilder(Method[] targets,
                                                           Method consume,
                                                           Method maybeThrow) {
        return PEATestUtils.behaviorRun(WRAPPER, targets)
                .dontinline(consume).dontinline(maybeThrow);
    }

    private static PEATestUtils.RunBuilder shapeBuilder(Method[] targets,
                                                        Method consume,
                                                        Method maybeThrow) {
        return PEATestUtils.shapeRun(WRAPPER, targets)
                .dontinline(consume).dontinline(maybeThrow);
    }

    private static void assertNeverEscapeMerge(PEATestUtils.RunResult run,
                                               Method target) throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();
        assertDistinctAllocations(before, 1, target);
        Asserts.assertTrue(after.allocationBCIs().isEmpty(),
                target + ": NeverEscape merge eliminates the allocation");
        after.assertAbsent("jeandle.new_instance");
        after.assertAbsent("poison");
        Asserts.assertEquals(report.round(0).effectCount("EliminateAllocation"), 1L,
                target + ": allocation eliminated exactly once");
        report.assertFinalTransformIdle();
        assertVerifierShape(run, report, target);
    }

    private static void assertMaterializeAtOnePred(PEATestUtils.RunResult run,
                                                   Method target, Method consume)
            throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();
        assertDistinctAllocations(before, 1, target);
        Asserts.assertEquals(after.allocationBCIs(), before.allocationBCIs(),
                target + ": PartiallyEscapes mix retains the source allocation");
        Asserts.assertTrue(report.round(0).effectCount("Materialize", "[VO=0]") >= 1,
                target + ": object is materialized at least once");
        String calleeName = PEATestUtils.MethodId.of(consume).llvmFunctionName();
        PEATestUtils.IRBlock callBlock = after.blockContaining(calleeName, 0);
        callBlock.assertAbsent("jeandle.new_instance");
        callBlock.assertBefore("store atomic i32", 0, calleeName, 0);
        report.assertFinalTransformIdle();
        assertVerifierShape(run, report, target);
    }

    private static void assertDistinctAllocations(PEATestUtils.IRBody body,
                                                  int expected, Method target) {
        List<Integer> bcis = body.allocationBCIs();
        Asserts.assertEquals(bcis.size(), expected, target + ": source allocation count");
        Set<Integer> distinct = new HashSet<>(bcis);
        Asserts.assertEquals(distinct.size(), expected,
                target + ": every source allocation has a distinct BCI");
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
        private static final String EXPECTED_DIGEST = "b8d9aa5d612c693b";

        public static class Node {
            int x;
        }

        public static class TestException extends RuntimeException {
            private static final long serialVersionUID = 1L;
        }

        private static int callCount;
        private static Node savedNode;

        public static void main(String[] args) throws Exception {
            new Node();
            new TestException();
            PEATestUtils.compileConfiguredTargetsAtLevel4();

            long digest = 0x9E3779B97F4A7C15L;
            for (int seed : new int[] {0, 7}) {
                int a = seed + 11;
                int b = seed + 23;
                int c = seed + 37;

                for (boolean which : new boolean[] {false, true}) {
                    int r = caseATwoPredFieldPhi(which, a, b);
                    Asserts.assertEquals(r, which ? a : b, "caseATwoPredFieldPhi");
                    digest = mix(digest, r);
                }
                for (boolean which : new boolean[] {false, true}) {
                    int r = caseATwoPredDefault(which, a);
                    Asserts.assertEquals(r, which ? a : 0, "caseATwoPredDefault");
                    digest = mix(digest, r);
                }
                for (int sel : new int[] {0, 1, 2}) {
                    int r = caseAThreePredFieldPhi(sel, a, b, c);
                    Asserts.assertEquals(r, sel == 0 ? a : sel == 1 ? b : c,
                            "caseAThreePredFieldPhi");
                    digest = mix(digest, r);
                }
                for (boolean which : new boolean[] {false, true}) {
                    int r = valuePhiSameVO(which, a, b, c);
                    Asserts.assertEquals(r, which ? b : c, "valuePhiSameVO");
                    digest = mix(digest, r);
                }
                for (boolean escape : new boolean[] {false, true}) {
                    resetObservation();
                    int r = materializeAtOnePred(escape, a, b);
                    Asserts.assertEquals(r, escape ? b : a, "materializeAtOnePred result");
                    Asserts.assertEquals(callCount, escape ? 1 : 0, "materializeAtOnePred calls");
                    digest = mix(digest, r);
                    digest = mix(digest, callCount);
                    if (escape) {
                        Asserts.assertNotNull(savedNode, "materializeAtOnePred saved identity");
                        Asserts.assertEquals(savedNode.x, b, "materializeAtOnePred saved field");
                        digest = mix(digest, savedNode.x);
                    }
                }
                for (boolean doThrow : new boolean[] {false, true}) {
                    int r = mergeWithEHPred(doThrow, a);
                    Asserts.assertEquals(r, doThrow ? a : a + 1, "mergeWithEHPred");
                    digest = mix(digest, r);
                }
            }

            String payload = Long.toUnsignedString(digest, 16);
            Asserts.assertEquals(payload, EXPECTED_DIGEST, "behavior digest");
            System.out.println("PEA-RESULT:" + payload);
        }

        public static int caseATwoPredFieldPhi(boolean which, int a, int b) {
            Node n = new Node();
            if (which) {
                n.x = a;
            } else {
                n.x = b;
            }
            return n.x;
        }

        public static int caseATwoPredDefault(boolean which, int a) {
            Node n = new Node();
            if (which) {
                n.x = a;
            }
            return n.x;
        }

        public static int caseAThreePredFieldPhi(int sel, int a, int b, int c) {
            Node n = new Node();
            if (sel == 0) {
                n.x = a;
            } else if (sel == 1) {
                n.x = b;
            } else {
                n.x = c;
            }
            return n.x;
        }

        public static int valuePhiSameVO(boolean which, int a, int b, int c) {
            Node n = new Node();
            n.x = a;
            Node p;
            if (which) {
                n.x = b;
                p = n;
            } else {
                n.x = c;
                p = n;
            }
            return p.x;
        }

        public static int materializeAtOnePred(boolean escape, int a, int b) {
            Node n = new Node();
            n.x = a;
            if (escape) {
                consume(n);
                n.x = b;
            }
            return n.x;
        }

        public static int mergeWithEHPred(boolean doThrow, int a) {
            Node n = new Node();
            n.x = a;
            try {
                maybeThrow(doThrow);
                n.x = a + 1;
            } catch (TestException expected) {
                // n.x == a on this (exception) predecessor
            }
            return n.x;
        }

        public static void consume(Node node) {
            callCount++;
            savedNode = node;
        }

        public static void maybeThrow(boolean doThrow) {
            if (doThrow) {
                throw new TestException();
            }
        }

        private static void resetObservation() {
            callCount = 0;
            savedNode = null;
        }

        private static long mix(long digest, int value) {
            return Long.rotateLeft(digest ^ Integer.toUnsignedLong(value), 17)
                    * 0x9E3779B97F4A7C15L;
        }
    }
}
