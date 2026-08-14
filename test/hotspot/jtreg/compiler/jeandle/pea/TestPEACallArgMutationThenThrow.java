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
 * @summary PEA call-argument materialization is visible after both normal and
 *          exceptional returns from a mutating callee
 * @library /test/lib /
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.pea.TestPEACallArgMutationThenThrow
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jdk.test.lib.Asserts;

public class TestPEACallArgMutationThenThrow {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestPEACallArgMutationThenThrow$TestWrapper";

    public static void main(String[] args) throws Exception {
        Method primitive = TestWrapper.class.getMethod("testPrimitive", boolean.class);
        Method reference = TestWrapper.class.getMethod("testReference", boolean.class,
                Object.class, Object.class);
        Method twoArguments = TestWrapper.class.getMethod("testTwoArguments",
                boolean.class, boolean.class);
        Method unexposed = TestWrapper.class.getMethod("testUnexposed", boolean.class);
        Method mutatePrimitive = TestWrapper.class.getMethod("mutatePrimitive",
                TestWrapper.IntBox.class, boolean.class);
        Method mutateReference = TestWrapper.class.getMethod("mutateReference",
                TestWrapper.RefBox.class, Object.class, boolean.class);
        Method mutateTwo = TestWrapper.class.getMethod("mutateTwo",
                TestWrapper.IntBox.class, TestWrapper.IntBox.class, boolean.class);
        Method[] targets = {primitive, reference, twoArguments, unexposed};

        behaviorBuilder(targets, mutatePrimitive, mutateReference, mutateTwo)
                .runPEAOnOffEquivalent();

        try (PEATestUtils.RunResult run = shapeBuilder(targets, mutatePrimitive,
                                                       mutateReference, mutateTwo).run()) {
            assertPassedObjectShape(run, primitive, mutatePrimitive, 1,
                    "store atomic i32", 1);
            assertPassedObjectShape(run, reference, mutateReference, 1,
                    "store atomic ptr addrspace(1)", 1);
            assertPassedObjectShape(run, twoArguments, mutateTwo, 2,
                    "store atomic i32", 2, 1);
            assertUnexposedShape(run, unexposed, mutatePrimitive);
        }
    }

    private static PEATestUtils.RunBuilder behaviorBuilder(
            Method[] targets, Method mutatePrimitive, Method mutateReference,
            Method mutateTwo) {
        return PEATestUtils.behaviorRun(WRAPPER, targets)
                .dontinline(mutatePrimitive)
                .dontinline(mutateReference)
                .dontinline(mutateTwo);
    }

    private static PEATestUtils.RunBuilder shapeBuilder(
            Method[] targets, Method mutatePrimitive, Method mutateReference,
            Method mutateTwo) {
        return PEATestUtils.shapeRun(WRAPPER, targets)
                .dontinline(mutatePrimitive)
                .dontinline(mutateReference)
                .dontinline(mutateTwo);
    }

    private static void assertPassedObjectShape(PEATestUtils.RunResult run, Method target,
                                                 Method callee, int allocations, String replay,
                                                 int... materializationsPerObject)
            throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();
        List<Integer> sourceBCIs = before.allocationBCIs();

        assertDistinctSourceAllocations(sourceBCIs, allocations, target);
        int materializationEffects = 0;
        for (int count : materializationsPerObject) {
            materializationEffects += count;
        }
        for (PEATestUtils.PEARound round : report.rounds()) {
            Asserts.assertEquals(round.effectCount("Materialize"),
                    (long) materializationEffects,
                    target + ": exact control-path materializations in round "
                            + round.iteration());
            for (int objectId = 0; objectId < materializationsPerObject.length; objectId++) {
                Asserts.assertEquals(round.effectCount("Materialize",
                                "[VO=" + objectId + "]"),
                        (long) materializationsPerObject[objectId],
                        target + ": materialization sites for passed VO " + objectId
                                + " in round " + round.iteration());
            }
        }
        Asserts.assertEquals(after.allocationBCIs(), sourceBCIs,
                target + ": only the source OrigAlloc allocations survive");

        String calleeName = PEATestUtils.MethodId.of(callee).llvmFunctionName();
        PEATestUtils.IRBlock callBlock = after.blockContaining(calleeName, 0);
        Asserts.assertEquals(callBlock.occurrenceCount(replay),
                materializationsPerObject.length,
                target + ": one field replay per materialized object");
        callBlock.assertBefore(replay, 0, calleeName, 0);

        long foldedPassedLoads = report.effects("ReplaceLoad").stream()
                .filter(effect -> effect.detail().contains("load atomic"))
                .count();
        Asserts.assertEquals(foldedPassedLoads, 0L,
                target + ": loads after the opaque invoke must read the real object");
    }

    private static void assertUnexposedShape(PEATestUtils.RunResult run, Method target,
                                              Method callee) throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();
        List<Integer> sourceBCIs = before.allocationBCIs();

        assertDistinctSourceAllocations(sourceBCIs, 2, target);
        for (PEATestUtils.PEARound round : report.rounds()) {
            Asserts.assertEquals(round.effectCount("Materialize"), 1L,
                    target + ": only the passed object materializes in round "
                            + round.iteration());
            round.uniqueEffect("Materialize", "[VO=0]");
        }
        Asserts.assertEquals(after.allocationBCIs(), List.of(sourceBCIs.get(0)),
                target + ": the unexposed allocation is eliminated");

        String calleeName = PEATestUtils.MethodId.of(callee).llvmFunctionName();
        PEATestUtils.IRBlock callBlock = after.blockContaining(calleeName, 0);
        Asserts.assertEquals(callBlock.occurrenceCount("store atomic i32"), 1,
                target + ": only the passed object's field is replayed");
        callBlock.assertBefore("store atomic i32", 0, calleeName, 0);

        for (PEATestUtils.PEARound round : report.rounds()) {
            Asserts.assertEquals(round.effectCount("ReplaceLoad", "[VO=0]",
                            "load atomic i32"), 0L,
                    target + ": passed-object loads stay real in round "
                            + round.iteration());
        }
        Asserts.assertEquals(report.round(0).effectCount("ReplaceLoad", "[VO=1]",
                        "load atomic i32"), 2L,
                target + ": both unexposed-object field loads are scalar-replaced");
        Asserts.assertEquals(after.lineCount("load atomic i32"), 2,
                target + ": normal and handler passed-object loads remain in final PEA IR");
    }

    private static void assertDistinctSourceAllocations(List<Integer> sourceBCIs,
                                                         int expected, Method target) {
        Asserts.assertEquals(sourceBCIs.size(), expected,
                target + ": source allocation count");
        Set<Integer> distinct = new HashSet<>(sourceBCIs);
        Asserts.assertEquals(distinct.size(), expected,
                target + ": every source allocation has a unique BCI");
    }

    public static class TestWrapper {
        private static final String EXPECTED_DIGEST = "2aceb4cf0b93609e";

        public static class IntBox {
            public int x;
        }

        public static class RefBox {
            public Object ref;
        }

        public static class TestException extends RuntimeException {
            private static final long serialVersionUID = 1L;
        }

        public static void main(String[] args) throws Exception {
            new IntBox();
            new RefBox();
            PEATestUtils.compileConfiguredTargetsAtLevel4();

            Object oldValue = new Object();
            Object newValue = new Object();
            long digest = 0x6A09E667F3BCC909L;
            for (boolean doThrow : new boolean[] {false, true}) {
                int primitive = testPrimitive(doThrow);
                int reference = testReference(doThrow, oldValue, newValue);
                int distinct = testTwoArguments(false, doThrow);
                int same = testTwoArguments(true, doThrow);
                int unexposed = testUnexposed(doThrow);

                Asserts.assertEquals(primitive, 42, "primitive callee mutation");
                Asserts.assertEquals(reference, 1, "reference callee mutation");
                Asserts.assertEquals(distinct, 4284, "two distinct argument mutations");
                Asserts.assertEquals(same, 84, "duplicate argument identity and write order");
                Asserts.assertEquals(unexposed, 427, "passed and unexposed field states");

                digest = mix(digest, primitive);
                digest = mix(digest, reference);
                digest = mix(digest, distinct);
                digest = mix(digest, same);
                digest = mix(digest, unexposed);
            }
            String payload = Long.toUnsignedString(digest, 16);
            Asserts.assertEquals(payload, EXPECTED_DIGEST, "exact behavior digest");
            System.out.println("PEA-RESULT:" + payload);
        }

        public static int testPrimitive(boolean doThrow) {
            IntBox p = new IntBox();
            p.x = 1;
            try {
                mutatePrimitive(p, doThrow);
            } catch (TestException expected) {
                return p.x;
            }
            return p.x;
        }

        public static int testReference(boolean doThrow, Object oldValue, Object newValue) {
            RefBox p = new RefBox();
            p.ref = oldValue;
            try {
                mutateReference(p, newValue, doThrow);
            } catch (TestException expected) {
                return p.ref == newValue ? 1 : -1;
            }
            return p.ref == newValue ? 1 : -1;
        }

        public static int testTwoArguments(boolean same, boolean doThrow) {
            IntBox a = new IntBox();
            IntBox b = same ? a : new IntBox();
            a.x = 1;
            b.x = 2;
            try {
                mutateTwo(a, b, doThrow);
            } catch (TestException expected) {
                return same ? a.x : a.x * 100 + b.x;
            }
            return same ? a.x : a.x * 100 + b.x;
        }

        public static int testUnexposed(boolean doThrow) {
            IntBox passed = new IntBox();
            IntBox local = new IntBox();
            passed.x = 1;
            local.x = 7;
            try {
                mutatePrimitive(passed, doThrow);
            } catch (TestException expected) {
                return passed.x * 10 + local.x;
            }
            return passed.x * 10 + local.x;
        }

        public static void mutatePrimitive(IntBox p, boolean doThrow) {
            p.x = 42;
            if (doThrow) {
                throw new TestException();
            }
        }

        public static void mutateReference(RefBox p, Object newValue, boolean doThrow) {
            p.ref = newValue;
            if (doThrow) {
                throw new TestException();
            }
        }

        public static void mutateTwo(IntBox a, IntBox b, boolean doThrow) {
            a.x = 42;
            b.x = 84;
            if (doThrow) {
                throw new TestException();
            }
        }

        private static long mix(long accumulator, long value) {
            return Long.rotateLeft(accumulator ^ value, 13) * 0x9E3779B97F4A7C15L;
        }
    }
}
