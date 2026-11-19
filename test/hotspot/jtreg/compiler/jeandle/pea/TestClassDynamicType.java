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
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 */

/*
 * @test
 * @summary Dynamic Class checks inspect virtual allocation types and preserve cast and deopt identity
 * @library /test/lib /
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler compiler.jeandle.pea.TestClassDynamicType
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;
import java.util.Collections;

import jdk.test.lib.Asserts;

public class TestClassDynamicType {
    private static final String WRAPPER = TestWrapper.class.getName();

    public static void main(String[] args) throws Exception {
        Method instance = TestWrapper.class.getMethod("testInstance", Class.class);
        Method array = TestWrapper.class.getMethod("testArray", Class.class);
        Method cast = TestWrapper.class.getMethod("testCast", Class.class);
        Method arrayCast = TestWrapper.class.getMethod("testArrayCast", Class.class);
        Method publish = TestWrapper.class.getMethod("testPublish", Class.class);
        Method deopt = TestWrapper.class.getMethod("testDeopt", Class.class);
        Method arrayDeopt = TestWrapper.class.getMethod("testArrayDeopt", Class.class);
        Method requestDeopt = TestWrapper.class.getDeclaredMethod("requestDeopt");
        Method sink = TestWrapper.class.getDeclaredMethod("publish", Object.class);
        Method[] targets = {instance, array, cast, arrayCast, publish, deopt, arrayDeopt};

        PEATestUtils.behaviorRun(WRAPPER, targets)
                .dontinline(requestDeopt).dontinline(sink)
                .runPEAOnOffEquivalent();

        try (PEATestUtils.RunResult run = PEATestUtils.shapeRun(WRAPPER, targets)
                .dontinline(requestDeopt).dontinline(sink).run()) {
            for (Method target : targets) {
                PEATestUtils.PEAReport report = run.report(target);
                System.out.println("PEA-CLASS-SHAPE:" + target.getName()
                        + ":rounds=" + report.roundCount()
                        + ":stop=" + report.stopReason()
                        + ":allocations=" + report.round0Before().peaAllocCount()
                        + "->" + report.finalAfter().peaAllocCount()
                        + ":sourceBCIs=" + report.round0Before().allocationBCIs());
                int sourceBCI = target.equals(array) || target.equals(arrayCast)
                        || target.equals(arrayDeopt) ? 1 : 0;
                Asserts.assertEquals(Collections.frequency(
                        report.round0Before().allocationBCIs(), sourceBCI), 1,
                        target + ": one source object at its allocation BCI");
                report.round0Before().assertPresent("@jeandle.check_instanceof(");
                report.round(0).after().assertAbsent("@jeandle.check_instanceof(");
                report.round(0).after().assertPresent("@jeandle.check_klass_subtype(");
            }
            for (Method target : new Method[] {instance, array, cast, arrayCast, deopt, arrayDeopt}) {
                Asserts.assertEquals(run.report(target).finalAfter().peaAllocCount(), 0,
                        target + ": dynamic type inspection must not materialize");
                Asserts.assertEquals(run.finalIR(target).loweredAllocCount(), 0);
            }
            for (Method target : new Method[] {publish}) {
                PEATestUtils.PEAReport report = run.report(target);
                Asserts.assertEquals(report.finalAfter().allocationBCIs(),
                        report.round0Before().allocationBCIs(),
                        target + ": publication retains original identity");
            }
            String deoptCallee = PEATestUtils.MethodId.of(requestDeopt).llvmFunctionName();
            PEATestUtils.VirtualObjectDescriptor cell = run.report(deopt).finalAfter()
                    .deoptBundleAtCall(deoptCallee, 0).virtualObject(0);
            Asserts.assertEquals(cell.kind(), PEATestUtils.DescriptorKind.INSTANCE);
            PEATestUtils.VirtualObjectDescriptor ints = run.report(arrayDeopt).finalAfter()
                    .deoptBundleAtCall(deoptCallee, 0).virtualObject(0);
            Asserts.assertEquals(ints.kind(), PEATestUtils.DescriptorKind.ARRAY);
        }
    }

    public static class TestWrapper {
        private static Method deoptTarget;
        private static Object published;
        private static int paths;

        interface Marker {}

        public static class Cell implements Marker {
            int value = 37;
        }

        public static void main(String[] args) throws Exception {
            new Cell();
            new int[2].getClass();
            PEATestUtils.compileConfiguredTargetsAtLevel4();

            check(testInstance(Cell.class) == 37, 1);
            check(testInstance(Marker.class) == 37, 2);
            check(testInstance(String.class) == -37, 4);
            check(testArray(Object.class) == 23, 8);
            check(testArray(Cloneable.class) == 23, 16);
            check(testArray(String[].class) == -23, 32);
            check(testCast(Object.class) == 37, 64);
            check(testArrayCast(Cloneable.class) == 23, 128);
            try {
                testCast(String.class);
                throw new AssertionError("incompatible instance cast succeeded");
            } catch (ClassCastException expected) {
                Asserts.assertEquals(expected.getMessage(), "Cannot cast " + Cell.class.getName() + " to java.lang.String");
                paths |= 256;
            }
            try {
                testArrayCast(Object[].class);
                throw new AssertionError("incompatible array cast succeeded");
            } catch (ClassCastException expected) {
                Asserts.assertEquals(expected.getMessage(), "Cannot cast [I to [Ljava.lang.Object;");
                paths |= 512;
            }
            check(testPublish(Cell.class) == 37 && ((Cell) published).value == 37, 1024);
            check(testPublish(String.class) == -37 && ((Cell) published).value == 37, 2048);

            deoptTarget = TestWrapper.class.getMethod("testDeopt", Class.class);
            check(testDeopt(Marker.class) == 37, 4096);
            deoptTarget = TestWrapper.class.getMethod("testArrayDeopt", Class.class);
            check(testArrayDeopt(Cloneable.class) == 23, 8192);
            if (paths != 16383) {
                throw new AssertionError("missing execution path: " + paths);
            }
            System.out.println("PEA-RESULT:" + paths);
        }

        public static int testInstance(Class<?> target) {
            Cell cell = new Cell();
            return target.isInstance(cell) ? cell.value : -cell.value;
        }

        public static int testArray(Class<?> target) {
            int[] array = new int[] {11, 12};
            return target.isInstance(array) ? array[0] + array[1] : -array[0] - array[1];
        }

        public static int testCast(Class<?> target) {
            Cell cell = new Cell();
            Object alias = target.cast(cell);
            if (alias != cell) {
                return Integer.MIN_VALUE;
            }
            return cell.value;
        }

        public static int testArrayCast(Class<?> target) {
            int[] array = new int[] {11, 12};
            Object alias = target.cast(array);
            if (alias != array) {
                return Integer.MIN_VALUE;
            }
            return array[0] + array[1];
        }

        public static int testPublish(Class<?> target) {
            Cell cell = new Cell();
            boolean matches = target.isInstance(cell);
            publish(cell);
            if (published != cell) {
                return Integer.MIN_VALUE;
            }
            return matches ? cell.value : -cell.value;
        }

        public static int testDeopt(Class<?> target) {
            Cell cell = new Cell();
            Object alias = cell;
            boolean matches = target.isInstance(alias);
            requestDeopt();
            if (alias != cell || cell.value != 37) {
                return Integer.MIN_VALUE;
            }
            return matches ? cell.value : -cell.value;
        }

        public static int testArrayDeopt(Class<?> target) {
            int[] array = new int[] {11, 12};
            Object alias = array;
            boolean matches = target.isInstance(alias);
            requestDeopt();
            if (alias != array || array[0] != 11 || array[1] != 12) {
                return Integer.MIN_VALUE;
            }
            return matches ? array[0] + array[1] : -array[0] - array[1];
        }

        private static void publish(Object value) {
            published = value;
        }

        private static void requestDeopt() {
            PEATestUtils.ActiveFrameDeoptEvidence evidence =
                    PEATestUtils.deoptimizeActiveFrame(deoptTarget, 2);
            if (!evidence.frameDeoptimized()) {
                throw new AssertionError("active target frame did not deoptimize");
            }
        }

        private static void check(boolean condition, int path) {
            if (!condition) {
                throw new AssertionError("wrong result on path " + path);
            }
            paths |= path;
        }
    }
}
