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
 * @summary Mixed-width substring confirmation computes equality without ordered mismatch recovery
 * @library /test/lib /
 * @compile --patch-module java.base=${test.src} java/lang/JeandleStringIntrinsicCaller.java
 * @run main/othervm/timeout=180 compiler.jeandle.intrinsic.TestStringSubstringConfirmation
 */
package compiler.jeandle.intrinsic;

import java.lang.reflect.Method;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jdk.test.lib.process.ProcessTools;

public class TestStringSubstringConfirmation {
    private static final String CALLER = "java.lang.JeandleStringIntrinsicCaller";
    private static final int[] LENGTHS = {1, 2, 3, 4, 7, 8, 9, 15, 16, 17, 63, 64, 65, 129};

    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            exercise();
            System.out.println("String substring confirmation PASSED");
            return;
        }
        Path dumps = Files.createTempDirectory("jeandle-string-confirmation");
        Path log = dumps.resolve("compilation.xml");
        ProcessTools.executeTestJava(
                "--patch-module=java.base=" + System.getProperty("test.class.path"),
                "-Xcomp", "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler",
                "-XX:+UnlockDiagnosticVMOptions", "-XX:+LogCompilation", "-XX:LogFile=" + log,
                "-XX:+JeandleDumpIR", "-XX:JeandleDumpDirectory=" + dumps,
                "-XX:CompileCommand=compileonly," + CALLER + "::indexOfIU*",
                "-XX:CompileCommand=compileonly," + CALLER + "::indexOfIL",
                TestStringSubstringConfirmation.class.getName(), "child")
                .shouldHaveExitValue(0).shouldContain("String substring confirmation PASSED");
        String xml = Files.readString(log);
        for (String name : new String[]{"indexOfIL", "indexOfIU", "indexOfIUL"}) {
            if (!xml.lines().anyMatch(line -> line.contains("<nmethod") && line.contains("compiler='jeandle'")
                    && line.contains(CALLER + " " + name + " "))) {
                throw new AssertionError("Missing executed Jeandle search: " + name);
            }
            try (var files = Files.list(dumps)) {
                Path file = files.filter(path -> path.getFileName().toString()
                        .startsWith("java_lang_JeandleStringIntrinsicCaller_" + name + "_")
                        && path.toString().endsWith("_optimized.ll")).findFirst()
                        .orElseThrow(() -> new AssertionError("Missing search IR: " + name));
                String ir = Files.readString(file);
                if (!ir.contains("packed.")) {
                    throw new AssertionError("Missing bounded short-needle confirmation");
                }
                if (!ir.contains("packed.filter.aligned") || !ir.contains("packed.filter.pending")) {
                    throw new AssertionError("Missing bounded ordered endpoint filter: " + name);
                }
                if (!name.equals("indexOfIUL")) continue;
                if (!ir.contains("zext <8 x i8>")
                        || !Pattern.compile("icmp (?:eq|ne) <8 x i16>").matcher(ir).find()) {
                    throw new AssertionError("Missing live mixed-width vector equality confirmation");
                }
                // The scan needs mask indices, but equality confirmation never
                // needs to subtract two unsigned character values.
                Set<String> characters = new HashSet<>();
                Matcher widen = Pattern.compile("(%[\\w.]+) = zext (?:nneg )?i(?:8|16) [^\\n]+ to i32")
                        .matcher(ir);
                while (widen.find()) characters.add(widen.group(1));
                Matcher difference = Pattern.compile("= sub (?:nuw |nsw )*i32 (%[\\w.]+), (%[\\w.]+)")
                        .matcher(ir);
                while (difference.find()) {
                    if (characters.contains(difference.group(1)) && characters.contains(difference.group(2))) {
                        throw new AssertionError("Substring confirmation computes an ordered character difference");
                    }
                }
                if (Pattern.compile("extractelement <(?:4|8) x i16>[^\\n]*, i\\d+ %")
                                .matcher(ir).find()) {
                    throw new AssertionError("Substring confirmation recovers an ordered mismatch lane");
                }
            }
        }
    }

    private static void exercise() throws Exception {
        Class<?> caller = Class.forName(CALLER);
        for (int encoding : new int[]{0, 1, 3}) {
            boolean sourceWide = (encoding & 1) != 0;
            boolean targetWide = (encoding & 2) != 0;
            Method method = caller.getMethod(encoding == 0 ? "indexOfIL"
                            : encoding == 1 ? "indexOfIUL" : "indexOfIU",
                    byte[].class, int.class, byte[].class, int.class, int.class);
            for (int count : new int[]{2, 3, 4}) {
                char[] needle = new char[count];
                Arrays.fill(needle, targetWide ? '\u12ff' : '\u00ff');
                needle[count - 1]--;
                for (int candidates : new int[]{1, 2, 3, 4, 7, 8, 15, 16, 17, 1024}) {
                    for (int at : new int[]{-1, 0, candidates / 2, candidates - 1}) {
                        char[] source = new char[count + candidates - 1];
                        Arrays.fill(source, needle[0]);
                        if (at >= 0) System.arraycopy(needle, 0, source, at, count);
                        for (int from : new int[]{0, 1, candidates - 1, candidates}) {
                            check(method, source, needle, sourceWide, targetWide, from);
                        }
                    }
                }
                {
                    char[] marked = new char[count];
                    Arrays.fill(marked, targetWide ? '\u1280' : '\u0080');
                    marked[0] = targetWide ? '\u12ff' : '\u00ff';
                    for (int length : new int[]{15, 16, 17, 18, 19, 20}) {
                        for (int at : new int[]{-1, 0, length - count, length - count + 1}) {
                            char[] source = new char[length];
                            Arrays.fill(source, marked[1]);
                            // A marker after the last complete candidate may
                            // set a filter lane but cannot establish a match.
                            if (at >= 0) source[at] = marked[0];
                            for (int from : new int[]{0, 1, 2, length - count, length}) {
                                check(method, source, marked, sourceWide, targetWide, from);
                            }
                        }
                    }
                }
            }
        }
        for (boolean targetWide : new boolean[]{false, true}) {
            Method method = caller.getMethod(targetWide ? "indexOfIU" : "indexOfIUL",
                    byte[].class, int.class, byte[].class, int.class, int.class);
            for (int length : LENGTHS) {
                char[] needle = new char[length];
                char base = targetWide ? '\u1261' : 'a';
                Arrays.fill(needle, base);
                for (int lane = 0; lane < length; lane++) {
                    char[] source = new char[length + 80];
                    Arrays.fill(source, base);
                    // A sole high-byte mismatch must reject this region even
                    // when every low byte and every other position matches.
                    source[3 + lane] ^= 0x4000;
                    for (int from : new int[]{0, 3, 7, 79, 80, 81}) {
                        check(method, source, needle, targetWide, from);
                    }
                    needle[lane] = (char) (base + 1);
                    Arrays.fill(source, base);
                    System.arraycopy(needle, 0, source, source.length - length, length);
                    for (int from : new int[]{0, 1, 3, 79, 80, 81}) {
                        check(method, source, needle, targetWide, from);
                    }
                    needle[lane] = base;
                }
            }
        }
    }

    private static void check(Method method, char[] source, char[] needle, boolean targetWide, int from)
            throws Exception {
        check(method, source, needle, true, targetWide, from);
    }

    private static void check(Method method, char[] source, char[] needle,
                              boolean sourceWide, boolean targetWide, int from) throws Exception {
        int expected = -1;
        for (int i = from; i <= source.length - needle.length; i++) {
            int j = 0;
            while (j < needle.length && source[i + j] == needle[j]) j++;
            if (j == needle.length) {
                expected = i;
                break;
            }
        }
        byte[] sourceBytes = encode(source, sourceWide);
        byte[] targetBytes = encode(needle, targetWide);
        int actual = (int) method.invoke(null, sourceBytes, source.length, targetBytes, needle.length, from);
        if (actual != expected || new String(source).indexOf(new String(needle), from) != expected) {
            throw new AssertionError(method.getName() + ": " + actual + " != " + expected
                    + ", length=" + needle.length + ", from=" + from);
        }
    }

    private static byte[] encode(char[] chars, boolean wide) {
        byte[] bytes = new byte[chars.length * (wide ? 2 : 1)];
        for (int i = 0; i < chars.length; i++) {
            if (wide) {
                boolean little = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;
                bytes[2 * i] = (byte) (little ? chars[i] : chars[i] >>> 8);
                bytes[2 * i + 1] = (byte) (little ? chars[i] >>> 8 : chars[i]);
            } else {
                bytes[i] = (byte) chars[i];
            }
        }
        return bytes;
    }
}
