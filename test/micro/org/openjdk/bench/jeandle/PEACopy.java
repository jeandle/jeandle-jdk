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

import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.CompilerControl;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/** Constant copies, type-check fallbacks and materialization of copied entries. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
public class PEACopy {
    private boolean[] booleans;
    private int[] integers;
    private Object objectIntegers;
    private String first;
    private String second;
    private Object compatible;
    private Object incompatible;
    private Payload payload;
    private static volatile int[] published;

    @Setup
    public void setup() {
        booleans = new boolean[64];
        integers = new int[64];
        for (int i = 0; i < 64; i++) {
            booleans[i] = (i & 1) == 0;
            integers[i] = 17 + i * 13;
        }
        objectIntegers = integers;
        first = new String("first");
        second = new String("second");
        compatible = first;
        incompatible = Integer.valueOf(9);
        payload = new Payload();
        payload.f0 = 17; payload.f1 = 19; payload.f2 = 23; payload.f3 = 29;
        payload.f4 = 31; payload.f5 = 37; payload.f6 = 41; payload.f7 = 43;
        payload.f8 = 47; payload.f9 = 53;
        payload.reference = first;
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public int booleanVirtual() {
        boolean[] source = {booleans[0], booleans[1], booleans[2], booleans[3]};
        boolean[] destination = new boolean[4];
        System.arraycopy(source, 0, destination, 0, 4);
        return (destination[0] ? 1 : 0) + (destination[1] ? 2 : 0)
                + (destination[2] ? 4 : 0) + (destination[3] ? 8 : 0);
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public int booleanConcrete() {
        boolean[] destination = new boolean[4];
        System.arraycopy(booleans, 0, destination, 0, 4);
        return (destination[0] ? 1 : 0) + (destination[1] ? 2 : 0)
                + (destination[2] ? 4 : 0) + (destination[3] ? 8 : 0);
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public int booleanClone() {
        boolean[] source = {booleans[0], booleans[1], booleans[2], booleans[3]};
        boolean[] destination = source.clone();
        return (destination[0] ? 1 : 0) + (destination[1] ? 2 : 0)
                + (destination[2] ? 4 : 0) + (destination[3] ? 8 : 0);
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public int concreteIntImplicitGuard() {
        int[] destination = new int[4];
        System.arraycopy(objectIntegers, 0, destination, 0, 4);
        return destination[0] + destination[1] + destination[2] + destination[3];
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public int concreteIntExplicitGuard() {
        Object source = objectIntegers;
        if (!(source instanceof int[])) {
            return -1;
        }
        int[] destination = new int[4];
        System.arraycopy(source, 0, destination, 0, 4);
        return destination[0] + destination[1] + destination[2] + destination[3];
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public int referenceNarrowingKnown() {
        Object[] source = {first, null, second};
        String[] destination = new String[3];
        System.arraycopy(source, 0, destination, 0, 3);
        return destination[0].length() + (destination[1] == null ? 1 : 0)
                + destination[2].length();
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public int referenceNarrowingUnknown() {
        Object[] source = {first, compatible, second};
        String[] destination = new String[3];
        System.arraycopy(source, 0, destination, 0, 3);
        return destination[0].length() + destination[1].length()
                + destination[2].length();
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public String referenceNarrowingFailure() {
        Object[] source = {first, incompatible, second};
        String[] destination = new String[3];
        try {
            System.arraycopy(source, 0, destination, 0, 3);
            return destination[1];
        } catch (ArrayStoreException expected) {
            return destination[0];
        }
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public int concreteFinalClone() {
        Payload copy = payload.clone();
        return copy.f0 + copy.f1 + copy.f2 + copy.f3 + copy.f4
                + copy.f5 + copy.f6 + copy.f7 + copy.f8 + copy.f9
                + copy.reference.length();
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public int[] escapeReplay() {
        int[] source = {integers[0], integers[1], integers[2], integers[3]};
        int[] destination = new int[4];
        System.arraycopy(source, 0, destination, 0, 4);
        published = destination;
        return destination;
    }

    @State(Scope.Thread)
    public static class Length {
        @Param({"4", "16", "64"})
        public int value;
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public int dynamicLengthFallback(Length input) {
        int length = input.value;
        int[] destination = new int[length];
        System.arraycopy(objectIntegers, 0, destination, 0, length);
        int result = 0;
        for (int i = 0; i < length; i++) {
            result += destination[i];
        }
        return result;
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public int booleanDynamicLengthFallback(Length input) {
        int length = input.value;
        boolean[] destination = new boolean[length];
        System.arraycopy(booleans, 0, destination, 0, length);
        int result = 0;
        for (int i = 0; i < length; i++) {
            result += destination[i] ? 1 : 0;
        }
        return result;
    }

    public static final class Payload implements Cloneable {
        int f0; int f1; int f2; int f3; int f4;
        int f5; int f6; int f7; int f8; int f9;
        String reference;

        @Override
        public Payload clone() {
            try {
                return (Payload) super.clone();
            } catch (CloneNotSupportedException e) {
                throw new AssertionError(e);
            }
        }
    }
}
