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
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 */

package org.openjdk.bench.vm.compiler;

import java.lang.reflect.Field;
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

/** Measures the jdk.internal.misc.Unsafe get*Acquire intrinsic family. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgsAppend = {
        "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED"
})
public class UnsafeAcquire {
    private static final int OPERATIONS = 1_000_000;
    private static final Unsafe U = Unsafe.getUnsafe();

    private static final long REFERENCE_OFFSET = offset("referenceValue");
    private static final long BOOLEAN_OFFSET = offset("booleanValue");
    private static final long BYTE_OFFSET = offset("byteValue");
    private static final long SHORT_OFFSET = offset("shortValue");
    private static final long CHAR_OFFSET = offset("charValue");
    private static final long INT_OFFSET = offset("intValue");
    private static final long LONG_OFFSET = offset("longValue");
    private static final long FLOAT_OFFSET = offset("floatValue");
    private static final long DOUBLE_OFFSET = offset("doubleValue");

    private final Holder holder = new Holder();

    public UnsafeAcquire() {
        holder.referenceValue = this;
        holder.booleanValue = true;
        holder.byteValue = (byte) 0x81;
        holder.shortValue = (short) 0x8123;
        holder.charValue = (char) 0x9123;
        holder.intValue = 0x87654321;
        holder.longValue = 0x8877665544332211L;
        holder.floatValue = 1.25f;
        holder.doubleValue = 1.25d;
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(OPERATIONS)
    public int getReferenceAcquire() {
        Object expected = holder.referenceValue;
        int result = 0;
        for (int i = 0; i < OPERATIONS; i++) {
            result += U.getReferenceAcquire(holder, REFERENCE_OFFSET) == expected
                    ? 1 : 0;
        }
        return result;
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(OPERATIONS)
    public int getBooleanAcquire() {
        int result = 0;
        for (int i = 0; i < OPERATIONS; i++) {
            result += U.getBooleanAcquire(holder, BOOLEAN_OFFSET) ? 1 : 0;
        }
        return result;
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(OPERATIONS)
    public int getByteAcquire() {
        int result = 0;
        for (int i = 0; i < OPERATIONS; i++) {
            result += U.getByteAcquire(holder, BYTE_OFFSET);
        }
        return result;
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(OPERATIONS)
    public int getShortAcquire() {
        int result = 0;
        for (int i = 0; i < OPERATIONS; i++) {
            result += U.getShortAcquire(holder, SHORT_OFFSET);
        }
        return result;
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(OPERATIONS)
    public int getCharAcquire() {
        int result = 0;
        for (int i = 0; i < OPERATIONS; i++) {
            result += U.getCharAcquire(holder, CHAR_OFFSET);
        }
        return result;
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(OPERATIONS)
    public int getIntAcquire() {
        int result = 0;
        for (int i = 0; i < OPERATIONS; i++) {
            result += U.getIntAcquire(holder, INT_OFFSET);
        }
        return result;
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(OPERATIONS)
    public long getLongAcquire() {
        long result = 0;
        for (int i = 0; i < OPERATIONS; i++) {
            result += U.getLongAcquire(holder, LONG_OFFSET);
        }
        return result;
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(OPERATIONS)
    public float getFloatAcquire() {
        float result = 0;
        for (int i = 0; i < OPERATIONS; i++) {
            result += U.getFloatAcquire(holder, FLOAT_OFFSET);
        }
        return result;
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(OPERATIONS)
    public double getDoubleAcquire() {
        double result = 0;
        for (int i = 0; i < OPERATIONS; i++) {
            result += U.getDoubleAcquire(holder, DOUBLE_OFFSET);
        }
        return result;
    }

    private static long offset(String name) {
        try {
            Field field = Holder.class.getDeclaredField(name);
            return U.objectFieldOffset(field);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    static class Holder {
        Object referenceValue;
        boolean booleanValue;
        byte byteValue;
        short shortValue;
        char charValue;
        int intValue;
        long longValue;
        float floatValue;
        double doubleValue;
    }
}
