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
 * @summary Test the intrinsics implementation of Math/StrictMath.min|max(float,float) and (double,double)
 * @library /test/lib /
 * @run main/othervm compiler.jeandle.intrinsic.TestFpMinMax
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

public class TestFpMinMax {
    public static void main(String[] args) throws Exception {
        String dumpPath = Files.createTempDirectory("jeandle_fp_min_max").toString();

        ArrayList<String> commandArgs = new ArrayList<>(List.of(
                "-Xbatch",
                "-XX:-TieredCompilation",
                "-XX:+UseJeandleCompiler",
                "-Xcomp",
                "-Xlog:jeandle=debug",
                "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dumpPath,
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::min_float",
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::max_float",
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::min_double",
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::max_double",
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::min_float_strict",
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::max_float_strict",
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::min_double_strict",
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::max_double_strict",
                TestWrapper.class.getName()));

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(commandArgs);
        OutputAnalyzer output = ProcessTools.executeCommand(pb);

        output.shouldHaveExitValue(0)
              .shouldContain("Method `static jfloat java.lang.Math.min(jfloat, jfloat)` is parsed as intrinsic")
              .shouldContain("Method `static jfloat java.lang.Math.max(jfloat, jfloat)` is parsed as intrinsic")
              .shouldContain("Method `static jdouble java.lang.Math.min(jdouble, jdouble)` is parsed as intrinsic")
              .shouldContain("Method `static jdouble java.lang.Math.max(jdouble, jdouble)` is parsed as intrinsic")
              .shouldContain("Method `static jfloat java.lang.StrictMath.min(jfloat, jfloat)` is parsed as intrinsic")
              .shouldContain("Method `static jfloat java.lang.StrictMath.max(jfloat, jfloat)` is parsed as intrinsic")
              .shouldContain("Method `static jdouble java.lang.StrictMath.min(jdouble, jdouble)` is parsed as intrinsic")
              .shouldContain("Method `static jdouble java.lang.StrictMath.max(jdouble, jdouble)` is parsed as intrinsic");

        // Verify the llvm intrinsic is used for each variant.
        FileCheck minFloatCheck = new FileCheck(dumpPath, TestWrapper.class.getMethod("min_float", float.class, float.class), false);
        minFloatCheck.checkPattern("call float @llvm.minimum.f32");

        FileCheck maxFloatCheck = new FileCheck(dumpPath, TestWrapper.class.getMethod("max_float", float.class, float.class), false);
        maxFloatCheck.checkPattern("call float @llvm.maximum.f32");

        FileCheck minDoubleCheck = new FileCheck(dumpPath, TestWrapper.class.getMethod("min_double", double.class, double.class), false);
        minDoubleCheck.checkPattern("call double @llvm.minimum.f64");

        FileCheck maxDoubleCheck = new FileCheck(dumpPath, TestWrapper.class.getMethod("max_double", double.class, double.class), false);
        maxDoubleCheck.checkPattern("call double @llvm.maximum.f64");

        FileCheck minFloatStrictCheck = new FileCheck(dumpPath, TestWrapper.class.getMethod("min_float_strict", float.class, float.class), false);
        minFloatStrictCheck.checkPattern("call float @llvm.minimum.f32");

        FileCheck maxFloatStrictCheck = new FileCheck(dumpPath, TestWrapper.class.getMethod("max_float_strict", float.class, float.class), false);
        maxFloatStrictCheck.checkPattern("call float @llvm.maximum.f32");

        FileCheck minDoubleStrictCheck = new FileCheck(dumpPath, TestWrapper.class.getMethod("min_double_strict", double.class, double.class), false);
        minDoubleStrictCheck.checkPattern("call double @llvm.minimum.f64");

        FileCheck maxDoubleStrictCheck = new FileCheck(dumpPath, TestWrapper.class.getMethod("max_double_strict", double.class, double.class), false);
        maxDoubleStrictCheck.checkPattern("call double @llvm.maximum.f64");
    }

    static class TestWrapper {
        static final float  DUMMY1 = Math.min(0.0f, 0.0f);       // force load java.lang.Math
        static final double DUMMY2 = StrictMath.min(0.0d, 0.0d); // force load java.lang.StrictMath

        public static void main(String[] args) {
            var random = Utils.getRandomInstance();

            // ---- float ----

            // NaN propagates regardless of argument position.
            Asserts.assertEquals(Float.NaN, min_float(Float.NaN, 1.0f), "min(NaN,1.0)");
            Asserts.assertEquals(Float.NaN, min_float(1.0f, Float.NaN), "min(1.0,NaN)");
            Asserts.assertEquals(Float.NaN, min_float(Float.NaN, Float.NaN), "min(NaN,NaN)");
            Asserts.assertEquals(Float.NaN, max_float(Float.NaN, 1.0f), "max(NaN,1.0)");
            Asserts.assertEquals(Float.NaN, max_float(1.0f, Float.NaN), "max(1.0,NaN)");
            Asserts.assertEquals(Float.NaN, max_float(Float.NaN, Float.NaN), "max(NaN,NaN)");

            // Signed zero: -0.0 is strictly smaller than +0.0.
            Asserts.assertEquals(0.0f, min_float(0.0f, 0.0f), "min(+0,+0)");
            Asserts.assertEquals(-0.0f, min_float(0.0f, -0.0f), "min(+0,-0)");
            Asserts.assertEquals(-0.0f, min_float(-0.0f, 0.0f), "min(-0,+0)");
            Asserts.assertEquals(-0.0f, min_float(-0.0f, -0.0f), "min(-0,-0)");
            Asserts.assertEquals(0.0f, max_float(0.0f, 0.0f), "max(+0,+0)");
            Asserts.assertEquals(0.0f, max_float(0.0f, -0.0f), "max(+0,-0)");
            Asserts.assertEquals(0.0f, max_float(-0.0f, 0.0f), "max(-0,+0)");
            Asserts.assertEquals(-0.0f, max_float(-0.0f, -0.0f), "max(-0,-0)");
            // Explicit raw-bits check for the sign of the zero result.
            Asserts.assertTrue(Float.floatToRawIntBits(min_float(0.0f, -0.0f)) == Float.floatToRawIntBits(-0.0f),
                    "min(+0,-0) should be -0.0");
            Asserts.assertTrue(Float.floatToRawIntBits(max_float(0.0f, -0.0f)) == Float.floatToRawIntBits(0.0f),
                    "max(+0,-0) should be +0.0");

            // Infinity
            Asserts.assertEquals(1.0f, min_float(Float.POSITIVE_INFINITY, 1.0f), "min(+Inf,1.0)");
            Asserts.assertEquals(Float.NEGATIVE_INFINITY, min_float(Float.NEGATIVE_INFINITY, 1.0f), "min(-Inf,1.0)");
            Asserts.assertEquals(Float.POSITIVE_INFINITY, max_float(Float.POSITIVE_INFINITY, 1.0f), "max(+Inf,1.0)");
            Asserts.assertEquals(1.0f, max_float(Float.NEGATIVE_INFINITY, 1.0f), "max(-Inf,1.0)");
            Asserts.assertEquals(Float.NEGATIVE_INFINITY, min_float(Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY), "min(+Inf,-Inf)");
            Asserts.assertEquals(Float.POSITIVE_INFINITY, max_float(Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY), "max(+Inf,-Inf)");

            // StrictMath shares Math's exact semantics.
            Asserts.assertEquals(Float.NaN, min_float_strict(Float.NaN, 1.0f), "strictMin(NaN,1.0)");
            Asserts.assertEquals(-0.0f, min_float_strict(0.0f, -0.0f), "strictMin(+0,-0)");
            Asserts.assertEquals(0.0f, max_float_strict(0.0f, -0.0f), "strictMax(+0,-0)");

            // Random values (never exactly zero/NaN/Inf, so a plain comparison is a valid reference)
            for (int i = 0; i < 2000; i++) {
                float x = (random.nextFloat() - 0.5f) * 2000.0f;
                float y = (random.nextFloat() - 0.5f) * 2000.0f;
                float expectedMin = (x <= y) ? x : y;
                float expectedMax = (x >= y) ? x : y;
                Asserts.assertEquals(expectedMin, min_float(x, y), "min(" + x + "," + y + ")");
                Asserts.assertEquals(expectedMax, max_float(x, y), "max(" + x + "," + y + ")");
                Asserts.assertEquals(expectedMin, min_float_strict(x, y), "strictMin(" + x + "," + y + ")");
                Asserts.assertEquals(expectedMax, max_float_strict(x, y), "strictMax(" + x + "," + y + ")");
            }

            // ---- double ----

            // NaN propagates regardless of argument position.
            Asserts.assertEquals(Double.NaN, min_double(Double.NaN, 1.0d), "min(NaN,1.0)");
            Asserts.assertEquals(Double.NaN, min_double(1.0d, Double.NaN), "min(1.0,NaN)");
            Asserts.assertEquals(Double.NaN, min_double(Double.NaN, Double.NaN), "min(NaN,NaN)");
            Asserts.assertEquals(Double.NaN, max_double(Double.NaN, 1.0d), "max(NaN,1.0)");
            Asserts.assertEquals(Double.NaN, max_double(1.0d, Double.NaN), "max(1.0,NaN)");
            Asserts.assertEquals(Double.NaN, max_double(Double.NaN, Double.NaN), "max(NaN,NaN)");

            // Signed zero: -0.0 is strictly smaller than +0.0.
            Asserts.assertEquals(0.0d, min_double(0.0d, 0.0d), "min(+0,+0)");
            Asserts.assertEquals(-0.0d, min_double(0.0d, -0.0d), "min(+0,-0)");
            Asserts.assertEquals(-0.0d, min_double(-0.0d, 0.0d), "min(-0,+0)");
            Asserts.assertEquals(-0.0d, min_double(-0.0d, -0.0d), "min(-0,-0)");
            Asserts.assertEquals(0.0d, max_double(0.0d, 0.0d), "max(+0,+0)");
            Asserts.assertEquals(0.0d, max_double(0.0d, -0.0d), "max(+0,-0)");
            Asserts.assertEquals(0.0d, max_double(-0.0d, 0.0d), "max(-0,+0)");
            Asserts.assertEquals(-0.0d, max_double(-0.0d, -0.0d), "max(-0,-0)");
            // Explicit raw-bits check for the sign of the zero result.
            Asserts.assertTrue(Double.doubleToRawLongBits(min_double(0.0d, -0.0d)) == Double.doubleToRawLongBits(-0.0d),
                    "min(+0,-0) should be -0.0");
            Asserts.assertTrue(Double.doubleToRawLongBits(max_double(0.0d, -0.0d)) == Double.doubleToRawLongBits(0.0d),
                    "max(+0,-0) should be +0.0");

            // Infinity
            Asserts.assertEquals(1.0d, min_double(Double.POSITIVE_INFINITY, 1.0d), "min(+Inf,1.0)");
            Asserts.assertEquals(Double.NEGATIVE_INFINITY, min_double(Double.NEGATIVE_INFINITY, 1.0d), "min(-Inf,1.0)");
            Asserts.assertEquals(Double.POSITIVE_INFINITY, max_double(Double.POSITIVE_INFINITY, 1.0d), "max(+Inf,1.0)");
            Asserts.assertEquals(1.0d, max_double(Double.NEGATIVE_INFINITY, 1.0d), "max(-Inf,1.0)");
            Asserts.assertEquals(Double.NEGATIVE_INFINITY, min_double(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY), "min(+Inf,-Inf)");
            Asserts.assertEquals(Double.POSITIVE_INFINITY, max_double(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY), "max(+Inf,-Inf)");

            // StrictMath shares Math's exact semantics.
            Asserts.assertEquals(Double.NaN, min_double_strict(Double.NaN, 1.0d), "strictMin(NaN,1.0)");
            Asserts.assertEquals(-0.0d, min_double_strict(0.0d, -0.0d), "strictMin(+0,-0)");
            Asserts.assertEquals(0.0d, max_double_strict(0.0d, -0.0d), "strictMax(+0,-0)");

            // Random values (never exactly zero/NaN/Inf, so a plain comparison is a valid reference)
            for (int i = 0; i < 2000; i++) {
                double x = (random.nextDouble() - 0.5d) * 2000.0d;
                double y = (random.nextDouble() - 0.5d) * 2000.0d;
                double expectedMin = (x <= y) ? x : y;
                double expectedMax = (x >= y) ? x : y;
                Asserts.assertEquals(expectedMin, min_double(x, y), "min(" + x + "," + y + ")");
                Asserts.assertEquals(expectedMax, max_double(x, y), "max(" + x + "," + y + ")");
                Asserts.assertEquals(expectedMin, min_double_strict(x, y), "strictMin(" + x + "," + y + ")");
                Asserts.assertEquals(expectedMax, max_double_strict(x, y), "strictMax(" + x + "," + y + ")");
            }
        }

        public static float min_float(float a, float b) {
            return Math.min(a, b);
        }

        public static float max_float(float a, float b) {
            return Math.max(a, b);
        }

        public static double min_double(double a, double b) {
            return Math.min(a, b);
        }

        public static double max_double(double a, double b) {
            return Math.max(a, b);
        }

        public static float min_float_strict(float a, float b) {
            return StrictMath.min(a, b);
        }

        public static float max_float_strict(float a, float b) {
            return StrictMath.max(a, b);
        }

        public static double min_double_strict(double a, double b) {
            return StrictMath.min(a, b);
        }

        public static double max_double_strict(double a, double b) {
            return StrictMath.max(a, b);
        }
    }
}
