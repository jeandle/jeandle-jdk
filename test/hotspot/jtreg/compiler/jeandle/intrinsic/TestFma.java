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
 *
 */

/*
 * @test
 * @key randomness
 * @requires os.arch=="amd64" | os.arch=="x86_64" | os.arch=="aarch64"
 * @summary Test the intrinsics implementation of Math.fma(double,double,double) and (float,float,float)
 * @library /test/lib /
 * @run main/othervm compiler.jeandle.intrinsic.TestFma
 */

package compiler.jeandle.intrinsic;

import compiler.jeandle.fileCheck.FileCheck;
import jdk.test.lib.Asserts;
import jdk.test.lib.Utils;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class TestFma {
    public static void main(String[] args) throws Exception {
        String dumpPath = Files.createTempDirectory("jeandle_fma").toString();

        ArrayList<String> commandArgs = new ArrayList<>(List.of(
                "-Xbatch",
                "-XX:-TieredCompilation",
                "-XX:+UseJeandleCompiler",
                "-Xcomp",
                "-Xlog:jeandle=debug",
                "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dumpPath,
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::fma_double",
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::fma_float",
                TestWrapper.class.getName()));

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(commandArgs);
        OutputAnalyzer output = ProcessTools.executeCommand(pb);

        output.shouldHaveExitValue(0)
              .shouldContain("Method `static jdouble java.lang.Math.fma(jdouble, jdouble, jdouble)` is parsed as intrinsic")
              .shouldContain("Method `static jfloat java.lang.Math.fma(jfloat, jfloat, jfloat)` is parsed as intrinsic");

        // Verify the llvm intrinsic is used for each variant.
        FileCheck doubleCheck = new FileCheck(dumpPath,
                TestWrapper.class.getMethod("fma_double", double.class, double.class, double.class), false);
        doubleCheck.checkPattern("call double @llvm.fma.f64");

        FileCheck floatCheck = new FileCheck(dumpPath,
                TestWrapper.class.getMethod("fma_float", float.class, float.class, float.class), false);
        floatCheck.checkPattern("call float @llvm.fma.f32");
    }

    static class TestWrapper {
        static final double DUMMY = Math.fma(0.0d, 0.0d, 0.0d); // force load java.lang.Math

        public static void main(String[] args) {
            var random = Utils.getRandomInstance();

            // ---- double ----

            // Basic values (mirrors compiler/floatingpoint/TestFMA.java's sanity vectors)
            Asserts.assertEquals(57.0d, fma_double(5.0d, 10.0d, 7.0d), "fma(5,10,7)");
            Asserts.assertEquals(-43.0d, fma_double(-5.0d, 10.0d, 7.0d), "fma(-5,10,7)");
            Asserts.assertEquals(-43.0d, fma_double(5.0d, -10.0d, 7.0d), "fma(5,-10,7)");
            Asserts.assertEquals(43.0d, fma_double(5.0d, 10.0d, -7.0d), "fma(5,10,-7)");
            Asserts.assertEquals(-57.0d, fma_double(-5.0d, 10.0d, -7.0d), "fma(-5,10,-7)");

            // NaN propagates from any argument.
            Asserts.assertEquals(Double.NaN, fma_double(Double.NaN, 2.0d, 3.0d), "fma(NaN,2,3)");
            Asserts.assertEquals(Double.NaN, fma_double(2.0d, Double.NaN, 3.0d), "fma(2,NaN,3)");
            Asserts.assertEquals(Double.NaN, fma_double(2.0d, 3.0d, Double.NaN), "fma(2,3,NaN)");

            // One of the first two arguments is infinite, the other is zero -> NaN.
            Asserts.assertEquals(Double.NaN, fma_double(Double.POSITIVE_INFINITY, 0.0d, 1.0d), "fma(+Inf,0,1)");
            Asserts.assertEquals(Double.NaN, fma_double(0.0d, Double.POSITIVE_INFINITY, 1.0d), "fma(0,+Inf,1)");
            Asserts.assertEquals(Double.NaN, fma_double(Double.NEGATIVE_INFINITY, 0.0d, 1.0d), "fma(-Inf,0,1)");

            // Exact product is infinite and the addend is an infinity of the opposite sign -> NaN;
            // same sign -> that infinity.
            Asserts.assertEquals(Double.NaN, fma_double(Double.POSITIVE_INFINITY, 2.0d, Double.NEGATIVE_INFINITY), "fma(+Inf,2,-Inf)");
            Asserts.assertEquals(Double.POSITIVE_INFINITY, fma_double(Double.POSITIVE_INFINITY, 2.0d, Double.POSITIVE_INFINITY), "fma(+Inf,2,+Inf)");

            // Documented divergence from a plain multiply: fma(-0.0,+0.0,+0.0) is +0.0
            // while (-0.0 * +0.0) is -0.0.
            Asserts.assertEquals(0.0d, fma_double(-0.0d, 0.0d, 0.0d), "fma(-0,+0,+0)");
            Asserts.assertTrue(Double.doubleToRawLongBits(fma_double(-0.0d, 0.0d, 0.0d)) == Double.doubleToRawLongBits(0.0d),
                    "fma(-0,+0,+0) should be +0.0");

            // Random values, compared against the interpreter's (uncompiled, unintrinsified)
            // Math.fma, which is the JLS-authoritative correctly-rounded reference.
            for (int i = 0; i < 2000; i++) {
                double a = (random.nextDouble() - 0.5d) * 1.0e10d;
                double b = (random.nextDouble() - 0.5d) * 1.0e10d;
                double c = (random.nextDouble() - 0.5d) * 1.0e10d;
                Asserts.assertEquals(fma_double_verified(a, b, c), fma_double(a, b, c), "fma(" + a + "," + b + "," + c + ")");
            }
            // Adversarial: c nearly cancels a*b, stressing the single- vs double-rounding path
            // that distinguishes a true fused multiply-add from "a*b+c".
            for (int i = 0; i < 2000; i++) {
                double a = (random.nextDouble() - 0.5d) * 1.0e8d;
                double b = (random.nextDouble() - 0.5d) * 1.0e8d;
                double c = -(a * b) + (random.nextDouble() - 0.5d) * 1.0e-6d;
                Asserts.assertEquals(fma_double_verified(a, b, c), fma_double(a, b, c), "fma_cancel(" + a + "," + b + "," + c + ")");
            }

            // ---- float ----

            Asserts.assertEquals(57.0f, fma_float(5.0f, 10.0f, 7.0f), "fma(5,10,7)");
            Asserts.assertEquals(-43.0f, fma_float(-5.0f, 10.0f, 7.0f), "fma(-5,10,7)");
            Asserts.assertEquals(-43.0f, fma_float(5.0f, -10.0f, 7.0f), "fma(5,-10,7)");
            Asserts.assertEquals(43.0f, fma_float(5.0f, 10.0f, -7.0f), "fma(5,10,-7)");
            Asserts.assertEquals(-57.0f, fma_float(-5.0f, 10.0f, -7.0f), "fma(-5,10,-7)");

            Asserts.assertEquals(Float.NaN, fma_float(Float.NaN, 2.0f, 3.0f), "fma(NaN,2,3)");
            Asserts.assertEquals(Float.NaN, fma_float(2.0f, Float.NaN, 3.0f), "fma(2,NaN,3)");
            Asserts.assertEquals(Float.NaN, fma_float(2.0f, 3.0f, Float.NaN), "fma(2,3,NaN)");

            Asserts.assertEquals(Float.NaN, fma_float(Float.POSITIVE_INFINITY, 0.0f, 1.0f), "fma(+Inf,0,1)");
            Asserts.assertEquals(Float.NaN, fma_float(0.0f, Float.POSITIVE_INFINITY, 1.0f), "fma(0,+Inf,1)");
            Asserts.assertEquals(Float.NaN, fma_float(Float.NEGATIVE_INFINITY, 0.0f, 1.0f), "fma(-Inf,0,1)");

            Asserts.assertEquals(Float.NaN, fma_float(Float.POSITIVE_INFINITY, 2.0f, Float.NEGATIVE_INFINITY), "fma(+Inf,2,-Inf)");
            Asserts.assertEquals(Float.POSITIVE_INFINITY, fma_float(Float.POSITIVE_INFINITY, 2.0f, Float.POSITIVE_INFINITY), "fma(+Inf,2,+Inf)");

            Asserts.assertEquals(0.0f, fma_float(-0.0f, 0.0f, 0.0f), "fma(-0,+0,+0)");
            Asserts.assertTrue(Float.floatToRawIntBits(fma_float(-0.0f, 0.0f, 0.0f)) == Float.floatToRawIntBits(0.0f),
                    "fma(-0,+0,+0) should be +0.0");

            for (int i = 0; i < 2000; i++) {
                float a = (random.nextFloat() - 0.5f) * 1.0e4f;
                float b = (random.nextFloat() - 0.5f) * 1.0e4f;
                float c = (random.nextFloat() - 0.5f) * 1.0e4f;
                Asserts.assertEquals(fma_float_verified(a, b, c), fma_float(a, b, c), "fma(" + a + "," + b + "," + c + ")");
            }
            for (int i = 0; i < 2000; i++) {
                float a = (random.nextFloat() - 0.5f) * 1.0e3f;
                float b = (random.nextFloat() - 0.5f) * 1.0e3f;
                float c = -(a * b) + (random.nextFloat() - 0.5f) * 1.0e-3f;
                Asserts.assertEquals(fma_float_verified(a, b, c), fma_float(a, b, c), "fma_cancel(" + a + "," + b + "," + c + ")");
            }
        }

        public static double fma_double(double a, double b, double c) {
            return Math.fma(a, b, c);
        }

        // Not in the compileonly list, so this stays interpreted: the JLS-authoritative
        // reference implementation, independent of Jeandle's intrinsic lowering.
        public static double fma_double_verified(double a, double b, double c) {
            return Math.fma(a, b, c);
        }

        public static float fma_float(float a, float b, float c) {
            return Math.fma(a, b, c);
        }

        public static float fma_float_verified(float a, float b, float c) {
            return Math.fma(a, b, c);
        }
    }
}
