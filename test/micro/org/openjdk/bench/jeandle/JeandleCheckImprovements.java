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
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.IterationParams;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
public class JeandleCheckImprovements {
    static volatile Object sink;

    public abstract static class MarkedInput {
        @Setup(Level.Iteration)
        public void beginIteration(IterationParams iteration) {
            markIteration("begin", iteration);
        }

        @TearDown(Level.Iteration)
        public void endIteration(IterationParams iteration) {
            markIteration("end", iteration);
        }

        private void markIteration(String phase, IterationParams iteration) {
            System.out.println("CHECK-ITERATION " + phase + " " + iteration.getType()
                    + " " + System.currentTimeMillis() + " END-CHECK-ITERATION");
        }

        void trial(int size) {
            JeandleBenchmarkSupport.requireJavaBlackhole();
            System.out.println("CHECK-TRIAL " + ProcessHandle.current().pid() + " " + size);
        }
    }

    @State(Scope.Thread)
    public static class ArrayInput extends MarkedInput {
        @Param({"16", "4096"})
        public int size;

        int[] values;
        int[] other;
        int end;
        int sequence;

        @Setup
        public void setup() {
            values = new int[size];
            other = new int[size + 17];
            for (int i = 0; i < values.length; i++) values[i] = 7 * i + 3;
            for (int i = 0; i < other.length; i++) other[i] = 11 * i + 5;
            end = size / 8;
            trial(size);
        }
    }

    @State(Scope.Thread)
    public static class ScaleInput extends ArrayInput {
        @Param({"-3", "0", "3", "5"})
        public int scale;
    }

    @State(Scope.Thread)
    public static class NullInput extends MarkedInput {
        @Param({"0", "2", "1024"})
        public int rate;

        int[] values;
        int sequence;

        @Setup
        public void setup() {
            values = new int[16];
            trial(values.length);
        }
    }

    @Benchmark
    public int fieldBound(ArrayInput input) {
        return fieldBoundKernel(input, input.values, 1);
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public static int fieldBoundKernel(ArrayInput input, int[] a, int offset) {
        int end = input.end;
        int sum = 0;
        for (int i = 0; i < end; i++) sum += a[3 * i + offset];
        return sum;
    }

    @Benchmark
    public int combinedAffine(ArrayInput input) {
        return combinedAffineKernel(input.values, input.end, 1, 2);
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public static int combinedAffineKernel(int[] a, int end, int firstOffset, int secondOffset) {
        int sum = 0;
        for (int i = 0; i < end; i++) {
            sum += a[3 * i + firstOffset] + a[5 * i + secondOffset];
        }
        return sum;
    }

    @Benchmark
    public int dynamicScale(ScaleInput input) {
        int offset = input.scale < 0 ? input.size - 2 : 1;
        return dynamicScaleKernel(input.values, input.end, offset, input.scale);
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public static int dynamicScaleKernel(int[] a, int end, int offset, int scale) {
        int sum = 0;
        for (int i = 0; i < end; i++) sum += a[scale * i + offset];
        return sum;
    }

    @Benchmark
    public int correlatedArray(ArrayInput input) {
        int sequence = input.sequence++;
        return correlatedArrayKernel(input.values, input.other, sequence & (input.size - 1),
                (sequence & 1) == 0);
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public static int correlatedArrayKernel(int[] a, int[] b, int index, boolean first) {
        int value;
        int[] selected;
        if (first) {
            value = a[index];
            touch(a);
            selected = a;
        } else {
            value = b[index];
            touch(b);
            selected = b;
        }
        touch(selected);
        return value + selected[index];
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public static void touch(Object value) {
        sink = value;
    }

    @Benchmark
    public int hotRange(ArrayInput input) {
        int offset = (input.sequence++ & 1) == 0 ? 1 : input.size - 2;
        return hotRangeKernel(input.values, input.end, offset);
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public static int hotRangeKernel(int[] a, int end, int offset) {
        int sum = 0;
        try {
            for (int i = 0; i < end; i++) sum += a[3 * i + offset];
        } catch (ArrayIndexOutOfBoundsException expected) {
            sum ^= 0x55667788;
        }
        return sum;
    }

    @Benchmark
    public int nullRate(NullInput input) {
        int[] value = input.rate != 0 && (input.sequence++ & (input.rate - 1)) == 0
                ? null : input.values;
        return nullKernel(value);
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public static int nullKernel(int[] value) {
        try {
            return value.length;
        } catch (NullPointerException expected) {
            return -1;
        }
    }
}
