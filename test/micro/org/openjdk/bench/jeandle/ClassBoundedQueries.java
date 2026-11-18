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

import java.io.Serializable;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
public class ClassBoundedQueries {
    private Number number = Integer.valueOf(17);
    private Object[] array = new String[3];
    private Object[] objects = {new Object(), new int[3], "text", new Object[2]};
    private Serializable[] serializables = {"text", new int[3], Integer.valueOf(17), new String[2]};
    private Cloneable[] cloneables = {new CloneValue(), new int[3], new String[2], new CloneChild()};
    private int cursor;

    @Benchmark
    public boolean numberIsArray() { return number.getClass().isArray(); }

    @Benchmark
    public boolean arrayIsArray() { return array.getClass().isArray(); }

    @Benchmark
    public Class<?> numberGetClass() { return number.getClass(); }

    @Benchmark
    public Class<?> arrayGetClass() { return array.getClass(); }

    @Benchmark
    public boolean originIsArray() { return objects[cursor++ & 3].getClass().isArray(); }

    @Benchmark
    public int originModifiers() { return objects[cursor++ & 3].getClass().getModifiers(); }

    @Benchmark
    public boolean originHidden() { return objects[cursor++ & 3].getClass().isHidden(); }

    @Benchmark
    public Class<?> originSuperclass() { return objects[cursor++ & 3].getClass().getSuperclass(); }

    @Benchmark
    public Class<?> originGetClassControl() { return objects[cursor++ & 3].getClass(); }

    @Benchmark
    public boolean serializableIsArray() { return serializables[cursor++ & 3].getClass().isArray(); }

    @Benchmark
    public boolean cloneableIsArray() { return cloneables[cursor++ & 3].getClass().isArray(); }

    @Benchmark
    public Class<?> serializableGetClassControl() { return serializables[cursor++ & 3].getClass(); }

    @Benchmark
    public Class<?> cloneableGetClassControl() { return cloneables[cursor++ & 3].getClass(); }

    static class CloneValue implements Cloneable {}
    static final class CloneChild extends CloneValue {}
}
