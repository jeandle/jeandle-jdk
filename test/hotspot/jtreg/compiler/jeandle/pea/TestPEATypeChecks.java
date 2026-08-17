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
 */

/*
 * @test
 * @summary PEA type checks on virtual objects: instanceof/checkcast of a known
 *          exact klass fold (ReplaceCall) while the receiver stays virtual, so
 *          the allocation and the chained field access are eliminated; a failed
 *          cast folds to false and throws CCE without materializing the virtual
 *          receiver; a published receiver keeps the real check. A [VirtualRef,
 *          null] merge materializes the inner virtual (Graal mergeObjectEntry).
 *          foldLoadKlass is not Java-reachable (only phase-1 template bodies and
 *          the unloaded-catch path emit jeandle.load_klass), so the klass-query
 *          oracle here is foldCheckCast (instanceof/checkcast) only.
 * @library /test/lib /
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.pea.TestPEATypeChecks
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;

import jdk.test.lib.Asserts;

public class TestPEATypeChecks {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestPEATypeChecks$TestWrapper";

    public static void main(String[] args) throws Exception {
        PEATestUtils.assertPhiParserContracts();

        Method exactTrue = TestWrapper.class.getMethod("instanceofExactTrue");
        Method sup = TestWrapper.class.getMethod("instanceofSuper");
        Method ifaceTrue = TestWrapper.class.getMethod("instanceofInterfaceTrue");
        Method unrelated = TestWrapper.class.getMethod("instanceofFalseUnrelated");
        Method primArr = TestWrapper.class.getMethod("instanceofPrimitiveArray");
        Method objArr = TestWrapper.class.getMethod("instanceofObjectArray");
        Method ifaceArr = TestWrapper.class.getMethod("instanceofInterfaceArray");
        Method nullOrVO = TestWrapper.class.getMethod("instanceofNullOrVO", boolean.class);
        Method chained = TestWrapper.class.getMethod("castSuccessChained");
        Method ifaceField = TestWrapper.class.getMethod("castSuccessInterfaceField");
        Method failsNoEsc = TestWrapper.class.getMethod("castFailsNoEscape");
        Method failsPub = TestWrapper.class.getMethod("castFailsAfterPublish");
        Method consume = TestWrapper.class.getMethod("consume", Object.class);
        Method[] targets = {exactTrue, sup, ifaceTrue, unrelated, primArr, objArr,
                ifaceArr, nullOrVO, chained, ifaceField, failsNoEsc, failsPub};

        PEATestUtils.behaviorRun(WRAPPER, targets).dontinline(consume).runPEAOnOffEquivalent();

        try (PEATestUtils.RunResult run =
                PEATestUtils.shapeRun(WRAPPER, targets).dontinline(consume).run()) {
            assertFoldedVirtual(run, exactTrue, 1);
            assertFoldedVirtual(run, sup, 1);
            assertFoldedVirtual(run, ifaceTrue, 1);
            assertFoldedVirtual(run, unrelated, 1);
            assertFoldedVirtual(run, primArr, 1);
            assertFoldedVirtual(run, objArr, 1);
            assertFoldedVirtual(run, ifaceArr, 2);
            assertFoldedVirtual(run, chained, 1);
            assertFoldedVirtual(run, ifaceField, 1);
            assertFoldedVirtual(run, failsNoEsc, 2);
            assertNullOrVOMerge(run, nullOrVO);
            assertPublishedReceiver(run, failsPub);
        }
    }

    // The type check folds (no surviving jeandle.check_instanceof / null check) and
    // every source allocation is eliminated; the receiver stays virtual throughout.
    private static void assertFoldedVirtual(PEATestUtils.RunResult run, Method target,
                                            int sourceCount) throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();
        Asserts.assertEquals(before.peaAllocCount(), sourceCount,
                target + ": source allocation count");
        Asserts.assertTrue(after.allocationBCIs().isEmpty(),
                target + ": folded type check eliminates every allocation");
        after.assertAbsent("jeandle.check_instanceof");
        after.assertAbsent("jeandle.new_instance");
        after.assertAbsent("poison");
        Asserts.assertTrue(report.maxNeverEscapes() >= 1,
                target + ": classified NeverEscape in some round");
        Asserts.assertTrue(report.effects("ReplaceCall").size() >= 1
                        || report.effects("EliminateAllocation").size() >= sourceCount,
                target + ": type check folded or allocation eliminated by PEA");
        report.assertFinalTransformIdle();
        assertVerifierShape(run, report, target);
    }

    // A [VirtualRef, null] merge materializes the inner virtual (the Sub allocation
    // is retained) even though the instanceof result is still computed correctly.
    private static void assertNullOrVOMerge(PEATestUtils.RunResult run, Method target)
            throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.IRBody after = report.finalAfter();
        after.assertAbsent("poison");
        Asserts.assertTrue(report.maxPartiallyEscapes() >= 1
                        || !after.allocationBCIs().isEmpty(),
                target + ": [VirtualRef, null] merge materializes the inner virtual");
        report.assertFinalTransformIdle();
        assertVerifierShape(run, report, target);
    }

    // The receiver is published before the cast, so it stays materialized and the
    // real subtype check must survive; the unrelated Other VO is still eliminated.
    private static void assertPublishedReceiver(PEATestUtils.RunResult run, Method target)
            throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();
        Asserts.assertEquals(before.peaAllocCount(), 2,
                target + ": published receiver plus Other");
        Asserts.assertEquals(after.allocationBCIs().size(), 1,
                target + ": published receiver retained, Other eliminated");
        Asserts.assertTrue(report.maxPartiallyEscapes() >= 1,
                target + ": published receiver classified PartiallyEscapes");
        after.assertAbsent("poison");
        report.assertFinalTransformIdle();
        assertVerifierShape(run, report, target);
    }

    private static void assertVerifierShape(PEATestUtils.RunResult run,
                                            PEATestUtils.PEAReport report,
                                            Method target) throws Exception {
        for (PEATestUtils.PEARound round : report.rounds()) {
            round.after().assertAbsent("poison");
            PEATestUtils.assertCompletePhis(round.after(), target.toString());
        }
        PEATestUtils.IRBody finalIR = run.finalIR(target);
        finalIR.assertAbsent("poison");
        PEATestUtils.assertCompletePhis(finalIR, target.toString());
    }

    public static class TestWrapper {
        private static final String EXPECTED_DIGEST = "27eee0f4fdd0356e";

        public static class Base { int x; }
        public static class Sub extends Base { int y; }
        public static class Unrelated { int z; }
        public interface Iface { }
        public static class IfaceImpl implements Iface { int w; }
        public static class Other { int q; }

        private static Object consumed;

        public static void main(String[] args) throws Exception {
            new Sub(); new IfaceImpl(); new Other(); new Unrelated();
            PEATestUtils.compileConfiguredTargetsAtLevel4();

            long digest = 0x9E3779B97F4A7C15L;
            digest = mix(digest, instanceofExactTrue());
            digest = mix(digest, instanceofSuper());
            digest = mix(digest, instanceofInterfaceTrue());
            digest = mix(digest, instanceofFalseUnrelated());
            digest = mix(digest, instanceofPrimitiveArray());
            digest = mix(digest, instanceofObjectArray());
            digest = mix(digest, instanceofInterfaceArray());
            digest = mix(digest, castSuccessChained());
            digest = mix(digest, castSuccessInterfaceField());
            digest = mix(digest, castFailsNoEscape());
            digest = mix(digest, castFailsAfterPublish());
            for (boolean useNull : new boolean[] {false, true}) {
                digest = mix(digest, instanceofNullOrVO(useNull));
            }

            String payload = Long.toUnsignedString(digest, 16);
            if (EXPECTED_DIGEST != null) {
                Asserts.assertEquals(payload, EXPECTED_DIGEST, "behavior digest");
            }
            System.out.println("PEA-RESULT:" + payload);
        }

        public static int instanceofExactTrue() {
            Sub s = new Sub();
            s.y = 3;
            return s instanceof Sub ? 1 : 0;
        }

        public static int instanceofSuper() {
            Sub s = new Sub();
            s.y = 3;
            return s instanceof Base ? 1 : 0;
        }

        public static int instanceofInterfaceTrue() {
            IfaceImpl o = new IfaceImpl();
            o.w = 5;
            return o instanceof Iface ? 1 : 0;
        }

        public static int instanceofFalseUnrelated() {
            Sub s = new Sub();
            s.y = 3;
            return s instanceof Iface ? 1 : 0;
        }

        public static int instanceofPrimitiveArray() {
            int[] a = new int[3];
            a[0] = 7;
            Object ao = a;
            int r = 0;
            r += (ao instanceof int[]) ? 1 : 0;
            r += (ao instanceof long[]) ? 10 : 0;
            r += (ao instanceof Object) ? 100 : 0;
            return r;
        }

        public static int instanceofObjectArray() {
            String[] a = new String[2];
            a[0] = "x";
            Object ao = a;
            int r = 0;
            r += (ao instanceof Object[]) ? 1 : 0;
            r += (ao instanceof String[]) ? 10 : 0;
            r += (ao instanceof Cloneable) ? 100 : 0;
            r += (ao instanceof int[]) ? 1000 : 0;
            return r;
        }

        public static int instanceofInterfaceArray() {
            IfaceImpl[] a = new IfaceImpl[1];
            a[0] = new IfaceImpl();
            Object ao = a;
            int r = 0;
            r += (ao instanceof IfaceImpl[]) ? 1 : 0;
            r += (ao instanceof Iface[]) ? 10 : 0;
            return r;
        }

        public static int instanceofNullOrVO(boolean useNull) {
            Object o = useNull ? null : new Sub();
            return (o instanceof Base) ? 1 : 0;
        }

        public static int castSuccessChained() {
            Object o = new Sub();
            ((Sub) o).y = 7;
            return ((Sub) o).y;
        }

        public static int castSuccessInterfaceField() {
            IfaceImpl o = new IfaceImpl();
            o.w = 9;
            if (o instanceof Iface) {
                return ((IfaceImpl) o).w;
            }
            return 0;
        }

        public static int castFailsNoEscape() {
            Sub o = new Sub();
            o.y = 5;
            Other t = new Other();
            t.q = 42;
            Object oo = o;
            try {
                Unrelated u = (Unrelated) oo;
                return u.z;
            } catch (ClassCastException e) {
                return t.q;
            }
        }

        public static int castFailsAfterPublish() {
            Sub o = new Sub();
            o.y = 5;
            Other t = new Other();
            t.q = 42;
            consume(o);
            Object oo = o;
            try {
                Unrelated u = (Unrelated) oo;
                return u.z;
            } catch (ClassCastException e) {
                return t.q;
            }
        }

        public static void consume(Object o) {
            consumed = o;
        }

        private static long mix(long digest, int value) {
            return Long.rotateLeft(digest ^ Integer.toUnsignedLong(value), 17)
                    * 0x9E3779B97F4A7C15L;
        }
    }
}
