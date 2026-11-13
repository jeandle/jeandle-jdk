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

/** Numeric consumers and controls with matching input streams. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
public class JeandleNumericConsumers {
    static final int SIZE = 256;
    int[] ints = new int[SIZE];
    long[] longs = new long[SIZE];
    double[] doubles = new double[SIZE];
    float[] floats = new float[SIZE];

    @Setup
    public void setup() {
        JeandleBenchmarkSupport.requireJavaBlackhole();
        Random r = new Random(20261112);
        for (int i = 0; i < SIZE; i++) {
            ints[i] = r.nextInt();
            longs[i] = r.nextLong();
            doubles[i] = r.nextGaussian() * 100;
            floats[i] = (float) doubles[i];
        }
        doubles[0] = -0.0;
        doubles[1] = Double.NaN;
        doubles[2] = Double.POSITIVE_INFINITY;
        floats[0] = -0.0f;
        floats[1] = Float.NaN;
        floats[2] = Float.NEGATIVE_INFINITY;
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public long roundInt() {
        long result = 0;
        for (int i = 0; i < SIZE; i++) {
            result += Math.round((double) ints[i]);
        }
        return result;
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public int roundNarrow() {
        int result = 0;
        for (int i = 0; i < SIZE; i++) {
            result += Math.round((float) (ints[i] & 65535));
        }
        return result;
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public int roundLossy() {
        int result = 0;
        for (int i = 0; i < SIZE; i++) {
            result += Math.round((float) ints[i]);
        }
        return result;
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public long roundGeneral() {
        long result = 0;
        for (int i = 0; i < SIZE; i++) {
            result += Math.round(doubles[i]);
        }
        return result;
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public int signumPositive() {
        int result = 0;
        for (int i = 0; i < SIZE; i++) {
            result += Math.signum(doubles[i]) > 0 ? 1 : 0;
        }
        return result;
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public int signumIsOneF() {
        int result = 0;
        for (int i = 0; i < SIZE; i++) {
            result += Math.signum(floats[i]) == 1 ? 1 : 0;
        }
        return result;
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public double signumValue() {
        double result = 0;
        for (int i = 0; i < SIZE; i++) {
            result += Math.signum(doubles[i]);
        }
        return result;
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public double signumTwice() {
        double result = 0;
        for (int i = 0; i < SIZE; i++) {
            result += Math.signum(Math.signum(doubles[i]));
        }
        return result;
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public int compressLowI() {
        int result = 0;
        for (int i = 0; i < SIZE; i++) {
            result += Integer.compress(ints[i], -1 >>> ints[(i + 1) & (SIZE - 1)]);
        }
        return result;
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public long expandLowL() {
        long result = 0;
        for (int i = 0; i < SIZE; i++) {
            result += Long.expand(longs[i], (1L << ints[i]) - 1);
        }
        return result;
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public long compressGeneral() {
        long result = 0;
        for (int i = 0; i < SIZE; i++) {
            result += Long.compress(longs[i], longs[(i + 1) & (SIZE - 1)]);
        }
        return result;
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public long rawCopySign() {
        long result = 0;
        for (int i = 0; i < SIZE; i++) {
            result += Double.doubleToRawLongBits(Math.copySign(Double.longBitsToDouble(longs[i]), Double.longBitsToDouble(longs[(i + 1) & (SIZE - 1)])));
        }
        return result;
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public long rawCopySignInteger() {
        long result = 0;
        for (int i = 0; i < SIZE; i++) {
            result += (longs[i] & Long.MAX_VALUE) | (longs[(i + 1) & (SIZE - 1)] & Long.MIN_VALUE);
        }
        return result;
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public double powReciprocal() {
        double result = 0;
        for (int i = 0; i < SIZE; i++) {
            result += Math.pow(doubles[i], -1.0);
        }
        return result;
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public double powHalf() {
        double result = 0;
        for (int i = 0; i < SIZE; i++) {
            result += Math.pow(Math.abs(doubles[i]), 0.5);
        }
        return result;
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public int deadSin() {
        int result = 0;
        for (int i = 0; i < SIZE; i++) {
            Math.sin(doubles[i]); result += ints[i];
        }
        return result;
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public double constantLibm() {
        double result = 0;
        for (int i = 0; i < SIZE; i++) {
            result += Math.cos(0.0) + Math.exp(0.0) + Math.log(1.0);
        }
        return result;
    }

    private static double halfCandidate(double x) {
        return x > 0.0 ? Math.sqrt(x) : x == 0.0 ? 0.0 : Math.pow(x, 0.5);
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public double powHalfCandidate() {
        double result = 0;
        for (int i = 0; i < SIZE; i++) result += halfCandidate(Math.abs(doubles[i]));
        return result;
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public double powHalfZeros() {
        double result = 0;
        for (int i = 0; i < SIZE; i++) {
            double x = (i & 1) == 0 ? 0.0 : Math.abs(doubles[i]);
            result += Math.pow(x, 0.5);
        }
        return result;
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public double powHalfZerosCandidate() {
        double result = 0;
        for (int i = 0; i < SIZE; i++) {
            double x = (i & 1) == 0 ? 0.0 : Math.abs(doubles[i]);
            result += halfCandidate(x);
        }
        return result;
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public long rawCopySignChain() {
        long result = 0;
        for (int i = 0; i < SIZE; i++) {
            result = Double.doubleToRawLongBits(Math.copySign(
                    Double.longBitsToDouble(result + longs[i]),
                    Double.longBitsToDouble(longs[(i + 1) & (SIZE - 1)])));
        }
        return result;
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public long rawCopySignIntegerChain() {
        long result = 0;
        for (int i = 0; i < SIZE; i++) {
            result = ((result + longs[i]) & Long.MAX_VALUE)
                    | (longs[(i + 1) & (SIZE - 1)] & Long.MIN_VALUE);
        }
        return result;
    }
}
