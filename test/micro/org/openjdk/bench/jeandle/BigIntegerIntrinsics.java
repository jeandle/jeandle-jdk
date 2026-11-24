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

import java.math.BigInteger;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
public class BigIntegerIntrinsics {
    // Include scalar-sized inputs, crypto-sized operands and the Montgomery
    // fallback above 512 limbs. Multiplication also crosses its Java thresholds.
    @Param({"64", "1024", "4096", "16448"})
    public int bits;

    private BigInteger[] values;
    private BigInteger modulus;
    private BigInteger exponent;
    private int cursor;
    private int shift;

    @Setup
    public void setup() {
        Random random = new Random(0x5eed);
        values = new BigInteger[16];
        for (int i = 0; i < values.length; i++) {
            values[i] = new BigInteger(bits, random).setBit(bits - 1);
        }
        modulus = new BigInteger(bits, random).setBit(bits - 1).setBit(0);
        exponent = BigInteger.valueOf(65537);
        shift = 17;
    }

    @Benchmark
    public BigInteger multiply() {
        int index = cursor++ & 15;
        return values[index].multiply(values[(index + 1) & 15]);
    }

    @Benchmark
    public BigInteger square() {
        BigInteger value = values[cursor++ & 15];
        return value.multiply(value);
    }

    @Benchmark
    public BigInteger modPow() {
        return values[cursor++ & 15].modPow(exponent, modulus);
    }

    @Benchmark
    public BigInteger shiftLeft() {
        return values[cursor++ & 15].shiftLeft(shift);
    }

    @Benchmark
    public BigInteger shiftRight() {
        return values[cursor++ & 15].shiftRight(shift);
    }
}
