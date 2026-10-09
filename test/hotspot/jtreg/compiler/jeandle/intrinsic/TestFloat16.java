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
 * @requires (vm.cpu.features ~= ".*avx512vl.*" | vm.cpu.features ~= ".*f16c.*") | os.arch=="aarch64"
 * @summary Test the intrinsic implementation of Float.floatToFloat16 and Float.float16ToFloat
 * @library /test/lib /
 * @run main/othervm compiler.jeandle.intrinsic.TestFloat16
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

public class TestFloat16 {
    public static void main(String[] args) throws Exception {
        String dumpPath = Files.createTempDirectory("jeandle_float16").toString();

        // Note: TestWrapper::main is intentionally NOT in the compileonly list below, so it
        // always runs interpreted. That makes every direct Float.floatToFloat16/float16ToFloat
        // call made inside main() (used below as the correctness oracle) run the real
        // interpreter/pure-Java reference behavior, independent of the intrinsic lowering under
        // test in the floatToFloat16/float16ToFloat wrapper methods.
        ArrayList<String> commandArgs = new ArrayList<>(List.of(
                "-Xbatch",
                "-XX:-TieredCompilation",
                "-XX:+UseJeandleCompiler",
                "-Xcomp",
                "-Xlog:jeandle=debug",
                "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dumpPath,
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::floatToFloat16",
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::float16ToFloat",
                TestWrapper.class.getName()));

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(commandArgs);
        OutputAnalyzer output = ProcessTools.executeCommand(pb);

        output.shouldHaveExitValue(0)
              .shouldContain("is parsed as intrinsic");

        // floatToFloat16: fptrunc float->half, then bitcast half->i16, then sign-extend back to
        // the computational int the JVM stack uses for short.
        FileCheck toF16Check = new FileCheck(dumpPath,
                TestWrapper.class.getMethod("floatToFloat16", float.class), false);
        toF16Check.checkPattern("fptrunc float .* to half");
        toF16Check.checkPattern("bitcast half .* to i16");
        toF16Check.checkPattern("sext i16 .* to i32");

        // float16ToFloat: truncate the computational int down to i16, bitcast to half, then
        // fpext half->float.
        FileCheck fromF16Check = new FileCheck(dumpPath,
                TestWrapper.class.getMethod("float16ToFloat", short.class), false);
        fromF16Check.checkPattern("trunc i32 .* to i16");
        fromF16Check.checkPattern("bitcast i16 .* to half");
        fromF16Check.checkPattern("fpext half .* to float");
    }

    static class TestWrapper {
        // Force load java.lang.Float
        static final short DUMMY = Float.floatToFloat16(0.0f);

        public static void main(String[] args) {
            var random = Utils.getRandomInstance();

            // ---- Special values ----
            Asserts.assertEquals((short) 0x0000, floatToFloat16(0.0f), "floatToFloat16(+0.0)");
            Asserts.assertEquals((short) 0x8000, floatToFloat16(-0.0f), "floatToFloat16(-0.0)");
            Asserts.assertEquals((short) 0x7C00, floatToFloat16(Float.POSITIVE_INFINITY),
                    "floatToFloat16(+Inf)");
            Asserts.assertEquals((short) 0xFC00, floatToFloat16(Float.NEGATIVE_INFINITY),
                    "floatToFloat16(-Inf)");

            Asserts.assertEquals(0.0f, float16ToFloat((short) 0x0000), "float16ToFloat(+0)");
            Asserts.assertEquals(-0.0f, float16ToFloat((short) 0x8000), "float16ToFloat(-0)");
            Asserts.assertTrue(Float.floatToRawIntBits(float16ToFloat((short) 0x8000))
                            == Float.floatToRawIntBits(-0.0f),
                    "float16ToFloat(-0) sign must be preserved");
            Asserts.assertEquals(Float.POSITIVE_INFINITY, float16ToFloat((short) 0x7C00),
                    "float16ToFloat(+Inf)");
            Asserts.assertEquals(Float.NEGATIVE_INFINITY, float16ToFloat((short) 0xFC00),
                    "float16ToFloat(-Inf)");

            // ---- NaN: result must still be NaN; exact payload bits are not specified. ----
            Asserts.assertTrue(isFloat16NaN(floatToFloat16(Float.NaN)), "floatToFloat16(NaN) must be NaN");
            Asserts.assertTrue(Float.isNaN(float16ToFloat((short) 0x7E00)),
                    "float16ToFloat(quiet NaN bits) must be NaN");
            Asserts.assertTrue(Float.isNaN(float16ToFloat((short) 0xFE00)),
                    "float16ToFloat(negative NaN bits) must be NaN");

            // ---- Exactly representable values: exact round trip. ----
            float[] exact = {
                1.0f, -1.0f, 2.0f, -2.0f, 0.5f, -0.5f, 3.0f, 1.5f, 1024.0f,
                65504.0f, -65504.0f,           // binary16 max finite magnitude
                0.00006103515625f,             // 2^-14, binary16 min normal
            };
            for (float f : exact) {
                Asserts.assertEquals(f, float16ToFloat(floatToFloat16(f)), "exact round trip for " + f);
            }

            // ---- Overflow: magnitudes beyond binary16 max finite (65504) become infinity. ----
            Asserts.assertEquals((short) 0x7C00, floatToFloat16(70000.0f),
                    "floatToFloat16(70000) overflows to +Inf");
            Asserts.assertEquals((short) 0xFC00, floatToFloat16(-70000.0f),
                    "floatToFloat16(-70000) overflows to -Inf");
            Asserts.assertEquals((short) 0x7C00, floatToFloat16(Float.MAX_VALUE),
                    "floatToFloat16(MAX_VALUE) overflows to +Inf");
            Asserts.assertEquals((short) 0xFC00, floatToFloat16(-Float.MAX_VALUE),
                    "floatToFloat16(-MAX_VALUE) overflows to -Inf");

            // ---- Underflow: magnitudes far below binary16's smallest subnormal flush to zero. ----
            Asserts.assertEquals((short) 0x0000, floatToFloat16(1.0e-10f),
                    "floatToFloat16(tiny) flushes to +0");
            Asserts.assertEquals((short) 0x8000, floatToFloat16(-1.0e-10f),
                    "floatToFloat16(-tiny) flushes to -0");

            // ---- Random fuzzing across the full float bit space, checked against the
            //      interpreter-run Float.floatToFloat16/float16ToFloat. ----
            for (int i = 0; i < 5000; i++) {
                float f = Float.intBitsToFloat(random.nextInt());
                short actual = floatToFloat16(f);
                if (Float.isNaN(f)) {
                    Asserts.assertTrue(isFloat16NaN(actual), "floatToFloat16(NaN input) must stay NaN for " + f);
                } else {
                    Asserts.assertEquals(Float.floatToFloat16(f), actual, "floatToFloat16 mismatch for " + f);
                }
            }
            for (int i = 0; i < 5000; i++) {
                short bits = (short) random.nextInt();
                float actual = float16ToFloat(bits);
                float expected = Float.float16ToFloat(bits);
                if (Float.isNaN(expected)) {
                    Asserts.assertTrue(Float.isNaN(actual), "float16ToFloat(NaN bits) must stay NaN for bits=" + bits);
                } else {
                    Asserts.assertEquals(expected, actual, "float16ToFloat mismatch for bits=" + bits);
                }
            }

            System.out.println("TestFloat16 PASSED");
        }

        static boolean isFloat16NaN(short bits) {
            int b = bits & 0xFFFF;
            return (b & 0x7C00) == 0x7C00 && (b & 0x03FF) != 0;
        }

        public static short floatToFloat16(float f) {
            return Float.floatToFloat16(f);
        }

        public static float float16ToFloat(short bits) {
            return Float.float16ToFloat(bits);
        }
    }
}
