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
 * @summary PEA preserves a nested virtual-object graph through an exception
 *          handler merge and reconstructs it at a forced post-handler deopt
 * @library /test/lib /
 * @modules java.base/jdk.internal.misc
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      -XX:-UseCompressedOops -XX:-UseCompressedClassPointers
 *      compiler.jeandle.pea.TestPEAExceptionHandlerGraphDeopt
 */

package compiler.jeandle.pea;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jdk.internal.misc.Unsafe;
import jdk.test.lib.Asserts;

public class TestPEAExceptionHandlerGraphDeopt {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestPEAExceptionHandlerGraphDeopt$TestWrapper";
    private static final String EXCEPTIONAL_PROPERTY =
            "compiler.jeandle.pea.exceptionHandlerGraphDeopt.exceptional";
    private static final String FINALIZER_CALLEE =
            "jeandle.register_finalizer_if_needed";
    private static final Unsafe UNSAFE = Unsafe.getUnsafe();
    private static final Pattern INVOKE_DESTINATIONS =
            Pattern.compile("^to label (%\\S+) unwind label (%\\S+)$");
    private static final Pattern BLOCK_LABEL =
            Pattern.compile("^([-A-Za-z$._0-9]+):(?: ;.*)?$");
    private static final Pattern CONDITIONAL_BRANCH = Pattern.compile(
            "^br i1 [^,]+, label (%\\S+), label (%\\S+)(?:, .*)?$");
    private static final Pattern UNCONDITIONAL_BRANCH =
            Pattern.compile("^br label (%\\S+)(?:, .*)?$");

    public static void main(String[] args) throws Exception {
        Method target = TestWrapper.class.getMethod(
                "test", TestWrapper.Holder.class);
        Method chainC = TestWrapper.class.getDeclaredMethod(
                "chainC", TestWrapper.Holder.class,
                TestWrapper.Outer.class, int.class);
        Method grandC = TestWrapper.class.getDeclaredMethod(
                "grandC", TestWrapper.Holder.class);
        Method requestDeopt = TestWrapper.class.getDeclaredMethod("requestDeopt");

        behaviorBuilder(target, chainC, grandC, requestDeopt, false)
                .runPEAOnOffEquivalent();
        behaviorBuilder(target, chainC, grandC, requestDeopt, true)
                .runPEAOnOffEquivalent();

        try (PEATestUtils.RunResult run =
                shapeBuilder(target, chainC, grandC, requestDeopt).run()) {
            assertShape(run, target, chainC, grandC, requestDeopt);
        }
    }

    private static PEATestUtils.RunBuilder behaviorBuilder(
            Method target, Method chainC, Method grandC,
            Method requestDeopt, boolean exceptional) {
        return PEATestUtils.behaviorRun(WRAPPER, target)
                .inline(chainC)
                .dontinline(grandC)
                .dontinline(requestDeopt)
                .extraFlags("-D" + EXCEPTIONAL_PROPERTY + "=" + exceptional);
    }

    private static PEATestUtils.RunBuilder shapeBuilder(
            Method target, Method chainC, Method grandC,
            Method requestDeopt) {
        return PEATestUtils.shapeRun(WRAPPER, target)
                .inline(chainC)
                .dontinline(grandC)
                .dontinline(requestDeopt)
                .extraFlags("-D" + EXCEPTIONAL_PROPERTY + "=false");
    }

    private static void assertShape(
            PEATestUtils.RunResult run, Method target, Method chainC,
            Method grandC, Method requestDeopt) throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        Asserts.assertEquals(report.roundCount(), 2,
                target + ": default PEA iteration count");
        Asserts.assertEquals(report.transformChangedRoundCount(), 2,
                target + ": allocation folding and follow-up CFG cleanup");
        Asserts.assertEquals(report.transformIdleRoundCount(), 0,
                target + ": both default rounds perform useful transforms");
        report.assertStoppedAtIterationCap();
        PEATestUtils.PEARound first = report.round(0);
        PEATestUtils.PEARound second = report.round(1);
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();

        before.assertAbsent(PEATestUtils.MethodId.of(chainC).llvmFunctionName());
        Asserts.assertEquals(before.allocationBCIs(), List.of(0, 20),
                target + ": exact outer and inlined-inner allocation BCIs");
        PEATestUtils.AllocationSite innerAllocation = before.allocations().stream()
                .filter(site -> site.key().kind()
                        == PEATestUtils.AllocationKind.INSTANCE)
                .filter(site -> site.key().bci() == 20)
                .findFirst().orElseThrow();

        InvokeEdge eliminatedAllocation = exactInvokeEdge(
                before, target, innerAllocation.result(), "@jeandle.new_instance(");
        InvokeEdge foldedFinalizer = exactInvokeEdge(
                before, target, FINALIZER_CALLEE, innerAllocation.result());
        String grandCName = PEATestUtils.MethodId.of(grandC).llvmFunctionName();
        InvokeEdge realThrower = exactInvokeEdge(before, target, grandCName);
        Asserts.assertNotEquals(
                eliminatedAllocation.lineIndex(), foldedFinalizer.lineIndex(),
                target + ": allocation and finalizer are distinct folded invokes");
        Asserts.assertNotEquals(foldedFinalizer.lineIndex(), realThrower.lineIndex(),
                target + ": folded finalizer and real thrower are distinct invokes");
        Asserts.assertNotEquals(
                foldedFinalizer.unwindDestination(), realThrower.unwindDestination(),
                target + ": folded and real invokes have distinct landingpads");
        assertDistinctSuccessors(foldedFinalizer, target, FINALIZER_CALLEE);
        assertDistinctSuccessors(realThrower, target, grandCName);
        String allocationHandler = handlerMergeDestination(
                before, eliminatedAllocation.unwindDestination(),
                target, "inlined inner allocation");
        String foldedHandler = handlerMergeDestination(
                before, foldedFinalizer.unwindDestination(), target, FINALIZER_CALLEE);
        String throwerHandler = handlerMergeDestination(
                before, realThrower.unwindDestination(), target, grandCName);
        Asserts.assertEquals(allocationHandler, foldedHandler,
                target + ": eliminated allocation and folded finalizer reach"
                        + " the same handler merge");
        Asserts.assertEquals(foldedHandler, throwerHandler,
                target + ": folded and real unwind paths reach the same handler");
        assertTypedCatchDestination(
                before, foldedHandler, target, "common handler");

        Asserts.assertTrue(first.hasStats(), target + ": round-0 PEA stats");
        Asserts.assertEquals(first.neverEscapes(), 2,
                target + ": outer and inner NeverEscape");
        Asserts.assertEquals(first.partiallyEscapes(), 0,
                target + ": no partial escape");
        Asserts.assertEquals(first.alwaysEscapes(), 0,
                target + ": no always escape");
        Asserts.assertEquals(effectCountForVO(first, "EliminateAllocation", 0), 1L,
                target + ": exact outer allocation elimination");
        Asserts.assertEquals(effectCountForVO(first, "EliminateAllocation", 1), 1L,
                target + ": exact inner allocation elimination");
        Asserts.assertEquals(effectCountForVO(first, "ReplaceCall", 1,
                        FINALIZER_CALLEE), 1L,
                target + ": exact inlined-inner finalizer fold");
        Asserts.assertEquals(effectCountForVO(first, "EliminateStore", 0), 2L,
                target + ": exact outer field-store eliminations");
        Asserts.assertEquals(effectCountForVO(first, "EliminateStore", 1), 1L,
                target + ": exact inner field-store elimination");
        Asserts.assertEquals(first.effectCount("Materialize"), 0L,
                target + ": handler graph is described rather than materialized");
        Asserts.assertTrue(second.hasStats(), target + ": round-1 PEA stats");
        Asserts.assertEquals(second.neverEscapes(), 0,
                target + ": no allocation remains for round 1");
        Asserts.assertEquals(second.partiallyEscapes(), 0,
                target + ": no partial escape remains for round 1");
        Asserts.assertEquals(second.alwaysEscapes(), 0,
                target + ": no always escape remains for round 1");

        after.assertAbsent(FINALIZER_CALLEE);
        after.assertAbsent(blockLabel(foldedFinalizer.unwindDestination()));
        after.assertPresent(grandCName);
        after.assertAbsent("poison");
        Asserts.assertEquals(after.allocationBCIs(), List.of(),
                target + ": both source allocations are eliminated");
        PEATestUtils.IRBody lowered = run.finalIR(target);
        Asserts.assertEquals(lowered.loweredAllocCount(), 0,
                target + ": no allocation survives lowering");
        lowered.assertAbsent("poison");

        String requestName = PEATestUtils.MethodId.of(requestDeopt).llvmFunctionName();
        PEATestUtils.DeoptBundle bundle = after.deoptBundleAtCall(requestName, 0);
        Asserts.assertEquals(bundle.scopes().size(), 1,
                target + ": requestDeopt has one root Java scope");
        Asserts.assertEquals(bundle.virtualObjects().size(), 2,
                target + ": exact outer and inner descriptors at requestDeopt");

        int outerValueOffset = offset(TestWrapper.Outer.class, "ox");
        int outerInnerOffset = offset(TestWrapper.Outer.class, "ref");
        int innerValueOffset = offset(TestWrapper.Inner.class, "ix");
        PEATestUtils.VirtualObjectDescriptor outer = null;
        PEATestUtils.VirtualObjectDescriptor inner = null;
        for (PEATestUtils.VirtualObjectDescriptor descriptor
                : bundle.virtualObjects().values()) {
            Asserts.assertEquals(descriptor.kind(),
                    PEATestUtils.DescriptorKind.INSTANCE,
                    target + ": graph descriptor kind");
            if (descriptor.fields().keySet().equals(
                    Set.of(outerValueOffset, outerInnerOffset))) {
                outer = descriptor;
            } else if (descriptor.fields().keySet().equals(Set.of(innerValueOffset))) {
                inner = descriptor;
            }
        }
        Asserts.assertNotNull(outer, target + ": exact outer descriptor");
        Asserts.assertNotNull(inner, target + ": exact inner descriptor");
        Asserts.assertNotEquals(outer.id(), inner.id(),
                target + ": distinct virtual identities");
        assertIntField(outer, outerValueOffset, 7, target);
        assertIntField(inner, innerValueOffset, 73, target);
        bundle.assertVORef(outer.id(), outerInnerOffset, inner.id());
        Asserts.assertEquals(scopeVORefCount(
                        bundle.rootScope().locals(), outer.id()), 2L,
                target + ": outer and alias are root-scope VORefs");
        Asserts.assertEquals(scopeVORefCount(
                        bundle.rootScope().locals(), inner.id()), 0L,
                target + ": inner is rooted through outer only");
    }

    private static void assertDistinctSuccessors(
            InvokeEdge edge, Method target, String callee) {
        Asserts.assertNotEquals(edge.normalDestination(), edge.unwindDestination(),
                target + ": invoke has distinct normal/unwind successors for " + callee);
    }

    private static InvokeEdge exactInvokeEdge(
            PEATestUtils.IRBody body, Method target, String... exactTokens) {
        InvokeEdge found = null;
        List<String> lines = body.lines();
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (!(line.startsWith("invoke ") || line.contains(" invoke "))
                    || !Arrays.stream(exactTokens).allMatch(line::contains)) {
                continue;
            }
            Asserts.assertTrue(index + 1 < lines.size(),
                    target + ": invoke destination continuation exists for "
                            + Arrays.toString(exactTokens));
            Matcher destinations = INVOKE_DESTINATIONS.matcher(lines.get(index + 1));
            Asserts.assertTrue(destinations.matches(),
                    target + ": anchored invoke destinations for "
                            + Arrays.toString(exactTokens));
            Asserts.assertNull(found,
                    target + ": exact tokens select one invoke "
                            + Arrays.toString(exactTokens));
            found = new InvokeEdge(index, destinations.group(1), destinations.group(2));
        }
        Asserts.assertNotNull(found,
                target + ": exact invoke for " + Arrays.toString(exactTokens));
        return found;
    }

    private static String handlerMergeDestination(
            PEATestUtils.IRBody body, String unwindDestination,
            Method target, String calleeName) {
        Map<String, ParsedBlock> blocks = parseBlocks(body);
        ParsedBlock unwind = requireBlock(
                blocks, unwindDestination, target, calleeName);
        Asserts.assertEquals(unwind.lines().stream()
                        .filter(line -> line.startsWith("%")
                                && line.contains(" = landingpad ")).count(), 1L,
                target + ": immediate unwind block has one landingpad for " + calleeName);
        Asserts.assertTrue(!unwind.lines().isEmpty(),
                target + ": non-empty unwind block for " + calleeName);
        Matcher branch = UNCONDITIONAL_BRANCH.matcher(
                unwind.lines().get(unwind.lines().size() - 1));
        Asserts.assertTrue(branch.matches(),
                target + ": unwind landingpad forwards to handler merge for " + calleeName);
        requireBlock(blocks, branch.group(1), target, calleeName);
        return branch.group(1);
    }

    private static void assertTypedCatchDestination(
            PEATestUtils.IRBody body, String handlerDestination,
            Method target, String context) {
        Map<String, ParsedBlock> blocks = parseBlocks(body);
        ParsedBlock handler = requireBlock(
                blocks, handlerDestination, target, context);
        List<String> unwindSuccessors =
                conditionalSuccessors(handler, target, context);
        String dispatch = uniqueSuccessor(
                blocks, unwindSuccessors, true, target, context,
                "exception-dispatch");
        String typeCheck = uniqueSuccessor(
                blocks, unwindSuccessors, false, target, context,
                "type-check");
        ParsedBlock checkBlock = requireBlock(blocks, typeCheck, target, context);
        Asserts.assertEquals(checkBlock.lines().stream()
                        .filter(line -> line.contains("@jeandle.check_instanceof(")).count(), 1L,
                target + ": exact exception type check for " + context);

        List<String> checkSuccessors =
                conditionalSuccessors(checkBlock, target, context);
        Asserts.assertTrue(checkSuccessors.contains(dispatch),
                target + ": type-check failure reaches the same dispatch for " + context);
        String catchDestination = checkSuccessors.stream()
                .filter(successor -> !successor.equals(dispatch))
                .findFirst().orElseThrow();
        requireBlock(blocks, catchDestination, target, context);
    }

    private static Map<String, ParsedBlock> parseBlocks(PEATestUtils.IRBody body) {
        Map<String, ParsedBlock> blocks = new LinkedHashMap<>();
        java.util.ArrayList<String> currentLines = null;
        for (String line : body.lines()) {
            Matcher label = BLOCK_LABEL.matcher(line);
            if (label.matches()) {
                String currentLabel = "%" + label.group(1);
                currentLines = new java.util.ArrayList<>();
                ParsedBlock previous = blocks.put(
                        currentLabel, new ParsedBlock(currentLabel, currentLines));
                Asserts.assertNull(
                        previous, body.methodId() + ": duplicate block " + currentLabel);
            } else if (currentLines != null) {
                currentLines.add(line);
            }
        }
        return blocks;
    }

    private static ParsedBlock requireBlock(
            Map<String, ParsedBlock> blocks, String label,
            Method target, String calleeName) {
        ParsedBlock block = blocks.get(label);
        Asserts.assertNotNull(
                block, target + ": missing CFG block " + label + " for " + calleeName);
        return block;
    }

    private static List<String> conditionalSuccessors(
            ParsedBlock block, Method target, String calleeName) {
        Asserts.assertTrue(!block.lines().isEmpty(),
                target + ": non-empty CFG block " + block.label() + " for " + calleeName);
        String terminator = block.lines().get(block.lines().size() - 1);
        Matcher branch = CONDITIONAL_BRANCH.matcher(terminator);
        Asserts.assertTrue(branch.matches(),
                target + ": anchored conditional terminator in " + block.label()
                        + " for " + calleeName);
        Asserts.assertNotEquals(branch.group(1), branch.group(2),
                target + ": distinct successors in " + block.label());
        return List.of(branch.group(1), branch.group(2));
    }

    private static String uniqueSuccessor(
            Map<String, ParsedBlock> blocks, List<String> successors,
            boolean dispatch, Method target, String calleeName, String role) {
        List<String> matches = successors.stream()
                .filter(successor -> isExceptionDispatch(
                        requireBlock(blocks, successor, target, calleeName)) == dispatch)
                .toList();
        Asserts.assertEquals(matches.size(), 1,
                target + ": unique " + role + " successor for " + calleeName);
        return matches.get(0);
    }

    private static boolean isExceptionDispatch(ParsedBlock block) {
        return block.lines().stream()
                .anyMatch(line -> line.contains("@install_exceptional_return("));
    }

    private static String blockLabel(String destination) {
        Asserts.assertTrue(destination.startsWith("%"),
                "LLVM destination has a percent-prefixed label");
        return destination.substring(1) + ":";
    }

    private static long effectCountForVO(
            PEATestUtils.PEARound round, String kind,
            int objectId, String... detailParts) {
        String objectToken = "[VO=" + objectId + "]";
        return round.effects().stream()
                .filter(effect -> effect.kind().equals(kind))
                .filter(effect -> Arrays.asList(
                        effect.detail().split("\\s+")).contains(objectToken))
                .filter(effect -> Arrays.stream(detailParts)
                        .allMatch(effect.detail()::contains))
                .count();
    }

    private static long scopeVORefCount(
            Map<Integer, PEATestUtils.DeoptValue> slots, int objectId) {
        return slots.values().stream()
                .filter(value -> value.kind() == PEATestUtils.DeoptValueKind.VO_REF)
                .filter(value -> value.virtualObjectId() == objectId)
                .count();
    }

    private static void assertIntField(
            PEATestUtils.VirtualObjectDescriptor descriptor,
            int fieldOffset, int expected, Method target) {
        PEATestUtils.VirtualObjectEntry entry = descriptor.fields().get(fieldOffset);
        Asserts.assertNotNull(entry,
                target + ": missing integer field at offset " + fieldOffset);
        Asserts.assertEquals(entry.basicType(), PEATestUtils.DeoptBasicType.INT,
                target + ": integer field basic type");
        Asserts.assertEquals(entry.value().kind(), PEATestUtils.DeoptValueKind.SCALAR,
                target + ": integer field value kind");
        Asserts.assertEquals(entry.value().operand(), "i32 " + expected,
                target + ": integer field value");
    }

    private static int offset(Class<?> holder, String fieldName) throws Exception {
        Field field = holder.getDeclaredField(fieldName);
        return Math.toIntExact(UNSAFE.objectFieldOffset(field));
    }

    private record InvokeEdge(
            int lineIndex, String normalDestination, String unwindDestination) {}

    private record ParsedBlock(String label, List<String> lines) {}

    public static class TestWrapper {
        private static final Method DEOPT_TARGET = target();
        private static final int NORMAL_RESULT = 110_773;
        private static final int EXCEPTIONAL_RESULT = 773;

        public static class Holder {
            public int h;
        }

        public static class Outer {
            public int ox;
            public Object ref;
        }

        public static class Inner {
            public int ix;
        }

        public static void main(String[] args) throws Exception {
            new Holder();
            new Outer();
            new Inner();
            PEATestUtils.compileConfiguredTargetsAtLevel4();

            boolean exceptional = Boolean.getBoolean(EXCEPTIONAL_PROPERTY);
            Holder input = null;
            if (!exceptional) {
                input = new Holder();
                input.h = 11;
            }
            int actual = test(input);
            int expected = exceptional ? EXCEPTIONAL_RESULT : NORMAL_RESULT;
            Asserts.assertEquals(actual, expected,
                    exceptional ? "exceptional handler reconstruction"
                                : "normal handler reconstruction");
            System.out.println("PEA-RESULT:" + actual);
        }

        public static int test(Holder input) {
            Outer outer = new Outer();
            outer.ox = 7;
            Outer alias = outer;
            int pathValue;
            try {
                pathValue = chainC(input, outer, 72);
            } catch (NullPointerException expected) {
                pathValue = 0;
            }

            requestDeopt();

            Inner first = (Inner) outer.ref;
            Inner second = (Inner) alias.ref;
            if (outer != alias || first == null || first != second
                    || outer.ref != first) {
                return Integer.MIN_VALUE + 1;
            }
            if (outer.ox != 7 || first.ix != 73) {
                return Integer.MIN_VALUE + 2;
            }
            return pathValue * 10_000 + outer.ox * 100 + first.ix;
        }

        private static int chainC(
                Holder input, Outer outer, int value) {
            int adjusted = value + 1;
            Inner inner = new Inner();
            inner.ix = adjusted;
            outer.ref = inner;
            return grandC(input);
        }

        private static int grandC(Holder input) {
            return input.h;
        }

        private static void requestDeopt() {
            PEATestUtils.ActiveFrameDeoptEvidence evidence =
                    PEATestUtils.deoptimizeActiveFrame(DEOPT_TARGET, 2);
            if (!evidence.frameDeoptimized()
                    || evidence.compilationLevel() != 4
                    || evidence.markedNMethods() != 1) {
                throw new AssertionError("exact active-frame deopt evidence");
            }
        }

        private static Method target() {
            try {
                return TestWrapper.class.getMethod("test", Holder.class);
            } catch (ReflectiveOperationException failure) {
                throw new ExceptionInInitializerError(failure);
            }
        }
    }
}
