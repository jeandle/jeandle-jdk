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

    private final Holder holder = new Holder();

    @Benchmark @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public boolean getBoolean() { return U.getBoolean(holder, BOOLEAN_OFFSET); }

    @Benchmark @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public byte getByte() { return U.getByte(holder, BYTE_OFFSET); }

    @Benchmark @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public short getShort() { return U.getShort(holder, SHORT_OFFSET); }

    @Benchmark @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public char getChar() { return U.getChar(holder, CHAR_OFFSET); }

    @Benchmark @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public int getInt() { return U.getInt(holder, INT_OFFSET); }

    @Benchmark @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public long getLong() { return U.getLong(holder, LONG_OFFSET); }

    @Benchmark @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public float getFloat() { return U.getFloat(holder, FLOAT_OFFSET); }

    @Benchmark @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public double getDouble() { return U.getDouble(holder, DOUBLE_OFFSET); }

    @Benchmark @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public void putBoolean() { U.putBoolean(holder, BOOLEAN_OFFSET, true); }

    @Benchmark @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public void putByte() { U.putByte(holder, BYTE_OFFSET, (byte) -37); }

    @Benchmark @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public void putShort() { U.putShort(holder, SHORT_OFFSET, (short) -12003); }

    @Benchmark @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public void putChar() { U.putChar(holder, CHAR_OFFSET, '\uffee'); }

    @Benchmark @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public void putInt() { U.putInt(holder, INT_OFFSET, 0x89abcdef); }

    @Benchmark @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public void putLong() { U.putLong(holder, LONG_OFFSET, 0x0123456789abcdefL); }

    @Benchmark @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public void putFloat() { U.putFloat(holder, FLOAT_OFFSET, -0.0f); }

    @Benchmark @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public void putDouble() { U.putDouble(holder, DOUBLE_OFFSET, -0.0d); }
}
