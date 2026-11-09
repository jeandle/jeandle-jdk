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

/** A short irregular prefix followed by repetitive text changes filter selectivity. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
public class StringSearchTransitions {
    @Param({"1024", "16384"})
    public int length;
    @Param({"64", "512"})
    public int needleLength;
    @Param({"LL", "UU", "UL"})
    public String encoding;
    @Param({"MISS", "LAST"})
    public String position;

    private final String[] sources = new String[16];
    private final String[] targets = new String[16];
    private int cursor;

    @Setup
    public void setup() {
        for (int state = 0; state < sources.length; state++) {
            char base = (char) ((encoding.equals("UU") ? 0x1234 : 'a') + state * 4);
            char[] source = new char[length];
            char[] target = new char[needleLength];
            Arrays.fill(source, base);
            Arrays.fill(target, base);
            for (int i = 2; i < 24; i += 3) {
                source[i] = (char) (base + 1);
            }
            target[needleLength / 2] = (char) (base + 2);
            if (encoding.equals("UL")) {
                source[0] = '\u5000';
            }
            int expected = -1;
            if (position.equals("LAST")) {
                expected = length - needleLength;
                System.arraycopy(target, 0, source, expected, needleLength);
            }
            sources[state] = new String(source);
            targets[state] = new String(target);
            if (sources[state].indexOf(targets[state]) != expected) {
                throw new AssertionError("Unexpected transition fixture result");
            }
        }
    }

    @Benchmark
    public int search() {
        int state = cursor++ & (sources.length - 1);
        return sources[state].indexOf(targets[state]);
    }
}
