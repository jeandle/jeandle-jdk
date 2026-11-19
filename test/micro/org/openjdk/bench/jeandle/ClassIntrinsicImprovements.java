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
import jdk.internal.reflect.Reflection;
import org.openjdk.jmh.annotations.*;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgsAppend = "--add-exports=java.base/jdk.internal.reflect=ALL-UNNAMED")
@State(Scope.Thread)
public class ClassIntrinsicImprovements {
    interface Marker { }
    static class Base { }
    static final class Value extends Base implements Marker {
        final int number;
        Value(int number) { this.number = number; }
    }

    int index;
    Class<?> target = Marker.class;
    Class<?> failureTarget = String.class;
    Class<?>[] mirrors = {String.class, int[].class, int.class, Object[].class};
    Object[] values = {new Value(37), new Object(), null, "value"};

    @Benchmark
    public int mergedProperties() {
        boolean choice = (index++ & 1) == 0;
        return (choice ? String.class : Integer.class).getModifiers()
                + Reflection.getClassAccessFlags(choice ? int.class : void.class)
                + ((choice ? String[].class : int[].class).isArray() ? 1 : 0)
                + ((choice ? Runnable.class : Cloneable.class).isInterface() ? 2 : 0)
                + ((choice ? int.class : void.class).isPrimitive() ? 4 : 0)
                + ((choice ? String.class : Integer.class).isHidden() ? 8 : 0);
    }

    @Benchmark
    public boolean primaryInstance() {
        return Base.class.isInstance(values[index++ & 3]);
    }

    @Benchmark
    public int virtualCast() {
        Value value = new Value(index++);
        Object alias = target.cast(value);
        return alias == value ? value.number : -1;
    }

    @Benchmark
    public Class<?> failedCast() {
        try {
            return failureTarget.cast(values[0]).getClass();
        } catch (ClassCastException expected) {
            return expected.getClass();
        }
    }

    @Benchmark
    public boolean dynamicProperty() {
        return mirrors[index++ & 3].isArray();
    }
}
