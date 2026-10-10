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
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Benchmark for the {@code _equalsB} / {@code _equalsC} intrinsics
 * ({@code java.util.Arrays.equals(byte[], byte[])} and
 * {@code Arrays.equals(char[], char[])}) under Jeandle, compared against C2.
 *
 * Jeandle lowers both intrinsics to {@code jeandle.memcmp} (the hand
 * vectorized template in templatemodule/template.ll, bcmp semantics: 32-byte
 * vector loop with overlapping tail, 8-byte path, byte loop under 8). C2
 * expands the same
 * intrinsics with its own vectorized-mismatch-style code. This harness
 * exercises every dispatch path and both early-exit extremes.
 *
 * Jeandle replaces C2 in the tier-4 compiler slot, so an A/B is just the
 * -XX:+/-UseJeandleCompiler flag on otherwise identical runs. Run the
 * benchmarks.jar twice and diff, e.g.:
 *
 *   $JDK/bin/java -jar benchmarks.jar JeandleArrayEquals \
 *       -jvmArgsAppend "-XX:+UseJeandleCompiler"
 *   $JDK/bin/java -jar benchmarks.jar JeandleArrayEquals \
 *       -jvmArgsAppend "-XX:-UseJeandleCompiler"
 *
 * or through the build:
 *
 *   make CONF=release test TEST='micro:org.openjdk.bench.jeandle.JeandleArrayEquals' \
 *       MICRO_VM_OPTIONS="-XX:-UseJeandleCompiler"
 *
 * The default sizes cover the memcmp template dispatch on byte length
 * (7 &lt; 8: byte loop; 11: qword loop; &gt;= 32: vector loop),
 * a typical small-array case, and large arrays where the vector loop
 * dominates. char[] doubles the byte length. Quick runs can subset with
 * {@code -p size=80} / {@code -f 1}.
 *
 * Note: with Jeandle's default flags, PEA disables compressed oops, so the
 * two configurations do not have identical heap encodings. Array payload
 * offsets are unaffected, which keeps the memcmp loop itself comparable.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3)
public class JeandleArrayEquals {

    @Param({"7", "11", "80", "1024", "8192"})
    int size;

    // volatile: without it, LLVM hoists the readnone memcmp out of the JMH
    // stub loop (nothing in the loop writes the arrays once the blackhole
    // stores are scalar-replaced), and the benchmark measures nothing. A
    // volatile load must stay in the loop. C2 does not perform the hoist,
    // so this affects both compilers symmetrically and adds ~1 cycle/op.
    volatile byte[] bytesA;
    volatile byte[] bytesEqual;
    volatile byte[] bytesDiffFirst;
    volatile byte[] bytesDiffMid;
    volatile byte[] bytesDiffLast;

    volatile char[] charsA;
    volatile char[] charsEqual;
    volatile char[] charsDiffFirst;
    volatile char[] charsDiffMid;
    volatile char[] charsDiffLast;

    @Setup
    public void setup() {
        Random r = new Random(42);

        bytesA = new byte[size];
        r.nextBytes(bytesA);

        // Distinct arrays with equal content: the a == b pointer-equality
        // fast path of the intrinsic must not trivially win.
        bytesEqual = bytesA.clone();
        bytesDiffFirst = bytesA.clone();
        bytesDiffFirst[0] ^= 1;
        bytesDiffMid = bytesA.clone();
        bytesDiffMid[size / 2] ^= 1;
        bytesDiffLast = bytesA.clone();
        bytesDiffLast[size - 1] ^= 1;

        charsA = new char[size];
        for (int i = 0; i < size; i++) {
            charsA[i] = (char) ('a' + r.nextInt(26));
        }

        charsEqual = charsA.clone();
        charsDiffFirst = charsA.clone();
        charsDiffFirst[0] ^= 1;
        charsDiffMid = charsA.clone();
        charsDiffMid[size / 2] ^= 1;
        charsDiffLast = charsA.clone();
        charsDiffLast[size - 1] ^= 1;
    }

    // ---- byte[]: _equalsB ----

    @Benchmark
    public boolean byteEqual() {
        return Arrays.equals(bytesA, bytesEqual);
    }

    @Benchmark
    public boolean byteDifferFirst() {
        return Arrays.equals(bytesA, bytesDiffFirst);
    }

    @Benchmark
    public boolean byteDifferMid() {
        return Arrays.equals(bytesA, bytesDiffMid);
    }

    @Benchmark
    public boolean byteDifferLast() {
        return Arrays.equals(bytesA, bytesDiffLast);
    }

    // ---- char[]: _equalsC ----

    @Benchmark
    public boolean charEqual() {
        return Arrays.equals(charsA, charsEqual);
    }

    @Benchmark
    public boolean charDifferFirst() {
        return Arrays.equals(charsA, charsDiffFirst);
    }

    @Benchmark
    public boolean charDifferMid() {
        return Arrays.equals(charsA, charsDiffMid);
    }

    @Benchmark
    public boolean charDifferLast() {
        return Arrays.equals(charsA, charsDiffLast);
    }
}
