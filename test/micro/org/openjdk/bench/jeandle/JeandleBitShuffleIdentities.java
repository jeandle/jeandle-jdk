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

/** Dynamic masks, Java-masked shift counts and nonmatching-mask controls. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
public class JeandleBitShuffleIdentities {
    static final int SIZE = 512;
    int[] ints, intMasks, shifts;
    long[] longs, longMasks;

    @Setup
    public void setup() {
        JeandleBenchmarkSupport.requireJavaBlackhole();
        Random random = new Random(Long.getLong("review.numeric.seed", 2601109L));
        ints = new int[SIZE];
        intMasks = new int[SIZE];
        shifts = new int[SIZE];
        longs = new long[SIZE];
        longMasks = new long[SIZE];
        int[] boundaries = {0, 1, 31, 32, -1, 63, 64, Integer.MIN_VALUE};
        for (int i = 0; i < SIZE; i++) {
            ints[i] = random.nextInt();
            longs[i] = random.nextLong();
            intMasks[i] = i % 16 == 0 ? 0 : i % 16 == 1 ? -1 : random.nextInt();
            longMasks[i] = i % 16 == 0 ? 0L : i % 16 == 1 ? -1L : random.nextLong();
            shifts[i] = i < boundaries.length ? boundaries[i] : random.nextInt();
        }
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public int expandCompressI() {
        int result = 0;
        for (int i = 0; i < SIZE; i++) result += Integer.expand(Integer.compress(ints[i], intMasks[i]), intMasks[i]);
        return result;
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public long expandCompressL() {
        long result = 0;
        for (int i = 0; i < SIZE; i++) result += Long.expand(Long.compress(longs[i], longMasks[i]), longMasks[i]);
        return result;
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public int compressExpandI() {
        int result = 0;
        for (int i = 0; i < SIZE; i++) result += Integer.compress(Integer.expand(ints[i], intMasks[i]), intMasks[i]);
        return result;
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public long compressExpandL() {
        long result = 0;
        for (int i = 0; i < SIZE; i++) result += Long.compress(Long.expand(longs[i], longMasks[i]), longMasks[i]);
        return result;
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public int compressSingleBitI() {
        int result = 0;
        for (int i = 0; i < SIZE; i++) result += Integer.compress(ints[i], 1 << shifts[i]);
        return result;
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public long compressSingleBitL() {
        long result = 0;
        for (int i = 0; i < SIZE; i++) result += Long.compress(longs[i], 1L << shifts[i]);
        return result;
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public int expandHighMaskI() {
        int result = 0;
        for (int i = 0; i < SIZE; i++) result += Integer.expand(ints[i], -1 << shifts[i]);
        return result;
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public long expandHighMaskL() {
        long result = 0;
        for (int i = 0; i < SIZE; i++) result += Long.expand(longs[i], -1L << shifts[i]);
        return result;
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public int expandAllOnesI() {
        int result = 0;
        for (int i = 0; i < SIZE; i++) result += Integer.expand(-1, intMasks[i]);
        return result;
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public long expandAllOnesL() {
        long result = 0;
        for (int i = 0; i < SIZE; i++) result += Long.expand(-1L, longMasks[i]);
        return result;
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public int expandSingleBitI() {
        int result = 0;
        for (int i = 0; i < SIZE; i++) result += Integer.expand(ints[i], 1 << shifts[i]);
        return result;
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public long expandSingleBitL() {
        long result = 0;
        for (int i = 0; i < SIZE; i++) result += Long.expand(longs[i], 1L << shifts[i]);
        return result;
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public int compressHighMaskI() {
        int result = 0;
        for (int i = 0; i < SIZE; i++) result += Integer.compress(ints[i], -1 << shifts[i]);
        return result;
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public long compressHighMaskL() {
        long result = 0;
        for (int i = 0; i < SIZE; i++) result += Long.compress(longs[i], -1L << shifts[i]);
        return result;
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public int differentMasksI() {
        int result = 0;
        for (int i = 0; i < SIZE; i++) {
            result += Integer.expand(Integer.compress(ints[i], intMasks[i]), intMasks[(i + 1) & (SIZE - 1)]);
        }
        return result;
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public long differentMasksL() {
        long result = 0;
        for (int i = 0; i < SIZE; i++) {
            result += Long.expand(Long.compress(longs[i], longMasks[i]), longMasks[(i + 1) & (SIZE - 1)]);
        }
        return result;
    }
}
