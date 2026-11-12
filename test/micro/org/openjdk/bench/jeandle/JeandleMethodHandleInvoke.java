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

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
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

/**
 * Public invokeExact calls with constant and mutable MethodHandle targets.
 * Their adapters can exercise the VM's internal invokeBasic protocol; compiler
 * output is needed to establish which adapter calls survive compilation.
 * Direct-call controls perform the same arithmetic on the same runtime input.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgsAppend = {
        "-XX:-DoEscapeAnalysis", "-XX:-EliminateAllocations", "-XX:-JeandleDoPEA"})
public class JeandleMethodHandleInvoke {
    private static final MethodHandle CONSTANT_ADD = findTarget("add");
    private static final MethodHandle CONSTANT_MIX = findTarget("mix");

    private volatile MethodHandle dynamicAdd;
    private volatile MethodHandle dynamicMix;
    private volatile int runtimeInput;

    @Setup
    public void setup() throws Throwable {
        dynamicAdd = findTarget("add");
        dynamicMix = findTarget("mix");
        runtimeInput = 0x12345678;
        int[] values = {Integer.MIN_VALUE, -17, -1, 0, 1, 42, Integer.MAX_VALUE};
        for (int value : values) {
            int expectedAdd = value + 17;
            int expectedMix = (value * 31) ^ (value >>> 3);
            if ((int) CONSTANT_ADD.invokeExact(value) != expectedAdd
                    || (int) dynamicAdd.invokeExact(value) != expectedAdd
                    || add(value) != expectedAdd
                    || (int) CONSTANT_MIX.invokeExact(value) != expectedMix
                    || (int) dynamicMix.invokeExact(value) != expectedMix
                    || mix(value) != expectedMix) {
                throw new IllegalStateException("MethodHandle target result differs");
            }
        }
        if (constantAdd() != directAdd() || mutableAdd() != directAdd()
                || constantMix() != directMix() || mutableMix() != directMix()) {
            throw new IllegalStateException("MethodHandle invocation result differs");
        }
    }

    @Benchmark
    public int constantAdd() throws Throwable {
        return (int) CONSTANT_ADD.invokeExact(runtimeInput);
    }

    @Benchmark
    public int mutableAdd() throws Throwable {
        return (int) dynamicAdd.invokeExact(runtimeInput);
    }

    @Benchmark
    public int directAdd() {
        return add(runtimeInput);
    }

    @Benchmark
    public int constantMix() throws Throwable {
        return (int) CONSTANT_MIX.invokeExact(runtimeInput);
    }

    @Benchmark
    public int mutableMix() throws Throwable {
        return (int) dynamicMix.invokeExact(runtimeInput);
    }

    @Benchmark
    public int directMix() {
        return mix(runtimeInput);
    }

    public static int add(int value) {
        return value + 17;
    }

    public static int mix(int value) {
        return (value * 31) ^ (value >>> 3);
    }

    private static MethodHandle findTarget(String name) {
        try {
            return MethodHandles.lookup().findStatic(JeandleMethodHandleInvoke.class,
                    name, MethodType.methodType(int.class, int.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
