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

import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.IterationParams;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
public class JeandleChecks {
    @Param({"16", "4096"})
    public int size;

    int[] values;
    int[] other;
    int sequence;
    volatile int[] current;
    static volatile int sink;
    static volatile int[] escaped;

    @Setup
    public void setup() {
        JeandleBenchmarkSupport.requireJavaBlackhole();
        Random random = new Random(181126);
        values = new int[size];
        other = new int[size + 1];
        for (int i = 0; i < size; i++) {
            values[i] = random.nextInt();
            other[i] = random.nextInt();
        }
        current = values;
        System.out.println("CHECK-TRIAL " + ProcessHandle.current().pid() + " " + size);
    }

    @Setup(Level.Iteration)
    public void beginIteration(IterationParams iteration) {
        markIteration("begin", iteration);
    }

    @TearDown(Level.Iteration)
    public void endIteration(IterationParams iteration) {
        markIteration("end", iteration);
    }

    private void markIteration(String phase, IterationParams iteration) {
        // Wall time and the log header link each timed window to compiler events.
        System.out.println("CHECK-ITERATION " + phase + " " + iteration.getType()
                + " " + System.currentTimeMillis() + " END-CHECK-ITERATION");
    }

    @Benchmark
    public int forward() {
        int sum = 0;
        for (int i = 0; i < values.length; i++) sum += values[i];
        return sum;
    }

    @Benchmark
    public int reverse() {
        int sum = 0;
        for (int i = values.length - 1; i >= 0; i--) sum += values[i];
        return sum;
    }

    @Benchmark
    public int subrange() {
        return subrangeKernel(values, 1, size - 1);
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public static int subrangeKernel(int[] a, int from, int end) {
        int sum = 0;
        for (int i = from; i < end; i++) sum += a[i];
        return sum;
    }

    @Benchmark
    public int scaled() {
        return scaledKernel(values, (size - 1) / 3, 1);
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public static int scaledKernel(int[] a, int end, int offset) {
        int sum = 0;
        for (int i = 0; i < end; i++) sum += a[3 * i + offset];
        return sum;
    }

    @Benchmark
    public int descending() {
        return descendingKernel(values, -size, size - 1);
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public static int descendingKernel(int[] a, int end, int offset) {
        int sum = 0;
        for (int i = 0; i > end; i -= 2) sum += a[i + offset];
        return sum;
    }

    @Benchmark
    public int knownLength() {
        return knownLengthKernel(Math.min((size - 1) / 3, 341), 1);
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public static int knownLengthKernel(int end, int offset) {
        int[] a = new int[1024];
        escaped = a;
        int sum = 0;
        for (int i = 0; i < end; i++) sum += a[3 * i + offset];
        return sum;
    }

    @Benchmark
    public int lengthMerge() {
        return lengthMergeKernel(values, (sequence++ & 1) == 0);
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public static int lengthMergeKernel(int[] a, boolean branch) {
        int length;
        if (branch) { length = a.length; touch(a, 1); }
        else { touch(a, 2); length = a.length; }
        touch(a, 3);
        return length + a.length;
    }

    @Benchmark
    public int stride() {
        int sum = 0;
        for (int i = 0; i < size; i += 3) sum += values[i];
        return sum;
    }

    @Benchmark
    public int pairs() {
        int sum = 0;
        for (int i = 0; i < size; i++) sum += values[i] + other[i];
        return sum;
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public static void touch(int[] a, int index) {
        a[0] ^= index;
        sink = index;
    }

    @Benchmark
    public int acrossCall() {
        int sum = 0;
        for (int i = 0; i < size; i++) {
            sum += values[i];
            touch(values, i);
            sum += values[i];
        }
        return sum;
    }

    @Benchmark
    public int conditional() {
        int sum = 0;
        for (int i = 0; i < size; i++) {
            int v = values[i];
            if (v >= 0) sum += other[i];
        }
        return sum;
    }

    @Benchmark
    public int mutableReceiver() {
        int sum = 0;
        for (int i = 0; i < size; i++) {
            current = (i & 1) == 0 ? values : other;
            int[] a = current;
            sum += a[i];
        }
        return sum;
    }
}
