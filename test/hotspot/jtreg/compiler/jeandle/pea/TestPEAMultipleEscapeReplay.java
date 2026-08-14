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
 * @summary PEA replays each distinct escape use point once, keeps mutually
 *          exclusive escape paths from crossing state, switches the alias to
 *          the real object after escape, and keeps loop-header PHIs complete
 * @library /test/lib /
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.pea.TestPEAMultipleEscapeReplay
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jdk.test.lib.Asserts;

public class TestPEAMultipleEscapeReplay {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestPEAMultipleEscapeReplay$TestWrapper";
    private static final String INT_STORE = "store atomic i32";
    private static final String REF_STORE = "store atomic ptr addrspace(1)";

    public static void main(String[] args) throws Exception {
        PEATestUtils.assertPhiParserContracts();

        Method two = TestWrapper.class.getMethod("twoEscapePoints", boolean.class, int.class);
        Method sameBlock = TestWrapper.class.getMethod("sameBlockOverwriteAndReuse",
                boolean.class, int.class);
        Method mutex = TestWrapper.class.getMethod("mutuallyExclusiveBranches",
                boolean.class, int.class);
        Method loop = TestWrapper.class.getMethod("loopEscapeFirstOrLater",
                int.class, boolean.class, int.class);
        Method callStore = TestWrapper.class.getMethod("callThenStoreSameBlock",
                boolean.class, int.class);
        Method consume = TestWrapper.class.getMethod("consume", TestWrapper.Box.class);
        Method consumeLeft = TestWrapper.class.getMethod("consumeLeft", TestWrapper.Box.class);
        Method consumeRight = TestWrapper.class.getMethod("consumeRight", TestWrapper.Box.class);
        Method[] targets = {two, sameBlock, mutex, loop, callStore};

        behaviorBuilder(targets, consume, consumeLeft, consumeRight).runPEAOnOffEquivalent();

        try (PEATestUtils.RunResult run =
                shapeBuilder(targets, consume, consumeLeft, consumeRight).run()) {
            assertTwoEscapePoints(run, two, consume);
            assertSameBlock(run, sameBlock, consume);
            assertMutuallyExclusive(run, mutex, consumeLeft, consumeRight);
            assertLoopEscape(run, loop, consume);
            assertCallThenStore(run, callStore, consume);
        }
    }

    private static PEATestUtils.RunBuilder behaviorBuilder(Method[] targets, Method consume,
                                                           Method consumeLeft, Method consumeRight) {
        return PEATestUtils.behaviorRun(WRAPPER, targets)
                .dontinline(consume).dontinline(consumeLeft).dontinline(consumeRight);
    }

    private static PEATestUtils.RunBuilder shapeBuilder(Method[] targets, Method consume,
                                                        Method consumeLeft, Method consumeRight) {
        return PEATestUtils.shapeRun(WRAPPER, targets)
                .dontinline(consume).dontinline(consumeLeft).dontinline(consumeRight);
    }

    private static void assertPartialEscape(PEATestUtils.RunResult run, Method target)
            throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();
        assertDistinctAllocations(before, 1, target);
        Asserts.assertEquals(after.allocationBCIs(), before.allocationBCIs(),
                target + ": PartiallyEscapes retains the source allocation");
        Asserts.assertTrue(report.round(0).effectCount("Materialize", "[VO=0]") >= 1,
                target + ": object materialized at least once");
        after.assertAbsent("poison");
        report.assertFinalTransformIdle();
        assertVerifierShape(run, report, target);
    }

    private static void assertTwoEscapePoints(PEATestUtils.RunResult run, Method target,
                                              Method consume) throws Exception {
        assertPartialEscape(run, target);
        PEATestUtils.IRBody after = run.report(target).finalAfter();
        String calleeName = PEATestUtils.MethodId.of(consume).llvmFunctionName();
        Asserts.assertEquals(after.occurrenceCount(calleeName), 2,
                target + ": exactly two escape use points");
    }

    private static void assertSameBlock(PEATestUtils.RunResult run, Method target,
                                        Method consume) throws Exception {
        assertPartialEscape(run, target);
        PEATestUtils.IRBody after = run.report(target).finalAfter();
        String calleeName = PEATestUtils.MethodId.of(consume).llvmFunctionName();
        Asserts.assertEquals(after.occurrenceCount(calleeName), 2,
                target + ": exactly two escape use points");
        Asserts.assertTrue(after.lineCount(INT_STORE) >= 2,
                target + ": replay store plus a real post-escape write survive");
    }

    private static void assertMutuallyExclusive(PEATestUtils.RunResult run, Method target,
                                                Method consumeLeft, Method consumeRight)
            throws Exception {
        assertPartialEscape(run, target);
        PEATestUtils.IRBody after = run.report(target).finalAfter();
        String leftName = PEATestUtils.MethodId.of(consumeLeft).llvmFunctionName();
        String rightName = PEATestUtils.MethodId.of(consumeRight).llvmFunctionName();
        PEATestUtils.IRBlock leftBlock = after.blockContaining(leftName, 0);
        PEATestUtils.IRBlock rightBlock = after.blockContaining(rightName, 0);
        Asserts.assertNotEquals(leftBlock.label(), rightBlock.label(),
                target + ": mutually exclusive escapes live in distinct blocks");
    }

    private static void assertLoopEscape(PEATestUtils.RunResult run, Method target,
                                        Method consume) throws Exception {
        assertPartialEscape(run, target);
        PEATestUtils.IRBody after = run.report(target).finalAfter();
        Asserts.assertTrue(after.lineCount("load atomic i32") >= 1,
                target + ": post-escape loop body reads the materialized object");
    }

    private static void assertCallThenStore(PEATestUtils.RunResult run, Method target,
                                            Method consume) throws Exception {
        assertPartialEscape(run, target);
        PEATestUtils.IRBody after = run.report(target).finalAfter();
        String calleeName = PEATestUtils.MethodId.of(consume).llvmFunctionName();
        after.assertBefore(calleeName, 0, REF_STORE, 0);
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
        private static final String EXPECTED_DIGEST = "171bd07ce15b13a9";

        public static class Box {
            int value;
        }

        private static int callCount;
        private static Box globalBox;

        public static void main(String[] args) throws Exception {
            new Box();
            PEATestUtils.compileConfiguredTargetsAtLevel4();

            long digest = 0x428A2F98D728AE22L;
            for (int seed : new int[] {2, 17}) {
                for (boolean first : new boolean[] {false, true}) {
                    resetObservation();
                    int r = twoEscapePoints(first, seed);
                    Asserts.assertEquals(r, (seed + (first ? 1 : 2)) * 31, "twoEscapePoints");
                    Asserts.assertEquals(callCount, 1, "twoEscapePoints single escape");
                    Asserts.assertNotNull(globalBox, "twoEscapePoints published");
                    Asserts.assertEquals(globalBox.value, seed + (first ? 1 : 2),
                            "twoEscapePoints published value");
                    digest = mix(digest, r);
                    digest = mix(digest, callCount);
                    digest = mix(digest, globalBox.value);
                }

                for (boolean escape : new boolean[] {false, true}) {
                    resetObservation();
                    int r = sameBlockOverwriteAndReuse(escape, seed);
                    int expected = (escape ? (seed + 9) : (seed + 1)) * 31;
                    Asserts.assertEquals(r, expected, "sameBlockOverwriteAndReuse");
                    if (escape) {
                        Asserts.assertEquals(callCount, 2, "sameBlock two uses");
                        Asserts.assertEquals(globalBox.value, seed + 9,
                                "sameBlock second use sees overwrite");
                    } else {
                        Asserts.assertEquals(callCount, 0, "sameBlock no-call");
                    }
                    digest = mix(digest, r);
                    digest = mix(digest, callCount);
                }

                for (boolean left : new boolean[] {false, true}) {
                    resetObservation();
                    int r = mutuallyExclusiveBranches(left, seed);
                    Asserts.assertEquals(r, (seed + (left ? 10 : 20)) * 31,
                            "mutuallyExclusiveBranches");
                    Asserts.assertEquals(callCount, 1, "mutex single escape");
                    Asserts.assertEquals(globalBox.value, seed + (left ? 10 : 20),
                            "mutex published value");
                    digest = mix(digest, r);
                    digest = mix(digest, globalBox.value);
                }

                for (int trips : new int[] {1, 3}) {
                    for (boolean escapeFirst : new boolean[] {false, true}) {
                        resetObservation();
                        int r = loopEscapeFirstOrLater(trips, escapeFirst, seed);
                        int lastField = seed;
                        int sum = 0;
                        for (int i = 0; i < trips; i++) {
                            lastField = lastField + i + 1;
                            sum = sum * 31 + lastField;
                        }
                        Asserts.assertEquals(r, sum * 31 + lastField, "loopEscapeFirstOrLater");
                        Asserts.assertEquals(callCount, 1, "loop escapes exactly once");
                        Asserts.assertEquals(globalBox.value, lastField,
                                "loop published last value");
                        digest = mix(digest, r);
                        digest = mix(digest, globalBox.value);
                    }
                }

                for (boolean escape : new boolean[] {false, true}) {
                    resetObservation();
                    int r = callThenStoreSameBlock(escape, seed);
                    Asserts.assertEquals(r, (seed + 1) * 31, "callThenStoreSameBlock");
                    if (escape) {
                        Asserts.assertEquals(callCount, 1, "callThenStore call");
                        Asserts.assertEquals(globalBox.value, seed + 1,
                                "callThenStore published value");
                    }
                    digest = mix(digest, r);
                    digest = mix(digest, callCount);
                }
            }

            String payload = Long.toUnsignedString(digest, 16);
            Asserts.assertEquals(payload, EXPECTED_DIGEST, "behavior digest");
            System.out.println("PEA-RESULT:" + payload);
        }

        public static int twoEscapePoints(boolean first, int seed) {
            Box box = new Box();
            box.value = seed + 1;
            if (first) {
                consume(box);
                return box.value * 31;
            }
            box.value = seed + 2;
            consume(box);
            return box.value * 31;
        }

        public static int sameBlockOverwriteAndReuse(boolean escape, int seed) {
            Box box = new Box();
            box.value = seed + 1;
            if (!escape) {
                return box.value * 31;
            }
            consume(box);
            box.value = seed + 9;
            consume(box);
            return box.value * 31;
        }

        public static int mutuallyExclusiveBranches(boolean left, int seed) {
            Box box = new Box();
            box.value = seed + 1;
            if (left) {
                box.value = seed + 10;
                consumeLeft(box);
                return box.value * 31;
            }
            box.value = seed + 20;
            consumeRight(box);
            return box.value * 31;
        }

        public static int loopEscapeFirstOrLater(int trips, boolean escapeFirst, int seed) {
            Box box = new Box();
            box.value = seed;
            int sum = 0;
            for (int i = 0; i < trips; i++) {
                box.value = box.value + i + 1;
                if (escapeFirst ? (i == 0) : (i + 1 == trips)) {
                    consume(box);
                }
                sum = sum * 31 + box.value;
            }
            return sum * 31 + box.value;
        }

        public static int callThenStoreSameBlock(boolean escape, int seed) {
            Box box = new Box();
            box.value = seed + 1;
            if (!escape) {
                return box.value * 31;
            }
            consume(box);
            globalBox = box;
            return globalBox.value * 31;
        }

        public static void consume(Box box) {
            callCount++;
            globalBox = box;
        }

        public static void consumeLeft(Box box) {
            callCount++;
            globalBox = box;
        }

        public static void consumeRight(Box box) {
            callCount++;
            globalBox = box;
        }

        private static void resetObservation() {
            callCount = 0;
            globalBox = null;
        }

        private static long mix(long digest, int value) {
            return Long.rotateLeft(digest ^ Integer.toUnsignedLong(value), 29)
                    * 0x9E3779B97F4A7C15L;
        }
    }
}
