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
 * along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 */

/*
 * @test
 * @summary PEA scalar-replaces a nested object graph through an inlined
 *          boundary, materializes the same graph once before a no-inline
 *          boundary, and preserves behavior under natural tiered and
 *          eager compilation
 * @library /test/lib /
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.pea.TestPEAInliningAndTieredCompilation
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;

import jdk.test.lib.Asserts;
import jdk.test.whitebox.WhiteBox;

public class TestPEAInliningAndTieredCompilation {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestPEAInliningAndTieredCompilation$TestWrapper";
    private static final String MODE_PROPERTY = "test.pea.compilation.mode";
    private static final String INLINE_SHAPE = "inline-shape";
    private static final String NOINLINE_SHAPE = "noinline-shape";
    private static final String NORMAL_BEHAVIOR = "normal-behavior";
    private static final String TIERED_BEHAVIOR = "tiered-behavior";
    private static final String XCOMP_BEHAVIOR = "xcomp-behavior";
    private static final String INT_LOAD = "load atomic i32";
    private static final String INT_STORE = "store atomic i32";
    private static final String REF_LOAD = "load atomic ptr addrspace(1)";
    private static final String REF_STORE = "store atomic ptr addrspace(1)";

    public static void main(String[] args) throws Exception {
        Method caller = TestWrapper.class.getMethod("caller", int.class, int.class);
        Method boundary = TestWrapper.class.getDeclaredMethod("boundary",
                TestWrapper.Outer.class, TestWrapper.Inner.class,
                TestWrapper.Inner.class, int.class);
        PEATestUtils.MethodId callerId = PEATestUtils.MethodId.of(caller);

        try (PEATestUtils.RunResult run = PEATestUtils.shapeRun(WRAPPER, callerId)
                .inline(boundary)
                .extraFlags("-D" + MODE_PROPERTY + "=" + INLINE_SHAPE)
                .run()) {
            assertInlineShape(run, callerId, boundary);
        }

        try (PEATestUtils.RunResult run = PEATestUtils.shapeRun(WRAPPER, callerId)
                .dontinline(boundary)
                .extraFlags("-D" + MODE_PROPERTY + "=" + NOINLINE_SHAPE)
                .run()) {
            assertNoInlineShape(run, callerId, boundary);
        }

        behaviorBuilder(callerId, boundary, NORMAL_BEHAVIOR)
                .runPEAOnOffEquivalent();
        behaviorBuilder(callerId, boundary, TIERED_BEHAVIOR)
                .tieredCompilation()
                .runPEAOnOffEquivalent();
        behaviorBuilder(callerId, boundary, XCOMP_BEHAVIOR)
                .xcomp()
                .runPEAOnOffEquivalent();
    }

    private static PEATestUtils.RunBuilder behaviorBuilder(
            PEATestUtils.MethodId caller, Method boundary, String mode) {
        return PEATestUtils.behaviorRun(WRAPPER, caller)
                .inline(boundary)
                .extraFlags("-D" + MODE_PROPERTY + "=" + mode);
    }

    private static void assertInlineShape(
            PEATestUtils.RunResult run, PEATestUtils.MethodId caller, Method boundary)
            throws Exception {
        PEATestUtils.PEAReport report = run.report(caller);
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();
        assertTwoDistinctSourceAllocations(before, caller);
        assertSound(report, run.finalIR(caller), caller);

        String boundaryName = PEATestUtils.MethodId.of(boundary).llvmFunctionName();
        after.assertOccurrenceCount(boundaryName, 0);
        Asserts.assertEquals(report.maxNeverEscapes(), 2,
                caller + ": both caller allocations are NeverEscape after inlining");
        Asserts.assertEquals(report.maxPartiallyEscapes(), 0,
                caller + ": inlined graph is never partially escaping");
        Asserts.assertEquals(report.maxAlwaysEscapes(), 0,
                caller + ": inlined graph is never always escaping");
        Asserts.assertEquals(after.allocationBCIs(), List.of(),
                caller + ": inlined graph allocations are scalar replaced");
        Asserts.assertEquals(run.finalIR(caller).loweredAllocCount(), 0,
                caller + ": no allocation survives lowering");
        after.assertLineCount(INT_LOAD, 0);
        after.assertLineCount(INT_STORE, 0);
        after.assertLineCount(REF_LOAD, 0);
        after.assertLineCount(REF_STORE, 0);
    }

    private static void assertNoInlineShape(
            PEATestUtils.RunResult run, PEATestUtils.MethodId caller, Method boundary)
            throws Exception {
        PEATestUtils.PEAReport report = run.report(caller);
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();
        assertTwoDistinctSourceAllocations(before, caller);
        assertSound(report, run.finalIR(caller), caller);

        String boundaryName = PEATestUtils.MethodId.of(boundary).llvmFunctionName();
        after.assertOccurrenceCount(boundaryName, 1);
        Asserts.assertEquals(report.maxNeverEscapes(), 0,
                caller + ": no no-inline argument is NeverEscape");
        Asserts.assertEquals(report.maxPartiallyEscapes(), 2,
                caller + ": both no-inline arguments are partially escaping");
        Asserts.assertEquals(report.maxAlwaysEscapes(), 0,
                caller + ": no no-inline argument is classified AlwaysEscape");
        for (PEATestUtils.PEARound round : report.rounds()) {
            Asserts.assertEquals(round.effectCount("EliminateStore"), 3L,
                    caller + ": all three initialized fields are tracked in round "
                            + round.iteration());
            Asserts.assertEquals(round.effectCount("Materialize"), 2L,
                    caller + ": both distinct call arguments materialize once in round "
                            + round.iteration());
        }
        PEATestUtils.AllocationKey[] sourceAllocations = before.allocations().stream()
                .map(PEATestUtils.AllocationSite::key)
                .toArray(PEATestUtils.AllocationKey[]::new);
        after.assertRetainsExactlyOriginalAllocations(before, sourceAllocations);
        Asserts.assertEquals(run.finalIR(caller).loweredAllocCount(), 2,
                caller + ": only the two source allocations survive lowering");

        PEATestUtils.IRBlock callBlock = after.blockContaining(boundaryName, 0);
        callBlock.assertOccurrenceCount(INT_STORE, 2);
        callBlock.assertOccurrenceCount(REF_STORE, 1);
        callBlock.assertBefore(INT_STORE, 0, boundaryName, 0);
        callBlock.assertBefore(INT_STORE, 1, boundaryName, 0);
        callBlock.assertBefore(REF_STORE, 0, boundaryName, 0);
        callBlock.assertAbsent("jeandle.new_instance");
        Asserts.assertEquals(after.lineCount(INT_STORE), 2,
                caller + ": exact integer replay count");
        Asserts.assertEquals(after.lineCount(REF_STORE), 1,
                caller + ": exact reference replay count");
    }

    private static void assertTwoDistinctSourceAllocations(
            PEATestUtils.IRBody before, PEATestUtils.MethodId caller) {
        List<Integer> bcis = before.allocationBCIs();
        Asserts.assertEquals(bcis.size(), 2, caller + ": source allocation count");
        Asserts.assertEquals(new HashSet<>(bcis).size(), 2,
                caller + ": source allocation BCIs are distinct");
    }

    private static void assertSound(PEATestUtils.PEAReport report,
                                    PEATestUtils.IRBody finalIR,
                                    PEATestUtils.MethodId caller) {
        for (PEATestUtils.PEARound round : report.rounds()) {
            PEATestUtils.assertStructuralSoundness(
                    round.before(), caller + " before round " + round.iteration());
            PEATestUtils.assertStructuralSoundness(
                    round.after(), caller + " after round " + round.iteration());
        }
        PEATestUtils.assertStructuralSoundness(finalIR, caller + " final IR");
    }

    public static class TestWrapper {
        private static final int TIERED_WARMUP_LIMIT = 200_000;
        private static final long DIGEST_SEED = 0x243f6a8885a308d3L;
        private static final String EXPECTED_DIGEST = "3cc8fb085967669";

        public static class Outer {
            int tag;
            Inner child;
        }

        public static class Inner {
            int value;
        }

        public static void main(String[] args) throws Exception {
            new Outer();
            new Inner();
            Method caller = TestWrapper.class.getMethod("caller", int.class, int.class);
            String mode = System.getProperty(MODE_PROPERTY);
            if (mode == null) {
                throw new AssertionError("missing " + MODE_PROPERTY);
            }

            switch (mode) {
                case TIERED_BEHAVIOR -> naturallyReachLevel4(caller);
                case XCOMP_BEHAVIOR -> {
                    assertOne(5, 7);
                    PEATestUtils.confirmLevel4(caller);
                }
                case INLINE_SHAPE, NOINLINE_SHAPE, NORMAL_BEHAVIOR -> {
                    PEATestUtils.compileConfiguredTargetsAtLevel4();
                }
                default -> throw new AssertionError("unknown compilation mode: " + mode);
            }

            String digest = runBehaviorMatrix();
            Asserts.assertEquals(digest, EXPECTED_DIGEST, mode + ": exact behavior digest");
            System.out.println("PEA-RESULT:" + digest);
        }

        private static void naturallyReachLevel4(Method caller) {
            WhiteBox whiteBox = WhiteBox.getWhiteBox();
            int invocations = 0;
            while (invocations < TIERED_WARMUP_LIMIT) {
                int seed = (invocations & 63) - 31;
                int delta = (invocations % 11) - 5;
                assertOne(seed, delta);
                invocations++;
                if (whiteBox.isMethodCompiled(caller)
                        && whiteBox.getMethodCompilationLevel(caller) == 4) {
                    PEATestUtils.confirmLevel4(caller);
                    return;
                }
            }
            throw new AssertionError("natural tiered warmup exhausted after "
                    + invocations + " invocations: compiled="
                    + whiteBox.isMethodCompiled(caller) + ", level="
                    + whiteBox.getMethodCompilationLevel(caller));
        }

        private static String runBehaviorMatrix() {
            long digest = DIGEST_SEED;
            int[] seeds = {-17, 0, 5, 101};
            int[] deltas = {-3, 0, 7};
            for (int seed : seeds) {
                for (int delta : deltas) {
                    long actual = assertOne(seed, delta);
                    digest = mix(digest, actual);
                }
            }
            return Long.toUnsignedString(digest, 16);
        }

        private static long assertOne(int seed, int delta) {
            long actual = caller(seed, delta);
            int updated = seed + delta;
            int tag = seed * 3 + 7 + updated;
            long expected = ((long) tag << 32) ^ Integer.toUnsignedLong(updated);
            Asserts.assertEquals(actual, expected,
                    "payload and repeated-argument identity for seed=" + seed
                            + ", delta=" + delta);
            return actual;
        }

        public static long caller(int seed, int delta) {
            Inner inner = new Inner();
            Outer outer = new Outer();
            inner.value = seed;
            outer.tag = seed * 3 + 7;
            outer.child = inner;
            return boundary(outer, inner, inner, delta);
        }

        private static long boundary(Outer outer, Inner first, Inner second, int delta) {
            if (first != second) {
                return Long.MIN_VALUE + 1;
            }
            if (outer.child != first) {
                return Long.MIN_VALUE + 2;
            }
            first.value += delta;
            outer.tag += first.value;
            return ((long) outer.tag << 32) ^ Integer.toUnsignedLong(second.value);
        }

        private static long mix(long digest, long value) {
            return Long.rotateLeft(digest ^ value, 19) * 0x9e3779b97f4a7c15L;
        }
    }
}
