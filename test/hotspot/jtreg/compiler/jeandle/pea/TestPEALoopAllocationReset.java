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
 * @summary PEA loop allocation reset: loop-local allocations are eliminated; a
 *          genuinely loop-carried chain of distinct allocations stays
 *          conservative (each iteration's instance must NOT be merged into one
 *          synthetic VO) and retains the source allocation; an escaped chain
 *          materializes the head once. traverse() has a step cap so a bogus
 *          self-cycle fails loudly instead of hanging.
 * @library /test/lib /
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.pea.TestPEALoopAllocationReset
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;

import jdk.test.lib.Asserts;

public class TestPEALoopAllocationReset {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestPEALoopAllocationReset$TestWrapper";

    public static void main(String[] args) throws Exception {
        PEATestUtils.assertPhiParserContracts();

        Method local = TestWrapper.class.getMethod("loopLocalNotCarried", int.class);
        Method chain = TestWrapper.class.getMethod("chainBuilding", int.class);
        Method vStart = TestWrapper.class.getMethod("chainFromVirtualStart", int.class);
        Method reset = TestWrapper.class.getMethod("resetAtThreshold", int.class, int.class);
        Method oneBranch = TestWrapper.class.getMethod("oneBranchAllocates",
                int.class, boolean.class);
        Method escape = TestWrapper.class.getMethod("escapingChain", int.class);
        Method consume = TestWrapper.class.getMethod("consume", TestWrapper.P.class);
        Method[] targets = {local, chain, vStart, reset, oneBranch, escape};

        PEATestUtils.behaviorRun(WRAPPER, targets).dontinline(consume).runPEAOnOffEquivalent();

        try (PEATestUtils.RunResult run =
                PEATestUtils.shapeRun(WRAPPER, targets).dontinline(consume).run()) {
            assertNeverEscapeLoopLocal(run, local);
            assertCarriedChainConservative(run, chain);
            assertCarriedChainConservative(run, vStart);
            assertCarriedChainConservative(run, reset);
            assertCarriedChainConservative(run, oneBranch);
            assertEscapedChain(run, escape);
        }
    }

    private static void assertNeverEscapeLoopLocal(PEATestUtils.RunResult run, Method target)
            throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();
        Asserts.assertEquals(before.peaAllocCount(), 1,
                target + ": one source allocation");
        Asserts.assertTrue(after.allocationBCIs().isEmpty(),
                target + ": loop-local allocation eliminated");
        after.assertAbsent("jeandle.new_instance");
        after.assertAbsent("poison");
        Asserts.assertTrue(report.maxNeverEscapes() >= 1,
                target + ": classified NeverEscape in some round");
        report.assertFinalTransformIdle();
        assertVerifierShape(run, report, target);
    }

    // A carried chain of distinct per-iteration allocations must stay conservative:
    // the source allocation is retained (never merged into one synthetic VO), no
    // Case-C field PHI is produced, and the object is classified PartiallyEscapes.
    private static void assertCarriedChainConservative(PEATestUtils.RunResult run,
                                                       Method target) throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();
        Asserts.assertTrue(before.peaAllocCount() >= 1,
                target + ": at least one source allocation site");
        // The carried instance is retained (OrigAlloc kept); distinct iterations are
        // not collapsed into one synthetic VO.
        Asserts.assertEquals(after.allocationBCIs(), before.allocationBCIs(),
                target + ": carried chain retains every source allocation");
        after.assertAbsent("pea.casec.field.phi");
        after.assertAbsent("poison");
        Asserts.assertTrue(report.maxPartiallyEscapes() >= 1,
                target + ": carried chain classified PartiallyEscapes");
        report.assertFinalTransformIdle();
        assertVerifierShape(run, report, target);
    }

    private static void assertEscapedChain(PEATestUtils.RunResult run, Method target)
            throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.IRBody after = report.finalAfter();
        Asserts.assertTrue(report.maxPartiallyEscapes() >= 1,
                target + ": escaped chain classified PartiallyEscapes");
        Asserts.assertTrue(report.effects("Materialize").size() >= 1,
                target + ": chain head materialized at the escape");
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
        private static final String EXPECTED_DIGEST = "a95d50d7b6096c4a";

        public static class P {
            int v;
            P next;
        }

        private static int consumedLen;
        private static int consumedSum;

        public static void main(String[] args) throws Exception {
            new P();
            PEATestUtils.compileConfiguredTargetsAtLevel4();

            long digest = 0x9E3779B97F4A7C15L;
            for (int n : new int[] {0, 1, 5}) {
                digest = mix(digest, chainBuilding(n));
                digest = mix(digest, chainFromVirtualStart(n));
                digest = mix(digest, loopLocalNotCarried(n));
            }
            for (int n : new int[] {0, 3}) {
                digest = mix(digest, escapingChain(n));
            }
            for (int n : new int[] {1, 7}) {
                for (int threshold : new int[] {2, 3}) {
                    digest = mix(digest, resetAtThreshold(n, threshold));
                }
            }
            for (int n : new int[] {0, 4}) {
                for (boolean alloc : new boolean[] {false, true}) {
                    digest = mix(digest, oneBranchAllocates(n, alloc));
                }
            }

            String payload = Long.toUnsignedString(digest, 16);
            if (EXPECTED_DIGEST != null) {
                Asserts.assertEquals(payload, EXPECTED_DIGEST, "behavior digest");
            }
            System.out.println("PEA-RESULT:" + payload);
        }

        public static int loopLocalNotCarried(int n) {
            int sum = 0;
            for (int i = 0; i < n; i++) {
                P t = new P();
                t.v = i;
                sum += t.v;
            }
            return sum;
        }

        public static int chainBuilding(int n) {
            P p = null;
            for (int i = 0; i < n; i++) {
                P t = new P();
                t.v = i;
                t.next = p;
                p = t;
            }
            return traverse(p);
        }

        public static int chainFromVirtualStart(int n) {
            P p = new P();
            p.v = 100;
            for (int i = 0; i < n; i++) {
                P t = new P();
                t.v = i;
                t.next = p;
                p = t;
            }
            return traverse(p);
        }

        public static int resetAtThreshold(int n, int threshold) {
            P p = null;
            for (int i = 0; i < n; i++) {
                P t = new P();
                t.v = i;
                t.next = p;
                p = t;
                if (i % threshold == threshold - 1) {
                    p = null;
                }
            }
            return traverse(p);
        }

        public static int oneBranchAllocates(int n, boolean alloc) {
            P p = null;
            for (int i = 0; i < n; i++) {
                if (alloc) {
                    P t = new P();
                    t.v = i;
                    t.next = p;
                    p = t;
                } else if (p != null) {
                    p.v += 1;
                }
            }
            return traverse(p);
        }

        public static int escapingChain(int n) {
            P p = null;
            for (int i = 0; i < n; i++) {
                P t = new P();
                t.v = i;
                t.next = p;
                p = t;
            }
            consumedLen = 0;
            consumedSum = 0;
            consume(p);
            return consumedLen * 100000 + consumedSum;
        }

        public static void consume(P head) {
            int len = 0;
            int sum = 0;
            P cur = head;
            while (cur != null && len < 1000) {
                sum += cur.v;
                len++;
                cur = cur.next;
            }
            consumedLen = len;
            consumedSum = sum;
        }

        // Bounded walk so a bogus self-cycle raises a wrong result instead of hanging.
        private static int traverse(P p) {
            int len = 0;
            int sum = 0;
            P cur = p;
            while (cur != null && len < 1000) {
                sum += cur.v;
                len++;
                cur = cur.next;
            }
            return len * 100000 + sum;
        }

        private static long mix(long digest, int value) {
            return Long.rotateLeft(digest ^ Integer.toUnsignedLong(value), 17)
                    * 0x9E3779B97F4A7C15L;
        }
    }
}
