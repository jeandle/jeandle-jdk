/*
 * Copyright (c) 2026, the Jeandle-JDK Authors. All Rights Reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License version 2 for more details (a copy is included
 * in the LICENSE file that accompanied this code).
 *
 * You should have received a copy of the GNU General Public License
 * version 2 along with this work; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 */

/*
 * @test
 * @summary Verify bit-exact copySign stores and preserve raw-bit reduction intrinsics
 * @requires os.arch=="amd64" | os.arch=="x86_64" | os.arch=="aarch64"
 * @library /test/lib /
 * @run main/othervm compiler.jeandle.intrinsic.TestCopySignStore
 */

package compiler.jeandle.intrinsic;

import compiler.jeandle.fileCheck.FileCheck;
import java.nio.file.Files;
import jdk.test.lib.Asserts;
import jdk.test.lib.Platform;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestCopySignStore {
    public static void main(String[] args) throws Exception {
        String dump = Files.createTempDirectory("jeandle_copySign_store").toString();
        OutputAnalyzer output = ProcessTools.executeCommand(
                ProcessTools.createLimitedTestJavaProcessBuilder(
                        "-Xcomp", "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler",
                        "-XX:+UseCopySignIntrinsic", "-Xlog:jeandle=debug",
                        "-XX:+UnlockDiagnosticVMOptions", "-XX:+JeandleDumpIR",
                        "-XX:JeandleDumpDirectory=" + dump,
                        "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::*Store",
                        "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::*Reduce",
                        TestWrapper.class.getName()));
        output.shouldHaveExitValue(0).shouldContain("COPY_SIGN_STORE_BITS_PASSED")
              .shouldContain("java.lang.Math.copySign").shouldContain("is parsed as intrinsic");

        // Math is intrinsic in the frontend; StrictMath is inlined later and
        // keeps its NaN-sign normalization around the Math intrinsic.
        for (String method : new String[] {"doubleStore", "floatStore",
                                           "doubleStrictStore", "floatStrictStore"}) {
            boolean isDouble = method.startsWith("double");
            boolean isStrict = method.contains("Strict");
            Class<?> arrayType = isDouble ? double[].class : float[].class;
            String fpType = isDouble ? "double" : "float";
            String intType = isDouble ? "i64" : "i32";
            FileCheck raw = new FileCheck(dump, TestWrapper.class.getMethod(method,
                    arrayType, arrayType, arrayType), false);
            if (isStrict) {
                raw.check("invoke hotspotcc " + fpType + " @\"java_lang_StrictMath_copySign");
            } else {
                raw.check("call " + fpType + " @llvm.copysign.");
            }
            raw.check("store atomic " + fpType);

            FileCheck optimized = new FileCheck(dump, TestWrapper.class.getMethod(method,
                    arrayType, arrayType, arrayType), true);
            optimized.checkPattern("define hotspotcc void .*" + method + ".*");
            if (isStrict) {
                optimized.check("fcmp ord " + fpType);
                optimized.checkPattern("select i1 .*" + fpType + " .*" + fpType + " 1.000000e\\+00");
                optimized.check("call " + fpType + " @llvm.copysign.");
                optimized.check("store atomic " + fpType);
                optimized.checkNot("StrictMath_copySign");
            } else if (Platform.isX64()) {
                optimized.check("load atomic " + intType);
                optimized.checkPattern("and " + intType + " .*"
                        + (isDouble ? "-9223372036854775808" : "-2147483648"));
                optimized.check("store atomic " + intType);
                optimized.checkNot("llvm.copysign");
            } else {
                optimized.check("call " + fpType + " @llvm.copysign.");
            }
        }

        // A raw-bit sum must keep copysign instead of expanding it into a
        // longer integer sign/magnitude sequence in the reduction loop.
        for (String method : new String[] {"doubleReduce", "floatReduce"}) {
            boolean isDouble = method.startsWith("double");
            Class<?> arrayType = isDouble ? double[].class : float[].class;
            String fpType = isDouble ? "double" : "float";
            for (boolean optimized : new boolean[] {false, true}) {
                FileCheck checker = new FileCheck(dump, TestWrapper.class.getMethod(method,
                        arrayType, arrayType), optimized);
                checker.check("call " + fpType + " @llvm.copysign.");
                checker.checkPattern("bitcast " + fpType + " .* to "
                        + (isDouble ? "i64" : "i32"));
                checker.check("add " + (isDouble ? "i64" : "i32"));
            }
        }

    }

    static class TestWrapper {
        // Include both signs of zero, subnormals, infinity, quiet NaNs and
        // signaling NaNs with payloads; use every magnitude/sign combination.
        private static final long[] DOUBLE_BITS = {
            0L, 0x8000000000000000L, 1L, 0x8000000000000001L,
            0x0010000000000000L, 0x8010000000000000L,
            0x3ff4000000000000L, 0xbff4000000000000L,
            0x7fefffffffffffffL, 0xffefffffffffffffL,
            0x7ff0000000000000L, 0xfff0000000000000L,
            0x7ff8000000000042L, 0xfff8000000000042L,
            0x7ff0000000000001L, 0xfff0000000000001L
        };
        private static final int[] FLOAT_BITS = {
            0, 0x80000000, 1, 0x80000001, 0x00800000, 0x80800000,
            0x3fa00000, 0xbfa00000, 0x7f7fffff, 0xff7fffff,
            0x7f800000, 0xff800000, 0x7fc00042, 0xffc00042,
            0x7f800001, 0xff800001
        };

        public static void main(String[] args) {
            // Resolve both overloads before compiling the wrappers with -Xcomp.
            Math.copySign(1.0, -1.0);
            Math.copySign(1.0f, -1.0f);
            StrictMath.copySign(1.0, -1.0);
            StrictMath.copySign(1.0f, -1.0f);
            checkReductions();
            for (boolean strict : new boolean[] {false, true}) {
                for (int alias = 0; alias < 3; alias++) {
                    int n = DOUBLE_BITS.length * DOUBLE_BITS.length;
                    double[] m = new double[n];
                    double[] s = new double[n];
                    float[] mf = new float[n];
                    float[] sf = new float[n];
                    long[] expected = new long[n];
                    int[] expectedF = new int[n];
                    for (int i = 0; i < n; i++) {
                        m[i] = Double.longBitsToDouble(DOUBLE_BITS[i / DOUBLE_BITS.length]);
                        s[i] = Double.longBitsToDouble(DOUBLE_BITS[i % DOUBLE_BITS.length]);
                        mf[i] = Float.intBitsToFloat(FLOAT_BITS[i / FLOAT_BITS.length]);
                        sf[i] = Float.intBitsToFloat(FLOAT_BITS[i % FLOAT_BITS.length]);
                        double sign = strict && Double.isNaN(s[i]) ? 1.0 : s[i];
                        float signF = strict && Float.isNaN(sf[i]) ? 1.0f : sf[i];
                        expected[i] = (Double.doubleToRawLongBits(m[i]) & Long.MAX_VALUE)
                                    | (Double.doubleToRawLongBits(sign) & Long.MIN_VALUE);
                        expectedF[i] = (Float.floatToRawIntBits(mf[i]) & Integer.MAX_VALUE)
                                     | (Float.floatToRawIntBits(signF) & Integer.MIN_VALUE);
                    }
                    double[] out = alias == 1 ? m : alias == 2 ? s : new double[n];
                    float[] outF = alias == 1 ? mf : alias == 2 ? sf : new float[n];
                    if (strict) {
                        doubleStrictStore(m, s, out);
                        floatStrictStore(mf, sf, outF);
                    } else {
                        doubleStore(m, s, out);
                        floatStore(mf, sf, outF);
                    }
                    for (int i = 0; i < n; i++) {
                        Asserts.assertEquals(Double.doubleToRawLongBits(out[i]), expected[i],
                                "double strict=" + strict + " alias=" + alias + " index=" + i);
                        Asserts.assertEquals(Float.floatToRawIntBits(outF[i]), expectedF[i],
                                "float strict=" + strict + " alias=" + alias + " index=" + i);
                    }
                }
            }
            // No source access is allowed for an empty destination.
            doubleStore(null, null, new double[0]);
            floatStore(null, null, new float[0]);
            doubleStrictStore(null, null, new double[0]);
            floatStrictStore(null, null, new float[0]);
            System.out.println("COPY_SIGN_STORE_BITS_PASSED");
        }

        private static void checkReductions() {
            double[] magnitudes = {
                Double.longBitsToDouble(0x7ff0000000000001L), -0.0,
                Double.longBitsToDouble(1L),
                Double.longBitsToDouble(0xfff8000000000042L), Double.POSITIVE_INFINITY
            };
            double[] signs = {-1.0, 1.0, -0.0, 1.0, -1.0};
            float[] magnitudesF = {
                Float.intBitsToFloat(0x7f800001), -0.0f, Float.intBitsToFloat(1),
                Float.intBitsToFloat(0xffc00042), Float.POSITIVE_INFINITY
            };
            float[] signsF = {-1.0f, 1.0f, -0.0f, 1.0f, -1.0f};
            Asserts.assertEquals(doubleReduce(magnitudes, signs), 0xffd8000000000044L,
                    "double raw-bit reduction");
            Asserts.assertEquals(floatReduce(magnitudesF, signsF), 0xfec00044,
                    "float raw-bit reduction");
            Asserts.assertEquals(doubleReduce(new double[0], null), 0L,
                    "empty double reduction");
            Asserts.assertEquals(floatReduce(new float[0], null), 0,
                    "empty float reduction");
        }

        public static long doubleReduce(double[] m, double[] s) {
            long result = 0;
            for (int i = 0; i < m.length; i++) {
                result += Double.doubleToRawLongBits(Math.copySign(m[i], s[i]));
            }
            return result;
        }

        public static int floatReduce(float[] m, float[] s) {
            int result = 0;
            for (int i = 0; i < m.length; i++) {
                result += Float.floatToRawIntBits(Math.copySign(m[i], s[i]));
            }
            return result;
        }

        public static void doubleStore(double[] m, double[] s, double[] out) {
            for (int i = 0; i < out.length; i++) {
                out[i] = Math.copySign(m[i], s[i]);
            }
        }

        public static void floatStore(float[] m, float[] s, float[] out) {
            for (int i = 0; i < out.length; i++) {
                out[i] = Math.copySign(m[i], s[i]);
            }
        }

        public static void doubleStrictStore(double[] m, double[] s, double[] out) {
            for (int i = 0; i < out.length; i++) {
                out[i] = StrictMath.copySign(m[i], s[i]);
            }
        }

        public static void floatStrictStore(float[] m, float[] s, float[] out) {
            for (int i = 0; i < out.length; i++) {
                out[i] = StrictMath.copySign(m[i], s[i]);
            }
        }
    }
}
