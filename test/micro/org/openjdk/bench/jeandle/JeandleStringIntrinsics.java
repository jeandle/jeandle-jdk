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
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
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

/**
 * Dynamic string-helper workloads. Run with explicit +/-UseJeandleCompiler
 * and matching heap flags to compare Jeandle and C2. A volatile fixture ring
 * supplies rotating, pre-generated inputs to each invocation. Constant handles
 * let both compilers inline the exact VM intrinsic candidates.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgsAppend = {"--add-opens=java.base/java.lang=ALL-UNNAMED"})
public class JeandleStringIntrinsics {
    @Param({"7", "16", "80", "1024"})
    public int length;

    @Param({"LATIN1", "UTF16"})
    public String encoding;

    @Param({"FIRST", "MIDDLE", "LAST", "MISS"})
    public String position;

    private static final int FIXTURE_COUNT = 8;

    private volatile Fixture[] fixtures;
    private int cursor;
    private int needleLength;

    private static final class Fixture {
        final byte[] latinA, latinB, utfA, utfB, latinNeedle, utfNeedle;
        final int character;

        Fixture(byte[] latinA, byte[] latinB, byte[] utfA, byte[] utfB,
                byte[] latinNeedle, byte[] utfNeedle, int character) {
            this.latinA = latinA;
            this.latinB = latinB;
            this.utfA = utfA;
            this.utfB = utfB;
            this.latinNeedle = latinNeedle;
            this.utfNeedle = utfNeedle;
            this.character = character;
        }
    }

    private static final MethodHandle EQUALSL = find("StringLatin1", "equals", boolean.class, byte[].class, byte[].class);
    private static final MethodHandle EQUALSU = find("StringUTF16", "equals", boolean.class, byte[].class, byte[].class);
    private static final MethodHandle COMPAREL = find("StringLatin1", "compareTo", int.class, byte[].class, byte[].class);
    private static final MethodHandle COMPAREU = find("StringUTF16", "compareTo", int.class, byte[].class, byte[].class);
    private static final MethodHandle COMPARELU = find("StringLatin1", "compareToUTF16", int.class, byte[].class, byte[].class);
    private static final MethodHandle COMPAREUL = find("StringUTF16", "compareToLatin1", int.class, byte[].class, byte[].class);
    private static final MethodHandle INDEXL = find("StringLatin1", "indexOf", int.class, byte[].class, byte[].class);
    private static final MethodHandle INDEXU = find("StringUTF16", "indexOf", int.class, byte[].class, byte[].class);
    private static final MethodHandle INDEXUL = find("StringUTF16", "indexOfLatin1", int.class, byte[].class, byte[].class);
    private static final MethodHandle INDEXIL = find("StringLatin1", "indexOf", int.class, byte[].class, int.class, byte[].class, int.class, int.class);
    private static final MethodHandle INDEXIU = find("StringUTF16", "indexOf", int.class, byte[].class, int.class, byte[].class, int.class, int.class);
    private static final MethodHandle INDEXIUL = find("StringUTF16", "indexOfLatin1", int.class, byte[].class, int.class, byte[].class, int.class, int.class);
    private static final MethodHandle CHARL = find("StringLatin1", "indexOfChar", int.class, byte[].class, int.class, int.class, int.class);
    private static final MethodHandle CHARU = find("StringUTF16", "indexOfChar", int.class, byte[].class, int.class, int.class, int.class);

    @Setup
    public void setup() throws Throwable {
        needleLength = Math.min(4, length);
        int at = switch (position) {
            case "FIRST" -> 0;
            case "MIDDLE" -> (length - needleLength) / 2;
            case "LAST" -> length - needleLength;
            default -> -1;
        };
        Fixture[] prepared = new Fixture[FIXTURE_COUNT];
        for (int i = 0; i < prepared.length; i++) {
            prepared[i] = prepareFixture(i, at);
        }
        cursor = 0;
        fixtures = prepared;
    }

    private Fixture prepareFixture(int variant, int at) throws Throwable {
        char base = (char) ('a' + 2 * variant);
        char marker = (char) (base + 1);
        byte[] latin = new byte[length];
        Arrays.fill(latin, (byte) base);
        char[] wide = new char[length];
        Arrays.fill(wide, encoding.equals("UTF16") ? (char) (0x1234 + 2 * variant) : base);
        if (at >= 0) {
            latin[at] = (byte) marker;
            wide[at] = marker;
            for (int i = 1; i < needleLength; i++) {
                wide[at + i] = base;
            }
        }
        byte[] latinB = latin.clone();
        char[] different = wide.clone();
        if (at >= 0) {
            latinB[at]++;
            different[at]++;
        }
        byte[] needle = new byte[needleLength];
        Arrays.fill(needle, (byte) base);
        needle[0] = (byte) marker;
        char[] wideNeedle = new char[needleLength];
        Arrays.fill(wideNeedle, base);
        wideNeedle[0] = marker;
        Fixture fixture = new Fixture(latin, latinB, utf16(wide), utf16(different),
                needle, utf16(wideNeedle), marker);
        verifyFixture(fixture, new String(latin, StandardCharsets.ISO_8859_1),
                new String(latinB, StandardCharsets.ISO_8859_1),
                new String(wide), new String(different), at);
        return fixture;
    }

    private void verifyFixture(Fixture f, String latinA, String latinB,
                               String utfA, String utfB, int at) throws Throwable {
        verify((boolean) EQUALSL.invokeExact(f.latinA, f.latinB) == (at < 0));
        verify((boolean) EQUALSU.invokeExact(f.utfA, f.utfB) == (at < 0));
        verify((int) COMPAREL.invokeExact(f.latinA, f.latinB) == latinA.compareTo(latinB));
        verify((int) COMPAREU.invokeExact(f.utfA, f.utfB) == utfA.compareTo(utfB));
        verify((int) COMPARELU.invokeExact(f.latinA, f.utfB) == latinA.compareTo(utfB));
        verify((int) COMPAREUL.invokeExact(f.utfA, f.latinB) == utfA.compareTo(latinB));
        verify((int) INDEXL.invokeExact(f.latinA, f.latinNeedle) == at);
        verify((int) INDEXU.invokeExact(f.utfA, f.utfNeedle) == at);
        verify((int) INDEXUL.invokeExact(f.utfA, f.latinNeedle) == at);
        verify((int) INDEXIL.invokeExact(f.latinA, length, f.latinNeedle, needleLength, 0) == at);
        verify((int) INDEXIU.invokeExact(f.utfA, length, f.utfNeedle, needleLength, 0) == at);
        verify((int) INDEXIUL.invokeExact(f.utfA, length, f.latinNeedle, needleLength, 0) == at);
        verify((int) CHARL.invokeExact(f.latinA, f.character, 0, length) == at);
        verify((int) CHARU.invokeExact(f.utfA, f.character, 0, length) == at);
    }

    private static void verify(boolean correct) {
        if (!correct) {
            throw new IllegalStateException("Invalid string fixture");
        }
    }

    private Fixture nextFixture() {
        return fixtures[cursor++ & (FIXTURE_COUNT - 1)];
    }

    @Benchmark
    public boolean equalsL() throws Throwable {
        Fixture f = nextFixture();
        return (boolean) EQUALSL.invokeExact(f.latinA, f.latinB);
    }
    @Benchmark
    public boolean equalsU() throws Throwable {
        Fixture f = nextFixture();
        return (boolean) EQUALSU.invokeExact(f.utfA, f.utfB);
    }
    @Benchmark
    public int compareL() throws Throwable {
        Fixture f = nextFixture();
        return (int) COMPAREL.invokeExact(f.latinA, f.latinB);
    }
    @Benchmark
    public int compareU() throws Throwable {
        Fixture f = nextFixture();
        return (int) COMPAREU.invokeExact(f.utfA, f.utfB);
    }
    @Benchmark
    public int compareLU() throws Throwable {
        Fixture f = nextFixture();
        return (int) COMPARELU.invokeExact(f.latinA, f.utfB);
    }
    @Benchmark
    public int compareUL() throws Throwable {
        Fixture f = nextFixture();
        return (int) COMPAREUL.invokeExact(f.utfA, f.latinB);
    }
    @Benchmark
    public int indexL() throws Throwable {
        Fixture f = nextFixture();
        return (int) INDEXL.invokeExact(f.latinA, f.latinNeedle);
    }
    @Benchmark
    public int indexU() throws Throwable {
        Fixture f = nextFixture();
        return (int) INDEXU.invokeExact(f.utfA, f.utfNeedle);
    }
    @Benchmark
    public int indexUL() throws Throwable {
        Fixture f = nextFixture();
        return (int) INDEXUL.invokeExact(f.utfA, f.latinNeedle);
    }
    @Benchmark
    public int indexIL() throws Throwable {
        Fixture f = nextFixture();
        return (int) INDEXIL.invokeExact(f.latinA, length, f.latinNeedle, needleLength, 0);
    }
    @Benchmark
    public int indexIU() throws Throwable {
        Fixture f = nextFixture();
        return (int) INDEXIU.invokeExact(f.utfA, length, f.utfNeedle, needleLength, 0);
    }
    @Benchmark
    public int indexIUL() throws Throwable {
        Fixture f = nextFixture();
        return (int) INDEXIUL.invokeExact(f.utfA, length, f.latinNeedle, needleLength, 0);
    }
    @Benchmark
    public int charL() throws Throwable {
        Fixture f = nextFixture();
        return (int) CHARL.invokeExact(f.latinA, f.character, 0, length);
    }
    @Benchmark
    public int charU() throws Throwable {
        Fixture f = nextFixture();
        return (int) CHARU.invokeExact(f.utfA, f.character, 0, length);
    }

    static MethodHandle find(String owner, String name, Class<?> result, Class<?>... args) {
        boolean latin = owner.equals("StringLatin1");
        if (!latin && !owner.equals("StringUTF16")) {
            throw new IllegalArgumentException("Unknown string helper: " + owner);
        }
        String wrapper = switch (name) {
            case "equals" -> latin ? "equalsL" : "equalsU";
            case "compareTo" -> latin ? "compareToL" : "compareToU";
            case "compareToUTF16" -> "compareToLU";
            case "compareToLatin1" -> "compareToUL";
            case "indexOf" -> args.length == 2 ? (latin ? "indexOfL" : "indexOfU")
                    : (latin ? "indexOfIL" : "indexOfIU");
            case "indexOfLatin1" -> args.length == 2 ? "indexOfUL" : "indexOfIUL";
            case "indexOfChar" -> latin ? "indexOfL_char" : "indexOfU_char";
            case "toBytes" -> "toBytesStringU";
            case "getChars" -> "getCharsStringU";
            case "compress" -> args[0] == char[].class ? "compressStringC" : "compressStringB";
            case "inflate" -> args[2] == char[].class ? "inflateStringC" : "inflateStringB";
            default -> throw new IllegalArgumentException("Unknown string helper: " + name);
        };
        try {
            // The ordinary wrapper exposes the registered invokestatic to
            // both compilers when the constant MethodHandle is inlined.
            Class<?> type = Class.forName("java.lang.JeandleStringIntrinsicCaller");
            return MethodHandles.publicLookup().findStatic(type, wrapper,
                    MethodType.methodType(result, args));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static byte[] utf16(char[] chars) {
        byte[] bytes = new byte[chars.length * 2];
        boolean big = ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN;
        for (int i = 0; i < chars.length; i++) {
            bytes[2 * i] = (byte) (big ? chars[i] >>> 8 : chars[i]);
            bytes[2 * i + 1] = (byte) (big ? chars[i] : chars[i] >>> 8);
        }
        return bytes;
    }
}
