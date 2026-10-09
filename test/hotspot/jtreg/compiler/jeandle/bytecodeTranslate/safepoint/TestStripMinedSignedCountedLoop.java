/*
 * Copyright (c) 2026, the Jeandle-JDK Authors. All Rights Reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1300 USA.
 *
 */

/*
 * @test
 * @summary Functional correctness of signed counted loops under safepoint strip
 *          mining — a regression guard for the per-batch inner-limit clamp.
 *          Each loop has a runtime bound larger than the strip-mining chunk
 *          budget, so it is wrapped in an outer batch loop whose inner limit is
 *          computed by the clamp; a miscompile there (e.g. a wrong overflow-safe
 *          formulation) diverges from the closed-form reference sum.
 * @run main/othervm -Xcomp -XX:-TieredCompilation -XX:+UseJeandleCompiler -XX:CompileCommand=compileonly,TestStripMinedSignedCountedLoop::sumIncreasing -XX:CompileCommand=compileonly,TestStripMinedSignedCountedLoop::sumDecreasing -XX:CompileCommand=compileonly,TestStripMinedSignedCountedLoop::sumStride TestStripMinedSignedCountedLoop
 */

public class TestStripMinedSignedCountedLoop {

    // N is set at runtime and passed as a parameter, so the compiled method
    // sees an opaque bound (not a compile-time constant) and strip mining fires.
    static final int N = 100000; // > chunk budget (default 1000)

    // Signed increasing counted loop with a long reduction.
    static long sumIncreasing(int n) {
        long s = 0;
        for (int i = 0; i < n; i++) {
            s += i;
        }
        return s;
    }

    // Signed decreasing counted loop.
    static long sumDecreasing(int n) {
        long s = 0;
        for (int i = n - 1; i >= 0; i--) {
            s += i;
        }
        return s;
    }

    // Signed counted loop with a non-unit stride.
    static long sumStride(int n) {
        long s = 0;
        for (int i = 0; i < n; i += 3) {
            s += i;
        }
        return s;
    }

    public static void main(String[] args) {
        // Warm up to ensure the compiled (strip-mined) bodies execute.
        sumIncreasing(N);
        sumDecreasing(N);
        sumStride(N);

        long inc = sumIncreasing(N);
        long dec = sumDecreasing(N);
        long str = sumStride(N);

        // Closed-form references (long arithmetic, no overflow for these N).
        long refInc = (long) N * (N - 1) / 2;          // 0 + 1 + ... + (N-1)
        long refDec = (long) N * (N - 1) / 2;          // (N-1) + ... + 1 + 0
        int terms = (N + 2) / 3;                        // count of 0,3,6,... < N
        long refStr = 3L * (terms - 1) * terms / 2;     // 3 * (0+1+...+(terms-1))

        check("increasing", inc, refInc);
        check("decreasing", dec, refDec);
        check("stride3", str, refStr);
        System.out.println("OK");
    }

    private static void check(String name, long got, long expected) {
        if (got != expected) {
            throw new RuntimeException(
                name + ": got " + got + ", expected " + expected);
        }
    }
}
