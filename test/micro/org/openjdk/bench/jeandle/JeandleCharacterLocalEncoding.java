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
 */

package org.openjdk.bench.jeandle;

import java.lang.invoke.MethodHandle;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/** Local allocation elimination and the cost boundaries of encoding exposure. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgsAppend = {
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED"})
public class JeandleCharacterLocalEncoding {
    private static final boolean LITTLE = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;
    private static final MethodHandle ASCII = StringIntrinsicBridge.find("encodeASCII", int.class,
            char[].class, int.class, byte[].class, int.class, int.class);
    private static final MethodHandle ISO_BYTES = StringIntrinsicBridge.find("encodeByteISO", int.class,
            byte[].class, int.class, byte[].class, int.class, int.class);
    private static volatile Object published;

    @State(Scope.Thread)
    public static class Inputs {
        @Param({"CLEAN", "MIXED", "PARTIAL"}) public String failure;
        int cursor;
        int failureMask;
        final char[] externalChars = new char[129];
        final byte[] externalDestination = new byte[129];

        @Setup public void setup() {
            failureMask = failure.equals("CLEAN") ? -1 : failure.equals("PARTIAL") ? 0 : 7;
            Arrays.fill(externalChars, 'A');
        }

        int next() { return cursor++; }
    }

    @Benchmark
    public int chars8(Inputs s) throws Throwable {
        int seed = s.next();
        char[] src = new char[8];
        byte[] dst = new byte[8];
        char value = (char) ('a' + (seed & 7));
        for (int i = 0; i < 8; i++) {
            src[i] = value;
            dst[i] = 7;
        }
        if (s.failureMask >= 0 && (seed & s.failureMask) == s.failureMask) src[4] = '\u0100';
        int prefix = (int) ASCII.invokeExact(src, 0, dst, 0, 8);
        int result = prefix;
        for (int i = 0; i < 8; i++) result = 31 * result + dst[i];
        return result;
    }

    @Benchmark
    public int chars9(Inputs s) throws Throwable {
        int seed = s.next();
        char[] src = new char[9];
        byte[] dst = new byte[9];
        char value = (char) ('a' + (seed & 7));
        for (int i = 0; i < 9; i++) {
            src[i] = value;
            dst[i] = 7;
        }
        if (s.failureMask >= 0 && (seed & s.failureMask) == s.failureMask) src[4] = '\u0100';
        int prefix = (int) ASCII.invokeExact(src, 0, dst, 0, 9);
        int result = prefix;
        for (int i = 0; i < 9; i++) result = 31 * result + dst[i];
        return result;
    }

    @Benchmark
    public int chars16(Inputs s) throws Throwable {
        int seed = s.next();
        char[] src = new char[16];
        byte[] dst = new byte[16];
        char value = (char) ('a' + (seed & 7));
        for (int i = 0; i < 16; i++) {
            src[i] = value;
            dst[i] = 7;
        }
        if (s.failureMask >= 0 && (seed & s.failureMask) == s.failureMask) src[8] = '\u0100';
        int prefix = (int) ASCII.invokeExact(src, 0, dst, 0, 16);
        int result = prefix;
        for (int i = 0; i < 16; i++) result = 31 * result + dst[i];
        return result;
    }

    @Benchmark
    public int chars17(Inputs s) throws Throwable {
        int seed = s.next();
        char[] src = new char[17];
        byte[] dst = new byte[17];
        char value = (char) ('a' + (seed & 7));
        for (int i = 0; i < 17; i++) {
            src[i] = value;
            dst[i] = 7;
        }
        if (s.failureMask >= 0 && (seed & s.failureMask) == s.failureMask) src[8] = '\u0100';
        int prefix = (int) ASCII.invokeExact(src, 0, dst, 0, 17);
        int result = prefix;
        for (int i = 0; i < 17; i++) result = 31 * result + dst[i];
        return result;
    }

    @Benchmark
    public int chars32(Inputs s) throws Throwable {
        int seed = s.next();
        char[] src = new char[32];
        byte[] dst = new byte[32];
        char value = (char) ('a' + (seed & 7));
        for (int i = 0; i < 32; i++) {
            src[i] = value;
            dst[i] = 7;
        }
        if (s.failureMask >= 0 && (seed & s.failureMask) == s.failureMask) src[16] = '\u0100';
        int prefix = (int) ASCII.invokeExact(src, 0, dst, 0, 32);
        int result = prefix;
        for (int i = 0; i < 32; i++) result = 31 * result + dst[i];
        return result;
    }

    @Benchmark
    public int chars64(Inputs s) throws Throwable {
        int seed = s.next();
        char[] src = new char[64];
        byte[] dst = new byte[64];
        char value = (char) ('a' + (seed & 7));
        for (int i = 0; i < 64; i++) {
            src[i] = value;
            dst[i] = 7;
        }
        if (s.failureMask >= 0 && (seed & s.failureMask) == s.failureMask) src[32] = '\u0100';
        int prefix = (int) ASCII.invokeExact(src, 0, dst, 0, 64);
        int result = prefix;
        for (int i = 0; i < 64; i++) result = 31 * result + dst[i];
        return result;
    }

    @Benchmark
    public int chars128(Inputs s) throws Throwable {
        int seed = s.next();
        char[] src = new char[128];
        byte[] dst = new byte[128];
        char value = (char) ('a' + (seed & 7));
        for (int i = 0; i < 128; i++) {
            src[i] = value;
            dst[i] = 7;
        }
        if (s.failureMask >= 0 && (seed & s.failureMask) == s.failureMask) src[64] = '\u0100';
        int prefix = (int) ASCII.invokeExact(src, 0, dst, 0, 128);
        int result = prefix;
        for (int i = 0; i < 128; i++) result = 31 * result + dst[i];
        return result;
    }

    @Benchmark
    public int chars129(Inputs s) throws Throwable {
        int seed = s.next();
        char[] src = new char[129];
        byte[] dst = new byte[129];
        char value = (char) ('a' + (seed & 7));
        for (int i = 0; i < 129; i++) {
            src[i] = value;
            dst[i] = 7;
        }
        if (s.failureMask >= 0 && (seed & s.failureMask) == s.failureMask) src[64] = '\u0100';
        int prefix = (int) ASCII.invokeExact(src, 0, dst, 0, 129);
        int result = prefix;
        for (int i = 0; i < 129; i++) result = 31 * result + dst[i];
        return result;
    }

    @Benchmark
    public int utf168(Inputs s) throws Throwable {
        int seed = s.next();
        byte[] src = new byte[16];
        byte[] dst = new byte[8];
        int value = 'a' + (seed & 7);
        for (int i = 0; i < 8; i++) {
            src[2 * i] = (byte) (value >>> (LITTLE ? 0 : 8));
            src[2 * i + 1] = (byte) (value >>> (LITTLE ? 8 : 0));
            dst[i] = 7;
        }
        if (s.failureMask >= 0 && (seed & s.failureMask) == s.failureMask) {
            src[8] = (byte) (0x100 >>> (LITTLE ? 0 : 8));
            src[9] = (byte) (0x100 >>> (LITTLE ? 8 : 0));
        }
        int prefix = (int) ISO_BYTES.invokeExact(src, 0, dst, 0, 8);
        int result = prefix;
        for (int i = 0; i < 8; i++) result = 31 * result + dst[i];
        return result;
    }

    @Benchmark
    public int utf169(Inputs s) throws Throwable {
        int seed = s.next();
        byte[] src = new byte[18];
        byte[] dst = new byte[9];
        int value = 'a' + (seed & 7);
        for (int i = 0; i < 9; i++) {
            src[2 * i] = (byte) (value >>> (LITTLE ? 0 : 8));
            src[2 * i + 1] = (byte) (value >>> (LITTLE ? 8 : 0));
            dst[i] = 7;
        }
        if (s.failureMask >= 0 && (seed & s.failureMask) == s.failureMask) {
            src[8] = (byte) (0x100 >>> (LITTLE ? 0 : 8));
            src[9] = (byte) (0x100 >>> (LITTLE ? 8 : 0));
        }
        int prefix = (int) ISO_BYTES.invokeExact(src, 0, dst, 0, 9);
        int result = prefix;
        for (int i = 0; i < 9; i++) result = 31 * result + dst[i];
        return result;
    }

    @Benchmark
    public int utf1616(Inputs s) throws Throwable {
        int seed = s.next();
        byte[] src = new byte[32];
        byte[] dst = new byte[16];
        int value = 'a' + (seed & 7);
        for (int i = 0; i < 16; i++) {
            src[2 * i] = (byte) (value >>> (LITTLE ? 0 : 8));
            src[2 * i + 1] = (byte) (value >>> (LITTLE ? 8 : 0));
            dst[i] = 7;
        }
        if (s.failureMask >= 0 && (seed & s.failureMask) == s.failureMask) {
            src[16] = (byte) (0x100 >>> (LITTLE ? 0 : 8));
            src[17] = (byte) (0x100 >>> (LITTLE ? 8 : 0));
        }
        int prefix = (int) ISO_BYTES.invokeExact(src, 0, dst, 0, 16);
        int result = prefix;
        for (int i = 0; i < 16; i++) result = 31 * result + dst[i];
        return result;
    }

    @Benchmark
    public int utf1617(Inputs s) throws Throwable {
        int seed = s.next();
        byte[] src = new byte[34];
        byte[] dst = new byte[17];
        int value = 'a' + (seed & 7);
        for (int i = 0; i < 17; i++) {
            src[2 * i] = (byte) (value >>> (LITTLE ? 0 : 8));
            src[2 * i + 1] = (byte) (value >>> (LITTLE ? 8 : 0));
            dst[i] = 7;
        }
        if (s.failureMask >= 0 && (seed & s.failureMask) == s.failureMask) {
            src[16] = (byte) (0x100 >>> (LITTLE ? 0 : 8));
            src[17] = (byte) (0x100 >>> (LITTLE ? 8 : 0));
        }
        int prefix = (int) ISO_BYTES.invokeExact(src, 0, dst, 0, 17);
        int result = prefix;
        for (int i = 0; i < 17; i++) result = 31 * result + dst[i];
        return result;
    }

    @Benchmark
    public int utf1632(Inputs s) throws Throwable {
        int seed = s.next();
        byte[] src = new byte[64];
        byte[] dst = new byte[32];
        int value = 'a' + (seed & 7);
        for (int i = 0; i < 32; i++) {
            src[2 * i] = (byte) (value >>> (LITTLE ? 0 : 8));
            src[2 * i + 1] = (byte) (value >>> (LITTLE ? 8 : 0));
            dst[i] = 7;
        }
        if (s.failureMask >= 0 && (seed & s.failureMask) == s.failureMask) {
            src[32] = (byte) (0x100 >>> (LITTLE ? 0 : 8));
            src[33] = (byte) (0x100 >>> (LITTLE ? 8 : 0));
        }
        int prefix = (int) ISO_BYTES.invokeExact(src, 0, dst, 0, 32);
        int result = prefix;
        for (int i = 0; i < 32; i++) result = 31 * result + dst[i];
        return result;
    }

    @Benchmark
    public int utf1664(Inputs s) throws Throwable {
        int seed = s.next();
        byte[] src = new byte[128];
        byte[] dst = new byte[64];
        int value = 'a' + (seed & 7);
        for (int i = 0; i < 64; i++) {
            src[2 * i] = (byte) (value >>> (LITTLE ? 0 : 8));
            src[2 * i + 1] = (byte) (value >>> (LITTLE ? 8 : 0));
            dst[i] = 7;
        }
        if (s.failureMask >= 0 && (seed & s.failureMask) == s.failureMask) {
            src[64] = (byte) (0x100 >>> (LITTLE ? 0 : 8));
            src[65] = (byte) (0x100 >>> (LITTLE ? 8 : 0));
        }
        int prefix = (int) ISO_BYTES.invokeExact(src, 0, dst, 0, 64);
        int result = prefix;
        for (int i = 0; i < 64; i++) result = 31 * result + dst[i];
        return result;
    }

    @Benchmark
    public int utf1665(Inputs s) throws Throwable {
        int seed = s.next();
        byte[] src = new byte[130];
        byte[] dst = new byte[65];
        int value = 'a' + (seed & 7);
        for (int i = 0; i < 65; i++) {
            src[2 * i] = (byte) (value >>> (LITTLE ? 0 : 8));
            src[2 * i + 1] = (byte) (value >>> (LITTLE ? 8 : 0));
            dst[i] = 7;
        }
        if (s.failureMask >= 0 && (seed & s.failureMask) == s.failureMask) {
            src[64] = (byte) (0x100 >>> (LITTLE ? 0 : 8));
            src[65] = (byte) (0x100 >>> (LITTLE ? 8 : 0));
        }
        int prefix = (int) ISO_BYTES.invokeExact(src, 0, dst, 0, 65);
        int result = prefix;
        for (int i = 0; i < 65; i++) result = 31 * result + dst[i];
        return result;
    }

    @Benchmark
    public int utf16128(Inputs s) throws Throwable {
        int seed = s.next();
        byte[] src = new byte[256];
        byte[] dst = new byte[128];
        int value = 'a' + (seed & 7);
        for (int i = 0; i < 128; i++) {
            src[2 * i] = (byte) (value >>> (LITTLE ? 0 : 8));
            src[2 * i + 1] = (byte) (value >>> (LITTLE ? 8 : 0));
            dst[i] = 7;
        }
        if (s.failureMask >= 0 && (seed & s.failureMask) == s.failureMask) {
            src[128] = (byte) (0x100 >>> (LITTLE ? 0 : 8));
            src[129] = (byte) (0x100 >>> (LITTLE ? 8 : 0));
        }
        int prefix = (int) ISO_BYTES.invokeExact(src, 0, dst, 0, 128);
        int result = prefix;
        for (int i = 0; i < 128; i++) result = 31 * result + dst[i];
        return result;
    }

    @Benchmark
    public int utf16129(Inputs s) throws Throwable {
        int seed = s.next();
        byte[] src = new byte[258];
        byte[] dst = new byte[129];
        int value = 'a' + (seed & 7);
        for (int i = 0; i < 129; i++) {
            src[2 * i] = (byte) (value >>> (LITTLE ? 0 : 8));
            src[2 * i + 1] = (byte) (value >>> (LITTLE ? 8 : 0));
            dst[i] = 7;
        }
        if (s.failureMask >= 0 && (seed & s.failureMask) == s.failureMask) {
            src[128] = (byte) (0x100 >>> (LITTLE ? 0 : 8));
            src[129] = (byte) (0x100 >>> (LITTLE ? 8 : 0));
        }
        int prefix = (int) ISO_BYTES.invokeExact(src, 0, dst, 0, 129);
        int result = prefix;
        for (int i = 0; i < 129; i++) result = 31 * result + dst[i];
        return result;
    }

    @Benchmark
    public int escapingChars128(Inputs s) throws Throwable {
        int seed = s.next();
        char[] src = new char[128];
        byte[] dst = new byte[128];
        Arrays.fill(src, (char) ('a' + (seed & 7)));
        Arrays.fill(dst, (byte) 7);
        if (s.failureMask >= 0 && (seed & s.failureMask) == s.failureMask) src[64] = '\u0100';
        int prefix = (int) ASCII.invokeExact(src, 0, dst, 0, 128);
        published = dst;
        return prefix;
    }

    @Benchmark
    public int localSourceExternalDestination128(Inputs s) throws Throwable {
        char[] src = new char[128];
        Arrays.fill(src, (char) ('a' + (s.next() & 7)));
        return (int) ASCII.invokeExact(src, 0, s.externalDestination, 0, 128);
    }

    @Benchmark
    public int externalSourceLocalDestination128(Inputs s) throws Throwable {
        byte[] dst = new byte[128];
        int prefix = (int) ASCII.invokeExact(s.externalChars, 0, dst, 0, 128);
        int result = prefix;
        for (int i = 0; i < 128; i++) result = 31 * result + dst[i];
        return result;
    }

    @Benchmark
    public int mixedSource128(Inputs s) throws Throwable {
        int seed = s.next();
        char[] local = new char[128];
        Arrays.fill(local, (char) ('a' + (seed & 7)));
        char[] src = (seed & 1) == 0 ? local : s.externalChars;
        return (int) ASCII.invokeExact(src, 0, s.externalDestination, 0, 128);
    }
}
