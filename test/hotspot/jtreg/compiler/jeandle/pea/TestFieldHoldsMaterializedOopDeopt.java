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
 * @summary A materialized oop in a virtual field survives full GC and active deopt
 * @modules java.base/jdk.internal.misc
 * @library /test/lib /
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox
 *        compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      -XX:-UseCompressedOops -XX:-UseCompressedClassPointers
 *      compiler.jeandle.pea.TestFieldHoldsMaterializedOopDeopt
 */

package compiler.jeandle.pea;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;

import jdk.internal.misc.Unsafe;
import jdk.test.lib.Asserts;

public class TestFieldHoldsMaterializedOopDeopt {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestFieldHoldsMaterializedOopDeopt$TestWrapper";
    private static final Unsafe UNSAFE = Unsafe.getUnsafe();

    public static void main(String[] args) throws Exception {
        Method target = TestWrapper.class.getMethod(
                "test", TestWrapper.Ext.class);
        Method requestDeopt = TestWrapper.class.getDeclaredMethod("requestDeopt");

        PEATestUtils.behaviorRun(WRAPPER, target)
                .dontinline(requestDeopt)
                .runPEAOnOffEquivalent();

        try (PEATestUtils.RunResult run =
                PEATestUtils.shapeRun(WRAPPER, target)
                        .dontinline(requestDeopt)
                        .run()) {
            PEATestUtils.PEAReport report = run.report(target);
            for (PEATestUtils.PEARound round : report.rounds()) {
                PEATestUtils.assertStructuralSoundness(
                        round.before(), "field-oop round "
                                + round.iteration() + " before");
                PEATestUtils.assertStructuralSoundness(
                        round.after(), "field-oop round "
                                + round.iteration() + " after");
            }
            PEATestUtils.assertStructuralSoundness(
                    run.finalIR(target), "field-oop final IR");
            Asserts.assertEquals(report.round0Before().peaAllocCount(), 1,
                    "one Outer allocation enters PEA");
            Asserts.assertEquals(report.finalAfter().peaAllocCount(), 0,
                    "the never-escaping Outer allocation is eliminated");
            Asserts.assertEquals(run.finalIR(target).loweredAllocCount(), 0,
                    "no lowered Outer allocation remains");

            PEATestUtils.DeoptBundle bundle =
                    report.finalAfter().deoptBundleAtCall(
                            PEATestUtils.MethodId.of(requestDeopt)
                                    .llvmFunctionName(),
                            0);
            bundle.assertVirtualObjectIds(0);
            PEATestUtils.VirtualObjectDescriptor outer =
                    bundle.virtualObjects().get(0);
            Asserts.assertNotNull(outer, "Outer descriptor");
            int refOffset = offset(TestWrapper.Outer.class, "ref");
            int xOffset = offset(TestWrapper.Outer.class, "x");
            Asserts.assertEquals(outer.fields().keySet(),
                    Set.of(refOffset, xOffset), "exact Outer fields");
            PEATestUtils.VirtualObjectEntry ref = outer.fields().get(refOffset);
            Asserts.assertEquals(ref.basicType(),
                    PEATestUtils.DeoptBasicType.OBJECT, "ref basic type");
            Asserts.assertEquals(ref.value().kind(),
                    PEATestUtils.DeoptValueKind.MATERIALIZED_OOP,
                    "ref remains a GC-live materialized oop");
            Asserts.assertTrue(ref.value().operand().startsWith("ptr "),
                    "ref has a typed oop operand");
            PEATestUtils.VirtualObjectEntry x = outer.fields().get(xOffset);
            Asserts.assertEquals(x.basicType(),
                    PEATestUtils.DeoptBasicType.INT, "x basic type");
            Asserts.assertEquals(x.value().kind(),
                    PEATestUtils.DeoptValueKind.SCALAR, "x scalar value");
            Asserts.assertEquals(x.value().operand(), "i32 7", "x payload");
        }
    }

    private static int offset(Class<?> holder, String name) throws Exception {
        Field field = holder.getDeclaredField(name);
        return Math.toIntExact(UNSAFE.objectFieldOffset(field));
    }

    public static class TestWrapper {
        private static final Method TARGET = target("test", Ext.class);
        private static Ext expected;

        public static void main(String[] args) throws Exception {
            new Outer();
            new Ext();
            PEATestUtils.compileConfiguredTargetsAtLevel4();

            Ext ext = new Ext();
            ext.payload = 41;
            expected = ext;
            int result = test(ext);
            if (result != 89 || ext.payload != 82) {
                throw new AssertionError(
                        "post-deopt identity/payload result mismatch: " + result);
            }
            System.out.println("PEA-RESULT:" + result);
        }

        public static int test(Ext ext) {
            Outer outer = new Outer();
            outer.ref = ext;
            outer.x = 7;

            requestDeopt();

            if (outer.ref != expected || outer.ref != ext
                    || outer.ref.payload != 41 || outer.x != 7) {
                throw new AssertionError(
                        "reconstructed reference identity/payload mismatch");
            }
            outer.ref.payload = 82;
            return outer.x + outer.ref.payload;
        }

        private static void requestDeopt() {
            jdk.test.whitebox.WhiteBox.getWhiteBox().fullGC();
            PEATestUtils.ActiveFrameDeoptEvidence evidence =
                    PEATestUtils.deoptimizeActiveFrame(TARGET, 2);
            if (!evidence.frameDeoptimized()
                    || evidence.compilationLevel() != 4
                    || evidence.markedNMethods() != 1) {
                throw new AssertionError("exact active-frame deopt evidence");
            }
        }

        private static Method target(String name, Class<?>... parameterTypes) {
            try {
                return TestWrapper.class.getMethod(name, parameterTypes);
            } catch (ReflectiveOperationException failure) {
                throw new ExceptionInInitializerError(failure);
            }
        }

        public static class Outer {
            public Ext ref;
            public int x;
        }

        public static class Ext {
            public int payload;
        }
    }
}
