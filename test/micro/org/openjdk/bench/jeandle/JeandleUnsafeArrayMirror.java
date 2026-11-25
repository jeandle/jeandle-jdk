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

import java.lang.reflect.Array;
import java.util.concurrent.TimeUnit;
import jdk.internal.misc.Unsafe;
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

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgsAppend = {"--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED"})
public class JeandleUnsafeArrayMirror {
    private static final Unsafe U = Unsafe.getUnsafe();

    @Param({"BYTE", "BYTE_INT", "ALL"})
    public String distribution;

    @Param({"0", "8", "1024", "65536"})
    public int size;

    private Class<?>[] types;
    private Object[] initialized;
    private int cursor;

    @Setup
    public void setup() {
        types = switch (distribution) {
            case "BYTE" -> new Class<?>[] {byte.class};
            case "BYTE_INT" -> new Class<?>[] {byte.class, int.class};
            case "ALL" -> new Class<?>[] {byte.class, boolean.class, short.class, char.class,
                    int.class, float.class, long.class, double.class};
            default -> throw new IllegalArgumentException(distribution);
        };
        initialized = new Object[types.length];
        for (int i = 0; i < types.length; ++i) {
            initialized[i] = Array.newInstance(types[i], size);
        }
    }

    @Benchmark
    public Object allocate() {
        return U.allocateUninitializedArray(types[cursor++ & (types.length - 1)], size);
    }

    @Benchmark
    public Object allocateAndInitialize() {
        int index = cursor++ & (types.length - 1);
        Object a = U.allocateUninitializedArray(types[index], size);
        System.arraycopy(initialized[index], 0, a, 0, size);
        return a;
    }
}
