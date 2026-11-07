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

import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.BenchmarkParams;

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class StringMixedWorkloads {
    @Param({"SHORT", "TEXT"})
    public String regime;
    @Param({"LATIN1", "UTF16"})
    public String encoding;

    private Input[] inputs;
    private int cursor;
    private volatile char[] publishedCopy;

    private static class Input {
        String left, right, haystack, needle, charSource, copySource;
        char[] construction, buffer, expectedCopy;
        int from, character, begin, end, destination, first, middle, last;
        int expectedCompare, expectedSearch, expectedChar;
        boolean expectedEquals;
    }

    @Setup
    public void setup(BenchmarkParams parameters) {
        int[] sizes = regime.equals("SHORT")
                ? new int[]{0, 1, 3, 7, 8, 12, 15, 16, 17, 31, 32, 33, 63, 64, 65, 7}
                : new int[]{0, 1, 3, 7, 8, 12, 15, 16, 17, 31, 32, 33, 128, 256, 1024, 4096};
        boolean wide = encoding.equals("UTF16");
        inputs = new Input[128];
        for (int i = 0; i < inputs.length; i++) {
            int length = sizes[(5 * i + i / 16) & 15];
            Input input = new Input();
            prepareComparison(input, length, i, wide);
            prepareSearch(input, length, i, wide);
            prepareCharacters(input, length, i, wide);
            prepareConstruction(input, length, i, wide);
            prepareCopy(input, length, i, wide);
            inputs[i] = input;
        }
        Random random = new Random(0x51a17eL);
        for (int i = inputs.length - 1; i > 0; i--) {
            int other = random.nextInt(i + 1);
            Input saved = inputs[i];
            inputs[i] = inputs[other];
            inputs[other] = saved;
        }
        // Exercise the full trial oracle before timing, including its long
        // loops. Restore the same sentinel state used by the timed copies.
        verifyCopiedBuffers(parameters);
        for (Input input : inputs) {
            Arrays.fill(input.buffer, '\ua55a');
        }
        cursor = 0;
        publishedCopy = null;
    }

    @TearDown(Level.Trial)
    public void verifyCopiedBuffers(BenchmarkParams parameters) {
        if (!parameters.getBenchmark().endsWith(".copyChars")) {
            return;
        }
        for (Input input : inputs) {
            for (int i = 0; i < input.expectedCopy.length; i++) {
                require(input.buffer[i] == input.expectedCopy[i]);
            }
        }
    }

    private static void prepareComparison(Input input, int length, int seed, boolean wide) {
        int shape = seed & 15;
        char[] left = text(length, seed, wide);
        char[] right = left.clone();
        if (shape == 3 || shape == 4) {
            right = Arrays.copyOf(left, length + 1);
        } else if (shape >= 5 && shape <= 10 && length != 0) {
            int at = shape <= 6 ? 0 : shape <= 8 ? length / 2 : length - 1;
            right[at] = (char) (right[at] + (wide ? 0x100 : 1));
        } else if (shape >= 12 && length != 0) {
            left = text(length, seed, false);
            right = left.clone();
            int at = shape < 14 ? 0 : length - 1;
            right[at] = (char) (right[at] + (wide ? 0x4000 : 1));
        }
        if (shape == 4 || shape == 6 || shape == 8 || shape == 10
                || shape == 13 || shape == 15) {
            char[] saved = left;
            left = right;
            right = saved;
        }
        input.left = new String(left);
        input.right = shape == 0 ? input.left : shape == 1
                ? new String(input.left) : new String(right);
        input.expectedCompare = compare(left, right);
        input.expectedEquals = input.expectedCompare == 0;
        require(input.left.compareTo(input.right) == input.expectedCompare);
        require(input.left.equals(input.right) == input.expectedEquals);
    }

    private static void prepareSearch(Input input, int length, int seed, boolean wide) {
        int[] needleSizes = {1, 2, 4, 16};
        int count = Math.min(needleSizes[(seed / 8) & 3], Math.max(1, length));
        boolean wideNeedle = wide && (seed & 1) == 0;
        if (wide && !wideNeedle && length > 1) {
            count = Math.min(count, length - 1);
        }
        char[] source = text(length, seed + 101, wide);
        char[] needle = text(count, seed + 211, wideNeedle);
        int shape = seed & 7;
        boolean miss = shape == 4 || shape == 5;
        if ((seed & 31) == 0) {
            needle = new char[0];
        } else if (miss) {
            for (int attempt = 0; attempt < 8 && indexOf(source, needle, 0) >= 0; attempt++) {
                needle = text(count, seed + 307 + 37 * attempt, wideNeedle);
            }
            if (indexOf(source, needle, 0) >= 0) {
                needle[(seed / 8) % count] = wideNeedle ? '\u7fff' : '\u00ff';
            }
        } else if (length >= count) {
            int at = shape < 2 ? 0 : shape < 4 ? length - count : (length - count) / 2;
            System.arraycopy(needle, 0, source, at, count);
            if (wide && !wideNeedle && length > count) {
                source[at == 0 ? length - 1 : 0] = '\u6fff';
            }
            int earlier;
            while ((earlier = indexOf(source, needle, 0)) < at && earlier >= 0) {
                source[earlier] = '\u0001';
            }
        }
        input.from = (seed & 31) == 1 ? -7 : (seed & 31) == 2 ? length + 7 : 0;
        input.haystack = new String(source);
        input.needle = new String(needle);
        input.expectedSearch = indexOf(source, needle, input.from);
        require(input.haystack.indexOf(input.needle, input.from) == input.expectedSearch);
    }

    private static void prepareCharacters(Input input, int length, int seed, boolean wide) {
        char[] source = text(length, seed + 419, wide);
        char character = (char) (wide && (seed & 4) == 0 ? 0x6200 + (seed & 31) : 'a' + seed % 26);
        for (int i = 0; i < length; i++) {
            if (source[i] == character) {
                source[i] = '?';
            }
        }
        if ((seed & 3) < 2 && length != 0) {
            source[(seed & 3) == 0 ? 0 : length - 1] = character;
        }
        input.character = character;
        input.charSource = new String(source);
        input.expectedChar = indexOf(source, new char[]{character}, input.from);
        require(input.charSource.indexOf(input.character, input.from) == input.expectedChar);
    }

    private static void prepareConstruction(Input input, int length, int seed, boolean wide) {
        char[] source = text(length, seed + 523, false);
        int shape = seed & 7;
        if (wide && shape >= 4 && length != 0) {
            if (shape == 7) {
                for (int i = 0; i < length; i++) {
                    source[i] |= 0x4000;
                }
            } else {
                int at = shape == 4 ? 0 : shape == 5 ? length / 2 : length - 1;
                source[at] |= 0x4000;
            }
        }
        input.construction = source;
        checkString(new String(source), source);
    }

    private static void prepareCopy(Input input, int length, int seed, boolean wide) {
        char[] source = text(length + 4, seed + 631, wide);
        input.begin = seed & 3;
        int count = (seed & 4) == 0 ? length : length / 2;
        input.end = input.begin + count;
        input.destination = 1 + seed % 3;
        input.buffer = new char[input.destination + count + 3];
        Arrays.fill(input.buffer, '\ua55a');
        char[] expected = input.buffer.clone();
        for (int i = 0; i < count; i++) {
            expected[input.destination + i] = source[input.begin + i];
        }
        input.expectedCopy = expected;
        input.copySource = new String(source);
        input.copySource.getChars(input.begin, input.end, input.buffer, input.destination);
        for (int i = 0; i < expected.length; i++) {
            require(input.buffer[i] == expected[i]);
        }
        input.first = input.destination;
        input.middle = input.destination + count / 2;
        input.last = input.destination + Math.max(0, count - 1);
    }

    private static char[] text(int length, int seed, boolean wide) {
        char[] result = new char[length];
        int bits = seed * 0x45d9f3b;
        for (int i = 0; i < length; i++) {
            bits = bits * 1664525 + 1013904223;
            char value = switch (i % 11) {
                case 0 -> '/';
                case 5 -> '-';
                case 10 -> (char) ('0' + ((bits >>> 16) % 10));
                default -> (char) ('a' + ((bits >>> 16) % 26));
            };
            if (i % 17 == 8) {
                value = (char) (0xc0 + (seed & 31));
            }
            result[i] = (char) (wide && i % 7 == 0 ? value | 0x4000 : value);
        }
        return result;
    }

    private static int compare(char[] left, char[] right) {
        for (int i = 0; i < Math.min(left.length, right.length); i++) {
            if (left[i] != right[i]) {
                return left[i] - right[i];
            }
        }
        return left.length - right.length;
    }

    private static int indexOf(char[] source, char[] needle, int from) {
        int start = Math.max(0, from);
        if (needle.length == 0) {
            return Math.min(start, source.length);
        }
        for (int i = start; i <= source.length - needle.length; i++) {
            int j = 0;
            while (j < needle.length && source[i + j] == needle[j]) {
                j++;
            }
            if (j == needle.length) {
                return i;
            }
        }
        return -1;
    }

    private static void checkString(String actual, char[] expected) {
        require(actual.length() == expected.length);
        for (int i = 0; i < expected.length; i++) {
            require(actual.charAt(i) == expected[i]);
        }
    }

    private static void require(boolean valid) {
        if (!valid) {
            throw new AssertionError("Invalid mixed String fixture");
        }
    }

    private Input next() {
        return inputs[cursor++ & 127];
    }

    @Benchmark
    public int compareString() {
        Input input = next();
        return input.left.compareTo(input.right);
    }

    @Benchmark
    public boolean equalsString() {
        Input input = next();
        return input.left.equals(input.right);
    }

    @Benchmark
    public int searchString() {
        Input input = next();
        return input.haystack.indexOf(input.needle, input.from);
    }

    @Benchmark
    public int searchChar() {
        Input input = next();
        return input.charSource.indexOf(input.character, input.from);
    }

    @Benchmark
    public String constructString() {
        return new String(next().construction);
    }

    @Benchmark
    public int copyChars() {
        Input input = next();
        input.copySource.getChars(input.begin, input.end, input.buffer, input.destination);
        publishedCopy = input.buffer;
        return input.buffer[input.first] + 31 * input.buffer[input.middle]
                + 997 * input.buffer[input.last];
    }
}
