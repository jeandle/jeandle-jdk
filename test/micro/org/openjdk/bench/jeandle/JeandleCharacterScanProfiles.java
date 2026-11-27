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
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 */

package org.openjdk.bench.jeandle;

import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/** Scanning and UTF-8 decoding after another caller seeds the shared profile. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgsAppend = {
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED"})
@State(Scope.Thread)
public class JeandleCharacterScanProfiles {
    private static final MethodHandle SCAN = StringIntrinsicBridge.find("countPositives", int.class,
            byte[].class, int.class, int.class);

    @Param({"FIRST", "MIXED"}) public String initialProfile;
    @Param({"1", "2", "7", "16", "1024"}) public int length;
    private byte[] clean;
    private byte[] nonAscii;
    private int sequence;

    @Setup public void setup() throws Throwable {
        clean = new byte[length];
        Arrays.fill(clean, (byte) 'A');
        nonAscii = clean.clone();
        nonAscii[0] = (byte) 0xce;
        if (length > 1) {
            nonAscii[1] = (byte) 0xb1;
        }
        if ((int) SCAN.invokeExact(clean, 0, length) != length
                || (int) SCAN.invokeExact(nonAscii, 0, length) != 0) {
            throw new AssertionError("scan prefix");
        }
        String decoded = new String(nonAscii, StandardCharsets.UTF_8);
        int expectedLength = length == 1 ? 1 : length - 1;
        char expectedFirst = length == 1 ? '\ufffd' : '\u03b1';
        if (decoded.length() != expectedLength || decoded.charAt(0) != expectedFirst) {
            throw new AssertionError("UTF-8 fixture");
        }
        for (int i = 0; i < 20000; i++) {
            byte[] source = initialProfile.equals("FIRST") || (i & 1) != 0 ? nonAscii : clean;
            int expected = source == clean ? length : 0;
            if ((int) SCAN.invokeExact(source, 0, length) != expected) {
                throw new AssertionError("shared scan profile");
            }
        }
    }

    @Benchmark public int scanClean() throws Throwable {
        return (int) SCAN.invokeExact(clean, 0, length);
    }

    @Benchmark public String decodeClean() {
        return new String(clean, StandardCharsets.UTF_8);
    }

    @Benchmark public String decodeMixed() {
        byte[] source = (++sequence & 7) == 0 ? nonAscii : clean;
        return new String(source, StandardCharsets.UTF_8);
    }
}
