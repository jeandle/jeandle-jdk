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
 * Reference copies use literal target mirrors. Shape and type checks have
 * separate methods, avoiding a product of every mirror and length relation.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgsAppend = {
        "-XX:-DoEscapeAnalysis", "-XX:-EliminateAllocations", "-XX:-JeandleDoPEA"})
public class JeandleArrayCopyOf {
    @Param({"0", "1", "7", "8", "9", "64", "1024"})
    public int size;

    private String[] strings;
    private Object[] objects;
    private int shrinkLength;
    private int growLength;
    private int rangeStart;
    private int rangeLength;
    private int rangeShrinkEnd;
    private int rangePaddedEnd;

    @Setup
    public void setup() {
        strings = new String[size];
        objects = new Object[size];
        for (int i = 0; i < size; i++) {
            String value = "element-" + i;
            strings[i] = value;
            objects[i] = value;
        }
        shrinkLength = size / 2;
        growLength = size + 3;
        rangeStart = size == 0 ? 0 : 1;
        rangeLength = size - rangeStart;
        rangeShrinkEnd = rangeStart + rangeLength / 2;
        rangePaddedEnd = size + 3;

        JeandleCopySupport.checkArray(objects, copyOfObjects(), 0, size, Object[].class);
        JeandleCopySupport.checkArray(objects, copyOfShrinkObjects(), 0, shrinkLength, Object[].class);
        JeandleCopySupport.checkArray(objects, copyOfGrowObjects(), 0, growLength, Object[].class);
        JeandleCopySupport.checkArray(strings, copyOfSameType(), 0, size, String[].class);
        JeandleCopySupport.checkArray(strings, copyOfWidened(), 0, size, Object[].class);
        JeandleCopySupport.checkArray(objects, copyOfNarrowed(), 0, size, String[].class);

        JeandleCopySupport.checkArray(objects, copyOfRangeObjects(), rangeStart,
                rangeLength, Object[].class);
        JeandleCopySupport.checkArray(objects, copyOfRangeShrinkObjects(), rangeStart,
                rangeLength / 2, Object[].class);
        JeandleCopySupport.checkArray(objects, copyOfRangePaddedObjects(), rangeStart,
                rangeLength + 3, Object[].class);
        JeandleCopySupport.checkArray(strings, copyOfRangeSameType(), rangeStart,
                rangeLength, String[].class);
        JeandleCopySupport.checkArray(strings, copyOfRangeWidened(), rangeStart,
                rangeLength, Object[].class);
        JeandleCopySupport.checkArray(objects, copyOfRangeNarrowed(), rangeStart,
                rangeLength, String[].class);
        JeandleCopySupport.checkArray(objects, arraycopyObjects(), 0, size, Object[].class);
        JeandleCopySupport.checkArray(objects, arraycopyNarrowed(), 0, size, String[].class);
        for (Object value : allocateObjects()) {
            if (value != null) {
                throw new IllegalStateException("Non-null object allocation control element");
            }
        }
        for (String value : allocateStrings()) {
            if (value != null) {
                throw new IllegalStateException("Non-null string allocation control element");
            }
        }
    }

    @Benchmark
    public Object[] copyOfObjects() {
        return Arrays.copyOf(objects, size, Object[].class);
    }

    @Benchmark
    public Object[] copyOfShrinkObjects() {
        return Arrays.copyOf(objects, shrinkLength, Object[].class);
    }

    @Benchmark
    public Object[] copyOfGrowObjects() {
        return Arrays.copyOf(objects, growLength, Object[].class);
    }

    @Benchmark
    public String[] copyOfSameType() {
        return Arrays.copyOf(strings, size, String[].class);
    }

    @Benchmark
    public Object[] copyOfWidened() {
        return Arrays.copyOf(strings, size, Object[].class);
    }

    @Benchmark
    public String[] copyOfNarrowed() {
        return Arrays.copyOf(objects, size, String[].class);
    }

    @Benchmark
    public Object[] copyOfRangeObjects() {
        return Arrays.copyOfRange(objects, rangeStart, size, Object[].class);
    }

    @Benchmark
    public Object[] copyOfRangeShrinkObjects() {
        return Arrays.copyOfRange(objects, rangeStart, rangeShrinkEnd, Object[].class);
    }

    @Benchmark
    public Object[] copyOfRangePaddedObjects() {
        return Arrays.copyOfRange(objects, rangeStart, rangePaddedEnd, Object[].class);
    }

    @Benchmark
    public String[] copyOfRangeSameType() {
        return Arrays.copyOfRange(strings, rangeStart, size, String[].class);
    }

    @Benchmark
    public Object[] copyOfRangeWidened() {
        return Arrays.copyOfRange(strings, rangeStart, size, Object[].class);
    }

    @Benchmark
    public String[] copyOfRangeNarrowed() {
        return Arrays.copyOfRange(objects, rangeStart, size, String[].class);
    }

    @Benchmark
    public Object[] allocateObjects() {
        return new Object[size];
    }

    @Benchmark
    public String[] allocateStrings() {
        return new String[size];
    }

    @Benchmark
    public Object[] arraycopyObjects() {
        Object[] copy = new Object[size];
        System.arraycopy(objects, 0, copy, 0, size);
        return copy;
    }

    @Benchmark
    public String[] arraycopyNarrowed() {
        String[] copy = new String[size];
        System.arraycopy(objects, 0, copy, 0, size);
        return copy;
    }
}

