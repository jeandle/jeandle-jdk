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

package org.openjdk.bench.java.lang;

import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.CompilerControl;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 8, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(3)
@State(Scope.Thread)
public class Float16Conversions {
    private static final int BATCH_SIZE = 256;

    private float[] finiteFloats;
    private float[] nanFloats;
    private short[] finiteHalves;
    private short[] nanHalves;
    private short[] halfSink;
    private float[] floatSink;

    @Setup
    public void setup() {
        finiteFloats = new float[BATCH_SIZE];
        nanFloats = new float[BATCH_SIZE];
        finiteHalves = new short[BATCH_SIZE];
        nanHalves = new short[BATCH_SIZE];
        halfSink = new short[BATCH_SIZE];
        floatSink = new float[BATCH_SIZE];

        // Vary every input so the conversions cannot be constant-folded or
        // hoisted. Keep finite and NaN measurements in separate benchmarks.
        for (int i = 0; i < BATCH_SIZE; i++) {
            int sign = (i & 1) << 31;
            int exponent = (113 + i % 30) << 23;
            int significand = (i * 0x45d9f3b) & 0x7fffff;
            finiteFloats[i] = Float.intBitsToFloat(sign | exponent | significand);

            int nanPayload = ((i * 0x45d9f3b) & 0x003fffff) | 1;
            nanFloats[i] = Float.intBitsToFloat(sign | 0x7fc00000 | nanPayload);

            int halfSign = (i & 1) << 15;
            int halfExponent = (1 + i % 30) << 10;
            int halfSignificand = (i * 0x1f5) & 0x03ff;
            finiteHalves[i] = (short) (halfSign | halfExponent | halfSignificand);

            int halfNanPayload = 0x0200 | (((i * 0x1f5) & 0x01ff) | 1);
            nanHalves[i] = (short) (halfSign | 0x7c00 | halfNanPayload);
        }
    }

    @Benchmark
    @OperationsPerInvocation(BATCH_SIZE)
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public void floatToFloat16Finite() {
        for (int i = 0; i < BATCH_SIZE; i++) {
            halfSink[i] = Float.floatToFloat16(finiteFloats[i]);
        }
    }

    @Benchmark
    @OperationsPerInvocation(BATCH_SIZE)
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public void floatToFloat16NaN() {
        for (int i = 0; i < BATCH_SIZE; i++) {
            halfSink[i] = Float.floatToFloat16(nanFloats[i]);
        }
    }

    @Benchmark
    @OperationsPerInvocation(BATCH_SIZE)
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public void float16ToFloatFinite() {
        for (int i = 0; i < BATCH_SIZE; i++) {
            floatSink[i] = Float.float16ToFloat(finiteHalves[i]);
        }
    }

    @Benchmark
    @OperationsPerInvocation(BATCH_SIZE)
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public void float16ToFloatNaN() {
        for (int i = 0; i < BATCH_SIZE; i++) {
            floatSink[i] = Float.float16ToFloat(nanHalves[i]);
        }
    }
}
