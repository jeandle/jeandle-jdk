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
import java.util.Random;
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

/** Compression over distinct buffers and varied positions of the first wide character. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgsAppend = {
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED"})
public class StringCompressionProfiles {
    @Param({"16", "32", "80", "4096"})
    public int length;

    @Param({"EARLY", "UNIFORM", "MIXED"})
    public String profile;

    private static final int INPUTS = 128;
    private static final byte SENTINEL = (byte) 0x5a;
    private static final MethodHandle COMPRESS_C = find(char[].class);
    private static final MethodHandle COMPRESS_B = find(byte[].class);
    private Input[] inputs;
    private int cursor;

    private static final class Input {
        char[] chars;
        byte[] wide, destination;
        int sourceOffset, destinationOffset, failure;
    }

    @Setup
    public void setup() throws Throwable {
        if (length < 8) throw new IllegalArgumentException("length must be at least eight");
        if (!profile.equals("EARLY") && !profile.equals("UNIFORM") && !profile.equals("MIXED")) {
            throw new IllegalArgumentException("Unknown profile: " + profile);
        }
        inputs = new Input[INPUTS];
        Random random = new Random(0x51a17eL);
        for (int i = 0; i < INPUTS; i++) {
            Input input = new Input();
            input.sourceOffset = (i & 1) == 0 ? 1 : 3;
            input.destinationOffset = (i & 2) == 0 ? 1 : 5;
            input.failure = profile.equals("EARLY") ? 1 + i % 7 : random.nextInt(length);
            if (profile.equals("MIXED") && (i & 1) == 0) input.failure = -1;
            input.chars = new char[input.sourceOffset + length + 3];
            for (int j = 0; j < input.chars.length; j++) {
                input.chars[j] = (char) (128 + random.nextInt(128));
            }
            if (input.failure >= 0) input.chars[input.sourceOffset + input.failure] = '\u8123';
            input.wide = utf16(input.chars);
            input.destination = new byte[input.destinationOffset + length + 3];
            check(input, true);
            check(input, false);
            Arrays.fill(input.destination, SENTINEL);
            inputs[i] = input;
        }
        for (int i = INPUTS - 1; i > 0; i--) {
            int other = random.nextInt(i + 1);
            Input saved = inputs[i];
            inputs[i] = inputs[other];
            inputs[other] = saved;
        }
        cursor = 0;
    }

    @Benchmark
    public int compressC() throws Throwable {
        Input input = inputs[cursor++ & (INPUTS - 1)];
        int result = (int) COMPRESS_C.invokeExact(input.chars, input.sourceOffset,
                input.destination, input.destinationOffset, length);
        return result + checksum(input.destination, input.destinationOffset, length);
    }

    @Benchmark
    public int compressB() throws Throwable {
        Input input = inputs[cursor++ & (INPUTS - 1)];
        int result = (int) COMPRESS_B.invokeExact(input.wide, input.sourceOffset,
                input.destination, input.destinationOffset, length);
        return result + checksum(input.destination, input.destinationOffset, length);
    }

    private void check(Input input, boolean chars) throws Throwable {
        Arrays.fill(input.destination, SENTINEL);
        int result = chars
                ? (int) COMPRESS_C.invokeExact(input.chars, input.sourceOffset,
                        input.destination, input.destinationOffset, length)
                : (int) COMPRESS_B.invokeExact(input.wide, input.sourceOffset,
                        input.destination, input.destinationOffset, length);
        if (result != (input.failure < 0 ? length : 0)) throw new AssertionError("Compression result");
        for (int j = 0; j < input.destination.length; j++) {
            int relative = j - input.destinationOffset;
            boolean legal = relative >= 0 && relative < length
                    && (input.failure < 0 || relative < input.failure);
            byte expected = legal ? (byte) input.chars[input.sourceOffset + relative] : SENTINEL;
            // On failure some compilers leave a validated prefix uncommitted.
            boolean untouchedPrefix = legal && input.failure >= 0 && input.destination[j] == SENTINEL;
            if (input.destination[j] != expected && !untouchedPrefix) {
                throw new AssertionError("Compression destination at " + j);
            }
        }
    }

    private static int checksum(byte[] bytes, int offset, int count) {
        return (bytes[offset] & 255) + 31 * (bytes[offset + count / 2] & 255)
                + 997 * (bytes[offset + count - 1] & 255);
    }

    private static MethodHandle find(Class<?> source) {
        return JeandleStringIntrinsics.find("StringUTF16", "compress", int.class,
                source, int.class, byte[].class, int.class, int.class);
    }

    private static byte[] utf16(char[] chars) {
        byte[] bytes = new byte[2 * chars.length];
        boolean big = ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN;
        for (int i = 0; i < chars.length; i++) {
            bytes[2 * i] = (byte) (big ? chars[i] >>> 8 : chars[i]);
            bytes[2 * i + 1] = (byte) (big ? chars[i] : chars[i] >>> 8);
        }
        return bytes;
    }
}
