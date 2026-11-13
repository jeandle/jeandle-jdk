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
 * or visit www.oracle.com if you have any questions.
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
 * Benchmark for the {@code _vectorizedHashCode} intrinsic via
 * {@code Arrays.hashCode} and {@code String.hashCode} under Jeandle, compared
 * against C2 ({@code -XX:-UseJeandleCompiler} on the same build).
 *
 * Lengths bracket the intrinsic's tiers: 7 stays on the scalar tail, 8 and 16
 * cover short vector blocks, 17/33 include a vector block plus tail,
 * 63 exercises the largest AVX-512 remainder, 64 amortizes tier dispatch, and 256/4096 measure the steady-state vector
 * loops. Data mixes sign bits so byte/char/int paths hash nonzero values.
 *
 * Jeandle replaces C2 in the tier-4 compiler slot, so an A/B is just the
 * -XX:+/-UseJeandleCompiler flag on otherwise identical runs:
 *
 *   make CONF=release test TEST='micro:org.openjdk.bench.jeandle.JeandleVectorizedHashCode' \
 *       MICRO="JAVA_OPTIONS=-Djmh.blackhole.autoDetect=false -Djmh.blackhole.mode=FULL_DONTINLINE;VM_OPTIONS=-XX:-UseJeandleCompiler"
 *
 * JAVA_OPTIONS carries the blackhole policy to the JMH controller JVM (Jeandle
 * has no compiler blackhole yet); keep it identical across all compared runs.
 * Quick runs can subset with {@code -p length=17} / {@code -f 1}.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3)
public class JeandleVectorizedHashCode {

    @Param({"0", "1", "7", "8", "16", "17", "31", "32", "33", "63", "64", "65", "256", "4096"})
    int length;

    // volatile: defeat hoisting the hash (and its loads) out of the JMH stub
    // loop; a fresh field read keeps every iteration's call site dynamic.
    volatile byte[] bytes;
    volatile char[] chars;
    volatile int[] ints;
    volatile int[] scalarInputs;
    volatile char[] latinChars;
    volatile char[] wideChars;
    volatile int salt = 42;
    volatile String latin1;
    volatile String utf16;

    @Setup
    public void setup() {
        byte[] b = new byte[length];
        char[] c = new char[length];
        int[] ia = new int[length];
        for (int i = 0; i < length; i++) {
            b[i] = (byte) (i * 31 + 0x80);
            c[i] = (char) (i * 131 + 0x100);
            ia[i] = i * 0x9E3779B9;
        }
        bytes = b;
        chars = c;
        ints = ia;
        int[] scalar = new int[40];
        for (int i = 0; i < scalar.length; i++) {
            scalar[i] = i * 0x9E3779B9;
        }
        scalarInputs = scalar;
        // latin1 keeps every char <= 0xFF; utf16 needs at least one >= 0x100
        // so the coder actually differs between the two strings.
        char[] latinChars = new char[length];
        char[] wideChars = new char[length];
        for (int i = 0; i < length; i++) {
            latinChars[i] = (char) ((i * 31 + 0x80) & 0xFF);
            wideChars[i] = (char) (i * 131 + 0x100);
        }
        this.latinChars = latinChars;
        this.wideChars = wideChars;
        latin1 = new String(latinChars);
        utf16 = new String(wideChars);
        latin1.hashCode();
        utf16.hashCode();
        if (length > 0 && utf16.indexOf(0x100) < 0) {
            throw new IllegalStateException("utf16 fixture collapsed to latin1");
        }
    }

    @Benchmark
    public int byteArrayHashCode() {
        return Arrays.hashCode(bytes);
    }

    @Benchmark
    public int charArrayHashCode() {
        return Arrays.hashCode(chars);
    }

    @Benchmark
    public int intArrayHashCode() {
        return Arrays.hashCode(ints);
    }

    // First hash, including construction and encoding of the new String.
    @Benchmark
    public int freshStringLatin1HashCode() {
        return new String(latinChars).hashCode();
    }

    @Benchmark
    public int freshStringUtf16HashCode() {
        return new String(wideChars).hashCode();
    }

    @Benchmark
    public int freshArray16HashCode() {
        return Arrays.hashCode(new int[]{salt, 1, 2, 3, 4, 5, 6, 7, 8,
                                         9, 10, 11, 12, 13, 14, 15});
    }

    @Benchmark
    public int freshArray17HashCode() {
        return Arrays.hashCode(new int[]{salt, 1, 2, 3, 4, 5, 6, 7, 8,
                                         9, 10, 11, 12, 13, 14, 15, 16});
    }

    // Dynamic-cell controls for the scalar folding cost budget.
    @Benchmark
    public int freshScalarArray16HashCode() {
        int[] a = scalarInputs;
        return Arrays.hashCode(new int[]{a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7],
                                         a[8], a[9], a[10], a[11], a[12], a[13], a[14], a[15]});
    }

    @Benchmark
    public int freshScalarArray17HashCode() {
        int[] a = scalarInputs;
        return Arrays.hashCode(new int[]{a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7],
                                         a[8], a[9], a[10], a[11], a[12], a[13], a[14], a[15],
                                         a[16]});
    }

    @Benchmark
    public int freshScalarArray32HashCode() {
        int[] a = scalarInputs;
        return Arrays.hashCode(new int[]{a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7],
                                         a[8], a[9], a[10], a[11], a[12], a[13], a[14], a[15],
                                         a[16], a[17], a[18], a[19], a[20], a[21], a[22], a[23],
                                         a[24], a[25], a[26], a[27], a[28], a[29], a[30], a[31]});
    }

    @Benchmark
    public int freshScalarArray40HashCode() {
        int[] a = scalarInputs;
        return Arrays.hashCode(new int[]{a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7],
                                         a[8], a[9], a[10], a[11], a[12], a[13], a[14], a[15],
                                         a[16], a[17], a[18], a[19], a[20], a[21], a[22], a[23],
                                         a[24], a[25], a[26], a[27], a[28], a[29], a[30], a[31],
                                         a[32], a[33], a[34], a[35], a[36], a[37], a[38], a[39]});
    }

    // Cached-hash controls do not measure the intrinsic.
    @Benchmark
    public int cachedStringLatin1HashCode() {
        return latin1.hashCode();
    }

    @Benchmark
    public int cachedStringUtf16HashCode() {
        return utf16.hashCode();
    }
}
