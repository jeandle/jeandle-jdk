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

/** Conversion workloads return destination samples so writes remain observable. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgsAppend = {"--add-opens=java.base/java.lang=ALL-UNNAMED"})
public class JeandleStringConversions {
    @Param({"7", "16", "80", "1024", "4096", "16384"})
    public int length;

    @Param({"LATIN1", "UTF16"})
    public String encoding;

    @Param({"NONE", "FIRST", "MIDDLE", "LAST"})
    public String failure;

    private volatile char[] chars, charDestination;
    private volatile byte[] latin, wide, byteDestination, wideDestination;
    private volatile byte[] produced;

    private static final MethodHandle TO_BYTES = find("StringUTF16", "toBytes", byte[].class,
            char[].class, int.class, int.class);
    private static final MethodHandle GET_CHARS = find("StringUTF16", "getChars", void.class,
            byte[].class, int.class, int.class, char[].class, int.class);
    private static final MethodHandle COMPRESS_C = find("StringUTF16", "compress", int.class,
            char[].class, int.class, byte[].class, int.class, int.class);
    private static final MethodHandle COMPRESS_B = find("StringUTF16", "compress", int.class,
            byte[].class, int.class, byte[].class, int.class, int.class);
    private static final MethodHandle INFLATE_C = find("StringLatin1", "inflate", void.class,
            byte[].class, int.class, char[].class, int.class, int.class);
    private static final MethodHandle INFLATE_B = find("StringLatin1", "inflate", void.class,
            byte[].class, int.class, byte[].class, int.class, int.class);

    @Setup
    public void setup() {
        char[] input = new char[length + 2];
        byte[] narrow = new byte[length + 2];
        for (int i = 0; i < input.length; i++) {
            input[i] = (char) ((i * 73 + 129) & 255);
            narrow[i] = (byte) input[i];
        }
        // UTF16 data still permits successful compression unless a failure
        // position is requested; high bytes otherwise exercise copy kernels.
        if (encoding.equals("UTF16") && !failure.equals("NONE")) {
            int at = switch (failure) {
                case "FIRST" -> 1;
                case "MIDDLE" -> 1 + length / 2;
                default -> length;
            };
            input[at] = '\u1234';
        }
        chars = input;
        latin = narrow;
        wide = utf16(input);
        byteDestination = new byte[length + 2];
        wideDestination = new byte[2 * (length + 2)];
        charDestination = new char[length + 2];
        Arrays.fill(byteDestination, (byte) 0x5a);
        Arrays.fill(wideDestination, (byte) 0x5a);
        Arrays.fill(charDestination, '\ua55a');
    }

    private static int checksum(byte[] a) {
        return (a[0] & 255) + 31 * (a[a.length / 2] & 255) + 997 * (a[a.length - 1] & 255);
    }

    private static int checksum(char[] a) {
        return a[0] + 31 * a[a.length / 2] + 997 * a[a.length - 1];
    }

    @Benchmark
    public int toBytes() throws Throwable {
        byte[] result = (byte[]) TO_BYTES.invokeExact(chars, 1, length);
        // Publish the whole allocation before sampling its contents.
        produced = result;
        return checksum(result);
    }

    @Benchmark
    public int getChars() throws Throwable {
        char[] destination = charDestination;
        GET_CHARS.invokeExact(wide, 1, length + 1, destination, 1);
        return checksum(destination);
    }

    @Benchmark
    public int compressC() throws Throwable {
        byte[] destination = byteDestination;
        int count = (int) COMPRESS_C.invokeExact(chars, 1, destination, 1, length);
        return count + checksum(destination);
    }

    @Benchmark
    public int compressB() throws Throwable {
        byte[] destination = byteDestination;
        int count = (int) COMPRESS_B.invokeExact(wide, 1, destination, 1, length);
        return count + checksum(destination);
    }

    @Benchmark
    public int inflateC() throws Throwable {
        char[] destination = charDestination;
        INFLATE_C.invokeExact(latin, 1, destination, 1, length);
        return checksum(destination);
    }

    @Benchmark
    public int inflateB() throws Throwable {
        byte[] destination = wideDestination;
        INFLATE_B.invokeExact(latin, 1, destination, 1, length);
        return checksum(destination);
    }

    private static MethodHandle find(String owner, String name, Class<?> result, Class<?>... args) {
        return JeandleStringIntrinsics.find(owner, name, result, args);
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
