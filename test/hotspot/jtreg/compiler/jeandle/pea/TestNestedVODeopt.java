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
 * @summary PEA preserves nested and shared object state across exact active-frame
 *          deoptimization and staged materialization
 * @library /test/lib /
 * @modules java.base/jdk.internal.misc
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      -XX:-UseCompressedOops -XX:-UseCompressedClassPointers
 *      compiler.jeandle.pea.TestNestedVODeopt
 */

package compiler.jeandle.pea;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jdk.internal.misc.Unsafe;
import jdk.test.lib.Asserts;

public class TestNestedVODeopt {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestNestedVODeopt$TestWrapper";
    private static final Unsafe UNSAFE = Unsafe.getUnsafe();
    private static final Pattern JAVA_KLASS =
            Pattern.compile("\"java-klass\"=\"([0-9]+)\"");
    private static final String LLVM_LOCAL =
            "(?:%[-A-Za-z$._0-9]+|%\"(?:[^\"\\\\]|\\\\.)*\")";

    public static void main(String[] args) throws Exception {
        Method nested = TestWrapper.class.getMethod(
                "testNestedDeopt", int.class);
        Method staged = TestWrapper.class.getMethod(
                "testStagedMaterialization", int.class, int.class);
        Method nestedDeopt =
                TestWrapper.class.getDeclaredMethod("requestNestedDeopt");
        Method stagedDeopt =
                TestWrapper.class.getDeclaredMethod("requestStagedDeopt");
        Method consumeInner = TestWrapper.class.getDeclaredMethod(
                "consumeInner", TestWrapper.Inner.class);
        Method consumeOuter = TestWrapper.class.getDeclaredMethod(
                "consumeOuter", TestWrapper.Outer.class);
        Method[] targets = {nested, staged};

        runBuilder(false, targets, nestedDeopt, stagedDeopt,
                consumeInner, consumeOuter).runPEAOnOffEquivalent();

        try (PEATestUtils.RunResult run =
                runBuilder(true, targets, nestedDeopt, stagedDeopt,
                        consumeInner, consumeOuter).run()) {
            assertNestedShape(run, nested, nestedDeopt);
            assertStagedShape(run, staged, stagedDeopt,
                    consumeInner, consumeOuter);
        }
    }

    private static PEATestUtils.RunBuilder runBuilder(
            boolean shape, Method[] targets, Method nestedDeopt,
            Method stagedDeopt, Method consumeInner, Method consumeOuter) {
        PEATestUtils.RunBuilder builder = shape
                ? PEATestUtils.shapeRun(WRAPPER, targets)
                : PEATestUtils.behaviorRun(WRAPPER, targets);
        return builder.dontinline(nestedDeopt)
                .dontinline(stagedDeopt)
                .dontinline(consumeInner)
                .dontinline(consumeOuter);
    }

    private static void assertNestedShape(
            PEATestUtils.RunResult run, Method target, Method requestDeopt)
            throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.PEARound first = report.round(0);
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();

        List<PEATestUtils.AllocationSite> allocations = before.allocations();
        Asserts.assertEquals(allocations.size(), 2,
                target + ": exact outer and inner source allocations");
        Asserts.assertEquals(new HashSet<>(before.allocationBCIs()).size(), 2,
                target + ": outer and inner have distinct source BCIs");
        Asserts.assertEquals(first.neverEscapes(), 2,
                target + ": both objects are virtual at every program point");
        Asserts.assertEquals(first.partiallyEscapes(), 0,
                target + ": no nested object materializes");
        Asserts.assertEquals(first.alwaysEscapes(), 0,
                target + ": no nested object escapes");
        for (int vo = 0; vo < 2; vo++) {
            Asserts.assertEquals(first.effectCount(
                    "EliminateAllocation", "[VO=" + vo + "]"), 1L,
                    target + ": one exact allocation elimination for VO " + vo);
        }
        after.assertRetainsExactlyOriginalAllocations(before);
        Asserts.assertEquals(run.finalIR(target).loweredAllocCount(), 0,
                target + ": no lowered allocation remains");

        String callee = PEATestUtils.MethodId.of(requestDeopt).llvmFunctionName();
        assertUniqueCall(before, after, callee, target);
        PEATestUtils.DeoptBundle source = before.deoptBundleAtCall(callee, 0);
        PEATestUtils.DeoptBundle reconstructed = after.deoptBundleAtCall(callee, 0);
        Asserts.assertEquals(source.virtualObjects().size(), 0,
                target + ": frontend helper call has no PEA descriptors");
        Asserts.assertEquals(reconstructed.rootScope().bci(),
                source.rootScope().bci(),
                target + ": descriptor rewrite preserves the exact deopt BCI");
        reconstructed.assertVirtualObjectIds(0, 1);
        NestedDescriptors graph = identifyNestedDescriptors(reconstructed);
        assertDescriptorKlass(graph.outer(), allocations.get(0), target);
        assertDescriptorKlass(graph.inner(), allocations.get(1), target);
        String seed = intArgument(after, 0);
        assertOuterDescriptor(reconstructed, graph.outer(), graph.inner(),
                PEATestUtils.DeoptValueKind.VO_REF, after, seed, 5);
        assertInnerDescriptor(graph.inner(), after, seed, 3);
    }

    private static void assertStagedShape(
            PEATestUtils.RunResult run, Method target, Method requestDeopt,
            Method consumeInner, Method consumeOuter) throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.PEARound first = report.round(0);
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();
        List<PEATestUtils.AllocationSite> allocations = before.allocations();

        Asserts.assertEquals(allocations.size(), 2,
                target + ": exact outer and inner source allocations");
        List<PEATestUtils.AllocationKey> keys =
                allocations.stream().map(PEATestUtils.AllocationSite::key).toList();
        Asserts.assertEquals(new HashSet<>(keys).size(), 2,
                target + ": exact typed source allocation identities");
        Asserts.assertEquals(first.neverEscapes(), 0,
                target + ": both objects have an escaping execution");
        Asserts.assertEquals(first.partiallyEscapes(), 2,
                target + ": both allocations remain virtual on mode zero");
        Asserts.assertEquals(first.alwaysEscapes(), 0,
                target + ": neither object escapes on every path");
        for (PEATestUtils.PEARound round : report.rounds()) {
            Asserts.assertEquals(round.effectCount("Materialize", "[VO=0]"), 1L,
                    target + ": outer materializes exactly once in round "
                            + round.iteration());
            Asserts.assertEquals(round.effectCount("Materialize", "[VO=1]"), 1L,
                    target + ": inner materializes exactly once in round "
                            + round.iteration());
            Asserts.assertEquals(round.effectCount("Materialize"), 2L,
                    target + ": exact staged materialization count in round "
                            + round.iteration());
        }
        after.assertRetainsExactlyOriginalAllocations(
                before, keys.toArray(PEATestUtils.AllocationKey[]::new));
        Asserts.assertEquals(run.finalIR(target).loweredAllocCount(), 2,
                target + ": final code contains only the two OrigAlloc sites");
        List<PEATestUtils.AllocationSite> finalAllocations =
                after.allocations();
        Asserts.assertEquals(finalAllocations.size(), 2,
                target + ": final IR retains exactly the outer and inner allocations");
        String sourceOuter = allocations.get(0).result();
        String sourceInner = allocations.get(1).result();
        String outerResult =
                allocationResult(after, allocations.get(0).key());
        String innerResult =
                allocationResult(after, allocations.get(1).key());

        String deoptCallee =
                PEATestUtils.MethodId.of(requestDeopt).llvmFunctionName();
        assertUniqueCall(before, after, deoptCallee, target);
        PEATestUtils.DeoptBundle source =
                before.deoptBundleAtCall(deoptCallee, 0);
        PEATestUtils.DeoptBundle bundle =
                after.deoptBundleAtCall(deoptCallee, 0);
        Asserts.assertEquals(source.virtualObjects().size(), 0,
                target + ": frontend staged call has no PEA descriptors");
        Asserts.assertEquals(bundle.rootScope().bci(), source.rootScope().bci(),
                target + ": staged descriptor preserves the exact call BCI");
        bundle.assertVirtualObjectIds(0);
        PEATestUtils.VirtualObjectDescriptor outer = bundle.virtualObject(0);
        assertDescriptorKlass(outer, allocations.get(0), target);
        String afterSeed = intArgument(after, 0);
        assertOuterDescriptor(bundle, outer, null,
                PEATestUtils.DeoptValueKind.MATERIALIZED_OOP,
                after, afterSeed, 11);
        PEATestUtils.VirtualObjectEntry firstChild =
                outer.fields().get(offset(TestWrapper.Outer.class, "first"));
        PEATestUtils.VirtualObjectEntry secondChild =
                outer.fields().get(offset(TestWrapper.Outer.class, "second"));
        Asserts.assertEquals(firstChild.value().operand(),
                secondChild.value().operand(),
                target + ": two fields carry the same materialized child oop");
        Asserts.assertEquals(firstChild.value().operand(),
                "ptr addrspace(1) " + innerResult,
                target + ": descriptor fields carry the retained inner OrigAlloc");

        String innerCallee =
                PEATestUtils.MethodId.of(consumeInner).llvmFunctionName();
        String outerCallee =
                PEATestUtils.MethodId.of(consumeOuter).llvmFunctionName();
        PEATestUtils.IRBlock innerBlock = after.blockContaining(innerCallee, 0);
        PEATestUtils.IRBlock outerBlock = after.blockContaining(outerCallee, 0);
        innerBlock.assertAbsent("jeandle.new_instance");
        outerBlock.assertAbsent("jeandle.new_instance");
        int innerValueOffset = offset(TestWrapper.Inner.class, "value");
        assertSourceScalarStore(before, sourceInner, innerValueOffset,
                intArgument(before, 0), 7);
        assertScalarReplay(after, innerBlock, innerResult, innerValueOffset,
                afterSeed, 7, innerCallee);
        innerBlock.assertOccurrenceCount("getelementptr", 1);
        innerBlock.assertOccurrenceCount("store atomic", 1);
        innerBlock.assertOccurrenceCount("store atomic i32", 1);
        innerBlock.assertOccurrenceCount("store atomic ptr addrspace(1)", 0);

        int markerOffset = offset(TestWrapper.Outer.class, "marker");
        int firstOffset = offset(TestWrapper.Outer.class, "first");
        int secondOffset = offset(TestWrapper.Outer.class, "second");
        assertAtomicIntUpdate(after, outerBlock, innerResult,
                innerValueOffset, 17, outerCallee);
        assertSourceScalarStore(before, sourceOuter, markerOffset,
                intArgument(before, 0), 11);
        assertScalarReplay(after, outerBlock, outerResult, markerOffset,
                afterSeed, 24, outerCallee);
        assertReferenceReplay(after, outerBlock, outerResult, firstOffset,
                "ptr addrspace(1)", innerResult, outerCallee);
        assertReferenceReplay(after, outerBlock, outerResult, secondOffset,
                "ptr addrspace(1)", innerResult, outerCallee);
        outerBlock.assertOccurrenceCount("getelementptr", 4);
        outerBlock.assertOccurrenceCount("load atomic i32", 1);
        outerBlock.assertOccurrenceCount("store atomic", 4);
        outerBlock.assertOccurrenceCount("store atomic i32", 2);
        outerBlock.assertOccurrenceCount("store atomic ptr addrspace(1)", 2);
        outerBlock.assertOccurrenceCount(
                "store atomic ptr addrspace(1) " + innerResult + ",", 2);
    }

    private static NestedDescriptors identifyNestedDescriptors(
            PEATestUtils.DeoptBundle bundle) throws Exception {
        Set<Integer> outerOffsets = Set.of(
                offset(TestWrapper.Outer.class, "first"),
                offset(TestWrapper.Outer.class, "second"),
                offset(TestWrapper.Outer.class, "marker"));
        Set<Integer> innerOffsets =
                Set.of(offset(TestWrapper.Inner.class, "value"));
        PEATestUtils.VirtualObjectDescriptor outer = null;
        PEATestUtils.VirtualObjectDescriptor inner = null;
        for (PEATestUtils.VirtualObjectDescriptor descriptor
                : bundle.virtualObjects().values()) {
            Asserts.assertEquals(descriptor.kind(),
                    PEATestUtils.DescriptorKind.INSTANCE,
                    "nested graph contains only instance descriptors");
            if (descriptor.fields().keySet().equals(outerOffsets)) {
                Asserts.assertNull(outer, "one exact outer descriptor");
                outer = descriptor;
            } else if (descriptor.fields().keySet().equals(innerOffsets)) {
                Asserts.assertNull(inner, "one exact inner descriptor");
                inner = descriptor;
            } else {
                throw new AssertionError(
                        "unexpected nested descriptor " + descriptor.id());
            }
        }
        Asserts.assertNotNull(outer, "outer descriptor");
        Asserts.assertNotNull(inner, "inner descriptor");
        return new NestedDescriptors(outer, inner);
    }

    private static void assertOuterDescriptor(
            PEATestUtils.DeoptBundle bundle,
            PEATestUtils.VirtualObjectDescriptor outer,
            PEATestUtils.VirtualObjectDescriptor inner,
            PEATestUtils.DeoptValueKind childKind,
            PEATestUtils.IRBody body, String seed, int markerDelta)
            throws Exception {
        Set<Integer> offsets = Set.of(
                offset(TestWrapper.Outer.class, "first"),
                offset(TestWrapper.Outer.class, "second"),
                offset(TestWrapper.Outer.class, "marker"));
        Asserts.assertEquals(outer.kind(),
                PEATestUtils.DescriptorKind.INSTANCE,
                "outer descriptor kind");
        Asserts.assertEquals(outer.fields().keySet(), offsets,
                "outer exact touched fields");
        for (String name : List.of("first", "second")) {
            int fieldOffset = offset(TestWrapper.Outer.class, name);
            PEATestUtils.VirtualObjectEntry entry =
                    outer.fields().get(fieldOffset);
            Asserts.assertEquals(entry.basicType(),
                    PEATestUtils.DeoptBasicType.OBJECT,
                    name + " object basic type");
            Asserts.assertEquals(entry.value().kind(), childKind,
                    name + " child representation");
            if (childKind == PEATestUtils.DeoptValueKind.VO_REF) {
                bundle.assertVORef(outer.id(), fieldOffset, inner.id());
            }
        }
        PEATestUtils.VirtualObjectEntry marker =
                outer.fields().get(offset(TestWrapper.Outer.class, "marker"));
        Asserts.assertEquals(marker.basicType(),
                PEATestUtils.DeoptBasicType.INT, "outer marker type");
        Asserts.assertEquals(marker.value().kind(),
                PEATestUtils.DeoptValueKind.SCALAR, "outer marker value");
        assertAffineI32(body, marker.value().operand(), seed, markerDelta,
                "outer marker descriptor value");
    }

    private static void assertInnerDescriptor(
            PEATestUtils.VirtualObjectDescriptor inner,
            PEATestUtils.IRBody body, String seed, int valueDelta)
            throws Exception {
        Asserts.assertEquals(inner.kind(),
                PEATestUtils.DescriptorKind.INSTANCE,
                "inner descriptor kind");
        int valueOffset = offset(TestWrapper.Inner.class, "value");
        Asserts.assertEquals(inner.fields().keySet(), Set.of(valueOffset),
                "inner exact touched field");
        PEATestUtils.VirtualObjectEntry value =
                inner.fields().get(valueOffset);
        Asserts.assertEquals(value.basicType(),
                PEATestUtils.DeoptBasicType.INT, "inner value type");
        Asserts.assertEquals(value.value().kind(),
                PEATestUtils.DeoptValueKind.SCALAR, "inner scalar value");
        assertAffineI32(body, value.value().operand(), seed, valueDelta,
                "inner descriptor value");
    }

    private static void assertDescriptorKlass(
            PEATestUtils.VirtualObjectDescriptor descriptor,
            PEATestUtils.AllocationSite allocation, Method target) {
        Matcher matcher = JAVA_KLASS.matcher(allocation.instruction());
        Asserts.assertTrue(matcher.find(),
                target + ": source allocation has an exact java-klass");
        Asserts.assertEquals(descriptor.klassOperand(),
                "i64 " + matcher.group(1),
                target + ": descriptor klass matches its source allocation");
    }

    private record ReplayStore(String slot, String value, String line) {}

    private record AffineI32(int seedCoefficient, int constant) {}

    private static ReplayStore exactReplayStore(
            PEATestUtils.IRBody body, PEATestUtils.IRBlock block,
            String owner, int offset, String type) {
        List<String> lines = block.lines();
        Pattern gep = gepPattern(owner, offset);
        java.util.ArrayList<String> slots = new java.util.ArrayList<>();
        for (String line : lines) {
            Matcher matcher = gep.matcher(line);
            if (matcher.matches()) {
                slots.add(matcher.group(1));
            }
        }
        Asserts.assertEquals(slots.size(), 1,
                body.methodId() + ": one replay GEP for " + owner
                        + " at offset " + offset);
        Pattern pattern = Pattern.compile("^store atomic "
                + Pattern.quote(type)
                + " (.+), ptr addrspace\\(1\\) "
                + Pattern.quote(slots.get(0))
                + " unordered, align \\d+(?:, .*)?$");
        java.util.ArrayList<ReplayStore> stores = new java.util.ArrayList<>();
        for (String line : lines) {
            Matcher matcher = pattern.matcher(line);
            if (matcher.matches()) {
                stores.add(new ReplayStore(
                        slots.get(0), matcher.group(1), line));
            }
        }
        Asserts.assertEquals(stores.size(), 1,
                body.methodId() + ": one exact replay store through "
                        + slots.get(0));
        return stores.get(0);
    }

    private static void assertScalarReplay(
            PEATestUtils.IRBody body, PEATestUtils.IRBlock block,
            String owner, int offset, String seed, int delta,
            String consumer) {
        ReplayStore replay = exactReplayStore(
                body, block, owner, offset, "i32");
        assertAffineI32(body, replay.value(), seed, delta,
                "scalar replay for " + owner + " at offset " + offset);
        block.assertBefore(replay.line(), 0, consumer, 0);
    }

    private static void assertReferenceReplay(
            PEATestUtils.IRBody body, PEATestUtils.IRBlock block,
            String owner, int offset, String type, String value,
            String consumer) {
        ReplayStore replay = exactReplayStore(
                body, block, owner, offset, type);
        Asserts.assertEquals(replay.value(), value,
                body.methodId() + ": exact reference replay value for "
                        + owner + " at offset " + offset);
        block.assertBefore(replay.line(), 0, consumer, 0);
    }

    private static void assertSourceScalarStore(
            PEATestUtils.IRBody body, String owner, int offset,
            String seed, int delta) {
        Pattern gep = gepPattern(owner, offset);
        java.util.HashSet<String> slots = new java.util.HashSet<>();
        for (String line : body.lines()) {
            Matcher matcher = gep.matcher(line);
            if (matcher.matches()) {
                slots.add(matcher.group(1));
            }
        }
        Asserts.assertTrue(!slots.isEmpty(),
                body.methodId() + ": source GEP for " + owner
                        + " at offset " + offset);
        int matches = 0;
        for (String slot : slots) {
            Pattern store = Pattern.compile(
                    "^store atomic i32 (.+), ptr addrspace\\(1\\) "
                    + Pattern.quote(slot)
                    + " unordered, align \\d+(?:, .*)?$");
            for (String line : body.lines()) {
                Matcher matcher = store.matcher(line);
                if (matcher.matches()
                        && isAffineI32(
                                body, matcher.group(1), seed, delta)) {
                    matches++;
                }
            }
        }
        Asserts.assertEquals(matches, 1,
                body.methodId() + ": one exact source scalar store for "
                        + owner + " at offset " + offset);
    }

    private static void assertAtomicIntUpdate(
            PEATestUtils.IRBody body, PEATestUtils.IRBlock block,
            String owner, int offset, int delta, String consumer) {
        List<String> lines = block.lines();
        Pattern gep = gepPattern(owner, offset);
        java.util.ArrayList<String> slots = new java.util.ArrayList<>();
        for (String line : lines) {
            Matcher matcher = gep.matcher(line);
            if (matcher.matches()) {
                slots.add(matcher.group(1));
            }
        }
        Asserts.assertEquals(slots.size(), 1,
                body.methodId() + ": one update GEP for " + owner
                        + " at offset " + offset);

        Pattern loadPattern = Pattern.compile("^(" + LLVM_LOCAL + ")"
                + " = load atomic i32, ptr addrspace\\(1\\) "
                + Pattern.quote(slots.get(0))
                + " unordered, align \\d+(?:, .*)?$");
        java.util.ArrayList<String> loads = new java.util.ArrayList<>();
        String loadedValue = null;
        for (String line : lines) {
            Matcher load = loadPattern.matcher(line);
            if (load.matches()) {
                loads.add(line);
                loadedValue = load.group(1);
            }
        }
        Asserts.assertEquals(loads.size(), 1,
                body.methodId() + ": one atomic int load through "
                        + slots.get(0));
        Pattern addPattern = Pattern.compile("^(" + LLVM_LOCAL
                + ") = add(?: nuw)?(?: nsw)? i32 "
                + "(?:" + Pattern.quote(loadedValue) + ", " + delta
                + "|" + delta + ", " + Pattern.quote(loadedValue)
                + ")(?:, .*)?$");
        java.util.ArrayList<String> adds = new java.util.ArrayList<>();
        String updatedValue = null;
        for (String line : lines) {
            Matcher add = addPattern.matcher(line);
            if (add.matches()) {
                adds.add(line);
                updatedValue = add.group(1);
            }
        }
        Asserts.assertEquals(adds.size(), 1,
                body.methodId() + ": one exact +" + delta
                        + " update of the loaded int");
        Pattern storePattern = Pattern.compile("^store atomic i32 "
                + Pattern.quote(updatedValue)
                + ", ptr addrspace\\(1\\) " + Pattern.quote(slots.get(0))
                + " unordered, align \\d+(?:, .*)?$");
        java.util.ArrayList<String> stores = new java.util.ArrayList<>();
        for (String line : lines) {
            if (storePattern.matcher(line).matches()) {
                stores.add(line);
            }
        }
        Asserts.assertEquals(stores.size(), 1,
                body.methodId() + ": one atomic int store through "
                        + slots.get(0));
        block.assertBefore(loads.get(0), 0, adds.get(0), 0);
        block.assertBefore(adds.get(0), 0, stores.get(0), 0);
        block.assertBefore(stores.get(0), 0, consumer, 0);
    }

    private static String intArgument(PEATestUtils.IRBody body, int index) {
        String marker = "@\"" + body.methodId().llvmFunctionName() + "\"(";
        Pattern argument = Pattern.compile(
                "(?:^|, )i32(?: [a-z][a-z0-9]*(?:\\([^)]*\\))?)* ("
                + LLVM_LOCAL + ")(?=,|\\))");
        java.util.ArrayList<String> arguments = new java.util.ArrayList<>();
        for (String line : body.lines()) {
            int start = line.indexOf(marker);
            if (start < 0 || !line.startsWith("define ")) {
                continue;
            }
            Matcher matcher = argument.matcher(
                    line.substring(start + marker.length()));
            while (matcher.find()) {
                arguments.add(matcher.group(1));
            }
        }
        Asserts.assertTrue(index < arguments.size(),
                body.methodId() + ": i32 argument " + index);
        return arguments.get(index);
    }

    private static void assertAffineI32(
            PEATestUtils.IRBody body, String typedOrRawOperand,
            String seed, int delta, String detail) {
        String operand = typedOrRawOperand.startsWith("i32 ")
                ? typedOrRawOperand.substring(4) : typedOrRawOperand;
        AffineI32 affine = affineI32(
                body, operand, seed, new HashSet<>());
        Asserts.assertEquals(affine, new AffineI32(1, delta),
                body.methodId() + ": " + detail);
    }

    private static boolean isAffineI32(
            PEATestUtils.IRBody body, String operand,
            String seed, int delta) {
        AffineI32 affine = affineI32(
                body, operand, seed, new HashSet<>());
        return new AffineI32(1, delta).equals(affine);
    }

    private static AffineI32 affineI32(
            PEATestUtils.IRBody body, String operand,
            String seed, Set<String> visiting) {
        if (operand.equals(seed)) {
            return new AffineI32(1, 0);
        }
        if (operand.matches("-?\\d+")) {
            return new AffineI32(0, Integer.parseInt(operand));
        }
        if (!operand.matches(LLVM_LOCAL) || !visiting.add(operand)) {
            return null;
        }
        Pattern arithmetic = Pattern.compile("^" + Pattern.quote(operand)
                + " = (add|sub)(?: nuw)?(?: nsw)? i32 ("
                + LLVM_LOCAL + "|-?\\d+), (" + LLVM_LOCAL
                + "|-?\\d+)(?:, .*)?$");
        AffineI32 result = null;
        for (String line : body.lines()) {
            Matcher matcher = arithmetic.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            AffineI32 left = affineI32(
                    body, matcher.group(2), seed, visiting);
            AffineI32 right = affineI32(
                    body, matcher.group(3), seed, visiting);
            if (left == null || right == null) {
                break;
            }
            int sign = matcher.group(1).equals("add") ? 1 : -1;
            result = new AffineI32(
                    left.seedCoefficient()
                            + sign * right.seedCoefficient(),
                    left.constant() + sign * right.constant());
            break;
        }
        visiting.remove(operand);
        return result;
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

    private static void assertUniqueCall(
            PEATestUtils.IRBody before, PEATestUtils.IRBody after,
            String callee, Method target) {
        String invocation = "@\"" + callee + "\"(";
        Asserts.assertEquals(before.occurrenceCount(invocation), 1,
                target + ": one exact frontend call to " + callee);
        Asserts.assertEquals(after.occurrenceCount(invocation), 1,
                target + ": one exact final call to " + callee);
        int bci = before.deoptBundleAtCall(callee, 0).rootScope().bci();
        Asserts.assertEquals(before.callOccurrencesAtBCI(callee, bci), List.of(0),
                target + ": exact callee is uniquely bound to source BCI " + bci);
        Asserts.assertEquals(after.callOccurrencesAtBCI(callee, bci), List.of(0),
                target + ": final callee preserves unique source BCI " + bci);
    }

    private static int offset(Class<?> holder, String name) throws Exception {
        Field field = holder.getDeclaredField(name);
        return Math.toIntExact(UNSAFE.objectFieldOffset(field));
    }

    private record NestedDescriptors(
            PEATestUtils.VirtualObjectDescriptor outer,
            PEATestUtils.VirtualObjectDescriptor inner) {}

    public static class TestWrapper {
        private static final Method NESTED_TARGET =
                target("testNestedDeopt", int.class);
        private static final Method STAGED_TARGET =
                target("testStagedMaterialization", int.class, int.class);

        public static class Outer {
            Inner first;
            Inner second;
            int marker;
        }

        public static class Inner {
            int value;
        }

        private static Outer savedOuter;
        private static Inner savedInner;
        private static int nestedDeopts;
        private static int stagedDeopts;

        public static void main(String[] args) throws Exception {
            new Outer();
            new Inner();
            PEATestUtils.compileConfiguredTargetsAtLevel4();

            long digest = 0x243F6A8885A308D3L;
            int nested = testNestedDeopt(17);
            Asserts.assertEquals(nested, 1056,
                    "deoptimized nested graph preserves mutation and identity");
            Asserts.assertEquals(nestedDeopts, 1,
                    "one nested active-frame deoptimization");
            digest = mix(digest, nested);

            int noEscape = testStagedMaterialization(23, 0);
            Asserts.assertEquals(noEscape, 2444,
                    "non-escaping staged path");
            digest = mix(digest, noEscape);

            int innerOnly = testStagedMaterialization(29, 1);
            Asserts.assertEquals(innerOnly, 3668,
                    "inner materializes while outer remains virtual");
            Asserts.assertNotNull(savedInner,
                    "mode one saves the materialized inner");
            Asserts.assertNull(savedOuter,
                    "mode one does not materialize the outer");
            digest = mix(digest, innerOnly);

            int both = testStagedMaterialization(31, 2);
            Asserts.assertEquals(both, 5504,
                    "inner then outer staged materialization");
            Asserts.assertEquals(stagedDeopts, 1,
                    "one staged active-frame deoptimization");
            Asserts.assertNotEquals(savedOuter, savedInner,
                    "saved outer and inner have distinct identities");
            Asserts.assertEquals(savedOuter.getClass(), Outer.class,
                    "materialized outer exact class");
            Asserts.assertEquals(savedInner.getClass(), Inner.class,
                    "materialized inner exact class");
            Asserts.assertSame(savedOuter.first, savedInner,
                    "saved outer first field identity");
            Asserts.assertSame(savedOuter.second, savedInner,
                    "saved outer second field identity");
            digest = mix(digest, both);
            digest = mix(digest, nestedDeopts);
            digest = mix(digest, stagedDeopts);

            System.out.println("PEA-RESULT:"
                    + Long.toUnsignedString(digest, 16));
        }

        public static int testNestedDeopt(int seed) {
            Outer outer = new Outer();
            Inner inner = new Inner();
            inner.value = seed + 3;
            outer.first = inner;
            outer.second = inner;
            outer.marker = seed + 5;

            requestNestedDeopt();
            if (outer.first != inner || outer.second != inner
                    || outer.first != outer.second
                    || outer.getClass() != Outer.class
                    || inner.getClass() != Inner.class
                    || outer.marker != seed + 5
                    || inner.value != seed + 3) {
                return -1;
            }
            outer.marker += 11;
            outer.first.value += 13;
            if (outer.second.value != seed + 16
                    || outer.first != outer.second) {
                return -2;
            }
            return outer.marker * 31 + outer.second.value;
        }

        public static int testStagedMaterialization(int seed, int mode) {
            Outer outer = new Outer();
            Inner inner = new Inner();
            inner.value = seed + 7;
            outer.first = inner;
            outer.second = inner;
            outer.marker = seed + 11;

            if (mode == 0) {
                return outer.marker * 71 + outer.first.value;
            }

            int first = consumeInner(inner);
            if (mode == 1) {
                return outer.marker * 89 + first;
            }

            requestStagedDeopt();
            if (outer.first != inner || outer.second != inner
                    || outer.first != outer.second
                    || outer.getClass() != Outer.class
                    || inner.getClass() != Inner.class
                    || inner.value != seed + 7) {
                return -3;
            }
            outer.marker += 13;
            outer.second.value += 17;
            int second = consumeOuter(outer);
            if (savedOuter != outer || savedInner != inner
                    || savedOuter.first != savedInner
                    || savedOuter.second != savedInner) {
                return -4;
            }
            return first + second;
        }

        private static int consumeInner(Inner inner) {
            savedInner = inner;
            return inner.value * 3;
        }

        private static int consumeOuter(Outer outer) {
            savedOuter = outer;
            return outer.marker * 97 + outer.first.value;
        }

        private static void requestNestedDeopt() {
            nestedDeopts++;
            PEATestUtils.ActiveFrameDeoptEvidence evidence =
                    PEATestUtils.deoptimizeActiveFrame(NESTED_TARGET, 2);
            assertEvidence(evidence, "nested");
        }

        private static void requestStagedDeopt() {
            stagedDeopts++;
            PEATestUtils.ActiveFrameDeoptEvidence evidence =
                    PEATestUtils.deoptimizeActiveFrame(STAGED_TARGET, 2);
            assertEvidence(evidence, "staged");
        }

        private static void assertEvidence(
                PEATestUtils.ActiveFrameDeoptEvidence evidence,
                String context) {
            if (!evidence.frameDeoptimized()
                    || evidence.compilationLevel() != 4
                    || evidence.markedNMethods() != 1) {
                throw new AssertionError(
                        context + " exact active-frame deopt evidence");
            }
        }

        private static Method target(String name, Class<?>... parameters) {
            try {
                return TestWrapper.class.getMethod(name, parameters);
            } catch (ReflectiveOperationException failure) {
                throw new ExceptionInInitializerError(failure);
            }
        }

        private static long mix(long accumulator, long value) {
            return Long.rotateLeft(accumulator ^ value, 17)
                    * 0x9E3779B97F4A7C15L;
        }
    }
}
