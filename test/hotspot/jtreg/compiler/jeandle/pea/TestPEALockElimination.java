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
 *
 */

/*
 * @test
 * @summary PEA eliminates never-escape virtual monitors and their allocations
 *          (single lock, sequential two segments, nested graph owner), and
 *          describes an eliminated monitor correctly at an in-lock safepoint
 * @library /test/lib /
 * @modules java.base/jdk.internal.misc
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      -XX:-UseCompressedOops -XX:-UseCompressedClassPointers
 *      compiler.jeandle.pea.TestPEALockElimination
 */

package compiler.jeandle.pea;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jdk.internal.misc.Unsafe;
import jdk.test.lib.Asserts;

public class TestPEALockElimination {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestPEALockElimination$TestWrapper";
    private static final Unsafe UNSAFE = Unsafe.getUnsafe();
    // LockingMode 1 (LM_LEGACY) emits *_with_thin_lock; LockingMode 2
    // (LM_LIGHTWEIGHT) emits *_with_lightweight_lock. Both fold identically.
    private static final String ENTER_LEGACY = "jeandle.monitorenter_with_thin_lock";
    private static final String EXIT_LEGACY = "jeandle.monitorexit_with_thin_lock";
    private static final String ENTER_LIGHT = "jeandle.monitorenter_with_lightweight_lock";
    private static final String EXIT_LIGHT = "jeandle.monitorexit_with_lightweight_lock";

    public static void main(String[] args) throws Exception {
        Method single = TestWrapper.class.getMethod("singleLock", int.class);
        Method twoSegments = TestWrapper.class.getMethod("sequentialTwoSegments", int.class);
        Method nested = TestWrapper.class.getMethod("nestedGraphOwner", int.class);
        Method inLock = TestWrapper.class.getMethod("safepointInsideLock", int.class);
        Method poll = TestWrapper.class.getDeclaredMethod("poll");
        Method[] targets = {single, twoSegments, nested, inLock};

        // Behavior (digest) equivalence for both legacy and lightweight locking.
        behaviorBuilder(targets, poll).lockingMode(1).runPEAOnOffEquivalent();
        behaviorBuilder(targets, poll).lockingMode(2).runPEAOnOffEquivalent();

        // Shape at both locking modes.
        try (PEATestUtils.RunResult run =
                shapeBuilder(targets, poll).lockingMode(1).run()) {
            assertNeverEscapeShape(run, single, 1, ENTER_LEGACY, EXIT_LEGACY);
            assertNeverEscapeShape(run, twoSegments, 1, ENTER_LEGACY, EXIT_LEGACY);
            assertNeverEscapeShape(run, nested, 2, ENTER_LEGACY, EXIT_LEGACY);
            assertNeverEscapeShape(run, inLock, 1, ENTER_LEGACY, EXIT_LEGACY);
            assertEliminatedMonitorDescriptor(run, inLock, poll);
        }
        try (PEATestUtils.RunResult run =
                shapeBuilder(targets, poll).lockingMode(2).run()) {
            assertNeverEscapeShape(run, single, 1, ENTER_LIGHT, EXIT_LIGHT);
            assertNeverEscapeShape(run, twoSegments, 1, ENTER_LIGHT, EXIT_LIGHT);
            assertNeverEscapeShape(run, nested, 2, ENTER_LIGHT, EXIT_LIGHT);
            assertNeverEscapeShape(run, inLock, 1, ENTER_LIGHT, EXIT_LIGHT);
            assertEliminatedMonitorDescriptor(run, inLock, poll);
        }
    }

    private static PEATestUtils.RunBuilder behaviorBuilder(Method[] targets, Method poll) {
        return PEATestUtils.behaviorRun(WRAPPER, targets).dontinline(poll);
    }

    private static PEATestUtils.RunBuilder shapeBuilder(Method[] targets, Method poll) {
        return PEATestUtils.shapeRun(WRAPPER, targets).dontinline(poll);
    }

    // Every owner here never escapes, so its allocation and its balanced
    // monitorenter/monitorexit pairs are all eliminated.
    private static void assertNeverEscapeShape(PEATestUtils.RunResult run, Method target,
                                               int allocations, String enter, String exit) {
        PEATestUtils.PEAReport report = run.report(target);
        report.assertFinalTransformIdle();
        PEATestUtils.PEARound first = report.round(0);
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();

        List<Integer> sourceBCIs = before.allocationBCIs();
        Asserts.assertEquals(sourceBCIs.size(), allocations,
                target + ": source allocation count");
        Asserts.assertEquals(new HashSet<>(sourceBCIs).size(), allocations,
                target + ": source allocations have distinct BCIs");
        int sourceEnters = before.lineCount(enter);
        Asserts.assertTrue(sourceEnters > 0, target + ": source monitorenter exists");
        Asserts.assertTrue(before.lineCount(exit) >= sourceEnters,
                target + ": source IR contains balanced normal and cleanup exits");

        Asserts.assertTrue(first.hasStats(), target + ": round-0 PEA stats");
        Asserts.assertEquals(first.neverEscapes(), allocations,
                target + ": NeverEscapes");
        Asserts.assertEquals(first.partiallyEscapes(), 0, target + ": PartiallyEscapes");
        Asserts.assertEquals(first.alwaysEscapes(), 0, target + ": AlwaysEscapes");

        Asserts.assertEquals(first.effectCount("EliminateAllocation"), (long) allocations,
                target + ": every source allocation is eliminated");
        Asserts.assertEquals(effectCountForVO(first, "ReplaceCall", 0, enter),
                (long) sourceEnters, target + ": every monitorenter is folded");
        Asserts.assertEquals(effectCountForVO(first, "ReplaceCall", 0, exit),
                (long) before.lineCount(exit), target + ": every monitorexit is folded");
        Asserts.assertEquals(first.effectCount("Materialize"), 0L,
                target + ": never-escape owner does not materialize");

        Asserts.assertEquals(after.allocationBCIs(), List.of(),
                target + ": no allocation remains after PEA");
        after.assertAbsent("jeandle.monitorenter");
        after.assertAbsent("jeandle.monitorexit");
    }

    // The poll call inside safepointInsideLock is a safepoint taken while the
    // owner is locked. The owner's allocation is eliminated, so the safepoint's
    // deopt bundle must describe it as virtual (with its constant field state)
    // and carry one eliminated monitor whose owner is a VORef to it.
    private static void assertEliminatedMonitorDescriptor(PEATestUtils.RunResult run,
                                                          Method target, Method poll)
            throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.IRBody after = report.finalAfter();
        String pollName = PEATestUtils.MethodId.of(poll).llvmFunctionName();
        PEATestUtils.DeoptBundle bundle = after.deoptBundleAtCall(pollName, 0);
        Asserts.assertEquals(bundle.scopes().size(), 1,
                target + ": poll safepoint has one Java scope");
        bundle.assertVirtualObjectIds(0);
        // The virtual owner carries its in-lock field state (x == 41, a constant
        // written before the safepoint); y stays an untouched default.
        PEATestUtils.VirtualObjectDescriptor owner = bundle.virtualObject(0);
        Asserts.assertEquals(owner.kind(), PEATestUtils.DescriptorKind.INSTANCE,
                target + ": owner descriptor kind");
        int xOffset = offset(TestWrapper.Box.class, "x");
        int yOffset = offset(TestWrapper.Box.class, "y");
        Asserts.assertEquals(owner.fields().keySet(), Set.of(xOffset),
                target + ": owner descriptor carries only the touched field");
        PEATestUtils.VirtualObjectEntry x = owner.fields().get(xOffset);
        Asserts.assertEquals(x.basicType(), PEATestUtils.DeoptBasicType.INT,
                target + ": owner x basic type");
        Asserts.assertEquals(x.value().kind(), PEATestUtils.DeoptValueKind.SCALAR,
                target + ": owner x is scalar");
        Asserts.assertEquals(x.value().operand(), "i32 41",
                target + ": owner x captures the constant in-lock value");
        Asserts.assertEquals(bundle.rootScope().monitors().size(), 1,
                target + ": exactly one monitor is held at the poll safepoint");
        PEATestUtils.DeoptMonitor monitor = bundle.rootScope().monitors().get(0);
        Asserts.assertTrue(monitor.eliminated(),
                target + ": the held monitor is described as eliminated");
        Asserts.assertEquals(monitor.owner().kind(), PEATestUtils.DeoptValueKind.VO_REF,
                target + ": the eliminated monitor owner is a typed VORef");
        Asserts.assertEquals(monitor.owner().virtualObjectId(), 0,
                target + ": the eliminated monitor owner keeps the locked VO identity");
    }

    private static int offset(Class<?> holder, String name) throws Exception {
        Field field = holder.getDeclaredField(name);
        return Math.toIntExact(UNSAFE.objectFieldOffset(field));
    }

    private static long effectCountForVO(PEATestUtils.PEARound round, String kind,
                                         int objectId, String... detailParts) {
        String objectToken = "[VO=" + objectId + "]";
        return round.effects().stream()
                .filter(effect -> effect.kind().equals(kind))
                .filter(effect -> Arrays.asList(effect.detail().split("\\s+"))
                        .contains(objectToken))
                .filter(effect -> Arrays.stream(detailParts)
                        .allMatch(effect.detail()::contains))
                .count();
    }

    public static class TestWrapper {
        private static final int ITERATIONS = 250;

        public static int pollCalls;

        public static class Box {
            public int x;
            public int y;
        }

        public static class Child {
            public int c;
        }

        public static class Owner {
            public int x;
            public Child child;
        }

        public static void main(String[] args) throws Exception {
            new Box();
            new Child();
            new Owner();
            PEATestUtils.compileConfiguredTargetsAtLevel4();

            long digest = 0x61c8864680b583ebL;
            for (int iteration = 0; iteration < ITERATIONS; iteration++) {
                int seed = iteration & 31;

                int single = singleLock(seed);
                Asserts.assertEquals(single, 2 * seed + 1, "singleLock");
                digest = mix(digest, single);

                int twoSegments = sequentialTwoSegments(seed);
                Asserts.assertEquals(twoSegments, 3 * seed + 7, "sequentialTwoSegments");
                digest = mix(digest, twoSegments);

                int nested = nestedGraphOwner(seed);
                Asserts.assertEquals(nested, 2 * seed + 1, "nestedGraphOwner");
                digest = mix(digest, nested);

                int inLock = safepointInsideLock(seed);
                Asserts.assertEquals(inLock, 41 + seed, "safepointInsideLock");
                digest = mix(digest, inLock);
            }
            Asserts.assertEquals(pollCalls, ITERATIONS,
                    "every safepointInsideLock call reaches the in-lock poll");
            System.out.println("PEA-RESULT:"
                    + Long.toUnsignedString(digest, 16) + ":" + pollCalls);
        }

        // Single never-escape lock; fields written and read inside the lock.
        public static int singleLock(int seed) {
            Box p = new Box();
            synchronized (p) {
                p.x = seed;
                p.y = seed + 1;
            }
            return p.x + p.y;
        }

        // Two independent lock segments on the same never-escape owner.
        public static int sequentialTwoSegments(int seed) {
            Box p = new Box();
            p.x = seed;
            synchronized (p) {
                p.x += 1;
                p.y = p.x;
            }
            int mid = p.x;
            synchronized (p) {
                p.y += 2;
                p.x = p.y;
            }
            return p.x + p.y + mid;
        }

        // Locked virtual owner holding a nested virtual child through a field.
        public static int nestedGraphOwner(int seed) {
            Owner owner = new Owner();
            owner.child = new Child();
            synchronized (owner) {
                owner.x = seed;
                owner.child.c = seed + 1;
            }
            return owner.x + owner.child.c;
        }

        // A real (dontinline) call is a safepoint taken while the never-escape
        // owner is locked; the owner must stay virtual through it. The constant
        // field write before the poll lets the descriptor assertion pin the
        // captured virtual field state.
        public static int safepointInsideLock(int seed) {
            Box p = new Box();
            int snapshot;
            synchronized (p) {
                p.x = 41;
                poll();
                p.y = p.x + seed;
                snapshot = p.y;
            }
            return snapshot;
        }

        public static void poll() {
            pollCalls++;
        }

        private static long mix(long digest, int value) {
            return Long.rotateLeft(digest ^ Integer.toUnsignedLong(value), 17)
                    * 0x9e3779b97f4a7c15L;
        }
    }
}
