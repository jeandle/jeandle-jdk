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
 * @summary PEA describes a Case-C synthetic (merged branch-local allocations)
 *          in a deopt frame state and the runtime reconstructs it at deopt.
 *          Graal treats a synthetic VO identically to a normal VO in deopt.
 * @library /test/lib /
 * @modules java.base/jdk.internal.misc
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      -XX:-UseCompressedOops -XX:-UseCompressedClassPointers
 *      compiler.jeandle.pea.TestPEASyntheticDeoptReconstruct
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

import jdk.internal.misc.Unsafe;
import jdk.test.lib.Asserts;

public class TestPEASyntheticDeoptReconstruct {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestPEASyntheticDeoptReconstruct$TestWrapper";
    private static final Unsafe UNSAFE = Unsafe.getUnsafe();

    public static void main(String[] args) throws Exception {
        Method target = TestWrapper.class.getMethod("test", boolean.class);
        Method requestDeopt = TestWrapper.class.getDeclaredMethod("requestDeopt");

        // Behavioral equivalence (PEA on vs off): exercises the deopt
        // reconstruction — if the described synthetic were mis-reconstructed,
        // test() would return a sentinel and the two runs would diverge.
        PEATestUtils.behaviorRun(WRAPPER, target)
                .dontinline(requestDeopt)
                .runPEAOnOffEquivalent();

        try (PEATestUtils.RunResult run = PEATestUtils.shapeRun(WRAPPER, target)
                .dontinline(requestDeopt)
                .run()) {
            PEATestUtils.PEAReport report = run.report(target);
            report.assertFinalTransformIdle();
            List<Integer> sourceBCIs = report.round0Before().allocationBCIs();
            Asserts.assertEquals(sourceBCIs.size(), 2,
                    "two source Point allocations feeding the Case-C merge");
            // The synthetic escapes only via the deopt frame state, so it is
            // DESCRIBED (not materialized): both sources are eliminated.
            PEATestUtils.PEARound firstRound = report.round(0);
            Asserts.assertEquals(firstRound.neverEscapes(), 2,
                    "both sources NeverEscaped (synthetic described, not materialized)");
            Asserts.assertEquals(firstRound.partiallyEscapes(), 0);
            Asserts.assertEquals(firstRound.alwaysEscapes(), 0);
            Asserts.assertEquals(report.finalAfter().allocationBCIs(), List.of(),
                    "NeverEscaped source OrigAllocs eliminated");
            Asserts.assertEquals(run.finalIR(target).loweredAllocCount(), 0,
                    "no lowered allocation remains");

            // The safepoint's deopt bundle describes exactly one VO — the
            // synthetic — as an INSTANCE with both fields.
            String callee = PEATestUtils.MethodId.of(requestDeopt).llvmFunctionName();
            PEATestUtils.DeoptBundle bundle =
                    report.finalAfter().deoptBundleAtCall(callee, 0);
            Asserts.assertEquals(bundle.virtualObjects().size(), 1,
                    "exactly one synthetic VO descriptor");
            PEATestUtils.VirtualObjectDescriptor vo =
                    bundle.virtualObjects().values().iterator().next();
            Asserts.assertEquals(vo.kind(), PEATestUtils.DescriptorKind.INSTANCE);
            int xOffset = Math.toIntExact(UNSAFE.objectFieldOffset(
                    TestWrapper.Point.class.getDeclaredField("x")));
            int yOffset = Math.toIntExact(UNSAFE.objectFieldOffset(
                    TestWrapper.Point.class.getDeclaredField("y")));
            Asserts.assertEquals(vo.fields().keySet(), Set.of(xOffset, yOffset),
                    "exact Point descriptor fields");
            // y is the same on both branches -> AllSame -> constant 20.
            assertIntField(vo, yOffset, "i32 20");
            // x differs per branch -> merged field value (a scalar, not a
            // constant). Assert it is a scalar INT field; the behavioral run
            // above proves its reconstructed value is correct.
            PEATestUtils.VirtualObjectEntry xEntry = vo.fields().get(xOffset);
            Asserts.assertNotNull(xEntry);
            Asserts.assertEquals(xEntry.basicType(), PEATestUtils.DeoptBasicType.INT);
            Asserts.assertEquals(xEntry.value().kind(), PEATestUtils.DeoptValueKind.SCALAR);
        }
    }

    private static void assertIntField(
            PEATestUtils.VirtualObjectDescriptor descriptor,
            int offset, String operand) {
        PEATestUtils.VirtualObjectEntry field = descriptor.fields().get(offset);
        Asserts.assertNotNull(field);
        Asserts.assertEquals(field.basicType(), PEATestUtils.DeoptBasicType.INT);
        Asserts.assertEquals(field.value().kind(), PEATestUtils.DeoptValueKind.SCALAR);
        Asserts.assertEquals(field.value().operand(), operand);
    }

    public static class TestWrapper {
        private static final Method DEOPT_TARGET = target();

        public static class Point {
            public int x;
            public int y;
        }

        public static void main(String[] args) throws Exception {
            new Point();
            PEATestUtils.compileConfiguredTargetsAtLevel4();
            // Call test once: the active-frame deopt fires inside it, after
            // which the method is re-interpreted, so a second in-JVM call would
            // not be level-4 compiled. cond is a method parameter, so the
            // compiled test(Z) contains both branches and the Case-C merge
            // regardless of the runtime value.
            int result = test(true);
            if (result != 1020) {
                throw new AssertionError("synthetic deopt result " + result);
            }
            System.out.println("PEA-RESULT:" + result);
        }

        // A Case-C merge: two branch-local Point allocations with unobservable
        // identity merge into one synthetic VO. It is referenced only by field
        // reads (never == ), so PEA keeps it virtual. requestDeopt()'s call is a
        // safepoint at which the synthetic is live and virtual, so (with deopt
        // support for synthetic VOs) it is DESCRIBED in the deopt frame state;
        // the runtime reconstructs it when the deopt fires.
        public static int test(boolean cond) {
            Point p;
            if (cond) {
                Point first = new Point();
                first.x = 10;
                first.y = 20;
                p = first;
            } else {
                Point second = new Point();
                second.x = 30;
                second.y = 20;
                p = second;
            }
            requestDeopt();
            int expectedX = cond ? 10 : 30;
            if (p.x != expectedX || p.y != 20) {
                return Integer.MIN_VALUE + 1;
            }
            return p.x * 100 + p.y;
        }

        private static void requestDeopt() {
            PEATestUtils.ActiveFrameDeoptEvidence evidence =
                    PEATestUtils.deoptimizeActiveFrame(DEOPT_TARGET, 2);
            if (!evidence.frameDeoptimized()
                    || evidence.compilationLevel() != 4
                    || evidence.markedNMethods() != 1) {
                throw new AssertionError("exact active-frame deopt evidence");
            }
        }

        private static Method target() {
            try {
                return TestWrapper.class.getMethod("test", boolean.class);
            } catch (ReflectiveOperationException failure) {
                throw new ExceptionInInitializerError(failure);
            }
        }
    }
}
