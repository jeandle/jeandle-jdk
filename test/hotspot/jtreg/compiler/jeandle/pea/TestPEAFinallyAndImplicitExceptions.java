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
 * @summary PEA preserves finally semantics (finally runs exactly once on the
 *          normal, caught, and outward-unwind paths) for virtual objects that
 *          the finally modifies or escapes, keeps a live virtual object across
 *          implicit exceptions, and keeps monitors balanced across a
 *          synchronized-finally
 * @library /test/lib /
 * @modules java.base/jdk.internal.misc
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      -XX:-UseCompressedOops -XX:-UseCompressedClassPointers
 *      compiler.jeandle.pea.TestPEAFinallyAndImplicitExceptions
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

import jdk.internal.misc.Unsafe;
import jdk.test.lib.Asserts;

public class TestPEAFinallyAndImplicitExceptions {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestPEAFinallyAndImplicitExceptions$TestWrapper";
    private static final Unsafe UNSAFE = Unsafe.getUnsafe();
    private static final String ENTER = "jeandle.monitorenter_with_lightweight_lock";
    private static final String EXIT = "jeandle.monitorexit_with_lightweight_lock";

    public static void main(String[] args) throws Exception {
        Method finallyAllPaths = TestWrapper.class.getMethod(
                "finallyAllPaths", int.class);
        Method finallyModifies = TestWrapper.class.getMethod(
                "finallyModifies", int.class);
        Method trapThenFinally = TestWrapper.class.getMethod(
                "trapThenFinally", int.class, Object.class, int[].class,
                int.class, int.class);
        Method syncFinally = TestWrapper.class.getMethod(
                "synchronizedFinally", int.class);
        Method throwMarker = TestWrapper.class.getDeclaredMethod("throwMarker");
        Method throwOther = TestWrapper.class.getDeclaredMethod("throwOther");
        Method sink = TestWrapper.class.getDeclaredMethod(
                "sink", TestWrapper.Box.class);
        Method[] targets = {finallyAllPaths, finallyModifies, trapThenFinally,
                syncFinally};

        // Behavior (finally counts, results, escaped identities) on/off, both
        // legacy and lightweight locking.
        for (int mode : List.of(1, 2)) {
            behaviorBuilder(targets, throwMarker, throwOther, sink)
                    .lockingMode(mode).runPEAOnOffEquivalent();
        }

        // Shape at lightweight locking.
        try (PEATestUtils.RunResult run = shapeBuilder(
                targets, throwMarker, throwOther, sink).lockingMode(2).run()) {
            assertEscapeReplay(run, finallyAllPaths, sink);
            assertNeverEscape(run, finallyModifies);
            assertTrapKeepsLiveVO(run, trapThenFinally);
            assertSyncFinally(run, syncFinally);
        }
    }

    private static PEATestUtils.RunBuilder behaviorBuilder(
            Method[] targets, Method throwMarker, Method throwOther, Method sink) {
        return PEATestUtils.behaviorRun(WRAPPER, targets)
                .dontinline(throwMarker).dontinline(throwOther).dontinline(sink);
    }

    private static PEATestUtils.RunBuilder shapeBuilder(
            Method[] targets, Method throwMarker, Method throwOther, Method sink) {
        return PEATestUtils.shapeRun(WRAPPER, targets)
                .dontinline(throwMarker).dontinline(throwOther).dontinline(sink);
    }

    // finally escapes the VO: it partially escapes at the sink in the finally,
    // the original allocation is retained, and the field replay (p.x += 100)
    // is emitted before the sink call on every path.
    private static void assertEscapeReplay(PEATestUtils.RunResult run, Method target,
                                           Method sink) {
        PEATestUtils.PEAReport report = run.report(target);
        report.assertFinalTransformIdle();
        PEATestUtils.PEARound first = report.round(0);
        PEATestUtils.IRBody after = report.finalAfter();
        Asserts.assertTrue(first.hasStats(), target + ": round-0 stats");
        Asserts.assertEquals(first.neverEscapes(), 0, target + ": NeverEscapes");
        Asserts.assertEquals(first.partiallyEscapes(), 1,
                target + ": the escaped VO partially escapes");
        Asserts.assertEquals(first.alwaysEscapes(), 0, target + ": AlwaysEscapes");
        Asserts.assertEquals(after.peaAllocCount(), 1,
                target + ": exactly one original allocation is retained");
        Asserts.assertEquals(after.allocationBCIs().size(), 1,
                target + ": retained allocation count");
        // No escape-site allocation is synthesized; the original is reused.
        Asserts.assertEquals(after.occurrenceCount("jeandle.new_instance"), 1,
                target + ": only the original allocation survives");
        String sinkName = PEATestUtils.MethodId.of(sink).llvmFunctionName();
        // Every sink call site has the p.x += 100 replay immediately before it.
        int sinkCalls = after.lineCount(sinkName);
        Asserts.assertTrue(sinkCalls > 0, target + ": the finally escapes via sink");
        for (int i = 0; i < sinkCalls; i++) {
            PEATestUtils.IRBlock sinkBlock = after.blockContaining(sinkName, i);
            sinkBlock.assertAbsent("jeandle.new_instance");
            sinkBlock.assertBefore("store atomic i32", 0, sinkName, 0);
        }
    }

    // finally modifies a never-escape VO: the allocation is eliminated and the
    // VO's field stores/loads are scalar-replaced (the finallyCount static
    // increment is a separate, non-VO side effect and is not counted).
    private static void assertNeverEscape(PEATestUtils.RunResult run, Method target) {
        PEATestUtils.PEAReport report = run.report(target);
        report.assertFinalTransformIdle();
        PEATestUtils.PEARound first = report.round(0);
        PEATestUtils.IRBody after = report.finalAfter();
        Asserts.assertTrue(first.hasStats(), target + ": round-0 stats");
        Asserts.assertEquals(first.neverEscapes(), 1, target + ": NeverEscapes");
        Asserts.assertEquals(first.partiallyEscapes(), 0,
                target + ": PartiallyEscapes");
        Asserts.assertEquals(first.alwaysEscapes(), 0, target + ": AlwaysEscapes");
        Asserts.assertEquals(after.allocationBCIs(), List.of(),
                target + ": allocation is eliminated");
        Asserts.assertTrue(effectCountForVO(first, "EliminateStore", 0) >= 1,
                target + ": the VO's field stores are eliminated");
        Asserts.assertTrue(effectCountForVO(first, "ReplaceLoad", 0) >= 1,
                target + ": the VO's field loads are scalar-replaced");
    }

    // The implicit trap's deopt bundle keeps the live VO: the never-escape VO
    // live across the trap is described (VO descriptor + VORef slot) so an
    // actual deopt would reconstruct it with its pre-trap field value.
    private static void assertTrapKeepsLiveVO(PEATestUtils.RunResult run,
                                              Method target) throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        report.assertFinalTransformIdle();
        PEATestUtils.PEARound first = report.round(0);
        PEATestUtils.IRBody after = report.finalAfter();
        Asserts.assertTrue(first.hasStats(), target + ": round-0 stats");
        Asserts.assertTrue(first.neverEscapes() >= 1,
                target + ": the live VO stays virtual across the trap");
        // A trap deopt bundle describes the live VO. p.x is 1 at the trap (the
        // write of 10+result has not happened yet on the trapping path).
        PEATestUtils.DeoptBundle bundle = findVOBundle(after, target);
        bundle.assertVirtualObjectIds(0);
        PEATestUtils.VirtualObjectDescriptor p = bundle.virtualObject(0);
        Asserts.assertEquals(p.kind(), PEATestUtils.DescriptorKind.INSTANCE,
                target + ": live VO descriptor kind");
        int xOffset = offset(TestWrapper.Box.class, "x");
        Asserts.assertEquals(p.fields().keySet(), Set.of(xOffset),
                target + ": live VO carries only its touched field");
        PEATestUtils.VirtualObjectEntry x = p.fields().get(xOffset);
        Asserts.assertEquals(x.basicType(), PEATestUtils.DeoptBasicType.INT,
                target + ": live VO field type");
        Asserts.assertEquals(x.value().kind(), PEATestUtils.DeoptValueKind.SCALAR,
                target + ": live VO field is scalar");
        Asserts.assertEquals(x.value().operand(), "i32 1",
                target + ": live VO field holds the pre-trap value");
        Asserts.assertTrue(hasVORef(bundle.rootScope().locals(), 0),
                target + ": root scope local holds the live VO by VORef");
    }

    // Find a deopt bundle in the body that describes at least one VO.
    private static PEATestUtils.DeoptBundle findVOBundle(
            PEATestUtils.IRBody after, Method target) {
        for (int occurrence = 0; ; occurrence++) {
            PEATestUtils.DeoptBundle bundle;
            try {
                bundle = after.deoptBundleAtCall(
                        "llvm.experimental.deoptimize.i32", occurrence);
            } catch (IllegalStateException e) {
                break;
            }
            if (!bundle.virtualObjects().isEmpty()) {
                return bundle;
            }
        }
        throw new AssertionError(target + ": no deopt bundle describes a live VO");
    }

    private static boolean hasVORef(java.util.Map<Integer, PEATestUtils.DeoptValue> slots,
                                    int voId) {
        return slots.values().stream().anyMatch(
                v -> v.kind() == PEATestUtils.DeoptValueKind.VO_REF
                        && v.virtualObjectId() == voId);
    }

    private static int offset(Class<?> holder, String name) throws Exception {
        return Math.toIntExact(
                UNSAFE.objectFieldOffset(holder.getDeclaredField(name)));
    }

    // synchronized-finally: the never-escape owner's allocation and its paired
    // monitor enter/exit are all eliminated; no IllegalMonitorStateException.
    private static void assertSyncFinally(PEATestUtils.RunResult run, Method target) {
        PEATestUtils.PEAReport report = run.report(target);
        report.assertFinalTransformIdle();
        PEATestUtils.PEARound first = report.round(0);
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();
        Asserts.assertTrue(first.hasStats(), target + ": round-0 stats");
        Asserts.assertEquals(first.neverEscapes(), 1, target + ": NeverEscapes");
        Asserts.assertEquals(first.partiallyEscapes(), 0,
                target + ": PartiallyEscapes");
        int enters = before.lineCount(ENTER);
        Asserts.assertTrue(enters > 0, target + ": source monitorenter exists");
        Asserts.assertEquals(effectCountForVO(first, "ReplaceCall", 0, ENTER),
                (long) enters, target + ": every monitorenter is folded");
        Asserts.assertEquals(effectCountForVO(first, "ReplaceCall", 0, EXIT),
                (long) before.lineCount(EXIT),
                target + ": every monitorexit is folded");
        Asserts.assertEquals(after.allocationBCIs(), List.of(),
                target + ": allocation is eliminated");
        after.assertAbsent("jeandle.monitorenter");
        after.assertAbsent("jeandle.monitorexit");
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

    public static class TestWrapper {
        private static final int ITERATIONS = 100;

        public static int finallyCount;
        public static Box saved;
        public static int uncaughtSeen;

        public static class Box {
            public int x;
        }

        public static class Marker extends RuntimeException {
            private static final long serialVersionUID = 1L;
        }

        public static class Other extends RuntimeException {
            private static final long serialVersionUID = 1L;
        }

        public static void main(String[] args) throws Exception {
            new Box();
            new Marker();
            new Other();
            PEATestUtils.compileConfiguredTargetsAtLevel4();

            long digest = 0x61c8864680b583ebL;
            for (int iteration = 0; iteration < ITERATIONS; iteration++) {
                // finally runs exactly once on the normal path.
                digest = mix(digest, runFinallyAllPaths(0, 110));
                // finally runs exactly once on the caught path.
                digest = mix(digest, runFinallyAllPaths(1, 120));
                // finally runs exactly once on the outward-unwind path.
                digest = mix(digest, runFinallyAllPathsUncaught());

                Asserts.assertEquals(finallyModifies(0), 110, "finallyModifies normal");
                digest = mix(digest, 110);
                Asserts.assertEquals(finallyModifies(1), 120, "finallyModifies caught");
                digest = mix(digest, 120);

                int[] arr = {1, 2, 3};
                Asserts.assertEquals(trapThenFinally(0, new Box(), arr, 1, 2), 110,
                        "NPE-free path");
                digest = mix(digest, 110);
                Asserts.assertEquals(trapThenFinally(0, null, arr, 1, 2), 101,
                        "NPE path");
                digest = mix(digest, 101);
                Asserts.assertEquals(trapThenFinally(1, new Box(), arr, 99, 2), 101,
                        "AIOOBE path");
                digest = mix(digest, 101);
                Asserts.assertEquals(trapThenFinally(2, "notabox", arr, 1, 2), 101,
                        "CCE path");
                digest = mix(digest, 101);
                Asserts.assertEquals(trapThenFinally(3, new Box(), arr, 1, 0), 101,
                        "div-zero path");
                digest = mix(digest, 101);

                Asserts.assertEquals(synchronizedFinally(7), 114,
                        "synchronized-finally");
                digest = mix(digest, 114);
            }
            // Each iteration runs finallyAllPaths 3 times (normal/caught/uncaught),
            // finallyModifies 2, trapThenFinally 5, synchronizedFinally 1 = 11.
            Asserts.assertEquals(finallyCount, ITERATIONS * 11,
                    "finally runs exactly once per invocation on every path");
            Asserts.assertEquals(uncaughtSeen, ITERATIONS,
                    "every outward-unwind path reaches the caller handler");
            System.out.println("PEA-RESULT:"
                    + Long.toUnsignedString(digest, 16) + ":" + finallyCount);
        }

        private static int runFinallyAllPaths(int selector, int expected) {
            int before = finallyCount;
            saved = null;
            int r = finallyAllPaths(selector);
            Asserts.assertEquals(r, expected, "finallyAllPaths result " + selector);
            Asserts.assertEquals(finallyCount, before + 1,
                    "finally ran exactly once");
            Asserts.assertNotNull(saved, "finally escaped the VO");
            Asserts.assertEquals(saved.x, expected, "escaped VO final field");
            return r;
        }

        private static int runFinallyAllPathsUncaught() {
            int before = finallyCount;
            saved = null;
            try {
                finallyAllPaths(2);
                throw new AssertionError("expected the uncaught exception");
            } catch (Other expected) {
                uncaughtSeen++;
            }
            Asserts.assertEquals(finallyCount, before + 1,
                    "finally ran exactly once before outward unwind");
            Asserts.assertNotNull(saved, "finally escaped the VO before unwind");
            Asserts.assertEquals(saved.x, 101, "escaped VO field before unwind");
            return 101;
        }

        // Finally escapes the VO and runs on normal / caught / outward-unwind.
        public static int finallyAllPaths(int selector) {
            Box p = new Box();
            p.x = 1;
            try {
                if (selector == 1) {
                    throwMarker();
                }
                if (selector == 2) {
                    throwOther();
                }
                p.x = 10;
            } catch (Marker expected) {
                p.x = 20;
            } finally {
                finallyCount++;
                p.x += 100;
                sink(p);
            }
            return p.x;
        }

        // Finally modifies a never-escape VO on the normal and caught paths.
        public static int finallyModifies(int selector) {
            Box p = new Box();
            p.x = 1;
            try {
                if (selector == 1) {
                    throwMarker();
                }
                p.x = 10;
            } catch (Marker expected) {
                p.x = 20;
            } finally {
                finallyCount++;
                p.x += 100;
            }
            return p.x;
        }

        // An implicit exception before a live VO, then finally continues with it.
        public static int trapThenFinally(int kind, Object obj, int[] arr,
                                          int index, int divisor) {
            Box p = new Box();
            p.x = 1;
            int result;
            try {
                if (kind == 0) {
                    result = ((Box) obj).x;
                } else if (kind == 1) {
                    result = arr[index];
                } else if (kind == 2) {
                    result = ((Box) obj).x;
                } else {
                    result = 100 / divisor;
                }
                p.x = 10 + result;
            } catch (NullPointerException | ArrayIndexOutOfBoundsException
                    | ClassCastException | ArithmeticException expected) {
                result = -1;
            } finally {
                finallyCount++;
                p.x += 100;
            }
            return p.x;
        }

        // A never-escape owner held across a synchronized block with try-finally.
        public static int synchronizedFinally(int seed) {
            Box p = new Box();
            p.x = seed;
            try {
                synchronized (p) {
                    p.x += 100;
                }
            } finally {
                finallyCount++;
                p.x += 7;
            }
            return p.x;
        }

        public static void throwMarker() {
            throw new Marker();
        }

        public static void throwOther() {
            throw new Other();
        }

        public static void sink(Box p) {
            saved = p;
        }

        private static long mix(long digest, int value) {
            return Long.rotateLeft(digest ^ Integer.toUnsignedLong(value), 17)
                    * 0x9e3779b97f4a7c15L;
        }
    }
}
