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

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
public class JeandleCheckGuardCosts {
    private static volatile int[] escaped;
    private static volatile int trainingSink;

    @State(Scope.Thread)
    public static class BoundedInput extends JeandleCheckImprovements.ArrayInput {
        @Param({"-3", "3"})
        public int scale;
    }

    @State(Scope.Thread)
    public static class PretrainedInput extends BoundedInput {
        @Setup(Level.Trial)
        public void trainLongLoop() {
            int[] training = new int[4096];
            for (int i = 0; i < training.length; i++) {
                training[i] = i;
            }
            int offset = scale < 0 ? training.length - 2 : 1;
            for (int i = 0; i < 20_000; i++) {
                trainingSink = boundedLongKernel(
                        training, (training.length - 2) / 3, offset, scale);
            }
        }
    }

    @State(Scope.Thread)
    public static class LocalInput extends JeandleCheckImprovements.MarkedInput {
        @Param({"2", "256"})
        public int trips;

        @Param({"0", "4"})
        public int failureRate;

        int sequence;

        @Setup
        public void setup() {
            trial(trips);
        }
    }

    @Benchmark
    public int boundedLongController(BoundedInput input) {
        int offset = input.scale < 0 ? input.size - 2 : 1;
        return boundedLongKernel(input.values, input.end, offset, input.scale);
    }

    @Benchmark
    public int pretrainedBoundedLongController(PretrainedInput input) {
        int offset = input.scale < 0 ? input.size - 2 : 1;
        return boundedLongKernel(input.values, input.end, offset, input.scale);
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public static int boundedLongKernel(int[] a, int end, int offset, int scale) {
        int sum = 0;
        long limit = Integer.toUnsignedLong(end);
        for (long i = 0; i < limit; i++) {
            sum += a[(int) (offset + i * scale)];
        }
        return sum;
    }

    @Benchmark
    public int constantLengthHandler(LocalInput input) {
        int offset = input.failureRate != 0 && (input.sequence++ & (input.failureRate - 1)) == 0
                ? 1024 : 0;
        return constantHandlerKernel(input.trips, offset, 3);
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public static int constantHandlerKernel(int end, int offset, int scale) {
        int[] a = new int[1024];
        escaped = a;
        touch(a);
        int sum = 0;
        try {
            for (int i = 0; i < end; i++) {
                sum += a[offset + i * scale];
            }
        } catch (ArrayIndexOutOfBoundsException expected) {
            sum ^= 999;
        }
        return sum;
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public static void touch(int[] a) {
        a[0] = 7;
    }
}
