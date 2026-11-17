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

import jdk.internal.misc.Unsafe;

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
import org.openjdk.jmh.infra.Blackhole;

/**
 * Primitive buffers are completely filled before any element is read. The
 * checksum and escaping buffer keep both initialization and allocation live.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgsAppend = {
        "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED"})
public class JeandleUninitializedArray {
    private static final Unsafe U = Unsafe.getUnsafe();

    @Param({"0", "8", "1024"})
    public int size;

    private Class<?> byteClass;
    private Class<?> intClass;

    @Setup
    public void setup() {
        byteClass = byte.class;
        intClass = int.class;
        byte[] bytes = (byte[]) U.allocateUninitializedArray(byteClass, size);
        int[] ints = (int[]) U.allocateUninitializedArray(intClass, size);
        if (bytes.length != size || ints.length != size ||
                fillAndRead(bytes) != 90 * size || fillAndRead(ints) != 90 * size) {
            throw new IllegalStateException("Incorrect initialized primitive buffer");
        }
    }

    @Benchmark
    public byte[] byteConstant(Blackhole bh) {
        byte[] array = (byte[]) U.allocateUninitializedArray(byte.class, size);
        bh.consume(fillAndRead(array));
        return array;
    }

    @Benchmark
    public byte[] byteDynamic(Blackhole bh) {
        byte[] array = (byte[]) U.allocateUninitializedArray(byteClass, size);
        bh.consume(fillAndRead(array));
        return array;
    }

    @Benchmark
    public byte[] byteStandard(Blackhole bh) {
        byte[] array = new byte[size];
        bh.consume(fillAndRead(array));
        return array;
    }

    @Benchmark
    public int[] intConstant(Blackhole bh) {
        int[] array = (int[]) U.allocateUninitializedArray(int.class, size);
        bh.consume(fillAndRead(array));
        return array;
    }

    @Benchmark
    public int[] intDynamic(Blackhole bh) {
        int[] array = (int[]) U.allocateUninitializedArray(intClass, size);
        bh.consume(fillAndRead(array));
        return array;
    }

    @Benchmark
    public int[] intStandard(Blackhole bh) {
        int[] array = new int[size];
        bh.consume(fillAndRead(array));
        return array;
    }

    private static int fillAndRead(byte[] array) {
        Arrays.fill(array, (byte) 90);
        int sum = 0;
        for (byte value : array) {
            sum += value;
        }
        return sum;
    }

    private static int fillAndRead(int[] array) {
        Arrays.fill(array, 90);
        int sum = 0;
        for (int value : array) {
            sum += value;
        }
        return sum;
    }
}
