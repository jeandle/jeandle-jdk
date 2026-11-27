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
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/** Direct intrinsic workloads plus a public text conversion mix. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgsAppend = {
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED"})
public class JeandleCharacterIntrinsics {
    private static final MethodHandle ISO = StringIntrinsicBridge.find("encodeISO", int.class,
            char[].class, int.class, byte[].class, int.class, int.class);
    private static final MethodHandle ISO_BYTES = StringIntrinsicBridge.find("encodeByteISO", int.class,
            byte[].class, int.class, byte[].class, int.class, int.class);
    private static final MethodHandle ASCII = StringIntrinsicBridge.find("encodeASCII", int.class,
            char[].class, int.class, byte[].class, int.class, int.class);
    private static final MethodHandle SCAN = StringIntrinsicBridge.find("countPositives", int.class,
            byte[].class, int.class, int.class);
    private static final MethodHandle DIGIT = predicate("isDigit");
    private static final MethodHandle LOWER = predicate("isLowerCase");
    private static final MethodHandle UPPER = predicate("isUpperCase");
    private static final MethodHandle SPACE = predicate("isWhitespace");

    private static MethodHandle predicate(String name) {
        return StringIntrinsicBridge.find(name, boolean.class, int.class);
    }

    @State(Scope.Thread)
    public static class Encoding {
        @Param({"7", "16", "80", "1024"}) public int length;
        @Param({"CLEAN", "FIRST", "LAST"}) public String failure;
        char[][] shortChars;
        byte[][] shortBytes;
        char[][] chars;
        char[][] offsetChars;
        byte[][] aliasUTF16;
        byte[][] utf16;
        byte[][] bytes;
        byte[] destination;
        String[] text;
        int cursor;

        @Setup
        public void setup() throws Throwable {
            shortChars = new char[8][7];
            shortBytes = new byte[8][7];
            chars = new char[8][length];
            offsetChars = new char[8][length];
            aliasUTF16 = new byte[8][2 * length];
            utf16 = new byte[8][2 * length];
            bytes = new byte[8][length];
            text = new String[8];
            destination = new byte[length];
            boolean little = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;
            int bad = failure.equals("FIRST") ? 0 : failure.equals("LAST") ? length - 1 : -1;
            for (int variant = 0; variant < 8; variant++) {
                Arrays.fill(shortChars[variant], (char) ('a' + variant));
                Arrays.fill(shortBytes[variant], (byte) ('a' + variant));
                int shortBad = failure.equals("FIRST") ? 0 : failure.equals("LAST") ? 6 : -1;
                if (shortBad >= 0) { shortChars[variant][shortBad] = '\u0100'; shortBytes[variant][shortBad] = -1; }
                Arrays.fill(offsetChars[variant], (char) ('a' + variant));
                int offsetBad = failure.equals("FIRST") ? 1 : failure.equals("LAST") ? length - 1 : -1;
                if (offsetBad >= 0) offsetChars[variant][offsetBad] = '\u0100';
                Arrays.fill(chars[variant], (char) ('a' + variant));
                Arrays.fill(bytes[variant], (byte) ('a' + variant));
                if (bad >= 0) {
                    chars[variant][bad] = '\u0100';
                    bytes[variant][bad] = -1;
                }
                for (int i = 0; i < length; i++) {
                    int c = chars[variant][i];
                    utf16[variant][2 * i] = (byte) (c >> (little ? 0 : 8));
                    utf16[variant][2 * i + 1] = (byte) (c >> (little ? 8 : 0));
                    int aliasBad = failure.equals("FIRST") ? length / 2
                            : failure.equals("LAST") ? 2 * (length / 2) - 1 : -1;
                    int aliasChar = i == aliasBad ? 0x100 : 'a' + variant;
                    aliasUTF16[variant][2 * i] = (byte) (aliasChar >> (little ? 0 : 8));
                    aliasUTF16[variant][2 * i + 1] = (byte) (aliasChar >> (little ? 8 : 0));
                }
                String pattern = "GET /api?id=123 HTTP/1.1\r\nContent-Type: text/plain\r\n";
                char[] request = new char[length];
                for (int i = 0; i < length; i++) request[i] = pattern.charAt((i + variant) % pattern.length());
                if (bad >= 0) request[bad] = '\u0100';
                text[variant] = new String(request);
                int expected = bad < 0 ? length : bad;
                if ((int) ISO.invokeExact(chars[variant], 0, destination, 0, length) != expected
                        || (int) ASCII.invokeExact(chars[variant], 0, destination, 0, length) != expected
                        || (int) ISO_BYTES.invokeExact(utf16[variant], 0, destination, 0, length) != expected) {
                    throw new AssertionError("encoding fixture");
                }
                int offsetPrefix = failure.equals("CLEAN") ? length - 1
                        : failure.equals("FIRST") ? 0 : length - 2;
                int aliasPrefix = failure.equals("CLEAN") ? length / 2
                        : failure.equals("FIRST") ? 0 : length / 2 - 1;
                if ((int) ASCII.invokeExact(offsetChars[variant], 1, destination, 1, length - 1) != offsetPrefix) {
                    throw new AssertionError("offset encoding fixture");
                }
                // Repeating the conversion checks that destination writes do not
                // corrupt the disjoint source slice in the alias fixture.
                for (int repeat = 0; repeat < 2; repeat++) {
                    byte[] both = aliasUTF16[variant];
                    if ((int) ISO_BYTES.invokeExact(both, length / 2, both, 0, length / 2) != aliasPrefix) {
                        throw new AssertionError("alias encoding fixture");
                    }
                }
                int count = (int) SCAN.invokeExact(bytes[variant], 0, length);
                if (bad < 0 ? count != length : count < 0 || count > bad) {
                    throw new AssertionError("scan fixture");
                }
            }
        }
        int next() { return cursor++ & 7; }
    }

    @State(Scope.Thread)
    public static class Characters {
        @Param({"ASCII", "LATIN1", "LATIN1_PERMUTED"}) public String distribution;
        int[] values = new int[256];
        int cursor;
        @Setup public void setup() {
            for (int i = 0; i < values.length; i++) {
                values[i] = distribution.equals("ASCII") ? i & 127
                        : distribution.equals("LATIN1") ? i : (i * 197 + 17) & 255;
            }
        }
        int next() { return values[cursor++ & 255]; }
    }

    @Benchmark public int encodeISO(Encoding s) throws Throwable {
        return (int) ISO.invokeExact(s.chars[s.next()], 0, s.destination, 0, s.length);
    }
    @Benchmark public int encodeISOBytes(Encoding s) throws Throwable {
        return (int) ISO_BYTES.invokeExact(s.utf16[s.next()], 0, s.destination, 0, s.length);
    }
    @Benchmark public int encodeASCII(Encoding s) throws Throwable {
        return (int) ASCII.invokeExact(s.chars[s.next()], 0, s.destination, 0, s.length);
    }
    @Benchmark public int countPositives(Encoding s) throws Throwable {
        return (int) SCAN.invokeExact(s.bytes[s.next()], 0, s.length);
    }
    @Benchmark public boolean isDigit(Characters s) throws Throwable {
        return (boolean) DIGIT.invokeExact(s.next());
    }
    @Benchmark public boolean isLowerCase(Characters s) throws Throwable {
        return (boolean) LOWER.invokeExact(s.next());
    }
    @Benchmark public boolean isUpperCase(Characters s) throws Throwable {
        return (boolean) UPPER.invokeExact(s.next());
    }
    @Benchmark public boolean isWhitespace(Characters s) throws Throwable {
        return (boolean) SPACE.invokeExact(s.next());
    }
    @Benchmark public int encodeOffset(Encoding s) throws Throwable {
        return (int) ASCII.invokeExact(s.offsetChars[s.next()], 1, s.destination, 1, s.length - 1);
    }
    @Benchmark public int encodeAlias(Encoding s) throws Throwable {
        byte[] both = s.aliasUTF16[s.next()];
        // The disjoint ranges preserve the fixture across invocations while
        // retaining the same-array Java fallback and its width change.
        return (int) ISO_BYTES.invokeExact(both, s.length / 2, both, 0, s.length / 2);
    }
    @Benchmark public int encodeKnown7(Encoding s) throws Throwable {
        return (int) ASCII.invokeExact(s.shortChars[s.next()], 0, s.destination, 0, 7);
    }
    @Benchmark public int scanKnown7(Encoding s) throws Throwable {
        return (int) SCAN.invokeExact(s.shortBytes[s.next()], 0, 7);
    }
    @Benchmark @OperationsPerInvocation(256)
    public int predicatesBatch(Characters s) throws Throwable {
        int total = 0;
        for (int c : s.values) {
            total += ((boolean) DIGIT.invokeExact(c) ? 1 : 0)
                    + ((boolean) LOWER.invokeExact(c) ? 2 : 0)
                    + ((boolean) UPPER.invokeExact(c) ? 4 : 0)
                    + ((boolean) SPACE.invokeExact(c) ? 8 : 0);
        }
        return total;
    }
    @Benchmark public int charsetEncoder(Encoding s) throws java.nio.charset.CharacterCodingException {
        var encoder = StandardCharsets.ISO_8859_1.newEncoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE);
        return encoder.encode(java.nio.CharBuffer.wrap(s.chars[s.next()])).remaining();
    }
    @Benchmark public int publicText(Encoding s) {
        String value = s.text[s.next()];
        byte[] iso = value.getBytes(StandardCharsets.ISO_8859_1);
        byte[] ascii = value.getBytes(StandardCharsets.US_ASCII);
        byte[] utf = value.getBytes(StandardCharsets.UTF_8);
        int result = Arrays.hashCode(iso) ^ Arrays.hashCode(ascii);
        String decoded = new String(utf, StandardCharsets.UTF_8);
        for (int i = 0; i < decoded.length(); i++) {
            char c = decoded.charAt(i);
            result += Character.isLowerCase(c) ? 1 : Character.isUpperCase(c) ? 2
                    : Character.isDigit(c) ? 3 : Character.isWhitespace(c) ? 4 : 5;
        }
        return result;
    }
}
