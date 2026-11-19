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

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
public class ClassTypeChecks {
    interface I0 {}
    interface I1 {}
    interface I2 {}
    interface I3 {}
    interface Missing {}
    static class Base {}
    static final class Value extends Base implements I0, I1, I2, I3 {}

    @Param({"identity", "primitiveIdentity", "primaryHit", "primaryMiss",
            "secondaryHit", "secondaryMiss", "secondaryThrash", "arrayCovariance", "mixed"})
    public String distribution;
    private Class<?>[] targets;
    private Class<?>[] sources;
    private int cursor;

    @Setup
    public void setup() {
        targets = new Class<?>[64];
        sources = new Class<?>[64];
        Class<?>[] interfaces = {I0.class, I1.class, I2.class, I3.class};
        Class<?>[] referenceTypes = {String.class, Integer.class, int[].class, Value.class};
        Class<?>[] primitiveTypes = {int.class, long.class, boolean.class, void.class};
        Class<?>[] mixedTargets = {String.class, Base.class, Missing.class, I0.class,
                Object[].class, int[].class, int.class, long.class};
        Class<?>[] mixedSources = {String.class, Value.class, Value.class, Object.class,
                String[].class, long[].class, int.class, int.class};
        Class<?>[] arrayTargets = {Object[].class, String[].class, Cloneable.class, int[].class};
        Class<?>[] arraySources = {String[].class, Object[].class, int[].class, long[].class};
        for (int i = 0; i < 64; ++i) {
            switch (distribution) {
                case "identity" -> targets[i] = sources[i] = referenceTypes[i & 3];
                case "primitiveIdentity" -> targets[i] = sources[i] = primitiveTypes[i & 3];
                case "primaryHit" -> { targets[i] = Base.class; sources[i] = Value.class; }
                case "primaryMiss" -> { targets[i] = String.class; sources[i] = Value.class; }
                case "secondaryHit" -> { targets[i] = I0.class; sources[i] = Value.class; }
                case "secondaryMiss" -> { targets[i] = Missing.class; sources[i] = Value.class; }
                case "secondaryThrash" -> { targets[i] = interfaces[i & 3]; sources[i] = Value.class; }
                case "arrayCovariance" -> { targets[i] = arrayTargets[i & 3]; sources[i] = arraySources[i & 3]; }
                case "mixed" -> { targets[i] = mixedTargets[i & 7]; sources[i] = mixedSources[i & 7]; }
                default -> throw new AssertionError(distribution);
            }
        }
    }

    @Benchmark
    public boolean assignable() {
        int index = cursor++ & 63;
        return targets[index].isAssignableFrom(sources[index]);
    }

    @Benchmark
    public Class<?> inputControl() {
        return sources[cursor++ & 63];
    }
}
