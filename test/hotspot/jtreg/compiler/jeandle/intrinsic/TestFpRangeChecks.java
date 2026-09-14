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
 * @key randomness
 * @requires os.arch=="amd64" | os.arch=="x86_64" | os.arch=="aarch64"
 * @summary Test Float/Double isFinite and isInfinite Jeandle intrinsics.
 * @library /test/lib /
 * @run main/othervm compiler.jeandle.intrinsic.TestFpRangeChecks
 */

package compiler.jeandle.intrinsic;

import compiler.jeandle.fileCheck.FileCheck;
import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class TestFpRangeChecks {
    public static void main(String[] args) throws Exception {
        String dumpPath = Files.createTempDirectory("jeandle_fp_range_checks").toString();

        ArrayList<String> commandArgs = new ArrayList<>(List.of(
                "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
                "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dumpPath,
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::floatIsFinite",
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::floatIsInfinite",
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::doubleIsFinite",
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::doubleIsInfinite",
                TestWrapper.class.getName()));

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(commandArgs);
        OutputAnalyzer output = ProcessTools.executeCommand(pb);

        output.shouldHaveExitValue(0)
              .shouldContain("Method `static jboolean java.lang.Float.isFinite(jfloat)` is parsed as intrinsic")
              .shouldContain("Method `static jboolean java.lang.Float.isInfinite(jfloat)` is parsed as intrinsic")
              .shouldContain("Method `static jboolean java.lang.Double.isFinite(jdouble)` is parsed as intrinsic")
              .shouldContain("Method `static jboolean java.lang.Double.isInfinite(jdouble)` is parsed as intrinsic");

        FileCheck floatFiniteCheck = new FileCheck(dumpPath,
                TestWrapper.class.getMethod("floatIsFinite", float.class), false);
        floatFiniteCheck.checkPattern("call i1 @llvm\\.is\\.fpclass\\.f32.*i32 504");
        floatFiniteCheck.checkPattern("zext i1 .* to i32");

        FileCheck floatInfiniteCheck = new FileCheck(dumpPath,
                TestWrapper.class.getMethod("floatIsInfinite", float.class), false);
        floatInfiniteCheck.checkPattern("call i1 @llvm\\.is\\.fpclass\\.f32.*i32 516");
        floatInfiniteCheck.checkPattern("zext i1 .* to i32");

        FileCheck doubleFiniteCheck = new FileCheck(dumpPath,
                TestWrapper.class.getMethod("doubleIsFinite", double.class), false);
        doubleFiniteCheck.checkPattern("call i1 @llvm\\.is\\.fpclass\\.f64.*i32 504");
        doubleFiniteCheck.checkPattern("zext i1 .* to i32");

        FileCheck doubleInfiniteCheck = new FileCheck(dumpPath,
                TestWrapper.class.getMethod("doubleIsInfinite", double.class), false);
        doubleInfiniteCheck.checkPattern("call i1 @llvm\\.is\\.fpclass\\.f64.*i32 516");
        doubleInfiniteCheck.checkPattern("zext i1 .* to i32");
    }

    static class TestWrapper {
        static final boolean FLOAT_LOADED = Float.isFinite(0.0f);
        static final boolean DOUBLE_LOADED = Double.isFinite(0.0d);

        public static void main(String[] args) {
            float[] floats = {
                    0.0f, -0.0f, 1.0f, -1.0f,
                    Float.MIN_VALUE, -Float.MIN_VALUE,
                    Float.MIN_NORMAL, -Float.MIN_NORMAL,
                    Float.MAX_VALUE, -Float.MAX_VALUE,
                    Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
                    Float.NaN,
                    Float.intBitsToFloat(0x7fc00001),
                    Float.intBitsToFloat(0xffc00001),
                    Float.intBitsToFloat(0x7f800001),
                    Float.intBitsToFloat(0xff800001)
            };
            for (float value : floats) {
                Asserts.assertEquals(floatFiniteReference(value), floatIsFinite(value),
                        "Float.isFinite raw bits "
                                + Integer.toHexString(Float.floatToRawIntBits(value)));
                Asserts.assertEquals(floatInfiniteReference(value), floatIsInfinite(value),
                        "Float.isInfinite raw bits "
                                + Integer.toHexString(Float.floatToRawIntBits(value)));
            }

            double[] doubles = {
                    0.0d, -0.0d, 1.0d, -1.0d,
                    Double.MIN_VALUE, -Double.MIN_VALUE,
                    Double.MIN_NORMAL, -Double.MIN_NORMAL,
                    Double.MAX_VALUE, -Double.MAX_VALUE,
                    Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                    Double.NaN,
                    Double.longBitsToDouble(0x7ff8000000000001L),
                    Double.longBitsToDouble(0xfff8000000000001L),
                    Double.longBitsToDouble(0x7ff0000000000001L),
                    Double.longBitsToDouble(0xfff0000000000001L)
            };
            for (double value : doubles) {
                Asserts.assertEquals(doubleFiniteReference(value), doubleIsFinite(value),
                        "Double.isFinite raw bits "
                                + Long.toHexString(Double.doubleToRawLongBits(value)));
                Asserts.assertEquals(doubleInfiniteReference(value), doubleIsInfinite(value),
                        "Double.isInfinite raw bits "
                                + Long.toHexString(Double.doubleToRawLongBits(value)));
            }

            Random random = new Random(0x5eed5eedL);
            for (int i = 0; i < 10_000; i++) {
                float value = Float.intBitsToFloat(random.nextInt());
                Asserts.assertEquals(floatFiniteReference(value), floatIsFinite(value));
                Asserts.assertEquals(floatInfiniteReference(value), floatIsInfinite(value));
            }
            for (int i = 0; i < 10_000; i++) {
                double value = Double.longBitsToDouble(random.nextLong());
                Asserts.assertEquals(doubleFiniteReference(value), doubleIsFinite(value));
                Asserts.assertEquals(doubleInfiniteReference(value), doubleIsInfinite(value));
            }

            System.out.println("TestFpRangeChecks PASSED");
        }

        public static boolean floatIsFinite(float value) {
            return Float.isFinite(value);
        }

        public static boolean floatIsInfinite(float value) {
            return Float.isInfinite(value);
        }

        public static boolean doubleIsFinite(double value) {
            return Double.isFinite(value);
        }

        public static boolean doubleIsInfinite(double value) {
            return Double.isInfinite(value);
        }

        private static boolean floatFiniteReference(float value) {
            return (Float.floatToRawIntBits(value) & 0x7f800000) != 0x7f800000;
        }

        private static boolean floatInfiniteReference(float value) {
            return (Float.floatToRawIntBits(value) & 0x7fffffff) == 0x7f800000;
        }

        private static boolean doubleFiniteReference(double value) {
            return (Double.doubleToRawLongBits(value) & 0x7ff0000000000000L)
                    != 0x7ff0000000000000L;
        }

        private static boolean doubleInfiniteReference(double value) {
            return (Double.doubleToRawLongBits(value) & 0x7fffffffffffffffL)
                    == 0x7ff0000000000000L;
        }
    }
}
