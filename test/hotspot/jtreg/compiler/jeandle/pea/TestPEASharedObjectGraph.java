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
 * You should have received a copy of the GNU General Public License
 * version 2 along with this work; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 */

/*
 * @test
 * @summary PEA preserves shared instance/array graphs, prunes dead references,
 *          and materializes each live identity exactly once
 * @library /test/lib /
 * @modules java.base/jdk.internal.misc
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      -XX:-UseCompressedOops -XX:-UseCompressedClassPointers
 *      compiler.jeandle.pea.TestPEASharedObjectGraph
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

public class TestPEASharedObjectGraph {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestPEASharedObjectGraph$TestWrapper";
    private static final Unsafe UNSAFE = Unsafe.getUnsafe();
    private static final Pattern JAVA_KLASS =
            Pattern.compile("\"java-klass\"=\"([0-9]+)\"");
    private static final String LLVM_LOCAL =
            "(?:%[-A-Za-z$._0-9]+|%\"(?:[^\"\\\\]|\\\\.)*\")";

    public static void main(String[] args) throws Exception {
        Method deopt = TestWrapper.class.getMethod(
                "testSharedDeopt", int.class);
        Method minimal = TestWrapper.class.getMethod(
                "testMinimalSharedChild", int.class, boolean.class);
        Method partial = TestWrapper.class.getMethod(
                "testPartialGraphEscape", int.class, int.class);
        Method dead = TestWrapper.class.getMethod(
                "testDeadOverwrittenChild", int.class, boolean.class);
        Method requestDeopt =
                TestWrapper.class.getDeclaredMethod("requestSharedDeopt");
        Method consumeParent = TestWrapper.class.getDeclaredMethod(
                "consumeParent", TestWrapper.Parent.class);
        Method consumeGraph = TestWrapper.class.getDeclaredMethod(
                "consumeGraph", TestWrapper.Root.class,
                TestWrapper.Parent.class, Object[].class);
        Method[] targets = {deopt, minimal, partial, dead};

        runBuilder(false, targets, requestDeopt, consumeParent, consumeGraph)
                .runPEAOnOffEquivalent();

        try (PEATestUtils.RunResult run =
                runBuilder(true, targets, requestDeopt,
                        consumeParent, consumeGraph).run()) {
            assertDeoptShape(run, deopt, requestDeopt);
            assertMinimalShape(run, minimal, consumeParent);
            assertPartialShape(run, partial, consumeParent, consumeGraph);
            assertDeadShape(run, dead, consumeParent);
        }
    }

    private static void assertMinimalShape(
            PEATestUtils.RunResult run, Method target, Method consumeParent)
            throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.PEARound first = report.round(0);
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();
        List<PEATestUtils.AllocationKey> keys =
                assertExactSourceAllocations(before, target, 3);

        Asserts.assertEquals(first.neverEscapes(), 1,
                target + ": second parent remains virtual");
        Asserts.assertEquals(first.partiallyEscapes(), 2,
                target + ": first parent and its child escape only on one path");
        Asserts.assertEquals(first.alwaysEscapes(), 0,
                target + ": no minimal-graph allocation always escapes");
        Asserts.assertEquals(first.effectCount(
                "EliminateAllocation", "[VO=2]"), 1L,
                target + ": second parent allocation is eliminated");
        for (PEATestUtils.PEARound round : report.rounds()) {
            Asserts.assertEquals(round.effectCount("Materialize"), 4L,
                    target + ": two objects materialize on two live paths in round "
                            + round.iteration());
            Asserts.assertEquals(round.effectCount(
                    "Materialize", "[VO=0]"), 2L,
                    target + ": shared child materializes once per live path in round "
                            + round.iteration());
            Asserts.assertEquals(round.effectCount(
                    "Materialize", "[VO=1]"), 2L,
                    target + ": escaping parent materializes once per live path in round "
                            + round.iteration());
            Asserts.assertEquals(round.effectCount(
                    "Materialize", "[VO=2]"), 0L,
                    target + ": virtual second parent never materializes in round "
                            + round.iteration());
        }
        after.assertRetainsExactlyOriginalAllocations(
                before, keys.get(0), keys.get(1));
        Asserts.assertEquals(run.finalIR(target).loweredAllocCount(), 2,
                target + ": only child and first-parent OrigAlloc sites remain");

        String callee =
                PEATestUtils.MethodId.of(consumeParent).llvmFunctionName();
        PEATestUtils.IRBlock callBlock = after.blockContaining(callee, 0);
        List<PEATestUtils.AllocationSite> allocations = before.allocations();
        String sourceChild = allocations.get(0).result();
        String sourceFirstParent = allocations.get(1).result();
        String child = allocationResult(after, allocations.get(0).key());
        String firstParent =
                allocationResult(after, allocations.get(1).key());
        int childValueOffset = offset(TestWrapper.Child.class, "value");
        int parentValueOffset = offset(TestWrapper.Parent.class, "value");
        int parentChildOffset = offset(TestWrapper.Parent.class, "child");
        String beforeSeed = intArgument(before, 0);
        String afterSeed = intArgument(after, 0);
        assertSourceScalarStore(
                before, sourceChild, childValueOffset, beforeSeed, 1);
        assertSourceScalarStore(
                before, sourceFirstParent, parentValueOffset, beforeSeed, 2);
        callBlock.assertAbsent("jeandle.new_instance");
        assertScalarReplay(after, callBlock, child, childValueOffset,
                afterSeed, 1, callee);
        assertScalarReplay(after, callBlock, firstParent, parentValueOffset,
                afterSeed, 2, callee);
        assertReferenceReplay(after, callBlock, firstParent, parentChildOffset,
                "ptr addrspace(1)", child, callee);
        callBlock.assertOccurrenceCount("getelementptr", 3);
        callBlock.assertOccurrenceCount("store atomic", 3);
        callBlock.assertOccurrenceCount("store atomic i32", 2);
        callBlock.assertOccurrenceCount("store atomic ptr addrspace(1)", 1);
        callBlock.assertOccurrenceCount(
                "store atomic ptr addrspace(1) " + child + ",", 1);
    }

    private static PEATestUtils.RunBuilder runBuilder(
            boolean shape, Method[] targets, Method requestDeopt,
            Method consumeParent, Method consumeGraph) {
        PEATestUtils.RunBuilder builder = shape
                ? PEATestUtils.shapeRun(WRAPPER, targets)
                : PEATestUtils.behaviorRun(WRAPPER, targets);
        return builder.dontinline(requestDeopt)
                .dontinline(consumeParent)
                .dontinline(consumeGraph);
    }

    private static void assertDeoptShape(
            PEATestUtils.RunResult run, Method target, Method requestDeopt)
            throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.PEARound first = report.round(0);
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();

        assertExactSourceAllocations(before, target, 5);
        Asserts.assertEquals(first.neverEscapes(), 5,
                target + ": complete shared graph stays virtual");
        Asserts.assertEquals(first.partiallyEscapes(), 0,
                target + ": shared deopt graph never materializes in compiled code");
        Asserts.assertEquals(first.alwaysEscapes(), 0,
                target + ": shared deopt graph has no ordinary escape");
        for (int vo = 0; vo < 5; vo++) {
            Asserts.assertEquals(first.effectCount(
                    "EliminateAllocation", "[VO=" + vo + "]"), 1L,
                    target + ": allocation for VO " + vo + " eliminated once");
        }
        after.assertRetainsExactlyOriginalAllocations(before);
        Asserts.assertEquals(run.finalIR(target).loweredAllocCount(), 0,
                target + ": no lowered graph allocation remains");

        String callee = PEATestUtils.MethodId.of(requestDeopt).llvmFunctionName();
        assertUniqueCall(before, after, callee, target);
        PEATestUtils.DeoptBundle source = before.deoptBundleAtCall(callee, 0);
        PEATestUtils.DeoptBundle bundle = after.deoptBundleAtCall(callee, 0);
        Asserts.assertEquals(source.virtualObjects().size(), 0,
                target + ": frontend deopt state has no descriptors");
        Asserts.assertEquals(bundle.rootScope().bci(), source.rootScope().bci(),
                target + ": graph descriptor rewrite preserves BCI");
        bundle.assertVirtualObjectIds(0, 1, 2, 3, 4);
        assertSharedDescriptorGraph(
                bundle, before.allocations(), after, target);
    }

    private static void assertPartialShape(
            PEATestUtils.RunResult run, Method target,
            Method consumeParent, Method consumeGraph) throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.PEARound first = report.round(0);
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();

        List<PEATestUtils.AllocationKey> keys =
                assertExactSourceAllocations(before, target, 5);
        Asserts.assertEquals(first.neverEscapes(), 0,
                target + ": every graph object reaches an escape in mode two");
        Asserts.assertEquals(first.partiallyEscapes(), 5,
                target + ": mode zero keeps the whole graph virtual");
        Asserts.assertEquals(first.alwaysEscapes(), 0,
                target + ": no object escapes on all modes");
        for (PEATestUtils.PEARound round : report.rounds()) {
            Asserts.assertEquals(round.effectCount("Materialize"), 5L,
                    target + ": one materialization per graph object in round "
                            + round.iteration());
            for (int vo = 0; vo < 5; vo++) {
                Asserts.assertEquals(round.effectCount(
                        "Materialize", "[VO=" + vo + "]"), 1L,
                        target + ": VO " + vo + " materializes once in round "
                                + round.iteration());
            }
        }
        after.assertRetainsExactlyOriginalAllocations(
                before, keys.toArray(PEATestUtils.AllocationKey[]::new));
        Asserts.assertEquals(run.finalIR(target).loweredAllocCount(), 5,
                target + ": materialization reuses all five OrigAlloc sites");

        String firstCallee =
                PEATestUtils.MethodId.of(consumeParent).llvmFunctionName();
        String secondCallee =
                PEATestUtils.MethodId.of(consumeGraph).llvmFunctionName();
        PEATestUtils.IRBlock firstBlock =
                after.blockContaining(firstCallee, 0);
        PEATestUtils.IRBlock secondBlock =
                after.blockContaining(secondCallee, 0);
        firstBlock.assertAbsent("jeandle.new_instance");
        firstBlock.assertAbsent("jeandle.new_array");
        secondBlock.assertAbsent("jeandle.new_instance");
        secondBlock.assertAbsent("jeandle.new_array");
        List<PEATestUtils.AllocationSite> allocations = before.allocations();
        Asserts.assertEquals(after.allocations().size(), 5,
                target + ": final IR retains exactly the five graph allocations");
        String child = allocationResult(after, allocations.get(0).key());
        String firstParent =
                allocationResult(after, allocations.get(1).key());
        String secondParent =
                allocationResult(after, allocations.get(2).key());
        String root = allocationResult(after, allocations.get(3).key());
        String array = allocationResult(after, allocations.get(4).key());
        String afterSeed = intArgument(after, 0);
        int childValueOffset = offset(TestWrapper.Child.class, "value");
        int parentValueOffset = offset(TestWrapper.Parent.class, "value");
        int parentChildOffset = offset(TestWrapper.Parent.class, "child");
        assertScalarReplay(after, firstBlock, child, childValueOffset,
                afterSeed, 1, firstCallee);
        assertScalarReplay(after, firstBlock, firstParent, parentValueOffset,
                afterSeed, 2, firstCallee);
        assertReferenceReplay(
                after, firstBlock, firstParent, parentChildOffset,
                "ptr addrspace(1)", child, firstCallee);
        firstBlock.assertOccurrenceCount("getelementptr", 3);
        firstBlock.assertOccurrenceCount("store atomic", 3);
        firstBlock.assertOccurrenceCount("store atomic i32", 2);
        firstBlock.assertOccurrenceCount("store atomic ptr addrspace(1)", 1);
        firstBlock.assertOccurrenceCount(
                "store atomic ptr addrspace(1) " + child + ",", 1);
        firstBlock.assertBefore("store atomic", 2, firstCallee, 0);

        assertScalarReplay(after, secondBlock, secondParent,
                parentValueOffset, afterSeed, 3, secondCallee);
        assertReferenceReplay(
                after, secondBlock, secondParent, parentChildOffset,
                "ptr addrspace(1)", child, secondCallee);
        assertScalarReplay(after, secondBlock, root,
                offset(TestWrapper.Root.class, "value"),
                afterSeed, 4, secondCallee);
        assertReferenceReplay(after, secondBlock, root,
                offset(TestWrapper.Root.class, "left"),
                "ptr addrspace(1)", firstParent, secondCallee);
        assertReferenceReplay(after, secondBlock, root,
                offset(TestWrapper.Root.class, "right"),
                "ptr addrspace(1)", secondParent, secondCallee);
        assertReferenceReplay(after, secondBlock, root,
                offset(TestWrapper.Root.class, "child"),
                "ptr addrspace(1)", child, secondCallee);
        int base = Unsafe.ARRAY_OBJECT_BASE_OFFSET;
        int scale = Unsafe.ARRAY_OBJECT_INDEX_SCALE;
        assertReferenceReplay(after, secondBlock, array, base,
                "ptr addrspace(1)", child, secondCallee);
        assertReferenceReplay(after, secondBlock, array, base + scale,
                "ptr addrspace(1)", firstParent, secondCallee);
        assertReferenceReplay(after, secondBlock, array, base + 2 * scale,
                "ptr addrspace(1)", secondParent, secondCallee);
        assertReferenceReplay(after, secondBlock, array, base + 3 * scale,
                "ptr addrspace(1)", child, secondCallee);
        secondBlock.assertOccurrenceCount("getelementptr", 10);
        secondBlock.assertOccurrenceCount("store atomic", 10);
        secondBlock.assertOccurrenceCount("store atomic i32", 2);
        secondBlock.assertOccurrenceCount("store atomic ptr addrspace(1)", 8);
        secondBlock.assertOccurrenceCount(
                "store atomic ptr addrspace(1) " + child + ",", 4);
        secondBlock.assertOccurrenceCount(
                "store atomic ptr addrspace(1) " + firstParent + ",", 2);
        secondBlock.assertOccurrenceCount(
                "store atomic ptr addrspace(1) " + secondParent + ",", 2);
        secondBlock.assertBefore("store atomic", 9, secondCallee, 0);
    }

    private static void assertDeadShape(
            PEATestUtils.RunResult run, Method target, Method consumeParent)
            throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.PEARound first = report.round(0);
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();
        List<PEATestUtils.AllocationKey> keys =
                assertExactSourceAllocations(before, target, 2);
        PEATestUtils.AllocationKey child = keys.get(0);
        PEATestUtils.AllocationKey parent = keys.get(1);

        Asserts.assertEquals(first.neverEscapes(), 1,
                target + ": overwritten child remains NeverEscape");
        Asserts.assertEquals(first.partiallyEscapes(), 1,
                target + ": live parent escapes only on the true path");
        Asserts.assertEquals(first.alwaysEscapes(), 0,
                target + ": no source allocation always escapes");
        Asserts.assertEquals(first.effectCount(
                "EliminateAllocation", "[VO=0]"), 1L,
                target + ": dead child allocation is eliminated");
        Asserts.assertEquals(first.effectCount(
                "Materialize", "[VO=0]"), 0L,
                target + ": dead child never materializes in round zero");
        Asserts.assertEquals(first.effectCount(
                "Materialize", "[VO=1]"), 1L,
                target + ": live parent materializes once in round zero");
        for (PEATestUtils.PEARound round : report.rounds().subList(
                1, report.roundCount())) {
            Asserts.assertEquals(round.effectCount("Materialize"), 1L,
                    target + ": one live parent materialization in round "
                            + round.iteration());
            Asserts.assertEquals(round.effectCount(
                    "Materialize", "[VO=0]"), 1L,
                    target + ": parent is the sole remaining VO in round "
                            + round.iteration());
        }
        after.assertRetainsExactlyOriginalAllocations(before, parent);
        Asserts.assertFalse(after.allocations().stream()
                        .map(PEATestUtils.AllocationSite::key)
                        .anyMatch(child::equals),
                target + ": overwritten child OrigAlloc is absent");
        Asserts.assertEquals(run.finalIR(target).loweredAllocCount(), 1,
                target + ": only the parent OrigAlloc is lowered");

        String callee =
                PEATestUtils.MethodId.of(consumeParent).llvmFunctionName();
        PEATestUtils.IRBlock callBlock = after.blockContaining(callee, 0);
        List<PEATestUtils.AllocationSite> allocations = before.allocations();
        String sourceParent = allocations.get(1).result();
        String parentResult =
                allocationResult(after, allocations.get(1).key());
        int childOffset = offset(TestWrapper.Parent.class, "child");
        int valueOffset = offset(TestWrapper.Parent.class, "value");
        String beforeSeed = intArgument(before, 0);
        String afterSeed = intArgument(after, 0);
        assertSourceScalarStore(
                before, sourceParent, valueOffset, beforeSeed, 2);
        callBlock.assertAbsent("jeandle.new_instance");
        assertReferenceReplay(after, callBlock, parentResult, childOffset,
                "ptr addrspace(1)", "null", callee);
        assertScalarReplay(after, callBlock, parentResult, valueOffset,
                afterSeed, 2, callee);
        callBlock.assertOccurrenceCount("getelementptr", 2);
        callBlock.assertOccurrenceCount("store atomic", 2);
        callBlock.assertOccurrenceCount("store atomic ptr addrspace(1)", 1);
        callBlock.assertOccurrenceCount("store atomic i32", 1);
    }

    private static List<PEATestUtils.AllocationKey> assertExactSourceAllocations(
            PEATestUtils.IRBody before, Method target, int expected) {
        List<PEATestUtils.AllocationKey> keys =
                before.allocations().stream()
                        .map(PEATestUtils.AllocationSite::key).toList();
        Asserts.assertEquals(keys.size(), expected,
                target + ": exact source allocation count");
        Asserts.assertEquals(new HashSet<>(keys).size(), expected,
                target + ": exact typed allocation identities");
        return keys;
    }

    private static void assertSharedDescriptorGraph(
            PEATestUtils.DeoptBundle bundle,
            List<PEATestUtils.AllocationSite> allocations,
            PEATestUtils.IRBody body, Method target)
            throws Exception {
        int childValue = offset(TestWrapper.Child.class, "value");
        Set<Integer> parentOffsets = Set.of(
                offset(TestWrapper.Parent.class, "child"),
                offset(TestWrapper.Parent.class, "value"));
        Set<Integer> rootOffsets = Set.of(
                offset(TestWrapper.Root.class, "left"),
                offset(TestWrapper.Root.class, "right"),
                offset(TestWrapper.Root.class, "child"),
                offset(TestWrapper.Root.class, "value"));

        PEATestUtils.VirtualObjectDescriptor child = null;
        PEATestUtils.VirtualObjectDescriptor root = null;
        PEATestUtils.VirtualObjectDescriptor array = null;
        java.util.ArrayList<PEATestUtils.VirtualObjectDescriptor> parents =
                new java.util.ArrayList<>();
        for (PEATestUtils.VirtualObjectDescriptor descriptor
                : bundle.virtualObjects().values()) {
            if (descriptor.kind() == PEATestUtils.DescriptorKind.ARRAY) {
                Asserts.assertNull(array, "one exact Object[] descriptor");
                array = descriptor;
            } else if (descriptor.fields().keySet().equals(Set.of(childValue))) {
                Asserts.assertNull(child, "one exact child descriptor");
                child = descriptor;
            } else if (descriptor.fields().keySet().equals(parentOffsets)) {
                parents.add(descriptor);
            } else if (descriptor.fields().keySet().equals(rootOffsets)) {
                Asserts.assertNull(root, "one exact diamond root descriptor");
                root = descriptor;
            } else {
                throw new AssertionError(
                        "unexpected shared descriptor " + descriptor.id());
            }
        }
        Asserts.assertNotNull(child, "shared child descriptor");
        Asserts.assertNotNull(root, "diamond root descriptor");
        Asserts.assertNotNull(array, "Object[] descriptor");
        Asserts.assertEquals(parents.size(), 2,
                "two parent descriptors share one child");
        String seed = intArgument(body, 0);
        PEATestUtils.VirtualObjectDescriptor firstParent =
                uniqueDescriptorWithIntValue(
                        parents, body, seed,
                        offset(TestWrapper.Parent.class, "value"), 2,
                        "first parent");
        PEATestUtils.VirtualObjectDescriptor secondParent =
                uniqueDescriptorWithIntValue(
                        parents, body, seed,
                        offset(TestWrapper.Parent.class, "value"), 3,
                        "second parent");
        assertDescriptorKlass(child, allocations.get(0), target);
        assertDescriptorKlass(firstParent, allocations.get(1), target);
        assertDescriptorKlass(secondParent, allocations.get(2), target);
        assertDescriptorKlass(root, allocations.get(3), target);
        assertDescriptorKlass(array, allocations.get(4), target);

        int parentChild = offset(TestWrapper.Parent.class, "child");
        for (PEATestUtils.VirtualObjectDescriptor parent : parents) {
            Asserts.assertEquals(voRef(parent, parentChild), child.id(),
                    "parent child VORef");
        }
        int rootLeft = offset(TestWrapper.Root.class, "left");
        int rootRight = offset(TestWrapper.Root.class, "right");
        int rootChild = offset(TestWrapper.Root.class, "child");
        Asserts.assertEquals(voRef(root, rootLeft), firstParent.id(),
                "root.left references the first parent");
        Asserts.assertEquals(voRef(root, rootRight), secondParent.id(),
                "root.right references the second parent");
        Asserts.assertEquals(voRef(root, rootChild), child.id(),
                "root child VORef");
        assertIntEntry(
                root, offset(TestWrapper.Root.class, "value"),
                body, seed, 4);
        assertIntEntry(child, childValue, body, seed, 1);

        int base = Unsafe.ARRAY_OBJECT_BASE_OFFSET;
        int scale = Unsafe.ARRAY_OBJECT_INDEX_SCALE;
        Asserts.assertEquals(array.elements().keySet(),
                Set.of(base, base + scale, base + 2 * scale, base + 3 * scale),
                "exact Object[] descriptor offsets");
        Asserts.assertEquals(voRef(array, base), child.id(),
                "array first child VORef");
        Asserts.assertEquals(voRef(array, base + scale), firstParent.id(),
                "array[1] references the first parent");
        Asserts.assertEquals(
                voRef(array, base + 2 * scale), secondParent.id(),
                "array[2] references the second parent");
        Asserts.assertEquals(voRef(array, base + 3 * scale), child.id(),
                "array second child VORef");
    }

    private static int voRef(
            PEATestUtils.VirtualObjectDescriptor descriptor, int offset) {
        PEATestUtils.VirtualObjectEntry entry =
                descriptor.entries().get(offset);
        Asserts.assertNotNull(entry,
                "descriptor " + descriptor.id() + " entry at " + offset);
        Asserts.assertEquals(entry.basicType(),
                PEATestUtils.DeoptBasicType.OBJECT,
                "descriptor reference basic type");
        Asserts.assertEquals(entry.value().kind(),
                PEATestUtils.DeoptValueKind.VO_REF,
                "descriptor edge is a VORef");
        return entry.value().virtualObjectId();
    }

    private static void assertIntEntry(
            PEATestUtils.VirtualObjectDescriptor descriptor, int offset,
            PEATestUtils.IRBody body, String seed, int delta) {
        PEATestUtils.VirtualObjectEntry entry =
                descriptor.entries().get(offset);
        Asserts.assertNotNull(entry,
                "descriptor " + descriptor.id() + " int entry at " + offset);
        Asserts.assertEquals(entry.basicType(),
                PEATestUtils.DeoptBasicType.INT,
                "descriptor integer basic type");
        Asserts.assertEquals(entry.value().kind(),
                PEATestUtils.DeoptValueKind.SCALAR,
                "descriptor integer scalar kind");
        assertAffineI32(body, entry.value().operand(), seed, delta,
                "descriptor " + descriptor.id() + " int value");
    }

    private static PEATestUtils.VirtualObjectDescriptor uniqueDescriptorWithIntValue(
            List<PEATestUtils.VirtualObjectDescriptor> descriptors,
            PEATestUtils.IRBody body, String seed, int offset, int delta,
            String detail) {
        List<PEATestUtils.VirtualObjectDescriptor> matches =
                descriptors.stream().filter(descriptor -> {
                    PEATestUtils.VirtualObjectEntry entry =
                            descriptor.entries().get(offset);
                    return entry != null
                            && entry.basicType()
                                    == PEATestUtils.DeoptBasicType.INT
                            && entry.value().kind()
                                    == PEATestUtils.DeoptValueKind.SCALAR
                            && isAffineI32(
                                    body, entry.value().operand(), seed, delta);
                }).toList();
        Asserts.assertEquals(matches.size(), 1,
                body.methodId() + ": one exact " + detail + " descriptor");
        return matches.get(0);
    }

    private static void assertDescriptorKlass(
            PEATestUtils.VirtualObjectDescriptor descriptor,
            PEATestUtils.AllocationSite allocation, Method target) {
        Matcher matcher = JAVA_KLASS.matcher(allocation.instruction());
        Asserts.assertTrue(matcher.find(),
                target + ": source allocation has an exact java-klass");
        Asserts.assertEquals(descriptor.klassOperand(),
                "i64 " + matcher.group(1),
                target + ": descriptor klass matches source allocation");
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
            PEATestUtils.IRBody body, String typedOrRawOperand,
            String seed, int delta) {
        String operand = typedOrRawOperand.startsWith("i32 ")
                ? typedOrRawOperand.substring(4) : typedOrRawOperand;
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
                target + ": exact callee uniquely bound to source BCI " + bci);
        Asserts.assertEquals(after.callOccurrencesAtBCI(callee, bci), List.of(0),
                target + ": final callee preserves source BCI " + bci);
    }

    private static int offset(Class<?> holder, String name) throws Exception {
        Field field = holder.getDeclaredField(name);
        return Math.toIntExact(UNSAFE.objectFieldOffset(field));
    }

    public static class TestWrapper {
        private static final Method DEOPT_TARGET =
                target("testSharedDeopt", int.class);

        public static class Child {
            int value;
        }

        public static class Parent {
            Child child;
            int value;
        }

        public static class Root {
            Parent left;
            Parent right;
            Child child;
            int value;
        }

        private static Child savedChild;
        private static Parent savedFirst;
        private static Parent savedSecond;
        private static Root savedRoot;
        private static Object[] savedArray;
        private static int sharedDeopts;

        public static void main(String[] args) throws Exception {
            new Child();
            new Parent();
            new Root();
            PEATestUtils.compileConfiguredTargetsAtLevel4();

            long digest = 0x13198A2E03707344L;
            int deopt = testSharedDeopt(17);
            Asserts.assertEquals(deopt, 3994,
                    "shared graph survives deoptimization and mutation");
            Asserts.assertEquals(sharedDeopts, 1,
                    "one exact shared-graph deoptimization");
            digest = mix(digest, deopt);

            int minimalVirtual = testMinimalSharedChild(23, false);
            Asserts.assertEquals(minimalVirtual, 3225,
                    "minimal two-parent shared-child virtual path");
            digest = mix(digest, minimalVirtual);

            int minimalEscape = testMinimalSharedChild(29, true);
            Asserts.assertEquals(minimalEscape, 4730,
                    "minimal shared child survives first-parent escape");
            Asserts.assertSame(savedFirst.child, savedChild,
                    "minimal escaped parent retains child identity");
            digest = mix(digest, minimalEscape);

            int virtual = testPartialGraphEscape(23, 0);
            Asserts.assertEquals(virtual, 3668,
                    "fully virtual shared graph result");
            digest = mix(digest, virtual);

            int first = testPartialGraphEscape(29, 1);
            Asserts.assertEquals(first, 226,
                    "first parent and child escape together");
            Asserts.assertSame(savedFirst.child, savedChild,
                    "first escaped parent reaches the saved child");
            digest = mix(digest, first);

            int all = testPartialGraphEscape(31, 2);
            Asserts.assertEquals(all, 4120,
                    "remaining graph escapes after its shared child");
            assertSavedGraph("partial graph");
            digest = mix(digest, all);

            int deadVirtual = testDeadOverwrittenChild(37, false);
            Asserts.assertEquals(deadVirtual, 2379,
                    "dead child is irrelevant on the virtual path");
            digest = mix(digest, deadVirtual);

            int deadEscape = testDeadOverwrittenChild(41, true);
            Asserts.assertEquals(deadEscape, 238,
                    "escaped parent retains the overwritten null edge");
            Asserts.assertNull(savedFirst.child,
                    "dead child cannot be reached through escaped parent");
            digest = mix(digest, deadEscape);
            digest = mix(digest, sharedDeopts);

            System.out.println("PEA-RESULT:"
                    + Long.toUnsignedString(digest, 16));
        }

        public static int testMinimalSharedChild(
                int seed, boolean escape) {
            Child child = new Child();
            Parent first = new Parent();
            Parent second = new Parent();
            child.value = seed + 1;
            first.child = child;
            first.value = seed + 2;
            second.child = child;
            second.value = seed + 3;
            if (escape) {
                consumeParent(first);
            }
            if (first.child != child || second.child != child
                    || first.child != second.child) {
                return -5;
            }
            second.child.value += escape ? 7 : 0;
            return second.child.value * 101
                    + first.value * 31 + second.value;
        }

        public static int testSharedDeopt(int seed) {
            Child child = new Child();
            Parent left = new Parent();
            Parent right = new Parent();
            Root root = new Root();
            Object[] array = new Object[4];

            child.value = seed + 1;
            left.child = child;
            left.value = seed + 2;
            right.child = child;
            right.value = seed + 3;
            root.left = left;
            root.right = right;
            root.child = child;
            root.value = seed + 4;
            array[0] = child;
            array[1] = left;
            array[2] = right;
            array[3] = child;

            requestSharedDeopt();
            if (root.left != left || root.right != right
                    || root.child != child
                    || left.child != child || right.child != child
                    || array[0] != child || array[3] != child
                    || array[1] != left || array[2] != right
                    || child.getClass() != Child.class
                    || left.getClass() != Parent.class
                    || right.getClass() != Parent.class
                    || root.getClass() != Root.class
                    || array.getClass() != Object[].class) {
                return -1;
            }
            ((Child) array[0]).value += 11;
            left.value += 13;
            right.value += 17;
            root.value += 19;
            if (root.child.value != child.value
                    || left.child.value != right.child.value
                    || ((Child) array[3]).value != child.value) {
                return -2;
            }
            return child.value * 101 + left.value * 17
                    + right.value * 13 + root.value;
        }

        public static int testPartialGraphEscape(int seed, int mode) {
            Child child = new Child();
            Parent first = new Parent();
            Parent second = new Parent();
            Root root = new Root();
            Object[] array = new Object[4];

            child.value = seed + 1;
            first.child = child;
            first.value = seed + 2;
            second.child = child;
            second.value = seed + 3;
            root.left = first;
            root.right = second;
            root.child = child;
            root.value = seed + 4;
            array[0] = child;
            array[1] = first;
            array[2] = second;
            array[3] = child;

            if (mode == 0) {
                return child.value * 101 + first.value * 31
                        + second.value * 17 + root.value;
            }

            int early = consumeParent(first);
            first.child.value += 11;
            if (mode == 1) {
                return early + child.value;
            }

            int late = consumeGraph(root, second, array);
            if (savedFirst != first || savedSecond != second
                    || savedRoot != root || savedArray != array
                    || savedChild != child
                    || child.getClass() != Child.class
                    || first.getClass() != Parent.class
                    || second.getClass() != Parent.class
                    || root.getClass() != Root.class
                    || array.getClass() != Object[].class) {
                return -3;
            }
            return early + late + child.value;
        }

        public static int testDeadOverwrittenChild(
                int seed, boolean escape) {
            Child child = new Child();
            Parent parent = new Parent();
            child.value = seed + 101;
            parent.child = child;
            parent.child = null;
            parent.value = seed + 2;
            if (escape) {
                int result = consumeParent(parent);
                if (savedFirst != parent || savedFirst.child != null) {
                    return -4;
                }
                return result;
            }
            return parent.value * 61;
        }

        private static int consumeParent(Parent parent) {
            savedFirst = parent;
            savedChild = parent.child;
            return parent.value * 5
                    + (parent.child == null ? 23 : parent.child.value);
        }

        private static int consumeGraph(
                Root root, Parent second, Object[] array) {
            savedRoot = root;
            savedSecond = second;
            savedArray = array;
            savedChild = root.child;
            if (root.left != savedFirst || root.right != second
                    || root.child != savedChild
                    || root.left.child != savedChild
                    || root.right.child != savedChild
                    || array[0] != savedChild
                    || array[1] != savedFirst
                    || array[2] != savedSecond
                    || array[3] != savedChild) {
                return Integer.MIN_VALUE;
            }
            return root.value * 97 + second.value * 13
                    + savedChild.value;
        }

        private static void requestSharedDeopt() {
            sharedDeopts++;
            PEATestUtils.ActiveFrameDeoptEvidence evidence =
                    PEATestUtils.deoptimizeActiveFrame(DEOPT_TARGET, 2);
            if (!evidence.frameDeoptimized()
                    || evidence.compilationLevel() != 4
                    || evidence.markedNMethods() != 1) {
                throw new AssertionError(
                        "exact shared-graph active-frame deopt evidence");
            }
        }

        private static void assertSavedGraph(String context) {
            Asserts.assertNotNull(savedRoot, context + ": saved root");
            Asserts.assertNotNull(savedArray, context + ": saved array");
            Asserts.assertSame(savedRoot.left, savedFirst,
                    context + ": root left identity");
            Asserts.assertSame(savedRoot.right, savedSecond,
                    context + ": root right identity");
            Asserts.assertSame(savedRoot.child, savedChild,
                    context + ": root child identity");
            Asserts.assertSame(savedFirst.child, savedChild,
                    context + ": first parent child identity");
            Asserts.assertSame(savedSecond.child, savedChild,
                    context + ": second parent child identity");
            Asserts.assertSame(savedArray[0], savedChild,
                    context + ": array first shared child");
            Asserts.assertSame(savedArray[3], savedChild,
                    context + ": array second shared child");
            Asserts.assertSame(savedArray[1], savedFirst,
                    context + ": array first parent");
            Asserts.assertSame(savedArray[2], savedSecond,
                    context + ": array second parent");
            Asserts.assertEquals(savedChild.getClass(), Child.class,
                    context + ": child exact class");
            Asserts.assertEquals(savedFirst.getClass(), Parent.class,
                    context + ": first parent exact class");
            Asserts.assertEquals(savedSecond.getClass(), Parent.class,
                    context + ": second parent exact class");
            Asserts.assertEquals(savedRoot.getClass(), Root.class,
                    context + ": root exact class");
            Asserts.assertEquals(savedArray.getClass(), Object[].class,
                    context + ": array exact class");
        }

        private static Method target(String name, Class<?>... parameters) {
            try {
                return TestWrapper.class.getMethod(name, parameters);
            } catch (ReflectiveOperationException failure) {
                throw new ExceptionInInitializerError(failure);
            }
        }

        private static long mix(long accumulator, long value) {
            return Long.rotateLeft(accumulator ^ value, 19)
                    * 0x9E3779B97F4A7C15L;
        }
    }
}
