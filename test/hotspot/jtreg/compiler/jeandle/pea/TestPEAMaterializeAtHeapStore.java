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
 * @summary PEA recursively materializes a partially-escaping object before a
 *          heap-publishing store (static field, external object field, array
 *          element), replaying fields onto the retained original allocation;
 *          the published location reads back the final values
 * @library /test/lib /
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.pea.TestPEAMaterializeAtHeapStore
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jdk.test.lib.Asserts;

public class TestPEAMaterializeAtHeapStore {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestPEAMaterializeAtHeapStore$TestWrapper";
    private static final String INT_STORE = "store atomic i32";
    private static final String REF_STORE = "store atomic ptr addrspace(1)";

    public static void main(String[] args) throws Exception {
        Method stat = TestWrapper.class.getMethod("storeStaticField", boolean.class, int.class);
        Method ext = TestWrapper.class.getMethod("storeExternalObjectField",
                boolean.class, TestWrapper.Holder.class, int.class);
        Method arr = TestWrapper.class.getMethod("storeObjectArray",
                boolean.class, TestWrapper.Box[].class, int.class);
        Method multi = TestWrapper.class.getMethod("storeMultipleRoots", boolean.class, int.class);
        Method nested = TestWrapper.class.getMethod("publishNestedChild",
                boolean.class, int.class, int.class);
        Method[] targets = {stat, ext, arr, multi, nested};

        PEATestUtils.behaviorRun(WRAPPER, targets).runPEAOnOffEquivalent();

        try (PEATestUtils.RunResult run = PEATestUtils.shapeRun(WRAPPER, targets).run()) {
            assertHeapStore(run, stat, 1);
            assertHeapStore(run, ext, 1);
            assertHeapStore(run, arr, 1);
            assertHeapStore(run, multi, 2);
            assertHeapStore(run, nested, 2);
        }
    }

    private static void assertHeapStore(PEATestUtils.RunResult run, Method target,
                                        int allocationCount) throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();
        assertDistinctAllocations(before, allocationCount, target);
        Asserts.assertEquals(after.allocationBCIs(), before.allocationBCIs(),
                target + ": PartiallyEscapes heap-store retains the source allocation(s)");
        Asserts.assertTrue(report.round(0).effectCount("Materialize") >= allocationCount,
                target + ": object materialized before the publishing store");
        after.assertLineCount(REF_STORE, allocationCount);
        after.assertAbsent("poison");
        report.assertFinalTransformIdle();
    }

    private static void assertDistinctAllocations(PEATestUtils.IRBody body,
                                                  int expected, Method target) {
        List<Integer> bcis = body.allocationBCIs();
        Asserts.assertEquals(bcis.size(), expected, target + ": source allocation count");
        Set<Integer> distinct = new HashSet<>(bcis);
        Asserts.assertEquals(distinct.size(), expected,
                target + ": every source allocation has a distinct BCI");
    }

    public static class TestWrapper {
        private static final String EXPECTED_DIGEST = "878afe521ba7a1e2";

        public static class Box {
            int value;
        }

        public static class Holder {
            Box field;
        }

        public static class Outer {
            int tag;
            Box child;
        }

        private static Box staticBox;
        private static Box staticSecond;
        private static Outer staticOuter;

        public static void main(String[] args) throws Exception {
            new Box();
            new Holder();
            new Outer();
            PEATestUtils.compileConfiguredTargetsAtLevel4();

            long digest = 0x10B0DCE6A2B7A4C3L;
            for (int v : new int[] {5, -3, 42}) {
                resetStatics();

                int statNo = storeStaticField(false, v);
                Asserts.assertEquals(statNo, -1, "storeStaticField no-escape scalar");
                Asserts.assertNull(staticBox, "storeStaticField no-escape does not publish");
                digest = mix(digest, statNo);

                resetStatics();
                int statYes = storeStaticField(true, v);
                Asserts.assertEquals(statYes, v, "storeStaticField escape readback");
                Asserts.assertNotNull(staticBox, "storeStaticField publishes");
                Asserts.assertEquals(staticBox.value, v, "storeStaticField published value");
                digest = mix(digest, statYes);

                Holder holder = new Holder();
                int extNo = storeExternalObjectField(false, holder, v);
                Asserts.assertEquals(extNo, -1, "storeExternalObjectField no-escape scalar");
                Asserts.assertNull(holder.field, "storeExternalObjectField no-escape no publish");
                digest = mix(digest, extNo);

                int extYes = storeExternalObjectField(true, holder, v + 1);
                Asserts.assertEquals(extYes, v + 1, "storeExternalObjectField escape readback");
                Asserts.assertNotNull(holder.field, "storeExternalObjectField publishes");
                Asserts.assertEquals(holder.field.value, v + 1,
                        "storeExternalObjectField published value");
                digest = mix(digest, extYes);

                Box[] array = new Box[1];
                int arrNo = storeObjectArray(false, array, v);
                Asserts.assertEquals(arrNo, -1, "storeObjectArray no-escape scalar");
                Asserts.assertNull(array[0], "storeObjectArray no-escape no publish");
                digest = mix(digest, arrNo);

                int arrYes = storeObjectArray(true, array, v + 2);
                Asserts.assertEquals(arrYes, v + 2, "storeObjectArray escape readback");
                Asserts.assertNotNull(array[0], "storeObjectArray publishes");
                Asserts.assertEquals(array[0].value, v + 2, "storeObjectArray published value");
                digest = mix(digest, arrYes);

                resetStatics();
                int multiNo = storeMultipleRoots(false, v);
                Asserts.assertEquals(multiNo, -1, "storeMultipleRoots no-escape scalar");
                digest = mix(digest, multiNo);

                resetStatics();
                int multiYes = storeMultipleRoots(true, v);
                Asserts.assertEquals(multiYes, (v) + (v + 1), "storeMultipleRoots escape readback");
                Asserts.assertNotNull(staticBox, "storeMultipleRoots publishes first");
                Asserts.assertNotNull(staticSecond, "storeMultipleRoots publishes second");
                Asserts.assertEquals(staticBox.value, v, "storeMultipleRoots first value");
                Asserts.assertEquals(staticSecond.value, v + 1, "storeMultipleRoots second value");
                digest = mix(digest, multiYes);

                resetStatics();
                int nestedNo = publishNestedChild(false, v, v + 7);
                Asserts.assertEquals(nestedNo, -1, "publishNestedChild no-escape scalar");
                Asserts.assertNull(staticOuter, "publishNestedChild no-escape no publish");
                digest = mix(digest, nestedNo);

                resetStatics();
                int nestedYes = publishNestedChild(true, v, v + 7);
                Asserts.assertEquals(nestedYes, v + (v + 7),
                        "publishNestedChild escape readback");
                Asserts.assertNotNull(staticOuter, "publishNestedChild publishes outer");
                Asserts.assertNotNull(staticOuter.child, "publishNestedChild publishes child");
                Asserts.assertEquals(staticOuter.tag, v, "publishNestedChild outer tag");
                Asserts.assertEquals(staticOuter.child.value, v + 7,
                        "publishNestedChild child value");
                digest = mix(digest, nestedYes);
            }

            String payload = Long.toUnsignedString(digest, 16);
            Asserts.assertEquals(payload, EXPECTED_DIGEST, "behavior digest");
            System.out.println("PEA-RESULT:" + payload);
        }

        public static int storeStaticField(boolean escape, int v) {
            Box b = new Box();
            b.value = v;
            if (!escape) {
                return -1;
            }
            staticBox = b;
            return staticBox.value;
        }

        public static int storeExternalObjectField(boolean escape, Holder ext, int v) {
            Box b = new Box();
            b.value = v;
            if (!escape) {
                return -1;
            }
            ext.field = b;
            return ext.field.value;
        }

        public static int storeObjectArray(boolean escape, Box[] array, int v) {
            Box b = new Box();
            b.value = v;
            if (!escape) {
                return -1;
            }
            array[0] = b;
            return array[0].value;
        }

        public static int storeMultipleRoots(boolean escape, int v) {
            Box a = new Box();
            Box b = new Box();
            a.value = v;
            b.value = v + 1;
            if (!escape) {
                return -1;
            }
            staticBox = a;
            staticSecond = b;
            return staticBox.value + staticSecond.value;
        }

        public static int publishNestedChild(boolean escape, int outerV, int childV) {
            Outer o = new Outer();
            Box child = new Box();
            o.tag = outerV;
            o.child = child;
            child.value = childV;
            if (!escape) {
                return -1;
            }
            staticOuter = o;
            return staticOuter.tag + staticOuter.child.value;
        }

        private static void resetStatics() {
            staticBox = null;
            staticSecond = null;
            staticOuter = null;
        }

        private static long mix(long digest, int value) {
            return Long.rotateLeft(digest ^ Integer.toUnsignedLong(value), 19)
                    * 0x9E3779B97F4A7C15L;
        }
    }
}
