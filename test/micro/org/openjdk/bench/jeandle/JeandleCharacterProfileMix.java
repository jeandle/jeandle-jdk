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
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/** Shared encoder profiles with clean, rejecting and partial-prefix callers. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgsAppend = {
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED"})
@State(Scope.Thread)
public class JeandleCharacterProfileMix {
    private static final MethodHandle ISO = StringIntrinsicBridge.find("encodeByteISO", int.class,
            byte[].class, int.class, byte[].class, int.class, int.class);

    @Param({"DENSE", "CLEAN", "MIXED"}) public String initialProfile;
    private final byte[] clean = new byte[2048];
    private final byte[] dense = new byte[2048];
    private final byte[] mixed = new byte[2048];
    private final byte[] destination = new byte[1024];

    @Setup public void setup() throws Throwable {
        boolean little = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;
        for (int i = 0; i < 1024; i++) {
            clean[2 * i + (little ? 0 : 1)] = 65;
            dense[2 * i + (little ? 1 : 0)] = 4;
            int value = i == 512 ? 0x400 : 65;
            mixed[2 * i] = (byte) (value >>> (little ? 0 : 8));
            mixed[2 * i + 1] = (byte) (value >>> (little ? 8 : 0));
        }
        byte[] first = initialProfile.equals("DENSE") ? dense
                : initialProfile.equals("CLEAN") ? clean : mixed;
        int expected = initialProfile.equals("DENSE") ? 0 : initialProfile.equals("CLEAN") ? 1024 : 512;
        for (int i = 0; i < 20000; i++) {
            if (encode(first) != expected) throw new AssertionError("initial encoding profile");
        }
    }

    private int encode(byte[] source) throws Throwable {
        return (int) ISO.invokeExact(source, 0, destination, 0, 1024);
    }

    private int rejectingCaller() throws Throwable {
        return (int) ISO.invokeExact(dense, 0, destination, 0, 1024);
    }

    private int partialCaller() throws Throwable {
        return (int) ISO.invokeExact(mixed, 0, destination, 0, 1024);
    }

    @Benchmark public int cleanCaller() throws Throwable {
        return encode(clean);
    }

    @Benchmark @OperationsPerInvocation(3)
    public int mixedCallers() throws Throwable {
        return rejectingCaller() + encode(clean) + partialCaller();
    }
}
