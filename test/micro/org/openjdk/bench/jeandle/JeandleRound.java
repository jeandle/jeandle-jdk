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
package org.openjdk.bench.jeandle;

import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/**
 * Rounding in reductions and array conversion, without a Blackhole call per
 * element. Run with the same FULL_DONTINLINE Java Blackhole harness setting as
 * JeandleMathMisc. Inputs are reproducible and are not compile-time constants.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
public class JeandleRound {
    static final int SIZE = 512;

    // Ordinary coordinates/prices, tiny values that round to zero, and a
    // stress distribution sampling arbitrary IEEE bit patterns.
    @Param({"ordinary", "tiny", "raw"})
    public String distribution;

    private float[] floats;
    private double[] doubles;
    private int[] ints;
    private long[] longs;

    @Setup
    public void setup() {
        JeandleBenchmarkSupport.requireJavaBlackhole();
        floats = new float[SIZE];
        doubles = new double[SIZE];
        ints = new int[SIZE];
        longs = new long[SIZE];
        Random random = new Random(662);
        for (int i = 0; i < SIZE; i++) {
            switch (distribution) {
                case "ordinary":
                    floats[i] = (random.nextFloat() - 0.5f) * 2000.0f;
                    doubles[i] = (random.nextDouble() - 0.5) * 2000.0;
                    break;
                case "tiny":
                    floats[i] = Math.scalb(random.nextFloat() - 0.5f, -40);
                    doubles[i] = Math.scalb(random.nextDouble() - 0.5, -80);
                    break;
                case "raw":
                    floats[i] = Float.intBitsToFloat(random.nextInt());
                    doubles[i] = Double.longBitsToDouble(random.nextLong());
                    break;
                default: throw new IllegalArgumentException(distribution);
            }
        }
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public int sumF() {
        int result = 0;
        for (int i = 0; i < SIZE; i++) result += Math.round(floats[i]);
        return result;
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public long sumD() {
        long result = 0;
        for (int i = 0; i < SIZE; i++) result += Math.round(doubles[i]);
        return result;
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public int[] storeF() {
        for (int i = 0; i < SIZE; i++) ints[i] = Math.round(floats[i]);
        return ints;
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public long[] storeD() {
        for (int i = 0; i < SIZE; i++) longs[i] = Math.round(doubles[i]);
        return longs;
    }
}
