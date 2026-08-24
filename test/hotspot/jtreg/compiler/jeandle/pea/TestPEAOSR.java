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
 * @summary PEA normal-entry and OSR loop state, escape, deopt, exception,
 *          and monitor integration
 * @library /test/lib /
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.pea.TestPEAOSR
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import jdk.test.lib.Asserts;
import jdk.test.whitebox.WhiteBox;

public class TestPEAOSR {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestPEAOSR$TestWrapper";
    private static final String MODE_PROPERTY = "test.pea.osr.mode";
    private static final String ACTIVE_DEOPT_PROPERTY =
            "test.pea.osr.activeDeopt";
    private static final String NORMAL_MODE = "normal";
    private static final String OSR_MODE = "osr";

    public static void main(String[] args) throws Exception {
        PEATestUtils.assertPhiParserContracts();

        Method entryReal = TestWrapper.class.getMethod(
                "entryReal", int.class, TestWrapper.Box.class);
        Method loopLocal = TestWrapper.class.getMethod(
                "loopLocal", int.class, int.class);
        Method carried = TestWrapper.class.getMethod(
                "carriedReplacement", int.class, int.class, int.class);
        Method escape = TestWrapper.class.getMethod(
                "conditionalEscape", int.class, int.class, boolean.class);
        Method activeDeopt = TestWrapper.class.getMethod(
                "activeDeopt", int.class, int.class, boolean.class);
        Method exception = TestWrapper.class.getMethod(
                "caughtException", int.class, int.class, boolean.class);
        Method lock = TestWrapper.class.getMethod(
                "balancedLock", int.class, int.class);
        Method publish = TestWrapper.class.getDeclaredMethod(
                "publish", TestWrapper.Box.class);
        Method thrower = TestWrapper.class.getDeclaredMethod(
                "thrower", boolean.class);
        Method lockPoll = TestWrapper.class.getDeclaredMethod("lockPoll");
        Method[] methods = {
                entryReal, loopLocal, carried, escape,
                activeDeopt, exception, lock
        };
        PEATestUtils.MethodId[] normal = methodIds(methods, false);
        PEATestUtils.MethodId[] osr = methodIds(methods, true);

        behaviorBuilder(normal, publish, thrower, lockPoll, NORMAL_MODE)
                .runPEAOnOffEquivalent();
        behaviorBuilder(osr, publish, thrower, lockPoll, OSR_MODE)
                .runPEAOnOffEquivalent();

        try (PEATestUtils.RunResult run =
                shapeBuilder(normal, publish, thrower, lockPoll, NORMAL_MODE)
                .peaIterations(4)
                .run()) {
            assertEntryReal(run, normal[0]);
            assertLoopLocal(run, normal[1]);
            assertNormalCarried(run, normal[2]);
            assertConditionalEscape(run, normal[3], publish);
            assertVirtualAtDeopt(run, normal[4], null);
            assertVirtualAtDeopt(run, normal[5], thrower);
            assertBalancedLock(run, normal[6], lockPoll);
        }

        try (PEATestUtils.RunResult run =
                shapeBuilder(osr, publish, thrower, lockPoll, OSR_MODE)
                .peaIterations(4)
                .run()) {
            assertEntryReal(run, osr[0]);
            assertLoopLocal(run, osr[1]);
            assertOSRCarried(run, osr[2]);
            assertConditionalEscape(run, osr[3], publish);
            assertVirtualAtDeopt(run, osr[4], null);
            assertVirtualAtDeopt(run, osr[5], thrower);
            assertBalancedLock(run, osr[6], lockPoll);
        }
    }

    private static PEATestUtils.RunBuilder behaviorBuilder(
            PEATestUtils.MethodId[] targets, Method publish, Method thrower,
            Method lockPoll, String mode) {
        return PEATestUtils.behaviorRun(WRAPPER, targets)
                .dontinline(publish)
                .dontinline(thrower)
                .dontinline(lockPoll)
                .extraFlags("-D" + MODE_PROPERTY + "=" + mode,
                        "-D" + ACTIVE_DEOPT_PROPERTY + "=true",
                        "-XX:CompileThreshold=1000");
    }

    private static PEATestUtils.RunBuilder shapeBuilder(
            PEATestUtils.MethodId[] targets, Method publish, Method thrower,
            Method lockPoll, String mode) {
        return PEATestUtils.shapeRun(WRAPPER, targets)
                .dontinline(publish)
                .dontinline(thrower)
                .dontinline(lockPoll)
                .extraFlags("-D" + MODE_PROPERTY + "=" + mode,
                        "-D" + ACTIVE_DEOPT_PROPERTY + "=false",
                        "-XX:CompileThreshold=1000");
    }

    private static PEATestUtils.MethodId[] methodIds(Method[] methods, boolean osr) {
        PEATestUtils.MethodId[] result = new PEATestUtils.MethodId[methods.length];
        for (int i = 0; i < methods.length; i++) {
            result[i] = osr
                    ? PEATestUtils.MethodId.osr(methods[i])
                    : PEATestUtils.MethodId.of(methods[i]);
        }
        return result;
    }

    private static void assertEntryReal(
            PEATestUtils.RunResult run, PEATestUtils.MethodId target) throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        Asserts.assertEquals(report.round0Before().peaAllocCount(), 0,
                target + ": incoming real oop must not become an allocation");
        Asserts.assertEquals(run.finalIR(target).loweredAllocCount(), 0,
                target + ": incoming real oop must not create a lowered allocation");
        assertStructural(run, report, target);
    }

    private static void assertLoopLocal(
            PEATestUtils.RunResult run, PEATestUtils.MethodId target) throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();
        assertSourceAllocations(before, target, instanceAt(9));
        Asserts.assertEquals(report.finalAfter().peaAllocCount(), 0,
                target + ": loop-local allocation must be eliminated");
        after.assertRetainsExactlyOriginalAllocations(before);
        Asserts.assertEquals(run.finalIR(target).loweredAllocCount(), 0,
                target + ": loop-local allocation must stay eliminated after lowering");
        Asserts.assertTrue(report.maxNeverEscapes() >= 1,
                target + ": loop-local allocation is NeverEscape");
        assertStructural(run, report, target);
    }

    private static void assertNormalCarried(
            PEATestUtils.RunResult run, PEATestUtils.MethodId target) throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();
        assertSourceAllocations(before, target, instanceAt(0), instanceAt(38));
        before.assertPresent("phi ptr addrspace(1)");
        report.round(0).after().assertPresent("pea.casec.field.phi = phi i32");
        Asserts.assertEquals(after.peaAllocCount(), 0,
                target + ": normal header Case C eliminates both allocations");
        after.assertRetainsExactlyOriginalAllocations(before);
        Asserts.assertEquals(run.finalIR(target).loweredAllocCount(), 0,
                target + ": normal carried allocations stay eliminated after lowering");
        assertStructural(run, report, target);
    }

    private static void assertOSRCarried(
            PEATestUtils.RunResult run, PEATestUtils.MethodId target) throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();
        PEATestUtils.AllocationKey replacement = instanceAt(38);
        assertSourceAllocations(before, target, replacement);
        before.assertPresent("phi ptr addrspace(1)");
        after.assertPresent("phi ptr addrspace(1)");
        Asserts.assertEquals(after.peaAllocCount(), 1,
                target + ": replacement merged with an incoming real oop retains OrigAlloc");
        after.assertRetainsExactlyOriginalAllocations(before, replacement);
        Asserts.assertEquals(run.finalIR(target).loweredAllocCount(), 1,
                target + ": OSR carried replacement remains one lowered OrigAlloc");
        assertStructural(run, report, target);
    }

    private static void assertConditionalEscape(
            PEATestUtils.RunResult run, PEATestUtils.MethodId target, Method publish)
            throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();
        PEATestUtils.AllocationKey source = instanceAt(24);
        assertSourceAllocations(before, target, source);
        Asserts.assertEquals(after.peaAllocCount(), 1,
                target + ": partial escape retains only OrigAlloc");
        after.assertRetainsExactlyOriginalAllocations(before, source);
        Asserts.assertEquals(run.finalIR(target).loweredAllocCount(), 1,
                target + ": conditional escape lowers exactly the retained OrigAlloc");
        Asserts.assertTrue(report.maxPartiallyEscapes() >= 1,
                target + ": conditional publication is PartiallyEscape");
        Asserts.assertTrue(report.effects("Materialize").size() >= 1,
                target + ": publication path plans materialization");
        String callee = PEATestUtils.MethodId.of(publish).llvmFunctionName();
        String callToken = "@\"" + callee + "\"";
        PEATestUtils.IRBlock publication = after.blockContaining(callToken, 0);
        publication.assertOccurrenceCount("store atomic i32", 1);
        publication.assertBefore("store atomic i32", 0, callToken, 0);
        assertStructural(run, report, target);
    }

    private static void assertVirtualAtDeopt(
            PEATestUtils.RunResult run, PEATestUtils.MethodId target, Method exactCall)
            throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();
        assertSourceAllocations(before, target, instanceAt(24));
        Asserts.assertEquals(after.peaAllocCount(), 0,
                target + ": deopt-only virtual state does not force materialization");
        after.assertRetainsExactlyOriginalAllocations(before);
        Asserts.assertEquals(run.finalIR(target).loweredAllocCount(), 0,
                target + ": deopt-only virtual state stays allocation-free after lowering");
        Asserts.assertTrue(report.maxNeverEscapes() >= 1,
                target + ": deopt-only object is NeverEscape");

        boolean foundDescriptor = false;
        if (exactCall != null) {
            String callee = PEATestUtils.MethodId.of(exactCall).llvmFunctionName();
            PEATestUtils.DeoptBundle bundle = after.deoptBundleAtCall(callee, 0);
            assertOneBoxDescriptor(bundle, target);
            foundDescriptor = true;
        } else {
            int polls = after.lineCount("jeandle.safepoint_poll");
            for (int i = 0; i < polls; i++) {
                PEATestUtils.DeoptBundle bundle =
                        after.deoptBundleAtCall("jeandle.safepoint_poll", i);
                if (!bundle.virtualObjects().isEmpty()) {
                    assertOneBoxDescriptor(bundle, target);
                    foundDescriptor = true;
                }
            }
        }
        Asserts.assertTrue(foundDescriptor,
                target + ": a live deopt point must carry a virtual-object descriptor");
        assertStructural(run, report, target);
    }

    private static void assertBalancedLock(
            PEATestUtils.RunResult run, PEATestUtils.MethodId target, Method lockPoll)
            throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();
        assertSourceAllocations(before, target, instanceAt(21));
        before.assertOccurrenceCount("jeandle.monitorenter", 1);
        before.assertOccurrenceCount("jeandle.monitorexit", 2);
        Asserts.assertEquals(after.peaAllocCount(), 0,
                target + ": virtual monitor owner allocation is eliminated");
        after.assertRetainsExactlyOriginalAllocations(before);
        Asserts.assertEquals(run.finalIR(target).loweredAllocCount(), 0,
                target + ": virtual monitor owner stays eliminated after lowering");
        after.assertAbsent("jeandle.monitorenter");
        after.assertAbsent("jeandle.monitorexit");
        String poll = PEATestUtils.MethodId.of(lockPoll).llvmFunctionName();
        PEATestUtils.DeoptBundle bundle = after.deoptBundleAtCall(poll, 0);
        assertOneBoxDescriptor(bundle, target);
        Asserts.assertEquals(bundle.rootScope().monitors().size(), 1,
                target + ": in-lock safepoint has one monitor");
        PEATestUtils.DeoptMonitor monitor = bundle.rootScope().monitors().get(0);
        Asserts.assertTrue(monitor.eliminated(),
                target + ": virtual owner monitor is eliminated");
        Asserts.assertEquals(
                monitor.owner().kind(), PEATestUtils.DeoptValueKind.VO_REF,
                target + ": eliminated monitor owner is a VORef");
        assertStructural(run, report, target);
    }

    private static void assertOneBoxDescriptor(
            PEATestUtils.DeoptBundle bundle, PEATestUtils.MethodId target) {
        bundle.assertVirtualObjectIds(0);
        PEATestUtils.VirtualObjectDescriptor descriptor = bundle.virtualObject(0);
        Asserts.assertEquals(descriptor.kind(), PEATestUtils.DescriptorKind.INSTANCE,
                target + ": virtual Box descriptor kind");
        Asserts.assertEquals(descriptor.fields().size(), 1,
                target + ": virtual Box carries exactly its written x field");
        PEATestUtils.VirtualObjectEntry x = descriptor.fields().values().iterator().next();
        Asserts.assertEquals(x.basicType(), PEATestUtils.DeoptBasicType.INT,
                target + ": virtual Box.x basic type");
        Asserts.assertEquals(x.value().kind(), PEATestUtils.DeoptValueKind.SCALAR,
                target + ": virtual Box.x is a scalar deopt value");
    }

    private static void assertStructural(
            PEATestUtils.RunResult run, PEATestUtils.PEAReport report,
            PEATestUtils.MethodId target) throws Exception {
        report.assertFinalTransformIdle();
        for (PEATestUtils.PEARound round : report.rounds()) {
            PEATestUtils.assertStructuralSoundness(
                    round.before(), target + " round " + round.iteration() + " before");
            PEATestUtils.assertStructuralSoundness(
                    round.after(), target + " round " + round.iteration() + " after");
        }
        PEATestUtils.assertStructuralSoundness(
                run.finalIR(target), target + " final lowered IR");
    }

    private static PEATestUtils.AllocationKey instanceAt(int bci) {
        return new PEATestUtils.AllocationKey(
                PEATestUtils.AllocationKind.INSTANCE, bci);
    }

    private static void assertSourceAllocations(
            PEATestUtils.IRBody body, PEATestUtils.MethodId target,
            PEATestUtils.AllocationKey... expected) {
        List<PEATestUtils.AllocationKey> actual = body.allocations().stream()
                .map(PEATestUtils.AllocationSite::key)
                .toList();
        Asserts.assertEquals(actual, List.of(expected),
                target + ": exact source allocation sites");
    }

    public static class TestWrapper {
        private static final int HOT_TRIPS = 100_000;
        private static final long WAIT_NANOS = TimeUnit.SECONDS.toNanos(30);
        private static final int IDENTITY_BIT = 1_000_000;

        private static volatile int phase;
        private static volatile Box published;
        private static int lockPolls;

        public static class Box {
            int x;
        }

        public static class MarkerException extends RuntimeException {
            private static final long serialVersionUID = 1L;
        }

        public static void main(String[] args) throws Exception {
            new Box();
            new MarkerException();
            String mode = System.getProperty(MODE_PROPERTY);
            if (NORMAL_MODE.equals(mode)) {
                PEATestUtils.compileConfiguredTargetsAtLevel4();
                runNormal();
            } else if (OSR_MODE.equals(mode)) {
                runOSR();
            } else {
                throw new IllegalArgumentException("Unknown test mode " + mode);
            }
        }

        private static void runNormal() throws Exception {
            Box entry = new Box();
            entry.x = 7;
            Asserts.assertEquals(entryReal(HOT_TRIPS, entry), 100_007);
            Asserts.assertEquals(loopLocal(HOT_TRIPS, 3), 650_000);
            Asserts.assertEquals(
                    carriedReplacement(HOT_TRIPS, 7, 50_000), 50_099);
            Asserts.assertEquals(
                    conditionalEscape(HOT_TRIPS, 11, true), 1_050_011);
            Asserts.assertEquals(
                    activeDeopt(HOT_TRIPS, 13, false), 1_050_013);
            Asserts.assertEquals(
                    caughtException(HOT_TRIPS, 17, true), 1_050_017);
            Asserts.assertEquals(balancedLock(HOT_TRIPS, 19), 50_019);
            assertBehaviorMatrix();
            System.out.println("PEA-RESULT:100007,650000,50099,1050011,"
                    + "1050013,1050017,50019,matrix");
        }

        private static void runOSR() throws Exception {
            Method entryMethod = TestWrapper.class.getMethod(
                    "entryReal", int.class, Box.class);
            Method localMethod = TestWrapper.class.getMethod(
                    "loopLocal", int.class, int.class);
            Method carriedMethod = TestWrapper.class.getMethod(
                    "carriedReplacement", int.class, int.class, int.class);
            Method escapeMethod = TestWrapper.class.getMethod(
                    "conditionalEscape", int.class, int.class, boolean.class);
            Method activeMethod = TestWrapper.class.getMethod(
                    "activeDeopt", int.class, int.class, boolean.class);
            Method exceptionMethod = TestWrapper.class.getMethod(
                    "caughtException", int.class, int.class, boolean.class);
            Method lockMethod = TestWrapper.class.getMethod(
                    "balancedLock", int.class, int.class);

            Box entry = new Box();
            entry.x = 7;
            int entryResult = naturallyRunOSR(entryMethod,
                    () -> entryReal(HOT_TRIPS, entry));
            int localResult = naturallyRunOSR(localMethod,
                    () -> loopLocal(HOT_TRIPS, 3));
            int carriedResult = naturallyRunOSR(carriedMethod,
                    () -> carriedReplacement(HOT_TRIPS, 7, 50_000));
            int escapeResult = naturallyRunOSR(escapeMethod,
                    () -> conditionalEscape(HOT_TRIPS, 11, true));
            int activeResult = naturallyRunOSR(activeMethod,
                    () -> activeDeopt(HOT_TRIPS, 13, false));
            int exceptionResult = naturallyRunOSR(exceptionMethod,
                    () -> caughtException(HOT_TRIPS, 17, true));
            int lockResult = naturallyRunOSR(lockMethod,
                    () -> balancedLock(HOT_TRIPS, 19));

            Asserts.assertEquals(entryResult, 100_007);
            Asserts.assertEquals(localResult, 650_000);
            Asserts.assertEquals(carriedResult, 50_099);
            Asserts.assertEquals(escapeResult, 1_050_011);
            Asserts.assertEquals(activeResult, 1_050_013);
            Asserts.assertEquals(exceptionResult, 1_050_017);
            Asserts.assertEquals(lockResult, 50_019);

            if (Boolean.getBoolean(ACTIVE_DEOPT_PROPERTY)) {
                deoptimizeActiveOSRFrame(activeMethod);
            }

            assertBehaviorMatrix();
            System.out.println("PEA-RESULT:100007,650000,50099,1050011,"
                    + "1050013,1050017,50019,matrix");
        }

        private static void deoptimizeActiveOSRFrame(Method activeMethod)
                throws Exception {
            PEATestUtils.MethodId activeOSR =
                    PEATestUtils.MethodId.osr(activeMethod);
            phase = 0;
            FutureTask<Integer> deoptTask = new FutureTask<>(
                    () -> activeDeopt(HOT_TRIPS, 13, true));
            Thread deoptWorker = new Thread(deoptTask, "pea-osr-active-deopt");
            deoptWorker.start();
            try {
                awaitPhase(1, deoptTask);
                PEATestUtils.ActiveFrameDeoptEvidence evidence =
                        PEATestUtils.deoptimizeActiveFrame(activeOSR, 0);
                Asserts.assertEquals(evidence.target(), activeOSR);
                Asserts.assertEquals(evidence.compilationLevel(), 4);
                Asserts.assertEquals(evidence.markedNMethods(), 1);
                Asserts.assertTrue(evidence.frameDeoptimized());
                Asserts.assertFalse(
                        WhiteBox.getWhiteBox().isMethodCompiled(activeMethod, true),
                        "active OSR nmethod must disappear synchronously");
                phase = 2;
                Asserts.assertEquals(deoptTask.get(30, TimeUnit.SECONDS), 1_050_013,
                        "deoptimized OSR frame resumes with field and identity state");
            } finally {
                phase = 2;
                deoptWorker.join(TimeUnit.SECONDS.toMillis(30));
                Asserts.assertFalse(deoptWorker.isAlive(),
                        "active-deopt OSR worker must terminate");
            }
        }

        private static void assertBehaviorMatrix() {
            Box zero = new Box();
            zero.x = 5;
            Asserts.assertEquals(entryReal(0, zero), 5);
            Asserts.assertEquals(entryReal(3, zero), 8);
            Asserts.assertEquals(loopLocal(0, 2), 0);
            Asserts.assertEquals(loopLocal(3, 2), 9);
            Asserts.assertEquals(carriedReplacement(0, 5, -1), 5);
            Asserts.assertEquals(carriedReplacement(3, 5, -1), 8);
            Asserts.assertEquals(carriedReplacement(3, 5, 1), 101);

            published = null;
            Asserts.assertEquals(conditionalEscape(0, 7, false), 7);
            Asserts.assertEquals(conditionalEscape(3, 7, false), 8);
            Asserts.assertNull(published);
            Asserts.assertEquals(conditionalEscape(3, 7, true), 1_000_008);
            Asserts.assertNotNull(published);
            Asserts.assertEquals(published.x, 8);

            Asserts.assertEquals(activeDeopt(0, 9, false), 1_000_009);
            Asserts.assertEquals(activeDeopt(3, 9, false), 1_000_010);
            Asserts.assertEquals(caughtException(0, 11, false), 11);
            Asserts.assertEquals(caughtException(3, 11, false), 12);
            Asserts.assertEquals(caughtException(3, 11, true), 1_000_012);
            Asserts.assertEquals(balancedLock(0, 13), 13);
            Asserts.assertEquals(balancedLock(3, 13), 14);
        }

        private static void awaitPhase(int expected, FutureTask<?> task) throws Exception {
            long deadline = System.nanoTime() + WAIT_NANOS;
            while (phase != expected) {
                if (task.isDone()) {
                    task.get();
                    throw new AssertionError(
                            "worker completed before reaching phase " + expected);
                }
                if (System.nanoTime() - deadline >= 0) {
                    throw new RuntimeException(
                            "Timed out waiting for worker phase " + expected);
                }
                Thread.onSpinWait();
            }
        }

        private static int naturallyRunOSR(Method method, Callable<Integer> action)
                throws Exception {
            FutureTask<Integer> task = new FutureTask<>(action);
            Thread worker = new Thread(task, "pea-osr-" + method.getName());
            worker.start();

            WhiteBox whiteBox = WhiteBox.getWhiteBox();
            long deadline = System.nanoTime() + WAIT_NANOS;
            while (!whiteBox.isMethodCompiled(method, true)
                    || whiteBox.getMethodCompilationLevel(method, true) != 4) {
                if (task.isDone()) {
                    int completed = task.get();
                    throw new RuntimeException(
                            "Worker completed with result " + completed
                                    + " before natural OSR level-4 compilation of "
                                    + PEATestUtils.MethodId.osr(method));
                }
                if (System.nanoTime() - deadline >= 0) {
                    throw new RuntimeException(
                            "Timed out waiting for natural OSR level-4 compilation of "
                                    + PEATestUtils.MethodId.osr(method));
                }
                Thread.onSpinWait();
            }
            PEATestUtils.confirmLevel4(PEATestUtils.MethodId.osr(method));
            int result = task.get(30, TimeUnit.SECONDS);
            worker.join(TimeUnit.SECONDS.toMillis(30));
            Asserts.assertFalse(worker.isAlive(), "bounded OSR worker must terminate");
            return result;
        }

        public static int entryReal(int trips, Box p) {
            for (int i = 0; i < trips; i++) {
                p.x++;
            }
            return p.x;
        }

        public static int loopLocal(int trips, int seed) {
            int sum = 0;
            for (int i = 0; i < trips; i++) {
                Box p = new Box();
                p.x = seed + (i & 7);
                sum += p.x;
            }
            return sum;
        }

        public static int carriedReplacement(int trips, int seed, int replaceAt) {
            Box p = new Box();
            p.x = seed;
            for (int i = 0; i < trips; i++) {
                p.x++;
                if (i == replaceAt) {
                    p = new Box();
                    p.x = 100;
                }
            }
            return p.x;
        }

        public static int conditionalEscape(int warmup, int seed, boolean escape) {
            int spin = 0;
            for (int i = 0; i < warmup; i++) {
                spin += i & 1;
            }
            Box p = new Box();
            p.x = seed + spin;
            if (escape) {
                publish(p);
            }
            return p.x + (escape && p == published ? IDENTITY_BIT : 0);
        }

        public static int activeDeopt(int warmup, int seed, boolean hold) {
            int spin = 0;
            for (int i = 0; i < warmup; i++) {
                spin += i & 1;
            }
            Box p = new Box();
            Box alias = p;
            p.x = seed + spin;
            if (hold) {
                phase = 1;
                while (phase == 1) {
                    spin += phase & 0;
                }
            }
            return p.x + (p == alias ? IDENTITY_BIT : 0);
        }

        public static int caughtException(int warmup, int seed, boolean doThrow) {
            int spin = 0;
            for (int i = 0; i < warmup; i++) {
                spin += i & 1;
            }
            Box p = new Box();
            p.x = seed + spin;
            try {
                thrower(doThrow);
                return p.x;
            } catch (MarkerException expected) {
                return p.x + IDENTITY_BIT;
            }
        }

        public static int balancedLock(int warmup, int seed) {
            int spin = 0;
            for (int i = 0; i < warmup; i++) {
                spin += i & 1;
            }
            Box p = new Box();
            synchronized (p) {
                p.x = seed + spin;
                lockPoll();
            }
            return p.x;
        }

        private static void publish(Box p) {
            published = p;
        }

        private static void thrower(boolean doThrow) {
            if (doThrow) {
                throw new MarkerException();
            }
        }

        private static void lockPoll() {
            lockPolls++;
        }
    }
}
