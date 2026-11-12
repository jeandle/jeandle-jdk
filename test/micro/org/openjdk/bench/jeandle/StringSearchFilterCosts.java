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
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/** Sparse approximate records and changes in the selectivity of a filter. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class StringSearchFilterCosts {
    private static final int STATES = 128;
    private static final int LENGTH = 1024;
    private static final int NEEDLE_LENGTH = 64;

    @Param({"LL", "UU", "UL"})
    public String encoding;

    @Param({"SPARSE", "CHANGING_SELECTIVITY"})
    public String profile;

    private final String[] sources = new String[STATES];
    private final String[] targets = new String[STATES];
    private final int[] starts = new int[STATES];
    private int cursor;

    @Setup
    public void setup() {
        if (!encoding.equals("LL") && !encoding.equals("UU") && !encoding.equals("UL")) {
            throw new IllegalArgumentException(encoding);
        }
        if (!profile.equals("SPARSE") && !profile.equals("CHANGING_SELECTIVITY")) {
            throw new IllegalArgumentException(profile);
        }
        for (int state = 0; state < STATES; state++) {
            char base = (char) ((encoding.equals("UU") ? 0x1234 : 'a') + (state & 3) * 8);
            char[] source = new char[LENGTH];
            char[] needle = new char[NEEDLE_LENGTH];
            if (profile.equals("SPARSE")) {
                Arrays.fill(source, (char) (base + 6));
                Arrays.fill(needle, base);
                needle[0] = (char) (base + 1);
                needle[NEEDLE_LENGTH - 1] = (char) (base + 2);
                int shift = (state >>> 2) & 15;
                for (int record = 0; record < 4; record++) {
                    int start = 96 + 192 * record + shift;
                    System.arraycopy(needle, 0, source, start, NEEDLE_LENGTH);
                    source[start + 16 + (state & 15)] = (char) (base + 3);
                }
            } else {
                for (int i = 0; i < source.length; i++) {
                    source[i] = (char) (base + (i & 1));
                }
                for (int i = 0; i < needle.length; i++) {
                    needle[i] = (char) (base + (i & 1));
                }
                // Early exceptional text differs at a position that becomes
                // unselective in the later, regular part of the same source.
                for (int i : new int[] {15, 23, 31}) {
                    source[i] = (char) (base + 2);
                }
                for (int i : new int[] {40, 48, 56}) {
                    needle[i] = (char) (base + 3);
                }
            }
            if (encoding.equals("UL")) {
                source[0] = '\u5000';
            }
            int from = state & 3;
            if (reference(source, needle, from) != -1) {
                throw new AssertionError("Unexpected complete match");
            }
            sources[state] = new String(source);
            targets[state] = new String(needle);
            starts[state] = from;
            if (sources[state].indexOf(targets[state], from) != -1) {
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
