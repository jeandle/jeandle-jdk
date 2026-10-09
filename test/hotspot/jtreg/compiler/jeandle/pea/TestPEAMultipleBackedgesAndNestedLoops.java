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
 * @summary PEA over multiple backedges and nested loops: a carried VO stays
 *          virtual across two latches and across outer/inner loops, with PHI
 *          incomings complete for every predecessor; a nested-loop VO is also
 *          eliminated; an inner-loop throw to an outer handler does not
 *          over-materialize. A shallow loop cutoff refuses new allocations
 *          inside the nest while still virtualizing the pre-nest object, and
 *          forced materialize-all keeps behavior identical to PEA-off.
 * @library /test/lib /
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.pea.TestPEAMultipleBackedgesAndNestedLoops
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;

import jdk.test.lib.Asserts;

public class TestPEAMultipleBackedgesAndNestedLoops {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestPEAMultipleBackedgesAndNestedLoops$TestWrapper";

    public static void main(String[] args) throws Exception {
        PEATestUtils.assertPhiParserContracts();

        Method twoLatch = TestWrapper.class.getMethod("twoLatchesCarry", int.class, int.class);
        Method sameVO = TestWrapper.class.getMethod("outerInnerSameVO", int.class, int.class);
        Method twoVO = TestWrapper.class.getMethod("twoLevelsTwoVOs", int.class, int.class);
        Method fanOut = TestWrapper.class.getMethod("multiExitFanOut", int.class, int.class);
        Method thrower = TestWrapper.class.getMethod("thrower", boolean.class);
        Method innerThrow = TestWrapper.class.getMethod("innerThrowToOuterHandler",
                int.class, boolean.class);
        Method[] targets = {twoLatch, sameVO, twoVO, fanOut, innerThrow};

        // Standard behavior: PEA-on vs PEA-off must match across the whole matrix.
        PEATestUtils.behaviorRun(WRAPPER, targets).dontinline(thrower).runPEAOnOffEquivalent();
        // Forced materialize-all is the sound fallback path; it must also match
        // PEA-off. (True non-convergence is not Java-inducible; the lit suite owns
        // that. This exercises the materialize-all emission path from jtreg.)
        assertMaterializeAllEquivalent(targets, thrower);

        try (PEATestUtils.RunResult run =
                PEATestUtils.shapeRun(WRAPPER, targets).dontinline(thrower).run()) {
            assertNeverEscapeNested(run, twoLatch);
            assertNeverEscapeNested(run, sameVO);
            assertNeverEscapeNested(run, twoVO);
            assertNeverEscapeNested(run, fanOut);
            assertNeverEscapeNested(run, innerThrow);
        }

        // Shallow cutoff: the nest (outer+inner loop) exceeds depth 1, so new
        // allocations inside the nest are refused while the pre-nest Acc is still
        // virtualized and eliminated.
        try (PEATestUtils.RunResult run =
                PEATestUtils.shapeRun(WRAPPER, twoVO).dontinline(thrower)
                        .extraLLVMOptions("-jeandle-pea-loop-cutoff=1").run()) {
            assertStopNewNested(run, twoVO);
        }
    }

    private static void assertMaterializeAllEquivalent(Method[] targets, Method thrower)
            throws Exception {
        String onPayload;
        try (PEATestUtils.RunResult on =
                PEATestUtils.behaviorRun(WRAPPER, targets).dontinline(thrower)
                        .extraLLVMOptions("-jeandle-pea-force-materialize-all").run()) {
            onPayload = payloadOf(on);
        }
        String offPayload;
        try (PEATestUtils.RunResult off =
                PEATestUtils.behaviorRun(WRAPPER, targets).dontinline(thrower).peaOff().run()) {
            offPayload = payloadOf(off);
        }
        Asserts.assertEquals(onPayload, offPayload,
                "forced materialize-all behavior must match PEA-off");
    }

    private static String payloadOf(PEATestUtils.RunResult run) {
        for (String line : run.output().getStdout().split("\\R")) {
            if (line.startsWith("PEA-RESULT:")) {
                return line.substring("PEA-RESULT:".length());
            }
        }
        throw new AssertionError("no PEA-RESULT line in " + run.command());
    }

    private static void assertNeverEscapeNested(PEATestUtils.RunResult run, Method target)
            throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();
        Asserts.assertTrue(before.peaAllocCount() >= 1,
                target + ": at least one source allocation");
        Asserts.assertTrue(after.allocationBCIs().isEmpty(),
                target + ": nested/multi-latch loop eliminates every allocation");
        after.assertAbsent("jeandle.new_instance");
        after.assertAbsent("poison");
        Asserts.assertTrue(report.maxNeverEscapes() >= 1,
                target + ": classified NeverEscape in some round");
        Asserts.assertTrue(report.effects("Materialize").isEmpty(),
                target + ": no over-materialization");
        report.assertFinalTransformIdle();
        assertVerifierShape(run, report, target);
    }

    private static void assertStopNewNested(PEATestUtils.RunResult run, Method target)
            throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();
        // Acc is allocated before the nest and stays virtual (eliminated); the
        // per-outer-iteration Inner is allocated inside the nest and, with a
        // depth-1 cutoff, is refused (retained as a real allocation).
        Asserts.assertEquals(before.peaAllocCount(), 2,
                target + ": Acc plus Inner source allocations");
        Asserts.assertEquals(after.allocationBCIs().size(), 1,
                target + ": only the in-nest Inner allocation is retained");
        after.assertAbsent("poison");
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
        private static final String EXPECTED_DIGEST = "73c76e3c35778d2e";

        public static class Acc { int a; int b; }
        public static class Inner { int v; }
        public static class MarkerException extends RuntimeException {
            private static final long serialVersionUID = 1L;
        }

        public static void main(String[] args) throws Exception {
            new Acc(); new Inner(); new MarkerException();
            PEATestUtils.compileConfiguredTargetsAtLevel4();

            long digest = 0x9E3779B97F4A7C15L;
            for (int n : new int[] {0, 1, 9, 30}) {
                for (int skipMod : new int[] {2, 3}) {
                    digest = mix(digest, twoLatchesCarry(n, skipMod));
                }
            }
            for (int outer : new int[] {0, 1, 3}) {
                for (int inner : new int[] {0, 2}) {
                    digest = mix(digest, outerInnerSameVO(outer, inner));
                    digest = mix(digest, twoLevelsTwoVOs(outer, inner));
                }
            }
            for (int n : new int[] {0, 6, 20}) {
                for (int sel : new int[] {0, 1, 2}) {
                    digest = mix(digest, multiExitFanOut(n, sel));
                }
            }
            for (int outer : new int[] {1, 3}) {
                for (boolean doThrow : new boolean[] {false, true}) {
                    digest = mix(digest, innerThrowToOuterHandler(outer, doThrow));
                }
            }

            String payload = Long.toUnsignedString(digest, 16);
            if (EXPECTED_DIGEST != null) {
                Asserts.assertEquals(payload, EXPECTED_DIGEST, "behavior digest");
            }
            System.out.println("PEA-RESULT:" + payload);
        }

        public static int twoLatchesCarry(int n, int skipMod) {
            Acc vo = new Acc();
            vo.a = 0;
            vo.b = 0;
            for (int i = 0; i < n; i++) {
                if (i % skipMod == 0) {
                    continue;
                }
                vo.a += i;
                if (vo.a > 100) {
                    break;
                }
                vo.b++;
            }
            return vo.a + vo.b;
        }

        public static int outerInnerSameVO(int outer, int inner) {
            Acc vo = new Acc();
            vo.a = 0;
            vo.b = 0;
            for (int o = 0; o < outer; o++) {
                for (int i = 0; i < inner; i++) {
                    vo.a += o * 10 + i;
                }
                vo.b++;
            }
            return vo.a + vo.b;
        }

        public static int twoLevelsTwoVOs(int outer, int inner) {
            Acc vo = new Acc();
            vo.a = 0;
            vo.b = 0;
            for (int o = 0; o < outer; o++) {
                Inner in = new Inner();
                in.v = 0;
                for (int i = 0; i < inner; i++) {
                    in.v += i;
                }
                vo.a += in.v;
                vo.b++;
            }
            return vo.a + vo.b;
        }

        public static int multiExitFanOut(int n, int sel) {
            Acc vo = new Acc();
            vo.a = 0;
            vo.b = 0;
            for (int i = 0; i < n; i++) {
                vo.a += i;
                if (vo.a > 100) {
                    break;
                }
                if (sel == 2 && i == n / 2) {
                    return vo.b;
                }
                vo.b++;
            }
            if (sel == 0) {
                return vo.a;
            }
            if (sel == 1) {
                return vo.b;
            }
            return vo.a + vo.b;
        }

        public static int innerThrowToOuterHandler(int outer, boolean doThrow) {
            Acc vo = new Acc();
            vo.a = 0;
            vo.b = 0;
            int caught = 0;
            for (int o = 0; o < outer; o++) {
                try {
                    for (int i = 0; i < 3; i++) {
                        thrower(doThrow && i == 1);
                        vo.a += i;
                    }
                } catch (MarkerException e) {
                    caught++;
                    vo.b += 1000;
                    continue;
                }
                vo.b++;
            }
            return vo.a + vo.b + caught;
        }

        public static void thrower(boolean doThrow) {
            if (doThrow) {
                throw new MarkerException();
            }
        }

        private static long mix(long digest, int value) {
            return Long.rotateLeft(digest ^ Integer.toUnsignedLong(value), 17)
                    * 0x9E3779B97F4A7C15L;
        }
    }
}
