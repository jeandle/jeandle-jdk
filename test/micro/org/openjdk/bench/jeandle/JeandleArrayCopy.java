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
import java.util.function.Supplier;

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
 * Copies into existing arrays separate copying and barrier costs from allocation.
 * Offset copies use source index 1 and destination index 3, changing payload
 * alignment where the element width permits it. Overlap cases move elements
 * left or right within one array.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgsAppend = {
        "-XX:-DoEscapeAnalysis", "-XX:-EliminateAllocations", "-XX:-JeandleDoPEA"})
public class JeandleArrayCopy {
    @Param({"0", "1", "7", "8", "9", "64", "1024"})
    public int size;

    private boolean[] booleansSource;
    private boolean[] booleansDestination;
    private byte[] bytesSource;
    private byte[] bytesDestination;
    private char[] charsSource;
    private char[] charsDestination;
    private short[] shortsSource;
    private short[] shortsDestination;
    private int[] intsSource;
    private int[] intsDestination;
    private long[] longsSource;
    private long[] longsDestination;
    private float[] floatsSource;
    private float[] floatsDestination;
    private double[] doublesSource;
    private double[] doublesDestination;
    private Object[] objectsSource;
    private Object[] objectsDestination;
    private Object[] checkedSource;
    private String[] checkedDestination;
    private int[] intsOverlapLeft;
    private int[] intsOverlapRight;
    private Object[] objectsOverlapLeft;
    private Object[] objectsOverlapRight;

    @Setup
    public void setup() {
        int length = size + 4;
        booleansSource = new boolean[length];
        booleansDestination = new boolean[length];
        bytesSource = new byte[length];
        bytesDestination = new byte[length];
        charsSource = new char[length];
        charsDestination = new char[length];
        shortsSource = new short[length];
        shortsDestination = new short[length];
        intsSource = new int[length];
        intsDestination = new int[length];
        longsSource = new long[length];
        longsDestination = new long[length];
        floatsSource = new float[length];
        floatsDestination = new float[length];
        doublesSource = new double[length];
        doublesDestination = new double[length];
        objectsSource = new Object[length];
        objectsDestination = new Object[length];
        checkedSource = new Object[length];
        checkedDestination = new String[length];
        intsOverlapLeft = new int[length];
        intsOverlapRight = new int[length];
        objectsOverlapLeft = new Object[length];
        objectsOverlapRight = new Object[length];
        for (int i = 0; i < length; i++) {
            booleansSource[i] = (i & 1) == 0;
            booleansDestination[i] = i % 3 == 0;
            bytesSource[i] = (byte) (i * 31 + 7);
            bytesDestination[i] = (byte) (-i - 1);
            charsSource[i] = (char) (i * 13 + 17);
            charsDestination[i] = (char) (i + 100);
            shortsSource[i] = (short) (i * 17 + 9);
            shortsDestination[i] = (short) (-i - 1);
            intsSource[i] = i * 31 + 19;
            intsDestination[i] = -i - 1;
            longsSource[i] = 0x1234567800000000L + i;
            longsDestination[i] = -i - 1L;
            floatsSource[i] = i + 0.25f;
            floatsDestination[i] = -i - 0.5f;
            doublesSource[i] = i + 0.125;
            doublesDestination[i] = -i - 0.5;
            objectsSource[i] = new Object();
            objectsDestination[i] = new Object();
            checkedSource[i] = "source-" + i;
            checkedDestination[i] = "destination-" + i;
        }
        resetOverlapFixtures();
        checkCopy(booleansSource, booleansDestination, 0, 0, this::copyBooleans);
        checkCopy(booleansSource, booleansDestination, 1, 3, this::copyBooleansOffset);
        checkCopy(bytesSource, bytesDestination, 0, 0, this::copyBytes);
        checkCopy(bytesSource, bytesDestination, 1, 3, this::copyBytesOffset);
        checkCopy(charsSource, charsDestination, 0, 0, this::copyChars);
        checkCopy(charsSource, charsDestination, 1, 3, this::copyCharsOffset);
        checkCopy(shortsSource, shortsDestination, 0, 0, this::copyShorts);
        checkCopy(shortsSource, shortsDestination, 1, 3, this::copyShortsOffset);
        checkCopy(intsSource, intsDestination, 0, 0, this::copyInts);
        checkCopy(intsSource, intsDestination, 1, 3, this::copyIntsOffset);
        checkCopy(longsSource, longsDestination, 0, 0, this::copyLongs);
        checkCopy(longsSource, longsDestination, 1, 3, this::copyLongsOffset);
        checkCopy(floatsSource, floatsDestination, 0, 0, this::copyFloats);
        checkCopy(floatsSource, floatsDestination, 1, 3, this::copyFloatsOffset);
        checkCopy(doublesSource, doublesDestination, 0, 0, this::copyDoubles);
        checkCopy(doublesSource, doublesDestination, 1, 3, this::copyDoublesOffset);
        checkCopy(objectsSource, objectsDestination, 0, 0, this::copyObjects);
        checkCopy(objectsSource, objectsDestination, 1, 3, this::copyObjectsOffset);
        checkCopy(checkedSource, checkedDestination, 0, 0, this::copyCheckedReferences);
        checkCopy(checkedSource, checkedDestination, 1, 3, this::copyCheckedReferencesOffset);
        checkCopy(intsOverlapLeft, intsOverlapLeft, 1, 0, this::copyIntsOverlapLeft);
        checkCopy(intsOverlapRight, intsOverlapRight, 0, 1, this::copyIntsOverlapRight);
        checkCopy(objectsOverlapLeft, objectsOverlapLeft, 1, 0, this::copyObjectsOverlapLeft);
        checkCopy(objectsOverlapRight, objectsOverlapRight, 0, 1, this::copyObjectsOverlapRight);
        resetOverlapFixtures();
    }

    private void resetOverlapFixtures() {
        for (int i = 0; i < intsOverlapLeft.length; i++) {
            intsOverlapLeft[i] = i * 31 + 19;
            intsOverlapRight[i] = i * 31 + 19;
            objectsOverlapLeft[i] = new Object();
            objectsOverlapRight[i] = new Object();
        }
    }

    private void checkCopy(Object source, Object destination, int sourceOffset,
                           int destinationOffset, Supplier<Object> operation) {
        int length = Array.getLength(destination);
        Class<?> component = destination.getClass().getComponentType();
        Object expected = Array.newInstance(component, length);
        for (int i = 0; i < length; i++) {
            Array.set(expected, i, Array.get(destination, i));
        }
        for (int i = 0; i < size; i++) {
            Array.set(expected, destinationOffset + i, Array.get(source, sourceOffset + i));
        }
        if (operation.get() != destination) {
            throw new IllegalStateException("Copy did not return the destination");
        }
        for (int i = 0; i < length; i++) {
            Object actualValue = Array.get(destination, i);
            Object expectedValue = Array.get(expected, i);
            if (component.isPrimitive() ? !actualValue.equals(expectedValue)
                    : actualValue != expectedValue) {
                throw new IllegalStateException("Incorrect destination element " + i);
            }
        }
    }

    @Benchmark
    public boolean[] copyBooleans() {
        System.arraycopy(booleansSource, 0, booleansDestination, 0, size);
        return booleansDestination;
    }

    @Benchmark
    public boolean[] copyBooleansOffset() {
        System.arraycopy(booleansSource, 1, booleansDestination, 3, size);
        return booleansDestination;
    }

    @Benchmark
    public byte[] copyBytes() {
        System.arraycopy(bytesSource, 0, bytesDestination, 0, size);
        return bytesDestination;
    }

    @Benchmark
    public byte[] copyBytesOffset() {
        System.arraycopy(bytesSource, 1, bytesDestination, 3, size);
        return bytesDestination;
    }

    @Benchmark
    public char[] copyChars() {
        System.arraycopy(charsSource, 0, charsDestination, 0, size);
        return charsDestination;
    }

    @Benchmark
    public char[] copyCharsOffset() {
        System.arraycopy(charsSource, 1, charsDestination, 3, size);
        return charsDestination;
    }

    @Benchmark
    public short[] copyShorts() {
        System.arraycopy(shortsSource, 0, shortsDestination, 0, size);
        return shortsDestination;
    }

    @Benchmark
    public short[] copyShortsOffset() {
        System.arraycopy(shortsSource, 1, shortsDestination, 3, size);
        return shortsDestination;
    }

    @Benchmark
    public int[] copyInts() {
        System.arraycopy(intsSource, 0, intsDestination, 0, size);
        return intsDestination;
    }

    @Benchmark
    public int[] copyIntsOffset() {
        System.arraycopy(intsSource, 1, intsDestination, 3, size);
        return intsDestination;
    }

    @Benchmark
    public long[] copyLongs() {
        System.arraycopy(longsSource, 0, longsDestination, 0, size);
        return longsDestination;
    }

    @Benchmark
    public long[] copyLongsOffset() {
        System.arraycopy(longsSource, 1, longsDestination, 3, size);
        return longsDestination;
    }

    @Benchmark
    public float[] copyFloats() {
        System.arraycopy(floatsSource, 0, floatsDestination, 0, size);
        return floatsDestination;
    }

    @Benchmark
    public float[] copyFloatsOffset() {
        System.arraycopy(floatsSource, 1, floatsDestination, 3, size);
        return floatsDestination;
    }

    @Benchmark
    public double[] copyDoubles() {
        System.arraycopy(doublesSource, 0, doublesDestination, 0, size);
        return doublesDestination;
    }

    @Benchmark
    public double[] copyDoublesOffset() {
        System.arraycopy(doublesSource, 1, doublesDestination, 3, size);
        return doublesDestination;
    }

    @Benchmark
    public Object[] copyObjects() {
        System.arraycopy(objectsSource, 0, objectsDestination, 0, size);
        return objectsDestination;
    }

    @Benchmark
    public Object[] copyObjectsOffset() {
        System.arraycopy(objectsSource, 1, objectsDestination, 3, size);
        return objectsDestination;
    }

    @Benchmark
    public String[] copyCheckedReferences() {
        System.arraycopy(checkedSource, 0, checkedDestination, 0, size);
        return checkedDestination;
    }

    @Benchmark
    public String[] copyCheckedReferencesOffset() {
        System.arraycopy(checkedSource, 1, checkedDestination, 3, size);
        return checkedDestination;
    }

    @Benchmark
    public int[] copyIntsOverlapLeft() {
        System.arraycopy(intsOverlapLeft, 1, intsOverlapLeft, 0, size);
        return intsOverlapLeft;
    }

    @Benchmark
    public int[] copyIntsOverlapRight() {
        System.arraycopy(intsOverlapRight, 0, intsOverlapRight, 1, size);
        return intsOverlapRight;
    }

    @Benchmark
    public Object[] copyObjectsOverlapLeft() {
        System.arraycopy(objectsOverlapLeft, 1, objectsOverlapLeft, 0, size);
        return objectsOverlapLeft;
    }

    @Benchmark
    public Object[] copyObjectsOverlapRight() {
        System.arraycopy(objectsOverlapRight, 0, objectsOverlapRight, 1, size);
        return objectsOverlapRight;
    }
}

