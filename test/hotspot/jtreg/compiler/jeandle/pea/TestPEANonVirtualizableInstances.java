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
 * @summary PEA virtualizes volatile-only state but conservatively retains
 *          finalizable, Thread, Reference, identity, and unknown-call objects
 * @library /test/lib /
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.pea.TestPEANonVirtualizableInstances
 */

package compiler.jeandle.pea;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.ref.WeakReference;
import java.util.List;

import jdk.test.lib.Asserts;

public class TestPEANonVirtualizableInstances {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestPEANonVirtualizableInstances$TestWrapper";
    private static final String SHAPE_RUN_PROPERTY =
            "compiler.jeandle.pea.nonVirtualizableShapeRun";
    private static final String THROWING_CTOR =
            "compiler_jeandle_pea_TestPEANonVirtualizableInstances$TestWrapper$ThrowingBox_<init>(I)V";

    public static void main(String[] args) throws Exception {
        Method volatileOnly = TestWrapper.class.getMethod("testVolatileOnly", int.class, int.class);
        Method finalizable = TestWrapper.class.getMethod("testFinalizable", int.class);
        Method threadConstruction = TestWrapper.class.getMethod(
                "testThreadConstructionOnly", int.class);
        Method threadLifecycle = TestWrapper.class.getMethod("testThreadLifecycle", int.class);
        Method weakCaller = TestWrapper.class.getMethod("testWeakReference", Object.class);
        Method weakFactory = TestWrapper.class.getMethod("makeWeakReference", Object.class);
        Method identity = TestWrapper.class.getMethod("testIdentitySensitive", int.class);
        Method unknown = TestWrapper.class.getMethod("testUnknownCall", int.class);
        Method valueBased = TestWrapper.class.getMethod("testValueBasedMonitor",
                int.class, Object.class);
        Method throwing = TestWrapper.class.getMethod("testThrowingConstructor", int.class);
        Method unknownConsumer = TestWrapper.class.getMethod("unknownConsumer",
                TestWrapper.PlainBox.class);
        Method threadRunner = TestWrapper.class.getMethod("startAndJoin",
                TestWrapper.TinyThread.class);
        Method identityHash = System.class.getMethod("identityHashCode", Object.class);
        Constructor<TestWrapper.FinalizableBox> finalizableCtor =
                TestWrapper.FinalizableBox.class.getDeclaredConstructor();
        Constructor<TestWrapper.TinyThread> threadCtor =
                TestWrapper.TinyThread.class.getDeclaredConstructor(int.class);
        Constructor<TestWrapper.ThrowingBox> throwingCtor =
                TestWrapper.ThrowingBox.class.getDeclaredConstructor(int.class);
        Method[] targets = {volatileOnly, finalizable, threadConstruction, threadLifecycle,
                weakCaller, weakFactory, identity, unknown, valueBased, throwing};

        behaviorBuilder(targets, unknownConsumer, threadRunner, weakFactory, identityHash,
                finalizableCtor, threadCtor, throwingCtor)
                .runPEAOnOffEquivalent();
        try (PEATestUtils.RunResult run =
                shapeBuilder(targets, unknownConsumer, threadRunner, weakFactory, identityHash,
                        finalizableCtor, threadCtor, throwingCtor).run()) {
            assertVolatileVirtualized(run, volatileOnly);
            assertFinalizableRefused(run, finalizable);
            assertTinyThreadConstructionRefused(run, threadConstruction);
            assertTinyThreadRetained(run, threadLifecycle);
            assertWeakReferenceRetainedByDeoptimization(run, weakFactory);
            assertWeakReferenceCallerFlow(run, weakCaller, weakFactory);
            assertIdentityMaterialized(run, identity, identityHash);
            assertUnknownCallMaterialized(run, unknown, unknownConsumer);
            assertValueBasedRefused(run, valueBased);
            assertThrowingConstructorRetained(run, throwing);
        }
    }

    private static PEATestUtils.RunBuilder shapeBuilder(Method[] targets, Method unknownConsumer,
                                                         Method threadRunner, Method weakFactory,
                                                         Method identityHash,
                                                         Constructor<?> finalizableCtor,
                                                         Constructor<?> threadCtor,
                                                         Constructor<?> throwingCtor) {
        return configure(PEATestUtils.shapeRun(WRAPPER, targets), unknownConsumer,
                threadRunner, weakFactory, identityHash, finalizableCtor, threadCtor,
                throwingCtor)
                .lockingMode(2)
                .extraFlags("-XX:DiagnoseSyncOnValueBasedClasses=1",
                        "-D" + SHAPE_RUN_PROPERTY + "=true");
    }

    private static PEATestUtils.RunBuilder behaviorBuilder(Method[] targets,
                                                            Method unknownConsumer,
                                                            Method threadRunner, Method weakFactory,
                                                            Method identityHash,
                                                            Constructor<?> finalizableCtor,
                                                            Constructor<?> threadCtor,
                                                            Constructor<?> throwingCtor) {
        return configure(PEATestUtils.behaviorRun(WRAPPER, targets), unknownConsumer,
                threadRunner, weakFactory, identityHash, finalizableCtor, threadCtor,
                throwingCtor);
    }

    private static PEATestUtils.RunBuilder configure(PEATestUtils.RunBuilder builder,
                                                      Method unknownConsumer,
                                                      Method threadRunner, Method weakFactory,
                                                      Method identityHash,
                                                      Constructor<?> finalizableCtor,
                                                      Constructor<?> threadCtor,
                                                      Constructor<?> throwingCtor) {
        return builder
                .dontinline(unknownConsumer)
                .dontinline(threadRunner)
                .dontinline(weakFactory)
                .dontinline(identityHash)
                .compileOnly(finalizableCtor)
                .compileOnly(threadCtor)
                .compileOnly(throwingCtor)
                .dontinline(throwingCtor);
    }

    private static void assertVolatileVirtualized(PEATestUtils.RunResult run, Method target)
            throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.PEARound first = report.round(0);
        PEATestUtils.IRBody before = first.before();
        PEATestUtils.IRBody after = report.finalAfter();

        Asserts.assertEquals(first.neverEscapes(), 1, target + ": volatile object is virtual");
        Asserts.assertEquals(before.allocations().size(), 1, target + ": source allocation");
        Asserts.assertEquals(before.allocations().get(0).key().kind(),
                PEATestUtils.AllocationKind.INSTANCE, target + ": volatile allocation kind");
        Asserts.assertEquals(first.effectCount("EliminateAllocation"), 1L,
                target + ": allocation elimination");
        Asserts.assertEquals(first.effectCount("EliminateStore", "store atomic i32"), 2L,
                target + ": exactly two volatile writes enter virtual state");
        Asserts.assertEquals(first.effectCount("ReplaceLoad", "load atomic i32"), 1L,
                target + ": exact volatile field load is scalarized");
        after.assertRetainsExactlyOriginalAllocations(before);
        after.assertAbsent("store atomic i32");
        after.assertAbsent("load atomic i32");
        Asserts.assertEquals(run.finalIR(target).loweredAllocCount(), 0,
                target + ": no allocation remains after volatile scalar replacement");
    }

    private static PEATestUtils.AllocationKey assertOneOriginalAllocationRetained(
            PEATestUtils.RunResult run, Method target) throws Exception {
        PEATestUtils.IRBody before = run.report(target).round(0).before();
        PEATestUtils.IRBody after = run.report(target).finalAfter();
        Asserts.assertEquals(before.allocations().size(), 1,
                target + ": one allocation subject to the eligibility gate");
        PEATestUtils.AllocationKey original = before.allocations().get(0).key();
        Asserts.assertEquals(original.kind(), PEATestUtils.AllocationKind.INSTANCE,
                target + ": eligibility-gated allocation kind");
        after.assertRetainsExactlyOriginalAllocations(before, original);
        return original;
    }

    private static void assertFinalizableRefused(PEATestUtils.RunResult run, Method target)
            throws Exception {
        PEATestUtils.PEARound first = run.report(target).round(0);
        assertOneOriginalAllocationRetained(run, target);
        Asserts.assertEquals(first.neverEscapes(), 0,
                target + ": finalizable allocation is not virtualized");
        Asserts.assertEquals(first.partiallyEscapes(), 0,
                target + ": finalizable allocation is not partially virtualized");
        Asserts.assertEquals(first.alwaysEscapes(), 0,
                target + ": finalizable allocation is refused before escape classification");
        Asserts.assertEquals(first.effects().size(), 0,
                target + ": finalizable eligibility refusal records no transform effects");
        first.before().assertOccurrenceCount("@jeandle.register_finalizer_if_needed(", 1);
        run.report(target).finalAfter().assertOccurrenceCount(
                "@jeandle.register_finalizer_if_needed(", 1);
    }

    private static void assertTinyThreadRetained(PEATestUtils.RunResult run, Method target)
            throws Exception {
        PEATestUtils.IRBody before = run.report(target).round(0).before();
        PEATestUtils.AllocationKey tinyThread = new PEATestUtils.AllocationKey(
                PEATestUtils.AllocationKind.INSTANCE, 4);
        long sourceMatches = before.allocations().stream()
                .filter(site -> site.key().equals(tinyThread)).count();
        Asserts.assertEquals(sourceMatches, 1L,
                target + ": exactly one TinyThread source allocation at bci 4");

        PEATestUtils.IRBody after = run.report(target).finalAfter();
        long retainedMatches = after.allocations().stream()
                .filter(site -> site.key().equals(tinyThread)).count();
        Asserts.assertEquals(retainedMatches, 1L,
                target + ": the original TinyThread allocation is retained exactly once");
        for (PEATestUtils.AllocationSite retained : after.allocations()) {
            Asserts.assertTrue(before.allocations().stream()
                            .map(PEATestUtils.AllocationSite::key)
                            .anyMatch(retained.key()::equals),
                    target + ": PEA may retain only source allocations: " + retained.key());
        }
    }

    private static void assertTinyThreadConstructionRefused(PEATestUtils.RunResult run,
                                                             Method target) throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.PEARound first = report.round(0);
        PEATestUtils.IRBody before = first.before();
        PEATestUtils.AllocationKey tinyThread = new PEATestUtils.AllocationKey(
                PEATestUtils.AllocationKind.INSTANCE, 0);
        List<PEATestUtils.AllocationSite> sourceMatches = before.allocations().stream()
                .filter(site -> site.key().equals(tinyThread)).toList();
        Asserts.assertEquals(sourceMatches.size(), 1,
                target + ": exactly one construction-only TinyThread allocation at bci 0");

        PEATestUtils.IRBody after = run.report(target).finalAfter();
        long retainedMatches = after.allocations().stream()
                .filter(site -> site.key().equals(tinyThread)).count();
        Asserts.assertEquals(retainedMatches, 1L,
                target + ": construction-only TinyThread allocation is retained exactly once");
        for (PEATestUtils.AllocationSite retained : after.allocations()) {
            Asserts.assertTrue(before.allocations().stream()
                            .map(PEATestUtils.AllocationSite::key)
                            .anyMatch(retained.key()::equals),
                    target + ": PEA may retain only source allocations: " + retained.key());
        }

        for (PEATestUtils.PEARound round : report.rounds()) {
            List<PEATestUtils.AllocationSite> roundMatches = round.before().allocations().stream()
                    .filter(site -> site.key().equals(tinyThread)).toList();
            if (roundMatches.isEmpty()) {
                continue;
            }
            Asserts.assertEquals(roundMatches.size(), 1,
                    target + ": exact TinyThread allocation in round " + round.iteration());
            PEATestUtils.AllocationSite roundSource = roundMatches.get(0);
            List<PEATestUtils.PEAEffect> attributedEffects = round.effects().stream()
                    .filter(effect -> {
                        int targetAt = effect.detail().indexOf(" target=");
                        if (targetAt < 0) {
                            return false;
                        }
                        String targetInstruction = effect.detail()
                                .substring(targetAt + " target=".length()).stripLeading();
                        return targetInstruction.startsWith(roundSource.result() + " =");
                    }).toList();
            Asserts.assertEquals(attributedEffects, List.of(),
                    target + ": eligibility-refused TinyThread has no VO effects in round "
                            + round.iteration());
            Asserts.assertEquals(round.effectCount(
                            "EliminateAllocation", roundSource.instruction()), 0L,
                    target + ": TinyThread never enters virtual state in round "
                            + round.iteration());
            Asserts.assertEquals(round.effectCount(
                            "Materialize", roundSource.instruction()), 0L,
                    target + ": TinyThread is not materialized in round "
                            + round.iteration());
        }
    }

    private static void assertWeakReferenceRetainedByDeoptimization(PEATestUtils.RunResult run,
                                                                     Method target) throws Exception {
        PEATestUtils.PEARound first = run.report(target).round(0);
        Asserts.assertEquals(first.before().allocations().size(), 0,
                target + ": Reference allocation is excluded before PEA");
        Asserts.assertEquals(first.effects().size(), 0,
                target + ": PEA does not transform an excluded Reference allocation");

        PEATestUtils.IRBody frontend = run.frontendIR(target);
        Asserts.assertEquals(frontend.peaAllocCount(), 0,
                target + ": frontend retains the Reference allocation outside PEA form");
        Asserts.assertEquals(frontend.loweredAllocCount(), 0,
                target + ": Reference allocation is not lowered into compiled code");
        Asserts.assertEquals(frontend.callOccurrencesAtBCI(
                        "llvm.experimental.deoptimize.p1", 0), List.of(0),
                target + ": original Reference allocation deoptimizes at its source bci");

        PEATestUtils.IRBody lowered = run.finalIR(target);
        Asserts.assertEquals(lowered.loweredAllocCount(), 0,
                target + ": lowering must not synthesize a compiled Reference allocation");
        Asserts.assertEquals(lowered.callOccurrencesAtBCI(
                        "llvm.experimental.gc.statepoint.p0", 0), List.of(0),
                target + ": lowered deoptimization preserves the original allocation bci");
    }

    private static void assertWeakReferenceCallerFlow(PEATestUtils.RunResult run, Method target,
                                                       Method factory)
            throws Exception {
        PEATestUtils.PEARound first = run.report(target).round(0);
        Asserts.assertEquals(first.before().allocations().size(), 0,
                target + ": caller owns no allocation site");
        Asserts.assertEquals(first.effects().size(), 0,
                target + ": caller flow is outside PEA transformations");

        String factoryName = PEATestUtils.MethodId.of(factory).llvmFunctionName();
        int[] operationBCIs = {6, 21, 26, 30, 45};
        PEATestUtils.IRBody frontend = run.frontendIR(target);
        frontend.assertOccurrenceCount("@\"" + factoryName + "\"(", 1);
        Asserts.assertEquals(frontend.callOccurrencesAtBCI(
                        "llvm.experimental.deoptimize.i32", 0), List.of(),
                target + ": caller is not replaced by an entry deoptimization stub");
        for (int index = 0; index < operationBCIs.length; index++) {
            Asserts.assertEquals(frontend.callOccurrencesAtBCI(
                            "llvm.experimental.deoptimize.i32", operationBCIs[index]),
                    List.of(index),
                    target + ": exact get/refersTo/clear fallback at bci "
                            + operationBCIs[index]);
        }
        frontend.assertBefore(factoryName, 0, "llvm.experimental.deoptimize.i32", 0);

        PEATestUtils.IRBody lowered = run.finalIR(target);
        String getLoad = "load atomic ptr addrspace(1), ptr addrspace(1)";
        String refersTo = "__jeandle_dynamic_call.java_lang_ref_Reference_refersToImpl"
                + "(Ljava/lang/Object;)Z";
        String clear = "__jeandle_dynamic_call.java_lang_ref_Reference_clear()V";
        lowered.assertOccurrenceCount("@\"" + factoryName + "\"", 1);
        lowered.assertOccurrenceCount(getLoad, 2);
        lowered.assertOccurrenceCount(refersTo, 2);
        lowered.assertOccurrenceCount(clear, 1);
        lowered.assertBefore(factoryName, 0, getLoad, 0);
        lowered.assertBefore(getLoad, 0, refersTo, 0);
        lowered.assertBefore(refersTo, 0, clear, 0);
        lowered.assertBefore(clear, 0, getLoad, 1);
        lowered.assertBefore(getLoad, 1, refersTo, 1);
    }

    private static void assertIdentityMaterialized(PEATestUtils.RunResult run, Method target,
                                                    Method identityHash) throws Exception {
        PEATestUtils.PEARound first = run.report(target).round(0);
        assertOneOriginalAllocationRetained(run, target);
        Asserts.assertEquals(first.effectCount("Materialize"), 1L,
                target + ": identity use materializes exactly once");
        Asserts.assertEquals(first.effectCount("EliminateStore", "store atomic i32"), 1L,
                target + ": the original field store is replayed at identity use");
        String callee = PEATestUtils.MethodId.of(identityHash).llvmFunctionName();
        PEATestUtils.IRBlock useBlock = run.report(target).finalAfter()
                .blockContaining(callee, 0);
        useBlock.assertOccurrenceCount("store atomic i32", 1);
        useBlock.assertBefore("store atomic i32", 0, callee, 0);
    }

    private static void assertUnknownCallMaterialized(PEATestUtils.RunResult run, Method target,
                                                       Method consumer) throws Exception {
        PEATestUtils.PEARound first = run.report(target).round(0);
        assertOneOriginalAllocationRetained(run, target);
        Asserts.assertEquals(first.effectCount("Materialize"), 1L,
                target + ": unknown call materializes exactly once");
        Asserts.assertEquals(first.effectCount("EliminateStore", "store atomic i32"), 1L,
                target + ": initial field state is replayed exactly once");
        String callee = PEATestUtils.MethodId.of(consumer).llvmFunctionName();
        PEATestUtils.IRBlock useBlock = run.report(target).finalAfter()
                .blockContaining(callee, 0);
        useBlock.assertOccurrenceCount("store atomic i32", 1);
        useBlock.assertBefore("store atomic i32", 0, callee, 0);
    }

    private static void assertValueBasedRefused(PEATestUtils.RunResult run, Method target)
            throws Exception {
        PEATestUtils.PEARound first = run.report(target).round(0);
        PEATestUtils.AllocationKey original = assertOneOriginalAllocationRetained(run, target);
        Asserts.assertEquals(first.neverEscapes(), 0,
                target + ": monitor-dependent allocation is not fully virtualized");
        Asserts.assertEquals(first.partiallyEscapes(), 1,
                target + ": unknown monitor arm materializes the allocation");
        Asserts.assertEquals(first.alwaysEscapes(), 0,
                target + ": allocation escapes only along the monitor arm");
        Asserts.assertEquals(first.effects().size(), 5,
                target + ": exact monitor-dependent transformation effects");
        Asserts.assertEquals(first.effectCount("EliminateAllocation"), 1L,
                target + ": allocation enters virtual state once");
        Asserts.assertEquals(first.effectCount("ReplaceLoad"), 1L,
                target + ": exact constructor null check replacement");
        Asserts.assertEquals(first.effectCount("ReplaceCall"), 1L,
                target + ": exact finalizer helper replacement");
        Asserts.assertEquals(first.effectCount("EliminateStore", "store atomic i32"), 1L,
                target + ": exact field store enters virtual state");
        Asserts.assertEquals(first.effectCount("Materialize"), 1L,
                target + ": unknown monitor arm materializes once");
        Asserts.assertEquals(first.lockReplays().size(), 0,
                target + ": runtime monitor is retained instead of virtually replayed");

        PEATestUtils.IRBody before = first.before();
        PEATestUtils.IRBody after = run.report(target).finalAfter();
        after.assertRetainsExactlyOriginalAllocations(before, original);
        before.assertOccurrenceCount("@jeandle.check_if_value_based(", 1);
        after.assertOccurrenceCount("@jeandle.check_if_value_based(", 1);
        after.assertOccurrenceCount("@jeandle.monitorenter_with_lightweight_lock(", 1);
        after.assertOccurrenceCount("@SharedRuntime_complete_monitor_locking_C(", 1);
        after.assertOccurrenceCount("@jeandle.monitorexit_with_lightweight_lock(", 1);
        PEATestUtils.IRBody frontend = run.frontendIR(target);
        Asserts.assertEquals(frontend.callOccurrencesAtBCI(
                        "llvm.experimental.deoptimize.i32", 0), List.of(),
                target + ": diagnostic monitor path is not an entry deopt stub");
        Asserts.assertEquals(frontend.callOccurrencesAtBCI(
                        "llvm.experimental.deoptimize.i32", 4), List.of(0),
                target + ": constructor null check keeps its exact fallback bci");
        Asserts.assertEquals(frontend.callOccurrencesAtBCI(
                        "llvm.experimental.deoptimize.i32", 29), List.of(2),
                target + ": monitor null check keeps its exact fallback bci");
        Asserts.assertEquals(run.finalIR(target).loweredAllocCount(), 1,
                target + ": lowering retains only the original allocation");
    }

    private static void assertThrowingConstructorRetained(PEATestUtils.RunResult run,
                                                           Method target) throws Exception {
        PEATestUtils.PEARound first = run.report(target).round(0);
        assertOneOriginalAllocationRetained(run, target);
        Asserts.assertEquals(first.effectCount("Materialize"), 1L,
                target + ": constructor receiver materializes once at its invoke");
        PEATestUtils.IRBody frontend = run.frontendIR(target);
        Asserts.assertEquals(frontend.callOccurrencesAtBCI(
                        "llvm.experimental.deoptimize.i32", 0), List.of(),
                target + ": throwing constructor path is not an entry deopt stub");
        Asserts.assertEquals(frontend.callOccurrencesAtBCI(
                        "llvm.experimental.deoptimize.i32", 5), List.of(0),
                target + ": constructor receiver null fallback keeps its invoke bci");
        PEATestUtils.IRBlock constructorBlock = frontend.blockContaining(THROWING_CTOR, 0);
        constructorBlock.assertOccurrenceCount("invoke hotspotcc void @\""
                + THROWING_CTOR + "\"(", 1);
        constructorBlock.assertOccurrenceCount(" to label ", 1);
        constructorBlock.assertOccurrenceCount(" unwind label ", 1);
    }

    public static class TestWrapper {
        private static PlainBox observed;
        private static int threadValue;
        private static int constructorCalls;
        private static volatile int finalizerProbe;

        public static class VolatileBox {
            volatile int value;
        }

        @SuppressWarnings({"deprecation", "removal"})
        public static class FinalizableBox {
            int value;

            @Override
            @SuppressWarnings("deprecation")
            protected void finalize() {
                finalizerProbe = value;
            }
        }

        public static class TinyThread extends Thread {
            private final int value;

            TinyThread(int value) {
                this.value = value;
            }

            @Override
            public void run() {
                threadValue = value;
            }
        }

        public static class PlainBox {
            int value;
        }

        public static class TestWeakReference<T> extends WeakReference<T> {
            TestWeakReference(T value) {
                super(value);
            }
        }

        public static class ThrowingBox {
            final int value;

            ThrowingBox(int value) {
                constructorCalls++;
                if (value < 0) {
                    throw new ConstructorFailure(value);
                }
                this.value = value;
            }
        }

        public static class ConstructorFailure extends RuntimeException {
            final int value;

            ConstructorFailure(int value) {
                this.value = value;
            }
        }

        public static void main(String[] args) throws Exception {
            new VolatileBox();
            new FinalizableBox();
            Asserts.assertEquals(FinalizableBox.class.getDeclaredMethod("finalize")
                    .getDeclaringClass(), FinalizableBox.class,
                    "finalizable class overrides Object.finalize");
            new TinyThread(0);
            new PlainBox();
            try {
                new ThrowingBox(-1);
            } catch (ConstructorFailure expected) {
            }
            PEATestUtils.compileConfiguredTargetsAtLevel4();

            Object strong = new Object();
            Object valueBasedAlternate = Integer.valueOf(42);
            Asserts.assertEquals(valueBasedAlternate.getClass(), Integer.class,
                    "monitor alternate is a value-based Integer");
            long digest = 0xC2B2AE3D27D4EB4FL;
            boolean executeValueBasedMonitor = !Boolean.getBoolean(SHAPE_RUN_PROPERTY);
            for (int value : new int[] {0, 7, -29, 0x12345678}) {
                int volatileResult = testVolatileOnly(value, value ^ 0x55AA55AA);
                Asserts.assertEquals(volatileResult,
                        value ^ 0x55AA55AA, "volatile field value");
                digest = mix(digest, volatileResult);

                int finalizableResult = testFinalizable(value);
                Asserts.assertEquals(finalizableResult, value * 3 + 1,
                        "finalizable object state");
                digest = mix(digest, finalizableResult);

                int constructionResult = testThreadConstructionOnly(value);
                Asserts.assertEquals(constructionResult, value * 7 + 5,
                        "construction-only Thread subclass state");
                digest = mix(digest, constructionResult);

                int threadResult = testThreadLifecycle(value);
                Asserts.assertEquals(threadResult, value * 5 + 3,
                        "Thread start/join lifecycle");
                digest = mix(digest, threadResult);
                digest = mix(digest, threadValue);

                int referenceResult = testWeakReference(strong);
                Asserts.assertEquals(referenceResult, 15,
                        "WeakReference get/refersTo/clear behavior");
                digest = mix(digest, referenceResult);

                int identityResult = testIdentitySensitive(value);
                Asserts.assertEquals(identityResult, value + 1,
                        "identity hash remains stable for the same object");
                digest = mix(digest, identityResult);

                observed = null;
                int unknownResult = testUnknownCall(value);
                Asserts.assertEquals(unknownResult, value + 9,
                        "unknown call observes and preserves object identity");
                Asserts.assertNotEquals(observed, null, "unknown call receives its argument");
                Asserts.assertEquals(observed.value, value + 9,
                        "post-call mutation is visible through the escaped identity");
                digest = mix(digest, unknownResult);
                digest = mix(digest, observed == null ? 0 : 1);
                digest = mix(digest, observed.value);

                if (executeValueBasedMonitor) {
                    int valueBasedResult = testValueBasedMonitor(value, valueBasedAlternate);
                    Asserts.assertEquals(valueBasedResult, value ^ 0x5A5A5A5A,
                            "value-based monitor check preserves monitor semantics");
                    digest = mix(digest, valueBasedResult);
                }

                constructorCalls = 0;
                int successfulValue = Math.abs(value);
                int constructorResult = testThrowingConstructor(successfulValue);
                Asserts.assertEquals(constructorResult, successfulValue,
                        "successful constructor result");
                Asserts.assertEquals(constructorCalls, 1, "successful constructor count");
                digest = mix(digest, constructorResult);
                digest = mix(digest, constructorCalls);

                constructorCalls = 0;
                int exceptionResult = testThrowingConstructor(-successfulValue - 1);
                Asserts.assertEquals(exceptionResult,
                        successfulValue + 1,
                        "throwing constructor exception value");
                Asserts.assertEquals(constructorCalls, 1, "throwing constructor count");
                digest = mix(digest, exceptionResult);
                digest = mix(digest, constructorCalls);
            }
            System.out.println("PEA-RESULT:" + Long.toUnsignedString(digest, 16));
        }

        public static int testVolatileOnly(int first, int second) {
            VolatileBox value = new VolatileBox();
            value.value = first;
            value.value = second;
            return value.value;
        }

        public static int testFinalizable(int value) {
            FinalizableBox box = new FinalizableBox();
            box.value = value;
            return box.value * 3 + 1;
        }

        public static int testThreadLifecycle(int value) throws InterruptedException {
            threadValue = 0;
            TinyThread thread = new TinyThread(value);
            return startAndJoin(thread);
        }

        public static int testThreadConstructionOnly(int value) {
            TinyThread thread = new TinyThread(value);
            return thread.value * 7 + 5;
        }

        public static int startAndJoin(TinyThread thread) throws InterruptedException {
            thread.start();
            thread.join();
            return threadValue * 5 + 3;
        }

        public static int testWeakReference(Object strong) {
            TestWeakReference<Object> reference = makeWeakReference(strong);
            boolean getBeforeClear = reference.get() == strong;
            boolean refersBeforeClear = reference.refersTo(strong);
            reference.clear();
            boolean getAfterClear = reference.get() == null;
            boolean refersAfterClear = reference.refersTo(null);
            return (getBeforeClear ? 1 : 0)
                    | (refersBeforeClear ? 2 : 0)
                    | (getAfterClear ? 4 : 0)
                    | (refersAfterClear ? 8 : 0);
        }

        public static TestWeakReference<Object> makeWeakReference(Object strong) {
            return new TestWeakReference<>(strong);
        }

        public static int testIdentitySensitive(int value) {
            PlainBox box = new PlainBox();
            box.value = value;
            int first = System.identityHashCode(box);
            int second = System.identityHashCode(box);
            return box.value + (first == second ? 1 : -1);
        }

        public static int testUnknownCall(int value) {
            PlainBox box = new PlainBox();
            box.value = value;
            unknownConsumer(box);
            box.value += 9;
            return observed == box ? observed.value : Integer.MIN_VALUE;
        }

        public static void unknownConsumer(PlainBox box) {
            observed = box;
        }

        @SuppressWarnings("synchronization")
        public static int testValueBasedMonitor(int value, Object alternate) {
            PlainBox boxed = new PlainBox();
            boxed.value = value;
            Object monitor = (value & 1) == 0 ? boxed : alternate;
            synchronized (monitor) {
                return boxed.value ^ 0x5A5A5A5A;
            }
        }

        public static int testThrowingConstructor(int value) {
            try {
                return new ThrowingBox(value).value;
            } catch (ConstructorFailure expected) {
                return -expected.value;
            }
        }

        private static long mix(long accumulator, long value) {
            return Long.rotateLeft(accumulator ^ value, 11) * 0x9E3779B97F4A7C15L;
        }
    }
}
