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
 * @summary Small primitive clones use non-tearing payload words and preserve floating-point bits
 * @library /test/lib /
 * @build jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler compiler.jeandle.arraycopy.TestPrimitiveCloneWords
 */
package compiler.jeandle.arraycopy;

import java.lang.reflect.Method;
import compiler.jeandle.pea.PEATestUtils;
import jdk.test.lib.Asserts;

public class TestPrimitiveCloneWords {
    public static void main(String[] args) throws Exception {
        Method bytes = Workload.class.getMethod("bytes", byte.class);
        Method chars = Workload.class.getMethod("chars", char.class);
        Method floats = Workload.class.getMethod("floats", float.class);
        Method doubles = Workload.class.getMethod("doubles", double.class);
        Method racing = Workload.class.getMethod("racing", int.class);
        PEATestUtils.behaviorRun(Workload.class.getName(), bytes, chars, floats, doubles, racing)
                .runPEAOnOffEquivalent();
        // Shape checks use fixed-size clone kernels; the racing driver includes
        // thread lifecycle code that can legitimately be recompiled.
        // Leave the copies for CopySpecialization instead of PEA element replay.
        try (var run = PEATestUtils.shapeRun(Workload.class.getName(), bytes, chars, floats, doubles)
                .maxArrayLength(0).run()) {
            for (Method method : new Method[] {bytes, chars, floats, doubles}) {
                run.frontendIR(method).assertPresent("@jeandle.copy");
                run.finalIR(method).assertPresent("load atomic volatile i64");
                run.finalIR(method).assertAbsent("@jeandle.clone_payload");
            }
            run.finalIR(bytes).assertAbsent("load atomic volatile i8");
        }
    }

    public static class Workload {
        private static volatile Object published;
        private static volatile boolean stop;
        private static volatile boolean started;
        private static volatile long writes;
        public static byte[] bytes(byte v) {
            byte[] a = {v, v, v, v, v, v, v, v};
            published = a;
            return a.clone();
        }

        public static char[] chars(char v) {
            char[] a = {v, v, v, v, v, v, v, v};
            published = a;
            return a.clone();
        }

        public static float[] floats(float v) {
            float[] a = {v, v, v, v, v, v, v, v};
            published = a;
            return a.clone();
        }

        public static double[] doubles(double v) {
            double[] a = {v, v, v, v, v, v, v, v};
            published = a;
            return a.clone();
        }

        public static void racing(int iterations) throws Exception {
            char[] source = {(char) 0x5555, (char) 0x5555, (char) 0x5555, (char) 0x5555,
                    (char) 0x5555, (char) 0x5555, (char) 0x5555, (char) 0x5555};
            stop = false;
            started = false;
            writes = 0;
            Thread writer = new Thread(() -> {
                char value = (char) 0xaaaa;
                while (!stop) {
                    for (int i = 0; i < source.length; ++i) { source[i] = value; }
                    ++writes;
                    started = true;
                    value ^= (char) 0xffff;
                }
            });
            writer.start();
            try {
                while (!started) { Thread.onSpinWait(); }
                for (int i = 0; i < iterations; ++i) {
                    char[] copy = source.clone();
                    published = copy;
                    for (char value : copy) {
                        if (value != (char) 0x5555 && value != (char) 0xaaaa) {
                            throw new AssertionError("torn char: " + (int) value);
                        }
                    }
                }
            } finally {
                stop = true;
                writer.join();
            }
            Asserts.assertGT(writes, 0L, "writer must mutate the cloned source");
        }

        public static void main(String[] args) throws Exception {
            bytes((byte) 0); chars((char) 0); floats(0); doubles(0);
            racing(100);
            PEATestUtils.compileConfiguredTargetsAtLevel4();
            for (int value : new int[] {0, -1, 0x55555555, 0xaaaaaaaa}) {
                for (byte b : bytes((byte) value)) { Asserts.assertEquals(b, (byte) value); }
                for (char c : chars((char) value)) { Asserts.assertEquals(c, (char) value); }
            }
            for (int bits : new int[] {0, 0x80000000, 0x7fc12345, 0x7f800000, 1}) {
                float value = Float.intBitsToFloat(bits);
                for (float f : floats(value)) {
                    Asserts.assertEquals(Float.floatToRawIntBits(f), Float.floatToRawIntBits(value));
                }
            }
            for (long bits : new long[] {0, Long.MIN_VALUE, 0x7ff8123456789abcL, 0x7ff0000000000000L, 1}) {
                double value = Double.longBitsToDouble(bits);
                for (double d : doubles(value)) {
                    Asserts.assertEquals(Double.doubleToRawLongBits(d), Double.doubleToRawLongBits(value));
                }
            }
            racing(20_000);
            System.out.println("PEA-RESULT:primitive-clone-words-passed");
        }
    }
}
