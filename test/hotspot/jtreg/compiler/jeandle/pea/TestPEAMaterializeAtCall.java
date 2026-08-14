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
 * @summary PEA materializes each distinct normal call argument once, replays
 *          nested state in dependency order, and leaves no-call paths virtual
 * @library /test/lib /
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.pea.TestPEAMaterializeAtCall
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import jdk.test.lib.Asserts;

public class TestPEAMaterializeAtCall {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestPEAMaterializeAtCall$TestWrapper";
    private static final String INT_STORE = "store atomic i32";
    private static final String REF_STORE = "store atomic ptr addrspace(1)";
    private static final String INT_LOAD = "load atomic i32";

    public static void main(String[] args) throws Exception {
        Method one = TestWrapper.class.getMethod("testOne", boolean.class, int.class);
        Method two = TestWrapper.class.getMethod("testTwo", boolean.class,
                int.class, int.class);
        Method same = TestWrapper.class.getMethod("testSameTwice", boolean.class,
                int.class);
        Method nested = TestWrapper.class.getMethod("testNested", boolean.class,
                int.class, int.class);
        Method consumeOne = TestWrapper.class.getMethod("consumeOne", TestWrapper.Box.class);
        Method consumeTwo = TestWrapper.class.getMethod("consumeTwo",
                TestWrapper.Box.class, TestWrapper.Box.class);
        Method consumeSame = TestWrapper.class.getMethod("consumeSameTwice",
                TestWrapper.Box.class, TestWrapper.Box.class);
        Method consumeNested = TestWrapper.class.getMethod("consumeNested",
                TestWrapper.Outer.class, TestWrapper.Box.class);
        Method[] targets = {one, two, same, nested};

        behaviorBuilder(targets, consumeOne, consumeTwo, consumeSame, consumeNested)
                .runPEAOnOffEquivalent();

        try (PEATestUtils.RunResult run =
                shapeBuilder(targets, consumeOne, consumeTwo, consumeSame,
                             consumeNested).run()) {
            assertShape(run, one, consumeOne, new int[] {0}, 1, 0, 1);
            assertShape(run, two, consumeTwo, new int[] {0, 1}, 2, 0, 2);
            assertShape(run, same, consumeSame, new int[] {0}, 1, 0, 1);
            assertShape(run, nested, consumeNested, new int[] {1, 0}, 2, 1, 2);
        }
    }

    private static PEATestUtils.RunBuilder behaviorBuilder(
            Method[] targets, Method... callees) {
        PEATestUtils.RunBuilder builder = PEATestUtils.behaviorRun(WRAPPER, targets);
        for (Method callee : callees) {
            builder.dontinline(callee);
        }
        return builder;
    }

    private static PEATestUtils.RunBuilder shapeBuilder(
            Method[] targets, Method... callees) {
        PEATestUtils.RunBuilder builder = PEATestUtils.shapeRun(WRAPPER, targets);
        for (Method callee : callees) {
            builder.dontinline(callee);
        }
        return builder;
    }

    private static void assertShape(PEATestUtils.RunResult run, Method target,
                                    Method callee, int[] materializationOrder,
                                    int intStores, int referenceStores,
                                    int postCallLoads) throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();
        int allocationCount = materializationOrder.length;
        int replayCount = intStores + referenceStores;
        List<Integer> sourceBCIs = before.allocationBCIs();

        Asserts.assertEquals(sourceBCIs.size(), allocationCount,
                target + ": source allocation count");
        Set<Integer> distinctBCIs = new HashSet<>(sourceBCIs);
        Asserts.assertEquals(distinctBCIs.size(), allocationCount,
                target + ": every source allocation has a distinct BCI");
        Asserts.assertEquals(after.allocationBCIs(), sourceBCIs,
                target + ": only source OrigAlloc allocations survive");
        before.assertPresent("br i1");

        for (PEATestUtils.PEARound round : report.rounds()) {
            List<PEATestUtils.PEAEffect> materializations = round.effects().stream()
                    .filter(effect -> effect.kind().equals("Materialize"))
                    .collect(Collectors.toList());
            Asserts.assertEquals(materializations.size(), materializationOrder.length,
                    target + ": one materialization per distinct ObjectID in round "
                            + round.iteration());
            for (int index = 0; index < materializationOrder.length; index++) {
                String object = "[VO=" + materializationOrder[index] + "]";
                Asserts.assertTrue(materializations.get(index).detail().contains(object),
                        target + ": materialization order at index " + index
                                + " in round " + round.iteration());
                Asserts.assertEquals(round.effectCount("Materialize", object), 1L,
                        target + ": ObjectID is materialized once in round "
                                + round.iteration());
            }
            Asserts.assertEquals(round.effectCount("EliminateStore"), (long) replayCount,
                    target + ": every source field store is tracked in round "
                            + round.iteration());
        }

        String calleeName = PEATestUtils.MethodId.of(callee).llvmFunctionName();
        PEATestUtils.IRBlock callBlock = after.blockContaining(calleeName, 0);
        Asserts.assertEquals(after.lineCount(INT_STORE), intStores,
                target + ": exact final integer replay count");
        Asserts.assertEquals(callBlock.occurrenceCount(INT_STORE), intStores,
                target + ": every integer replay is in the call predecessor");
        Asserts.assertEquals(after.lineCount(REF_STORE), referenceStores,
                target + ": exact final reference replay count");
        Asserts.assertEquals(callBlock.occurrenceCount(REF_STORE), referenceStores,
                target + ": every reference replay is in the call predecessor");
        Asserts.assertEquals(after.lineCount(INT_LOAD), postCallLoads,
                target + ": only post-call field loads remain real");
        callBlock.assertAbsent("jeandle.new_instance");
        if (intStores != 0) {
            callBlock.assertBefore(INT_STORE, intStores - 1, calleeName, 0);
        }
        if (referenceStores != 0) {
            callBlock.assertBefore(REF_STORE, referenceStores - 1, calleeName, 0);
        }

        // A materialized real argument stays as a live oop in the call's
        // deopt bundle. It must not also receive a virtual-object descriptor.
        callBlock.assertAbsent("i64 262156");
        callBlock.assertAbsent("i64 524300");
        if (allocationCount == 2) {
            callBlock.assertAbsent("i64 4295229452");
            callBlock.assertAbsent("i64 4295491596");
        }
        after.assertAbsent("poison");

        // The total final replay count equals the replay count in the one call
        // block. Consequently the no-call return path executes no replay.
        Asserts.assertEquals(after.lineCount(INT_STORE) + after.lineCount(REF_STORE),
                replayCount, target + ": no replay outside the call predecessor");
    }

    public static class TestWrapper {
        private static final String EXPECTED_DIGEST = "e33b8073c103339b";

        public static class Box {
            int value;
        }

        public static class Outer {
            int value;
            Box child;
        }

        private static int callCount;
        private static int seenFirst;
        private static int seenSecond;
        private static boolean seenIdentity;
        private static Box savedFirst;
        private static Box savedSecond;
        private static Outer savedOuter;

        public static void main(String[] args) throws Exception {
            new Box();
            new Outer();
            PEATestUtils.compileConfiguredTargetsAtLevel4();

            long digest = 0x6A09E667F3BCC909L;
            for (int value : new int[] {3, -7, 101}) {
                resetObservation();
                int result = testOne(false, value);
                assertNoCall(result, value, "testOne no-call");
                digest = mix(digest, result);
                digest = mix(digest, callCount);

                resetObservation();
                result = testOne(true, value);
                Asserts.assertEquals(result, value + 101, "testOne caller sees mutation");
                assertCall(value, -1, true, false, "testOne call");
                Asserts.assertEquals(savedFirst.value, value + 101,
                        "testOne saved identity sees mutation");
                digest = mix(digest, result);
                digest = mix(digest, seenFirst);
                digest = mix(digest, callCount);

                int right = value * 2 + 1;
                resetObservation();
                result = testTwo(false, value, right);
                assertNoCall(result, encode(value, right), "testTwo no-call");
                digest = mix(digest, result);
                digest = mix(digest, callCount);

                resetObservation();
                result = testTwo(true, value, right);
                Asserts.assertEquals(result, encode(value + 201, right + 202),
                        "testTwo caller sees both mutations");
                assertCall(value, right, false, true, "testTwo call");
                Asserts.assertNotEquals(savedFirst, savedSecond,
                        "testTwo receives distinct identities");
                Asserts.assertEquals(savedFirst.value, value + 201,
                        "testTwo first saved value");
                Asserts.assertEquals(savedSecond.value, right + 202,
                        "testTwo second saved value");
                digest = mix(digest, result);
                digest = mix(digest, seenFirst);
                digest = mix(digest, seenSecond);
                digest = mix(digest, seenIdentity ? 1 : 0);

                resetObservation();
                result = testSameTwice(false, value);
                assertNoCall(result, value, "testSameTwice no-call");
                digest = mix(digest, result);
                digest = mix(digest, callCount);

                resetObservation();
                result = testSameTwice(true, value);
                Asserts.assertEquals(result, value + 707,
                        "testSameTwice caller sees ordered writes");
                assertCall(value, value, true, true, "testSameTwice call");
                Asserts.assertSame(savedFirst, savedSecond,
                        "testSameTwice receives one identity twice");
                Asserts.assertEquals(savedFirst.value, value + 707,
                        "testSameTwice saved identity sees both writes");
                digest = mix(digest, result);
                digest = mix(digest, seenFirst);
                digest = mix(digest, seenSecond);
                digest = mix(digest, seenIdentity ? 1 : 0);

                int outerValue = value - 2;
                int childValue = value + 4;
                resetObservation();
                result = testNested(false, outerValue, childValue);
                assertNoCall(result, encode(outerValue, childValue),
                        "testNested no-call");
                digest = mix(digest, result);
                digest = mix(digest, callCount);

                resetObservation();
                result = testNested(true, outerValue, childValue);
                Asserts.assertEquals(result,
                        encode(outerValue + 505, childValue + 606),
                        "testNested caller sees both mutations");
                assertCall(outerValue, childValue, true, true, "testNested call");
                Asserts.assertSame(savedOuter.child, savedFirst,
                        "nested outer and actual argument share the child identity");
                Asserts.assertEquals(savedOuter.value, outerValue + 505,
                        "nested saved outer sees mutation");
                Asserts.assertEquals(savedFirst.value, childValue + 606,
                        "nested saved child sees mutation");
                digest = mix(digest, result);
                digest = mix(digest, seenFirst);
                digest = mix(digest, seenSecond);
                digest = mix(digest, seenIdentity ? 1 : 0);
            }

            String payload = Long.toUnsignedString(digest, 16);
            Asserts.assertEquals(payload, EXPECTED_DIGEST, "exact behavior digest");
            System.out.println("PEA-RESULT:" + payload);
        }

        public static int testOne(boolean call, int value) {
            Box box = new Box();
            box.value = value;
            if (!call) {
                return box.value;
            }
            consumeOne(box);
            return box.value;
        }

        public static int testTwo(boolean call, int left, int right) {
            Box first = new Box();
            Box second = new Box();
            first.value = left;
            second.value = right;
            if (!call) {
                return encode(first.value, second.value);
            }
            consumeTwo(first, second);
            return encode(first.value, second.value);
        }

        public static int testSameTwice(boolean call, int value) {
            Box box = new Box();
            box.value = value;
            if (!call) {
                return box.value;
            }
            consumeSameTwice(box, box);
            return box.value;
        }

        public static int testNested(boolean call, int outerValue, int childValue) {
            Outer outer = new Outer();
            Box child = new Box();
            outer.value = outerValue;
            outer.child = child;
            child.value = childValue;
            if (!call) {
                return encode(outer.value, child.value);
            }
            consumeNested(outer, child);
            return encode(outer.value, child.value);
        }

        public static void consumeOne(Box box) {
            callCount++;
            seenFirst = box.value;
            seenSecond = -1;
            seenIdentity = true;
            savedFirst = box;
            box.value += 101;
        }

        public static void consumeTwo(Box first, Box second) {
            callCount++;
            seenFirst = first.value;
            seenSecond = second.value;
            seenIdentity = first == second;
            savedFirst = first;
            savedSecond = second;
            first.value += 201;
            second.value += 202;
        }

        public static void consumeSameTwice(Box first, Box second) {
            callCount++;
            seenFirst = first.value;
            seenSecond = second.value;
            seenIdentity = first == second;
            savedFirst = first;
            savedSecond = second;
            first.value += 303;
            second.value += 404;
        }

        public static void consumeNested(Outer outer, Box child) {
            callCount++;
            seenFirst = outer.value;
            seenSecond = child.value;
            seenIdentity = outer.child == child;
            savedOuter = outer;
            savedFirst = child;
            outer.value += 505;
            child.value += 606;
        }

        private static void resetObservation() {
            callCount = 0;
            seenFirst = Integer.MIN_VALUE;
            seenSecond = Integer.MIN_VALUE;
            seenIdentity = false;
            savedFirst = null;
            savedSecond = null;
            savedOuter = null;
        }

        private static void assertNoCall(int actual, int expected, String context) {
            Asserts.assertEquals(actual, expected, context + " result");
            Asserts.assertEquals(callCount, 0, context + " callee count");
            Asserts.assertNull(savedFirst, context + " first saved argument");
            Asserts.assertNull(savedSecond, context + " second saved argument");
            Asserts.assertNull(savedOuter, context + " saved outer argument");
        }

        private static void assertCall(int first, int second, boolean identity,
                                       boolean hasSecond, String context) {
            Asserts.assertEquals(callCount, 1, context + " callee count");
            Asserts.assertEquals(seenFirst, first, context + " first entry value");
            if (hasSecond) {
                Asserts.assertEquals(seenSecond, second, context + " second entry value");
            }
            Asserts.assertEquals(seenIdentity, identity, context + " entry identity");
            Asserts.assertNotNull(savedFirst, context + " first saved argument");
        }

        private static int encode(int first, int second) {
            return first * 1009 ^ second;
        }

        private static long mix(long accumulator, long value) {
            return Long.rotateLeft(accumulator ^ value, 13) * 0x9E3779B97F4A7C15L;
        }
    }
}
