/*
 * Copyright (c) 2026, the Jeandle-JDK Authors. All Rights Reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 */
package org.openjdk.bench.vm.compiler;

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
@Measurement(iterations = 8, time = 1)
@Fork(value = 3, jvmArgsAppend = {
        "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED"
})
public class UnsafeUnalignedAccess {
    private static final Unsafe U = Unsafe.getUnsafe();
    private static final long OFFSET = U.arrayBaseOffset(byte[].class) + 1L;

    private final byte[] data = new byte[32];
    private short shortValue = (short) 0x8123;
    private char charValue = '\uffee';
    private int intValue = 0x89abcdef;
    private long longValue = 0x0123456789abcdefL;

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public short getShortUnaligned() {
        return U.getShortUnaligned(data, OFFSET);
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public char getCharUnaligned() {
        return U.getCharUnaligned(data, OFFSET);
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public int getIntUnaligned() {
        return U.getIntUnaligned(data, OFFSET);
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public long getLongUnaligned() {
        return U.getLongUnaligned(data, OFFSET);
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public void putShortUnaligned() {
        U.putShortUnaligned(data, OFFSET, shortValue);
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public void putCharUnaligned() {
        U.putCharUnaligned(data, OFFSET, charValue);
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public void putIntUnaligned() {
        U.putIntUnaligned(data, OFFSET, intValue);
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public void putLongUnaligned() {
        U.putLongUnaligned(data, OFFSET, longValue);
    }
}
