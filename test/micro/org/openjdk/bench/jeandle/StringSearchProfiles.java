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

import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/** Repeated text with a different internal mismatch position on each input. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class StringSearchProfiles {
    private static final int STATES = 128;

    @Param({"LL", "UU", "UL"})
    public String encoding;

    @Param({"1", "2", "3", "7"})
    public int period;

    @Param({"1024"})
    public int length;

    @Param({"64"})
    public int needleLength;

    @Param({"MISS", "LAST"})
    public String position;

    private final String[] sources = new String[STATES];
    private final String[] targets = new String[STATES];
    private final int[] starts = new int[STATES];
    private int cursor;

    @Setup
    public void setup() {
        if (period <= 0 || period > 7 || needleLength < 4 || length <= needleLength + 3) {
            throw new IllegalArgumentException("Invalid search dimensions");
        }
        if (!encoding.equals("LL") && !encoding.equals("UU") && !encoding.equals("UL")) {
            throw new IllegalArgumentException(encoding);
        }
        if (!position.equals("MISS") && !position.equals("LAST")) {
            throw new IllegalArgumentException(position);
        }
        for (int state = 0; state < STATES; state++) {
            char base = (char) ((encoding.equals("UU") ? 0x1234 : 'a') + (state & 3) * 8);
            char[] source = new char[length];
            char[] needle = new char[needleLength];
            for (int i = 0; i < source.length; i++) {
                source[i] = (char) (base + i % period);
            }
            for (int i = 0; i < needle.length; i++) {
                needle[i] = (char) (base + i % period);
            }
            int mixed = state * 0x9e3779b9;
            int distinguishing = 2 + Integer.remainderUnsigned(mixed, needleLength - 3);
            // The marker is absent from every unmodified source period.
            needle[distinguishing] = (char) (base + period + 5);
            if (encoding.equals("UL")) {
                source[0] = '\u5000';
            }
            int expected = -1;
            if (position.equals("LAST")) {
                expected = length - needleLength;
                System.arraycopy(needle, 0, source, expected, needleLength);
            }
            int from = state & 3;
            if (reference(source, needle, from) != expected) {
                throw new AssertionError("Invalid periodic search fixture");
            }
            sources[state] = new String(source);
            targets[state] = new String(needle);
            starts[state] = from;
            if (sources[state].indexOf(targets[state], from) != expected) {
                throw new AssertionError("Public search disagrees with scalar reference");
            }
        }
    }

    private static int reference(char[] source, char[] target, int from) {
        for (int i = from; i <= source.length - target.length; i++) {
            int j = 0;
            while (j < target.length && source[i + j] == target[j]) {
                j++;
            }
            if (j == target.length) {
                return i;
            }
        }
        return -1;
    }

    @Benchmark
    public int publicSearch() {
        int index = cursor++ & (STATES - 1);
        return sources[index].indexOf(targets[index], starts[index]);
    }
}
