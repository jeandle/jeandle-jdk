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

import java.lang.invoke.MethodHandle;
import java.nio.ByteOrder;
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

/** Repeated-prefix searches with a late mismatch in every candidate. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
public class JeandleStringSearchAdversarial {
    @Param({"1024", "16384"})
    public int length;
    @Param({"8", "64", "256"})
    public int needleLength;
    @Param({"LL", "UU", "UL"})
    public String encoding;
    @Param({"MISS", "LAST"})
    public String position;

    private static final int STATES = 8;
    private final byte[][] sources = new byte[STATES][];
    private final byte[][] needles = new byte[STATES][];
    private final String[] strings = new String[STATES];
    private final String[] targets = new String[STATES];
    private int cursor;

    private static final MethodHandle LL = JeandleStringIntrinsics.find("StringLatin1", "indexOf",
            int.class, byte[].class, int.class, byte[].class, int.class, int.class);
    private static final MethodHandle UU = JeandleStringIntrinsics.find("StringUTF16", "indexOf",
            int.class, byte[].class, int.class, byte[].class, int.class, int.class);
    private static final MethodHandle UL = JeandleStringIntrinsics.find("StringUTF16", "indexOfLatin1",
            int.class, byte[].class, int.class, byte[].class, int.class, int.class);

    @Setup
    public void setup() throws Throwable {
        if (needleLength > length) throw new IllegalArgumentException("needle longer than source");
        for (int state = 0; state < STATES; state++) {
            char prefix = (char) ((encoding.equals("UU") ? 0x1234 : 'a') + state * 2);
            char suffix = (char) (prefix + 1);
            char[] source = new char[length];
            char[] needle = new char[needleLength];
            Arrays.fill(source, prefix);
            Arrays.fill(needle, prefix);
            needle[needleLength - 1] = suffix;
            if (encoding.equals("UL")) source[0] = '\u1234';
            if (position.equals("LAST")) source[length - 1] = suffix;
            strings[state] = new String(source);
            targets[state] = new String(needle);
            sources[state] = encode(source, !encoding.equals("LL"));
            needles[state] = encode(needle, encoding.equals("UU"));
            int expected = position.equals("LAST") ? length - needleLength : -1;
            int direct = helper(state);
            int api = strings[state].indexOf(targets[state], 0);
            if (direct != expected || api != expected) {
                throw new AssertionError("search fixture: " + direct + "/" + api + " != " + expected);
            }
        }
    }

    @Benchmark
    public int helperSearch() throws Throwable {
        return helper(cursor++ & (STATES - 1));
    }

    @Benchmark
    public int publicSearch() {
        int state = cursor++ & (STATES - 1);
        return strings[state].indexOf(targets[state], 0);
    }

    private int helper(int state) throws Throwable {
        byte[] source = sources[state];
        byte[] needle = needles[state];
        return switch (encoding) {
            case "LL" -> (int) LL.invokeExact(source, length, needle, needleLength, 0);
            case "UU" -> (int) UU.invokeExact(source, length, needle, needleLength, 0);
            case "UL" -> (int) UL.invokeExact(source, length, needle, needleLength, 0);
            default -> throw new IllegalArgumentException(encoding);
        };
    }

    private static byte[] encode(char[] chars, boolean wide) {
        byte[] bytes = new byte[chars.length * (wide ? 2 : 1)];
        boolean big = ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN;
        for (int i = 0; i < chars.length; i++) {
            if (wide) {
                bytes[2 * i] = (byte) (big ? chars[i] >>> 8 : chars[i]);
                bytes[2 * i + 1] = (byte) (big ? chars[i] : chars[i] >>> 8);
            } else {
                bytes[i] = (byte) chars[i];
            }
        }
        return bytes;
    }
}
