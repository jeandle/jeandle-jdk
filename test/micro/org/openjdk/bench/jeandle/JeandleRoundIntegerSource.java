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

import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
public class JeandleRoundIntegerSource {
    static final int SIZE = 512;

    @Param({"full", "precision", "saturation", "exact"})
    public String range;

    int[] ints = new int[SIZE];
    long[] longs = new long[SIZE];
    int intMask;
    int intBias;
    long longMask;
    long longBias;

    @Setup
    public void setup() {
        JeandleBenchmarkSupport.requireJavaBlackhole();
        Random random = new Random(662131);
        for (int i = 0; i < SIZE; i++) {
            ints[i] = random.nextInt();
            longs[i] = random.nextLong();
        }
        switch (range) {
            case "full":
                intMask = Integer.MAX_VALUE;
                longMask = Long.MAX_VALUE;
                break;
            case "precision":
                intMask = (1 << 25) - 1;
                longMask = (1L << 54) - 1;
                break;
            case "saturation":
                intMask = 255;
                intBias = Integer.MAX_VALUE & ~intMask;
                longMask = 4095;
                longBias = Long.MAX_VALUE & ~longMask;
                break;
            case "exact":
                intMask = 65535;
                longMask = 65535;
                break;
            default:
                throw new IllegalArgumentException(range);
        }
    }

    private int nextInt(int value, int index) {
        int magnitude = ((Integer.rotateLeft(value, 5) ^ ints[index]) & intMask) | intBias;
        int sign = ints[index] >> 31;
        return (magnitude ^ sign) - sign;
    }

    private long nextLong(long value, int index) {
        long magnitude = ((Long.rotateLeft(value, 5) ^ longs[index]) & longMask) | longBias;
        long sign = longs[index] >> 63;
        return (magnitude ^ sign) - sign;
    }

    // The next input depends on the rounded result, keeping the operation scalar.
    @Benchmark
    @OperationsPerInvocation(SIZE)
    public int roundFloat() {
        int value = 1;
        for (int i = 0; i < SIZE; i++) {
            value = Math.round((float) nextInt(value, i));
        }
        return value;
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public long roundDouble() {
        long value = 1;
        for (int i = 0; i < SIZE; i++) {
            value = Math.round((double) nextLong(value, i));
        }
        return value;
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public int castFloat() {
        int value = 1;
        for (int i = 0; i < SIZE; i++) {
            value = (int) (float) nextInt(value, i);
        }
        return value;
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public long castDouble() {
        long value = 1;
        for (int i = 0; i < SIZE; i++) {
            value = (long) (double) nextLong(value, i);
        }
        return value;
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public int alignedFloat() {
        int value = 1;
        for (int i = 0; i < SIZE; i++) {
            value = Math.round((float) (nextInt(value, i) << 8));
        }
        return value;
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public long alignedDouble() {
        long value = 1;
        for (int i = 0; i < SIZE; i++) {
            value = Math.round((double) (nextLong(value, i) & ~1023L));
        }
        return value;
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public int fractionalFloat() {
        int value = 1;
        for (int i = 0; i < SIZE; i++) {
            value = Math.round((nextInt(value, i) & 65535) * 0.01f);
        }
        return value;
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public long fractionalDouble() {
        long value = 1;
        for (int i = 0; i < SIZE; i++) {
            value = Math.round((nextLong(value, i) & 65535L) * 0.01);
        }
        return value;
    }
}
