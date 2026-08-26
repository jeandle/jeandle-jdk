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

package org.openjdk.bench.java.util.concurrent;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import jdk.internal.misc.Unsafe;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.CompilerControl;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Call-boundary coverage for primitive Unsafe compareAndSet operations.
 * Int/long use the public AtomicInteger/AtomicLong APIs; byte/short call
 * Unsafe directly because there are no corresponding public atomic wrappers.
 * Performance runs keep these benchmark entry methods out of line from the
 * JMH harness. AtomicInteger/AtomicLong.compareAndSet may still be inlined
 * and lowered according to the selected compiler's intrinsic strategy.
 * Every benchmark method executes two CAS operations to keep the per-operation
 * call-boundary overhead consistent between success and failure cases.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 8, time = 1)
@Fork(5)
public class AtomicCompareAndSet {
    private static final Unsafe U = Unsafe.getUnsafe();
    private static final long BYTE_OFFSET;
    private static final long SHORT_OFFSET;

    static {
        try {
            BYTE_OFFSET = U.objectFieldOffset(
                    AtomicCompareAndSet.class.getDeclaredField("byteValue"));
            SHORT_OFFSET = U.objectFieldOffset(
                    AtomicCompareAndSet.class.getDeclaredField("shortValue"));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final AtomicInteger intValue = new AtomicInteger();
    private final AtomicLong longValue = new AtomicLong();
    private volatile byte byteValue;
    private volatile short shortValue;

    @Benchmark
    @OperationsPerInvocation(2)
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public boolean byteSuccess() {
        boolean first = U.compareAndSetByte(this, BYTE_OFFSET, (byte) 0, (byte) 1);
        boolean second = U.compareAndSetByte(this, BYTE_OFFSET, (byte) 1, (byte) 0);
        return first & second;
    }

    @Benchmark
    @OperationsPerInvocation(2)
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public boolean byteFailure() {
        boolean first = U.compareAndSetByte(this, BYTE_OFFSET, (byte) 1, (byte) 2);
        boolean second = U.compareAndSetByte(this, BYTE_OFFSET, (byte) 1, (byte) 2);
        return first & second;
    }

    @Benchmark
    @OperationsPerInvocation(2)
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public boolean shortSuccess() {
        boolean first = U.compareAndSetShort(this, SHORT_OFFSET, (short) 0, (short) 1);
        boolean second = U.compareAndSetShort(this, SHORT_OFFSET, (short) 1, (short) 0);
        return first & second;
    }

    @Benchmark
    @OperationsPerInvocation(2)
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public boolean shortFailure() {
        boolean first = U.compareAndSetShort(this, SHORT_OFFSET, (short) 1, (short) 2);
        boolean second = U.compareAndSetShort(this, SHORT_OFFSET, (short) 1, (short) 2);
        return first & second;
    }

    @Benchmark
    @OperationsPerInvocation(2)
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public boolean intSuccess() {
        boolean first = intValue.compareAndSet(0, 1);
        boolean second = intValue.compareAndSet(1, 0);
        return first & second;
    }

    @Benchmark
    @OperationsPerInvocation(2)
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public boolean intFailure() {
        boolean first = intValue.compareAndSet(1, 2);
        boolean second = intValue.compareAndSet(1, 2);
        return first & second;
    }

    @Benchmark
    @OperationsPerInvocation(2)
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public boolean longSuccess() {
        boolean first = longValue.compareAndSet(0L, 1L);
        boolean second = longValue.compareAndSet(1L, 0L);
        return first & second;
    }

    @Benchmark
    @OperationsPerInvocation(2)
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public boolean longFailure() {
        boolean first = longValue.compareAndSet(1L, 2L);
        boolean second = longValue.compareAndSet(1L, 2L);
        return first & second;
    }
}
