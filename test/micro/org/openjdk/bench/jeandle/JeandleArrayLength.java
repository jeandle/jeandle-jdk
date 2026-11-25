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

import java.lang.reflect.Array;
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
 * Reflective length with known array types and an Object receiver that cycles
 * through primitive, reference, and multidimensional arrays.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
public class JeandleArrayLength {
    @Param({"0", "8", "1024"})
    public int size;

    private int[] ints;
    private Object[] objects;
    private Object[] arrays;
    private int cursor;

    @Setup
    public void setup() {
        ints = new int[size];
        objects = new Object[size];
        arrays = new Object[] {ints, objects, new byte[size], new int[size][]};
        for (Object array : arrays) {
            if (Array.getLength(array) != size) {
                throw new IllegalStateException("Incorrect reflective array length");
            }
        }
    }

    @Benchmark
    public int knownPrimitive() {
        return Array.getLength(ints);
    }

    @Benchmark
    public int knownReference() {
        return Array.getLength(objects);
    }

    @Benchmark
    public int opaqueMixed() {
        Object array = arrays[cursor++ & 3];
        return Array.getLength(array);
    }

    @Benchmark
    public int locallyAllocatedReference() {
        return Array.getLength(new Object[size]);
    }

    @Benchmark
    public int locallyAllocatedNonnegative() {
        return Array.getLength(new int[size & 65535]);
    }

    @Benchmark
    public int locallyAllocatedPrimitive() {
        return Array.getLength(new int[size]);
    }
}
