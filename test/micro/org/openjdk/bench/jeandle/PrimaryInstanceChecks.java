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
public class PrimaryInstanceChecks {
    static class Base {}
    static class Child extends Base {}

    @Param({"self", "subtype", "miss", "mixed"})
    public String distribution;

    private Object[] values;
    private Object[] arrays;
    private int cursor;

    @Setup
    public void setup() throws ClassNotFoundException {
        Class.forName(Base.class.getName());
        Class.forName(Child.class.getName());
        values = new Object[16];
        arrays = new Object[16];
        for (int i = 0; i < values.length; i++) {
            String kind = distribution.equals("mixed")
                    ? switch (i & 3) {
                        case 0 -> "self";
                        case 1 -> "subtype";
                        case 2 -> "miss";
                        default -> "null";
                    } : distribution;
            switch (kind) {
                case "self" -> {
                    values[i] = new Base();
                    arrays[i] = new Object[i & 3];
                }
                case "subtype" -> {
                    values[i] = new Child();
                    arrays[i] = new String[i & 3];
                }
                case "miss" -> {
                    values[i] = new Object();
                    arrays[i] = new int[i & 3];
                }
                case "null" -> {
                    values[i] = null;
                    arrays[i] = null;
                }
                default -> throw new AssertionError(distribution);
            }
        }
    }

    // Separate self hits from subtype hits to expose the primary-super display
    // lookup cost for same-class Base objects.
    @Benchmark
    public boolean primaryInstance() {
        return Base.class.isInstance(values[cursor++ & 15]);
    }

    @Benchmark
    public boolean arrayInstance() {
        return Object[].class.isInstance(arrays[cursor++ & 15]);
    }
}
