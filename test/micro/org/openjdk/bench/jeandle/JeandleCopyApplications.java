/*
 * Copyright (c) 2026, Alibaba Group Holding Limited. All Rights Reserved.
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

import java.util.ArrayList;
import java.util.Arrays;
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
 * Application-shaped copy workloads and a sensitivity sweep of copy mixes.
 * The mix percentages are experimental inputs, not estimates of Java-wide
 * operation frequencies. Collection snapshots exercise real JDK call chains.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgsAppend = {"-XX:+JeandleDoPEA"})
public class JeandleCopyApplications {
    private int[] input;
    private Object[] objects;
    private int[] lengths;
    private int cursor;
    private static volatile int[] published;
    private static volatile Object[] snapshotPublished;
    private ArrayList<Object> existing;

    @Setup
    public void setup() {
        input = new int[72];
        objects = new Object[16];
        lengths = new int[]{4, 16, 64};
        for (int i = 0; i < input.length; i++) {
            input[i] = i * 31 + 19;
        }
        for (int i = 0; i < objects.length; i++) {
            objects[i] = new Object();
        }
        existing = new ArrayList<>(Arrays.asList(objects));
    }

    @State(Scope.Thread)
    public static class Mix {
        @Param({"0", "1", "10", "50", "100"})
        public int virtualPercent;
    }

    private int transientCopy() {
        int[] source = new int[16];
        source[0] = input[0];
        source[15] = input[15];
        int[] dest = source.clone();
        return dest[0] + dest[7] + dest[15];
    }

    @Benchmark
    public int mixedCopies(Mix mix) {
        int index = cursor++;
        if (Integer.remainderUnsigned(index, 100) < mix.virtualPercent) {
            return transientCopy();
        }
        switch (index & 3) {
            case 0: {
                int[] dest = new int[16];
                System.arraycopy(input, 2, dest, 0, 16);
                return dest[0] + dest[7] + dest[15];
            }
            case 1: {
                int length = lengths[(index >>> 2) % lengths.length];
                int[] dest = new int[length];
                System.arraycopy(input, 0, dest, 0, length);
                return dest[0] + dest[length - 1];
            }
            case 2: {
                int[] dest = new int[16];
                System.arraycopy(input, 0, dest, 0, 16);
                published = dest;
                return dest[0] + dest[7] + dest[15];
            }
            default: {
                Object[] dest = Arrays.copyOfRange(objects, 2, 14);
                snapshotPublished = dest;
                return (dest[0] == objects[2] ? 1 : 0)
                        + (dest[11] == objects[13] ? 1 : 0);
            }
        }
    }

    @Benchmark
    public Object[] collectionSnapshot() {
        Object[] values = {objects[0], objects[1], objects[2], objects[3],
                           objects[4], objects[5], objects[6], objects[7],
                           objects[8], objects[9], objects[10], objects[11],
                           objects[12], objects[13], objects[14], objects[15]};
        Object[] snapshot = new ArrayList<>(Arrays.asList(values)).toArray();
        snapshotPublished = snapshot;
        return snapshot;
    }

    @Benchmark
    public Object[] existingCollectionSnapshot() {
        Object[] snapshot = existing.toArray();
        snapshotPublished = snapshot;
        return snapshot;
    }
}
