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

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
public class JeandleNumericFolding {
    static final int SIZE = 512;
    int[] ints = new int[SIZE];
    long[] longs = new long[SIZE];
    int[] intMasks = new int[SIZE];
    long[] longMasks = new long[SIZE];
    float[] floats = new float[SIZE];
    double[] doubles = new double[SIZE];

    @Setup
    public void setup() {
        JeandleBenchmarkSupport.requireJavaBlackhole();
        Random random = new Random(662131);
        for (int i = 0; i < SIZE; i++) {
            ints[i] = random.nextInt();
            longs[i] = random.nextLong();
            intMasks[i] = random.nextInt();
            longMasks[i] = random.nextLong();
            floats[i] = Float.intBitsToFloat(random.nextInt());
            doubles[i] = Double.longBitsToDouble(random.nextLong());
        }
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public int roundAlignedFloat() {
        int result = 0;
        for (int i = 0; i < SIZE; i++) {
            result += Math.round((float) (ints[i] << 8));
        }
        return result;
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public long roundAlignedDouble() {
        long result = 0;
        for (int i = 0; i < SIZE; i++) {
            result += Math.round((double) (longs[i] & ~1023L));
        }
        return result;
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public int roundLossyFloat() {
        int result = 0;
        for (int i = 0; i < SIZE; i++) {
            result += Math.round((float) ints[i]);
        }
        return result;
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public long roundLossyDouble() {
        long result = 0;
        for (int i = 0; i < SIZE; i++) {
            result += Math.round((double) longs[i]);
        }
        return result;
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public int compressZeroInt() {
        int result = 0;
        for (int i = 0; i < SIZE; i++) {
            result += Integer.compress(ints[i], intMasks[i]) == 0 ? 1 : 0;
        }
        return result;
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public int compressZeroLong() {
        int result = 0;
        for (int i = 0; i < SIZE; i++) {
            result += Long.compress(longs[i], longMasks[i]) == 0 ? 1 : 0;
        }
        return result;
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public int maskedZeroInt() {
        int result = 0;
        for (int i = 0; i < SIZE; i++) {
            result += (ints[i] & intMasks[i]) == 0 ? 1 : 0;
        }
        return result;
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public int maskedZeroLong() {
        int result = 0;
        for (int i = 0; i < SIZE; i++) {
            result += (longs[i] & longMasks[i]) == 0 ? 1 : 0;
        }
        return result;
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public int signumThresholdFloat() {
        int result = 0;
        for (int i = 0; i < SIZE; i++) {
            result += Math.signum(floats[i]) > 0.5f ? 1 : 0;
        }
        return result;
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public int signumThresholdDouble() {
        int result = 0;
        for (int i = 0; i < SIZE; i++) {
            result += Math.signum(doubles[i]) > 0.5 ? 1 : 0;
        }
        return result;
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public int positiveFloat() {
        int result = 0;
        for (int i = 0; i < SIZE; i++) {
            result += floats[i] > 0.0f ? 1 : 0;
        }
        return result;
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public int positiveDouble() {
        int result = 0;
        for (int i = 0; i < SIZE; i++) {
            result += doubles[i] > 0.0 ? 1 : 0;
        }
        return result;
    }
}
