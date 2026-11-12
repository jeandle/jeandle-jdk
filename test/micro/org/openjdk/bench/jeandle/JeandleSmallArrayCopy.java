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
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Prepared sources keep source allocation and initialization outside timing.
 * Length guards describe eight/nine-element inputs without virtual arrays.
 * String[] sources prove every element is assignable to Object.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgsAppend = {
        "-XX:-DoEscapeAnalysis", "-XX:-EliminateAllocations", "-XX:-JeandleDoPEA"})
public class JeandleSmallArrayCopy {
    private static final String[] SOURCE = {
        "zero", "one", "two", "three", "four", "five", "six", "seven",
        "eight", "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen"
    };

    private volatile String[] source8;
    private volatile String[] source9;
    private volatile String[] source10;

    @Setup
    public void setup() {
        String first = new String("first");
        String second = new String("second");
        source8 = new String[] {first, second, first, second, first, second, first, second};
        source9 = new String[] {first, second, first, second, first, second, first, second, first};
        source10 = new String[] {first, second, first, second, first, second, first, second, first, second};
        JeandleCopySupport.checkArray(SOURCE, copyOfObjects8(), 0, 8, Object[].class);
        JeandleCopySupport.checkArray(SOURCE, copyOfObjects9(), 0, 9, Object[].class);
        JeandleCopySupport.checkArray(SOURCE, copyOfStrings8(), 0, 8, String[].class);
        JeandleCopySupport.checkArray(SOURCE, copyOfStrings9(), 0, 9, String[].class);
        JeandleCopySupport.checkArray(SOURCE, copyOfRangeObjects8(), 3, 8, Object[].class);
        JeandleCopySupport.checkArray(SOURCE, copyOfRangeObjects9(), 3, 9, Object[].class);
        JeandleCopySupport.checkArray(SOURCE, arraycopyObjects8(), 0, 8, Object[].class);
        JeandleCopySupport.checkArray(SOURCE, arraycopyObjects9(), 0, 9, Object[].class);
        JeandleCopySupport.checkArray(source8, copyOfPreparedObjects8(), 0, 8, Object[].class);
        JeandleCopySupport.checkArray(source9, copyOfPreparedObjects9(), 0, 9, Object[].class);
        JeandleCopySupport.checkArray(source8, arraycopyPreparedObjects8(), 0, 8, Object[].class);
        JeandleCopySupport.checkArray(source9, arraycopyPreparedObjects9(), 0, 9, Object[].class);
        JeandleCopySupport.checkArray(source9, copyOfRangePreparedObjects8(), 1, 8, Object[].class);
        JeandleCopySupport.checkArray(source10, copyOfRangePreparedObjects9(), 1, 9, Object[].class);
        JeandleCopySupport.checkArray(source9, arraycopyRangePreparedObjects8(), 1, 8, Object[].class);
        JeandleCopySupport.checkArray(source10, arraycopyRangePreparedObjects9(), 1, 9, Object[].class);
        for (Object value : allocateObjects8()) {
            if (value != null) {
                throw new IllegalStateException("Non-null allocation control element");
            }
        }
        for (Object value : allocateObjects9()) {
            if (value != null) {
                throw new IllegalStateException("Non-null allocation control element");
            }
        }
    }

    @Benchmark
    public Object[] copyOfObjects8() {
        return Arrays.copyOf(SOURCE, 8, Object[].class);
    }

    @Benchmark
    public Object[] copyOfObjects9() {
        return Arrays.copyOf(SOURCE, 9, Object[].class);
    }

    @Benchmark
    public String[] copyOfStrings8() {
        return Arrays.copyOf(SOURCE, 8, String[].class);
    }

    @Benchmark
    public String[] copyOfStrings9() {
        return Arrays.copyOf(SOURCE, 9, String[].class);
    }

    @Benchmark
    public Object[] copyOfRangeObjects8() {
        return Arrays.copyOfRange(SOURCE, 3, 11, Object[].class);
    }

    @Benchmark
    public Object[] copyOfRangeObjects9() {
        return Arrays.copyOfRange(SOURCE, 3, 12, Object[].class);
    }

    @Benchmark
    public Object[] arraycopyObjects8() {
        Object[] copy = new Object[8];
        System.arraycopy(SOURCE, 0, copy, 0, 8);
        return copy;
    }

    @Benchmark
    public Object[] arraycopyObjects9() {
        Object[] copy = new Object[9];
        System.arraycopy(SOURCE, 0, copy, 0, 9);
        return copy;
    }

    @Benchmark
    public Object[] copyOfPreparedObjects8() {
        String[] source = source8;
        if (source.length != 8) {
            throw new IllegalStateException("Unexpected source length");
        }
        return Arrays.copyOf(source, 8, Object[].class);
    }

    @Benchmark
    public Object[] copyOfPreparedObjects9() {
        String[] source = source9;
        if (source.length != 9) {
            throw new IllegalStateException("Unexpected source length");
        }
        return Arrays.copyOf(source, 9, Object[].class);
    }

    @Benchmark
    public Object[] arraycopyPreparedObjects8() {
        String[] source = source8;
        if (source.length != 8) {
            throw new IllegalStateException("Unexpected source length");
        }
        Object[] copy = new Object[8];
        System.arraycopy(source, 0, copy, 0, 8);
        return copy;
    }

    @Benchmark
    public Object[] arraycopyPreparedObjects9() {
        String[] source = source9;
        if (source.length != 9) {
            throw new IllegalStateException("Unexpected source length");
        }
        Object[] copy = new Object[9];
        System.arraycopy(source, 0, copy, 0, 9);
        return copy;
    }

    @Benchmark
    public Object[] copyOfRangePreparedObjects8() {
        String[] source = source9;
        if (source.length != 9) {
            throw new IllegalStateException("Unexpected source length");
        }
        return Arrays.copyOfRange(source, 1, 9, Object[].class);
    }

    @Benchmark
    public Object[] copyOfRangePreparedObjects9() {
        String[] source = source10;
        if (source.length != 10) {
            throw new IllegalStateException("Unexpected source length");
        }
        return Arrays.copyOfRange(source, 1, 10, Object[].class);
    }

    @Benchmark
    public Object[] arraycopyRangePreparedObjects8() {
        String[] source = source9;
        if (source.length != 9) {
            throw new IllegalStateException("Unexpected source length");
        }
        Object[] copy = new Object[8];
        System.arraycopy(source, 1, copy, 0, 8);
        return copy;
    }

    @Benchmark
    public Object[] arraycopyRangePreparedObjects9() {
        String[] source = source10;
        if (source.length != 10) {
            throw new IllegalStateException("Unexpected source length");
        }
        Object[] copy = new Object[9];
        System.arraycopy(source, 1, copy, 0, 9);
        return copy;
    }

    @Benchmark
    public Object[] allocateObjects8() {
        return new Object[8];
    }

    @Benchmark
    public Object[] allocateObjects9() {
        return new Object[9];
    }
}

