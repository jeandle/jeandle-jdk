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
 * @summary Eliminate length-only dynamic arrays without losing negative length exceptions
 * @library /test/lib /
 * @build jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler compiler.jeandle.pea.TestUnusedArrayLength
 */
package compiler.jeandle.pea;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import jdk.test.lib.Asserts;

public class TestUnusedArrayLength {
    public static void main(String[] args) throws Exception {
        Method ints = Workload.class.getMethod("ints", int.class);
        Method bytes = Workload.class.getMethod("bytes", int.class);
        Method refs = Workload.class.getMethod("refs", int.class);
        Method nonnegative = Workload.class.getMethod("nonnegative", int.class);
        Method escaped = Workload.class.getMethod("escaped", int.class);
        PEATestUtils.behaviorRun(Workload.class.getName(), ints, bytes, refs, nonnegative, escaped)
                .runPEAOnOffEquivalent();
        try (var run = PEATestUtils.shapeRun(Workload.class.getName(), ints, bytes, refs, nonnegative, escaped).run()) {
            run.frontendIR(nonnegative).assertPresent("@jeandle.new_array");
            run.finalIR(nonnegative).assertAbsent("@new_array");
            run.finalIR(escaped).assertPresent("@new_array");
        }
    }

    public static class Workload {
        private static volatile Object published;
        private static int finalizers;

        public static void main(String[] args) throws Exception {
            // Load array classes before forcing compilation.
            ints(0); bytes(0); refs(0); nonnegative(0); escaped(0);
            PEATestUtils.compileConfiguredTargetsAtLevel4();
            for (int n : new int[] {0, 1, 17, 1024, 65536}) {
                Asserts.assertEquals(ints(n), n);
                Asserts.assertEquals(bytes(n), n);
                Asserts.assertEquals(refs(n), n);
                Asserts.assertEquals(nonnegative(n), n & 65535);
                Asserts.assertEquals(escaped(n), n);
                Asserts.assertEquals(Array.getLength(published), n);
            }
            for (int i = 0; i < 100; ++i) {
                Asserts.assertEquals(ints(-1), -17);
                Asserts.assertEquals(bytes(-1), -17);
                Asserts.assertEquals(refs(-1), -17);
            }
            Asserts.assertEquals(finalizers, 106);
            System.out.println("PEA-RESULT:unused-array-length-passed");
        }

        public static int ints(int n) {
            try {
                return Array.getLength(new int[n]);
            } catch (NegativeArraySizeException expected) {
                return -17;
            } finally {
                ++finalizers;
            }
        }

        public static int bytes(int n) {
            try { return Array.getLength(new byte[n]); }
            catch (NegativeArraySizeException expected) { return -17; }
        }

        public static int refs(int n) {
            try { return Array.getLength(new String[n]); }
            catch (NegativeArraySizeException expected) { return -17; }
        }

        public static int nonnegative(int n) {
            return Array.getLength(new int[n & 65535]);
        }

        public static int escaped(int n) {
            int[] a = new int[n];
            published = a;
            return Array.getLength(a);
        }
    }
}
