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
 * @summary PEA getClass folding: Object.getClass() on a virtual object folds to
 *          the exact Class mirror (ReplaceCall) without materializing, for
 *          instances, subclasses and arrays; field access around getClass still
 *          folds. The Class-mirror identity and array-class exactness match
 *          Java semantics.
 *          (foldLoadKlass is not Java-reachable: only phase-1 template bodies and
 *          the unloaded-catch path emit jeandle.load_klass, so it is lit-only.)
 * @library /test/lib /
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.pea.TestPEAGetClassAndArrayTypes
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;

import jdk.test.lib.Asserts;

public class TestPEAGetClassAndArrayTypes {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestPEAGetClassAndArrayTypes$TestWrapper";

    public static void main(String[] args) throws Exception {
        PEATestUtils.assertPhiParserContracts();

        Method exact = TestWrapper.class.getMethod("getClassPojoExact");
        Method sub = TestWrapper.class.getMethod("getClassSubVsSuper");
        Method iface = TestWrapper.class.getMethod("getClassVsInterface");
        Method primArr = TestWrapper.class.getMethod("getClassPrimitiveArray");
        Method objArr = TestWrapper.class.getMethod("getClassObjectArray");
        Method ifaceArr = TestWrapper.class.getMethod("getClassInterfaceArray");
        Method fields = TestWrapper.class.getMethod("fieldsAroundGetClass", boolean.class);
        Method[] targets = {exact, sub, iface, primArr, objArr, ifaceArr, fields};

        PEATestUtils.behaviorRun(WRAPPER, targets).runPEAOnOffEquivalent();

        try (PEATestUtils.RunResult run =
                PEATestUtils.shapeRun(WRAPPER, targets).run()) {
            assertGetClassFolded(run, exact, 1);
            assertGetClassFolded(run, sub, 1);
            assertGetClassFolded(run, iface, 2);
            assertGetClassFolded(run, primArr, 1);
            assertGetClassFolded(run, objArr, 1);
            assertGetClassFolded(run, ifaceArr, 2);
            assertGetClassFolded(run, fields, 1);
        }
    }

    private static void assertGetClassFolded(PEATestUtils.RunResult run, Method target,
                                             int sourceCount) throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();
        Asserts.assertEquals(before.peaAllocCount(), sourceCount,
                target + ": source allocation count");
        Asserts.assertTrue(after.allocationBCIs().isEmpty(),
                target + ": folded getClass eliminates every allocation");
        after.assertAbsent("jeandle.get_class");
        after.assertAbsent("jeandle.new_instance");
        after.assertAbsent("poison");
        Asserts.assertTrue(report.maxNeverEscapes() >= 1,
                target + ": classified NeverEscape in some round");
        Asserts.assertTrue(report.effects("ReplaceCall").size() >= 1
                        || report.effects("EliminateAllocation").size() >= sourceCount,
                target + ": getClass folded or allocation eliminated by PEA");
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
        private static final String EXPECTED_DIGEST = "228c3dd1ad46d1f2";

        public static class Pojo { int x; }
        public static class SubPojo extends Pojo { int y; }
        public interface Iface2 { }
        public static class Iface2Impl implements Iface2 { }

        public static void main(String[] args) throws Exception {
            new Pojo(); new SubPojo(); new Iface2Impl();
            PEATestUtils.compileConfiguredTargetsAtLevel4();

            long digest = 0x9E3779B97F4A7C15L;
            digest = mix(digest, getClassPojoExact());
            digest = mix(digest, getClassSubVsSuper());
            digest = mix(digest, getClassVsInterface());
            digest = mix(digest, getClassPrimitiveArray());
            digest = mix(digest, getClassObjectArray());
            digest = mix(digest, getClassInterfaceArray());
            for (boolean mutate : new boolean[] {false, true}) {
                digest = mix(digest, fieldsAroundGetClass(mutate));
            }

            String payload = Long.toUnsignedString(digest, 16);
            if (EXPECTED_DIGEST != null) {
                Asserts.assertEquals(payload, EXPECTED_DIGEST, "behavior digest");
            }
            System.out.println("PEA-RESULT:" + payload);
        }

        public static int getClassPojoExact() {
            Pojo p = new Pojo();
            p.x = 1;
            return (p.getClass() == Pojo.class) ? 1 : 0;
        }

        public static int getClassSubVsSuper() {
            SubPojo s = new SubPojo();
            s.x = 1;
            Class<?> c = s.getClass();
            int r = 0;
            r += (c == SubPojo.class) ? 1 : 0;
            r += (c == Pojo.class) ? 10 : 0;
            r += (c == Object.class) ? 100 : 0;
            return r;
        }

        public static int getClassVsInterface() {
            Pojo p = new Pojo();
            p.x = 1;
            Iface2Impl o = new Iface2Impl();
            Class<?> cp = p.getClass();
            Class<?> co = o.getClass();
            int r = 0;
            r += (cp == Iface2.class) ? 1 : 0;
            r += (co == Iface2Impl.class) ? 10 : 0;
            return r;
        }

        public static int getClassPrimitiveArray() {
            int[] a = new int[3];
            Object ao = a;
            Class<?> c = ao.getClass();
            int r = 0;
            r += (c == int[].class) ? 1 : 0;
            r += (c == long[].class) ? 10 : 0;
            r += (c == Object[].class) ? 100 : 0;
            r += (c == Object.class) ? 1000 : 0;
            return r;
        }

        public static int getClassObjectArray() {
            String[] a = new String[2];
            a[0] = "x";
            Object ao = a;
            Class<?> c = ao.getClass();
            int r = 0;
            r += (c == String[].class) ? 1 : 0;
            r += (c == Object[].class) ? 10 : 0;
            r += (c == Cloneable.class) ? 100 : 0;
            return r;
        }

        public static int getClassInterfaceArray() {
            Iface2Impl[] a = new Iface2Impl[1];
            a[0] = new Iface2Impl();
            Object ao = a;
            Class<?> c = ao.getClass();
            int r = 0;
            r += (c == Iface2Impl[].class) ? 1 : 0;
            r += (c == Iface2[].class) ? 10 : 0;
            return r;
        }

        public static int fieldsAroundGetClass(boolean mutate) {
            Pojo p = new Pojo();
            p.x = 7;
            Class<?> c = p.getClass();
            if (mutate) {
                p.x = 9;
            }
            return (c == Pojo.class ? 100 : 0) + p.x;
        }

        private static long mix(long digest, int value) {
            return Long.rotateLeft(digest ^ Integer.toUnsignedLong(value), 17)
                    * 0x9E3779B97F4A7C15L;
        }
    }
}
