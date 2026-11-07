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
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 */
package org.openjdk.bench.jeandle;

import java.lang.invoke.MethodHandle;
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

/** Length guards expose fixed sizes while the arrays remain material and dynamic. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgsAppend = {
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED"})
public class StringKnownLengths {
    @Param({"FIRST", "LAST", "EQUAL"})
    public String mismatch;
    private final byte[][] left = new byte[16][];
    private final byte[][] right = new byte[16][];
    private int cursor;
    private static final MethodHandle EQUALS = find("equalsKnown16", boolean.class);
    private static final MethodHandle COMPARE = find("compareKnown16", int.class);

    private static MethodHandle find(String name, Class<?> result) {
        return StringIntrinsicBridge.find(name, result, byte[].class, byte[].class);
    }

    @Setup
    public void setup() throws Throwable {
        for (int s = 0; s < left.length; s++) {
            left[s] = new byte[16];
            for (int i = 0; i < 16; i++) {
                left[s][i] = (byte) (0x80 + s + i);
            }
            right[s] = left[s].clone();
            if (!mismatch.equals("EQUAL")) {
                right[s][mismatch.equals("FIRST") ? 0 : 15]++;
            }
            int expected = mismatch.equals("EQUAL") ? 0 : -1;
            if ((int) COMPARE.invokeExact(left[s], right[s]) != expected
                    || (boolean) EQUALS.invokeExact(left[s], right[s]) != (expected == 0)) {
                throw new AssertionError("Unexpected fixed-length fixture result");
            }
        }
    }

    @Benchmark
    public boolean equals16() throws Throwable {
        int s = cursor++ & (left.length - 1);
        byte[] a = left[s], b = right[s];
        return (boolean) EQUALS.invokeExact(a, b);
    }

    @Benchmark
    public int compare16() throws Throwable {
        int s = cursor++ & (left.length - 1);
        byte[] a = left[s], b = right[s];
        return (int) COMPARE.invokeExact(a, b);
    }
}
