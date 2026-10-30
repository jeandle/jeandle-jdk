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
package org.openjdk.bench.jeandle;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.ByteOrder;
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

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgsAppend = {"--add-opens=java.base/java.lang=ALL-UNNAMED"})
public class JeandleStringCompareLanes {
    @Param({"32", "256"})
    public int length;

    @Param({"FIRST_BLOCK", "LAST_BLOCK", "EQUAL"})
    public String mismatch;

    private static final int FIXTURE_COUNT = 32;
    private static final boolean LITTLE_ENDIAN = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;
    private static final MethodHandle COMPARE_L = find("compareToL");
    private static final MethodHandle COMPARE_U = find("compareToU");
    private static final MethodHandle COMPARE_LU = find("compareToLU");
    private static final MethodHandle COMPARE_UL = find("compareToUL");

    private volatile Fixture[] fixtures;
    private int cursor;

    private record Fixture(byte[] latinLeft, byte[] latinRight,
                           byte[] wideLeft, byte[] wideRight,
                           byte[] mixedLeft, byte[] mixedRight) {}

    @Setup
    public void setup() throws Throwable {
        Fixture[] prepared = new Fixture[FIXTURE_COUNT];
        for (int lane = 0; lane < FIXTURE_COUNT; lane++) {
            byte[] latinLeft = new byte[length];
            byte[] wideLeft = new byte[2 * length];
            byte[] mixedLeft = new byte[2 * length];
            for (int i = 0; i < length; i++) {
                int value = 128 + ((i + lane) & 63);
                latinLeft[i] = (byte) value;
                putChar(wideLeft, i, value + 0x8000);
                putChar(mixedLeft, i, value);
            }
            byte[] latinRight = latinLeft.clone();
            byte[] wideRight = wideLeft.clone();
            byte[] mixedRight = mixedLeft.clone();
            int at = switch (mismatch) {
                case "FIRST_BLOCK" -> lane;
                case "LAST_BLOCK" -> length - 1 - lane;
                default -> -1;
            };
            if (at >= 0) {
                latinRight[at]++;
                putChar(wideRight, at, getChar(wideLeft, at) + 1);
                putChar(mixedRight, at, getChar(mixedLeft, at) + 1);
            }
            Fixture f = new Fixture(latinLeft, latinRight, wideLeft, wideRight, mixedLeft, mixedRight);
            check((int) COMPARE_L.invokeExact(f.latinLeft, f.latinRight),
                    reference(f.latinLeft, f.latinRight, false, false));
            check((int) COMPARE_U.invokeExact(f.wideLeft, f.wideRight),
                    reference(f.wideLeft, f.wideRight, true, true));
            check((int) COMPARE_LU.invokeExact(f.latinLeft, f.mixedRight),
                    reference(f.latinLeft, f.mixedRight, false, true));
            check((int) COMPARE_UL.invokeExact(f.mixedLeft, f.latinRight),
                    reference(f.mixedLeft, f.latinRight, true, false));
            prepared[lane] = f;
        }
        fixtures = prepared;
        cursor = 0;
    }

    private Fixture next() {
        return fixtures[cursor++ & (FIXTURE_COUNT - 1)];
    }

    @Benchmark
    public int compareL() throws Throwable {
        Fixture f = next();
        return (int) COMPARE_L.invokeExact(f.latinLeft, f.latinRight);
    }

    @Benchmark
    public int compareU() throws Throwable {
        Fixture f = next();
        return (int) COMPARE_U.invokeExact(f.wideLeft, f.wideRight);
    }

    @Benchmark
    public int compareLU() throws Throwable {
        Fixture f = next();
        return (int) COMPARE_LU.invokeExact(f.latinLeft, f.mixedRight);
    }

    @Benchmark
    public int compareUL() throws Throwable {
        Fixture f = next();
        return (int) COMPARE_UL.invokeExact(f.mixedLeft, f.latinRight);
    }

    private static int reference(byte[] left, byte[] right, boolean leftWide, boolean rightWide) {
        int count = left.length >> (leftWide ? 1 : 0);
        for (int i = 0; i < count; i++) {
            int a = leftWide ? getChar(left, i) : left[i] & 0xff;
            int b = rightWide ? getChar(right, i) : right[i] & 0xff;
            if (a != b) {
                return a - b;
            }
        }
        return 0;
    }

    private static int getChar(byte[] data, int index) {
        int first = data[2 * index] & 0xff;
        int second = data[2 * index + 1] & 0xff;
        return LITTLE_ENDIAN ? first | (second << 8) : (first << 8) | second;
    }

    private static void putChar(byte[] data, int index, int value) {
        data[2 * index] = (byte) (LITTLE_ENDIAN ? value : value >>> 8);
        data[2 * index + 1] = (byte) (LITTLE_ENDIAN ? value >>> 8 : value);
    }

    private static void check(int actual, int expected) {
        if (actual != expected) {
            throw new IllegalStateException("Invalid comparison fixture: " + actual + " != " + expected);
        }
    }

    private static MethodHandle find(String name) {
        try {
            // The wrapper exposes an ordinary invokestatic to both compilers.
            Class<?> type = Class.forName("java.lang.JeandleStringIntrinsicCaller");
            return MethodHandles.publicLookup().findStatic(type, name,
                    MethodType.methodType(int.class, byte[].class, byte[].class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
