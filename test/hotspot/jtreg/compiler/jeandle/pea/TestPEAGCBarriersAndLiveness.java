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
 * @summary PEA GC barriers and virtual-object oop liveness across full GC and deopt
 * @requires vm.gc.Serial & vm.gc.G1
 * @modules java.base/jdk.internal.misc
 * @library /test/lib /
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox
 *        compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      -XX:-UseCompressedOops -XX:-UseCompressedClassPointers
 *      compiler.jeandle.pea.TestPEAGCBarriersAndLiveness
 */

package compiler.jeandle.pea;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import jdk.internal.misc.Unsafe;
import jdk.test.lib.Asserts;

public class TestPEAGCBarriersAndLiveness {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestPEAGCBarriersAndLiveness$TestWrapper";
    private static final Unsafe UNSAFE = Unsafe.getUnsafe();

    public static void main(String[] args) throws Exception {
        Method external = TestWrapper.class.getMethod(
                "testExternal", TestWrapper.Ref.class);
        Method sibling = TestWrapper.class.getMethod(
                "testMaterializedSibling", TestWrapper.Ref.class, int.class);
        Method checkpoint = TestWrapper.class.getDeclaredMethod(
                "checkpoint", int.class, int.class);
        Method requestDeopt = TestWrapper.class.getDeclaredMethod("requestDeopt");
        Method verifyExternal = TestWrapper.class.getDeclaredMethod(
                "verifyExternal", TestWrapper.Holder.class, TestWrapper.Ref.class);
        Method verifySibling = TestWrapper.class.getDeclaredMethod(
                "verifySibling", TestWrapper.Holder.class, TestWrapper.Ref.class,
                int.class);
        Method mix = TestWrapper.class.getDeclaredMethod(
                "mix", long.class, long.class);
        Method[] targets = {external, sibling};
        Method[] inline = {verifyExternal, verifySibling, mix};

        for (String collector : List.of("-XX:+UseSerialGC", "-XX:+UseG1GC")) {
            behaviorRun(targets, checkpoint, requestDeopt, inline, collector)
                    .runPEAOnOffEquivalent();
            try (PEATestUtils.RunResult run =
                    shapeRun(targets, checkpoint, requestDeopt, inline, collector).run()) {
                assertExternalShape(run, external, checkpoint);
                assertSiblingShape(run, sibling, checkpoint, collector);
            }
        }
    }

    private static PEATestUtils.RunBuilder behaviorRun(
            Method[] targets, Method checkpoint, Method requestDeopt,
            Method[] inline, String collector) {
        PEATestUtils.RunBuilder builder =
                PEATestUtils.behaviorRun(WRAPPER, targets)
                        .dontinline(checkpoint)
                        .dontinline(requestDeopt)
                        .extraFlags(collector);
        for (Method helper : inline) {
            builder.inline(helper);
        }
        return builder;
    }

    private static PEATestUtils.RunBuilder shapeRun(
            Method[] targets, Method checkpoint, Method requestDeopt,
            Method[] inline, String collector) {
        PEATestUtils.RunBuilder builder =
                PEATestUtils.shapeRun(WRAPPER, targets)
                        .dontinline(checkpoint)
                        .dontinline(requestDeopt)
                        .extraFlags(collector);
        for (Method helper : inline) {
            builder.inline(helper);
        }
        return builder;
    }

    private static void assertExternalShape(
            PEATestUtils.RunResult run, Method target, Method checkpoint)
            throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        assertStructuralSoundness(run, target, report);
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();
        Asserts.assertEquals(before.peaAllocCount(), 2,
                "external case starts with holder and Object[] allocations");
        Asserts.assertEquals(after.peaAllocCount(), 0,
                "never-escaping holder and Object[] allocations are eliminated");
        Asserts.assertEquals(run.finalIR(target).loweredAllocCount(), 0,
                "no lowered allocation survives for the external case");
        run.finalIR(target).assertAbsent("store atomic ptr addrspace(1)");
        run.finalIR(target).assertAbsent("jeandle.pre_barrier.exit: ; preds =");
        run.finalIR(target).assertAbsent("jeandle.post_barrier.exit: ; preds =");
        Asserts.assertEquals(postBarrierCount(after), 0L,
                "post barriers for stores into virtual objects are eliminated");
        assertVirtualGraphAtCheckpoint(after, checkpoint, false);
    }

    private static void assertSiblingShape(
            PEATestUtils.RunResult run, Method target, Method checkpoint,
            String collector)
            throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        assertStructuralSoundness(run, target, report);
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();
        List<PEATestUtils.AllocationSite> original = before.allocations();
        Asserts.assertEquals(original.size(), 3,
                "sibling case starts with holder, sibling, and Object[] allocations");
        Asserts.assertEquals(new HashSet<>(before.allocationBCIs()).size(), 3,
                "the three allocations have distinct source identities");
        Asserts.assertEquals(after.peaAllocCount(), 1,
                "only the published sibling OrigAlloc survives PEA");
        Asserts.assertEquals(run.finalIR(target).loweredAllocCount(), 1,
                "only the published sibling allocation survives lowering");
        Asserts.assertEquals(postBarrierCount(after), 0L,
                "post barriers for the remaining virtual stores are eliminated");
        assertLoweredPublication(run.finalIR(target), collector);
        assertVirtualGraphAtCheckpoint(after, checkpoint, true);
    }

    private static void assertLoweredPublication(
            PEATestUtils.IRBody body, String collector) {
        body.assertLineCount("store atomic i8 0", 1);
        long sequentialPublications = body.lines().stream()
                .filter(line -> line.contains("store atomic ptr addrspace(1)"))
                .filter(line -> line.contains("seq_cst"))
                .count();
        Asserts.assertEquals(sequentialPublications, 1L,
                "one exact real sibling publication");

        if (collector.equals("-XX:+UseSerialGC")) {
            body.assertLineCount("store atomic ptr addrspace(1)", 1);
            body.assertAbsent("jeandle.pre_barrier.exit: ; preds =");
            body.assertAbsent("jeandle.post_barrier.exit: ; preds =");
            body.assertBefore("store atomic ptr addrspace(1)", 0,
                    "store atomic i8 0", 0);
        } else if (collector.equals("-XX:+UseG1GC")) {
            body.assertLineCount("store atomic ptr addrspace(1)", 2);
            body.assertBetween(
                    "jeandle.pre_barrier.exit: ; preds =", 0,
                    "store atomic ptr addrspace(1)", 1,
                    "jeandle.post_barrier.exit: ; preds =", 0);
            body.assertBetween(
                    "jeandle.pre_barrier.exit: ; preds =", 0,
                    "store atomic i8 0", 0,
                    "jeandle.post_barrier.exit: ; preds =", 0);
        } else {
            throw new IllegalArgumentException("unexpected collector " + collector);
        }
    }

    private static long postBarrierCount(PEATestUtils.IRBody body) {
        return body.lines().stream()
                .filter(line -> line.contains("@jeandle.post_barrier"))
                .count();
    }

    private static void assertStructuralSoundness(
            PEATestUtils.RunResult run, Method target,
            PEATestUtils.PEAReport report) throws Exception {
        for (PEATestUtils.PEARound round : report.rounds()) {
            PEATestUtils.assertStructuralSoundness(
                    round.before(), target + " PEA round " + round.iteration() + " before");
            PEATestUtils.assertStructuralSoundness(
                    round.after(), target + " PEA round " + round.iteration() + " after");
        }
        PEATestUtils.assertStructuralSoundness(
                run.finalIR(target), target + " final IR");
    }

    private static void assertVirtualGraphAtCheckpoint(
            PEATestUtils.IRBody body, Method checkpoint, boolean siblingCase)
            throws Exception {
        String callee = PEATestUtils.MethodId.of(checkpoint).llvmFunctionName();
        PEATestUtils.DeoptBundle bundle = body.deoptBundleAtCall(callee, 0);
        bundle.assertVirtualObjectIds(0, 1);

        int holderRef = offset(TestWrapper.Holder.class, "ref");
        int holderExternal = offset(TestWrapper.Holder.class, "external");
        int holderRefs = offset(TestWrapper.Holder.class, "refs");
        Set<Integer> holderOffsets =
                Set.of(holderRef, holderExternal, holderRefs);
        PEATestUtils.VirtualObjectDescriptor holder = null;
        PEATestUtils.VirtualObjectDescriptor array = null;
        for (PEATestUtils.VirtualObjectDescriptor descriptor
                : bundle.virtualObjects().values()) {
            if (descriptor.kind() == PEATestUtils.DescriptorKind.ARRAY) {
                array = descriptor;
            } else if (descriptor.fields().keySet().equals(holderOffsets)) {
                holder = descriptor;
            }
        }
        Asserts.assertNotNull(holder, "holder descriptor");
        Asserts.assertNotNull(array, "Object[] descriptor");
        bundle.assertVORef(holder.id(), holderRefs, array.id());
        assertMaterializedOop(holder.fields().get(holderRef), "holder.ref");
        assertMaterializedOop(holder.fields().get(holderExternal),
                "holder.external");

        int base = Unsafe.ARRAY_OBJECT_BASE_OFFSET;
        int scale = Unsafe.ARRAY_OBJECT_INDEX_SCALE;
        Asserts.assertEquals(array.elements().keySet(),
                Set.of(base, base + scale, base + 2 * scale),
                "exact Object[] element offsets");
        assertMaterializedOop(array.elements().get(base), "refs[0]");
        assertMaterializedOop(array.elements().get(base + scale), "refs[1]");
        assertMaterializedOop(array.elements().get(base + 2 * scale), "refs[2]");
    }

    private static void assertMaterializedOop(
            PEATestUtils.VirtualObjectEntry entry, String detail) {
        Asserts.assertNotNull(entry, detail + " entry");
        Asserts.assertEquals(entry.basicType(),
                PEATestUtils.DeoptBasicType.OBJECT, detail + " basic type");
        Asserts.assertEquals(entry.value().kind(),
                PEATestUtils.DeoptValueKind.MATERIALIZED_OOP,
                detail + " is a live materialized oop");
        Asserts.assertTrue(entry.value().operand().startsWith("ptr "),
                detail + " has a typed oop operand");
    }

    private static int offset(Class<?> holder, String name) throws Exception {
        Field field = holder.getDeclaredField(name);
        return Math.toIntExact(UNSAFE.objectFieldOffset(field));
    }

    public static class TestWrapper {
        private static final int WAIT_FIRST_GC = 1;
        private static final int FIRST_GC_DONE = 2;
        private static final int WAIT_SECOND_GC = 3;
        private static final int SECOND_GC_DONE = 4;
        private static final long WAIT_NANOS = TimeUnit.SECONDS.toNanos(30);

        private static final Method EXTERNAL_TARGET =
                target("testExternal", Ref.class);
        private static final Method SIBLING_TARGET =
                target("testMaterializedSibling", Ref.class, int.class);

        private static volatile int phase;
        private static volatile Throwable workerFailure;
        private static volatile long workerResult;
        private static volatile Ref publishedSibling;
        private static Method deoptTarget;

        public static void main(String[] args) throws Exception {
            new Holder();
            new Ref();
            PEATestUtils.compileConfiguredTargetsAtLevel4();

            Ref external = new Ref();
            external.payload = 0x13579BDF;
            long externalResult = runCase(
                    () -> testExternal(external), EXTERNAL_TARGET);
            long expectedExternal = mix(0x13579BDFL, 0x13579BDFL);
            if (externalResult != expectedExternal
                    || external.payload != 0x13579BDF) {
                throw new AssertionError("external oop did not survive GC/deopt");
            }

            int siblingPayload = 0x2468ACE;
            publishedSibling = null;
            long siblingResult = runCase(
                    () -> testMaterializedSibling(external, siblingPayload),
                    SIBLING_TARGET);
            Ref sibling = publishedSibling;
            long oneSiblingRead = mix(siblingPayload, 0x13579BDFL);
            long expectedSibling = mix(oneSiblingRead, oneSiblingRead);
            if (sibling == null || sibling.payload != siblingPayload
                    || siblingResult != expectedSibling
                    || external.payload != 0x13579BDF) {
                throw new AssertionError("materialized sibling did not survive GC/deopt");
            }

            long result = mix(externalResult, siblingResult);
            System.out.println("PEA-RESULT:" + Long.toUnsignedString(result, 16));
        }

        private static long runCase(CheckedLongSupplier action, Method target)
                throws Exception {
            phase = 0;
            workerFailure = null;
            workerResult = 0;
            deoptTarget = target;
            Thread worker = new Thread(() -> {
                try {
                    workerResult = action.getAsLong();
                } catch (Throwable failure) {
                    workerFailure = failure;
                }
            }, "pea-gc-liveness-worker");
            worker.start();

            awaitPhase(WAIT_FIRST_GC);
            jdk.test.whitebox.WhiteBox.getWhiteBox().fullGC();
            phase = FIRST_GC_DONE;
            awaitPhase(WAIT_SECOND_GC);
            jdk.test.whitebox.WhiteBox.getWhiteBox().fullGC();
            phase = SECOND_GC_DONE;

            worker.join(TimeUnit.NANOSECONDS.toMillis(WAIT_NANOS));
            if (worker.isAlive()) {
                throw new AssertionError("timed out joining GC liveness worker");
            }
            if (workerFailure != null) {
                throw new AssertionError("GC liveness worker failed", workerFailure);
            }
            return workerResult;
        }

        public static long testExternal(Ref external) {
            Holder holder = new Holder();
            Object[] refs = new Object[3];
            holder.ref = external;
            holder.external = external;
            holder.refs = refs;
            refs[0] = external;
            refs[1] = external;
            refs[2] = external;

            checkpoint(WAIT_FIRST_GC, FIRST_GC_DONE);
            requestDeopt();
            long first = verifyExternal(holder, external);
            checkpoint(WAIT_SECOND_GC, SECOND_GC_DONE);
            long second = verifyExternal(holder, external);
            return mix(first, second);
        }

        public static long testMaterializedSibling(Ref external, int payload) {
            Holder holder = new Holder();
            Ref sibling = new Ref();
            Object[] refs = new Object[3];
            sibling.payload = payload;
            publishedSibling = sibling;
            holder.ref = sibling;
            holder.external = external;
            holder.refs = refs;
            refs[0] = external;
            refs[1] = sibling;
            refs[2] = sibling;

            checkpoint(WAIT_FIRST_GC, FIRST_GC_DONE);
            requestDeopt();
            long first = verifySibling(holder, external, payload);
            checkpoint(WAIT_SECOND_GC, SECOND_GC_DONE);
            long second = verifySibling(holder, external, payload);
            return mix(first, second);
        }

        private static long verifyExternal(Holder holder, Ref external) {
            Object[] refs = holder.refs;
            if (holder.ref != external || holder.external != external
                    || refs.length != 3
                    || refs[0] != external || refs[1] != external
                    || refs[2] != external || external.payload != 0x13579BDF) {
                throw new AssertionError("external identity/alias/payload mismatch");
            }
            return external.payload;
        }

        private static long verifySibling(
                Holder holder, Ref external, int payload) {
            Ref sibling = publishedSibling;
            Object[] refs = holder.refs;
            if (sibling == null || holder.ref != sibling
                    || holder.external != external || refs.length != 3
                    || refs[0] != external || refs[1] != sibling
                    || refs[2] != sibling || refs[1] != refs[2]
                    || sibling.payload != payload
                    || external.payload != 0x13579BDF) {
                throw new AssertionError("sibling identity/alias/payload mismatch");
            }
            return mix(sibling.payload, external.payload);
        }

        private static void checkpoint(int waiting, int released) {
            phase = waiting;
            long deadline = System.nanoTime() + WAIT_NANOS;
            while (phase != released) {
                if (System.nanoTime() - deadline >= 0) {
                    throw new AssertionError("timed out at GC checkpoint " + waiting);
                }
                Thread.onSpinWait();
            }
        }

        private static void awaitPhase(int expected) {
            long deadline = System.nanoTime() + WAIT_NANOS;
            while (phase != expected) {
                if (workerFailure != null) {
                    throw new AssertionError(
                            "worker failed before phase " + expected, workerFailure);
                }
                if (System.nanoTime() - deadline >= 0) {
                    throw new AssertionError("timed out waiting for phase " + expected);
                }
                Thread.onSpinWait();
            }
        }

        private static void requestDeopt() {
            PEATestUtils.ActiveFrameDeoptEvidence evidence =
                    PEATestUtils.deoptimizeActiveFrame(deoptTarget, 2);
            if (!evidence.frameDeoptimized()
                    || evidence.compilationLevel() != 4
                    || evidence.markedNMethods() != 1) {
                throw new AssertionError("exact active-frame deopt evidence");
            }
        }

        private static Method target(String name, Class<?>... parameterTypes) {
            try {
                return TestWrapper.class.getMethod(name, parameterTypes);
            } catch (ReflectiveOperationException failure) {
                throw new ExceptionInInitializerError(failure);
            }
        }

        private static long mix(long accumulator, long value) {
            return Long.rotateLeft(accumulator ^ value, 19)
                    * 0x9E3779B97F4A7C15L;
        }

        @FunctionalInterface
        private interface CheckedLongSupplier {
            long getAsLong() throws Exception;
        }

        public static class Holder {
            public Object ref;
            public Object external;
            public Object[] refs;
        }

        public static class Ref {
            public int payload;
        }
    }
}
