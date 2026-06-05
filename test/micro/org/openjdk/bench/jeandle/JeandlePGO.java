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
package org.openjdk.bench.jeandle;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Profile-Guided Optimization benchmark for the Jeandle JIT.
 *
 * A/B is driven by the {@code -XX:JeandleUseProfile} flag (default on). Run the
 * benchmark JAR twice and diff:
 *   ...benchmarks.jar JeandlePGO -jvmArgsAppend "-XX:+UseJeandleCompiler -XX:+JeandleUseProfile"
 *   ...benchmarks.jar JeandlePGO -jvmArgsAppend "-XX:+UseJeandleCompiler -XX:-JeandleUseProfile"
 *
 * What each method targets:
 *   biasedBranch     - branch_weights (block layout). Expected delta: small;
 *                      modern branch predictors already handle a biased branch, so
 *                      the win is mostly icache/codegen density.
 *   neverTakenGuard  - unstable-if pruning. A never-taken cold path that REJOINS
 *                      the loop; pruning removes the phi merge / cold-path constraint
 *                      on the hot accumulator. Expected delta: small.
 *   monomorphicCall  - FORWARD-LOOKING (profile-guided devirtualization, not yet implemented).
 *                      A truly virtual call (two impls loaded) whose receiver is
 *                      monomorphic in profile. Today it is a full vtable dispatch
 *                      under both flag settings; this records the baseline so the
 *                      the guarded-direct-call + inlining win is measurable later.
 *                      THIS is where the dramatic PGO win will show.
 *
 * Honest note: branch weights and unstable-if prune alone are foundational and roughly perf-neutral
 * here. This harness is built to (a) rigorously confirm that and (b) be the standing
 * benchmark for the devirtualization/inlining gains.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 8, time = 1)
@Fork(value = 3, jvmArgs = {"-XX:+UseJeandleCompiler"})
public class JeandlePGO {

    static final int SIZE = 8192;

    int[] data;
    Shape[] shapes;

    @Setup
    public void setup() {
        Random r = new Random(42);
        data = new int[SIZE];
        for (int i = 0; i < SIZE; i++) {
            data[i] = r.nextInt(1_000_000); // always >= 0
        }
        // Load BOTH Shape impls so the area() call is NOT CHA-monomorphic (can't be
        // statically bound); only profile-based devirtualization can specialize it.
        Shape keepSquareLoaded = new Square();
        if (keepSquareLoaded.area(1) == Integer.MIN_VALUE) {
            throw new AssertionError(); // never; just forces Square to be loaded
        }
        shapes = new Shape[SIZE];
        for (int i = 0; i < SIZE; i++) {
            shapes[i] = new Circle(); // receiver is monomorphic (Circle) in profile
        }
    }

    // --- branch weights: heavily-biased conditional branch in a hot loop ---
    @Benchmark
    public int biasedBranch() {
        int[] a = data;
        int s = 0;
        for (int i = 0; i < a.length; i++) {
            int v = a[i];
            if ((v & 15) != 0) {                 // ~15/16 taken
                s += v * 31 + (v >>> 3) - (v & 7);
            } else {
                s += (v ^ 0x5a5a5a5a) + 1;
            }
        }
        return s;
    }

    // --- unstable-if prune: never-taken cold path (with a rejoining call) pruned to uncommon_trap ---
    @Benchmark
    public int neverTakenGuard() {
        int[] a = data;
        int s = 0;
        for (int i = 0; i < a.length; i++) {
            int v = a[i];
            if (v < 0) {                         // never taken in profile
                s += slowPath(v);                // non-inlinable call; rejoins the loop
            }
            s += v * 7 + (v >>> 2);
        }
        return s;
    }

    // --- forward-looking: monomorphic virtual call (devirt target, baseline today) ---
    @Benchmark
    public int monomorphicCall() {
        Shape[] sh = shapes;
        int s = 0;
        for (int i = 0; i < sh.length; i++) {
            s += sh[i].area(i);                  // virtual; profile-monomorphic (Circle)
        }
        return s;
    }

    private static int slowPath(int v) {
        // Deliberately not trivially inlinable / has side-effect-ish shape.
        int x = v;
        for (int k = 0; k < 3; k++) x = x * 1234567 + 89;
        return x;
    }

    abstract static class Shape {
        abstract int area(int x);
    }

    static final class Circle extends Shape {
        int area(int x) { return x * 3 + 1; }
    }

    static final class Square extends Shape {
        int area(int x) { return x * x + 2; }
    }
}
