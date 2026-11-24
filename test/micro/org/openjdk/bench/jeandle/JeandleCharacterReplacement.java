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

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/** Public encoding with repeated unmappable characters and mixed text. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 10, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
@State(Scope.Thread)
public class JeandleCharacterReplacement {
    @Param({"16", "1024"}) public int length;
    @Param({"ASCII", "DENSE", "MIXED"}) public String distribution;
    private final String[] values = new String[16];
    private int cursor;

    @Setup
    public void setup() {
        for (int n = 0; n < values.length; n++) {
            char[] chars = new char[length];
            for (int i = 0; i < length; i++) {
                boolean invalid = distribution.equals("DENSE")
                        || distribution.equals("MIXED") && (i & 7) == 0;
                chars[i] = (char) ((invalid ? 0x400 : 'a') + ((i + n) & 15));
            }
            values[n] = new String(chars);
        }
    }

    @Benchmark
    public byte[] iso88591() {
        return values[cursor++ & 15].getBytes(StandardCharsets.ISO_8859_1);
    }
}
