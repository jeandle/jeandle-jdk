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
 * @summary Character search uses bounded vector tiers and bypasses vector setup for short inputs
 * @library /test/lib /
 * @compile --patch-module java.base=${test.src} java/lang/JeandleStringIntrinsicCaller.java
 * @run main/othervm/timeout=180 compiler.jeandle.intrinsic.TestStringCharSearchTiers
 */
package compiler.jeandle.intrinsic;

import java.lang.reflect.Method;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.regex.Pattern;

import jdk.test.lib.process.ProcessTools;

public class TestStringCharSearchTiers {
    private static final String CALLER = "java.lang.JeandleStringIntrinsicCaller";
    private static final int[] LENGTHS = {0, 1, 7, 8, 9, 12, 15, 16, 17, 31, 32, 33, 63, 64, 65};

    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            exercise();
            System.out.println("String character search tiers PASSED");
            return;
        }
        Path dumps = Files.createTempDirectory("jeandle-string-char-tiers");
        Path log = dumps.resolve("compilation.xml");
        ProcessTools.executeTestJava(
                "--patch-module=java.base=" + System.getProperty("test.class.path"),
                "-Xmixed", "-Xbatch", "-XX:CompileThreshold=100", "-XX:-TieredCompilation",
                "-XX:+UseJeandleCompiler", "-XX:+UnlockDiagnosticVMOptions",
                "-XX:+LogCompilation", "-XX:LogFile=" + log,
                "-XX:+JeandleDumpIR", "-XX:JeandleDumpDirectory=" + dumps,
                "-XX:CompileCommand=compileonly," + CALLER + "::indexOfL_char",
                "-XX:CompileCommand=compileonly," + CALLER + "::indexOfU_char",
                TestStringCharSearchTiers.class.getName(), "child")
                .shouldHaveExitValue(0).shouldContain("String character search tiers PASSED");
        String xml = Files.readString(log);
        for (String method : new String[]{"indexOfL_char", "indexOfU_char"}) {
            if (!xml.lines().anyMatch(line -> line.contains("<nmethod") && line.contains("compiler='jeandle'")
                    && line.contains(CALLER + " " + method + " "))) {
                throw new AssertionError("Missing executed Jeandle character search: " + method);
            }
        }
        try (var paths = Files.list(dumps)) {
            var files = paths.filter(path -> path.getFileName().toString()
                    .startsWith("java_lang_JeandleStringIntrinsicCaller_indexOfU_char_")
                    && path.toString().endsWith("_optimized.ll")).toList();
            String ir = files.stream().map(path -> {
                try {
                    return Files.readString(path);
                } catch (java.io.IOException exception) {
                    throw new java.io.UncheckedIOException(exception);
                }
            }).filter(text -> text.contains("load <16 x i16>")).findFirst()
                    .orElseThrow(() -> new AssertionError("UTF16 vector search intrinsic was not reached"));
            if (!ir.contains("load <8 x i16>")
                    || !Pattern.compile("icmp eq <8 x i16>").matcher(ir).find()) {
                throw new AssertionError("Missing bounded eight-word character search tier");
            }
        }
        try (var paths = Files.list(dumps)) {
            var files = paths.filter(path -> path.getFileName().toString()
                    .startsWith("java_lang_JeandleStringIntrinsicCaller_indexOfL_char_")
                    && path.toString().endsWith("_optimized.ll")).toList();
            String ir = files.stream().map(path -> {
                try {
                    return Files.readString(path);
                } catch (java.io.IOException exception) {
                    throw new java.io.UncheckedIOException(exception);
                }
            }).filter(text -> text.contains("load <16 x i8>")).findFirst()
                    .orElseThrow(() -> new AssertionError("Latin1 vector search intrinsic was not reached"));
            if (!Pattern.compile("%initial\\.vector[\\w.]* = icmp (?:samesign )?"
                    + "(?:(?:sge|uge) i32 %initial\\.left[\\w.]*, 16"
                    + "|(?:sgt|ugt) i32 %initial\\.left[\\w.]*, 15)")
                    .matcher(ir).find()
                    || !Pattern.compile("br i1 %initial\\.vector[\\w.]*, label %vector\\.prepare[\\w.]*, label %tail\\.check")
                    .matcher(ir).find()) {
                throw new AssertionError("Short Latin1 search does not bypass vector preparation");
            }
        }
    }

    private static void exercise() throws Exception {
        Class<?> caller = Class.forName(CALLER);
        for (boolean wide : new boolean[]{false, true}) {
            Method method = caller.getMethod(wide ? "indexOfU_char" : "indexOfL_char",
                    byte[].class, int.class, int.class, int.class);
            byte[] warmup = new byte[256];
            for (int i = 0; i < 3000; i++) {
                verify(method, -1, warmup, 255, 1, 64);
            }
            for (int length : LENGTHS) {
                for (int from : new int[]{1, 3}) {
                    int max = from + length;
                    for (int target : wide ? new int[]{255, 0x12ff, 0xffff} : new int[]{255}) {
                        for (int lane = -1; lane < length; lane++) {
                            byte[] bytes = new byte[(max + 2) * (wide ? 2 : 1)];
                            if (wide) {
                                for (int i = 0; i < max + 2; i++) put(bytes, i, 0x1234);
                            } else {
                                Arrays.fill(bytes, (byte) 0x34);
                            }
                            // A match outside either bound must not affect the result.
                            set(bytes, 0, target, wide);
                            set(bytes, max, target, wide);
                            if (lane >= 0) set(bytes, from + lane, target, wide);
                            verify(method, lane < 0 ? -1 : from + lane, bytes, target, from, max);
                            if (wide) {
                                for (int invalid : new int[]{-1, 65536, Integer.MAX_VALUE}) {
                                    verify(method, -1, bytes, invalid, from, max);
                                }
                            }
                            if (lane >= 0 && from + lane + 1 < max) {
                                set(bytes, from + lane + 1, target, wide);
                                verify(method, from + lane, bytes, target, from, max);
                            }
                        }
                    }
                }
            }
        }
    }

    private static void verify(Method method, int expected, byte[] bytes, int target, int from, int max)
            throws Exception {
        int actual = (int) method.invoke(null, bytes, target, from, max);
        if (actual != expected) {
            throw new AssertionError(method.getName() + ": " + actual + " != " + expected
                    + ", from=" + from + ", max=" + max + ", target=" + target);
        }
    }

    private static void set(byte[] bytes, int index, int value, boolean wide) {
        if (wide) {
            put(bytes, index, value);
        } else {
            bytes[index] = (byte) value;
        }
    }

    private static void put(byte[] bytes, int index, int value) {
        boolean little = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;
        bytes[2 * index] = (byte) (little ? value : value >>> 8);
        bytes[2 * index + 1] = (byte) (little ? value >>> 8 : value);
    }
}
