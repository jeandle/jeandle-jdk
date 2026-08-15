/*
 * Copyright (c) 2026, the Jeandle-JDK Authors. All Rights Reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only,
 * as published by the Free Software Foundation.
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
 * @summary PEA outer iteration reaches an exact fixpoint after replay-enabled
 *          canonicalization and does not duplicate balanced lock replay
 * @library /test/lib /
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      -XX:-UseCompressedOops -XX:-UseCompressedClassPointers
 *      compiler.jeandle.pea.TestPEAOuterIteration
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jdk.test.lib.Asserts;

public class TestPEAOuterIteration {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestPEAOuterIteration$TestWrapper";
    private static final String MONITOR_ENTER = "@jeandle.monitorenter";
    private static final String MONITOR_EXIT = "@jeandle.monitorexit";
    private static final String FIELD_PHI = "pea.field.phi";
    private static final String CASE_C_FIELD_PHI = "pea.casec.field.phi";
    private static final String MATERIALIZED_PHI = "pea.materialized.phi";
    private static final Pattern INVOKE_DESTINATIONS = Pattern.compile(
            "^to label (%\\S+) unwind label (%\\S+)$");

    public static void main(String[] args) throws Exception {
        Method replayFold = TestWrapper.class.getMethod(
                "replayEnablesFolding", int.class);
        Method lockReplay = TestWrapper.class.getMethod(
                "balancedLockReplay", int.class);
        Method alreadyIdle = TestWrapper.class.getMethod(
                "alreadyIdle", int.class);
        Method loopRollback = TestWrapper.class.getMethod(
                "loopRollback", int.class, boolean.class, int.class);
        Method escape = TestWrapper.class.getMethod(
                "escape", TestWrapper.Box.class);
        Method[] targets = {replayFold, lockReplay, loopRollback, alreadyIdle};

        PEATestUtils.assertStructuralParserContracts();
        PEATestUtils.behaviorRun(WRAPPER, targets)
                .dontinline(escape)
                .runPEAOnOffEquivalent();

        ShapeRun cap1 = runShape(1, targets, escape);
        ShapeRun cap2 = runShape(2, targets, escape);
        ShapeRun cap4 = runShape(4, targets, escape);
        ShapeRun cap16 = runShape(16, targets, escape);

        assertReplayEnabledFolding(cap1.report(replayFold),
                cap2.report(replayFold), cap4.report(replayFold),
                cap16.report(replayFold), replayFold, escape);
        assertBalancedLockReplay(cap1.report(lockReplay),
                cap2.report(lockReplay), cap4.report(lockReplay),
                cap16.report(lockReplay), lockReplay, escape);
        assertLoopRollback(cap1.report(loopRollback), cap2.report(loopRollback),
                cap4.report(loopRollback), cap16.report(loopRollback), loopRollback);
        assertAlreadyIdle(cap1.report(alreadyIdle), cap2.report(alreadyIdle),
                cap4.report(alreadyIdle), cap16.report(alreadyIdle), alreadyIdle);

        for (Method target : targets) {
            cap16.report(target).finalAfter().assertCrossProcessExactEquals(
                    cap4.report(target).finalAfter(),
                    target + ": cap 4 and cap 16 have exact stable final IR");
        }
    }

    private static ShapeRun runShape(int cap, Method[] targets, Method escape)
            throws Exception {
        try (PEATestUtils.RunResult run = PEATestUtils.shapeRun(WRAPPER, targets)
                .peaIterations(cap)
                .dontinline(escape)
                .run()) {
            PEATestUtils.PEAReport[] reports =
                    new PEATestUtils.PEAReport[targets.length];
            for (int i = 0; i < targets.length; i++) {
                Method target = targets[i];
                PEATestUtils.PEAReport report = run.report(target);
                reports[i] = report;
                Asserts.assertTrue(report.roundCount() >= 1
                                && report.roundCount() <= cap,
                        target + ": exact rounds respect cap " + cap);
                for (PEATestUtils.PEARound round : report.rounds()) {
                    PEATestUtils.assertStructuralSoundness(round.before(),
                            target + ": cap " + cap + " round "
                                    + round.iteration() + " before");
                    PEATestUtils.assertStructuralSoundness(round.after(),
                            target + ": cap " + cap + " round "
                                    + round.iteration() + " after");
                }
                PEATestUtils.assertStructuralSoundness(report.finalAfter(),
                        target + ": cap " + cap + " final");
                if (cap >= 4) {
                    report.assertStoppedAtFixpoint();
                    report.assertFinalTransformIdle();
                }
            }
            return new ShapeRun(targets, reports);
        }
    }

    private static void assertReplayEnabledFolding(
            PEATestUtils.PEAReport cap1, PEATestUtils.PEAReport cap2,
            PEATestUtils.PEAReport cap4, PEATestUtils.PEAReport cap16,
            Method target, Method escape) {
        assertRoundCounts(cap1, cap2, cap4, cap16, target, 1, 2, 3, 3);
        cap1.assertStoppedAtIterationCap();
        cap2.assertStoppedAtIterationCap();

        PEATestUtils.IRBody input = cap4.round0Before();
        List<PEATestUtils.AllocationSite> allocations = input.allocations();
        Asserts.assertEquals(allocations.size(), 2,
                target + ": guard and candidate source allocations");
        cap1.finalAfter().assertRetainsExactlyOriginalAllocations(
                input, allocations.get(1).key());
        cap2.finalAfter().assertRetainsExactlyOriginalAllocations(input);
        cap4.finalAfter().assertRetainsExactlyOriginalAllocations(input);

        String escapeName = PEATestUtils.MethodId.of(escape).llvmFunctionName();
        cap1.round(0).after().assertPresent("br i1 true");
        cap1.round(0).after().assertAbsent("load atomic i32");
        cap1.round(0).after().assertLineCount("@\"" + escapeName + "\"", 1);
        Asserts.assertEquals(cap1.round(0).effectCount("Materialize", "[VO=1]"),
                2L, target + ": candidate replay covers escape and surviving arms");
        cap2.round(1).before().assertPresent("br i1 true");
        cap2.round(1).before().assertRetainsExactlyOriginalAllocations(
                input, allocations.get(1).key());
        cap1.finalAfter().assertCrossProcessExactEquals(
                cap2.round(1).before(),
                target + ": finalAfter is the complete post-canonicalization round");
        Asserts.assertFalse(cap2.round(1).transformIdle(),
                target + ": round 2 removes the newly non-escaping candidate");
        Asserts.assertTrue(cap4.round(2).transformIdle(),
                target + ": unchanged complete round reaches the fixpoint");
        cap2.round(1).after().assertAbsent("@\"" + escapeName + "\"");
        cap4.finalAfter().assertLineCount("store atomic i32", 0);
        cap4.finalAfter().assertLineCount("load atomic i32", 0);
        cap4.finalAfter().assertLineCount("br i1", 0);
        Asserts.assertEquals(ShapeSummary.of(cap2.finalAfter()),
                ShapeSummary.of(cap4.finalAfter()),
                target + ": productive round 2 already has stable IR shape");
    }

    private static void assertBalancedLockReplay(
            PEATestUtils.PEAReport cap1, PEATestUtils.PEAReport cap2,
            PEATestUtils.PEAReport cap4, PEATestUtils.PEAReport cap16,
            Method target, Method escape) {
        assertRoundCounts(cap1, cap2, cap4, cap16, target, 1, 2, 2, 2);
        cap1.assertStoppedAtIterationCap();
        cap2.assertStoppedAtFixpoint();

        PEATestUtils.IRBody input = cap4.round0Before();
        List<PEATestUtils.AllocationSite> allocations = input.allocations();
        Asserts.assertEquals(allocations.size(), 1,
                target + ": one source lock-owner allocation");
        for (PEATestUtils.PEAReport report :
                List.of(cap1, cap2, cap4, cap16)) {
            report.finalAfter().assertRetainsExactlyOriginalAllocations(
                    input, allocations.get(0).key());
        }
        Asserts.assertFalse(cap1.round(0).transformIdle(),
                target + ": first round emits balanced replay");
        Asserts.assertTrue(cap2.round(1).transformIdle(),
                target + ": immediate repeated analysis is physically idle");
        PEATestUtils.IRBody stable = cap4.finalAfter();
        stable.assertLineCount(MONITOR_ENTER, 1);
        stable.assertLineCount(MONITOR_EXIT, 2);
        String escapeName = PEATestUtils.MethodId.of(escape).llvmFunctionName();
        PEATestUtils.IRBlock callBlock =
                stable.blockContaining("@\"" + escapeName + "\"", 0);
        callBlock.assertOccurrenceCount(MONITOR_ENTER, 1);
        callBlock.assertOccurrenceCount(MONITOR_EXIT, 0);
        callBlock.assertBefore(MONITOR_ENTER, 0, "@\"" + escapeName + "\"", 0);

        InvokeDestinations destinations =
                uniqueInvokeDestinations(stable, escapeName);
        PEATestUtils.IRBlock normal =
                stable.blockByLabel(destinations.normal());
        normal.assertOccurrenceCount(MONITOR_EXIT, 1);
        normal.assertBefore("store atomic", 0, MONITOR_EXIT, 0);
        normal.assertBefore("load atomic", 0, MONITOR_EXIT, 0);
        PEATestUtils.IRBlock exceptional =
                stable.blockByLabel(destinations.exceptional());
        exceptional.assertOccurrenceCount(MONITOR_EXIT, 1);
        exceptional.assertBefore("landingpad", 0, MONITOR_EXIT, 0);
        Asserts.assertEquals(cap1.finalAfter().lineCount(MONITOR_ENTER),
                stable.lineCount(MONITOR_ENTER),
                target + ": idle rounds do not repeat lock replay");
        Asserts.assertEquals(cap1.finalAfter().lineCount(MONITOR_EXIT),
                stable.lineCount(MONITOR_EXIT),
                target + ": idle rounds do not repeat balanced lock exits");
        stable.assertLineCount(MATERIALIZED_PHI, 0);
        stable.assertLineCount(FIELD_PHI, 0);
        stable.assertLineCount(CASE_C_FIELD_PHI, 0);
        Asserts.assertEquals(cap1.round(0).lockReplayPhysicalGroups().size(), 1,
                target + ": first round has one physical lock replay batch");
        Asserts.assertEquals(cap2.round(1).lockReplayPhysicalGroups().size(), 1,
                target + ": idle analysis repeats one replay plan");
    }

    private static InvokeDestinations uniqueInvokeDestinations(
            PEATestUtils.IRBody body, String exactCallee) {
        List<String> lines = body.lines();
        InvokeDestinations result = null;
        String calleeToken = "@\"" + exactCallee + "\"";
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!line.contains("invoke ") || !line.contains(calleeToken)) {
                continue;
            }
            if (result != null || i + 1 >= lines.size()) {
                throw new AssertionError(body.methodId()
                        + ": expected one complete invoke of " + calleeToken);
            }
            Matcher destinations = INVOKE_DESTINATIONS.matcher(lines.get(i + 1));
            if (!destinations.matches()) {
                throw new AssertionError(body.methodId()
                        + ": malformed invoke destinations after " + line);
            }
            result = new InvokeDestinations(
                    destinations.group(1), destinations.group(2));
        }
        if (result == null) {
            throw new AssertionError(body.methodId()
                    + ": missing invoke of " + calleeToken);
        }
        return result;
    }

    private record InvokeDestinations(String normal, String exceptional) {}

    private static void assertAlreadyIdle(
            PEATestUtils.PEAReport cap1, PEATestUtils.PEAReport cap2,
            PEATestUtils.PEAReport cap4, PEATestUtils.PEAReport cap16,
            Method target) {
        assertRoundCounts(cap1, cap2, cap4, cap16, target, 1, 1, 1, 1);
        cap1.assertStoppedAtFixpoint();
        cap2.assertStoppedAtFixpoint();
        for (PEATestUtils.PEAReport report :
                List.of(cap1, cap2, cap4, cap16)) {
            Asserts.assertEquals(report.round0Before().allocations().size(), 0,
                    target + ": idle method starts allocation-free");
            Asserts.assertTrue(report.rounds().stream()
                            .allMatch(PEATestUtils.PEARound::transformIdle),
                    target + ": every configured probe is transform-idle");
            Asserts.assertTrue(report.effects("Materialize").isEmpty(),
                    target + ": idle method has no replay effect");
            report.finalAfter().assertRetainsExactlyOriginalAllocations(
                    report.round0Before());
        }
        Asserts.assertEquals(ShapeSummary.of(cap1.finalAfter()),
                ShapeSummary.of(cap16.finalAfter()),
                target + ": an already-idle method is a strict fixpoint");
    }

    private static void assertLoopRollback(
            PEATestUtils.PEAReport cap1, PEATestUtils.PEAReport cap2,
            PEATestUtils.PEAReport cap4, PEATestUtils.PEAReport cap16,
            Method target) {
        assertRoundCounts(cap1, cap2, cap4, cap16, target, 1, 2, 2, 2);
        PEATestUtils.IRBody input = cap4.round0Before();
        List<PEATestUtils.AllocationSite> allocations = input.allocations();
        Asserts.assertEquals(allocations.size(), 1,
                target + ": one loop-carried source allocation");
        for (PEATestUtils.PEAReport report :
                List.of(cap1, cap2, cap4, cap16)) {
            report.finalAfter().assertRetainsExactlyOriginalAllocations(
                    input, allocations.get(0).key());
        }
        cap1.assertStoppedAtIterationCap();
        cap2.assertStoppedAtFixpoint();
        Asserts.assertFalse(cap1.round(0).transformIdle(),
                target + ": first round plans loop replay");
        Asserts.assertTrue(cap2.round(1).transformIdle(),
                target + ": unchanged complete repeated round reaches the fixpoint");
        for (PEATestUtils.PEAReport report :
                List.of(cap1, cap2, cap4, cap16)) {
            for (PEATestUtils.PEARound round : report.rounds()) {
                Asserts.assertEquals(
                        round.effectCount("Materialize", "[VO=0]"), 2L,
                        target + ": round " + round.iteration()
                                + " has exactly two loop replay placements");
                Asserts.assertEquals(
                        round.effectCount("Materialize", "[VO=0]", "preheader"),
                        1L, target + ": round " + round.iteration()
                                + " has one loop-entry replay placement");
            }
        }
        Asserts.assertEquals(cap1.round(0).effectCount(
                        "Materialize", "[VO=0]", "bci_10_null_check_pass"),
                1L, target + ": first round has one in-loop replay placement");
        Asserts.assertEquals(cap2.round(1).effectCount(
                        "Materialize", "[VO=0]", "pea.replay"),
                1L, target + ": repeated analysis finds one replay block");
        Asserts.assertEquals(
                cap1.round(0).effectCount("EliminateStore", "[VO=0]"), 2L,
                target + ": first round removes the two source field stores");
        Asserts.assertEquals(
                cap2.round(1).effectCount("EliminateStore", "[VO=0]"), 4L,
                target + ": repeated analysis sees four replay field stores");
        Asserts.assertEquals(ShapeSummary.of(cap1.finalAfter()),
                ShapeSummary.of(cap4.finalAfter()),
                target + ": later rounds do not duplicate loop replay");
        PEATestUtils.IRBody stable = cap4.finalAfter();
        stable.assertLineCount("store atomic", 6);
        stable.assertLineCount("load atomic", 5);
        stable.assertLineCount("br i1", 5);
        stable.assertLineCount(MATERIALIZED_PHI, 0);
        stable.assertLineCount(FIELD_PHI, 0);
        stable.assertLineCount(CASE_C_FIELD_PHI, 0);
        stable.assertLineCount(MONITOR_ENTER, 0);
        stable.assertLineCount(MONITOR_EXIT, 0);
    }

    private static void assertRoundCounts(
            PEATestUtils.PEAReport cap1, PEATestUtils.PEAReport cap2,
            PEATestUtils.PEAReport cap4, PEATestUtils.PEAReport cap16,
            Method target, int one, int two, int four, int sixteen) {
        Asserts.assertEquals(cap1.roundCount(), one,
                target + ": exact cap-1 round count");
        Asserts.assertEquals(cap2.roundCount(), two,
                target + ": exact cap-2 round count");
        Asserts.assertEquals(cap4.roundCount(), four,
                target + ": exact cap-4 round count");
        Asserts.assertEquals(cap16.roundCount(), sixteen,
                target + ": exact cap-16 round count");
    }

    private static final class ShapeRun {
        private final Method[] targets;
        private final PEATestUtils.PEAReport[] reports;

        ShapeRun(Method[] targets, PEATestUtils.PEAReport[] reports) {
            this.targets = targets.clone();
            this.reports = reports.clone();
        }

        PEATestUtils.PEAReport report(Method target) {
            return reports[indexOf(target)];
        }

        private int indexOf(Method target) {
            int index = Arrays.asList(targets).indexOf(target);
            if (index < 0) {
                throw new IllegalArgumentException("Unknown target " + target);
            }
            return index;
        }
    }

    private record ShapeSummary(
            List<PEATestUtils.AllocationKey> allocations, int stores, int loads,
            int branches, int materializedPhis, int fieldPhis, int caseCPhis,
            int monitorEnters, int monitorExits) {
        static ShapeSummary of(PEATestUtils.IRBody body) {
            return new ShapeSummary(body.allocations().stream()
                    .map(PEATestUtils.AllocationSite::key).toList(),
                    body.lineCount("store atomic"), body.lineCount("load atomic"),
                    body.lineCount("br i1"), body.occurrenceCount(MATERIALIZED_PHI),
                    body.occurrenceCount(FIELD_PHI),
                    body.occurrenceCount(CASE_C_FIELD_PHI),
                    body.occurrenceCount(MONITOR_ENTER),
                    body.occurrenceCount(MONITOR_EXIT));
        }
    }

    public static class TestWrapper {
        public static class Box {
            int value;
            int other;
        }

        private static final String EXPECTED = "18:24:1:3013:3014:26";
        static Box escaped;
        static int escapeCount;
        static int loopIdentity;

        public static void main(String[] args) throws Exception {
            new Box();
            PEATestUtils.compileConfiguredTargetsAtLevel4();

            reset();
            int folded = replayEnablesFolding(17);
            Asserts.assertEquals(folded, 18, "replay-enabled folding result");
            Asserts.assertNull(escaped, "dead candidate escape is removed");
            Asserts.assertEquals(escapeCount, 0, "dead escape arm is not taken");

            reset();
            int locked = balancedLockReplay(11);
            Asserts.assertEquals(locked, 24, "balanced lock replay result");
            Asserts.assertNotNull(escaped, "locked object escapes");
            Asserts.assertEquals(escaped.value, 11, "locked escaped value");
            Asserts.assertEquals(escaped.other, 12, "post-escape field update");
            Asserts.assertEquals(escapeCount, 1, "one locked escape");
            Asserts.assertFalse(Thread.holdsLock(escaped),
                    "escaped monitor is released on the caller thread");
            assertMonitorReacquirable(escaped);

            reset();
            int zeroTrip = loopRollback(0, true, 2);
            Asserts.assertEquals(zeroTrip, 1, "zero-trip loop result");
            Asserts.assertNull(escaped, "zero-trip loop does not escape");
            Asserts.assertEquals(escapeCount, 0, "zero-trip loop escape count");
            Asserts.assertEquals(loopIdentity, 0, "zero-trip loop identity");

            reset();
            int noEscape = loopRollback(3, false, 2);
            Asserts.assertEquals(noEscape, 3013, "non-escaping loop result");
            Asserts.assertNull(escaped, "multi-trip loop remains virtual");
            Asserts.assertEquals(escapeCount, 0, "non-escaping loop escape count");
            Asserts.assertEquals(loopIdentity, 0, "non-escaping loop identity");

            reset();
            int loopEscape = loopRollback(3, true, 2);
            Asserts.assertEquals(loopEscape, 3014, "escaping loop result");
            Asserts.assertNotNull(escaped, "loop-carried object escapes");
            Asserts.assertEquals(escaped.value, 5, "loop-carried escaped value");
            Asserts.assertEquals(escaped.other, 1, "loop-carried replayed field");
            Asserts.assertEquals(escapeCount, 2, "two loop escape executions");
            Asserts.assertEquals(loopIdentity, 1, "escaping loop identity");

            int idle = alreadyIdle(9);
            Asserts.assertEquals(idle, 26, "already-idle result");
            String payload = folded + ":" + locked + ":" + zeroTrip + ":"
                    + noEscape + ":" + loopEscape + ":" + idle;
            Asserts.assertEquals(payload, EXPECTED, "exact outer-iteration payload");
            System.out.println("PEA-RESULT:" + payload);
        }

        public static int replayEnablesFolding(int value) {
            Box guard = new Box();
            guard.value = 0;
            Box candidate = new Box();
            candidate.value = value;
            candidate.other = 1;
            if (guard.value != 0) {
                escape(candidate);
            }
            return candidate.value + candidate.other;
        }

        public static int balancedLockReplay(int value) {
            Box box = new Box();
            box.value = value;
            int observed;
            synchronized (box) {
                escape(box);
                box.other = value + 1;
                observed = box.value + box.other;
            }
            int identity = escaped == box ? 1 : 0;
            return observed + identity;
        }

        public static int alreadyIdle(int value) {
            return value + 17;
        }

        public static int loopRollback(int trips, boolean escapeOnEven, int seed) {
            Box box = new Box();
            box.value = seed;
            box.other = 1;
            int sum = 0;
            for (int i = 0; i < trips; i++) {
                box.value += box.other;
                if (escapeOnEven && (i & 1) == 0) {
                    escape(box);
                }
                sum = sum * 31 + box.value;
            }
            int identity = escapeOnEven && trips > 0 && escaped == box ? 1 : 0;
            loopIdentity = identity;
            return sum + box.other + identity;
        }

        public static void escape(Box box) {
            escaped = box;
            escapeCount++;
        }

        private static void reset() {
            escaped = null;
            escapeCount = 0;
            loopIdentity = 0;
        }

        private static void assertMonitorReacquirable(Object monitor)
                throws InterruptedException {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread contender = new Thread(() -> {
                try {
                    synchronized (monitor) {
                        if (!Thread.holdsLock(monitor)) {
                            throw new AssertionError(
                                    "contender does not own reacquired monitor");
                        }
                    }
                } catch (Throwable throwable) {
                    failure.set(throwable);
                }
            }, "pea-monitor-reacquire");
            contender.setDaemon(true);
            contender.start();
            contender.join(10_000);
            if (contender.isAlive()) {
                throw new AssertionError(
                        "contender could not reacquire escaped monitor");
            }
            if (failure.get() != null) {
                throw new AssertionError(
                        "contender failed while reacquiring monitor", failure.get());
            }
        }
    }
}
