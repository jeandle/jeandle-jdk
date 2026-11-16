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

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/** Copy consumers with inlined loop polls, dynamic clones and typed copy chains. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgsAppend = {"-XX:+JeandleDoPEA"})
@State(Scope.Thread)
public class PEACopyImprovements {
    @Param({"16", "64", "256"})
    public int length;
    private int[] input;
    private int[] cloneInput;
    private String[] references;

    @Setup
    public void setup() {
        input = new int[72];
        cloneInput = new int[length];
        for (int i = 0; i < cloneInput.length; i++) {
            cloneInput[i] = i * 17 + 13;
        }
        for (int i = 0; i < input.length; i++) {
            input[i] = i * 31 + 19;
        }
        references = new String[] {new String("payload")};
    }

    @Benchmark
    public long consumeCopy16() {
        int[] copy = new int[16];
        System.arraycopy(input, 2, copy, 0, 16);
        long sum = 0;
        for (int i = 0; i < copy.length; i++) {
            sum += copy[i];
        }
        return sum;
    }

    @Benchmark
    public long consumeCopy64() {
        int[] copy = new int[64];
        System.arraycopy(input, 2, copy, 0, 64);
        long sum = 0;
        for (int i = 0; i < copy.length; i++) {
            sum += copy[i];
        }
        return sum;
    }

    @Benchmark
    public int unusedDynamicClone() {
        cloneInput.clone();
        return cloneInput[0];
    }

    @Benchmark
    public int readDynamicClone() {
        int[] copy = cloneInput.clone();
        return cloneInput[0] + copy[0];
    }

    @Benchmark
    public String referenceCopyChain() {
        String[] tmp = new String[1];
        System.arraycopy(references, 0, tmp, 0, 1);
        Object[] box = {tmp[0]};
        String[] copy = new String[1];
        System.arraycopy(box, 0, copy, 0, 1);
        return copy[0];
    }
}
