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
import org.openjdk.jmh.annotations.OperationsPerInvocation;
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
public class UnsignedDivModIntrinsics {
    private static final int OPERATIONS = 16;

    @Param({"small", "high"})
    public String divisorKind;

    private int intSeed;
    private int intDivisor;
    private long longSeed;
    private long longDivisor;

    @Setup
    public void setup() {
        intSeed = 0x89abcdef;
        longSeed = 0x89abcdef01234567L;
        if (divisorKind.equals("small")) {
            intDivisor = 3;
            longDivisor = 3L;
        } else {
            intDivisor = -3;
            longDivisor = -3L;
        }
    }

    // Keep a loop-free dependency chain so every invocation has the same 16 operations.
    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(OPERATIONS)
    public int divideUnsignedInt() {
        int salt = intSeed += 0x9e3779b9;
        int value = salt;
        int divisor = intDivisor;
        value = Integer.divideUnsigned((value + salt) ^ 0x9e3779b9, divisor);
        value = Integer.divideUnsigned((value + salt) ^ 0x7f4a7c15, divisor);
        value = Integer.divideUnsigned((value + salt) ^ 0x94d049bb, divisor);
        value = Integer.divideUnsigned((value + salt) ^ 0x2545f491, divisor);
        value = Integer.divideUnsigned((value + salt) ^ 0x369dea0f, divisor);
        value = Integer.divideUnsigned((value + salt) ^ 0x85ebca6b, divisor);
        value = Integer.divideUnsigned((value + salt) ^ 0xc2b2ae35, divisor);
        value = Integer.divideUnsigned((value + salt) ^ 0x27d4eb2f, divisor);
        value = Integer.divideUnsigned((value + salt) ^ 0x165667b1, divisor);
        value = Integer.divideUnsigned((value + salt) ^ 0xd3a2646c, divisor);
        value = Integer.divideUnsigned((value + salt) ^ 0xfd7046c5, divisor);
        value = Integer.divideUnsigned((value + salt) ^ 0xb55a4f09, divisor);
        value = Integer.divideUnsigned((value + salt) ^ 0x1b873593, divisor);
        value = Integer.divideUnsigned((value + salt) ^ 0xcc9e2d51, divisor);
        value = Integer.divideUnsigned((value + salt) ^ 0xe6546b64, divisor);
        value = Integer.divideUnsigned((value + salt) ^ 0x9e3779b1, divisor);
        return value;
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(OPERATIONS)
    public int remainderUnsignedInt() {
        int salt = intSeed += 0x9e3779b9;
        int value = salt;
        int divisor = intDivisor;
        value = Integer.remainderUnsigned((value + salt) ^ 0x9e3779b9, divisor);
        value = Integer.remainderUnsigned((value + salt) ^ 0x7f4a7c15, divisor);
        value = Integer.remainderUnsigned((value + salt) ^ 0x94d049bb, divisor);
        value = Integer.remainderUnsigned((value + salt) ^ 0x2545f491, divisor);
        value = Integer.remainderUnsigned((value + salt) ^ 0x369dea0f, divisor);
        value = Integer.remainderUnsigned((value + salt) ^ 0x85ebca6b, divisor);
        value = Integer.remainderUnsigned((value + salt) ^ 0xc2b2ae35, divisor);
        value = Integer.remainderUnsigned((value + salt) ^ 0x27d4eb2f, divisor);
        value = Integer.remainderUnsigned((value + salt) ^ 0x165667b1, divisor);
        value = Integer.remainderUnsigned((value + salt) ^ 0xd3a2646c, divisor);
        value = Integer.remainderUnsigned((value + salt) ^ 0xfd7046c5, divisor);
        value = Integer.remainderUnsigned((value + salt) ^ 0xb55a4f09, divisor);
        value = Integer.remainderUnsigned((value + salt) ^ 0x1b873593, divisor);
        value = Integer.remainderUnsigned((value + salt) ^ 0xcc9e2d51, divisor);
        value = Integer.remainderUnsigned((value + salt) ^ 0xe6546b64, divisor);
        value = Integer.remainderUnsigned((value + salt) ^ 0x9e3779b1, divisor);
        return value;
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(OPERATIONS)
    public long divideUnsignedLong() {
        long salt = longSeed += 0x9e3779b97f4a7c15L;
        long value = salt;
        long divisor = longDivisor;
        value = Long.divideUnsigned((value + salt) ^ 0x9e3779b97f4a7c15L, divisor);
        value = Long.divideUnsigned((value + salt) ^ 0xbf58476d1ce4e5b9L, divisor);
        value = Long.divideUnsigned((value + salt) ^ 0x94d049bb133111ebL, divisor);
        value = Long.divideUnsigned((value + salt) ^ 0xd6e8feb86659fd93L, divisor);
        value = Long.divideUnsigned((value + salt) ^ 0xa0761d6478bd642fL, divisor);
        value = Long.divideUnsigned((value + salt) ^ 0xe7037ed1a0b428dbL, divisor);
        value = Long.divideUnsigned((value + salt) ^ 0x8ebc6af09c88c6e3L, divisor);
        value = Long.divideUnsigned((value + salt) ^ 0x589965cc75374cc3L, divisor);
        value = Long.divideUnsigned((value + salt) ^ 0x1d8e4e27c47d124fL, divisor);
        value = Long.divideUnsigned((value + salt) ^ 0xeb44accab455d165L, divisor);
        value = Long.divideUnsigned((value + salt) ^ 0xc6bc279692b5c323L, divisor);
        value = Long.divideUnsigned((value + salt) ^ 0xd3833e804f4c574bL, divisor);
        value = Long.divideUnsigned((value + salt) ^ 0x9e6c63d0676a9a99L, divisor);
        value = Long.divideUnsigned((value + salt) ^ 0x9e3779b185ebca87L, divisor);
        value = Long.divideUnsigned((value + salt) ^ 0xc2b2ae3d27d4eb4fL, divisor);
        value = Long.divideUnsigned((value + salt) ^ 0x165667b19e3779f9L, divisor);
        return value;
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(OPERATIONS)
    public long remainderUnsignedLong() {
        long salt = longSeed += 0x9e3779b97f4a7c15L;
        long value = salt;
        long divisor = longDivisor;
        value = Long.remainderUnsigned((value + salt) ^ 0x9e3779b97f4a7c15L, divisor);
        value = Long.remainderUnsigned((value + salt) ^ 0xbf58476d1ce4e5b9L, divisor);
        value = Long.remainderUnsigned((value + salt) ^ 0x94d049bb133111ebL, divisor);
        value = Long.remainderUnsigned((value + salt) ^ 0xd6e8feb86659fd93L, divisor);
        value = Long.remainderUnsigned((value + salt) ^ 0xa0761d6478bd642fL, divisor);
        value = Long.remainderUnsigned((value + salt) ^ 0xe7037ed1a0b428dbL, divisor);
        value = Long.remainderUnsigned((value + salt) ^ 0x8ebc6af09c88c6e3L, divisor);
        value = Long.remainderUnsigned((value + salt) ^ 0x589965cc75374cc3L, divisor);
        value = Long.remainderUnsigned((value + salt) ^ 0x1d8e4e27c47d124fL, divisor);
        value = Long.remainderUnsigned((value + salt) ^ 0xeb44accab455d165L, divisor);
        value = Long.remainderUnsigned((value + salt) ^ 0xc6bc279692b5c323L, divisor);
        value = Long.remainderUnsigned((value + salt) ^ 0xd3833e804f4c574bL, divisor);
        value = Long.remainderUnsigned((value + salt) ^ 0x9e6c63d0676a9a99L, divisor);
        value = Long.remainderUnsigned((value + salt) ^ 0x9e3779b185ebca87L, divisor);
        value = Long.remainderUnsigned((value + salt) ^ 0xc2b2ae3d27d4eb4fL, divisor);
        value = Long.remainderUnsigned((value + salt) ^ 0x165667b19e3779f9L, divisor);
        return value;
    }
}
