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
 */

package org.openjdk.bench.jeandle.pea;

import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.CompilerControl;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Measures boxing-aware escape analysis in straight-line, merge, loop, and
 * partial-escape shapes. Inputs stay outside the default wrapper caches so an
 * uneliminated box performs a real allocation.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class BoxingPartialEscapeAnalysis {
    private static final int VALUE_COUNT = 1 << 10;
    private static final int MASK = VALUE_COUNT - 1;
    private static final int LOOP_STEPS = 8;

    private static volatile Object escaped;

    private final int[] values = new int[VALUE_COUNT];
    private int cursor;

    @Setup(Level.Trial)
    public void setup() {
        for (int i = 0; i < VALUE_COUNT; i++) {
            values[i] = 1_000_000 + i * 17;
        }
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public int primitiveBaseline() {
        return values[cursor++ & MASK];
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public int integerRoundTrip() {
        int value = values[cursor++ & MASK];
        Integer boxed = Integer.valueOf(value);
        return boxed.intValue();
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public long longRoundTrip() {
        long value = values[cursor++ & MASK];
        Long boxed = Long.valueOf(value);
        return boxed.longValue();
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public int branchMerge() {
        int value = values[cursor++ & MASK];
        Integer boxed;
        if ((value & 1) == 0) {
            boxed = Integer.valueOf(value);
        } else {
            boxed = Integer.valueOf(value + 1);
        }
        return boxed.intValue();
    }

    @Benchmark
    @OperationsPerInvocation(LOOP_STEPS + 1)
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public int loopCarried() {
        int index = cursor++ & MASK;
        Integer accumulator = Integer.valueOf(values[index]);
        for (int i = 0; i < LOOP_STEPS; i++) {
            int delta = (values[(index + i) & MASK] & 7) + 1;
            accumulator = Integer.valueOf(accumulator.intValue() + delta);
        }
        return accumulator.intValue();
    }

    /**
     * Diagnostic baseline for a partially escaping box. Jeandle PEA recognizes
     * the box as partially escaping, but intentionally does not yet sink its
     * allocation into the rare escape branch. Consequently this benchmark is
     * expected to retain one allocation per invocation even when
     * JeandleOptBoxing is enabled; it must not be used as an allocation-
     * elimination acceptance test.
     */
    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public int partialEscape() {
        int index = cursor++ & MASK;
        Integer boxed = Integer.valueOf(values[index]);
        if (index == 0) {
            escape(boxed);
        }
        return boxed.intValue();
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    private static void escape(Object value) {
        escaped = value;
    }
}
