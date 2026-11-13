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
 * Math.pow under Jeandle, compared against C2 ({@code -XX:-UseJeandleCompiler}
 * on the same build). powDefault varies both operands from state arrays.
 * powSquare and powHalf keep the exponent a literal 2.0 / 0.5 in the method
 * body - the shape later constant-folding optimizations are measured against -
 * while the base still comes from state so the call is not fully
 * constant-foldable. Bases stay positive and finite so the general libm path,
 * not a special-case bail-out, dominates the measurement. Results require a
 * Java Blackhole; see README.md in this directory for the harness options.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class JeandlePow {
    static final int SIZE = 512;

    private double[] bases;
    private double[] exponents;

    @Setup
    public void setup() {
        JeandleBenchmarkSupport.requireJavaBlackhole();
        bases = new double[SIZE];
        exponents = new double[SIZE];
        for (int i = 0; i < SIZE; i++) {
            // Bases spread multiplicatively over ~1e-3 .. ~1e3.
            bases[i] = Math.exp(((i % 64) - 32) * 0.1875) + (i & 1) * 0.03125;
            // Mostly non-integer exponents over ~-8 .. ~8.
            exponents[i] = ((i % 32) - 16) * 0.5 + ((i & 1) == 0 ? 0.25 : 0.75);
        }
        JeandleBenchmarkSupport.check(Math.pow(2.0, 10.0) == 1024.0, "pow default");
        JeandleBenchmarkSupport.check(Math.pow(3.0, 2.0) == 9.0, "pow square");
        JeandleBenchmarkSupport.check(Math.pow(9.0, 0.5) == 3.0, "pow half");
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public void powDefault(Blackhole bh) {
        for (int i = 0; i < SIZE; i++) {
            bh.consume(Math.pow(bases[i], exponents[i]));
        }
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public void powSquare(Blackhole bh) {
        for (int i = 0; i < SIZE; i++) {
            bh.consume(Math.pow(bases[i], 2.0));
        }
    }

    @Benchmark
    @OperationsPerInvocation(SIZE)
    public void powHalf(Blackhole bh) {
        for (int i = 0; i < SIZE; i++) {
            bh.consume(Math.pow(bases[i], 0.5));
        }
    }
}
