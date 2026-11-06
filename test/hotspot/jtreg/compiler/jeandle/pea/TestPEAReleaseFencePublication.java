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
 * @summary PEA materializes and initializes an object before a release fence
 *          that precedes publication to another thread
 * @library /test/lib /
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.pea.TestPEAReleaseFencePublication
 */

package compiler.jeandle.pea;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Method;
import java.util.List;

import jdk.test.lib.Asserts;

public class TestPEAReleaseFencePublication {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestPEAReleaseFencePublication$TestWrapper";
    private static final String RELEASE_FENCE = "fence release";
    private static final String INT_STORE = "store atomic i32";
    private static final String LONG_STORE = "store atomic i64";
    private static final String REF_STORE = PEATestUtils.referenceStore();

    public static void main(String[] args) throws Exception {
        Method target = TestWrapper.class.getMethod("publishAcrossReleaseFence",
                int.class, long.class, Object.class);
        Method publish = TestWrapper.class.getMethod("publish", TestWrapper.Box.class);

        try (PEATestUtils.RunResult run = PEATestUtils.shapeRun(WRAPPER, target)
                .dontinline(publish).run()) {
            assertShape(run, target, publish);
        }

        PEATestUtils.behaviorRun(WRAPPER, target)
                .dontinline(publish).runPEAOnOffEquivalent();
    }

    private static void assertShape(PEATestUtils.RunResult run, Method target,
                                    Method publish) throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.PEARound first = report.round(0);
        PEATestUtils.IRBody before = first.before();
        PEATestUtils.IRBody after = report.finalAfter();
        PEATestUtils.AllocationKey box = new PEATestUtils.AllocationKey(
                PEATestUtils.AllocationKind.INSTANCE, 0);

        Asserts.assertEquals(run.frontendIR(target).allocations().stream()
                        .map(PEATestUtils.AllocationSite::key).toList(),
                List.of(box), target + ": frontend Box allocation");
        Asserts.assertEquals(before.allocationBCIs(), List.of(0),
                target + ": post-inlining Box allocation");
        Asserts.assertEquals(after.allocationBCIs(), List.of(0),
                target + ": release fence retains the materialized allocation");
        Asserts.assertEquals(first.effectCount("Materialize"), 1L,
                target + ": release fence materializes the live Box exactly once");
        Asserts.assertEquals(first.effectCount("EliminateStore"), 3L,
                target + ": all three Box fields enter virtual state before replay");
        report.assertFinalTransformIdle();

        String callee = PEATestUtils.MethodId.of(publish).llvmFunctionName();
        after.assertLineCount(RELEASE_FENCE, 1);
        after.assertLineCount(INT_STORE, 1);
        after.assertLineCount(LONG_STORE, 1);
        after.assertLineCount(REF_STORE, 1);
        after.assertOccurrenceCount(callee, 1);

        // This is the regression oracle.  Delaying any replay until the
        // publication call would place it after the release fence and permit
        // another thread to observe a default field value.
        after.assertBefore(INT_STORE, 0, RELEASE_FENCE, 0);
        after.assertBefore(LONG_STORE, 0, RELEASE_FENCE, 0);
        after.assertBefore(REF_STORE, 0, RELEASE_FENCE, 0);
        after.assertBefore(RELEASE_FENCE, 0, callee, 0);

        PEATestUtils.IRBody finalIR = run.finalIR(target);
        Asserts.assertEquals(finalIR.loweredAllocCount(), 1,
                target + ": materialized Box allocation survives lowering");
        finalIR.assertPresent(RELEASE_FENCE);
    }

    public static class TestWrapper {
        private static final int ITERATIONS = 100_000;
        private static final Object[] PAYLOADS = {new Object(), new Object()};
        private static final VarHandle PUBLISHED;

        static {
            try {
                PUBLISHED = MethodHandles.lookup().findStaticVarHandle(
                        TestWrapper.class, "published", Box.class);
            } catch (ReflectiveOperationException exception) {
                throw new ExceptionInInitializerError(exception);
            }
        }

        public static class Box {
            int sequence;
            long mirror;
            Object payload;
        }

        private static Box published;
        private static volatile Throwable producerFailure;

        public static void main(String[] args) throws Exception {
            new Box();
            publish(null);
            // Resolve the public fence wrappers before exact compilation so
            // the target contains the real intrinsics instead of an
            // unresolved-call deoptimization stub.
            VarHandle.releaseFence();
            VarHandle.acquireFence();
            PEATestUtils.compileConfiguredTargetsAtLevel4();

            long digest = runPublicationStress();
            System.out.println("PEA-RESULT:" + Long.toUnsignedString(digest, 16));
        }

        public static void publishAcrossReleaseFence(int sequence, long mirror,
                                                     Object payload) {
            Box box = new Box();
            box.sequence = sequence;
            box.mirror = mirror;
            box.payload = payload;
            VarHandle.releaseFence();
            publish(box);
        }

        public static void publish(Box box) {
            published = box;
        }

        private static long runPublicationStress() throws Exception {
            published = null;
            producerFailure = null;
            Thread producer = new Thread(() -> {
                try {
                    for (int sequence = 1; sequence <= ITERATIONS; sequence++) {
                        while ((Box) PUBLISHED.getOpaque() != null) {
                            Thread.onSpinWait();
                        }
                        long mirror = expectedMirror(sequence);
                        publishAcrossReleaseFence(sequence, mirror,
                                PAYLOADS[sequence & 1]);
                    }
                } catch (Throwable throwable) {
                    producerFailure = throwable;
                }
            }, "pea-release-fence-producer");
            producer.setDaemon(true);
            producer.start();

            long digest = 0x6a09e667f3bcc909L;
            for (int expected = 1; expected <= ITERATIONS; expected++) {
                Box box;
                while ((box = (Box) PUBLISHED.getOpaque()) == null) {
                    Throwable failure = producerFailure;
                    if (failure != null) {
                        throw new AssertionError("producer failed", failure);
                    }
                    Thread.onSpinWait();
                }
                VarHandle.acquireFence();

                Asserts.assertEquals(box.sequence, expected,
                        "published sequence at iteration " + expected);
                Asserts.assertEquals(box.mirror, expectedMirror(expected),
                        "published long field at iteration " + expected);
                Asserts.assertSame(box.payload, PAYLOADS[expected & 1],
                        "published reference field at iteration " + expected);
                digest = Long.rotateLeft(digest ^ box.sequence, 9) + box.mirror;
                PUBLISHED.setOpaque(null);
            }

            producer.join(30_000);
            Asserts.assertFalse(producer.isAlive(), "producer must finish");
            if (producerFailure != null) {
                throw new AssertionError("producer failed", producerFailure);
            }
            return digest;
        }

        private static long expectedMirror(int sequence) {
            return ~((long) sequence * 0x9e3779b97f4a7c15L);
        }
    }
}
