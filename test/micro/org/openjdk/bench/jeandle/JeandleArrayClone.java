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
package org.openjdk.bench.jeandle;

import java.util.Arrays;

import java.util.concurrent.TimeUnit;

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

/**
 * Escaping array clones with runtime lengths. All primitive element kinds
 * and shallow reference copies use the same length distribution.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgsAppend = {
        "-XX:-DoEscapeAnalysis", "-XX:-EliminateAllocations", "-XX:-JeandleDoPEA"})
public class JeandleArrayClone {
    @Param({"0", "1", "7", "8", "9", "64", "1024"})
    public int size;

    private boolean[] booleans;
    private byte[] bytes;
    private char[] chars;
    private short[] shorts;
    private int[] ints;
    private long[] longs;
    private float[] floats;
    private double[] doubles;
    private Object[] objects;

    @Setup
    public void setup() {
        booleans = new boolean[size];
        bytes = new byte[size];
        chars = new char[size];
        shorts = new short[size];
        ints = new int[size];
        longs = new long[size];
        floats = new float[size];
        doubles = new double[size];
        objects = new Object[size];
        for (int i = 0; i < size; i++) {
            booleans[i] = (i & 1) == 0;
            bytes[i] = (byte) (i * 31 + 7);
            chars[i] = (char) (i * 13 + 17);
            shorts[i] = (short) (i * 17 + 9);
            ints[i] = i * 31 + 19;
            longs[i] = 0x1234567800000000L + i;
            floats[i] = i + 0.25f;
            doubles[i] = i + 0.125;
            objects[i] = new Object();
        }
        JeandleCopySupport.checkArray(booleans, booleans.clone(), 0, size, boolean[].class);
        JeandleCopySupport.checkArray(bytes, bytes.clone(), 0, size, byte[].class);
        JeandleCopySupport.checkArray(chars, chars.clone(), 0, size, char[].class);
        JeandleCopySupport.checkArray(shorts, shorts.clone(), 0, size, short[].class);
        JeandleCopySupport.checkArray(ints, ints.clone(), 0, size, int[].class);
        JeandleCopySupport.checkArray(longs, longs.clone(), 0, size, long[].class);
        JeandleCopySupport.checkArray(floats, floats.clone(), 0, size, float[].class);
        JeandleCopySupport.checkArray(doubles, doubles.clone(), 0, size, double[].class);
        JeandleCopySupport.checkArray(objects, objects.clone(), 0, size, Object[].class);
        JeandleCopySupport.checkArray(ints, arraycopyInts(), 0, size, int[].class);
        JeandleCopySupport.checkArray(objects, arraycopyObjects(), 0, size, Object[].class);
        if (!Arrays.equals(allocateInts(), new int[size]) ||
                !Arrays.equals(allocateObjects(), new Object[size])) {
            throw new IllegalStateException("Incorrect allocation control");
        }
    }

    @Benchmark
    public boolean[] cloneBooleans() {
        return booleans.clone();
    }

    @Benchmark
    public byte[] cloneBytes() {
        return bytes.clone();
    }

    @Benchmark
    public char[] cloneChars() {
        return chars.clone();
    }

    @Benchmark
    public short[] cloneShorts() {
        return shorts.clone();
    }

    @Benchmark
    public int[] cloneInts() {
        return ints.clone();
    }

    @Benchmark
    public long[] cloneLongs() {
        return longs.clone();
    }

    @Benchmark
    public float[] cloneFloats() {
        return floats.clone();
    }

    @Benchmark
    public double[] cloneDoubles() {
        return doubles.clone();
    }

    @Benchmark
    public Object[] cloneObjects() {
        return objects.clone();
    }

    @Benchmark
    public int[] allocateInts() {
        return new int[size];
    }

    @Benchmark
    public int[] arraycopyInts() {
        int[] copy = new int[size];
        System.arraycopy(ints, 0, copy, 0, size);
        return copy;
    }

    @Benchmark
    public Object[] allocateObjects() {
        return new Object[size];
    }

    @Benchmark
    public Object[] arraycopyObjects() {
        Object[] copy = new Object[size];
        System.arraycopy(objects, 0, copy, 0, size);
        return copy;
    }
}

