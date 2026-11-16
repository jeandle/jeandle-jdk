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
 * @summary Remove unused dynamic array clones while preserving checks and escaping identities
 * @library /test/lib /
 * @modules java.base/jdk.internal.misc
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler compiler.jeandle.pea.TestUnusedDynamicClone
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;
import jdk.test.lib.Asserts;

public class TestUnusedDynamicClone {
    public static void main(String[] args) throws Exception {
        String wrapper = TestWrapper.class.getName();
        Method primitive = TestWrapper.class.getMethod("unusedPrimitive", int[].class);
        Method reference = TestWrapper.class.getMethod("unusedReference", String[].class);
        Method exported = TestWrapper.class.getMethod("exported", int[].class);
        PEATestUtils.behaviorRun(wrapper, primitive, reference, exported)
                .runPEAOnOffEquivalent();
        try (PEATestUtils.RunResult run = PEATestUtils.shapeRun(wrapper, primitive, reference, exported)
                .extraFlags("-Dpea.unused.clone.shape=true").run()) {
            for (Method target : new Method[] {primitive, reference}) {
                run.frontendIR(target).assertPresent("@jeandle.copy");
                run.frontendIR(target).assertPresent("@jeandle.new_array");
                run.finalIR(target).assertAbsent("@new_array");
                run.finalIR(target).assertAbsent("@jeandle.clone_payload");
                run.finalIR(target).assertAbsent("@jeandle.copy");
            }
            run.finalIR(exported).assertPresent("@new_array");
        }
    }

    public static class TestWrapper {
        public static void main(String[] args) throws Exception {
            new int[1].clone();
            new String[1].clone();
            PEATestUtils.compileConfiguredTargetsAtLevel4();
            for (int length : new int[] {0, 1, 17, 1024}) {
                int[] primitive = new int[length];
                String[] reference = new String[length];
                Asserts.assertEquals(unusedPrimitive(primitive), 37);
                Asserts.assertEquals(unusedReference(reference), 37);
                int[] copy = exported(primitive);
                Asserts.assertTrue(copy != primitive, "escaping clone keeps its identity");
                Asserts.assertEquals(copy.length, primitive.length);
                if (length != 0) {
                    primitive[0] = 19;
                    Asserts.assertEquals(copy[0], 0, "escaping clone keeps its snapshot");
                }
            }
            if (!Boolean.getBoolean("pea.unused.clone.shape")) {
                try {
                    unusedPrimitive(null);
                    throw new AssertionError("missing primitive clone null check");
                } catch (NullPointerException expected) {
                }
                try {
                    unusedReference(null);
                    throw new AssertionError("missing reference clone null check");
                } catch (NullPointerException expected) {
                }
            }
            System.out.println("PEA-RESULT:unused-dynamic-clone-passed");
        }

        public static int unusedPrimitive(int[] source) {
            source.clone();
            return 37;
        }

        public static int unusedReference(String[] source) {
            source.clone();
            return 37;
        }

        public static int[] exported(int[] source) {
            return source.clone();
        }
    }
}
