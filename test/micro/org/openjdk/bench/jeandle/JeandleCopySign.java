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
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/** Store loops expose throughput; raw-bit reductions retain every payload bit. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
public class JeandleCopySign {
    static final int SIZE = 512;
    @Param({"normal", "special"})
    public String distribution;
    double[] doubles, doubleSigns, doubleOutput;
    float[] floats, floatSigns, floatOutput;

    @Setup
    public void setup() {
        JeandleBenchmarkSupport.requireJavaBlackhole();
        Random random = new Random(Long.getLong("review.numeric.seed", 2601109L));
        doubles = new double[SIZE];
        doubleSigns = new double[SIZE];
        doubleOutput = new double[SIZE];
        floats = new float[SIZE];
        floatSigns = new float[SIZE];
        floatOutput = new float[SIZE];
        long[] specialD = {0L, Long.MIN_VALUE, 0x7ff0000000000000L, 0xfff0000000000000L,
                1L, 0x8000000000000001L, 0x7ff8000000001234L, 0xfff0000000005678L};
        int[] specialF = {0, Integer.MIN_VALUE, 0x7f800000, 0xff800000,
                1, 0x80000001, 0x7fc01234, 0xff805678};
        for (int i = 0; i < SIZE; i++) {
            if ("normal".equals(distribution)) {
                doubles[i] = (random.nextDouble() - 0.5) * 1e6;
                floats[i] = (random.nextFloat() - 0.5f) * 1e6f;
                doubleSigns[i] = random.nextBoolean() ? 1.0 : -1.0;
                floatSigns[i] = random.nextBoolean() ? 1.0f : -1.0f;
            } else if ("special".equals(distribution)) {
                doubles[i] = Double.longBitsToDouble(specialD[i % specialD.length]);
                floats[i] = Float.intBitsToFloat(specialF[i % specialF.length]);
                doubleSigns[i] = Double.longBitsToDouble(random.nextLong());
                floatSigns[i] = Float.intBitsToFloat(random.nextInt());
            } else {
                throw new IllegalArgumentException(distribution);
            }
        }
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public double[] copySignDStore() {
        for (int i = 0; i < SIZE; i++) {
            doubleOutput[i] = Math.copySign(doubles[i], doubleSigns[i]);
        }
        return doubleOutput;
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public long copySignDReduce() {
        long result = 0;
        for (int i = 0; i < SIZE; i++) {
            result += Double.doubleToRawLongBits(Math.copySign(doubles[i], doubleSigns[i]));
        }
        return result;
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public float[] copySignFStore() {
        for (int i = 0; i < SIZE; i++) {
            floatOutput[i] = Math.copySign(floats[i], floatSigns[i]);
        }
        return floatOutput;
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public int copySignFReduce() {
        int result = 0;
        for (int i = 0; i < SIZE; i++) {
            result += Float.floatToRawIntBits(Math.copySign(floats[i], floatSigns[i]));
        }
        return result;
    }
}
