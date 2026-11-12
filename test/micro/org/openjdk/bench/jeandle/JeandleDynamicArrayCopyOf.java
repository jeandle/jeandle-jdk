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
 * Target mirrors are loaded from mutable state, so their runtime subtype
 * checks are measured separately from copies with literal class mirrors.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgsAppend = {
        "-XX:-DoEscapeAnalysis", "-XX:-EliminateAllocations", "-XX:-JeandleDoPEA"})
public class JeandleDynamicArrayCopyOf {
    @Param({"8", "64", "1024"})
    public int size;

    @Param({"OBJECT", "STRING", "CHAR_SEQUENCE"})
    public String target;

    private Object[] source;
    private Class<? extends Object[]> targetMirror;
    private int rangeEnd;

    @Setup
    public void setup() {
        source = new Object[size + 1];
        for (int i = 0; i < source.length; i++) {
            source[i] = "element-" + i;
        }
        targetMirror = switch (target) {
            case "OBJECT" -> Object[].class;
            case "STRING" -> String[].class;
            case "CHAR_SEQUENCE" -> CharSequence[].class;
            default -> throw new IllegalArgumentException("Unknown target " + target);
        };
        rangeEnd = size + 1;
        JeandleCopySupport.checkArray(source, copyOf(), 0, size, targetMirror);
        JeandleCopySupport.checkArray(source, copyOfRange(), 1, size, targetMirror);
    }

    @Benchmark
    public Object[] copyOf() {
        return Arrays.copyOf(source, size, targetMirror);
    }

    @Benchmark
    public Object[] copyOfRange() {
        return Arrays.copyOfRange(source, 1, rangeEnd, targetMirror);
    }
}

