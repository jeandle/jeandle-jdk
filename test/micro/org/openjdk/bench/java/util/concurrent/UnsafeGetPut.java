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
public class UnsafeGetPut {
    private static final int OPERATIONS = 100_000;

    private static final Unsafe U = Unsafe.getUnsafe();

    private static final class Holder {
        boolean booleanValue;
        byte byteValue;
        short shortValue;
        char charValue;
        int intValue;
        long longValue;
        float floatValue;
        double doubleValue;
    }

    private static final long BOOLEAN_OFFSET = U.objectFieldOffset(Holder.class, "booleanValue");
    private static final long BYTE_OFFSET = U.objectFieldOffset(Holder.class, "byteValue");
    private static final long SHORT_OFFSET = U.objectFieldOffset(Holder.class, "shortValue");
    private static final long CHAR_OFFSET = U.objectFieldOffset(Holder.class, "charValue");
    private static final long INT_OFFSET = U.objectFieldOffset(Holder.class, "intValue");
    private static final long LONG_OFFSET = U.objectFieldOffset(Holder.class, "longValue");
    private static final long FLOAT_OFFSET = U.objectFieldOffset(Holder.class, "floatValue");
    private static final long DOUBLE_OFFSET = U.objectFieldOffset(Holder.class, "doubleValue");

    private final Holder[] holders = createHolders();

    private static Holder[] createHolders() {
        Holder[] result = new Holder[OPERATIONS];
        for (int i = 0; i < result.length; i++) {
            Holder holder = new Holder();
            holder.booleanValue = (i & 1) != 0;
            holder.byteValue = (byte) i;
            holder.shortValue = (short) i;
            holder.charValue = (char) i;
            holder.intValue = i;
            holder.longValue = i;
            holder.floatValue = i;
            holder.doubleValue = i;
            result[i] = holder;
        }
        return result;
    }

    @Benchmark @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(OPERATIONS)
    public int getBoolean() { int r = 0; for (int i = 0; i < OPERATIONS; i++) r += U.getBoolean(holders[i], BOOLEAN_OFFSET) ? 1 : 0; return r; }

    @Benchmark @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(OPERATIONS)
    public int getByte() { int r = 0; for (int i = 0; i < OPERATIONS; i++) r += U.getByte(holders[i], BYTE_OFFSET); return r; }

    @Benchmark @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(OPERATIONS)
    public int getShort() { int r = 0; for (int i = 0; i < OPERATIONS; i++) r += U.getShort(holders[i], SHORT_OFFSET); return r; }

    @Benchmark @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(OPERATIONS)
    public int getChar() { int r = 0; for (int i = 0; i < OPERATIONS; i++) r += U.getChar(holders[i], CHAR_OFFSET); return r; }

    @Benchmark @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(OPERATIONS)
    public int getInt() { int r = 0; for (int i = 0; i < OPERATIONS; i++) r += U.getInt(holders[i], INT_OFFSET); return r; }

    @Benchmark @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(OPERATIONS)
    public long getLong() { long r = 0; for (int i = 0; i < OPERATIONS; i++) r += U.getLong(holders[i], LONG_OFFSET); return r; }

    @Benchmark @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(OPERATIONS)
    public float getFloat() { float r = 0; for (int i = 0; i < OPERATIONS; i++) r += U.getFloat(holders[i], FLOAT_OFFSET); return r; }

    @Benchmark @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(OPERATIONS)
    public double getDouble() { double r = 0; for (int i = 0; i < OPERATIONS; i++) r += U.getDouble(holders[i], DOUBLE_OFFSET); return r; }

    @Benchmark @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(OPERATIONS)
    public int putBoolean() { for (int i = 0; i < OPERATIONS; i++) U.putBoolean(holders[i], BOOLEAN_OFFSET, true); return holders[OPERATIONS - 1].booleanValue ? 1 : 0; }

    @Benchmark @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(OPERATIONS)
    public int putByte() { for (int i = 0; i < OPERATIONS; i++) U.putByte(holders[i], BYTE_OFFSET, (byte) -37); return holders[OPERATIONS - 1].byteValue; }

    @Benchmark @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(OPERATIONS)
    public int putShort() { for (int i = 0; i < OPERATIONS; i++) U.putShort(holders[i], SHORT_OFFSET, (short) -12003); return holders[OPERATIONS - 1].shortValue; }

    @Benchmark @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(OPERATIONS)
    public int putChar() { for (int i = 0; i < OPERATIONS; i++) U.putChar(holders[i], CHAR_OFFSET, '\uffee'); return holders[OPERATIONS - 1].charValue; }

    @Benchmark @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(OPERATIONS)
    public int putInt() { for (int i = 0; i < OPERATIONS; i++) U.putInt(holders[i], INT_OFFSET, 0x89abcdef); return holders[OPERATIONS - 1].intValue; }

    @Benchmark @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(OPERATIONS)
    public long putLong() { for (int i = 0; i < OPERATIONS; i++) U.putLong(holders[i], LONG_OFFSET, 0x0123456789abcdefL); return holders[OPERATIONS - 1].longValue; }

    @Benchmark @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(OPERATIONS)
    public float putFloat() { for (int i = 0; i < OPERATIONS; i++) U.putFloat(holders[i], FLOAT_OFFSET, -0.0f); return holders[OPERATIONS - 1].floatValue; }

    @Benchmark @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(OPERATIONS)
    public double putDouble() { for (int i = 0; i < OPERATIONS; i++) U.putDouble(holders[i], DOUBLE_OFFSET, -0.0d); return holders[OPERATIONS - 1].doubleValue; }
}
