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

import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Integer/Long compress and expand under Jeandle, compared against C2
 * ({@code -XX:-UseJeandleCompiler} on the same build). Each invocation walks
 * pre-filled value and mask arrays so neither argument is a compile-time
 * constant; results are consumed through a Blackhole. Masks deliberately
 * spread the popcount across the full width, because the pure-Java
 * fallback cost scales with the number of set mask bits while a bit-manip
 * intrinsic is popcount-independent.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class JeandleBitShuffle {
    static final int SIZE = 512;

    private int[] intValues;
    private int[] intMasks;
    private long[] longValues;
    private long[] longMasks;

    @Setup
    public void setup() {
        intValues = new int[SIZE];
        intMasks = new int[SIZE];
        longValues = new long[SIZE];
        longMasks = new long[SIZE];
        for (int i = 0; i < SIZE; i++) {
            int hash = i * 0x9E3779B9;
            intValues[i] = hash ^ (hash >>> 16);
            longValues[i] = ((long) hash << 32) | (hash * 0x85EBCA6BL & 0xFFFFFFFFL);
            // Even lanes take masks stepping through every even popcount
            // from 0 to the full width (0/32 and 0/64 both occur); odd lanes
            // add structured/peppered patterns whose popcounts cluster near
            // the middle (about 14-20 for int, 27-40 for long) instead of
            // covering every count.
            if ((i & 1) == 0) {
                // i % 32 == 0 alternates between the empty and full mask so
                // popcounts 0 and 32 both occur; other multiples of two step
                // through every popcount in between.
                if (i % 32 == 0) {
                    intMasks[i] = (i % 64 == 0) ? 0 : -1;
                } else {
                    intMasks[i] = 0xFFFFFFFF >>> (i % 32);
                }
                if (i % 64 == 0) {
                    longMasks[i] = (i % 128 == 0) ? 0L : -1L;
                } else {
                    longMasks[i] = 0xFFFFFFFFFFFFFFFFL >>> (i % 64);
                }
            } else {
                int pattern = i % 4;
                intMasks[i] = switch (pattern) {
                    case 0 -> 0x55555555;
                    case 1 -> 0xAAAAAAAA;
                    case 2 -> 0x0F0F0F0F;
                    default -> 0x00FF00FF;
                } ^ (hash & 0x01010101);
                longMasks[i] = ((long) intMasks[i] << 32)
                        | (intMasks[i] * 0x9E3779B9 & 0xFFFFFFFFL);
            }
        }
        check(Integer.compress(0x12345678, -1) == 0x12345678, "int compress full mask");
        check(Integer.compress(0x12345678, 0) == 0, "int compress empty mask");
        check(Integer.expand(0x12345678, -1) == 0x12345678, "int expand full mask");
        check(Integer.expand(0x12345678, 0) == 0, "int expand empty mask");
        check(Long.compress(0x123456789ABCDEFL, -1L) == 0x123456789ABCDEFL,
                "long compress full mask");
        check(Long.expand(0x123456789ABCDEFL, -1L) == 0x123456789ABCDEFL,
                "long expand full mask");
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public void compressI(Blackhole bh) {
        for (int i = 0; i < SIZE; i++) {
            bh.consume(Integer.compress(intValues[i], intMasks[i]));
        }
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public void expandI(Blackhole bh) {
        for (int i = 0; i < SIZE; i++) {
            bh.consume(Integer.expand(intValues[i], intMasks[i]));
        }
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public void compressL(Blackhole bh) {
        for (int i = 0; i < SIZE; i++) {
            bh.consume(Long.compress(longValues[i], longMasks[i]));
        }
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public void expandL(Blackhole bh) {
        for (int i = 0; i < SIZE; i++) {
            bh.consume(Long.expand(longValues[i], longMasks[i]));
        }
    }

    private static void check(boolean condition, String what) {
        if (!condition) {
            throw new IllegalStateException("Unexpected " + what + " result");
        }
    }
}
