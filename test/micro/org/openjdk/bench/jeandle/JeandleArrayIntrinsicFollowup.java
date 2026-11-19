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
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/** Allocation and copy cases that retain runtime lengths at the call site. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
public class JeandleArrayIntrinsicFollowup {
    @State(Scope.Thread)
    public static class ReflectiveState {
        @Param({"131071", "131073", "262145"})
        public int size;
        @Param({"BYTE", "INT", "LONG", "OBJECT"})
        public String type;
        Class<?> component;

        @Setup
        public void setup() {
            component = switch (type) {
                case "BYTE" -> byte.class;
                case "INT" -> int.class;
                case "LONG" -> long.class;
                case "OBJECT" -> Object.class;
                default -> throw new IllegalArgumentException(type);
            };
            Object array = Array.newInstance(component, size);
            if (Array.getLength(array) != size || array.getClass().getComponentType() != component) {
                throw new AssertionError("reflective allocation");
            }
        }
    }

    @State(Scope.Thread)
    public static class CopyState {
        @Param({"8", "1024"})
        public int size;
        Object[] source;
        int exactLength;
        int paddedLength;
        int[] mixedLengths;
        int nextLength;

        @Setup
        public void setup() {
            source = new Object[size];
            Arrays.setAll(source, i -> new Object());
            exactLength = size;
            paddedLength = size + 9;
            mixedLengths = new int[1024];
            Random random = new Random(20261118);
            for (int i = 0; i < mixedLengths.length; i++) {
                mixedLengths[i] = random.nextBoolean() ? exactLength : paddedLength;
            }
            Object[] copy = Arrays.copyOf(source, paddedLength, Object[].class);
            for (int i = 0; i < copy.length; i++) {
                if (copy[i] != (i < source.length ? source[i] : null)) {
                    throw new AssertionError("copy identity or padding");
                }
            }
        }
    }

    @Benchmark
    public Object reflective(ReflectiveState state) {
        return Array.newInstance(state.component, state.size);
    }

    @Benchmark
    public Object[] exactCopy(CopyState state) {
        return Arrays.copyOf(state.source, state.exactLength, Object[].class);
    }

    @Benchmark
    public Object[] paddedCopy(CopyState state) {
        return Arrays.copyOf(state.source, state.paddedLength, Object[].class);
    }

    @Benchmark
    public Object[] mixedCopy(CopyState state) {
        int length = state.mixedLengths[state.nextLength++ & (state.mixedLengths.length - 1)];
        return Arrays.copyOf(state.source, length, Object[].class);
    }
}
