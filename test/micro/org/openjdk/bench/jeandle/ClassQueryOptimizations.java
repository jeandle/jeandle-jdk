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
import org.openjdk.jmh.annotations.*;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5)
@Measurement(iterations = 5)
@Fork(3)
@State(Scope.Thread)
public class ClassQueryOptimizations {
    private Object[] objects;
    private Class<?>[] types;
    private Class<?>[] targets;
    private int cursor;

    @Setup
    public void setup() {
        objects = new Object[] {Integer.valueOf(1), "text", new int[4], new A()};
        types = new Class<?>[] {Integer.class, String.class, int[].class, Runnable.class,
                               int.class, Number.class, Object.class, B.class};
        targets = new Class<?>[] {Number.class, CharSequence.class, Object.class, A.class};
        // Resolve and initialize both mirror alternatives before measurement.
        new A();
        new B();
    }

    @Benchmark
    public boolean originAssignable() {
        int index = cursor++;
        return targets[index & 3].isAssignableFrom(objects[(index >>> 2) & 3].getClass());
    }

    @Benchmark
    public int originProperties() {
        Class<?> type = objects[cursor++ & 3].getClass();
        return (type.isPrimitive() ? 1 : 0) | (type.isInterface() ? 2 : 0);
    }

    @Benchmark
    public boolean primaryAssignable() {
        return Number.class.isAssignableFrom(types[cursor++ & 7]);
    }

    @Benchmark
    public Class<?> commonSuperclass() {
        return ((cursor++ & 1) == 0 ? A.class : B.class).getSuperclass();
    }

    @Benchmark
    public Class<?> mixedSuperclass() {
        return ((cursor++ & 1) == 0 ? String.class : Integer.class).getSuperclass();
    }

    @Benchmark
    public Class<?> dynamicSuperclass() {
        return types[cursor++ & 7].getSuperclass();
    }

    @Benchmark
    public Object inputControl() {
        return objects[cursor++ & 3];
    }

    @State(Scope.Thread)
    public static class LoopState {
        @Param({"4", "64"})
        public int count;
        boolean[] choices = {true, false, true, false};
    }

    @Benchmark
    public int commonLoopProperty(LoopState state) {
        Class<?> type = String.class;
        int total = 0;
        for (int i = 0; i < state.count; i++) {
            type = state.choices[i & 3] ? type : Integer.class;
            total += type.isPrimitive() ? 1 : 0;
        }
        return total;
    }

    @Benchmark
    public int mixedLoopProperty(LoopState state) {
        Class<?> type = String.class;
        int total = 0;
        for (int i = 0; i < state.count; i++) {
            type = state.choices[i & 3] ? type : int.class;
            total += type.isPrimitive() ? 1 : 0;
        }
        return total;
    }

    static class Base {}
    static class A extends Base {}
    static class B extends Base {}
}
