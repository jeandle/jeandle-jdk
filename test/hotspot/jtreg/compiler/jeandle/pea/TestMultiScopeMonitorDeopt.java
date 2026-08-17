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
 * @summary PEA reconstructs every nested, reentrant, and interleaved virtual
 *          monitor across root and forced-inline scopes at active-frame deopt
 * @library /test/lib /
 * @modules java.base/jdk.internal.misc
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      -XX:-UseCompressedOops -XX:-UseCompressedClassPointers
 *      compiler.jeandle.pea.TestMultiScopeMonitorDeopt
 */

package compiler.jeandle.pea;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jdk.internal.misc.Unsafe;
import jdk.test.lib.Asserts;

public class TestMultiScopeMonitorDeopt {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestMultiScopeMonitorDeopt$TestWrapper";
    private static final Unsafe UNSAFE = Unsafe.getUnsafe();

    public static void main(String[] args) throws Exception {
        Method target = TestWrapper.class.getMethod(
                "testNestedReentrantInterleaved", int.class);
        Method inlineScope = TestWrapper.class.getDeclaredMethod(
                "inlineScope", TestWrapper.LockState.class,
                TestWrapper.LockState.class, int.class);
        Method requestDeopt = TestWrapper.class.getDeclaredMethod("requestDeopt");

        for (int lockingMode : List.of(1, 2)) {
            builder(false, lockingMode, target, inlineScope, requestDeopt)
                    .runPEAOnOffEquivalent();
        }

        try (PEATestUtils.RunResult run =
                builder(true, 2, target, inlineScope, requestDeopt).run()) {
            assertShape(run, target, requestDeopt);
        }
    }

    private static PEATestUtils.RunBuilder builder(
            boolean shape, int lockingMode, Method target,
            Method inlineScope, Method requestDeopt) {
        PEATestUtils.RunBuilder builder = shape
                ? PEATestUtils.shapeRun(WRAPPER, target)
                : PEATestUtils.behaviorRun(WRAPPER, target);
        return builder.inline(inlineScope)
                .dontinline(requestDeopt)
                .lockingMode(lockingMode);
    }

    private static void assertShape(
            PEATestUtils.RunResult run, Method target, Method requestDeopt)
            throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        report.assertFinalTransformIdle();
        PEATestUtils.PEARound first = report.round(0);
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();
        List<Integer> sourceBCIs = before.allocationBCIs();

        Asserts.assertEquals(sourceBCIs.size(), 3,
                target + ": two visible owners and one monitor-only owner");
        Asserts.assertEquals(new HashSet<>(sourceBCIs).size(), 3,
                target + ": every owner has a distinct source allocation");
        Asserts.assertEquals(first.neverEscapes(), 3,
                target + ": all owners remain virtual until deoptimization");
        Asserts.assertEquals(first.partiallyEscapes(), 0,
                target + ": no owner escapes in compiled Java code");
        Asserts.assertEquals(first.alwaysEscapes(), 0,
                target + ": no owner always escapes");
        Asserts.assertEquals(after.allocationBCIs(), List.of(),
                target + ": every virtual owner allocation is eliminated");
        Asserts.assertEquals(run.finalIR(target).loweredAllocCount(), 0,
                target + ": no lowered owner allocation remains");

        String callee = PEATestUtils.MethodId.of(requestDeopt).llvmFunctionName();
        PEATestUtils.DeoptBundle source = exactBundle(before, callee);
        PEATestUtils.DeoptBundle bundle = exactBundle(after, callee);
        Asserts.assertEquals(source.virtualObjects().size(), 0,
                target + ": frontend helper call has no PEA descriptors");
        Asserts.assertEquals(bundle.rootScope().bci(), source.rootScope().bci(),
                target + ": descriptor rewrite preserves root call BCI");
        Asserts.assertEquals(bundle.rootScope().duplicateBCI(),
                bundle.rootScope().bci(),
                target + ": root BCI remains duplicated exactly");
        Asserts.assertEquals(bundle.scopes().size(), 2,
                target + ": exact root plus forced-inline scope count");
        Asserts.assertEquals(bundle.inlineScopes().size(), 1,
                target + ": exactly one forced-inline Java scope");

        Map<Integer, Integer> ownerByMarker = descriptorIdsByMarker(bundle, target);
        int outerOwner = ownerByMarker.get(101);
        int innerOwner = ownerByMarker.get(202);
        int monitorOnlyOwner = monitorOnlyDescriptor(bundle, ownerByMarker, target);

        assertMonitor(bundle.rootScope(), 0, outerOwner, target + ": root outer");
        Asserts.assertEquals(bundle.rootScope().monitors().size(), 1,
                target + ": root scope carries one logical entry");

        PEATestUtils.DeoptScope inline = bundle.inlineScopes().get(0);
        Asserts.assertEquals(inline.monitors().size(), 3,
                target + ": inline scope preserves all three logical entries");
        assertMonitor(inline, 0, innerOwner, target + ": inline inner owner");
        assertMonitor(inline, 1, outerOwner,
                target + ": inline reentrant outer owner");
        assertMonitor(inline, 2, monitorOnlyOwner,
                target + ": inline innermost monitor-only owner");

    }

    private static PEATestUtils.DeoptBundle exactBundle(
            PEATestUtils.IRBody body, String callee) {
        Asserts.assertEquals(body.occurrenceCount("@\"" + callee + "\"("), 1,
                body.methodId() + ": exact no-VO deopt helper call");
        return body.deoptBundleAtCall(callee, 0);
    }

    private static Map<Integer, Integer> descriptorIdsByMarker(
            PEATestUtils.DeoptBundle bundle, Method target) throws Exception {
        int idOffset = offset(TestWrapper.LockState.class, "id");
        int valueOffset = offset(TestWrapper.LockState.class, "value");
        Map<Integer, Integer> byMarker = new HashMap<>();
        for (PEATestUtils.VirtualObjectDescriptor descriptor
                : bundle.virtualObjects().values()) {
            if (!descriptor.fields().keySet().equals(Set.of(idOffset, valueOffset))) {
                continue;
            }
            PEATestUtils.DeoptValue id = descriptor.fields().get(idOffset).value();
            Asserts.assertEquals(id.kind(), PEATestUtils.DeoptValueKind.SCALAR,
                    target + ": owner marker is scalar");
            int marker = Integer.parseInt(id.operand().substring("i32 ".length()));
            Asserts.assertNull(byMarker.put(marker, descriptor.id()),
                    target + ": owner marker is unique");
        }
        Asserts.assertEquals(byMarker.keySet(), Set.of(101, 202),
                target + ": exact visible-owner marker set");
        assertIntScalar(bundle.virtualObject(byMarker.get(101)), valueOffset, target);
        assertIntScalar(bundle.virtualObject(byMarker.get(202)), valueOffset, target);
        return byMarker;
    }

    private static int monitorOnlyDescriptor(
            PEATestUtils.DeoptBundle bundle, Map<Integer, Integer> visible,
            Method target) {
        Asserts.assertEquals(bundle.virtualObjects().size(), 3,
                target + ": exact descriptor count");
        Set<Integer> remaining = new HashSet<>(bundle.virtualObjects().keySet());
        remaining.removeAll(visible.values());
        Asserts.assertEquals(remaining.size(), 1,
                target + ": one monitor-only descriptor");
        int id = remaining.iterator().next();
        Asserts.assertEquals(bundle.virtualObject(id).fields(), Map.of(),
                target + ": marker-free monitor-only owner descriptor");
        return id;
    }

    private static void assertMonitor(
            PEATestUtils.DeoptScope scope, int depth, int ownerId,
            String message) {
        PEATestUtils.DeoptMonitor monitor = scope.monitors().get(depth);
        Asserts.assertEquals(monitor.depth(), depth, message + " depth");
        Asserts.assertTrue(monitor.eliminated(), message + " is eliminated");
        Asserts.assertEquals(monitor.owner().kind(),
                PEATestUtils.DeoptValueKind.VO_REF, message + " owner kind");
        Asserts.assertEquals(monitor.owner().virtualObjectId(), ownerId,
                message + " owner identity");
    }

    private static void assertIntScalar(
            PEATestUtils.VirtualObjectDescriptor descriptor,
            int offset, Method target) {
        PEATestUtils.VirtualObjectEntry entry = descriptor.fields().get(offset);
        Asserts.assertNotNull(entry);
        Asserts.assertEquals(entry.basicType(), PEATestUtils.DeoptBasicType.INT,
                target + ": dynamic owner value type");
        Asserts.assertEquals(entry.value().kind(),
                PEATestUtils.DeoptValueKind.SCALAR,
                target + ": dynamic owner value kind");
        Asserts.assertTrue(entry.value().operand().startsWith("i32 "),
                target + ": dynamic owner value operand");
        Asserts.assertFalse(entry.value().operand().contains("poison"),
                target + ": dynamic owner value is available");
        Asserts.assertFalse(entry.value().operand().contains("undef"),
                target + ": dynamic owner value is defined");
    }

    private static int offset(Class<?> holder, String name) throws Exception {
        Field field = holder.getDeclaredField(name);
        return Math.toIntExact(UNSAFE.objectFieldOffset(field));
    }

    public static class TestWrapper {
        private static final Method DEOPT_TARGET = target();
        private static int deoptRequests;

        public static class LockState {
            public int id;
            public int value;
        }

        public static class MetadataLock { }

        public static void main(String[] args) throws Exception {
            new LockState();
            new MetadataLock();
            PEATestUtils.compileConfiguredTargetsAtLevel4();
            int result = testNestedReentrantInterleaved(7);
            if (result != 38_039) {
                throw new AssertionError("multi-scope monitor result " + result);
            }
            if (deoptRequests != 1) {
                throw new AssertionError("deopt request count " + deoptRequests);
            }
            System.out.println("PEA-RESULT:" + result);
        }

        public static int testNestedReentrantInterleaved(int seed) {
            LockState outer = new LockState();
            outer.id = 101;
            outer.value = seed;
            LockState inner = new LockState();
            inner.id = 202;
            inner.value = seed * 2;

            int innerResult;
            synchronized (outer) {
                outer.value++;
                innerResult = inlineScope(outer, inner, seed);
                if (!Thread.holdsLock(outer)) {
                    return Integer.MIN_VALUE + 1;
                }
                outer.value += 7;
            }
            if (Thread.holdsLock(outer) || Thread.holdsLock(inner)) {
                return Integer.MIN_VALUE + 2;
            }
            synchronized (outer) {
                synchronized (inner) {
                    if (!Thread.holdsLock(outer) || !Thread.holdsLock(inner)) {
                        return Integer.MIN_VALUE + 3;
                    }
                    outer.value += 11;
                    inner.value += 13;
                }
            }
            if (innerResult != 53 || Thread.holdsLock(outer)
                    || Thread.holdsLock(inner)) {
                return Integer.MIN_VALUE + 4;
            }
            return outer.value * 1000 + inner.value;
        }

        private static int inlineScope(
                LockState outer, LockState inner, int seed) {
            synchronized (inner) {
                inner.value += 2;
                synchronized (outer) {
                    outer.value += 4;
                    synchronized (new MetadataLock()) {
                        int token = requestDeopt();
                        if (!Thread.holdsLock(outer)
                                || !Thread.holdsLock(inner)) {
                            return Integer.MIN_VALUE + 5;
                        }
                        outer.value += token;
                        inner.value += 6;
                    }
                    if (!Thread.holdsLock(outer)
                            || !Thread.holdsLock(inner)) {
                        return Integer.MIN_VALUE + 6;
                    }
                    outer.value += 3;
                }
                if (!Thread.holdsLock(outer)
                        || !Thread.holdsLock(inner)) {
                    return Integer.MIN_VALUE + 7;
                }
                inner.value += 4;
            }
            return outer.value + inner.value + seed;
        }

        private static int requestDeopt() {
            deoptRequests++;
            if (deoptRequests != 1) {
                throw new AssertionError("deopt helper reexecuted");
            }
            PEATestUtils.ActiveFrameDeoptEvidence evidence =
                    PEATestUtils.deoptimizeActiveFrame(DEOPT_TARGET, 2);
            if (!evidence.frameDeoptimized()
                    || evidence.compilationLevel() != 4
                    || evidence.markedNMethods() != 1) {
                throw new AssertionError("exact active-frame deopt evidence");
            }
            return 5;
        }

        private static Method target() {
            try {
                return TestWrapper.class.getMethod(
                        "testNestedReentrantInterleaved", int.class);
            } catch (ReflectiveOperationException failure) {
                throw new ExceptionInInitializerError(failure);
            }
        }
    }
}
