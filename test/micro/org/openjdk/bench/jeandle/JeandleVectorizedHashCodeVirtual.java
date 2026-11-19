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

import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import jdk.internal.util.ArraysSupport;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Hashes fixed-size fresh arrays with independent dynamic cells. Literal
 * allocation sizes allow scalar replacement; each volatile input-array read
 * keeps the cells dynamic in the compiled benchmark. Run compared compilers
 * with the same software Blackhole configuration.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
public class JeandleVectorizedHashCodeVirtual {
    private static final boolean LITTLE_ENDIAN = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;

    volatile int[] inputs;

    @Setup
    public void setup() {
        int[] values = new int[128];
        for (int i = 0; i < values.length; i++) {
            values[i] = (i + 1) * 0x9E3779B9;
        }
        inputs = values;
    }

    @Benchmark
    public int byte16() {
        int[] a = inputs;
        return Arrays.hashCode(new byte[]{
            (byte) a[0], (byte) a[1], (byte) a[2], (byte) a[3], (byte) a[4], (byte) a[5],
            (byte) a[6], (byte) a[7], (byte) a[8], (byte) a[9], (byte) a[10], (byte) a[11],
            (byte) a[12], (byte) a[13], (byte) a[14], (byte) a[15]
        });
    }

    @Benchmark
    public int byte17() {
        int[] a = inputs;
        return Arrays.hashCode(new byte[]{
            (byte) a[0], (byte) a[1], (byte) a[2], (byte) a[3], (byte) a[4], (byte) a[5],
            (byte) a[6], (byte) a[7], (byte) a[8], (byte) a[9], (byte) a[10], (byte) a[11],
            (byte) a[12], (byte) a[13], (byte) a[14], (byte) a[15], (byte) a[16]
        });
    }

    @Benchmark
    public int int16() {
        int[] a = inputs;
        return Arrays.hashCode(new int[]{
            a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7],
            a[8], a[9], a[10], a[11], a[12], a[13], a[14], a[15]
        });
    }

    @Benchmark
    public int int17() {
        int[] a = inputs;
        return Arrays.hashCode(new int[]{
            a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7],
            a[8], a[9], a[10], a[11], a[12], a[13], a[14], a[15],
            a[16]
        });
    }

    @Benchmark
    public int int31() {
        int[] a = inputs;
        return Arrays.hashCode(new int[]{
            a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7],
            a[8], a[9], a[10], a[11], a[12], a[13], a[14], a[15],
            a[16], a[17], a[18], a[19], a[20], a[21], a[22], a[23],
            a[24], a[25], a[26], a[27], a[28], a[29], a[30]
        });
    }

    @Benchmark
    public int constant17() {
        return ArraysSupport.vectorizedHashCode(new int[]{
            7, -1, 0, 5, 31, -128, 255, 32768, -32768,
            1, 2, 3, 4, 5, 6, 7, 8
        }, 0, 17, inputs[0], ArraysSupport.T_INT);
    }

    @Benchmark
    public int mixed17() {
        int[] a = inputs;
        return Arrays.hashCode(new byte[]{
            (byte) a[0], 7, -1, (byte) a[3], (byte) a[4], (byte) a[5], (byte) a[6],
            0, 5, (byte) a[9], (byte) a[10], (byte) a[11], (byte) a[12],
            (byte) a[13], (byte) a[14], (byte) a[15], (byte) a[16]
        });
    }

    @Benchmark
    public int byte21() {
        int[] a = inputs;
        return Arrays.hashCode(new byte[]{
            (byte) a[0], (byte) a[1], (byte) a[2], (byte) a[3], (byte) a[4], (byte) a[5],
            (byte) a[6], (byte) a[7], (byte) a[8], (byte) a[9], (byte) a[10], (byte) a[11],
            (byte) a[12], (byte) a[13], (byte) a[14], (byte) a[15], (byte) a[16], (byte) a[17],
            (byte) a[18], (byte) a[19], (byte) a[20]
        });
    }

    @Benchmark
    public int byte22() {
        int[] a = inputs;
        return Arrays.hashCode(new byte[]{
            (byte) a[0], (byte) a[1], (byte) a[2], (byte) a[3], (byte) a[4], (byte) a[5],
            (byte) a[6], (byte) a[7], (byte) a[8], (byte) a[9], (byte) a[10], (byte) a[11],
            (byte) a[12], (byte) a[13], (byte) a[14], (byte) a[15], (byte) a[16], (byte) a[17],
            (byte) a[18], (byte) a[19], (byte) a[20], (byte) a[21]
        });
    }

    @Benchmark
    public int char21() {
        int[] a = inputs;
        return Arrays.hashCode(new char[]{
            (char) a[0], (char) a[1], (char) a[2], (char) a[3], (char) a[4], (char) a[5],
            (char) a[6], (char) a[7], (char) a[8], (char) a[9], (char) a[10], (char) a[11],
            (char) a[12], (char) a[13], (char) a[14], (char) a[15], (char) a[16], (char) a[17],
            (char) a[18], (char) a[19], (char) a[20]
        });
    }

    @Benchmark
    public int char22() {
        int[] a = inputs;
        return Arrays.hashCode(new char[]{
            (char) a[0], (char) a[1], (char) a[2], (char) a[3], (char) a[4], (char) a[5],
            (char) a[6], (char) a[7], (char) a[8], (char) a[9], (char) a[10], (char) a[11],
            (char) a[12], (char) a[13], (char) a[14], (char) a[15], (char) a[16], (char) a[17],
            (char) a[18], (char) a[19], (char) a[20], (char) a[21]
        });
    }

    @Benchmark
    public int int32() {
        int[] a = inputs;
        return Arrays.hashCode(new int[]{
            a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7],
            a[8], a[9], a[10], a[11], a[12], a[13], a[14], a[15],
            a[16], a[17], a[18], a[19], a[20], a[21], a[22], a[23],
            a[24], a[25], a[26], a[27], a[28], a[29], a[30], a[31]
        });
    }

    @Benchmark
    public int int33() {
        int[] a = inputs;
        return Arrays.hashCode(new int[]{
            a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7],
            a[8], a[9], a[10], a[11], a[12], a[13], a[14], a[15],
            a[16], a[17], a[18], a[19], a[20], a[21], a[22], a[23],
            a[24], a[25], a[26], a[27], a[28], a[29], a[30], a[31],
            a[32]
        });
    }

    @Benchmark
    public int int40() {
        int[] a = inputs;
        return Arrays.hashCode(new int[]{
            a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7],
            a[8], a[9], a[10], a[11], a[12], a[13], a[14], a[15],
            a[16], a[17], a[18], a[19], a[20], a[21], a[22], a[23],
            a[24], a[25], a[26], a[27], a[28], a[29], a[30], a[31],
            a[32], a[33], a[34], a[35], a[36], a[37], a[38], a[39]
        });
    }

    @Benchmark
    public int int64() {
        int[] a = inputs;
        return Arrays.hashCode(new int[]{
            a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7],
            a[8], a[9], a[10], a[11], a[12], a[13], a[14], a[15],
            a[16], a[17], a[18], a[19], a[20], a[21], a[22], a[23],
            a[24], a[25], a[26], a[27], a[28], a[29], a[30], a[31],
            a[32], a[33], a[34], a[35], a[36], a[37], a[38], a[39],
            a[40], a[41], a[42], a[43], a[44], a[45], a[46], a[47],
            a[48], a[49], a[50], a[51], a[52], a[53], a[54], a[55],
            a[56], a[57], a[58], a[59], a[60], a[61], a[62], a[63]
        });
    }

    @Benchmark
    public int int128() {
        int[] a = inputs;
        return Arrays.hashCode(new int[]{
            a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7],
            a[8], a[9], a[10], a[11], a[12], a[13], a[14], a[15],
            a[16], a[17], a[18], a[19], a[20], a[21], a[22], a[23],
            a[24], a[25], a[26], a[27], a[28], a[29], a[30], a[31],
            a[32], a[33], a[34], a[35], a[36], a[37], a[38], a[39],
            a[40], a[41], a[42], a[43], a[44], a[45], a[46], a[47],
            a[48], a[49], a[50], a[51], a[52], a[53], a[54], a[55],
            a[56], a[57], a[58], a[59], a[60], a[61], a[62], a[63],
            a[64], a[65], a[66], a[67], a[68], a[69], a[70], a[71],
            a[72], a[73], a[74], a[75], a[76], a[77], a[78], a[79],
            a[80], a[81], a[82], a[83], a[84], a[85], a[86], a[87],
            a[88], a[89], a[90], a[91], a[92], a[93], a[94], a[95],
            a[96], a[97], a[98], a[99], a[100], a[101], a[102], a[103],
            a[104], a[105], a[106], a[107], a[108], a[109], a[110], a[111],
            a[112], a[113], a[114], a[115], a[116], a[117], a[118], a[119],
            a[120], a[121], a[122], a[123], a[124], a[125], a[126], a[127]
        });
    }

    @Benchmark
    public int dynamicUtf16() {
        int value = inputs[0];
        byte low = (byte) value;
        byte high = (byte) (value >>> 8);
        byte[] bytes = LITTLE_ENDIAN ? new byte[]{low, high} : new byte[]{high, low};
        return ArraysSupport.vectorizedHashCode(bytes, 0, 1, 0, ArraysSupport.T_CHAR);
    }
}
