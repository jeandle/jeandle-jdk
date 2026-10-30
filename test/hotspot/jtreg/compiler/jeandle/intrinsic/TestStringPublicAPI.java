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

/*
 * @test
 * @summary Compiled public String APIs preserve UTF16 code units and supplementary code points
 * @library /test/lib
 * @run main/othervm/timeout=180 compiler.jeandle.intrinsic.TestStringPublicAPI
 */
package compiler.jeandle.intrinsic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

import jdk.test.lib.process.ProcessTools;

public class TestStringPublicAPI {
    private static final String[] ROOTS = {"compare", "equal", "search", "codePoint", "copy"};

    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            warmup();
            exercise();
            System.out.println("Public String API PASSED");
            return;
        }
        Path log = Files.createTempFile("string-public-api", ".xml");
        Path dump = Files.createTempDirectory("string-public-ir");
        var command = new ArrayList<>(List.of("-Xmixed", "-Xbatch", "-XX:CompileThreshold=100", "-XX:-TieredCompilation",
                "-XX:+UseJeandleCompiler", "-XX:+UnlockDiagnosticVMOptions", "-XX:+LogCompilation",
                "-XX:LogFile=" + log, "-Xlog:jeandle=debug",
                "-XX:+JeandleDumpIR", "-XX:JeandleDumpDirectory=" + dump,
                "-XX:CompileCommand=inline,java.lang.String::compareTo",
                "-XX:CompileCommand=inline,java.lang.String::equals",
                "-XX:CompileCommand=inline,java.lang.String::indexOf",
                "-XX:CompileCommand=inline,java.lang.String::getChars"));
        for (String root : ROOTS) {
            command.add("-XX:CompileCommand=compileonly," + TestStringPublicAPI.class.getName() + "::" + root);
        }
        command.add(TestStringPublicAPI.class.getName());
        command.add("child");
        var output = ProcessTools.executeTestJava(command.toArray(String[]::new));
        output.shouldHaveExitValue(0).shouldContain("Public String API PASSED");
        output.shouldContain("StringLatin1.compareTo")
                .shouldContain("StringUTF16.compareTo")
                .shouldContain("StringLatin1.indexOf")
                .shouldContain("StringUTF16.indexOf")
                .shouldContain("StringUTF16.getChars")
                .shouldContain("is parsed as intrinsic");
        System.out.println("PUBLIC-STRING-IR:" + dump);
        String xml = Files.readString(log);
        for (String root : ROOTS) {
            if (xml.lines().noneMatch(line -> line.contains("<nmethod")
                    && line.contains("compiler='jeandle'")
                    && line.contains(TestStringPublicAPI.class.getName() + " " + root + " "))) {
                throw new AssertionError("Missing compiled public API root: " + root);
            }
            String stem = TestStringPublicAPI.class.getName().replace('.', '_') + "_" + root + "_";
            boolean realBody = false;
            try (var files = Files.list(dump)) {
                for (Path file : files.filter(path -> path.getFileName().toString().startsWith(stem)
                        && path.getFileName().toString().endsWith("_optimized.ll")).toList()) {
                    String ir = Files.readString(file);
                    int begin = ir.indexOf("define hotspotcc");
                    int end = ir.indexOf("\n}", begin);
                    if (begin < 0 || end < 0) throw new AssertionError("Missing root body: " + file);
                    String body = ir.substring(begin, end);
                    realBody |= body.contains("load ") && body.contains("ret ");
                }
            }
            if (!realBody) throw new AssertionError("No executable public API body: " + root);
        }
    }

    private static volatile int warmupSink;

    private static void warmup() {
        String[] inputs = {"ababa", "a\u0100b\uffff", "a\ud83d\ude00b\ud83d\ude00", "\ud800x\udc00"};
        char[] destination = new char[32];
        for (int i = 0; i < 2000; i++) {
            String left = inputs[i & 3];
            String right = inputs[(i >>> 2) & 3];
            warmupSink = compare(left, right) + (equal(left, right) ? 1 : 0)
                    + search(left, right, 0) + codePoint(left, (i & 4) == 0 ? 'a' : 0x1f600, 0);
            copy(left, 0, left.length(), destination, 1);
        }
    }

    private static void exercise() {
        String[] values = {"", "a", "ababa", "\u0000\u007f\u0080\u00ff",
                "a\u0100b\uffff", "a\ud83d\ude00b\ud83d\ude00", "\ud800x\udc00",
                "\ud800\udc00\udbff\udfff", "\udfff\ud800", "a".repeat(257) + "b"};
        for (String left : values) {
            for (String right : values) {
                require(compare(left, right) == referenceCompare(left, right), "compare");
                require(equal(left, new String(right.toCharArray())) == referenceEquals(left, right), "equals");
                for (int from : new int[]{-3, 0, 1, left.length() / 2, left.length(), Integer.MAX_VALUE}) {
                    require(search(left, right, from) == referenceSearch(left, right, from), "substring");
                }
            }
            for (int cp : new int[]{-1, 0, 'a', 255, 256, 0xd800, 0xdc00, 0xd83d, 0xde00,
                    0x10000, 0x1f600, 0x10ffff, 0x110000, Integer.MAX_VALUE}) {
                for (int from : new int[]{-1, 0, 1, 2, left.length(), Integer.MAX_VALUE}) {
                    require(codePoint(left, cp, from) == referenceCodePoint(left, cp, from), "code point");
                }
            }
            for (int start = 0; start <= left.length(); start++) {
                char[] destination = new char[left.length() + 4];
                Arrays.fill(destination, '\ua55a');
                copy(left, start, left.length(), destination, 2);
                for (int i = 0; i < destination.length; i++) {
                    char expected = i >= 2 && i < 2 + left.length() - start
                            ? left.charAt(start + i - 2) : '\ua55a';
                    require(destination[i] == expected, "getChars contents and sentinels");
                }
            }
        }
        require(codePoint("x\ud83d\ude00y", 0x1f600, 1) == 1, "supplementary pair");
        require(codePoint("x\ud83d\ude00y", 0x1f600, 2) == -1, "from inside pair");
        require(search("x\ud83d\ude00y", "\ude00", 0) == 2, "substring searches code units");
        searchBoundaries();
    }

    private static void searchBoundaries() {
        for (boolean wide : new boolean[]{false, true}) {
            char first = wide ? '\u1234' : 'a';
            char second = (char) (first + 1);
            char absent = (char) (first + 2);
            for (int length : new int[]{2, 3, 4, 5, 6, 7, 15, 16, 17, 31, 32, 33, 64, 257}) {
                char[] repeated = new char[length];
                Arrays.fill(repeated, first);
                verifySearchOffsets(new String(repeated), String.valueOf(first));
                verifySearchOffsets(new String(repeated), String.valueOf(absent));
                repeated[length - 1] = absent;
                verifySearchOffsets(new String(repeated), String.valueOf(absent));
                if (!wide) {
                    repeated[0] = '\u1234';
                    verifySearchOffsets(new String(repeated), String.valueOf(first));
                    verifySearchOffsets(new String(repeated), String.valueOf(absent));
                }
                for (int count : new int[]{2, 3, 4, 5, 7, 8, 9, 15, 16, 17, 31, 32, 33}) {
                    if (count > length) continue;
                    char[] source = new char[length];
                    for (int i = 0; i < length; i++) source[i] = (i & 1) == 0 ? first : second;
                    char[] needle = new char[count];
                    Arrays.fill(needle, first);
                    needle[1] = absent;
                    needle[count - 1] = second;
                    verifySearchOffsets(new String(source), new String(needle));
                    // Place two matches, including the last legal candidate.
                    System.arraycopy(needle, 0, source, 0, count);
                    System.arraycopy(needle, 0, source, length - count, count);
                    verifySearchOffsets(new String(source), new String(needle));
                    if (!wide) {
                        // Preserve a Latin1 needle with a UTF16 source.
                        source[0] = '\u1234';
                        verifySearchOffsets(new String(source), new String(needle));
                    }
                    if (count >= 4) {
                        Arrays.fill(source, first);
                        Arrays.fill(needle, first);
                        needle[count / 2] = absent;
                        verifySearchOffsets(new String(source), new String(needle));
                        System.arraycopy(needle, 0, source, length - count, count);
                        verifySearchOffsets(new String(source), new String(needle));
                    }
                }
            }
        }
    }

    private static void verifySearchOffsets(String source, String needle) {
        for (int from : new int[]{-1, 0, 1, 2, 7, 15, 16, 31, source.length() - needle.length(),
                source.length(), Integer.MAX_VALUE}) {
            require(search(source, needle, from) == referenceSearch(source, needle, from),
                    "dense candidates and short search boundaries");
        }
    }

    public static int compare(String left, String right) { return left.compareTo(right); }
    public static boolean equal(String left, String right) { return left.equals(right); }
    public static int search(String source, String target, int from) { return source.indexOf(target, from); }
    public static int codePoint(String source, int point, int from) { return source.indexOf(point, from); }
    public static void copy(String source, int start, int end, char[] destination, int offset) {
        source.getChars(start, end, destination, offset);
    }

    private static int referenceCompare(String left, String right) {
        for (int i = 0; i < Math.min(left.length(), right.length()); i++) {
            int difference = left.charAt(i) - right.charAt(i);
            if (difference != 0) return difference;
        }
        return left.length() - right.length();
    }

    private static boolean referenceEquals(String left, String right) {
        return left.length() == right.length() && referenceCompare(left, right) == 0;
    }

    private static int referenceSearch(String source, String target, int from) {
        int begin = Math.max(0, from);
        if (target.isEmpty()) return Math.min(begin, source.length());
        for (int i = begin; i <= source.length() - target.length(); i++) {
            int j = 0;
            while (j < target.length() && source.charAt(i + j) == target.charAt(j)) j++;
            if (j == target.length()) return i;
        }
        return -1;
    }

    private static int referenceCodePoint(String source, int point, int from) {
        if (point < 0 || point > 0x10ffff) return -1;
        for (int i = Math.max(0, from); i < source.length(); i++) {
            char first = source.charAt(i);
            if (point < 0x10000) {
                if (first == point) return i;
            } else if (first >= 0xd800 && first <= 0xdbff && i + 1 < source.length()) {
                char second = source.charAt(i + 1);
                if (second >= 0xdc00 && second <= 0xdfff
                        && 0x10000 + ((first - 0xd800) << 10) + second - 0xdc00 == point) return i;
            }
        }
        return -1;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
