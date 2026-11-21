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
 * @summary PEA canonicalizes a folded guard before the next round and preserves
 *          the nested virtual graph through a loop and exact sink replay
 * @library /test/lib /
 * @modules java.base/jdk.internal.misc
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.pea.TestPEALoopNestedRefCanonicalization
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

public class TestPEALoopNestedRefCanonicalization {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestPEALoopNestedRefCanonicalization$TestWrapper";
    private static final String SAFEPOINT_POLL = "jeandle.safepoint_poll";
    private static final String LLVM_LOCAL =
            "(?:%[-A-Za-z$._0-9]+|%\"(?:[^\"\\\\]|\\\\.)*\")";
    private static final Unsafe UNSAFE = Unsafe.getUnsafe();

    public static void main(String[] args) throws Exception {
        Method target = TestWrapper.class.getMethod(
                "loopNestedRefCanonicalization", TestWrapper.Source.class, int.class);
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
                target + ": two outer PEA rounds canonicalize and virtualize the nested graph");
        report.assertStoppedAtIterationCap();

        for (PEATestUtils.PEARound round : report.rounds()) {
            PEATestUtils.assertStructuralSoundness(round.before(),
                    target + ": round " + round.iteration() + " before");
            PEATestUtils.assertStructuralSoundness(round.after(),
                    target + ": round " + round.iteration() + " after");
        }
        // LLVM runtime unrolling may introduce poison on guarded bypass edges;
        // the PEA snapshots above retain the strict poison checks.
        PEATestUtils.assertCompletePhis(
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

        PEATestUtils.PEARound second = report.round(1);
        PEATestUtils.IRBody retryBefore = second.before();
        PEATestUtils.IRBody after = second.after();
        retryBefore.assertRetainsExactlyOriginalAllocations(
                source, outerKey, innerKey);
        after.assertRetainsExactlyOriginalAllocations(source, outerKey, innerKey);
        retryBefore.assertAbsent("br i1 true");
        retryBefore.assertAbsent(PEATestUtils.MethodId.of(escapeInner).llvmFunctionName());

        String sourceInner = allocationResult(retryBefore, innerKey);
        int innerElemsOffset = offset(TestWrapper.Inner.class, "elems");
        FieldStore sourceInnerStore = exactFieldStore(
                retryBefore, sourceInner, innerElemsOffset, PEATestUtils.referencePointerType());
        PEATestUtils.IRBlock sourceGuard = retryBefore.blockContaining(
                "icmp ne ptr addrspace(1) %0, null", 0);
        Matcher nullCondition = Pattern.compile("(" + LLVM_LOCAL
                + ") = icmp ne ptr addrspace\\(1\\) %0, null")
                .matcher(String.join("\n", sourceGuard.lines()));
        Asserts.assertTrue(nullCondition.find(), target + ": source null comparison");
        String guardCondition = nullCondition.group(1);
        if (sourceGuard.occurrenceCount("br i1 " + guardCondition + ",") == 0) {
            Matcher widened = Pattern.compile("(" + LLVM_LOCAL + ") = and i1 "
                    + Pattern.quote(guardCondition) + ", (" + LLVM_LOCAL + ")")
                    .matcher(String.join("\n", sourceGuard.lines()));
            Asserts.assertTrue(widened.find(), target + ": source guard conjunction");
            sourceGuard.assertPresent(widened.group(2)
                    + " = call i1 @llvm.experimental.widenable.condition()");
            guardCondition = widened.group(1);
        }
        sourceGuard.assertOccurrenceCount("br i1 " + guardCondition + ",", 1);
        List<String> guardTargets = sourceGuard.conditionalBranchTargets();
        Asserts.assertEquals(guardTargets.get(0), sourceInnerStore.block().label(),
                target + ": the source null-check success edge defines Inner.elems");
        retryBefore.blockByLabel(guardTargets.get(1))
                .assertOccurrenceCount("@llvm.experimental.deoptimize", 1);
        assertBranchLocalOopLoad(sourceInnerStore, target);

        Asserts.assertEquals(second.effectCount("Materialize", "[VO=0]"), 1L,
                target + ": virtual Outer materializes exactly once at its sink");
        Asserts.assertEquals(second.effectCount("Materialize", "[VO=1]"), 1L,
                target + ": virtual Inner materializes exactly once at the same sink");
        Asserts.assertEquals(second.effectCount("Materialize"), 2L,
                target + ": both nested objects have exactly one replay plan");
        Asserts.assertEquals(second.effectCount("EliminateStore", "[VO=1]"), 1L,
                target + ": Inner.elems remains virtual until the sink");
        Asserts.assertEquals(second.effectCount("EliminateStore", "[VO=0]"), 1L,
                target + ": Outer.iterator remains virtual until the sink");

        String outerResult = allocationResult(after, outerKey);
        String innerResult = allocationResult(after, innerKey);
        FieldStore retainedInnerStore = exactFieldStore(
                after, innerResult, innerElemsOffset, PEATestUtils.referencePointerType());
        Asserts.assertTrue(PEATestUtils.isEquivalentReferenceOperand(
                        after, retainedInnerStore.value(), sourceInnerStore.value()),
                target + ": Inner.elems replays the exact source payload SSA value");

        String consumeName = PEATestUtils.MethodId.of(consume).llvmFunctionName();
        PEATestUtils.IRBlock sink = after.blockContaining(consumeName, 0);
        Asserts.assertEquals(retainedInnerStore.block().label(), sink.label(),
                target + ": Inner.elems replays only in the sink block");
        int outerIteratorOffset = offset(TestWrapper.Outer.class, "iterator");
        FieldStore outerReplay = exactFieldStore(
                after, outerResult, outerIteratorOffset, PEATestUtils.referencePointerType());
        Asserts.assertEquals(outerReplay.block().label(), sink.label(),
                target + ": Outer.iterator replays only in the sink block");
        Asserts.assertTrue(PEATestUtils.isEquivalentReferenceOperand(
                        after, outerReplay.value(), innerResult),
                target + ": Outer.iterator replays the retained Inner SSA value");
        sink.assertBefore(retainedInnerStore.line(), 0, outerReplay.line(), 0);
        sink.assertBefore(outerReplay.line(), 0, consumeName, 0);

        PEATestUtils.PEAEffect materialize =
                second.uniqueEffect("Materialize", "[VO=0]");
        Asserts.assertTrue(materialize.detail().contains("block=%" + sink.label() + " "),
                target + ": Outer materialization effect targets the sink block");
        Asserts.assertTrue(materialize.detail().contains(outerResult + " = "),
                target + ": materialization effect names the retained Outer allocation");

        PEATestUtils.DeoptBundle loopBundle =
                uniqueVirtualLoopBundle(after, target);
        loopBundle.assertVirtualObjectIds(0, 1);
        PEATestUtils.VirtualObjectDescriptor outer = loopBundle.virtualObject(0);
        Asserts.assertEquals(outer.kind(), PEATestUtils.DescriptorKind.INSTANCE,
                target + ": loop descriptor is the virtual Outer instance");
        Asserts.assertEquals(outer.fields().keySet(), Set.of(outerIteratorOffset),
                target + ": virtual Outer carries exactly its iterator reference");
        PEATestUtils.VirtualObjectEntry iterator =
                outer.fields().get(outerIteratorOffset);
        Asserts.assertEquals(iterator.basicType(), PEATestUtils.DeoptBasicType.OBJECT,
                target + ": Outer.iterator deopt basic type");
        loopBundle.assertVORef(0, outerIteratorOffset, 1);
        PEATestUtils.VirtualObjectDescriptor inner = loopBundle.virtualObject(1);
        Asserts.assertEquals(inner.kind(), PEATestUtils.DescriptorKind.INSTANCE,
                target + ": loop descriptor is the virtual Inner instance");
        Asserts.assertEquals(inner.fields().keySet(), Set.of(innerElemsOffset),
                target + ": virtual Inner carries exactly its payload reference");
        PEATestUtils.VirtualObjectEntry elems = inner.fields().get(innerElemsOffset);
        Asserts.assertEquals(elems.basicType(), PEATestUtils.DeoptBasicType.OBJECT,
                target + ": Inner.elems deopt basic type");
        Asserts.assertEquals(elems.value().kind(),
                PEATestUtils.DeoptValueKind.MATERIALIZED_OOP,
                target + ": loop deopt state retains the real source payload");
        String payloadOperand = elems.value().operand();
        Asserts.assertTrue(payloadOperand.startsWith("ptr addrspace(1) "),
                target + ": payload descriptor contains a wide oop");
        String payloadValue = payloadOperand.substring("ptr addrspace(1) ".length());
        if (PEATestUtils.useCompressedOops()) {
            after.assertLineCount(payloadValue + " = addrspacecast "
                    + PEATestUtils.referencePointerType() + " " + sourceInnerStore.value()
                    + " to ptr addrspace(1)", 1);
        } else {
            Asserts.assertEquals(payloadValue, sourceInnerStore.value(),
                    target + ": loop descriptor names the exact source payload SSA value");
        }
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
            int result = loopNestedRefCanonicalization(source, 3);

            Asserts.assertEquals(result, 209804,
                    "exact loop and nested-reference result");
            Asserts.assertNotNull(escapedOuter,
                    "Outer materializes at the opaque sink");
            Asserts.assertNotNull(escapedOuter.iterator,
                    "materialized Outer preserves its Inner reference");
            Asserts.assertNotNull(escapedOuter.iterator.elems,
                    "materialized Inner preserves its branch-local elems field");
            Asserts.assertSame(escapedOuter.iterator.elems, payload,
                    "Outer replay reaches the exact source payload through Inner");
            Asserts.assertEquals(escapedOuter.iterator.elems.tag, 1234,
                    "nested payload remains readable after loop materialization");
            Asserts.assertNull(escapedInner,
                    "the conditionally escaping arm is unreachable at runtime");
            System.out.println("PEA-RESULT:" + result);
        }

        public static int loopNestedRefCanonicalization(Source source, int trips) {
            Guard guard = new Guard();
            guard.zero = 0;

            Outer outer = new Outer();
            Inner inner = new Inner();
            int state = 7;
            // Folding the virtual guard removes the opaque escape arm before
            // the next PEA round. Both nested objects can then stay virtual
            // through the loop and replay their exact references at the sink.
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
