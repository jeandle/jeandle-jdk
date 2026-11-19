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

/** Runtime identities with finite inputs and a separate exceptional distribution. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
public class JeandlePowIdentities {
    static final int SIZE = 512;
    @Param({"normal", "special"})
    public String distribution;
    double[] bases, exponents, output;

    @Setup
    public void setup() {
        JeandleBenchmarkSupport.requireJavaBlackhole();
        Random random = new Random(Long.getLong("review.numeric.seed", 2601109L));
        bases = new double[SIZE];
        exponents = new double[SIZE];
        output = new double[SIZE];
        double[] special = {0.0, -0.0, 1.0, -1.0, 2.0, -2.0,
                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                Double.NaN, Double.MIN_VALUE, -Double.MIN_VALUE,
                Double.MAX_VALUE, -Double.MAX_VALUE, 0.5, -0.5,
                Double.longBitsToDouble(0xfff8000000001234L)};
        for (int i = 0; i < SIZE; i++) {
            bases[i] = switch (distribution) {
                case "normal" -> 0.125 + random.nextDouble() * 7.875;
                case "special" -> special[i % special.length];
                default -> throw new IllegalArgumentException(distribution);
            };
            exponents[i] = (random.nextDouble() - 0.5) * 12.0;
        }
    }

    // The pow caller sees a call result as exponent in bytecode. Inspect IR
    // to confirm inlining/canonicalization reveals the constant before folding.
    private static double halfExponent(boolean choice) {
        return choice ? 0.5 : 0.5;
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public double[] powOneStore() {
        for (int i = 0; i < SIZE; i++) {
            output[i] = Math.pow(bases[i], 1.0);
        }
        return output;
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public double[] powMinusOneStore() {
        for (int i = 0; i < SIZE; i++) {
            output[i] = Math.pow(bases[i], -1.0);
        }
        return output;
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public double[] powHalfStore() {
        for (int i = 0; i < SIZE; i++) {
            output[i] = Math.pow(bases[i], 0.5);
        }
        return output;
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public double[] powLateHalfStore() {
        for (int i = 0; i < SIZE; i++) {
            output[i] = Math.pow(bases[i], halfExponent(bases[i] > 1.0));
        }
        return output;
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public double[] powTwoStore() {
        for (int i = 0; i < SIZE; i++) {
            output[i] = Math.pow(bases[i], 2.0);
        }
        return output;
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public double[] powZeroStore() {
        for (int i = 0; i < SIZE; i++) {
            output[i] = Math.pow(bases[i], 0.0);
        }
        return output;
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public double[] powVariableStore() {
        for (int i = 0; i < SIZE; i++) {
            output[i] = Math.pow(bases[i], exponents[i]);
        }
        return output;
    }
}
