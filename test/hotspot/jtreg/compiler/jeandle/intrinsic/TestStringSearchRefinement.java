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
 * @summary Repetitive substring searches preserve the first match when learning an internal filter
 * @library /test/lib /
 * @compile --patch-module java.base=${test.src} java/lang/JeandleStringIntrinsicCaller.java
 * @run main/othervm/timeout=180 compiler.jeandle.intrinsic.TestStringSearchRefinement
 */
package compiler.jeandle.intrinsic;

import java.lang.reflect.Method;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.regex.Pattern;

import jdk.test.lib.process.ProcessTools;

public class TestStringSearchRefinement {
    private static final String CALLER = "java.lang.JeandleStringIntrinsicCaller";
    private static final int[] LENGTHS = {2, 3, 7, 16, 31, 64, 65, 129};

    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            exercise();
            System.out.println("String search refinement PASSED");
            return;
        }
        for (String width : new String[] {"default", "32"}) {
            Path dumps = Files.createTempDirectory("jeandle-search-refinement");
            Path log = dumps.resolve("compilation.xml");
            System.out.println("SEARCH_REFINEMENT_DUMPS=" + dumps);
            var command = new java.util.ArrayList<String>();
            command.addAll(Arrays.asList(
                    "--patch-module=java.base=" + System.getProperty("test.class.path"),
                    "-Xcomp", "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler",
                    "-XX:+UnlockDiagnosticVMOptions", "-XX:+LogCompilation", "-XX:LogFile=" + log,
                    "-XX:+JeandleDumpIR", "-XX:JeandleDumpDirectory=" + dumps,
                    "-XX:CompileCommand=compileonly," + CALLER + "::indexOfI*"));
            if (!width.equals("default")) {
                command.add("-XX:MaxVectorSize=" + width);
            }
            command.add(TestStringSearchRefinement.class.getName());
            command.add("child");
            ProcessTools.executeTestJava(command.toArray(String[]::new))
                    .shouldHaveExitValue(0).shouldContain("String search refinement PASSED");
            System.out.println("SEARCH_REFINEMENT_SEMANTICS=" + width + " PASS");
            String xml = Files.readString(log);
            for (String method : new String[] {"indexOfIL", "indexOfIU", "indexOfIUL"}) {
                if (!xml.lines().anyMatch(line -> line.contains("<nmethod")
                        && line.contains("compiler='jeandle'")
                        && line.contains(CALLER + " " + method + " "))) {
                    throw new AssertionError("Missing executed Jeandle search: " + method);
                }
                try (var files = Files.list(dumps)) {
                    Path irFile = files.filter(path -> path.getFileName().toString()
                            .startsWith("java_lang_JeandleStringIntrinsicCaller_" + method + "_")
                            && path.toString().endsWith("_optimized.ll")).findFirst()
                            .orElseThrow(() -> new AssertionError("Missing search IR: " + method));
                    String ir = Files.readString(irFile);
                    // The delayed learning budget survives across vector
                    // windows; a fixed third-character probe has no budget.
                    if (!Pattern.compile("phi i32 [^\\n]*\\[ -4, ").matcher(ir).find()) {
                        throw new AssertionError("Missing delayed filter-learning state: " + method);
                    }
                    // A nonempty learned mask contributes a new budget to
                    // the candidate entry; empty masks retain the filter.
                    if (!Pattern.compile("%refinement\\.initial[^=\\n]* = phi i32 [^\\n]*\\[ -4, ")
                            .matcher(ir).find()) {
                        throw new AssertionError("Missing filter-learning budget refresh: " + method);
                    }
                }
            }
        }
    }

    private static void exercise() throws Exception {
        Class<?> caller = Class.forName(CALLER);
        for (int encoding : new int[] {0, 1, 3}) {
            boolean sourceWide = (encoding & 1) != 0;
            boolean targetWide = (encoding & 2) != 0;
            String name = encoding == 0 ? "indexOfIL" : encoding == 1 ? "indexOfIUL" : "indexOfIU";
            Method method = caller.getMethod(name, byte[].class, int.class,
                    byte[].class, int.class, int.class);
            char base = targetWide ? '\u1261' : 'a';
            for (int length : LENGTHS) {
                for (int period : new int[] {1, 2, 3, 7}) {
                    for (int difference : new int[] {0, 1, 2, length / 2, length - 2}) {
                        if (difference >= length) continue;
                        char[] needle = periodic(length, period, base);
                        needle[difference] = (char) (base + period + 5);
                        for (int hit : new int[] {-1, 17, 73}) {
                            char[] source = periodic(length + 96, period, base);
                            if (hit >= 0) {
                                System.arraycopy(needle, 0, source, hit, needle.length);
                            }
                            for (int from : new int[] {0, 1, 3, 72, 97}) {
                                check(method, source, needle, from, sourceWide, targetWide);
                            }
                        }
                    }
                }
            }
            // Learning a late internal position must not extend a partial
            // final vector window past the validated source payload.
            char[] source = new char[120];
            char[] needle = new char[65];
            Arrays.fill(source, base);
            Arrays.fill(needle, base);
            needle[63] = (char) (base + 1);
            for (int from : new int[] {0, 1, 31, 32, 47, 48, 55, 56}) {
                check(method, source, needle, from, sourceWide, targetWide);
            }
            // Several complete matches in the suffix must still return the
            // earliest candidate after many rejected periodic candidates.
            source = new char[384];
            needle = new char[33];
            Arrays.fill(source, base);
            Arrays.fill(needle, base);
            needle[13] = (char) (base + 1);
            for (int hit : new int[] {97, 161, 257}) {
                System.arraycopy(needle, 0, source, hit, needle.length);
            }
            for (int from : new int[] {0, 1, 96, 97, 98, 160, 162, 258}) {
                check(method, source, needle, from, sourceWide, targetWide);
            }
            // An irregular prefix can select a position that ceases to
            // reject candidates in the regular suffix. Refreshing that
            // position must preserve both bounds and earliest-match order.
            for (int period : new int[] {1, 2, 3}) {
                source = periodic(2048, period, base);
                needle = periodic(129, period, base);
                for (int i : new int[] {2, 5, 8, 11, 14, 17, 20, 23}) {
                    source[i] = (char) (base + 4);
                }
                needle[64] = (char) (base + 5);
                for (int hit : new int[] {-1, 97, 385, 1919}) {
                    char[] input = source.clone();
                    if (hit >= 0) {
                        System.arraycopy(needle, 0, input, hit, needle.length);
                    }
                    for (int from : new int[] {0, 1, 22, 23, 24, 31, 32, 96, 98, 384, 386, 1918, 1920}) {
                        check(method, input, needle, from, sourceWide, targetWide);
                    }
                }
            }
        }
    }

    private static char[] periodic(int count, int period, char base) {
        char[] chars = new char[count];
        for (int i = 0; i < count; i++) {
            chars[i] = (char) (base + i % period);
        }
        return chars;
    }

    private static void check(Method method, char[] source, char[] needle, int from,
                              boolean sourceWide, boolean targetWide) throws Exception {
        int expected = -1;
        for (int i = from; i <= source.length - needle.length; i++) {
            int j = 0;
            while (j < needle.length && source[i + j] == needle[j]) j++;
            if (j == needle.length) {
                expected = i;
                break;
            }
        }
        int actual = (int) method.invoke(null, encode(source, sourceWide), source.length,
                encode(needle, targetWide), needle.length, from);
        if (actual != expected) {
            throw new AssertionError(method.getName() + ": " + actual + " != " + expected
                    + ", source=" + source.length + ", needle=" + needle.length + ", from=" + from);
        }
    }

    private static byte[] encode(char[] chars, boolean wide) {
        byte[] bytes = new byte[chars.length * (wide ? 2 : 1)];
        boolean little = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;
        for (int i = 0; i < chars.length; i++) {
            if (wide) {
                bytes[2 * i] = (byte) (little ? chars[i] : chars[i] >>> 8);
                bytes[2 * i + 1] = (byte) (little ? chars[i] >>> 8 : chars[i]);
            } else {
                bytes[i] = (byte) chars[i];
            }
        }
        return bytes;
    }
}
