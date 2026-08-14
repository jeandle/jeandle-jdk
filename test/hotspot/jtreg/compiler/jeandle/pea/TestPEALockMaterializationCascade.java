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
 * @summary PEA materializes cyclic, shared virtual lock owners before a real
 *          monitor or an escaping call and replays their locks once in depth order
 * @library /test/lib /
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.pea.TestPEALockMaterializationCascade
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jdk.test.lib.Asserts;

public class TestPEALockMaterializationCascade {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestPEALockMaterializationCascade$TestWrapper";
    private static final String MONITOR_ENTER = "@jeandle.monitorenter";
    private static final String MONITOR_EXIT = "@jeandle.monitorexit";
    private static final String INT_STORE = "store atomic i32";
    private static final String REF_STORE = "store atomic ptr addrspace(1)";

    public static void main(String[] args) throws Exception {
        Method realEnter = TestWrapper.class.getMethod(
                "realEnterCascade", boolean.class, int.class);
        Method calleeEscape = TestWrapper.class.getMethod(
                "calleeEscapeCascade", boolean.class, int.class);
        Method escapeAndMaybeThrow = TestWrapper.class.getMethod(
                "escapeAndMaybeThrow", TestWrapper.Node.class, boolean.class);
        Method publishAfterRealAndMaybeThrow = TestWrapper.class.getMethod(
                "publishAfterRealAndMaybeThrow", TestWrapper.Node.class, boolean.class);
        Method maybeThrow = TestWrapper.class.getMethod("maybeThrow", boolean.class);
        Method verifyAndReacquire = TestWrapper.class.getMethod(
                "verifyAndReacquire", TestWrapper.Node.class, TestWrapper.Node.class,
                TestWrapper.Node.class, Object.class);
        Method[] targets = {realEnter, calleeEscape};
        Method[] callees = {escapeAndMaybeThrow, publishAfterRealAndMaybeThrow,
                maybeThrow, verifyAndReacquire};

        for (int lockingMode : new int[] {1, 2}) {
            behaviorBuilder(targets, callees).lockingMode(lockingMode)
                    .runPEAOnOffEquivalent();
        }

        try (PEATestUtils.RunResult run =
                shapeBuilder(targets, callees).lockingMode(2).run()) {
            assertShape(run, realEnter, publishAfterRealAndMaybeThrow, 5, 4, false);
            assertShape(run, calleeEscape, escapeAndMaybeThrow, 4, 4, true);
        }
    }

    private static PEATestUtils.RunBuilder behaviorBuilder(Method[] targets,
                                                            Method[] callees) {
        PEATestUtils.RunBuilder builder = PEATestUtils.behaviorRun(WRAPPER, targets);
        for (Method callee : callees) {
            builder.dontinline(callee);
        }
        return builder;
    }

    private static PEATestUtils.RunBuilder shapeBuilder(Method[] targets,
                                                         Method[] callees) {
        PEATestUtils.RunBuilder builder = PEATestUtils.shapeRun(WRAPPER, targets);
        for (Method callee : callees) {
            builder.dontinline(callee);
        }
        return builder;
    }

    private static void assertShape(PEATestUtils.RunResult run, Method target,
                                    Method escapeCall, int sourceEnterCount,
                                    int replayEnterCount, boolean callEscape)
            throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        report.assertFinalTransformIdle();
        PEATestUtils.IRBody input = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();
        List<Integer> allocationBCIs = input.allocationBCIs();

        Asserts.assertEquals(allocationBCIs.size(), 3,
                target + ": three virtual lock-owner allocations in the PEA input");
        Asserts.assertEquals(new HashSet<>(allocationBCIs).size(), 3,
                target + ": lock owners have distinct source allocation BCIs");
        input.assertLineCount(MONITOR_ENTER, sourceEnterCount);
        Asserts.assertTrue(input.lineCount(MONITOR_EXIT) >= sourceEnterCount,
                target + ": every source monitor region has normal/exceptional cleanup");
        input.assertPresent("landingpad");
        input.assertLineCount("@\"" + PEATestUtils.MethodId.of(escapeCall)
                .llvmFunctionName() + "\"", 1);

        for (PEATestUtils.PEARound round : report.rounds()) {
            Asserts.assertEquals(round.effectCount("Materialize"), 3L,
                    target + ": every owner materializes once in round "
                            + round.iteration());
            for (int object = 0; object < 3; object++) {
                Asserts.assertEquals(round.effectCount(
                                "Materialize", "[VO=" + object + "]"), 1L,
                        target + ": VO " + object + " materializes once in round "
                                + round.iteration());
            }
            assertPhysicalReplay(round, target, replayEnterCount);
        }

        Asserts.assertEquals(after.allocationBCIs(), allocationBCIs,
                target + ": materialization reuses exactly the three source OrigAllocs");
        after.assertLineCount(MONITOR_ENTER,
                replayEnterCount + (callEscape ? 0 : 1));
        PEATestUtils.IRBlock replayBlock = after.blockContaining(MONITOR_ENTER, 0);
        Asserts.assertEquals(replayBlock.occurrenceCount(INT_STORE), 3,
                target + ": initial scalar fields replay in the escape block");
        Asserts.assertEquals(replayBlock.occurrenceCount(REF_STORE), 5,
                target + ": cycle/shared edges replay in the escape block");
        replayBlock.assertBefore(INT_STORE, 2, MONITOR_ENTER, 0);
        replayBlock.assertBefore(REF_STORE, 4, MONITOR_ENTER, 0);

        String escapeName = "@\"" + PEATestUtils.MethodId.of(escapeCall)
                .llvmFunctionName() + "\"";
        if (callEscape) {
            after.assertBefore(MONITOR_ENTER, replayEnterCount - 1, escapeName, 0);
        } else {
            after.assertBefore(MONITOR_ENTER, replayEnterCount - 1,
                    MONITOR_ENTER, replayEnterCount);
            after.assertBefore(MONITOR_ENTER, replayEnterCount, escapeName, 0);
        }
    }

    private static void assertPhysicalReplay(PEATestUtils.PEARound round,
                                             Method target,
                                             int expectedPhysicalEnters) {
        Asserts.assertEquals(round.lockReplayPhysicalGroups().size(), 1,
                target + ": one physical lock-replay batch in round "
                        + round.iteration());
        List<PEATestUtils.PEALockReplay> rows = round.lockReplayPhysicalGroups()
                .values().iterator().next();
        Map<Integer, PEATestUtils.PEALockReplay> byOrdinal = new LinkedHashMap<>();
        for (PEATestUtils.PEALockReplay row : rows) {
            PEATestUtils.PEALockReplay physical = byOrdinal.putIfAbsent(
                    row.ordinal(), row);
            if (physical != null) {
                Asserts.assertEquals(row.receiverVO(), physical.receiverVO(),
                        target + ": logical aliases share the physical receiver");
                Asserts.assertEquals(row.depth(), physical.depth(),
                        target + ": logical aliases share the physical depth");
            }
        }
        Asserts.assertEquals(byOrdinal.size(), expectedPhysicalEnters,
                target + ": logical aliases do not count as additional physical enters");
        Asserts.assertEquals(new ArrayList<>(byOrdinal.keySet()), List.of(0, 1, 2, 3),
                target + ": physical replay ordinals are contiguous");
        Asserts.assertEquals(byOrdinal.values().stream()
                        .map(PEATestUtils.PEALockReplay::receiverVO).toList(),
                List.of(0, 1, 0, 2),
                target + ": reentrant/interleaved owners retain source lock order");
        int previousDepth = -1;
        for (PEATestUtils.PEALockReplay physical : byOrdinal.values()) {
            Asserts.assertTrue(previousDepth < physical.depth(),
                    target + ": physical lock depth strictly increases");
            previousDepth = physical.depth();
        }
    }

    public static class TestWrapper {
        private static final Object REAL_LOCK = new Object();

        public static class Node {
            int value;
            Node next;
            Node shared;

            Node(int value) {
                this.value = value;
            }
        }

        public static class MarkerException extends RuntimeException {
            private static final long serialVersionUID = 1L;
        }

        private static Node escaped;
        private static Node verifiedA;
        private static Node verifiedB;
        private static Node verifiedC;
        private static int verifyCount;

        public static void main(String[] args) throws Exception {
            new Node(0);
            PEATestUtils.compileConfiguredTargetsAtLevel4();

            long digest = 0x6A09E667F3BCC909L;
            for (int seed : new int[] {3, 101}) {
                for (boolean doThrow : new boolean[] {false, true}) {
                    resetObservations();
                    long realResult = invokeReal(doThrow, seed);
                    assertResult(realResult, doThrow, seed, "real monitor cascade");
                    assertVerifiedGraph(seed, "real monitor cascade");
                    digest = mix(digest, realResult);
                    digest = mix(digest, verifyCount);

                    resetObservations();
                    long escapeResult = invokeEscape(doThrow, seed);
                    assertResult(escapeResult, doThrow, seed, "callee escape cascade");
                    assertVerifiedGraph(seed, "callee escape cascade");
                    Asserts.assertSame(escaped, verifiedC,
                            "escaping callee publishes the original c identity");
                    Asserts.assertSame(escaped.next, verifiedA,
                            "published c retains its a edge");
                    Asserts.assertSame(escaped.shared, verifiedB,
                            "published c retains its shared b edge");
                    digest = mix(digest, escapeResult);
                    digest = mix(digest, System.identityHashCode(escaped)
                            == System.identityHashCode(verifiedC) ? 1 : 0);
                }
            }
            System.out.println("PEA-RESULT:" + Long.toUnsignedString(digest, 16));
        }

        public static long realEnterCascade(boolean doThrow, int seed) {
            Node a = new Node(seed);
            Node b = new Node(seed + 10);
            Node c = new Node(seed + 20);
            a.next = b;
            b.next = a;
            c.next = a;
            a.shared = b;
            c.shared = b;
            synchronized (a) {
                synchronized (b) {
                    synchronized (b.next) {
                        synchronized (c) {
                            synchronized (REAL_LOCK) {
                                publishAfterRealAndMaybeThrow(c, doThrow);
                            }
                        }
                    }
                }
            }
            return encode(a.value, b.value, c.value, 0);
        }

        public static long calleeEscapeCascade(boolean doThrow, int seed) {
            Node a = new Node(seed);
            Node b = new Node(seed + 10);
            Node c = new Node(seed + 20);
            a.next = b;
            b.next = a;
            c.next = a;
            a.shared = b;
            c.shared = b;
            synchronized (a) {
                synchronized (b) {
                    synchronized (b.next) {
                        synchronized (c) {
                            escapeAndMaybeThrow(c, doThrow);
                        }
                    }
                }
            }
            return encode(a.value, b.value, c.value, 0);
        }

        public static void escapeAndMaybeThrow(Node c, boolean doThrow) {
            publishMutateAndMaybeThrow(c, doThrow);
        }

        public static void publishAfterRealAndMaybeThrow(Node c, boolean doThrow) {
            publishMutateAndMaybeThrow(c, doThrow);
        }

        public static void maybeThrow(boolean doThrow) {
            if (doThrow) {
                throw new MarkerException();
            }
        }

        public static void verifyAndReacquire(Node a, Node b, Node c, Object realLock) {
            Asserts.assertFalse(Thread.holdsLock(a),
                    "a lock must be released before post-exit verification");
            Asserts.assertFalse(Thread.holdsLock(b),
                    "b lock must be released before post-exit verification");
            Asserts.assertFalse(Thread.holdsLock(c),
                    "c lock must be released before post-exit verification");
            Asserts.assertFalse(Thread.holdsLock(realLock),
                    "real lock must be released before post-exit verification");
            synchronized (a) {
                synchronized (b) {
                    synchronized (b.next) {
                        synchronized (c) {
                            synchronized (realLock) {
                                Asserts.assertSame(a.next, b, "a-to-b cycle edge");
                                Asserts.assertSame(b.next, a, "b-to-a cycle edge");
                                Asserts.assertSame(c.next, a, "c-to-a edge");
                                Asserts.assertSame(a.shared, b, "a shared b edge");
                                Asserts.assertSame(c.shared, b, "c shared b edge");
                            }
                        }
                    }
                }
            }
            verifiedA = a;
            verifiedB = b;
            verifiedC = c;
            verifyCount++;
        }

        private static void mutate(Node a, Node b, Node c) {
            a.value += 1;
            b.value += 2;
            c.value += 3;
        }

        private static void mutateFinally(Node a, Node b, Node c) {
            a.value += 100;
            b.value += 200;
            c.value += 300;
        }

        private static void publishMutateAndMaybeThrow(Node c, boolean doThrow) {
            escaped = c;
            try {
                mutate(c.next, c.shared, c);
                maybeThrow(doThrow);
            } finally {
                mutateFinally(c.next, c.shared, c);
            }
        }

        private static long encode(int a, int b, int c, int caught) {
            return ((long) a << 48) ^ ((long) b << 32) ^ ((long) c << 16) ^ caught;
        }

        private static void assertResult(long actual, boolean doThrow, int seed,
                                         String context) {
            int caught = doThrow ? 1 : 0;
            Asserts.assertEquals(actual,
                    encode(seed + 101, seed + 212, seed + 323, caught),
                    context + " exact values and catch path");
        }

        private static void assertVerifiedGraph(int seed, String context) {
            Asserts.assertEquals(verifyCount, 1,
                    context + ": post-exit locks were reacquired once");
            Asserts.assertNotNull(verifiedA, context + ": verified a");
            Asserts.assertNotNull(verifiedB, context + ": verified b");
            Asserts.assertNotNull(verifiedC, context + ": verified c");
            Asserts.assertSame(verifiedA.next, verifiedB,
                    context + ": a-to-b identity after return");
            Asserts.assertSame(verifiedB.next, verifiedA,
                    context + ": b-to-a identity after return");
            Asserts.assertSame(verifiedC.next.next, verifiedB,
                    context + ": cycle and shared paths reach the same b identity");
            Asserts.assertEquals(verifiedA.value, seed + 101,
                    context + ": a value after normal/throw/finally");
            Asserts.assertEquals(verifiedB.value, seed + 212,
                    context + ": b value after normal/throw/finally");
            Asserts.assertEquals(verifiedC.value, seed + 323,
                    context + ": c value after normal/throw/finally");
        }

        private static long invokeReal(boolean doThrow, int seed) {
            return invoke(doThrow, seed, true);
        }

        private static long invokeEscape(boolean doThrow, int seed) {
            return invoke(doThrow, seed, false);
        }

        private static long invoke(boolean doThrow, int seed, boolean realCase) {
            long result = 0;
            boolean caught = false;
            try {
                result = realCase
                        ? realEnterCascade(doThrow, seed)
                        : calleeEscapeCascade(doThrow, seed);
            } catch (MarkerException expected) {
                caught = true;
            }
            Asserts.assertEquals(caught, doThrow,
                    "throw selector matches the observed catch path");
            Asserts.assertNotNull(escaped, "callee published c before normal/throw exit");
            Node c = escaped;
            Node a = c.next;
            Node b = c.shared;
            if (caught) {
                result = encode(a.value, b.value, c.value, 1);
            }
            verifyAndReacquire(a, b, c, REAL_LOCK);
            return result;
        }

        private static void resetObservations() {
            escaped = null;
            verifiedA = null;
            verifiedB = null;
            verifiedC = null;
            verifyCount = 0;
        }

        private static long mix(long accumulator, long value) {
            return Long.rotateLeft(accumulator ^ value, 11) * 0x9E3779B97F4A7C15L;
        }
    }
}
