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
 * Math.copySign, Math.signum and Math.round under Jeandle, compared against
 * C2 ({@code -XX:-UseJeandleCompiler} on the same build). Each invocation
 * walks a pre-filled array so arguments are never compile-time constants;
 * every result is consumed through a Java Blackhole (see README.md in this
 * directory for the required harness options). Two-argument methods use two
 * distinct input arrays so both arguments vary per operation. Values mix
 * magnitudes, signs, zeroes, NaN and rounding ties so no single special case
 * dominates.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class JeandleMathMisc {
    static final int SIZE = 512;

    private double[] doubleMagnitudes;
    private double[] doubleSigns;
    private double[] signumDoubles;
    private float[] floatMagnitudes;
    private float[] floatSigns;
    private float[] signumFloats;
    private float[] roundFloats;
    private double[] roundDoubles;

    @Setup
    public void setup() {
        JeandleBenchmarkSupport.requireJavaBlackhole();
        doubleMagnitudes = new double[SIZE];
        doubleSigns = new double[SIZE];
        signumDoubles = new double[SIZE];
        floatMagnitudes = new float[SIZE];
        floatSigns = new float[SIZE];
        signumFloats = new float[SIZE];
        roundFloats = new float[SIZE];
        roundDoubles = new double[SIZE];
        for (int i = 0; i < SIZE; i++) {
            int lane = i & 7;
            double magnitude = (i + 1) * (1.0 / 64.0);
            double signed = (lane < 4) ? magnitude : -magnitude;
            doubleSigns[i] = (lane == 1 || lane == 5) ? -0.0
                    : (lane == 3) ? 0.0
                    : (lane == 6) ? Double.NaN
                    : -signed;
            switch (lane) {
                case 0 -> doubleMagnitudes[i] = magnitude;
                case 1 -> doubleMagnitudes[i] = -magnitude;
                case 2 -> doubleMagnitudes[i] = magnitude * 1e6;
                case 3 -> doubleMagnitudes[i] = magnitude * 1e-6;
                case 4 -> doubleMagnitudes[i] = Double.NaN;
                case 5 -> doubleMagnitudes[i] = signed;
                case 6 -> doubleMagnitudes[i] = Double.longBitsToDouble(
                        0x0010000000000000L + i);
                default -> doubleMagnitudes[i] = Double.longBitsToDouble(
                        (long) (i % 2046 + 1) << 52);
            }
            signumDoubles[i] = switch (lane) {
                case 0 -> signed;
                case 1 -> 0.0;
                case 2 -> -0.0;
                case 3 -> Double.NaN;
                case 4 -> Double.longBitsToDouble((long) (i % 2046 + 1) << 52);
                case 5 -> Double.longBitsToDouble(0x0010000000000000L + i);
                case 6 -> Double.POSITIVE_INFINITY;
                default -> Double.NEGATIVE_INFINITY;
            };
            float floatSigned = (lane < 4) ? (float) magnitude : -(float) magnitude;
            floatSigns[i] = (lane == 1 || lane == 5) ? -0.0f
                    : (lane == 3) ? 0.0f
                    : (lane == 6) ? Float.NaN
                    : -floatSigned;
            floatMagnitudes[i] = switch (lane) {
                case 0 -> floatSigned;
                case 1 -> -floatSigned;
                case 2 -> floatSigned * 1e6f;
                case 3 -> floatSigned * 1e-6f;
                case 4 -> Float.NaN;
                case 5 -> floatSigned;
                case 6 -> Float.intBitsToFloat(0x00800000 + i);
                default -> Float.intBitsToFloat((i % 254 + 1) << 23);
            };
            signumFloats[i] = switch (lane) {
                case 0 -> floatSigned;
                case 1 -> 0.0f;
                case 2 -> -0.0f;
                case 3 -> Float.NaN;
                case 4 -> Float.intBitsToFloat((i % 254 + 1) << 23);
                case 5 -> Float.intBitsToFloat(0x00800000 + i);
                case 6 -> Float.POSITIVE_INFINITY;
                default -> Float.NEGATIVE_INFINITY;
            };
            roundFloats[i] = switch (lane) {
                case 0 -> i + 0.5f;
                case 1 -> i - 0.5f;
                case 2 -> i + 0.4999f;
                case 3 -> -i - 0.5f;
                case 4 -> i * 1e6f;
                case 5 -> i * 1e-6f;
                case 6 -> (i & 1) == 0 ? Float.NaN : 0.0f;
                default -> (i & 1) == 0 ? -0.0f : Float.intBitsToFloat(
                        (i % 254 + 1) << 23);
            };
            roundDoubles[i] = switch (lane) {
                case 0 -> i + 0.5;
                case 1 -> i - 0.5;
                case 2 -> i + 0.49999999999999994;
                case 3 -> -i - 0.5;
                case 4 -> i * 1e9;
                case 5 -> i * 1e-9;
                case 6 -> (i & 1) == 0 ? Double.NaN : 0.0;
                default -> (i & 1) == 0 ? -0.0 : Double.longBitsToDouble(
                        (long) (i % 2046 + 1) << 52);
            };
        }
        check(Math.copySign(3.5, -1.0) == -3.5, "copySign(double, double)");
        check(Math.copySign(-3.5f, 2.0f) == 3.5f, "copySign(float, float)");
        check(Math.signum(-42.0) == -1.0 && Math.signum(0.25f) == 1.0f,
                "signum");
        check(Math.round(2.5f) == 3 && Math.round(-2.5f) == -2, "round(float)");
        check(Math.round(2.5) == 3L && Math.round(-2.5) == -2L, "round(double)");
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public void copySignDD(Blackhole bh) {
        for (int i = 0; i < SIZE; i++) {
            bh.consume(Math.copySign(doubleMagnitudes[i], doubleSigns[i]));
        }
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public void copySignFF(Blackhole bh) {
        for (int i = 0; i < SIZE; i++) {
            bh.consume(Math.copySign(floatMagnitudes[i], floatSigns[i]));
        }
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public void signumD(Blackhole bh) {
        for (int i = 0; i < SIZE; i++) {
            bh.consume(Math.signum(signumDoubles[i]));
        }
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public void signumF(Blackhole bh) {
        for (int i = 0; i < SIZE; i++) {
            bh.consume(Math.signum(signumFloats[i]));
        }
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public void roundF(Blackhole bh) {
        for (int i = 0; i < SIZE; i++) {
            bh.consume(Math.round(roundFloats[i]));
        }
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public void roundD(Blackhole bh) {
        for (int i = 0; i < SIZE; i++) {
            bh.consume(Math.round(roundDoubles[i]));
        }
    }

    private static void check(boolean condition, String what) {
        if (!condition) {
            throw new IllegalStateException("Unexpected " + what + " result");
        }
    }
}
