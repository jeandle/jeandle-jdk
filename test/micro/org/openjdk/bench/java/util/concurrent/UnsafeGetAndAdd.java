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
public class UnsafeGetAndAdd {
    private static final Unsafe U = Unsafe.getUnsafe();

    private static final class Holder {
        byte byteValue;
        short shortValue;
        int intValue;
        long longValue;
    }

    private static final long BYTE_OFFSET = U.objectFieldOffset(Holder.class, "byteValue");
    private static final long SHORT_OFFSET = U.objectFieldOffset(Holder.class, "shortValue");
    private static final long INT_OFFSET = U.objectFieldOffset(Holder.class, "intValue");
    private static final long LONG_OFFSET = U.objectFieldOffset(Holder.class, "longValue");

    private final Holder holder = new Holder();

    // Keep the benchmark entry out of line from the JMH harness; the Unsafe
    // call itself may still be inlined and lowered by the selected compiler.
    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public byte getAndAddByte() {
        return U.getAndAddByte(holder, BYTE_OFFSET, (byte) 1);
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public short getAndAddShort() {
        return U.getAndAddShort(holder, SHORT_OFFSET, (short) 1);
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public int getAndAddInt() {
        return U.getAndAddInt(holder, INT_OFFSET, 1);
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public long getAndAddLong() {
        return U.getAndAddLong(holder, LONG_OFFSET, 1L);
    }
}
