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

import java.lang.reflect.Array;
import java.util.concurrent.TimeUnit;
import jdk.internal.misc.Unsafe;
import org.openjdk.jmh.annotations.*;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
public class JeandleCheckCoverage {
    private static final Unsafe UNSAFE = Unsafe.getUnsafe();
    private static volatile Object escaped;
    public static class Payload { public int value; }

    @State(Scope.Thread)
    public static class Input extends JeandleCheckImprovements.MarkedInput {
        @Param({"16", "4096"})
        public int size;
        int[] values;
        int[] bounds;
        int end;
        int sequence;
        Class<?> mirror;

        @Setup
        public void setup() {
            values = new int[Math.max(1024, size)];
            for (int i = 0; i < values.length; i++) values[i] = 7 * i + 3;
            end = size / 8;
            bounds = new int[] {end};
            mirror = int.class;
            new Payload();
            Array.newInstance(int.class, 0);
            trial(size);
        }
    }

    @Benchmark
    public int arrayBound(Input s) { return arrayBoundKernel(s.values, s.bounds, 1); }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public static int arrayBoundKernel(int[] a, int[] bounds, int offset) {
        int sum = a.length;
        int end = bounds[0];
        for (int i = 0; i < end; i++) sum += a[3 * i + offset];
        return sum;
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public static int getBound(Input s) { return s.end; }

    @Benchmark
    public int callBound(Input s) { return callBoundKernel(s, s.values, 1); }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public static int callBoundKernel(Input s, int[] a, int offset) {
        int sum = a.length;
        int end = getBound(s);
        for (int i = 0; i < end; i++) sum += a[3 * i + offset];
        return sum;
    }

    @Benchmark
    public int nested(Input s) {
        return nestedKernel(s.values, Math.max(2, s.size / 256),
                Math.min(64, s.size / 2), 64 + (s.sequence++ & 1));
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public static int nestedKernel(int[] a, int rows, int columns, int stride) {
        int sum = a.length;
        for (int r = 0; r < rows; r++)
            for (int j = 0; j < columns; j++) sum += a[stride * r + 3 * j];
        return sum;
    }

    @Benchmark
    public int clamped(Input s) {
        int offset = (s.sequence++ & 1) == 0 ? -17 : s.values.length - 17;
        return clampedKernel(s.values, s.size, offset);
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public static int clampedKernel(int[] a, int end, int offset) {
        int length = a.length;
        if (length == 0) return 0;
        int sum = 0;
        for (int i = 0; i < end; i++)
            sum += a[Math.min(Math.max(i + offset, 0), length - 1)];
        return sum;
    }

    @Benchmark
    public int forward(Input s) { return forwardKernel(s); }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public static int forwardKernel(Input s) {
        int sum = 0;
        for (int i = 0; i < s.values.length; i++) sum += s.values[i];
        return sum;
    }

    @Benchmark
    public boolean unsafeAllocation(Input s) throws InstantiationException {
        return unsafeKernel(Payload.class);
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public static boolean unsafeKernel(Class<?> type) throws InstantiationException {
        Object value = UNSAFE.allocateInstance(type);
        escaped = value;
        return value != null;
    }

    @Benchmark
    public boolean reflectionAllocation(Input s) { return reflectionKernel(s.mirror, s.end); }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public static boolean reflectionKernel(Class<?> mirror, int length) {
        Object value = Array.newInstance(mirror, length);
        escaped = value;
        return value != null;
    }

    @Benchmark
    public int outOfRange(Input s) {
        return guardedKernel(s.values, s.end, (s.sequence++ & 1) == 0 ? 1 : Integer.MAX_VALUE);
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public static int guardedKernel(int[] a, int end, int offset) {
        int sum = 0;
        try {
            for (int i = 0; i < end; i++) sum += a[3 * i + offset];
        } catch (ArrayIndexOutOfBoundsException expected) {
            return sum;
        }
        return sum;
    }
}
