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
 *
 */

package org.openjdk.bench.java.util;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import jdk.internal.misc.Unsafe;
import jdk.internal.util.ArraysSupport;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/** Measures the _vectorizedMismatch intrinsic directly. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgsAppend = {
        "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.util=ALL-UNNAMED"
})
public class VectorizedMismatchIntrinsic {

    // Cover the inline byte path (< 16 bytes), the 16-byte boundary, and the
    // existing vector-tier cases.
    @Param({"8", "32", "64", "8192"})
    public int length;

    private byte[] bytes;
    private byte[] otherBytes;
    private char[] chars;
    private char[] otherChars;
    private int[] ints;
    private int[] otherInts;
    private long[] longs;
    private long[] otherLongs;

    @Setup
    public void setup() {
        bytes = new byte[length];
        otherBytes = new byte[length];
        chars = new char[length];
        otherChars = new char[length];
        ints = new int[length];
        otherInts = new int[length];
        longs = new long[length];
        otherLongs = new long[length];

        Arrays.fill(bytes, (byte) 0x5a);
        Arrays.fill(otherBytes, (byte) 0x5a);
        Arrays.fill(chars, (char) 0x5a5a);
        Arrays.fill(otherChars, (char) 0x5a5a);
        Arrays.fill(ints, 0x5a5a5a5a);
        Arrays.fill(otherInts, 0x5a5a5a5a);
        Arrays.fill(longs, 0x5a5a5a5a5a5a5a5aL);
        Arrays.fill(otherLongs, 0x5a5a5a5a5a5a5a5aL);

    }

    @Benchmark
    public int byteMismatch() {
        return ArraysSupport.vectorizedMismatch(bytes, Unsafe.ARRAY_BYTE_BASE_OFFSET,
                otherBytes, Unsafe.ARRAY_BYTE_BASE_OFFSET, length,
                ArraysSupport.LOG2_ARRAY_BYTE_INDEX_SCALE);
    }

    @Benchmark
    public int charMismatch() {
        return ArraysSupport.vectorizedMismatch(chars, Unsafe.ARRAY_CHAR_BASE_OFFSET,
                otherChars, Unsafe.ARRAY_CHAR_BASE_OFFSET, length,
                ArraysSupport.LOG2_ARRAY_CHAR_INDEX_SCALE);
    }

    @Benchmark
    public int intMismatch() {
        return ArraysSupport.vectorizedMismatch(ints, Unsafe.ARRAY_INT_BASE_OFFSET,
                otherInts, Unsafe.ARRAY_INT_BASE_OFFSET, length,
                ArraysSupport.LOG2_ARRAY_INT_INDEX_SCALE);
    }

    @Benchmark
    public int longMismatch() {
        return ArraysSupport.vectorizedMismatch(longs, Unsafe.ARRAY_LONG_BASE_OFFSET,
                otherLongs, Unsafe.ARRAY_LONG_BASE_OFFSET, length,
                ArraysSupport.LOG2_ARRAY_LONG_INDEX_SCALE);
    }
}
