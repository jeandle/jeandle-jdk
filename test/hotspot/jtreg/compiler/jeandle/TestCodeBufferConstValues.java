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

/**
 * @test
 * @summary Constant payloads keep their exact value after the exact
 *          (plan-driven) const-section allocation is installed.
 *
 *          The methods under test are compiled by Jeandle while an identical
 *          copy of each body is kept out of the compiler via CompileCommand,
 *          so the interpreted copy acts as the reference. A constant that is
 *          emitted at the wrong offset, truncated, or mixed up with a
 *          neighbour changes the numeric result bit-for-bit and fails here.
 *
 *          This is the correctness half of the const layout work. The capacity half
 *          is asserted by TestCodeBufferConstsExactAlloc; this test runs on
 *          any build and is deliberately independent of the instrumentation.
 *
 * @library /test/lib
 * @run main/othervm -XX:+UnlockDiagnosticVMOptions -XX:+UseJeandleCompiler
 *      -Xbatch
 *      -XX:CompileCommand=compileonly,compiler.jeandle.TestCodeBufferConstValues::computeDouble
 *      -XX:CompileCommand=compileonly,compiler.jeandle.TestCodeBufferConstValues::computeFloat
 *      -XX:CompileCommand=compileonly,compiler.jeandle.TestCodeBufferConstValues::computeOopReloc
 *      compiler.jeandle.TestCodeBufferConstValues
 */

package compiler.jeandle;

import jdk.test.lib.Asserts;

public class TestCodeBufferConstValues {

    // --- compiled by Jeandle -------------------------------------------------

    // Dense switch -> jump table (.rodata, align 16) plus 64-bit constants
    // (.rodata.cst8, align 8).
    public static double computeDouble(double val, int op) {
        double res = val;
        switch (op) {
            case 0: res = res * 3.14159265358979323846 + 2.71828182845904523536; break;
            case 1: res = res * 1.41421356237309504880 - 1.73205080756887729352; break;
            case 2: res = res * 0.57721566490153286060 + 0.69314718055994530941; break;
            case 3: res = res * 1.61803398874989484820 - 0.30102999566398119521; break;
            case 4: res = res * 2.30258509299404568401 + 1.20205690315959428539; break;
            case 5: res = res * 0.91596559417721901504 - 0.78539816339744830961; break;
            case 6: res = res * 0.26179938779914943653 + 0.52359877559829887307; break;
            default: res = res * 1.00000000000000000000; break;
        }
        return res;
    }

    // 32-bit constants only (.rodata.cst4, align 4).
    public static float computeFloat(float val, int op) {
        float res = val;
        switch (op) {
            case 0: res = res * 1.25f + 0.75f; break;
            case 1: res = res * 2.50f - 1.25f; break;
            case 2: res = res * 3.75f + 0.50f; break;
            case 3: res = res * 4.125f - 0.25f; break;
            case 4: res = res * 5.625f + 1.125f; break;
            default: res = res * 0.50f; break;
        }
        return res;
    }

    // String constants live in the const section behind an oop relocation.
    public static int computeOopReloc(int mode) {
        String s = (mode % 2 == 0) ? "ConstAlpha_Jeandle_RISCV64"
                                   : "ConstBeta_CodeBuffer_Layout";
        return s.length();
    }

    // --- interpreted reference ----------------------------------------------

    public static double computeDoubleRef(double val, int op) {
        double res = val;
        switch (op) {
            case 0: res = res * 3.14159265358979323846 + 2.71828182845904523536; break;
            case 1: res = res * 1.41421356237309504880 - 1.73205080756887729352; break;
            case 2: res = res * 0.57721566490153286060 + 0.69314718055994530941; break;
            case 3: res = res * 1.61803398874989484820 - 0.30102999566398119521; break;
            case 4: res = res * 2.30258509299404568401 + 1.20205690315959428539; break;
            case 5: res = res * 0.91596559417721901504 - 0.78539816339744830961; break;
            case 6: res = res * 0.26179938779914943653 + 0.52359877559829887307; break;
            default: res = res * 1.00000000000000000000; break;
        }
        return res;
    }

    public static float computeFloatRef(float val, int op) {
        float res = val;
        switch (op) {
            case 0: res = res * 1.25f + 0.75f; break;
            case 1: res = res * 2.50f - 1.25f; break;
            case 2: res = res * 3.75f + 0.50f; break;
            case 3: res = res * 4.125f - 0.25f; break;
            case 4: res = res * 5.625f + 1.125f; break;
            default: res = res * 0.50f; break;
        }
        return res;
    }

    public static int computeOopRelocRef(int mode) {
        String s = (mode % 2 == 0) ? "ConstAlpha_Jeandle_RISCV64"
                                   : "ConstBeta_CodeBuffer_Layout";
        return s.length();
    }

    public static void main(String[] args) {
        // Warm up so the compiled entries are actually installed.
        double dsum = 0.0;
        float fsum = 0.0f;
        int oopSum = 0;
        for (int i = 0; i < 40000; i++) {
            dsum += computeDouble(i * 0.001, i % 8);
            fsum += computeFloat(i * 0.01f, i % 6);
            oopSum += computeOopReloc(i);
        }

        // Bit-for-bit comparison against the interpreted copy.
        for (int op = 0; op < 8; op++) {
            for (int i = 0; i < 64; i++) {
                double v = i * 0.001 - 3.25;
                long got = Double.doubleToLongBits(computeDouble(v, op));
                long want = Double.doubleToLongBits(computeDoubleRef(v, op));
                Asserts.assertEQ(got, want,
                        "double const mismatch: op=" + op + " v=" + v
                                + " got=" + Double.longBitsToDouble(got)
                                + " want=" + Double.longBitsToDouble(want));
            }
        }

        for (int op = 0; op < 6; op++) {
            for (int i = 0; i < 64; i++) {
                float v = (float) (i * 0.01 - 0.5);
                int got = Float.floatToIntBits(computeFloat(v, op));
                int want = Float.floatToIntBits(computeFloatRef(v, op));
                Asserts.assertEQ(got, want,
                        "float const mismatch: op=" + op + " v=" + v
                                + " got=" + Float.intBitsToFloat(got)
                                + " want=" + Float.intBitsToFloat(want));
            }
        }

        for (int mode = 0; mode < 8; mode++) {
            Asserts.assertEQ(computeOopReloc(mode), computeOopRelocRef(mode),
                    "oop-relocated string const mismatch: mode=" + mode);
        }

        // The warmed-up run must have produced real work.
        Asserts.assertNE(dsum, 0.0, "double warm-up produced no value");
        Asserts.assertNE(fsum, 0.0f, "float warm-up produced no value");
        Asserts.assertNE(oopSum, 0, "oop warm-up produced no value");
    }
}
