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
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you have any questions.
 */
package org.openjdk.bench.jeandle;

import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import jdk.internal.util.ArraysSupport;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * Exercises transitions between vector widths with dynamic slices, and public
 * array hashes with their fixed initial value and complete-array range.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
public class JeandleVectorizedHashCodeTiers {
    @Param({"7", "8", "15", "16", "24", "31", "32", "64", "65", "95", "96", "103", "104", "127", "128", "4096", "65536"})
    public int length;
    public volatile byte[] bytes;
    public volatile byte[] utf16;
    public volatile char[] chars;
    public volatile short[] shorts;
    public volatile int[] ints;
    public volatile byte[] publicBytes;
    public volatile int[] publicInts;
    public volatile int seed = 0x87654321;
    public volatile int offset = 3;

    @Setup
    public void setup() {
        int n = length + 8;
        byte[] b = new byte[n];
        byte[] u = new byte[n * 2];
        char[] c = new char[n];
        short[] s = new short[n];
        int[] a = new int[n];
        boolean little = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;
        for (int i = 0; i < n; i++) {
            int value = (i + 17) * 0x9e3779b9;
            b[i] = (byte) value;
            c[i] = (char) value;
            s[i] = (short) value;
            a[i] = value;
            u[i * 2 + (little ? 0 : 1)] = (byte) value;
            u[i * 2 + (little ? 1 : 0)] = (byte) (value >>> 8);
        }
        bytes = b;
        utf16 = u;
        chars = c;
        shorts = s;
        ints = a;
        publicBytes = Arrays.copyOf(b, length);
        publicInts = Arrays.copyOf(a, length);
    }

    @Benchmark public int signedByte() {
        return ArraysSupport.vectorizedHashCode(bytes, offset, length, seed, ArraysSupport.T_BYTE);
    }
    @Benchmark public int unsignedByte() {
        return ArraysSupport.vectorizedHashCode(bytes, offset, length, seed, ArraysSupport.T_BOOLEAN);
    }
    @Benchmark public int unsignedChar() {
        return ArraysSupport.vectorizedHashCode(chars, offset, length, seed, ArraysSupport.T_CHAR);
    }
    @Benchmark public int signedShort() {
        return ArraysSupport.vectorizedHashCode(shorts, offset, length, seed, ArraysSupport.T_SHORT);
    }
    @Benchmark public int signedInt() {
        return ArraysSupport.vectorizedHashCode(ints, offset, length, seed, ArraysSupport.T_INT);
    }
    @Benchmark public int utf16Bytes() {
        return ArraysSupport.vectorizedHashCode(utf16, offset, length, seed, ArraysSupport.T_CHAR);
    }
    @Benchmark public int publicByte() { return Arrays.hashCode(publicBytes); }
    @Benchmark public int publicInt() { return Arrays.hashCode(publicInts); }
    @Benchmark public int zeroSeedByte() {
        return ArraysSupport.vectorizedHashCode(bytes, offset, length, 0, ArraysSupport.T_BYTE);
    }
}
