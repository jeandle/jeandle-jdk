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

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
public class FinalInstanceChecks {
    private Object[] values;
    private Class<?>[] targets;
    private int cursor;

    @Setup
    public void setup() {
        values = new Object[] {"one", new Object(), null, new int[4],
                Integer.valueOf(17), new String[2], "two", new long[4],
                "three", new Object[2], new int[2][1], null,
                new String[0], Integer.valueOf(9), new int[0], new long[0]};
        targets = new Class<?>[] {String.class, String.class, Object.class, int[].class,
                Number.class, Object[].class, Runnable.class, int[].class,
                int.class, String[].class, Object[].class, void.class,
                Cloneable.class, String.class, Object.class, long[].class};
    }

    @Benchmark
    public boolean finalStringInstance() {
        return String.class.isInstance(values[cursor++ & 15]);
    }

    @Benchmark
    public boolean primitiveArrayInstance() {
        return int[].class.isInstance(values[cursor++ & 15]);
    }

    @Benchmark
    public boolean referenceArrayInstance() {
        return String[].class.isInstance(values[cursor++ & 15]);
    }

    @Benchmark
    public boolean covariantArrayInstance() {
        return Object[].class.isInstance(values[cursor++ & 15]);
    }

    @Benchmark
    public boolean getClassEqualsConstant() {
        Object value = values[cursor++ & 15];
        return value != null && value.getClass() == String.class;
    }

    @Benchmark
    public boolean getClassEqualsDynamic() {
        int index = cursor++ & 15;
        Object value = values[index];
        Class<?> target = targets[index];
        return value != null && target != null && value.getClass() == target;
    }

    @Benchmark
    public boolean directOriginAssignable() {
        int index = cursor++ & 15;
        Object value = values[index];
        return value != null && targets[index].isAssignableFrom(value.getClass());
    }

    @Benchmark
    public boolean helperOriginAssignable() {
        int index = cursor++ & 15;
        Object value = values[index];
        return value != null && targets[index].isAssignableFrom(typeOf(value));
    }

    // The same input and null guard isolate the getClass-origin work from the
    // subtype check used by both assignable benchmarks.
    @Benchmark
    public boolean originIsInstanceControl() {
        int index = cursor++ & 15;
        Object value = values[index];
        return value != null && targets[index].isInstance(value);
    }

    private static Class<?> typeOf(Object value) {
        return value.getClass();
    }

    @Benchmark
    public Object inputControl() {
        return values[cursor++ & 15];
    }
}
