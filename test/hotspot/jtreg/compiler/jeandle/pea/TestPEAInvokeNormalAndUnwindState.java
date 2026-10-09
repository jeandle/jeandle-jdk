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
 * @summary PEA keeps distinct normal and unwind states for a materialized
 *          invoke argument while scalar-replacing an unpassed object
 * @library /test/lib /
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.pea.TestPEAInvokeNormalAndUnwindState
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jdk.test.lib.Asserts;

public class TestPEAInvokeNormalAndUnwindState {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestPEAInvokeNormalAndUnwindState$TestWrapper";

    public static void main(String[] args) throws Exception {
        Method target = TestWrapper.class.getMethod("target", int.class);
        Method callee = TestWrapper.class.getMethod("callee",
                TestWrapper.IntBox.class, int.class);

        PEATestUtils.behaviorRun(WRAPPER, target)
                .dontinline(callee)
                .runPEAOnOffEquivalent();

        try (PEATestUtils.RunResult run = PEATestUtils.shapeRun(WRAPPER, target)
                .dontinline(callee)
                .run()) {
            assertShape(run, target, callee);
        }
    }

    private static void assertShape(PEATestUtils.RunResult run, Method target,
                                    Method callee) throws Exception {
        PEATestUtils.IRBody frontend = run.frontendIR(target);
        String calleeName = PEATestUtils.MethodId.of(callee).llvmFunctionName();
        PEATestUtils.IRBlock frontendInvokeBlock = frontend.blockContaining(calleeName, 0);
        Asserts.assertEquals(frontendInvokeBlock.occurrenceCount(
                        "invoke hotspotcc void @\"" + calleeName + "\"("), 1,
                target + ": exact descriptor-qualified callee invoke");
        Asserts.assertEquals(frontendInvokeBlock.occurrenceCount("to label"), 1,
                target + ": exact callee invoke normal successor");
        Asserts.assertEquals(frontendInvokeBlock.occurrenceCount("unwind label"), 1,
                target + ": exact callee invoke with normal and unwind successors");

        List<Integer> sourceBCIs = frontend.allocationBCIs();
        Asserts.assertEquals(sourceBCIs.size(), 2,
                target + ": two frontend source allocations");
        Set<Integer> distinctBCIs = new HashSet<>(sourceBCIs);
        Asserts.assertEquals(distinctBCIs.size(), 2,
                target + ": distinct p and q allocation BCIs");

        PEATestUtils.PEAReport report = run.report(target);
        report.assertFinalTransformIdle();
        PEATestUtils.PEARound first = report.round(0);
        Asserts.assertTrue(first.hasStats(), target + ": round-0 PEA stats");
        Asserts.assertEquals(first.neverEscapes(), 1,
                target + ": q is NeverEscape in round 0");
        Asserts.assertEquals(first.partiallyEscapes(), 1,
                target + ": p is PartiallyEscape in round 0");
        Asserts.assertEquals(first.alwaysEscapes(), 0,
                target + ": no AlwaysEscape allocation in round 0");
        Asserts.assertEquals(effectCountForVO(first, "EliminateAllocation", 1), 1L,
                target + ": q allocation is eliminated in round 0");
        Asserts.assertEquals(effectCountForVO(first, "ReplaceLoad", 1,
                        "load atomic i32"), 2L,
                target + ": q handler and normal loads are scalar-replaced");
        Asserts.assertEquals(effectCountForVO(first, "ReplaceLoad", 0,
                        "load atomic i32"), 0L,
                target + ": PEA does not scalar-replace the handler p load");

        for (PEATestUtils.PEARound round : report.rounds()) {
            Asserts.assertEquals(round.effectCount("Materialize"), 1L,
                    target + ": one p materialization in round " + round.iteration());
            Asserts.assertEquals(effectCountForVO(round, "Materialize", 0), 1L,
                    target + ": p materializes exactly once in round "
                            + round.iteration());
        }

        PEATestUtils.IRBody after = report.finalAfter();
        Asserts.assertEquals(after.allocationBCIs(), List.of(sourceBCIs.get(0)),
                target + ": only p's original allocation survives final PEA IR");
        Asserts.assertEquals(after.lineCount("load atomic i32"), 1,
                target + ": only p's handler load remains real");
        Asserts.assertEquals(after.lineCount("store atomic i32 41"), 1,
                target + ": one replay of p's pre-invoke field state");
        Asserts.assertEquals(after.lineCount("store atomic i32 99"), 1,
                target + ": one normal-successor field write");

        PEATestUtils.IRBlock invokeBlock = after.blockContaining(calleeName, 0);
        Asserts.assertEquals(invokeBlock.occurrenceCount("store atomic i32 41"), 1,
                target + ": p replay is in the invoke predecessor");
        Asserts.assertEquals(invokeBlock.occurrenceCount("store atomic i32 99"), 0,
                target + ": normal write is not in the invoke predecessor");
        invokeBlock.assertBefore("store atomic i32 41", 0, calleeName, 0);
    }

    private static long effectCountForVO(PEATestUtils.PEARound round, String kind,
                                         int objectId, String... detailParts) {
        String objectToken = "[VO=" + objectId + "]";
        return round.effects().stream()
                .filter(effect -> effect.kind().equals(kind))
                .filter(effect -> Arrays.asList(effect.detail().split("\\s+")).contains(objectToken))
                .filter(effect -> Arrays.stream(detailParts)
                        .allMatch(effect.detail()::contains))
                .count();
    }

    public static class TestWrapper {
        private static final int ITERATIONS = 2_000;
        private static final String EXPECTED_PAYLOAD = "4107,4207,9907,9907";

        public static class IntBox {
            public int x;
        }

        public static class TestException extends RuntimeException {
            private static final long serialVersionUID = 1L;
        }

        public static void main(String[] args) throws Exception {
            new IntBox();
            PEATestUtils.compileConfiguredTargetsAtLevel4();

            int[] expected = {4107, 4207, 9907, 9907};
            for (int iteration = 0; iteration < ITERATIONS; iteration++) {
                for (int mode = 0; mode < expected.length; mode++) {
                    Asserts.assertEquals(target(mode), expected[mode],
                            "invoke normal/unwind state for mode " + mode
                                    + " at iteration " + iteration);
                }
            }
            System.out.println("PEA-RESULT:" + EXPECTED_PAYLOAD);
        }

        public static int target(int mode) {
            IntBox p = new IntBox();
            IntBox q = new IntBox();
            p.x = 41;
            q.x = 7;
            try {
                callee(p, mode);
                p.x = 99;
            } catch (TestException expected) {
                return p.x * 100 + q.x;
            }
            return p.x * 100 + q.x;
        }

        public static void callee(IntBox p, int mode) {
            if ((mode & 1) != 0) {
                p.x = 42;
            }
            if (mode < 2) {
                throw new TestException();
            }
        }
    }
}
