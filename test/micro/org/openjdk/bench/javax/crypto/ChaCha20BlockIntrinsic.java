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

package org.openjdk.bench.javax.crypto;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.TimeUnit;

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
 * Focused benchmark of the private ChaCha20 block wrapper that owns the
 * direct call to {@code implChaCha20Block}. This excludes Cipher/provider
 * dispatch, the transform loop, and XOR. Each benchmark operation generates
 * 1024 bytes of keystream, using as many wrapper calls as the backend needs.
 *
 * The private wrapper is resolved once during setup. Diagnostic runs must
 * prove that the MethodHandle adapter reaches the wrapper and that the
 * enabled modes call {@code StubRoutines_chacha20Block}.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 8, time = 1)
@Fork(value = 3, jvmArgsAppend = {
        "--add-opens=java.base/com.sun.crypto.provider=ALL-UNNAMED"
})
public class ChaCha20BlockIntrinsic {

    private static final int STATE_LENGTH = 16;
    private static final int KEYSTREAM_LENGTH = 1024;
    private static volatile int sink;

    private MethodHandle block;
    private int[] state;
    private byte[] result;
    private long counter;

    @Setup
    public void setup() throws ReflectiveOperationException {
        Class<?> cipherClass = Class.forName("com.sun.crypto.provider.ChaCha20Cipher");
        MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(
                cipherClass, MethodHandles.lookup());
        block = lookup.findStatic(cipherClass, "chaCha20Block",
                MethodType.methodType(int.class, int[].class, long.class, byte[].class));

        state = new int[STATE_LENGTH];
        state[0] = 0x61707865;
        state[1] = 0x3320646e;
        state[2] = 0x79622d32;
        state[3] = 0x6b206574;
        for (int i = 4; i < state.length; i++) {
            state[i] = 0x9e3779b9 * i + 0x1234567;
        }
        result = new byte[KEYSTREAM_LENGTH];
        counter = 1;
    }

    @Benchmark
    public int block() throws Throwable {
        int total = 0;
        int checksum = 0;
        do {
            int generated = (int) block.invokeExact(state, counter, result);
            counter += generated / 64;
            total += generated;
            checksum = 31 * checksum + result[0];
            checksum = 31 * checksum + result[generated - 1];
        } while (total < KEYSTREAM_LENGTH);
        checksum += total;
        sink = checksum;
        return checksum;
    }
}
