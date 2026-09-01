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
 * @summary End-to-end coverage for a loop whose only forward edge is dead
 *          only under PEA's identity tracking (two distinct fresh allocations
 *          are never reference-equal — a fold no earlier pass can do), with a
 *          field-PHI merge downstream: the method must compile, eliminate all
 *          allocations, and keep every PHI complete for every structural
 *          predecessor on every round. Note: the malformed-%pea.field.phi
 *          crash itself ( EliminateUnreachableBlocks -> removePredecessor ->
 *          removeIncomingValue(-1) ) is owned by lit tests 752/753/754,
 *          because the pre-PEA pipeline masks this shape at the VM level
 *          (LoopRotate preloads the merged field on the dead exit path, which
 *          keeps the phi receiver real until round-1 canonicalization removes
 *          the dead nest). `o == null` on a fresh allocation does not work
 *          either: the implicit null check on the <init> receiver lets
 *          pre-PEA GVN fold the guard before PEA ever sees the loop.
 * @library /test/lib /
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      -XX:-UseCompressedOops -XX:-UseCompressedClassPointers
 *      compiler.jeandle.pea.TestPEADeadLoopFieldPhi
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;

import jdk.test.lib.Asserts;

public class TestPEADeadLoopFieldPhi {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestPEADeadLoopFieldPhi$TestWrapper";

    public static void main(String[] args) throws Exception {
        PEATestUtils.assertPhiParserContracts();
        PEATestUtils.assertStructuralParserContracts();

        Method deadLoop = TestWrapper.class.getMethod("deadLoopFieldPhi",
                boolean.class, int.class, int.class, int.class);
        Method sink = TestWrapper.class.getMethod("sink", int.class);
        Method[] targets = {deadLoop};

        PEATestUtils.behaviorRun(WRAPPER, targets).dontinline(sink)
                .runPEAOnOffEquivalent();

        try (PEATestUtils.RunResult run =
                PEATestUtils.shapeRun(WRAPPER, targets).dontinline(sink)
                        .peaIterations(3).run()) {
            PEATestUtils.PEAReport report = run.report(deadLoop);
            PEATestUtils.IRBody before = report.round0Before();
            PEATestUtils.IRBody after = report.finalAfter();

            Asserts.assertEquals(before.peaAllocCount(), 3,
                    deadLoop + ": o, a, b source allocations");
            // The guarded loop must survive the pre-PEA pipeline, otherwise
            // the test exercises nothing.
            before.assertPresent("sink(I)V");
            Asserts.assertEquals(report.round(0).neverEscapes(), 2,
                    deadLoop + ": the identity-compared a and b die first");
            Asserts.assertEquals(
                    report.round(0).effectCount("EliminateAllocation"), 2L,
                    deadLoop + ": a and b are eliminated in round 0");
            Asserts.assertTrue(
                    report.round(1).effectCount("CreatePHI") == 1,
                    deadLoop + ": the merge synthesizes the field PHI once "
                            + "the dead nest is gone (round 1)");
            Asserts.assertEquals(report.round(1).neverEscapes(), 1,
                    deadLoop + ": o is eliminated once the dead nest is gone");
            Asserts.assertEquals(report.effects("EliminateAllocation").size(), 3,
                    deadLoop + ": all three allocations eliminated across rounds");

            // The malformed-PHI oracle: every PHI's incoming blocks must
            // match its block's structural predecessors on every round, even
            // while the dead loop nest still occupies its (poison) slots.
            // Poison itself is only required to be gone once CFG cleanup has
            // caught up (final round / final IR).
            for (PEATestUtils.PEARound round : report.rounds()) {
                PEATestUtils.assertCompletePhis(round.after(), deadLoop.toString());
            }
            after.assertAbsent("jeandle.new_instance");
            after.assertAbsent("poison");
            PEATestUtils.assertCompletePhis(after, deadLoop.toString());
            PEATestUtils.IRBody finalIR = run.finalIR(deadLoop);
            finalIR.assertAbsent("poison");
            PEATestUtils.assertCompletePhis(finalIR, deadLoop.toString());
            report.assertFinalTransformIdle();
            report.assertStoppedAtFixpoint();
        }
    }

    public static class TestWrapper {
        private static final String EXPECTED_DIGEST = "edd529ac19032d65";

        public static class Acc {
            int f;
        }

        static int lastSink;

        public static void sink(int v) {
            lastSink = v;
        }

        public static int deadLoopFieldPhi(boolean which, int x, int y, int n) {
            Acc o = new Acc();
            Acc a = new Acc();
            Acc b = new Acc();
            // PEA-only-provable dead: two distinct fresh allocations are never
            // reference-equal, and only PEA's identity tracking can prove it
            // (pre-PEA passes cannot, so the loop reaches PEA). The opaque
            // sink call keeps the loop alive until PEA.
            if (a == b) {
                for (int i = 0; i < n; i++) {
                    sink(i);
                }
            } else if (which) {
                o.f = x;
            } else {
                o.f = y;
            }
            return o.f;
        }

        public static void main(String[] args) throws Exception {
            new Acc();
            PEATestUtils.compileConfiguredTargetsAtLevel4();

            long digest = 0x254F0D7CDB10CBF1L;
            for (int seed : new int[] {0, 9}) {
                for (boolean which : new boolean[] {false, true}) {
                    int x = seed + 3;
                    int y = seed + 4;
                    int n = seed + 5;
                    int r = deadLoopFieldPhi(which, x, y, n);
                    Asserts.assertEquals(r, which ? x : y, "deadLoopFieldPhi");
                    digest = mix(digest, r);
                }
            }

            String payload = Long.toUnsignedString(digest, 16);
            Asserts.assertEquals(payload, EXPECTED_DIGEST, "behavior digest");
            System.out.println("PEA-RESULT:" + payload);
        }

        private static long mix(long digest, int value) {
            return Long.rotateLeft(digest ^ Integer.toUnsignedLong(value), 23)
                    * 0x9E3779B97F4A7C15L;
        }
    }
}
