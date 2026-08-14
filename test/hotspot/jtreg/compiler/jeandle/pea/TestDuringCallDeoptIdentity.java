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
 * @summary PEA preserves the identity of materialized call arguments while
 *          their caller is deoptimized during the non-inlined call
 * @library /test/lib /
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      -XX:-UseCompressedOops -XX:-UseCompressedClassPointers
 *      compiler.jeandle.pea.TestDuringCallDeoptIdentity
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;

import jdk.test.lib.Asserts;

public class TestDuringCallDeoptIdentity {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestDuringCallDeoptIdentity$TestWrapper";

    public static void main(String[] args) throws Exception {
        Method target = TestWrapper.class.getMethod(
                "testDuringCall", TestWrapper.External.class,
                int.class, int.class);
        Method callee = TestWrapper.class.getDeclaredMethod(
                "mutateDuringCall", TestWrapper.Payload.class,
                TestWrapper.Payload.class, TestWrapper.Payload.class,
                TestWrapper.External.class, int.class);
        Method requestCallerDeopt =
                TestWrapper.class.getDeclaredMethod("requestCallerDeopt");

        runBuilder(false, target, callee, requestCallerDeopt)
                .runPEAOnOffEquivalent();

        try (PEATestUtils.RunResult run =
                runBuilder(true, target, callee, requestCallerDeopt).run()) {
            assertShape(run, target, callee);
        }
    }

    private static PEATestUtils.RunBuilder runBuilder(
            boolean shape, Method target, Method callee,
            Method requestCallerDeopt) {
        PEATestUtils.RunBuilder builder = shape
                ? PEATestUtils.shapeRun(WRAPPER, target)
                : PEATestUtils.behaviorRun(WRAPPER, target);
        return builder.dontinline(callee).dontinline(requestCallerDeopt);
    }

    private static void assertShape(
            PEATestUtils.RunResult run, Method target, Method callee)
            throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        report.assertFinalTransformIdle();
        PEATestUtils.PEARound first = report.round(0);
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();
        List<Integer> sourceBCIs = before.allocationBCIs();

        Asserts.assertEquals(sourceBCIs.size(), 3,
                target + ": p, q, and nested child enter PEA");
        Asserts.assertEquals(new HashSet<>(sourceBCIs).size(), 3,
                target + ": every logical object has a distinct source allocation");
        Asserts.assertEquals(first.neverEscapes(), 0,
                target + ": no call argument remains virtual on every path");
        Asserts.assertEquals(first.partiallyEscapes(), 3,
                target + ": the three-object graph materializes only on call paths");
        Asserts.assertEquals(first.alwaysEscapes(), 0,
                target + ": the no-call path keeps all allocations virtual");
        Asserts.assertEquals(after.allocationBCIs(), sourceBCIs,
                target + ": every materialized object reuses one source OrigAlloc");
        Asserts.assertEquals(after.peaAllocCount(), 3,
                target + ": exact retained PEA OrigAlloc count");
        Asserts.assertEquals(run.finalIR(target).loweredAllocCount(), 3,
                target + ": exact retained lowered OrigAlloc count");

        for (PEATestUtils.PEARound round : report.rounds()) {
            Asserts.assertEquals(round.effectCount("Materialize"), 3L,
                    target + ": one materialization per distinct object in round "
                            + round.iteration());
            for (int objectId = 0; objectId < 3; objectId++) {
                Asserts.assertEquals(round.effectCount(
                        "Materialize", "[VO=" + objectId + "]"), 1L,
                        target + ": ObjectID " + objectId
                                + " materializes once in round " + round.iteration());
            }
        }

        String calleeName = PEATestUtils.MethodId.of(callee).llvmFunctionName();
        Asserts.assertEquals(before.occurrenceCount("@\"" + calleeName + "\"("), 1,
                target + ": exact frontend non-inlined call");
        Asserts.assertEquals(after.occurrenceCount("@\"" + calleeName + "\"("), 1,
                target + ": exact optimized non-inlined call");
        PEATestUtils.DeoptBundle source =
                before.deoptBundleAtCall(calleeName, 0);
        PEATestUtils.DeoptBundle bundle =
                after.deoptBundleAtCall(calleeName, 0);
        Asserts.assertEquals(source.virtualObjects().size(), 0,
                target + ": frontend call has no PEA descriptors");
        Asserts.assertEquals(bundle.virtualObjects().size(), 0,
                target + ": materialized arguments have no virtual descriptors");
        Asserts.assertEquals(bundle.rootScope().bci(), source.rootScope().bci(),
                target + ": materialization preserves the call BCI");
        Asserts.assertEquals(bundle.rootScope().duplicateBCI(),
                bundle.rootScope().bci(),
                target + ": call BCI remains duplicated exactly");
        Asserts.assertEquals(bundle.scopes().size(), 1,
                target + ": one exact active caller scope");

        PEATestUtils.IRBlock callBlock =
                after.blockContaining(calleeName, 0);
        callBlock.assertAbsent("jeandle.new_instance");
        after.assertAbsent("poison");
    }

    public static class TestWrapper {
        private static final String EXPECTED_DIGEST = "35d7b5af4430947";
        private static final Method DEOPT_TARGET = target();

        public static class Payload {
            int intValue;
            long longValue;
            double doubleValue;
            Object reference;
            Payload child;
        }

        public static class External {
            int marker;
        }

        private static boolean deoptStarted;
        private static int deoptRequests;
        private static int calleeEntries;
        private static int mutationPasses;
        private static int guardSkips;
        private static int callerContinuations;
        private static Payload savedP;
        private static Payload savedSame;
        private static Payload savedQ;
        private static Payload savedChild;
        private static External savedExternal;

        public static void main(String[] args) throws Exception {
            new Payload();
            new External();
            PEATestUtils.compileConfiguredTargetsAtLevel4();

            long digest = 0x6A09E667F3BCC909L;

            External noCallExternal = external(41);
            long noCall = testDuringCall(noCallExternal, 5, -1);
            Asserts.assertEquals(noCall, initialResult(5),
                    "no-call path retains the initialized graph");
            Asserts.assertEquals(calleeEntries, 0,
                    "no-call path does not enter the callee");
            Asserts.assertEquals(mutationPasses, 0,
                    "no-call path does not mutate");
            Asserts.assertEquals(callerContinuations, 0,
                    "no-call path has no call continuation");
            digest = mix(digest, noCall);

            External normalExternal = external(43);
            long normal = testDuringCall(normalExternal, 11, 0);
            Asserts.assertEquals(normal, finalResult(11),
                    "normal call observes callee and caller mutations");
            assertSavedIdentity(normalExternal, "normal call");
            Asserts.assertEquals(deoptRequests, 0,
                    "normal call does not request deoptimization");
            Asserts.assertEquals(mutationPasses, 1,
                    "normal call mutates once");
            Asserts.assertEquals(callerContinuations, 1,
                    "normal call continues once");
            digest = mix(digest, normal);

            External deoptExternal = external(47);
            long deopt = testDuringCall(deoptExternal, 23, 1);
            Asserts.assertEquals(deopt, finalResult(23),
                    "deoptimized caller observes every mutation");
            assertSavedIdentity(deoptExternal, "deoptimized call");
            Asserts.assertEquals(deoptRequests, 1,
                    "one exact caller deoptimization request");
            Asserts.assertEquals(mutationPasses, 2,
                    "the one-shot guard prevents repeated mutation");
            Asserts.assertEquals(guardSkips, 0,
                    "the deoptimized caller resumes at the call continuation");
            Asserts.assertEquals(callerContinuations, 2,
                    "the deoptimized caller continues exactly once");
            Asserts.assertEquals(calleeEntries, 2,
                    "normal and deoptimizing calls each enter once");
            digest = mix(digest, deopt);
            digest = mix(digest, deoptRequests);
            digest = mix(digest, mutationPasses);
            digest = mix(digest, guardSkips);
            digest = mix(digest, callerContinuations);

            String payload = Long.toUnsignedString(digest, 16);
            Asserts.assertEquals(payload, EXPECTED_DIGEST,
                    "exact behavior digest");
            System.out.println("PEA-RESULT:" + payload);
        }

        public static long testDuringCall(
                External external, int seed, int mode) {
            Payload p = new Payload();
            Payload q = new Payload();
            Payload child = new Payload();

            p.intValue = seed + 1;
            p.longValue = 1000L + seed;
            p.doubleValue = seed + 0.5;
            p.reference = external;
            q.intValue = seed + 2;
            q.longValue = 2000L + seed;
            q.doubleValue = seed + 1.5;
            q.reference = external;
            child.intValue = seed + 3;
            child.longValue = 3000L + seed;
            child.doubleValue = seed + 2.5;
            child.reference = external;
            p.child = child;

            if (mode < 0) {
                return seed * 1000003L + 0x102030405L;
            }

            mutateDuringCall(p, p, q, external, mode);
            callerContinuations++;

            if (p != savedP || p != savedSame || q != savedQ
                    || child != savedChild || external != savedExternal
                    || p.child != child
                    || p.intValue != seed + 29
                    || p.longValue != seed + 1208L
                    || p.doubleValue != seed + 4.25
                    || p.reference != child
                    || q.intValue != seed + 34
                    || q.longValue != seed + 2212L
                    || q.doubleValue != seed + 5.75
                    || q.reference != p
                    || child.intValue != seed + 39
                    || child.longValue != seed + 3216L
                    || child.doubleValue != seed + 7.25
                    || child.reference != external) {
                return Long.MIN_VALUE + 1;
            }

            p.intValue += 23;
            p.longValue += 113;
            p.doubleValue += 3.25;
            p.reference = external;
            q.intValue += 25;
            q.longValue += 127;
            q.doubleValue += 3.5;
            q.reference = child;
            child.intValue += 27;
            child.longValue += 131;
            child.doubleValue += 3.75;
            child.reference = p;

            if (p.intValue != seed + 52
                    || p.longValue != seed + 1321L
                    || p.doubleValue != seed + 7.5
                    || p.reference != external
                    || q.intValue != seed + 59
                    || q.longValue != seed + 2339L
                    || q.doubleValue != seed + 9.25
                    || q.reference != child
                    || child.intValue != seed + 66
                    || child.longValue != seed + 3347L
                    || child.doubleValue != seed + 11.0
                    || child.reference != p
                    || p.child != child) {
                return Long.MIN_VALUE + 2;
            }
            return seed * 2000003L + 0x506070809L;
        }

        private static void mutateDuringCall(
                Payload first, Payload same, Payload q,
                External external, int mode) {
            calleeEntries++;
            if (mode == 1 && deoptStarted) {
                guardSkips++;
                return;
            }
            if (first != same || first == q || first.child == null
                    || first.reference != external
                    || q.reference != external
                    || first.child.reference != external) {
                throw new AssertionError("initial call identity");
            }

            if (mode == 1) {
                deoptStarted = true;
            }
            savedP = first;
            savedSame = same;
            savedQ = q;
            savedChild = first.child;
            savedExternal = external;
            mutationPasses++;

            first.intValue += 11;
            first.longValue += 101;
            first.doubleValue += 1.25;
            first.reference = q;
            q.intValue += 13;
            q.longValue += 103;
            q.doubleValue += 1.5;
            q.reference = first.child;
            first.child.intValue += 15;
            first.child.longValue += 105;
            first.child.doubleValue += 1.75;
            first.child.reference = first;

            if (mode == 1) {
                requestCallerDeopt();
            }

            if (first != same || first.child != savedChild
                    || first.intValue != q.intValue - 3
                    || first.longValue != q.longValue - 1002
                    || first.doubleValue != q.doubleValue - 1.25
                    || first.reference != q
                    || first.child.intValue != q.intValue + 3
                    || first.child.longValue != q.longValue + 1002
                    || first.child.doubleValue != q.doubleValue + 1.25
                    || q.reference != first.child
                    || first.child.reference != first) {
                throw new AssertionError("pre-deopt mutation identity");
            }

            first.intValue += 17;
            first.longValue += 107;
            first.doubleValue += 2.5;
            first.reference = first.child;
            q.intValue += 19;
            q.longValue += 109;
            q.doubleValue += 2.75;
            q.reference = first;
            first.child.intValue += 21;
            first.child.longValue += 111;
            first.child.doubleValue += 3.0;
            first.child.reference = external;
        }

        private static void requestCallerDeopt() {
            deoptRequests++;
            if (deoptRequests != 1) {
                throw new AssertionError("deopt helper reexecuted");
            }
            PEATestUtils.ActiveFrameDeoptEvidence evidence =
                    PEATestUtils.deoptimizeActiveFrame(DEOPT_TARGET, 3);
            if (!evidence.frameDeoptimized()
                    || evidence.compilationLevel() != 4
                    || evidence.markedNMethods() != 1) {
                throw new AssertionError("exact active caller deopt evidence");
            }
        }

        private static External external(int marker) {
            External external = new External();
            external.marker = marker;
            return external;
        }

        private static void assertSavedIdentity(
                External external, String context) {
            Asserts.assertSame(savedP, savedSame,
                    context + ": repeated argument identity");
            Asserts.assertNotEquals(savedP, savedQ,
                    context + ": distinct argument identities");
            Asserts.assertSame(savedP.child, savedChild,
                    context + ": nested child identity");
            Asserts.assertSame(savedExternal, external,
                    context + ": external argument identity");
        }

        private static long initialResult(int seed) {
            return seed * 1000003L + 0x102030405L;
        }

        private static long finalResult(int seed) {
            return seed * 2000003L + 0x506070809L;
        }

        private static long mix(long accumulator, long value) {
            return Long.rotateLeft(accumulator ^ value, 13)
                    * 0x9E3779B97F4A7C15L;
        }

        private static Method target() {
            try {
                return TestWrapper.class.getMethod(
                        "testDuringCall", External.class,
                        int.class, int.class);
            } catch (ReflectiveOperationException failure) {
                throw new ExceptionInInitializerError(failure);
            }
        }
    }
}
