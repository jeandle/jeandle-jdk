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
 * @summary PEA loop field state: an allocation kept virtual across a loop keeps
 *          a stable per-offset field PHI at the header, foldable loads/stores
 *          are eliminated, multiple backedges/exits keep PHI incomings aligned,
 *          and no Graal-style loop-exit materialization is inserted
 * @library /test/lib /
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.pea.TestPEALoopFieldState
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;

import jdk.test.lib.Asserts;

// Mirrors Graal LoopEscape.test0/test1 plus multi-backedge, multi-exit and
// partial/unmodified-field shapes. The trip count is a runtime parameter, so the
// compiled shape always contains the full loop regardless of the value used here.
public class TestPEALoopFieldState {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestPEALoopFieldState$TestWrapper";

    public static void main(String[] args) throws Exception {
        PEATestUtils.assertPhiParserContracts();

        Method inc = TestWrapper.class.getMethod("loopFieldIncrements", int.class);
        Method cond = TestWrapper.class.getMethod("conditionalBody", int.class);
        Method cont = TestWrapper.class.getMethod("continueTwoBackedges",
                int.class, int.class);
        Method exits = TestWrapper.class.getMethod("multipleExits", int.class, int.class);
        Method partial = TestWrapper.class.getMethod("partialFieldModification", int.class);
        Method unchanged = TestWrapper.class.getMethod("fieldUnchanged", int.class);
        Method[] targets = {inc, cond, cont, exits, partial, unchanged};

        PEATestUtils.behaviorRun(WRAPPER, targets).runPEAOnOffEquivalent();

        try (PEATestUtils.RunResult run = PEATestUtils.shapeRun(WRAPPER, targets).run()) {
            assertNeverEscapeLoop(run, inc, true);
            assertNeverEscapeLoop(run, cond, true);
            assertNeverEscapeLoop(run, cont, true);
            assertNeverEscapeLoop(run, exits, true);
            assertNeverEscapeLoop(run, partial, true);
            assertNeverEscapeLoop(run, unchanged, false);
        }
    }

    // expectsFieldPhi: methods that store a field inside the loop keep a per-offset
    // header PHI; methods that only read (or whose stores fold to a constant) need none.
    private static void assertNeverEscapeLoop(PEATestUtils.RunResult run, Method target,
                                              boolean expectsFieldPhi) throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();
        Asserts.assertEquals(before.peaAllocCount(), 1,
                target + ": one source allocation");
        Asserts.assertTrue(after.allocationBCIs().isEmpty(),
                target + ": NeverEscape loop eliminates the allocation");
        after.assertAbsent("jeandle.new_instance");
        after.assertAbsent("poison");
        // Foldable field accesses are eliminated: no replay stores and no surviving
        // field loads remain (all of L's fields are i32).
        after.assertAbsent("store atomic i32");
        after.assertAbsent("load atomic i32");
        if (expectsFieldPhi) {
            after.assertPresent("pea.field.phi");
        } else {
            after.assertAbsent("pea.field.phi");
        }
        Asserts.assertTrue(report.maxNeverEscapes() >= 1,
                target + ": classified NeverEscape in some round");
        Asserts.assertTrue(report.effects("EliminateAllocation").size() >= 1,
                target + ": allocation eliminated by PEA");
        // Loop exit reads fold from the merged header state; Jeandle must not insert
        // a Graal-style forced materialization at loop exits (its OOM-cleanup unwind
        // edges leave every loop, so such a rule would defeat loop virtualization).
        Asserts.assertTrue(report.effects("Materialize").isEmpty(),
                target + ": no loop-exit materialization");
        report.assertFinalTransformIdle();
        PEATestUtils.IRBody finalIR = run.finalIR(target);
        finalIR.assertAbsent("jeandle.new_instance");
        finalIR.assertAbsent("poison");
        Asserts.assertEquals(finalIR.loweredAllocCount(), 0,
                target + ": allocation fully lowered away");
        for (PEATestUtils.PEARound round : report.rounds()) {
            round.after().assertAbsent("poison");
            PEATestUtils.assertCompletePhis(round.after(), target.toString());
        }
        PEATestUtils.assertCompletePhis(finalIR, target.toString());
    }

    public static class TestWrapper {
        // Filled in once the on/off behavior is confirmed equivalent; null skips the
        // internal check (the parent still compares PEA-on vs PEA-off payloads).
        private static final String EXPECTED_DIGEST = "f105be0c411a9b16";

        public static class L {
            int a;
            int b;
            int c;
        }

        public static void main(String[] args) throws Exception {
            new L();
            PEATestUtils.compileConfiguredTargetsAtLevel4();

            long digest = 0x9E3779B97F4A7C15L;
            for (int count : new int[] {0, 1, 2, 5}) {
                digest = mix(digest, loopFieldIncrements(count));
                digest = mix(digest, conditionalBody(count));
            }
            for (int count : new int[] {0, 1, 7}) {
                for (int skipMod : new int[] {2, 3}) {
                    digest = mix(digest, continueTwoBackedges(count, skipMod));
                }
            }
            for (int count : new int[] {0, 5, 20}) {
                for (int breakAt : new int[] {-1, 3}) {
                    digest = mix(digest, multipleExits(count, breakAt));
                }
            }
            for (int count : new int[] {0, 1, 4}) {
                digest = mix(digest, partialFieldModification(count));
            }
            for (int count : new int[] {0, 3}) {
                digest = mix(digest, fieldUnchanged(count));
            }

            String payload = Long.toUnsignedString(digest, 16);
            if (EXPECTED_DIGEST != null) {
                Asserts.assertEquals(payload, EXPECTED_DIGEST, "behavior digest");
            }
            System.out.println("PEA-RESULT:" + payload);
        }

        public static int loopFieldIncrements(int count) {
            L l = new L();
            l.a = 5;
            l.b = 5;
            l.c = 5;
            for (int i = 0; i < count; i++) {
                l.a++;
                l.b--;
                l.c = 4;
            }
            return l.a + l.b * 10 + l.c * 100;
        }

        public static int conditionalBody(int count) {
            L l = new L();
            l.a = 5;
            l.b = 5;
            l.c = 5;
            for (int i = 0; i < count; i++) {
                if (l.a % 2 == 0) {
                    l.a++;
                    l.b--;
                    l.c = 4;
                } else {
                    l.a++;
                }
            }
            return l.a + l.b * 10 + l.c * 100;
        }

        public static int continueTwoBackedges(int count, int skipMod) {
            L l = new L();
            l.a = 0;
            l.b = 10;
            for (int i = 0; i < count; i++) {
                if (i % skipMod == 0) {
                    continue;
                }
                l.a += i;
                l.b--;
            }
            return l.a + l.b;
        }

        public static int multipleExits(int count, int breakAt) {
            L l = new L();
            l.a = 0;
            l.b = 0;
            for (int i = 0; i < count; i++) {
                l.a += i;
                if (l.a > 50) {
                    return l.a * 7;
                }
                if (breakAt >= 0 && i == breakAt) {
                    break;
                }
                l.b += 2;
            }
            return l.a * 100 + l.b;
        }

        public static int partialFieldModification(int count) {
            L l = new L();
            l.a = 1;
            l.b = 2;
            l.c = 3;
            for (int i = 0; i < count; i++) {
                l.a += i;
            }
            l.c = 30;
            return l.a + l.b * 100 + l.c;
        }

        public static int fieldUnchanged(int count) {
            L l = new L();
            l.a = 7;
            int sum = 0;
            for (int i = 0; i < count; i++) {
                sum += l.a;
            }
            return sum + l.a;
        }

        private static long mix(long digest, int value) {
            return Long.rotateLeft(digest ^ Integer.toUnsignedLong(value), 17)
                    * 0x9E3779B97F4A7C15L;
        }
    }
}
