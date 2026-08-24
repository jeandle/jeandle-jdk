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
 * @summary PEA materializes a partially-escaping object before a return or a
 *          throw, replaying fields onto the retained original allocation; the
 *          no-escape path performs no replay; the thrown operand is a real
 *          object whose fields the caller/catch can observe
 * @library /test/lib /
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.pea.TestPEAMaterializeAtReturn
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jdk.test.lib.Asserts;

public class TestPEAMaterializeAtReturn {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestPEAMaterializeAtReturn$TestWrapper";
    private static final String INT_STORE = "store atomic i32";
    private static final String REF_STORE = "store atomic ptr addrspace(1)";

    public static void main(String[] args) throws Exception {
        Method pojo = TestWrapper.class.getMethod("returnPojo", boolean.class, int.class);
        Method array = TestWrapper.class.getMethod("returnArray", boolean.class, int.class);
        Method nested = TestWrapper.class.getMethod("returnNestedGraph",
                boolean.class, int.class, int.class);
        Method thr = TestWrapper.class.getMethod("throwCustomException", boolean.class, int.class);
        Method[] targets = {pojo, array, nested, thr};

        PEATestUtils.behaviorRun(WRAPPER, targets).runPEAOnOffEquivalent();

        try (PEATestUtils.RunResult run = PEATestUtils.shapeRun(WRAPPER, targets).run()) {
            assertReturn(run, pojo, 1);
            assertReturn(run, array, 1);
            assertReturn(run, nested, 2);
            assertThrow(run, thr);
        }
    }

    private static void assertReturn(PEATestUtils.RunResult run, Method target,
                                     int allocationCount) throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();
        assertDistinctAllocations(before, allocationCount, target);
        Asserts.assertEquals(after.allocationBCIs(), before.allocationBCIs(),
                target + ": PartiallyEscapes return retains the source allocation(s)");
        Asserts.assertTrue(report.round(0).effectCount("Materialize") >= allocationCount,
                target + ": object materialized before the return");
        after.assertAbsent("poison");
        Asserts.assertTrue(after.lineCount(INT_STORE) + after.lineCount(REF_STORE) >= 1,
                target + ": at least one field replay store survives");
        report.assertFinalTransformIdle();
    }

    private static void assertThrow(PEATestUtils.RunResult run, Method target) throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();
        assertDistinctAllocations(before, 2, target);
        Asserts.assertEquals(after.allocationBCIs(), before.allocationBCIs(),
                target + ": throw retains both source allocations");
        Asserts.assertTrue(report.round(0).effectCount("Materialize") >= 2,
                target + ": exception and payload materialized before the throw");
        after.assertPresent("landingpad");
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
        private static final String EXPECTED_DIGEST = "203df25e4bd97914";

        public static class Box {
            int value;
        }

        public static class Outer {
            int tag;
            Box child;
        }

        public static class TestException extends RuntimeException {
            private static final long serialVersionUID = 1L;
            int code;
            Box payload;
        }

        public static void main(String[] args) throws Exception {
            new Box();
            new Outer();
            new TestException();
            PEATestUtils.compileConfiguredTargetsAtLevel4();

            long digest = 0x6A09E667F3BCC909L;
            for (int v : new int[] {3, -7, 101}) {
                Box pojoNo = returnPojo(false, v);
                Asserts.assertNull(pojoNo, "returnPojo no-escape returns null");
                digest = mix(digest, 0);

                Box pojoYes = returnPojo(true, v);
                Asserts.assertNotNull(pojoYes, "returnPojo escape returns object");
                Asserts.assertEquals(pojoYes.value, v, "returnPojo escape field");
                digest = mix(digest, pojoYes.value);

                int[] arrNo = returnArray(false, v);
                Asserts.assertNull(arrNo, "returnArray no-escape returns null");
                digest = mix(digest, 0);

                int[] arrYes = returnArray(true, v);
                Asserts.assertNotNull(arrYes, "returnArray escape returns array");
                Asserts.assertEquals(arrYes.length, 3, "returnArray length");
                Asserts.assertEquals(arrYes[0], v, "returnArray elem 0");
                Asserts.assertEquals(arrYes[1], v + 1, "returnArray elem 1");
                Asserts.assertEquals(arrYes[2], v + 2, "returnArray elem 2");
                digest = mix(digest, arrYes[0]);
                digest = mix(digest, arrYes[2]);

                Outer nestedNo = returnNestedGraph(false, v, v + 5);
                Asserts.assertNull(nestedNo, "returnNestedGraph no-escape returns null");
                digest = mix(digest, 0);

                Outer nestedYes = returnNestedGraph(true, v, v + 5);
                Asserts.assertNotNull(nestedYes, "returnNestedGraph escape returns graph");
                Asserts.assertEquals(nestedYes.tag, v, "returnNestedGraph outer tag");
                Asserts.assertNotNull(nestedYes.child, "returnNestedGraph child present");
                Asserts.assertEquals(nestedYes.child.value, v + 5, "returnNestedGraph child field");
                digest = mix(digest, nestedYes.tag);
                digest = mix(digest, nestedYes.child.value);

                int thrNo = throwCustomException(false, v);
                Asserts.assertEquals(thrNo, v, "throwCustomException no-escape returns scalar");
                digest = mix(digest, thrNo);

                resetException();
                int thrYesResult;
                try {
                    throwCustomException(true, v);
                    thrYesResult = Integer.MIN_VALUE;
                } catch (TestException e) {
                    Asserts.assertEquals(e.code, v + 1, "throwCustomException caught code");
                    Asserts.assertNotNull(e.payload, "throwCustomException caught payload");
                    Asserts.assertEquals(e.payload.value, v, "throwCustomException payload field");
                    thrYesResult = e.code + e.payload.value;
                }
                Asserts.assertTrue(threwException, "throwCustomException escape actually threw");
                digest = mix(digest, thrYesResult);
            }

            String payload = Long.toUnsignedString(digest, 16);
            Asserts.assertEquals(payload, EXPECTED_DIGEST, "behavior digest");
            System.out.println("PEA-RESULT:" + payload);
        }

        public static Box returnPojo(boolean escape, int v) {
            Box b = new Box();
            b.value = v;
            if (!escape) {
                return null;
            }
            return b;
        }

        public static int[] returnArray(boolean escape, int v) {
            int[] arr = new int[3];
            arr[0] = v;
            arr[1] = v + 1;
            arr[2] = v + 2;
            if (!escape) {
                return null;
            }
            return arr;
        }

        public static Outer returnNestedGraph(boolean escape, int outerV, int childV) {
            Outer o = new Outer();
            Box child = new Box();
            o.tag = outerV;
            o.child = child;
            child.value = childV;
            if (!escape) {
                return null;
            }
            return o;
        }

        public static int throwCustomException(boolean escape, int v) {
            Box b = new Box();
            b.value = v;
            if (!escape) {
                return v;
            }
            TestException e = new TestException();
            e.code = v + 1;
            e.payload = b;
            threwException = true;
            throw e;
        }

        private static boolean threwException;

        private static void resetException() {
            threwException = false;
        }

        private static long mix(long digest, int value) {
            return Long.rotateLeft(digest ^ Integer.toUnsignedLong(value), 13)
                    * 0x9E3779B97F4A7C15L;
        }
    }
}
