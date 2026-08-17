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
 * version 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 */

/*
 * @test
 * @summary PEA multi-scope deopt: virtual objects referenced from the root
 *          scope's locals, from an inlined scope's operand stack, and as a
 *          cross-scope object graph are all described in the single root-scope
 *          object pool (VORef by vo-id) and reconstructed with correct
 *          identity across a three-scope (caller-callee-grandchild) deopt
 * @library /test/lib /
 * @modules java.base/jdk.internal.misc
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      -XX:-UseCompressedOops -XX:-UseCompressedClassPointers
 *      compiler.jeandle.pea.TestMultiScopeDeopt
 */

package compiler.jeandle.pea;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jdk.internal.misc.Unsafe;
import jdk.test.lib.Asserts;
import jdk.test.whitebox.WhiteBox;

public class TestMultiScopeDeopt {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestMultiScopeDeopt$TestWrapper";
    private static final Unsafe UNSAFE = Unsafe.getUnsafe();
    private static final String DEOPT_CALLEE = "llvm.experimental.deoptimize.i32";

    public static void main(String[] args) throws Exception {
        Method outerLocal = TestWrapper.class.getMethod(
                "outerLocalOnly", TestWrapper.Holder.class);
        Method operandStack = TestWrapper.class.getMethod(
                "operandStack", TestWrapper.Holder.class);
        Method crossGraph = TestWrapper.class.getMethod(
                "crossScopeGraph", TestWrapper.Holder.class);
        Method chainA = TestWrapper.class.getDeclaredMethod(
                "chainA", TestWrapper.Holder.class);
        Method grandA = TestWrapper.class.getDeclaredMethod(
                "grandA", TestWrapper.Holder.class);
        Method chainB = TestWrapper.class.getDeclaredMethod(
                "chainB", TestWrapper.Holder.class, TestWrapper.Inner.class);
        Method grandB = TestWrapper.class.getDeclaredMethod(
                "grandB", TestWrapper.Holder.class, TestWrapper.Inner.class);
        Method chainC = TestWrapper.class.getDeclaredMethod(
                "chainC", TestWrapper.Holder.class, TestWrapper.Outer.class);
        Method grandC = TestWrapper.class.getDeclaredMethod(
                "grandC", TestWrapper.Holder.class);
        Method requestDeopt = TestWrapper.class.getDeclaredMethod("requestDeopt");
        Method[] targets = {outerLocal, operandStack, crossGraph};

        // Behavior (deopt reconstruction) equivalence for PEA on/off.
        builder(false, targets, requestDeopt, chainA, grandA, chainB, grandB, chainC, grandC)
                .runPEAOnOffEquivalent();

        // Shape (multi-scope descriptor structure).
        try (PEATestUtils.RunResult run =
                builder(true, targets, requestDeopt, chainA, grandA, chainB, grandB,
                        chainC, grandC).run()) {
            assertOuterLocalShape(run, outerLocal, requestDeopt);
            assertOperandStackShape(run, operandStack);
            assertCrossGraphShape(run, crossGraph, requestDeopt);
        }
    }

    private static PEATestUtils.RunBuilder builder(
            boolean shape, Method[] targets, Method requestDeopt, Method chainA,
            Method grandA, Method chainB, Method grandB, Method chainC, Method grandC) {
        PEATestUtils.RunBuilder builder = shape
                ? PEATestUtils.shapeRun(WRAPPER, targets)
                : PEATestUtils.behaviorRun(WRAPPER, targets);
        return builder.inline(chainA).inline(grandA)
                .inline(chainB).inline(grandB)
                .inline(chainC).inline(grandC)
                .dontinline(requestDeopt)
                .extraFlags("-XX:+JeandleUseProfile",
                        "-XX:ProfileMaturityPercentage=0", "-XX:CompileThreshold=20000");
    }

    // Scenario A: a VO allocated by the root method, live only in the root
    // scope's local, reconstructed after a three-scope requestDeopt deopt.
    private static void assertOuterLocalShape(PEATestUtils.RunResult run, Method target,
                                              Method requestDeopt) throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        report.assertFinalTransformIdle();
        PEATestUtils.IRBody after = report.finalAfter();
        String deoptCallee = PEATestUtils.MethodId.of(requestDeopt).llvmFunctionName();
        PEATestUtils.DeoptBundle bundle = after.deoptBundleAtCall(deoptCallee, 0);
        Asserts.assertEquals(bundle.scopes().size(), 3,
                target + ": three scopes (caller-callee-grandchild)");
        // Root scope first, then two inline scopes (caller-callee-grandchild).
        Asserts.assertTrue(bundle.rootScope().root(), target + ": first scope is root");
        Asserts.assertEquals(bundle.inlineScopes().size(), 2,
                target + ": two inline scopes below the root");
        // Exactly one VO in the shared root object pool; the root scope's
        // locals reference it by VORef.
        bundle.assertVirtualObjectIds(0);
        PEATestUtils.VirtualObjectDescriptor outer = bundle.virtualObject(0);
        Asserts.assertEquals(outer.kind(), PEATestUtils.DescriptorKind.INSTANCE,
                target + ": outer descriptor kind");
        int oxOffset = offset(TestWrapper.Outer.class, "ox");
        Asserts.assertEquals(outer.fields().keySet(), Set.of(oxOffset),
                target + ": outer carries only its touched field");
        assertIntField(outer, oxOffset, 7, target);
        Asserts.assertTrue(hasVORef(bundle.rootScope().locals(), 0),
                target + ": root scope local holds outer by VORef");
        Asserts.assertEquals(after.allocationBCIs(), List.of(),
                target + ": outer is fully eliminated (NeverEscapes)");
    }

    // Scenario B: a VO on the operand stack of the innermost inlined scope at
    // the deopt (and also in that scope's locals).
    private static void assertOperandStackShape(PEATestUtils.RunResult run, Method target)
            throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        report.assertFinalTransformIdle();
        PEATestUtils.IRBody after = report.finalAfter();
        PEATestUtils.DeoptBundle bundle = threeScopeBundle(after, target);
        bundle.assertVirtualObjectIds(0);
        PEATestUtils.VirtualObjectDescriptor inner = bundle.virtualObject(0);
        int ixOffset = offset(TestWrapper.Inner.class, "ix");
        Asserts.assertEquals(inner.fields().keySet(), Set.of(ixOffset),
                target + ": inner carries only its touched field");
        assertIntField(inner, ixOffset, 3, target);
        // The innermost (grandchild) scope references the VO from BOTH a local
        // slot and an operand-stack slot (the VO is mid-expression at the trap).
        PEATestUtils.DeoptScope innermost =
                bundle.scopes().get(bundle.scopes().size() - 1);
        Asserts.assertFalse(innermost.root(), target + ": innermost scope is inlined");
        Asserts.assertTrue(hasVORef(innermost.stack(), 0),
                target + ": innermost scope operand stack holds the VO by VORef");
        Asserts.assertEquals(after.allocationBCIs(), List.of(),
                target + ": inner is fully eliminated (NeverEscapes)");
    }

    // Scenario C: a cross-scope object graph — the root-scope VO references an
    // inlined-scope VO by VORef — reconstructed after a requestDeopt deopt.
    private static void assertCrossGraphShape(PEATestUtils.RunResult run, Method target,
                                              Method requestDeopt) throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        report.assertFinalTransformIdle();
        PEATestUtils.IRBody after = report.finalAfter();
        String deoptCallee = PEATestUtils.MethodId.of(requestDeopt).llvmFunctionName();
        PEATestUtils.DeoptBundle bundle = after.deoptBundleAtCall(deoptCallee, 0);
        Asserts.assertEquals(bundle.scopes().size(), 3,
                target + ": three scopes (caller-callee-grandchild)");
        // Two distinct VOs in the shared root object pool: outer (root scope)
        // and inner (inlined scope). outer.ref is a VORef to inner, proving
        // cross-scope identity is preserved through the single pool. (outer and
        // inner both have their first field at the same offset, so distinguish
        // them by outer's unique ref field.)
        bundle.assertVirtualObjectIds(0, 1);
        int oxOffset = offset(TestWrapper.Outer.class, "ox");
        int refOffset = offset(TestWrapper.Outer.class, "ref");
        int ixOffset = offset(TestWrapper.Inner.class, "ix");
        PEATestUtils.VirtualObjectDescriptor outer = null;
        PEATestUtils.VirtualObjectDescriptor inner = null;
        for (PEATestUtils.VirtualObjectDescriptor descriptor
                : bundle.virtualObjects().values()) {
            Asserts.assertEquals(descriptor.kind(), PEATestUtils.DescriptorKind.INSTANCE,
                    target + ": graph node is an instance");
            if (descriptor.fields().containsKey(refOffset)) {
                outer = descriptor;
            } else if (descriptor.fields().containsKey(ixOffset)) {
                inner = descriptor;
            }
        }
        Asserts.assertNotNull(outer, target + ": outer descriptor (has ref field)");
        Asserts.assertNotNull(inner, target + ": inner descriptor (has ix field)");
        Asserts.assertNotEquals(outer.id(), inner.id(),
                target + ": outer and inner are distinct VOs");
        assertIntField(outer, oxOffset, 7, target);
        assertIntField(inner, ixOffset, 5, target);
        bundle.assertVORef(outer.id(), refOffset, inner.id());
        Asserts.assertTrue(hasVORef(bundle.rootScope().locals(), outer.id()),
                target + ": root scope local holds outer by VORef");
        Asserts.assertEquals(after.allocationBCIs(), List.of(),
                target + ": whole graph is fully eliminated (NeverEscapes)");
    }

    // Find the single three-scope deopt bundle in the body (the uncommon trap).
    private static PEATestUtils.DeoptBundle threeScopeBundle(
            PEATestUtils.IRBody after, Method target) {
        for (int occurrence = 0; ; occurrence++) {
            PEATestUtils.DeoptBundle bundle;
            try {
                bundle = after.deoptBundleAtCall(DEOPT_CALLEE, occurrence);
            } catch (IllegalStateException e) {
                break;
            }
            if (bundle.scopes().size() == 3) {
                return bundle;
            }
        }
        throw new AssertionError(target + ": no three-scope deopt bundle found");
    }

    private static boolean hasVORef(Map<Integer, PEATestUtils.DeoptValue> slots,
                                    int voId) {
        return slots.values().stream().anyMatch(
                v -> v.kind() == PEATestUtils.DeoptValueKind.VO_REF
                        && v.virtualObjectId() == voId);
    }

    private static void assertIntField(PEATestUtils.VirtualObjectDescriptor descriptor,
                                       int fieldOffset, int expected, Method target) {
        PEATestUtils.VirtualObjectEntry entry = descriptor.fields().get(fieldOffset);
        Asserts.assertNotNull(entry, target + ": missing field at offset " + fieldOffset);
        Asserts.assertEquals(entry.basicType(), PEATestUtils.DeoptBasicType.INT,
                target + ": field basic type");
        Asserts.assertEquals(entry.value().kind(), PEATestUtils.DeoptValueKind.SCALAR,
                target + ": field is scalar");
        Asserts.assertEquals(entry.value().operand(), "i32 " + expected,
                target + ": field value");
    }

    private static int offset(Class<?> holder, String name) throws Exception {
        Field field = holder.getDeclaredField(name);
        return Math.toIntExact(UNSAFE.objectFieldOffset(field));
    }

    public static class TestWrapper {
        private static final int WARMUP = 10_000;
        private static final long SEED = 0x61c8864680b583ebL;

        public static class Holder { public int h; }
        public static class Outer { public int ox; public Object ref; }
        public static class Inner { public int ix; }

        private static Method deoptTarget;

        public static void main(String[] args) throws Exception {
            new Holder();
            new Outer();
            new Inner();
            Method outerLocalTarget = TestWrapper.class.getMethod(
                    "outerLocalOnly", Holder.class);
            Method crossGraphTarget = TestWrapper.class.getMethod(
                    "crossScopeGraph", Holder.class);

            // Warm the natural-trap scenario (B) so its null path is cold. A and
            // C use requestDeopt (a forced deopt) and need no profile warmup.
            for (int i = 0; i < WARMUP; i++) {
                Holder h = new Holder();
                h.h = i + 1;
                operandStack(h);
            }
            PEATestUtils.compileConfiguredTargetsAtLevel4();

            long digest = SEED;
            // A: requestDeopt deopt reconstructs the root-local VO.
            deoptTarget = outerLocalTarget;
            Holder holderA = new Holder();
            holderA.h = 1;
            int a = outerLocalOnly(holderA);
            Asserts.assertEquals(a, 7, "outerLocalOnly reconstruction");
            digest = mix(digest, a);
            // B: cold uncommon-trap deopt describes the operand-stack VO.
            int b = operandStack(null);
            Asserts.assertEquals(b, 300, "operandStack reconstruction");
            digest = mix(digest, b);
            // C: requestDeopt deopt reconstructs the cross-scope graph.
            deoptTarget = crossGraphTarget;
            Holder holderC = new Holder();
            holderC.h = 1;
            int c = crossScopeGraph(holderC);
            Asserts.assertEquals(c, 12, "crossScopeGraph reconstruction");
            digest = mix(digest, c);
            System.out.println("PEA-RESULT:" + Long.toUnsignedString(digest, 16));
        }

        // Scenario A: outer is live only in the root scope's local at the deopt.
        public static int outerLocalOnly(Holder inp) {
            Outer outer = new Outer();
            outer.ox = 7;
            int k = chainA(inp);
            return outer.ox + k;
        }

        public static int chainA(Holder inp) {
            return grandA(inp);
        }

        public static int grandA(Holder inp) {
            return requestDeopt();
        }

        // Scenario B: inner is on the innermost scope's operand stack at the deopt.
        public static int operandStack(Holder inp) {
            Inner inner = new Inner();
            inner.ix = 3;
            int r;
            try {
                r = chainB(inp, inner);
            } catch (NullPointerException expected) {
                r = 0;
            }
            return inner.ix * 100 + r;
        }

        public static int chainB(Holder inp, Inner inner) {
            return grandB(inp, inner);
        }

        public static int grandB(Holder inp, Inner inner) {
            inner.ix = inp.h;
            return inner.ix;
        }

        // Scenario C: outer (root scope) references inner (inlined scope).
        public static int crossScopeGraph(Holder inp) {
            Outer outer = new Outer();
            outer.ox = 7;
            int k = chainC(inp, outer);
            Inner inner = (Inner) outer.ref;
            return outer.ox + (inner != null ? inner.ix : 0) + k;
        }

        public static int chainC(Holder inp, Outer outer) {
            Inner inner = new Inner();
            inner.ix = 5;
            outer.ref = inner;
            return grandC(inp);
        }

        public static int grandC(Holder inp) {
            return requestDeopt();
        }

        private static int requestDeopt() {
            PEATestUtils.ActiveFrameDeoptEvidence evidence =
                    PEATestUtils.deoptimizeActiveFrame(deoptTarget, 2);
            if (!evidence.frameDeoptimized() || evidence.compilationLevel() != 4
                    || evidence.markedNMethods() != 1) {
                throw new AssertionError("exact active-frame deopt evidence");
            }
            return 0;
        }

        private static long mix(long digest, int value) {
            return Long.rotateLeft(digest ^ Integer.toUnsignedLong(value), 17)
                    * 0x9e3779b97f4a7c15L;
        }
    }
}
