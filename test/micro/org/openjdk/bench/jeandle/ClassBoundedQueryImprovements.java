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

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
public class ClassBoundedQueryImprovements {
    private final Number[] numbers = {Integer.valueOf(1), Double.valueOf(2), Long.valueOf(3), Float.valueOf(4)};
    private final Object[][] arrays = {new String[1], new Number[2], new Integer[3], new PrivateComponent[4]};
    private final Object[] objects = {"text", new int[1], Integer.valueOf(2), new PrivateComponent()};
    private final Class<?>[] targets = {Number.class, Object.class, Integer.class, CharSequence.class};
    private int cursor;

    @Benchmark
    public boolean boundedAssignable() {
        Number value = numbers[cursor++ & 3];
        return Number.class.isAssignableFrom(value.getClass());
    }

    @Benchmark
    public boolean boundedIsInstance() {
        Number value = numbers[cursor++ & 3];
        Objects.requireNonNull(value);
        return Number.class.isInstance(value);
    }

    @Benchmark
    public boolean dynamicAssignable() {
        int index = cursor++;
        Number value = numbers[index & 3];
        return targets[(index >>> 2) & 3].isAssignableFrom(value.getClass());
    }

    @Benchmark
    public boolean opaqueAssignable() {
        return Number.class.isAssignableFrom(objects[cursor++ & 3].getClass());
    }

    @Benchmark
    public Class<?> arraySuperclass() {
        return arrays[cursor++ & 3].getClass().getSuperclass();
    }

    @Benchmark
    public Class<?> arraySuperclassControl() {
        Objects.requireNonNull(arrays[cursor++ & 3]);
        return Object.class;
    }

    @Benchmark
    public boolean arrayHidden() {
        return arrays[cursor++ & 3].getClass().isHidden();
    }

    @Benchmark
    public boolean arrayHiddenControl() {
        Objects.requireNonNull(arrays[cursor++ & 3]);
        return false;
    }

    @Benchmark
    public int arrayModifiers() {
        return arrays[cursor++ & 3].getClass().getModifiers();
    }

    @Benchmark
    public Class<?> opaqueSuperclass() {
        return objects[cursor++ & 3].getClass().getSuperclass();
    }

    private static class PrivateComponent {}
}
