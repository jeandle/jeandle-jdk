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
 * @summary PEA fresh retry keeps an unavailable nested object real while its
 *          virtual outer holder replays the exact materialized reference
 * @library /test/lib /
 * @modules java.base/jdk.internal.misc
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      -XX:-UseCompressedOops -XX:-UseCompressedClassPointers
 *      compiler.jeandle.pea.TestPEALoopKeptRealNestedRefRetry
 */

package compiler.jeandle.pea;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jdk.internal.misc.Unsafe;
import jdk.test.lib.Asserts;

public class TestPEALoopKeptRealNestedRefRetry {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestPEALoopKeptRealNestedRefRetry$TestWrapper";
    private static final String SAFEPOINT_POLL = "jeandle.safepoint_poll";
    private static final String LLVM_LOCAL =
            "(?:%[-A-Za-z$._0-9]+|%\"(?:[^\"\\\\]|\\\\.)*\")";
    private static final Unsafe UNSAFE = Unsafe.getUnsafe();

    public static void main(String[] args) throws Exception {
        Method target = TestWrapper.class.getMethod(
                "loopKeptRealNestedRefRetry", TestWrapper.Source.class, int.class);
        Method consume = TestWrapper.class.getDeclaredMethod(
                "consume", TestWrapper.Outer.class, int.class);
        Method escapeInner = TestWrapper.class.getDeclaredMethod(
                "escapeInner", TestWrapper.Inner.class);

        runBuilder(false, target, consume, escapeInner).runPEAOnOffEquivalent();

        try (PEATestUtils.RunResult run =
                runBuilder(true, target, consume, escapeInner).run()) {
            assertShape(run, target, consume, escapeInner);
        }
    }

    private static PEATestUtils.RunBuilder runBuilder(
            boolean shape, Method target, Method consume, Method escapeInner) {
        PEATestUtils.RunBuilder builder = shape
                ? PEATestUtils.shapeRun(WRAPPER, target)
                : PEATestUtils.behaviorRun(WRAPPER, target);
        return builder.peaIterations(2)
                .dontinline(consume)
                .dontinline(escapeInner);
    }

    private static void assertShape(
            PEATestUtils.RunResult run, Method target, Method consume,
            Method escapeInner)
            throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        Asserts.assertEquals(report.roundCount(), 2,
                target + ": two outer PEA pipeline rounds expose the unavailable nested state");
        report.assertStoppedAtIterationCap();

        for (PEATestUtils.PEARound round : report.rounds()) {
            PEATestUtils.assertStructuralSoundness(round.before(),
                    target + ": round " + round.iteration() + " before");
            PEATestUtils.assertStructuralSoundness(round.after(),
                    target + ": round " + round.iteration() + " after");
        }
        PEATestUtils.assertStructuralSoundness(
                run.finalIR(target), target + ": final lowered IR");

        PEATestUtils.IRBody source = report.round0Before();
        List<PEATestUtils.AllocationSite> sourceAllocations = source.allocations();
        Asserts.assertEquals(sourceAllocations.size(), 3,
                target + ": exact Guard, Outer, and Inner source allocations");
        PEATestUtils.AllocationKey guardKey = sourceAllocations.get(0).key();
        PEATestUtils.AllocationKey outerKey = sourceAllocations.get(1).key();
        PEATestUtils.AllocationKey innerKey = sourceAllocations.get(2).key();
        Asserts.assertNotEquals(guardKey, outerKey,
                target + ": Guard and Outer have distinct source identities");
        Asserts.assertNotEquals(outerKey, innerKey,
                target + ": Outer and Inner have distinct source identities");

        PEATestUtils.PEARound retry = report.round(1);
        PEATestUtils.IRBody retryBefore = retry.before();
        PEATestUtils.IRBody after = retry.after();
        retryBefore.assertRetainsExactlyOriginalAllocations(
                source, outerKey, innerKey);
        after.assertRetainsExactlyOriginalAllocations(source, outerKey, innerKey);
        retryBefore.assertOccurrenceCount("br i1 true", 1);

        String sourceInner = allocationResult(retryBefore, innerKey);
        int innerElemsOffset = offset(TestWrapper.Inner.class, "elems");
        FieldStore sourceInnerStore = exactFieldStore(
                retryBefore, sourceInner, innerElemsOffset, "ptr addrspace(1)");
        PEATestUtils.IRBlock constantBranch =
                retryBefore.blockContaining("br i1 true", 0);
        List<String> branchTargets = constantBranch.conditionalBranchTargets();
        Asserts.assertNotEquals(branchTargets.get(0), branchTargets.get(1),
                target + ": the syntactic dead predecessor remains in round-two input");
        PEATestUtils.IRBlock liveArm =
                retryBefore.blockByLabel(branchTargets.get(0));
        Asserts.assertTrue(liveArm.conditionalBranchTargets().contains(
                        sourceInnerStore.block().label()),
                target + ": the live arm's null-check successor defines Inner.elems");
        PEATestUtils.IRBlock deadArm =
                retryBefore.blockByLabel(branchTargets.get(1));
        deadArm.assertOccurrenceCount(
                PEATestUtils.MethodId.of(escapeInner).llvmFunctionName(), 1);
        assertBranchLocalOopLoad(sourceInnerStore, target);

        Asserts.assertEquals(retry.effectCount("Materialize", "[VO=0]"), 1L,
                target + ": virtual Outer materializes exactly once at its sink");
        Asserts.assertEquals(retry.effectCount("Materialize"), 1L,
                target + ": kept-real Inner has no materialization plan");
        Asserts.assertEquals(retry.effectCount("Materialize", "[VO=1]"), 0L,
                target + ": unavailable Inner remains an original real allocation");
        Asserts.assertEquals(retry.effectCount("EliminateAllocation", "[VO=1]"), 0L,
                target + ": retry does not eliminate the kept-real Inner allocation");
        Asserts.assertEquals(retry.effectCount("EliminateStore", "[VO=1]"), 0L,
                target + ": retry preserves the kept-real Inner initialization store");
        Asserts.assertEquals(retry.effectCount("EliminateStore", "[VO=0]"), 1L,
                target + ": Outer.iterator remains virtual until the sink");

        String outerResult = allocationResult(after, outerKey);
        String innerResult = allocationResult(after, innerKey);
        FieldStore retainedInnerStore = exactFieldStore(
                after, innerResult, innerElemsOffset, "ptr addrspace(1)");
        Asserts.assertFalse(retainedInnerStore.line().contains("pea.matslot"),
                target + ": Inner.elems remains its original real-object store");

        String consumeName = PEATestUtils.MethodId.of(consume).llvmFunctionName();
        PEATestUtils.IRBlock sink = after.blockContaining(consumeName, 0);
        int outerIteratorOffset = offset(TestWrapper.Outer.class, "iterator");
        FieldStore outerReplay = exactFieldStore(
                after, outerResult, outerIteratorOffset, "ptr addrspace(1)");
        Asserts.assertEquals(outerReplay.block().label(), sink.label(),
                target + ": Outer.iterator replays only in the sink block");
        Asserts.assertEquals(outerReplay.value(), innerResult,
                target + ": Outer.iterator replays the retained Inner SSA value");
        sink.assertBefore(outerReplay.line(), 0, consumeName, 0);
        Asserts.assertEquals(after.occurrenceCount(
                "store atomic ptr addrspace(1) " + innerResult + ","), 1,
                target + ": one exact Outer-to-Inner reference replay");

        PEATestUtils.PEAEffect materialize =
                retry.uniqueEffect("Materialize", "[VO=0]");
        Asserts.assertTrue(materialize.detail().contains("block=%" + sink.label() + " "),
                target + ": Outer materialization effect targets the sink block");
        Asserts.assertTrue(materialize.detail().contains(outerResult + " = "),
                target + ": materialization effect names the retained Outer allocation");

        PEATestUtils.DeoptBundle loopBundle =
                uniqueVirtualLoopBundle(after, target);
        loopBundle.assertVirtualObjectIds(0);
        PEATestUtils.VirtualObjectDescriptor outer = loopBundle.virtualObject(0);
        Asserts.assertEquals(outer.kind(), PEATestUtils.DescriptorKind.INSTANCE,
                target + ": loop descriptor is the virtual Outer instance");
        Asserts.assertEquals(outer.fields().keySet(), Set.of(outerIteratorOffset),
                target + ": virtual Outer carries exactly its iterator reference");
        PEATestUtils.VirtualObjectEntry iterator =
                outer.fields().get(outerIteratorOffset);
        Asserts.assertEquals(iterator.basicType(), PEATestUtils.DeoptBasicType.OBJECT,
                target + ": Outer.iterator deopt basic type");
        Asserts.assertEquals(iterator.value().kind(),
                PEATestUtils.DeoptValueKind.MATERIALIZED_OOP,
                target + ": loop deopt state holds a real Inner oop");
        Asserts.assertEquals(iterator.value().operand(),
                "ptr addrspace(1) " + innerResult,
                target + ": loop deopt state names the retained Inner allocation");
    }

    private static PEATestUtils.DeoptBundle uniqueVirtualLoopBundle(
            PEATestUtils.IRBody body, Method target) {
        int polls = body.lineCount(SAFEPOINT_POLL);
        Asserts.assertTrue(polls >= 1, target + ": compiled loop has a safepoint poll");
        ArrayList<PEATestUtils.DeoptBundle> virtualBundles = new ArrayList<>();
        for (int i = 0; i < polls; i++) {
            PEATestUtils.DeoptBundle bundle =
                    body.deoptBundleAtCall(SAFEPOINT_POLL, i);
            if (!bundle.virtualObjects().isEmpty()) {
                virtualBundles.add(bundle);
            }
        }
        Asserts.assertEquals(virtualBundles.size(), 1,
                target + ": exactly one loop poll carries the virtual Outer");
        return virtualBundles.get(0);
    }

    private static void assertBranchLocalOopLoad(
            FieldStore store, Method target) {
        Asserts.assertTrue(store.value().startsWith("%"),
                target + ": Inner.elems is initialized from a branch-local SSA value");
        String definition = store.value() + " = load";
        Asserts.assertEquals(store.block().lines().stream()
                        .filter(line -> line.startsWith(definition))
                        .filter(line -> line.contains("ptr addrspace(1)"))
                        .count(),
                1L, target + ": one branch-local oop load feeds Inner.elems");
    }

    private record FieldStore(
            PEATestUtils.IRBlock block, String slot, String value, String line) {}

    private static FieldStore exactFieldStore(
            PEATestUtils.IRBody body, String owner, int offset, String type) {
        Pattern gep = gepPattern(owner, offset);
        List<String> slots = body.lines().stream()
                .map(gep::matcher)
                .filter(Matcher::matches)
                .map(matcher -> matcher.group(1))
                .toList();
        Asserts.assertEquals(slots.size(), 1,
                body.methodId() + ": one field GEP for " + owner
                        + " at offset " + offset);

        Pattern storePattern = Pattern.compile("^store atomic "
                + Pattern.quote(type) + " (" + LLVM_LOCAL + "), "
                + "ptr addrspace\\(1\\) " + Pattern.quote(slots.get(0))
                + " unordered, align \\d+(?:, .*)?$");
        List<Matcher> stores = body.lines().stream()
                .map(storePattern::matcher)
                .filter(Matcher::matches)
                .toList();
        Asserts.assertEquals(stores.size(), 1,
                body.methodId() + ": one exact store through " + slots.get(0));
        String line = stores.get(0).group(0);
        return new FieldStore(body.blockContaining(line, 0), slots.get(0),
                stores.get(0).group(1), line);
    }

    private static Pattern gepPattern(String owner, int offset) {
        return Pattern.compile("^(" + LLVM_LOCAL
                + ") = getelementptr(?: inbounds)?(?: nusw)?(?: nuw)?"
                + "(?: inrange\\(-?\\d+, -?\\d+\\))?"
                + " i8, ptr addrspace\\(1\\) "
                + Pattern.quote(owner) + ", i64 " + offset
                + "(?:, ![-A-Za-z$._0-9]+ ![^,\\s]+)*$");
    }

    private static String allocationResult(
            PEATestUtils.IRBody body, PEATestUtils.AllocationKey key) {
        List<String> results = body.allocations().stream()
                .filter(site -> site.key().equals(key))
                .map(PEATestUtils.AllocationSite::result)
                .toList();
        Asserts.assertEquals(results.size(), 1,
                body.methodId() + ": one allocation for " + key);
        return results.get(0);
    }

    private static int offset(Class<?> holder, String name) throws Exception {
        Field field = holder.getDeclaredField(name);
        return Math.toIntExact(UNSAFE.objectFieldOffset(field));
    }

    public static class TestWrapper {
        public static class Guard {
            int zero;
        }

        public static class Source {
            Payload value;
        }

        public static class Payload {
            int tag;
        }

        public static class Inner {
            Payload elems;
        }

        public static class Outer {
            Inner iterator;
        }

        private static Outer escapedOuter;
        private static Inner escapedInner;

        public static void main(String[] args) throws Exception {
            new Guard();
            new Source();
            new Payload();
            new Inner();
            new Outer();
            PEATestUtils.compileConfiguredTargetsAtLevel4();

            Payload payload = new Payload();
            payload.tag = 1234;
            Source source = new Source();
            source.value = payload;
            int result = loopKeptRealNestedRefRetry(source, 3);

            Asserts.assertEquals(result, 209804,
                    "exact loop and nested-reference result");
            Asserts.assertNotNull(escapedOuter,
                    "Outer materializes at the opaque sink");
            Asserts.assertNotNull(escapedOuter.iterator,
                    "materialized Outer preserves its Inner reference");
            Asserts.assertNotNull(escapedOuter.iterator.elems,
                    "retained Inner preserves its branch-local elems field");
            Asserts.assertSame(escapedOuter.iterator.elems, payload,
                    "Outer replay reaches the exact source payload through Inner");
            Asserts.assertEquals(escapedOuter.iterator.elems.tag, 1234,
                    "nested payload remains readable after loop materialization");
            Asserts.assertNull(escapedInner,
                    "the conditionally escaping arm is unreachable at runtime");
            System.out.println("PEA-RESULT:" + result);
        }

        public static int loopKeptRealNestedRefRetry(Source source, int trips) {
            Guard guard = new Guard();
            guard.zero = 0;

            Outer outer = new Outer();
            Inner inner = new Inner();
            int state = 7;
            // The first PEA round retains inner because the opaque escape is
            // still present while folding the guard to a literal true branch.
            // The second round proves the escape arm dead, but source.value is
            // still defined below that branch and does not statically dominate
            // the loop poll where outer's nested state is reconstructed.
            if (guard.zero != 0) {
                escapeInner(inner);
                state = -19;
            } else {
                inner.elems = source.value;
            }
            outer.iterator = inner;

            guard = null;
            inner = null;
            for (int i = 0; i < trips; i++) {
                state = state * 31 + i;
            }
            return consume(outer, state);
        }

        private static int consume(Outer outer, int state) {
            escapedOuter = outer;
            return state + outer.iterator.elems.tag;
        }

        private static void escapeInner(Inner inner) {
            escapedInner = inner;
        }
    }
}
