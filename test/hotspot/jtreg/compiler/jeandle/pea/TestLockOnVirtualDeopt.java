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
 * @summary PEA reconstructs a virtual lock in an exactly deoptimized active
 *          frame that continues, mutates, exits normally, and reacquires it
 * @library /test/lib /
 * @modules java.base/jdk.internal.misc
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      -XX:-UseCompressedOops -XX:-UseCompressedClassPointers
 *      compiler.jeandle.pea.TestLockOnVirtualDeopt
 */

package compiler.jeandle.pea;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jdk.internal.misc.Unsafe;
import jdk.test.lib.Asserts;

public class TestLockOnVirtualDeopt {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestLockOnVirtualDeopt$TestWrapper";
    private static final Unsafe UNSAFE = Unsafe.getUnsafe();

    public static void main(String[] args) throws Exception {
        Method target = TestWrapper.class.getMethod("testContinuingFrame", int.class);
        Method requestDeopt = TestWrapper.class.getDeclaredMethod("requestDeopt");

        for (int lockingMode : List.of(1, 2)) {
            builder(false, lockingMode, target, requestDeopt)
                    .runPEAOnOffEquivalent();
        }

        try (PEATestUtils.RunResult run =
                builder(true, 2, target, requestDeopt).run()) {
            assertShape(run, target, requestDeopt);
        }
    }

    private static PEATestUtils.RunBuilder builder(
            boolean shape, int lockingMode, Method target, Method requestDeopt) {
        PEATestUtils.RunBuilder builder = shape
                ? PEATestUtils.shapeRun(WRAPPER, target)
                : PEATestUtils.behaviorRun(WRAPPER, target);
        return builder.dontinline(requestDeopt).lockingMode(lockingMode);
    }

    private static void assertShape(
            PEATestUtils.RunResult run, Method target, Method requestDeopt)
            throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        report.assertFinalTransformIdle();
        PEATestUtils.PEARound first = report.round(0);
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();
        List<Integer> sourceBCIs = before.allocationBCIs();

        Asserts.assertEquals(sourceBCIs.size(), 1,
                target + ": one source lock owner");
        Asserts.assertEquals(new HashSet<>(sourceBCIs).size(), 1,
                target + ": source owner allocation BCI is unique");
        Asserts.assertEquals(first.neverEscapes(), 1,
                target + ": owner escapes only through deopt reconstruction");
        Asserts.assertEquals(first.partiallyEscapes(), 0,
                target + ": deopt reconstruction does not retain the source allocation");
        Asserts.assertEquals(first.alwaysEscapes(), 0,
                target + ": owner does not always escape");
        Asserts.assertEquals(after.allocationBCIs(), List.of(),
                target + ": virtual owner source allocation is eliminated");
        Asserts.assertEquals(run.finalIR(target).loweredAllocCount(), 0,
                target + ": no lowered allocation remains");

        String callee = PEATestUtils.MethodId.of(requestDeopt).llvmFunctionName();
        PEATestUtils.DeoptBundle source = exactBundle(before, callee);
        PEATestUtils.DeoptBundle bundle = exactBundle(after, callee);
        Asserts.assertEquals(source.virtualObjects().size(), 0,
                target + ": frontend helper call has no PEA descriptors");
        Asserts.assertEquals(bundle.rootScope().bci(), source.rootScope().bci(),
                target + ": descriptor rewrite preserves helper-call BCI");
        Asserts.assertEquals(bundle.rootScope().duplicateBCI(),
                bundle.rootScope().bci(),
                target + ": helper-call BCI is duplicated exactly");
        Asserts.assertEquals(bundle.scopes().size(), 1,
                target + ": single active Java scope");
        bundle.assertVirtualObjectIds(0);

        PEATestUtils.VirtualObjectDescriptor point = bundle.virtualObject(0);
        int xOffset = offset(TestWrapper.Point.class, "x");
        int yOffset = offset(TestWrapper.Point.class, "y");
        Asserts.assertEquals(point.fields().keySet(), Set.of(xOffset, yOffset),
                target + ": exact owner field descriptor");
        assertIntScalar(point, xOffset);
        assertIntScalar(point, yOffset);

        Asserts.assertEquals(bundle.rootScope().monitors().size(), 1,
                target + ": one exact logical monitor entry");
        PEATestUtils.DeoptMonitor monitor =
                bundle.rootScope().monitors().get(0);
        Asserts.assertEquals(monitor.depth(), 0,
                target + ": root monitor depth");
        Asserts.assertTrue(monitor.eliminated(),
                target + ": virtual owner monitor is eliminated");
        Asserts.assertEquals(monitor.owner().kind(),
                PEATestUtils.DeoptValueKind.VO_REF,
                target + ": monitor owner uses a typed VORef");
        Asserts.assertEquals(monitor.owner().virtualObjectId(), 0,
                target + ": monitor owner descriptor identity");
    }

    private static PEATestUtils.DeoptBundle exactBundle(
            PEATestUtils.IRBody body, String callee) {
        Asserts.assertEquals(body.occurrenceCount("@\"" + callee + "\"("), 1,
                body.methodId() + ": exact no-VO deopt helper call");
        return body.deoptBundleAtCall(callee, 0);
    }

    private static void assertIntScalar(
            PEATestUtils.VirtualObjectDescriptor descriptor, int offset) {
        PEATestUtils.VirtualObjectEntry entry = descriptor.fields().get(offset);
        Asserts.assertNotNull(entry);
        Asserts.assertEquals(entry.basicType(), PEATestUtils.DeoptBasicType.INT);
        Asserts.assertEquals(entry.value().kind(),
                PEATestUtils.DeoptValueKind.SCALAR);
        Asserts.assertTrue(entry.value().operand().startsWith("i32 "));
        Asserts.assertFalse(entry.value().operand().contains("poison"));
        Asserts.assertFalse(entry.value().operand().contains("undef"));
    }

    private static int offset(Class<?> holder, String name) throws Exception {
        Field field = holder.getDeclaredField(name);
        return Math.toIntExact(UNSAFE.objectFieldOffset(field));
    }

    public static class TestWrapper {
        private static final Method DEOPT_TARGET = target();
        private static int deoptRequests;

        public static class Point {
            public int x;
            public int y;
        }

        public static void main(String[] args) throws Exception {
            new Point();
            PEATestUtils.compileConfiguredTargetsAtLevel4();
            int result = testContinuingFrame(7);
            if (result != 37_051) {
                throw new AssertionError("single monitor result " + result);
            }
            if (deoptRequests != 1) {
                throw new AssertionError("deopt request count " + deoptRequests);
            }
            System.out.println("PEA-RESULT:" + result);
        }

        public static int testContinuingFrame(int seed) {
            Point point = new Point();
            point.x = seed + 11;
            point.y = seed + 24;
            synchronized (point) {
                int token = requestDeopt();
                if (!Thread.holdsLock(point)
                        || point.x != 18 || point.y != 31) {
                    return Integer.MIN_VALUE + 1;
                }
                point.x += token;
                point.y += 3;
                if (point.x != 23 || point.y != 34
                        || !Thread.holdsLock(point)) {
                    return Integer.MIN_VALUE + 2;
                }
            }
            if (Thread.holdsLock(point)) {
                return Integer.MIN_VALUE + 3;
            }
            synchronized (point) {
                if (!Thread.holdsLock(point)
                        || point.x != 23 || point.y != 34) {
                    return Integer.MIN_VALUE + 4;
                }
                point.x += 7;
                point.y += 17;
            }
            if (Thread.holdsLock(point)
                    || point.x != 30 || point.y != 51) {
                return Integer.MIN_VALUE + 5;
            }
            return point.x * 1000 + point.y + 7000;
        }

        private static int requestDeopt() {
            deoptRequests++;
            if (deoptRequests != 1) {
                throw new AssertionError("deopt helper reexecuted");
            }
            PEATestUtils.ActiveFrameDeoptEvidence evidence =
                    PEATestUtils.deoptimizeActiveFrame(DEOPT_TARGET, 2);
            if (!evidence.frameDeoptimized()
                    || evidence.compilationLevel() != 4
                    || evidence.markedNMethods() != 1) {
                throw new AssertionError("exact active-frame deopt evidence");
            }
            return 5;
        }

        private static Method target() {
            try {
                return TestWrapper.class.getMethod(
                        "testContinuingFrame", int.class);
            } catch (ReflectiveOperationException failure) {
                throw new ExceptionInInitializerError(failure);
            }
        }
    }
}
