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

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgsAppend = {
        "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED"
})
public class UnsafeMemoryOrdering {
    private static final int OPERATIONS = 100_000;
    private static final Unsafe U = Unsafe.getUnsafe();

    private static final class Holder {
        int value;
        int witness;
    }

    private static final long VALUE_OFFSET = U.objectFieldOffset(Holder.class, "value");
    private static final long WITNESS_OFFSET = U.objectFieldOffset(Holder.class, "witness");
    private final Holder holder = new Holder();

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(OPERATIONS)
    public int getOpaque() {
        int result = 0;
        for (int i = 0; i < OPERATIONS; i++) result += U.getIntOpaque(holder, VALUE_OFFSET);
        return result;
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(OPERATIONS)
    public int putOpaque() {
        for (int i = 0; i < OPERATIONS; i++) U.putIntOpaque(holder, VALUE_OFFSET, i);
        return holder.value;
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(OPERATIONS)
    public int getVolatile() {
        int result = 0;
        for (int i = 0; i < OPERATIONS; i++) result += U.getIntVolatile(holder, VALUE_OFFSET);
        return result;
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(OPERATIONS)
    public int putVolatile() {
        for (int i = 0; i < OPERATIONS; i++) U.putIntVolatile(holder, VALUE_OFFSET, i);
        return holder.value;
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(OPERATIONS)
    public int getAcquire() {
        int result = 0;
        for (int i = 0; i < OPERATIONS; i++) result += U.getIntAcquire(holder, VALUE_OFFSET);
        return result;
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(OPERATIONS)
    public int putRelease() {
        for (int i = 0; i < OPERATIONS; i++) U.putIntRelease(holder, VALUE_OFFSET, i);
        return holder.value;
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(OPERATIONS)
    public int fullFence() {
        int result = 0;
        for (int i = 0; i < OPERATIONS; i++) {
            U.fullFence();
            result += i;
        }
        return result;
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(OPERATIONS)
    public int loadFence() {
        int result = 0;
        for (int i = 0; i < OPERATIONS; i++) {
            U.loadFence();
            result += i;
        }
        return result;
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(OPERATIONS)
    public int storeFence() {
        int result = 0;
        for (int i = 0; i < OPERATIONS; i++) {
            U.storeFence();
            result += i;
        }
        return result;
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(OPERATIONS)
    public int storeStoreFence() {
        int result = 0;
        for (int i = 0; i < OPERATIONS; i++) {
            U.storeStoreFence();
            result += i;
        }
        return result;
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(OPERATIONS)
    public int fullFenceWithAccesses() {
        int result = 0;
        for (int i = 0; i < OPERATIONS; i++) {
            U.putInt(holder, VALUE_OFFSET, i);
            U.fullFence();
            result += U.getInt(holder, WITNESS_OFFSET);
        }
        return result;
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(OPERATIONS)
    public int loadFenceWithAccesses() {
        int result = 0;
        for (int i = 0; i < OPERATIONS; i++) {
            result += U.getInt(holder, VALUE_OFFSET);
            U.loadFence();
            result += U.getInt(holder, WITNESS_OFFSET);
        }
        return result;
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(OPERATIONS)
    public int storeFenceWithAccesses() {
        for (int i = 0; i < OPERATIONS; i++) {
            U.putInt(holder, VALUE_OFFSET, i);
            U.storeFence();
            U.putInt(holder, WITNESS_OFFSET, i);
        }
        return holder.witness;
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(OPERATIONS)
    public int storeStoreFenceWithAccesses() {
        for (int i = 0; i < OPERATIONS; i++) {
            U.putInt(holder, VALUE_OFFSET, i);
            U.storeStoreFence();
            U.putInt(holder, WITNESS_OFFSET, i);
        }
        return holder.witness;
    }
}
