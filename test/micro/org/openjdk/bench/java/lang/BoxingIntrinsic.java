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

package org.openjdk.bench.java.lang;

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
 * Measures frontend boxing and unboxing optimization independently of
 * boxing-aware escape analysis. Jeandle's semantic operations can be toggled
 * through -XX:ControlIntrinsic. C2 does not implement these methods as
 * LibraryCallKit intrinsics; -XX:EliminateAutoBox is its closest specialized
 * control, while disabling the same ControlIntrinsic IDs is a useful control
 * experiment.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class BoxingIntrinsic {
    private static final int BATCH = 1 << 10;
    private static final int MASK = BATCH - 1;
    private static final int PRIMITIVE_KINDS = 8;

    private final int[] cachedInts = new int[BATCH];
    private final int[] uncachedInts = new int[BATCH];
    private final Integer[] boxedInts = new Integer[BATCH];
    private final Boolean[] boxedBooleans = new Boolean[BATCH];
    private final Byte[] boxedBytes = new Byte[BATCH];
    private final Character[] boxedChars = new Character[BATCH];
    private final Short[] boxedShorts = new Short[BATCH];
    private final Long[] boxedLongs = new Long[BATCH];
    private final Float[] boxedFloats = new Float[BATCH];
    private final Double[] boxedDoubles = new Double[BATCH];
    private final Object[] integerSink = new Object[BATCH];
    private final Object[] mixedSink = new Object[BATCH * PRIMITIVE_KINDS];

    private int cursor;

    @Setup(Level.Trial)
    public void setup() {
        for (int i = 0; i < BATCH; i++) {
            int cached = (i & 255) - 128;
            int uncached = 1_000_000 + i * 17;
            cachedInts[i] = cached;
            uncachedInts[i] = uncached;

            boxedInts[i] = Integer.valueOf(uncached);
            boxedBooleans[i] = Boolean.valueOf((i & 1) != 0);
            boxedBytes[i] = Byte.valueOf((byte) i);
            boxedChars[i] = Character.valueOf((char) (i + 256));
            boxedShorts[i] = Short.valueOf((short) (i + 256));
            boxedLongs[i] = Long.valueOf(uncached);
            boxedFloats[i] = Float.valueOf(uncached + 0.25f);
            boxedDoubles[i] = Double.valueOf(uncached + 0.5d);
        }
    }

    @Benchmark
    @OperationsPerInvocation(BATCH)
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public Object integerValueOfCached() {
        int start = cursor++ & MASK;
        for (int i = 0; i < BATCH; i++) {
            integerSink[i] = Integer.valueOf(cachedInts[(start + i) & MASK]);
        }
        return integerSink[start];
    }

    @Benchmark
    @OperationsPerInvocation(BATCH)
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public Object integerValueOfUncached() {
        int start = cursor++ & MASK;
        for (int i = 0; i < BATCH; i++) {
            integerSink[i] = Integer.valueOf(uncachedInts[(start + i) & MASK]);
        }
        return integerSink[start];
    }

    @Benchmark
    @OperationsPerInvocation(BATCH)
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public long integerUnbox() {
        int start = cursor++ & MASK;
        long sum = 0;
        for (int i = 0; i < BATCH; i++) {
            sum += boxedInts[(start + i) & MASK].intValue();
        }
        return sum;
    }

    @Benchmark
    @OperationsPerInvocation(BATCH * PRIMITIVE_KINDS)
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public Object allPrimitiveValueOf() {
        int start = cursor++ & MASK;
        int output = 0;
        for (int i = 0; i < BATCH; i++) {
            int value = uncachedInts[(start + i) & MASK];
            mixedSink[output++] = Boolean.valueOf((value & 1) != 0);
            mixedSink[output++] = Byte.valueOf((byte) value);
            mixedSink[output++] = Character.valueOf((char) value);
            mixedSink[output++] = Short.valueOf((short) value);
            mixedSink[output++] = Integer.valueOf(value);
            mixedSink[output++] = Long.valueOf(value);
            mixedSink[output++] = Float.valueOf(value + 0.25f);
            mixedSink[output++] = Double.valueOf(value + 0.5d);
        }
        return mixedSink[start * PRIMITIVE_KINDS];
    }

    @Benchmark
    @OperationsPerInvocation(BATCH * PRIMITIVE_KINDS)
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public long allPrimitiveUnbox() {
        int start = cursor++ & MASK;
        long sum = 0;
        for (int i = 0; i < BATCH; i++) {
            int index = (start + i) & MASK;
            sum += boxedBooleans[index].booleanValue() ? 1 : 0;
            sum += boxedBytes[index].byteValue();
            sum += boxedChars[index].charValue();
            sum += boxedShorts[index].shortValue();
            sum += boxedInts[index].intValue();
            sum += boxedLongs[index].longValue();
            sum += Float.floatToRawIntBits(boxedFloats[index].floatValue());
            sum += Double.doubleToRawLongBits(boxedDoubles[index].doubleValue());
        }
        return sum;
    }
}
