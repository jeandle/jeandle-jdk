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

import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.TimeUnit;
import jdk.internal.misc.Unsafe;
import org.openjdk.jmh.annotations.*;

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
public class ThreadFenceAndSize {
    private static final Unsafe U = Unsafe.getUnsafe();
    private static final MethodHandle CARRIER;
    private static final MethodHandle CACHE;
    private static final MethodHandle SET_CACHE;
    private static volatile Instrumentation instrumentation;

    static {
        try {
            // Reuse the java.base test helper so native calls are parsed inside
            // ordinary Java methods, including when reached through a MethodHandle.
            Class<?> helper = Class.forName("java.lang.JeandleThreadHelper");
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            CARRIER = lookup.findStatic(helper, "carrier", MethodType.methodType(Thread.class));
            CACHE = lookup.findStatic(helper, "cache", MethodType.methodType(Object[].class));
            SET_CACHE = lookup.findStatic(helper, "setCache", MethodType.methodType(void.class, Object[].class));
        } catch (ReflectiveOperationException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    public static void premain(String arguments, Instrumentation value) { instrumentation = value; }

    public static class Payload { long a, b, c, d; }

    @Param({"37", "10000"})
    public int length;
    private Object payload;
    private Object[] savedCache;
    private Object[] cache = {new Object()};
    private long address;
    private int before;
    private int after;

    @Setup
    public void setup() throws Throwable {
        if (instrumentation == null) throw new IllegalStateException("Run with ThreadFenceAndSize as a javaagent");
        payload = length == 37 ? new Payload() : new byte[length];
        savedCache = (Object[]) CACHE.invokeExact();
        SET_CACHE.invokeExact(cache);
        address = U.allocateMemory(128);
        U.setMemory(address, 128, (byte) 0);
    }

    @TearDown
    public void tearDown() throws Throwable {
        SET_CACHE.invokeExact(savedCache);
        U.freeMemory(address);
    }

    @Benchmark
    public Thread carrier() throws Throwable { return (Thread) CARRIER.invokeExact(); }

    @Benchmark
    public Object[] cache() throws Throwable { return (Object[]) CACHE.invokeExact(); }

    @Benchmark
    public Object[] replaceCache() throws Throwable {
        SET_CACHE.invokeExact(cache);
        return (Object[]) CACHE.invokeExact();
    }

    @Benchmark
    public long objectSize() { return instrumentation.getObjectSize(payload); }

    @Benchmark
    public long freshArraySize() { return instrumentation.getObjectSize(new byte[length]); }

    @Benchmark
    public long freshInstanceSize() { return instrumentation.getObjectSize(new Payload()); }

    @Benchmark
    public int storeStoreFence() {
        before = after;
        U.storeStoreFence();
        after = before + 1;
        return after;
    }

    @Benchmark
    public long writeback() {
        U.putLong(address, before++);
        U.writebackMemory(address, 64);
        return U.getLong(address);
    }
}
