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
 * Reflective allocation with literal and runtime component mirrors.
 * Separate states keep the component parameter out of literal-mirror and
 * bytecode-allocation cases.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgsAppend = {
        "-XX:-DoEscapeAnalysis", "-XX:-EliminateAllocations", "-XX:-JeandleDoPEA"})
public class JeandleReflectArray {
    @State(Scope.Thread)
    public static class LengthState {
        @Param({"0", "1", "8", "9", "64", "1024"})
        public int size;

        @Setup
        public void setup() {
            checkAllocation(Array.newInstance(byte.class, 0), byte[].class, 0);
            checkAllocation(Array.newInstance(Object.class, 0), Object[].class, 0);
            checkAllocation(Array.newInstance(byte.class, size), byte[].class, size);
            checkAllocation(Array.newInstance(Object.class, size), Object[].class, size);
            checkAllocation(new byte[size], byte[].class, size);
            checkAllocation(new Object[size], Object[].class, size);
        }
    }

    @State(Scope.Thread)
    public static class DynamicState {
        @Param({"0", "1", "8", "9", "64", "1024"})
        public int size;

        @Param({"BOOLEAN", "BYTE", "CHAR", "SHORT", "INT", "LONG", "FLOAT", "DOUBLE",
                "OBJECT", "STRING", "INT_ARRAY", "OBJECT_ARRAY"})
        public String componentName;

        private Class<?> component;

        @Setup
        public void setup() {
            Class<?> expectedArrayClass;
            switch (componentName) {
                case "BOOLEAN":
                    component = boolean.class;
                    expectedArrayClass = boolean[].class;
                    break;
                case "BYTE":
                    component = byte.class;
                    expectedArrayClass = byte[].class;
                    break;
                case "CHAR":
                    component = char.class;
                    expectedArrayClass = char[].class;
                    break;
                case "SHORT":
                    component = short.class;
                    expectedArrayClass = short[].class;
                    break;
                case "INT":
                    component = int.class;
                    expectedArrayClass = int[].class;
                    break;
                case "LONG":
                    component = long.class;
                    expectedArrayClass = long[].class;
                    break;
                case "FLOAT":
                    component = float.class;
                    expectedArrayClass = float[].class;
                    break;
                case "DOUBLE":
                    component = double.class;
                    expectedArrayClass = double[].class;
                    break;
                case "OBJECT":
                    component = Object.class;
                    expectedArrayClass = Object[].class;
                    break;
                case "STRING":
                    component = String.class;
                    expectedArrayClass = String[].class;
                    break;
                case "INT_ARRAY":
                    component = int[].class;
                    expectedArrayClass = int[][].class;
                    break;
                case "OBJECT_ARRAY":
                    component = Object[].class;
                    expectedArrayClass = Object[][].class;
                    break;
                default:
                    throw new IllegalArgumentException("Unknown component " + componentName);
            }
            checkAllocation(Array.newInstance(component, 0), expectedArrayClass, 0);
            checkAllocation(Array.newInstance(component, size), expectedArrayClass, size);
        }
    }

    private static void checkAllocation(Object array, Class<?> expectedClass, int size) {
        if (array == null || array.getClass() != expectedClass || Array.getLength(array) != size) {
            throw new IllegalStateException("Incorrect allocated array type or length");
        }
        Class<?> component = expectedClass.getComponentType();
        Object defaultValue = defaultValue(component);
        for (int i = 0; i < size; i++) {
            Object actual = Array.get(array, i);
            if (component.isPrimitive() ? !defaultValue.equals(actual) : actual != null) {
                throw new IllegalStateException("Non-default allocated array element " + i);
            }
        }
    }

    private static Object defaultValue(Class<?> component) {
        if (component == boolean.class) {
            return false;
        } else if (component == byte.class) {
            return (byte) 0;
        } else if (component == char.class) {
            return (char) 0;
        } else if (component == short.class) {
            return (short) 0;
        } else if (component == int.class) {
            return 0;
        } else if (component == long.class) {
            return 0L;
        } else if (component == float.class) {
            return 0.0f;
        } else if (component == double.class) {
            return 0.0;
        }
        return null;
    }

    @Benchmark
    public byte[] reflectBytes(LengthState state) {
        return (byte[]) Array.newInstance(byte.class, state.size);
    }

    @Benchmark
    public Object[] reflectObjects(LengthState state) {
        return (Object[]) Array.newInstance(Object.class, state.size);
    }

    @Benchmark
    public Object reflectDynamic(DynamicState state) {
        return Array.newInstance(state.component, state.size);
    }

    @Benchmark
    public byte[] allocateBytes(LengthState state) {
        return new byte[state.size];
    }

    @Benchmark
    public Object[] allocateObjects(LengthState state) {
        return new Object[state.size];
    }
}

