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

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class JeandleStringComparePrefixes {
    @Param({"32", "64", "256"})
    public int length;

    @Param({"LATIN1", "UTF16", "LU", "UL"})
    public String encoding;

    @Param({"FIRST_BLOCK", "LAST_BLOCK"})
    public String mismatch;

    private record Fixture(String left, String right) {}
    private volatile Fixture[] fixtures;
    private int cursor;

    @Setup
    public void setup() {
        Fixture[] prepared = new Fixture[32];
        for (int lane = 0; lane < prepared.length; lane++) {
            char[] left = new char[length];
            for (int i = 0; i < length; i++) {
                left[i] = (char) (128 + ((i + lane) & 63)
                        + (encoding.equals("UTF16") ? 0x8000 : 0));
            }
            char[] right = left.clone();
            int at = mismatch.equals("FIRST_BLOCK") ? lane : length - 1 - lane;
            if (encoding.equals("LU")) {
                right[at] += 0x100;
            } else if (encoding.equals("UL")) {
                left[at] += 0x100;
            } else {
                right[at]++;
            }
            String a = new String(left);
            String b = new String(right);
            int expected = left[at] - right[at];
            if (a.compareTo(b) != expected || b.compareTo(a) != -expected) {
                throw new AssertionError("Invalid common-prefix comparison fixture");
            }
            prepared[lane] = new Fixture(a, b);
        }
        fixtures = prepared;
        cursor = 0;
    }

    @Benchmark
    public int compare() {
        Fixture fixture = fixtures[cursor++ & 31];
        return fixture.left.compareTo(fixture.right);
    }
}
