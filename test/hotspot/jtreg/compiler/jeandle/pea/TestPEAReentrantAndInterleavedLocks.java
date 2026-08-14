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
 * @summary PEA eliminates reentrant virtual monitors and replays interleaved
 *          virtual monitors once in global bytecode-depth order
 * @library /test/lib /
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.pea.TestPEAReentrantAndInterleavedLocks
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jdk.test.lib.Asserts;

public class TestPEAReentrantAndInterleavedLocks {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestPEAReentrantAndInterleavedLocks$TestWrapper";
    private static final String MONITOR_ENTER =
            "jeandle.monitorenter_with_lightweight_lock";
    private static final String MONITOR_EXIT =
            "jeandle.monitorexit_with_lightweight_lock";

    public static void main(String[] args) throws Exception {
        Method reentrant2 = TestWrapper.class.getMethod("reentrant2Normal", int.class);
        Method reentrant3 = TestWrapper.class.getMethod("reentrant3ThrowFinally",
                boolean.class, int.class);
        Method innermost = TestWrapper.class.getMethod("escapeInnermost",
                boolean.class, int.class);
        Method middle = TestWrapper.class.getMethod("escapeMiddle",
                boolean.class, int.class);
        Method sink = TestWrapper.class.getMethod("sink", TestWrapper.Box.class);
        Method throwMarker = TestWrapper.class.getMethod("throwMarker");
        Method[] targets = {reentrant2, reentrant3, innermost, middle};

        for (int lockingMode : List.of(1, 2)) {
            builder(false, lockingMode, targets, sink, throwMarker)
                    .runPEAOnOffEquivalent();
        }

        try (PEATestUtils.RunResult run =
                builder(true, 2, targets, sink, throwMarker).run()) {
            assertNeverEscapeShape(run, reentrant2, 2);
            assertNeverEscapeShape(run, reentrant3, 3);
            assertReplayShape(run, innermost, sink,
                    List.of(new ExpectedLock(0, 0), new ExpectedLock(1, 1),
                            new ExpectedLock(0, 2), new ExpectedLock(2, 3)),
                    List.of(0, 1, 2));
            assertReplayShape(run, middle, sink,
                    List.of(new ExpectedLock(0, 0), new ExpectedLock(1, 1),
                            new ExpectedLock(0, 2)),
                    List.of(0, 1));
        }
    }

    private static PEATestUtils.RunBuilder builder(boolean shape, int lockingMode,
                                                    Method[] targets,
                                                    Method sink,
                                                    Method throwMarker) {
        PEATestUtils.RunBuilder builder = shape
                ? PEATestUtils.shapeRun(WRAPPER, targets)
                : PEATestUtils.behaviorRun(WRAPPER, targets);
        return builder.dontinline(sink)
                .dontinline(throwMarker)
                .lockingMode(lockingMode);
    }

    private static void assertNeverEscapeShape(PEATestUtils.RunResult run,
                                                Method target,
                                                int expectedEnters) {
        PEATestUtils.PEAReport report = run.report(target);
        report.assertFinalTransformIdle();
        PEATestUtils.PEARound first = report.round(0);
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();

        assertSourceMonitorShape(before, target, 1, expectedEnters);
        assertStats(first, target, 1, 0, 0);
        Asserts.assertEquals(effectCountForVO(first, "EliminateAllocation", 0), 1L,
                target + ": virtual allocation is eliminated");
        Asserts.assertEquals(effectCountForVO(first, "ReplaceCall", 0, MONITOR_ENTER),
                (long) expectedEnters,
                target + ": every reentrant monitorenter is folded");
        Asserts.assertEquals(effectCountForVO(first, "ReplaceCall", 0, MONITOR_EXIT),
                (long) before.lineCount(MONITOR_EXIT),
                target + ": every reentrant monitorexit is folded");
        Asserts.assertEquals(first.effectCount("Materialize"), 0L,
                target + ": NeverEscape owner does not materialize");

        Asserts.assertEquals(after.allocationBCIs(), List.of(),
                target + ": no allocation remains after PEA");
        after.assertAbsent("jeandle.monitorenter");
        after.assertAbsent("jeandle.monitorexit");
    }

    private static void assertReplayShape(PEATestUtils.RunResult run,
                                          Method target,
                                          Method sink,
                                          List<ExpectedLock> expectedLocks,
                                          List<Integer> materializedVOs) {
        PEATestUtils.PEAReport report = run.report(target);
        report.assertFinalTransformIdle();
        PEATestUtils.PEARound first = report.round(0);
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();
        List<Integer> sourceBCIs = before.allocationBCIs();

        assertSourceMonitorShape(before, target, 3, 4);
        if (materializedVOs.size() == 3) {
            assertStats(first, target, 0, 3, 0);
        } else {
            assertStats(first, target, 1, 2, 0);
        }
        Asserts.assertEquals(first.effectCount("EliminateAllocation"), 3L,
                target + ": every source allocation is analyzed");
        Asserts.assertEquals(effectCountForVO(first, "ReplaceCall", 0, MONITOR_ENTER),
                2L, target + ": both a monitorenters are folded");
        Asserts.assertEquals(effectCountForVO(first, "ReplaceCall", 1, MONITOR_ENTER),
                1L, target + ": b monitorenter is folded");
        Asserts.assertEquals(effectCountForVO(first, "ReplaceCall", 2, MONITOR_ENTER),
                1L, target + ": c monitorenter is folded");

        for (PEATestUtils.PEARound round : report.rounds()) {
            Asserts.assertEquals(round.effectCount("Materialize"),
                    (long) materializedVOs.size(),
                    target + ": exact materialization count in round "
                            + round.iteration());
            for (int objectId : materializedVOs) {
                Asserts.assertEquals(effectCountForVO(round, "Materialize", objectId),
                        1L, target + ": VO " + objectId
                                + " materializes once in round " + round.iteration());
            }
            assertPhysicalReplay(round, target, expectedLocks);
        }

        List<Integer> expectedAllocations = materializedVOs.stream()
                .map(sourceBCIs::get)
                .toList();
        Asserts.assertEquals(after.allocationBCIs(), expectedAllocations,
                target + ": only source OrigAlloc allocations that materialize survive");
        Asserts.assertEquals(after.lineCount(MONITOR_ENTER), expectedLocks.size(),
                target + ": one final monitorenter per physical replay ordinal");
        Asserts.assertTrue(after.lineCount(MONITOR_EXIT) >= expectedLocks.size(),
                target + ": final IR retains an exit for every replayed lock");

        String sinkName = PEATestUtils.MethodId.of(sink).llvmFunctionName();
        PEATestUtils.IRBlock sinkBlock = after.blockContaining(sinkName, 0);
        Asserts.assertEquals(sinkBlock.occurrenceCount(MONITOR_ENTER),
                expectedLocks.size(),
                target + ": all replay monitorenters are in the sink predecessor");
        Asserts.assertEquals(sinkBlock.occurrenceCount("store atomic i32"),
                materializedVOs.size() * 2,
                target + ": both fields of every materialized owner replay before sink");
        sinkBlock.assertAbsent("jeandle.new_instance");
        sinkBlock.assertBefore("store atomic i32", materializedVOs.size() * 2 - 1,
                MONITOR_ENTER, 0);
        sinkBlock.assertBefore(MONITOR_ENTER, expectedLocks.size() - 1,
                sinkName, 0);
    }

    private static void assertPhysicalReplay(PEATestUtils.PEARound round,
                                             Method target,
                                             List<ExpectedLock> expectedLocks) {
        Map<PEATestUtils.PEALockReplayPhysicalGroup,
                List<PEATestUtils.PEALockReplay>> groups =
                round.lockReplayPhysicalGroups();
        Asserts.assertEquals(groups.size(), 1,
                target + ": one physical replay batch in round " + round.iteration());

        List<PEATestUtils.PEALockReplay> rows = groups.values().iterator().next();
        LinkedHashMap<Integer, PEATestUtils.PEALockReplay> physical =
                new LinkedHashMap<>();
        for (PEATestUtils.PEALockReplay row : rows) {
            PEATestUtils.PEALockReplay previous = physical.putIfAbsent(
                    row.ordinal(), row);
            if (previous != null) {
                Asserts.assertEquals(row.receiverVO(), previous.receiverVO(),
                        target + ": logical aliases share the physical receiver");
                Asserts.assertEquals(row.depth(), previous.depth(),
                        target + ": logical aliases share the physical depth");
            }
        }
        Asserts.assertEquals(physical.size(), expectedLocks.size(),
                target + ": exact physical replay operation count in round "
                        + round.iteration());

        int ordinal = 0;
        int previousDepth = -1;
        for (PEATestUtils.PEALockReplay replay : physical.values()) {
            ExpectedLock expected = expectedLocks.get(ordinal);
            Asserts.assertEquals(replay.ordinal(), ordinal,
                    target + ": contiguous physical ordinal");
            Asserts.assertEquals(replay.receiverVO(), expected.receiverVO(),
                    target + ": receiver for physical ordinal " + ordinal);
            Asserts.assertEquals(replay.depth(), expected.depth(),
                    target + ": bytecode depth for physical ordinal " + ordinal);
            Asserts.assertTrue(replay.depth() > previousDepth,
                    target + ": globally increasing replay depth");
            previousDepth = replay.depth();
            ordinal++;
        }
    }

    private static void assertSourceMonitorShape(PEATestUtils.IRBody before,
                                                 Method target,
                                                 int allocations,
                                                 int monitorEnters) {
        List<Integer> allocationBCIs = before.allocationBCIs();
        Asserts.assertEquals(allocationBCIs.size(), allocations,
                target + ": source allocation count");
        Set<Integer> distinctBCIs = new HashSet<>(allocationBCIs);
        Asserts.assertEquals(distinctBCIs.size(), allocations,
                target + ": source allocations have distinct BCIs");
        Asserts.assertEquals(before.lineCount(MONITOR_ENTER), monitorEnters,
                target + ": source lexical monitorenter count");
        Asserts.assertTrue(before.lineCount(MONITOR_EXIT) >= monitorEnters,
                target + ": source IR contains balanced normal and cleanup exits");
    }

    private static void assertStats(PEATestUtils.PEARound round, Method target,
                                    int never, int partial, int always) {
        Asserts.assertTrue(round.hasStats(), target + ": round-0 PEA stats");
        Asserts.assertEquals(round.neverEscapes(), never, target + ": NeverEscapes");
        Asserts.assertEquals(round.partiallyEscapes(), partial,
                target + ": PartiallyEscapes");
        Asserts.assertEquals(round.alwaysEscapes(), always, target + ": AlwaysEscapes");
    }

    private static long effectCountForVO(PEATestUtils.PEARound round, String kind,
                                         int objectId, String... detailParts) {
        String objectToken = "[VO=" + objectId + "]";
        return round.effects().stream()
                .filter(effect -> effect.kind().equals(kind))
                .filter(effect -> List.of(effect.detail().split("\\s+"))
                        .contains(objectToken))
                .filter(effect -> List.of(detailParts).stream()
                        .allMatch(effect.detail()::contains))
                .count();
    }

    private record ExpectedLock(int receiverVO, int depth) {}

    public static class TestWrapper {
        private static final int ITERATIONS = 250;
        private static final int INNER_A_ID = 101;
        private static final int INNER_B_ID = 102;
        private static final int INNER_C_ID = 103;
        private static final int MIDDLE_A_ID = 201;
        private static final int MIDDLE_B_ID = 202;
        private static final int MIDDLE_C_ID = 203;

        public static Box saved;
        public static int finallyCount;

        public static class Box {
            public int id;
            public int value;
            public Box next;
        }

        public static class Marker extends RuntimeException {
            private static final long serialVersionUID = 1L;
        }

        public static void main(String[] args) throws Exception {
            new Box();
            new Marker();
            PEATestUtils.compileConfiguredTargetsAtLevel4();

            long digest = 0x61c8864680b583ebL;
            for (int iteration = 0; iteration < ITERATIONS; iteration++) {
                int seed = iteration & 31;
                int reentrant2 = reentrant2Normal(seed);
                Asserts.assertEquals(reentrant2, seed + 3,
                        "two-level normal reentrancy");
                digest = mix(digest, reentrant2);

                int reentrant3Normal = reentrant3ThrowFinally(false, seed);
                Asserts.assertEquals(reentrant3Normal, seed + 7,
                        "three-level normal reentrancy");
                digest = mix(digest, reentrant3Normal);
                int reentrant3Throw = reentrant3ThrowFinally(true, seed);
                Asserts.assertEquals(reentrant3Throw, seed + 1007,
                        "three-level exceptional reentrancy");
                digest = mix(digest, reentrant3Throw);

                for (boolean doThrow : List.of(false, true)) {
                    saved = null;
                    int inner = escapeInnermost(doThrow, seed);
                    int expectedInner = 3 * seed + 75 + (doThrow ? 10_000 : 0);
                    Asserts.assertEquals(inner, expectedInner,
                            "innermost-owner result");
                    assertSavedChain(
                            new int[] {INNER_C_ID, INNER_B_ID, INNER_A_ID},
                            new int[] {seed + 38, seed + 22, seed + 15});
                    digest = mix(digest, inner);

                    saved = null;
                    int middle = escapeMiddle(doThrow, seed);
                    int expectedMiddle = 3 * seed + 165 + (doThrow ? 20_000 : 0);
                    Asserts.assertEquals(middle, expectedMiddle,
                            "middle-owner result");
                    assertSavedChain(
                            new int[] {MIDDLE_B_ID, MIDDLE_A_ID},
                            new int[] {seed + 52, seed + 45});
                    digest = mix(digest, middle);
                }
            }

            int expectedFinallyCount = ITERATIONS * 6;
            Asserts.assertEquals(finallyCount, expectedFinallyCount,
                    "every normal and exceptional return executes finally");
            System.out.println("PEA-RESULT:"
                    + Long.toUnsignedString(digest, 16) + ":" + finallyCount);
        }

        private static void assertSavedChain(int[] expectedIds,
                                             int[] expectedValues) {
            Asserts.assertEquals(expectedIds.length, expectedValues.length,
                    "saved-chain oracle dimensions");
            Box owner = saved;
            for (int index = 0; index < expectedIds.length; index++) {
                Asserts.assertNotNull(owner,
                        "escaped owner chain element " + index);
                Asserts.assertEquals(owner.id, expectedIds[index],
                        "escaped owner id at chain element " + index);
                Asserts.assertEquals(owner.value, expectedValues[index],
                        "escaped owner value at chain element " + index);
                Asserts.assertFalse(Thread.holdsLock(owner),
                        "escaped owner lock released before reacquire at chain element "
                                + index);
                synchronized (owner) {
                    Asserts.assertTrue(Thread.holdsLock(owner),
                            "escaped owner held during reacquire at chain element "
                                    + index);
                    Asserts.assertEquals(owner.id, expectedIds[index],
                            "escaped owner identity remains stable at chain element "
                                    + index);
                    Asserts.assertEquals(owner.value, expectedValues[index],
                            "escaped owner fields remain stable at chain element "
                                    + index);
                }
                Asserts.assertFalse(Thread.holdsLock(owner),
                        "escaped owner lock released after reacquire at chain element "
                                + index);
                owner = owner.next;
            }
            Asserts.assertNull(owner, "escaped owner chain terminates exactly");
        }

        private static long mix(long digest, int value) {
            return Long.rotateLeft(digest ^ Integer.toUnsignedLong(value), 17)
                    * 0x9e3779b97f4a7c15L;
        }

        public static int reentrant2Normal(int seed) {
            Box p = new Box();
            p.id = 11;
            p.value = seed;
            p.next = p;
            int result;
            synchronized (p) {
                p.value++;
                synchronized (p.next) {
                    p.value += 2;
                    result = p.value;
                }
            }
            return result;
        }

        public static int reentrant3ThrowFinally(boolean doThrow, int seed) {
            Box p = new Box();
            p.id = 13;
            p.value = seed;
            p.next = p;
            int result;
            try {
                synchronized (p) {
                    p.value++;
                    synchronized (p.next) {
                        p.value += 2;
                        synchronized (p.next.next) {
                            p.value += 4;
                            if (doThrow) {
                                throwMarker();
                            }
                            result = p.value;
                        }
                    }
                }
            } catch (Marker expected) {
                result = p.value + 1000;
            } finally {
                finallyCount++;
            }
            return result;
        }

        public static int escapeInnermost(boolean doThrow, int seed) {
            Box a = new Box();
            Box b = new Box();
            Box c = new Box();
            a.id = INNER_A_ID;
            a.value = seed + 10;
            b.id = INNER_B_ID;
            b.value = seed + 20;
            b.next = a;
            c.id = INNER_C_ID;
            c.value = seed + 30;
            c.next = b;
            int result;
            boolean identityMatches = true;
            try {
                synchronized (a) {
                    a.value++;
                    synchronized (b) {
                        b.value += 2;
                        synchronized (b.next) {
                            a.value += 4;
                            synchronized (c) {
                                c.value += 8;
                                sink(c);
                                identityMatches = saved == c;
                                int value = a.value + b.value + c.value;
                                if (doThrow) {
                                    throwMarker();
                                }
                                result = identityMatches ? value : Integer.MIN_VALUE;
                            }
                        }
                    }
                }
            } catch (Marker expected) {
                int value = a.value + b.value + c.value + 10_000;
                result = identityMatches ? value : Integer.MIN_VALUE;
            } finally {
                finallyCount++;
            }
            return result;
        }

        public static int escapeMiddle(boolean doThrow, int seed) {
            Box a = new Box();
            Box b = new Box();
            Box c = new Box();
            a.id = MIDDLE_A_ID;
            a.value = seed + 40;
            b.id = MIDDLE_B_ID;
            b.value = seed + 50;
            b.next = a;
            c.id = MIDDLE_C_ID;
            c.value = seed + 60;
            int result;
            boolean identityMatches = true;
            try {
                synchronized (a) {
                    a.value++;
                    synchronized (b) {
                        b.value += 2;
                        synchronized (b.next) {
                            a.value += 4;
                            synchronized (c) {
                                c.value += 8;
                                sink(b);
                                identityMatches = saved == b;
                                int value = a.value + b.value + c.value;
                                if (doThrow) {
                                    throwMarker();
                                }
                                result = identityMatches ? value : Integer.MIN_VALUE;
                            }
                        }
                    }
                }
            } catch (Marker expected) {
                int value = a.value + b.value + c.value + 20_000;
                result = identityMatches ? value : Integer.MIN_VALUE;
            } finally {
                finallyCount++;
            }
            return result;
        }

        public static void sink(Box owner) {
            saved = owner;
        }

        public static void throwMarker() {
            throw new Marker();
        }
    }
}
