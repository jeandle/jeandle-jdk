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
 * @summary PEA atomically recomposes a legacy object-array descriptor with a
 *          current synthetic element across outer iterations
 * @library /test/lib /
 * @modules java.base/jdk.internal.misc
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      -XX:-UseCompressedOops -XX:-UseCompressedClassPointers
 *      compiler.jeandle.pea.TestPEACrossRoundDeoptPool
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jdk.internal.misc.Unsafe;
import jdk.test.lib.Asserts;

public class TestPEACrossRoundDeoptPool {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestPEACrossRoundDeoptPool$TestWrapper";
    private static final Unsafe UNSAFE = Unsafe.getUnsafe();

    public static void main(String[] args) throws Exception {
        Method target = TestWrapper.class.getMethod(
                "test", int.class, boolean.class);
        Method checkpoint = TestWrapper.class.getDeclaredMethod("checkpoint");
        Method requestDeopt =
                TestWrapper.class.getDeclaredMethod("requestDeopt");

        PEATestUtils.assertStructuralParserContracts();
        PEATestUtils.behaviorRun(WRAPPER, target)
                .dontinline(checkpoint)
                .dontinline(requestDeopt)
                .runPEAOnOffEquivalent();

        try (PEATestUtils.RunResult run =
                PEATestUtils.shapeRun(WRAPPER, target)
                        .peaIterations(4)
                        .dontinline(checkpoint)
                        .dontinline(requestDeopt)
                        .run()) {
            PEATestUtils.PEAReport report = run.report(target);
            Asserts.assertEquals(report.roundCount(), 3,
                    "current construction, cross-round composition, then fixpoint");
            report.assertStoppedAtFixpoint();
            report.assertFinalTransformIdle();
            for (PEATestUtils.PEARound round : report.rounds()) {
                PEATestUtils.assertStructuralSoundness(
                        round.after(), target + ": round " + round.iteration());
            }

            Asserts.assertEquals(
                    report.round0Before().allocations().size(), 3,
                    "two Point sources and one object-array source");
            report.finalAfter().assertRetainsExactlyOriginalAllocations(
                    report.round0Before());
            Asserts.assertEquals(run.finalIR(target).loweredAllocCount(), 0,
                    "the complete graph remains virtual");

            String callee =
                    PEATestUtils.MethodId.of(requestDeopt).llvmFunctionName();
            int arrayBase = UNSAFE.arrayBaseOffset(TestWrapper.Point[].class);

            PEATestUtils.PEARound compositionRound = report.round(1);
            Asserts.assertFalse(compositionRound.transformIdle(),
                    "round 1 must compose the prior array with the current Point");
            PEATestUtils.DeoptBundle beforeComposition =
                    compositionRound.before().deoptBundleAtCall(callee, 0);
            beforeComposition.assertVirtualObjectIds(0);
            Asserts.assertEquals(beforeComposition.virtualObject(0).kind(),
                    PEATestUtils.DescriptorKind.ARRAY,
                    "round 0 leaves one legacy array descriptor");
            Asserts.assertEquals(
                    beforeComposition.virtualObject(0).elements().keySet(),
                    Set.of(arrayBase), "legacy array has one exact element");
            Asserts.assertEquals(
                    beforeComposition.virtualObject(0).elements()
                            .get(arrayBase).value().kind(),
                    PEATestUtils.DeoptValueKind.MATERIALIZED_OOP,
                    "before composition the array element is an opaque SSA oop");

            compositionRound.uniqueEffect(
                    "RewriteDeoptPool", "nodes=2", "current=1", callee);
            PEATestUtils.DeoptBundle afterComposition =
                    compositionRound.after().deoptBundleAtCall(callee, 0);
            afterComposition.assertVirtualObjectIds(0, 1);
            Asserts.assertEquals(
                    new ArrayList<>(afterComposition.virtualObjects().keySet()),
                    List.of(0, 1),
                    "one atomic rewrite emits legacy array before current Point");
            Asserts.assertEquals(afterComposition.virtualObject(0).kind(),
                    PEATestUtils.DescriptorKind.ARRAY,
                    "legacy array remains wire id 0");
            Asserts.assertEquals(afterComposition.virtualObject(1).kind(),
                    PEATestUtils.DescriptorKind.INSTANCE,
                    "current synthetic Point receives wire id 1");
            afterComposition.assertVORef(0, arrayBase, 1);

            PEATestUtils.DeoptBundle bundle =
                    report.finalAfter().deoptBundleAtCall(callee, 0);
            bundle.assertVirtualObjectIds(0, 1);
            Asserts.assertEquals(
                    new ArrayList<>(bundle.virtualObjects().keySet()),
                    List.of(0, 1), "final descriptor order remains stable");

            PEATestUtils.VirtualObjectDescriptor array =
                    bundle.virtualObject(0);
            PEATestUtils.VirtualObjectDescriptor point =
                    bundle.virtualObject(1);
            Asserts.assertEquals(
                    array.kind(), PEATestUtils.DescriptorKind.ARRAY,
                    "legacy descriptor remains first in deterministic pool order");
            Asserts.assertEquals(
                    point.kind(), PEATestUtils.DescriptorKind.INSTANCE,
                    "current synthetic Point follows the legacy array");

            Asserts.assertEquals(array.elements().keySet(), Set.of(arrayBase),
                    "one exact object-array element");
            bundle.assertVORef(array.id(), arrayBase, point.id());

            int xOffset = Math.toIntExact(UNSAFE.objectFieldOffset(
                    TestWrapper.Point.class.getDeclaredField("x")));
            int yOffset = Math.toIntExact(UNSAFE.objectFieldOffset(
                    TestWrapper.Point.class.getDeclaredField("y")));
            Asserts.assertEquals(point.fields().keySet(),
                    Set.of(xOffset, yOffset), "exact Point field state");
            for (Map.Entry<Integer, PEATestUtils.VirtualObjectEntry> entry
                    : point.fields().entrySet()) {
                Asserts.assertEquals(entry.getValue().basicType(),
                        PEATestUtils.DeoptBasicType.INT,
                        "Point field computational type");
                Asserts.assertEquals(entry.getValue().value().kind(),
                        PEATestUtils.DeoptValueKind.SCALAR,
                        "Point field remains a scalar deopt value");
            }
        }
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
            int result = test(7, true);
            if (result != 48) {
                throw new AssertionError("cross-round deopt result " + result);
            }
            System.out.println("PEA-RESULT:" + result);
        }

        public static int test(int seed, boolean chooseSecond) {
            Point first = new Point();
            checkpoint();
            Point second = new Point();
            checkpoint();
            Point[] points = new Point[1];
            checkpoint();
            points[0] = first;
            if (chooseSecond) {
                checkpoint();
                points[0] = second;
                checkpoint();
            }
            points[0].x = 3;
            points[0].y = 9 + seed;
            requestDeopt();
            return points[0].x * points[0].y;
        }

        private static void checkpoint() {}

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
                return TestWrapper.class.getMethod(
                        "test", int.class, boolean.class);
            } catch (ReflectiveOperationException failure) {
                throw new ExceptionInInitializerError(failure);
            }
        }
    }
}
