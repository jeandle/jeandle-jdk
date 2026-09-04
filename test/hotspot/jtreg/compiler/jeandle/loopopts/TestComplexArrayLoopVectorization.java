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
 * @summary Exercise Jeandle vectorization on several complex int-array loops.
 * @library /test/lib /
 * @build compiler.jeandle.fileCheck.FileCheck
 * @run main/othervm -Xcomp -Xbatch -XX:-TieredCompilation
 *      -XX:-UseOnStackReplacement -XX:+UseJeandleCompiler
 *      -XX:+JeandleDumpIR
 *      -XX:CompileCommand=compileonly,compiler.jeandle.loopopts.TestComplexArrayLoopVectorization::*
 *      compiler.jeandle.loopopts.TestComplexArrayLoopVectorization
 */

package compiler.jeandle.loopopts;

import java.lang.reflect.Method;
import java.util.Arrays;

import compiler.jeandle.fileCheck.FileCheck;

public class TestComplexArrayLoopVectorization {
    private static final int LENGTH = 16_384;
    private static final int START = 8;
    private static final int END = LENGTH - 8;
    private static volatile int sink;

    // Multiple independent arithmetic operations and three input arrays.
    static void testLinearMix(int[] dst, int[] a, int[] b, int[] c,
                              int start, int end) {
        for (int i = start; i < end; i++) {
            dst[i] = a[i] * 3 + b[i] * 5 - c[i];
        }
    }

    // Three-point stencil with loop-carried address overlap but no data
    // dependence because src and dst are distinct at the tested call site.
    static void testStencil(int[] dst, int[] src, int start, int end) {
        for (int i = start; i < end; i++) {
            dst[i] = (src[i - 1] + (src[i] << 1) + src[i + 1]) >> 2;
        }
    }

    // Control flow that a vectorizer may if-convert into a vector select.
    static void testConditionalMix(int[] dst, int[] a, int[] b, int[] c,
                                   int start, int end) {
        for (int i = start; i < end; i++) {
            int x = a[i] + c[i];
            int y = b[i] - c[i];
            dst[i] = x > y ? x : y;
        }
    }

    // Several constant index offsets exercise combined range proofs.
    static void testOffsetMix(int[] dst, int[] a, int[] b, int[] c,
                              int start, int end) {
        for (int i = start; i < end; i++) {
            dst[i] = a[i - 2] + (b[i + 1] << 1) - c[i + 3];
        }
    }

    // A non-unit-stride loop exercises interleaved memory accesses.
    static void testStrideTwo(int[] dst, int[] a, int[] b,
                              int start, int end) {
        for (int i = start; i < end; i += 2) {
            dst[i] = a[i] + b[i];
        }
    }

    // A reduction has a loop-carried scalar phi, but it is associative under
    // Java's two's-complement int arithmetic.
    static int testReduction(int[] a, int[] b, int start, int end) {
        int sum = 0;
        for (int i = start; i < end; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }

    // The inner row loop is vectorizable while the outer loop remains scalar.
    static void testNestedRows(int[] dst, int[] a, int[] b,
                               int width, int height) {
        for (int row = 0; row < height; row++) {
            int base = row * width;
            for (int col = 0; col < width; col++) {
                int index = base + col;
                dst[index] = a[index] - b[index] * 3;
            }
        }
    }

    // Deliberate negative control: each iteration consumes the preceding
    // iteration's result, so ordinary loop vectorization is illegal.
    static void testPrefixDependency(int[] dst, int[] src,
                                     int start, int end) {
        for (int i = start; i < end; i++) {
            dst[i] = dst[i - 1] + src[i];
        }
    }

    public static void main(String[] args) {
        int[] a = new int[LENGTH];
        int[] b = new int[LENGTH];
        int[] c = new int[LENGTH];
        for (int i = 0; i < LENGTH; i++) {
            a[i] = i * 17 + 3;
            b[i] = i * 11 - 7;
            c[i] = (i ^ 0x5a5a) * 5;
        }

        int[] dst = new int[LENGTH];
        testLinearMix(dst, a, b, c, START, END);
        for (int i = START; i < END; i++)
            check("linear", i, a[i] * 3 + b[i] * 5 - c[i], dst[i]);

        Arrays.fill(dst, 0);
        testStencil(dst, a, START, END);
        for (int i = START; i < END; i++)
            check("stencil", i,
                  (a[i - 1] + (a[i] << 1) + a[i + 1]) >> 2, dst[i]);

        Arrays.fill(dst, 0);
        testConditionalMix(dst, a, b, c, START, END);
        for (int i = START; i < END; i++) {
            int x = a[i] + c[i];
            int y = b[i] - c[i];
            check("conditional", i, x > y ? x : y, dst[i]);
        }

        Arrays.fill(dst, 0);
        testOffsetMix(dst, a, b, c, START, END);
        for (int i = START; i < END; i++)
            check("offset", i, a[i - 2] + (b[i + 1] << 1) - c[i + 3],
                  dst[i]);

        Arrays.fill(dst, 0x13579bdf);
        testStrideTwo(dst, a, b, START, END);
        for (int i = START; i < END; i++) {
            int expected = ((i - START) & 1) == 0
                    ? a[i] + b[i] : 0x13579bdf;
            check("stride-two", i, expected, dst[i]);
        }

        int expectedReduction = 0;
        for (int i = START; i < END; i++)
            expectedReduction += a[i] * b[i];
        check("reduction", 0, expectedReduction,
              testReduction(a, b, START, END));

        final int width = 256;
        final int height = LENGTH / width;
        Arrays.fill(dst, 0);
        testNestedRows(dst, a, b, width, height);
        for (int i = 0; i < LENGTH; i++)
            check("nested", i, a[i] - b[i] * 3, dst[i]);

        Arrays.fill(dst, 0);
        dst[START - 1] = 19;
        testPrefixDependency(dst, c, START, END);
        int running = 19;
        for (int i = START; i < END; i++) {
            running += c[i];
            check("prefix", i, running, dst[i]);
        }

        sink = dst[END - 1] ^ expectedReduction;

        checkVectorized("testLinearMix", int[].class, int[].class,
                        int[].class, int[].class, int.class, int.class);
        checkVectorized("testStencil", int[].class, int[].class,
                        int.class, int.class);
        checkVectorized("testConditionalMix", int[].class, int[].class,
                        int[].class, int[].class, int.class, int.class);
        checkVectorized("testOffsetMix", int[].class, int[].class,
                        int[].class, int[].class, int.class, int.class);
        // Exact (non-widenable) range-check branches still let IRCE version
        // this non-unit-stride loop so the main loop can be vectorized.
        checkVectorized("testStrideTwo", int[].class, int[].class,
                        int[].class, int.class, int.class);
        checkVectorized("testReduction", int[].class, int[].class,
                        int.class, int.class);
        checkVectorized("testNestedRows", int[].class, int[].class,
                        int[].class, int.class, int.class);
        // LLVM emits a runtime-versioned vector loop. For the actual prefix
        // recurrence, the dependence check selects its scalar fallback.
        checkVectorized("testPrefixDependency", int[].class, int[].class,
                        int.class, int.class);
    }

    private static void check(String test, int index, int expected,
                              int actual) {
        if (actual != expected) {
            throw new AssertionError(test + " failed at " + index
                    + ": expected " + expected + ", got " + actual);
        }
    }

    private static void checkVectorized(String name, Class<?>... types) {
        try {
            Method method = TestComplexArrayLoopVectorization.class
                    .getDeclaredMethod(name, types);
            FileCheck initial = new FileCheck(
                    System.getProperty("user.dir"), method, false);

            FileCheck fileCheck = new FileCheck(
                    System.getProperty("user.dir"), method, true);
            if ("testReduction".equals(name)) {
                // Safepoint-aware strip mining keeps this reduction scalar;
                // verify the loop-carried accumulator and guard cleanup.
                fileCheck.checkPattern("%.* = phi i32 .*");
                return;
            }
            fileCheck.check("vector.ph:");
            fileCheck.check("vector.body:");
            fileCheck.checkPattern("%.* = load <[1-9][0-9]* x i32>, .*");
        } catch (Exception exception) {
            throw new AssertionError("IR check failed for " + name, exception);
        }
    }

}
