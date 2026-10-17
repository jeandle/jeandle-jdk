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
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you have additional questions or have any
 * questions.
 */
package org.openjdk.bench.jeandle;

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

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * Benchmark for the {@code _vectorizedMismatch} intrinsic via
 * {@code Arrays.mismatch} under Jeandle, compared against C2
 * ({@code -XX:-UseJeandleCompiler} on the same build).
 *
 * Sizes bracket the inline tiers on byte length: 16 is inside the x86
 * inline medium tier (16-32 bytes), 33 is the first length that falls back
 * to the shared StubRoutines stub on both compilers, and 65536 measures
 * the stub's vector loop. All-equal rows force full-range scans.
 *
 * Jeandle replaces C2 in the tier-4 compiler slot, so an A/B is just the
 * -XX:+/-UseJeandleCompiler flag on otherwise identical runs:
 *
 *   make CONF=release test TEST='micro:org.openjdk.bench.jeandle.JeandleVectorizedMismatch' \
 *       MICRO="VM_OPTIONS=-XX:-UseJeandleCompiler"
 *
 * Quick runs can subset with {@code -p bytes=16} / {@code -f 1}.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3)
public class JeandleVectorizedMismatch {

    @Param({"16", "33", "65536"})
    int bytes;

    private static final int ROWS = 256;

    // volatile: like JeandleArrayEquals, keeps the readnone mismatch call
    // (and the whole row loop) from being hoisted out of the JMH stub loop.
    volatile byte[][] rowsA;
    volatile byte[][] rowsB;
    volatile byte[] singleA;
    volatile byte[] singleB;

    @Setup
    public void setup() {
        byte[][] a = new byte[ROWS][];
        byte[][] b = new byte[ROWS][];
        for (int i = 0; i < ROWS; i++) {
            // Distinct arrays with equal content: no a == b shortcut, and
            // equal content forces the full scan every row.
            a[i] = new byte[bytes];
            b[i] = new byte[bytes];
            Arrays.fill(a[i], (byte) i);
            Arrays.fill(b[i], (byte) i);
        }
        rowsA = a;
        rowsB = b;
        singleA = new byte[bytes];
        singleB = new byte[bytes];
        Arrays.fill(singleA, (byte) 7);
        Arrays.fill(singleB, (byte) 7);
    }

    @Benchmark
    public int rowWise() {
        byte[][] a = rowsA;
        byte[][] b = rowsB;
        int sum = 0;
        for (int i = 0; i < ROWS; i++) {
            sum += Arrays.mismatch(a[i], b[i]);
        }
        return sum;
    }

    @Benchmark
    public int single() {
        return Arrays.mismatch(singleA, singleB);
    }
}
