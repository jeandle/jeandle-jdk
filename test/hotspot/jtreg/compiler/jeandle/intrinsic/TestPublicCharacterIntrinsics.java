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
 * @summary Public Character predicates recognize late-resolved Latin1 inlinees
 * @library /test/lib
 * @compile --patch-module java.base=${test.src} java/lang/JeandleLateCharacterCaller.java
 * @run main/othervm/timeout=180 compiler.jeandle.intrinsic.TestPublicCharacterIntrinsics
 */
package compiler.jeandle.intrinsic;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestPublicCharacterIntrinsics {
    private static final String CLASS = TestPublicCharacterIntrinsics.class.getName();
    private static final String LATE_CALLER = "java.lang.JeandleLateCharacterCaller";
    private static final int[] UNICODE = {0x100, 0x391, 0x3b1, 0x410, 0x430, 0x660,
            0x2000, 0x2028, 0x3000, 0x10400, 0x10428, 0x1d7ce,
            -1, Integer.MIN_VALUE, 0x110000, Integer.MAX_VALUE};

    public static int latin1(int value) {
        int ch = value & 255;
        return (Character.isDigit(ch) ? 1 : 0)
                | (Character.isLowerCase(ch) ? 2 : 0)
                | (Character.isUpperCase(ch) ? 4 : 0)
                | (Character.isWhitespace(ch) ? 8 : 0);
    }

    public static int mixed(int ch) {
        return (Character.isDigit(ch) ? 1 : 0)
                | (Character.isLowerCase(ch) ? 2 : 0)
                | (Character.isUpperCase(ch) ? 4 : 0)
                | (Character.isWhitespace(ch) ? 8 : 0);
    }

    public static int unicode(int value) {
        int ch = (value & 255) + 0x300;
        return (Character.isDigit(ch) ? 1 : 0)
                | (Character.isLowerCase(ch) ? 2 : 0)
                | (Character.isUpperCase(ch) ? 4 : 0)
                | (Character.isWhitespace(ch) ? 8 : 0);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            Method nullable = Class.forName(LATE_CALLER).getMethod("nullable", boolean.class, int.class);
            Method lateLatin1 = Class.forName(LATE_CALLER).getMethod("latin1", int.class);
            // Initialize every provider before compiling the target methods.
            for (int ch : UNICODE) {
                Character.isDigit(ch);
                Character.isLowerCase(ch);
                Character.isUpperCase(ch);
                Character.isWhitespace(ch);
            }
            Character.isDigit('a');
            Character.isLowerCase('a');
            Character.isUpperCase('A');
            Character.isWhitespace(' ');
            long digest = 0;
            for (int repeat = 0; repeat < 16; repeat++) {
                for (int ch = 0; ch < 256; ch++) {
                    digest = digest * 31 + latin1(ch);
                    digest = digest * 31 + mixed(ch);
                    digest = digest * 31 + unicode(ch);
                }
                for (int ch : UNICODE) {
                    digest = digest * 31 + mixed(ch);
                }
                // Test wrapped inputs before expected range traps retire the helper.
                for (int ch : new int[]{'a', 'A', '9', ' ', 0xff, 0x10041, -0xff9f,
                        Integer.MIN_VALUE, 0x100, 0xffff, Integer.MAX_VALUE}) {
                    digest = digest * 31 + nullableResult(nullable, false, ch);
                    digest = digest * 31 + lateLatin1Result(lateLatin1, ch);
                }
                digest = digest * 31 + nullableResult(nullable, true, 'a');
            }
            System.out.println("PUBLIC CHARACTER PASSED digest=" + digest);
            return;
        }
        String expected = digest(launch("reference", null, null));
        for (String mode : List.of("enabled", "disabled", "flag-off", "method-off")) {
            Path dumps = Files.createTempDirectory("public-character-" + mode);
            Path log = dumps.resolve("compilation.xml");
            OutputAnalyzer output = launch(mode, log, dumps);
            Files.writeString(dumps.resolve("stdout.log"), output.getOutput());
            output.shouldContain(expected);
            System.out.println(mode + " compilation evidence: " + dumps);
            String xml = Files.readString(log);
            for (String method : List.of("latin1", "mixed", "unicode")) {
                if (!xml.lines().anyMatch(line -> line.contains("<nmethod ")
                        && line.contains("compiler='jeandle'")
                        && line.contains("method='" + CLASS + " " + method + " "))) {
                    throw new AssertionError("Missing executed Jeandle method: " + method);
                }
                List<Path> ir;
                try (var files = Files.list(dumps)) {
                    ir = files.filter(file -> file.getFileName().toString().startsWith(
                            CLASS.replace('.', '_') + "_" + method + "_"))
                            .filter(file -> file.toString().endsWith("_optimized.ll")).toList();
                }
                if (ir.isEmpty()) {
                    throw new AssertionError("Missing final IR for " + method);
                }
                for (Path file : ir) {
                    String text = Files.readString(file);
                    int start = text.indexOf("define hotspotcc");
                    String body = text.substring(start, text.indexOf("\n}", start));
                    boolean lookup = body.contains("@jeandle.latin1_properties");
                    boolean expectLookup = mode.equals("enabled") && method.equals("latin1");
                    if (method.equals("latin1") || method.equals("unicode") || !mode.equals("enabled")) {
                        if (lookup != expectLookup) {
                            throw new AssertionError("Wrong Latin1 specialization in " + file);
                        }
                    }
                    if (method.equals("mixed") && !body.contains("__jeandle_dynamic_call.java_lang_CharacterData_")) {
                        throw new AssertionError("Missing polymorphic provider dispatch in " + file);
                    }
                }
            }
            if (!xml.lines().anyMatch(line -> line.contains("<nmethod ")
                    && line.contains("compiler='jeandle'")
                    && line.contains("method='" + LATE_CALLER + " nullable "))) {
                throw new AssertionError("Missing executed nullable provider method");
            }
            if (!xml.lines().anyMatch(line -> line.contains("<nmethod ")
                    && line.contains("compiler='jeandle'")
                    && line.contains("method='" + LATE_CALLER + " latin1 "))) {
                throw new AssertionError("Missing executed late Latin1 provider method");
            }
            try (var files = Files.list(dumps)) {
                var ir = files.filter(file -> file.getFileName().toString().startsWith("java_lang_JeandleLateCharacterCaller_latin1_"))
                        .filter(file -> file.toString().endsWith("_optimized.ll")).toList();
                if (ir.isEmpty()) {
                    throw new AssertionError("Missing final late Latin1 IR");
                }
                for (Path file : ir) {
                    String text = Files.readString(file);
                    int start = text.indexOf("define hotspotcc");
                    String body = text.substring(start, text.indexOf("\n}", start));
                    if (body.contains("@jeandle.latin1_properties") != mode.equals("enabled")) {
                        throw new AssertionError("Wrong guarded late Latin1 specialization in " + file);
                    }
                }
            }
        }
    }

    private static int lateLatin1Result(Method method, int ch) throws Exception {
        try {
            return (int) method.invoke(null, ch);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof ArrayIndexOutOfBoundsException && (char) ch > 255) {
                return 32;
            }
            throw e;
        }
    }

    private static int nullableResult(Method method, boolean nullReceiver, int ch) throws Exception {
        try {
            return (int) method.invoke(null, nullReceiver, ch);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof NullPointerException) {
                if (!nullReceiver) {
                    throw e;
                }
                return 16;
            }
            if (e.getCause() instanceof ArrayIndexOutOfBoundsException && !nullReceiver && (char) ch > 255) {
                return 32;
            }
            throw e;
        }
    }

    private static String digest(OutputAnalyzer output) {
        output.shouldHaveExitValue(0).shouldContain("PUBLIC CHARACTER PASSED digest=");
        return output.getOutput().lines().filter(line -> line.startsWith("PUBLIC CHARACTER PASSED"))
                .findFirst().orElseThrow();
    }

    private static OutputAnalyzer launch(String mode, Path log, Path dumps) throws Exception {
        var options = new ArrayList<String>();
        options.add("--patch-module=java.base=" + System.getProperty("test.class.path"));
        if (mode.equals("reference")) {
            options.add("-Xint");
        } else {
            options.addAll(List.of("-Xcomp", "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler",
                    "-XX:+UnlockDiagnosticVMOptions", "-XX:+LogCompilation", "-XX:LogFile=" + log,
                    "-XX:+JeandleDumpIR", "-XX:JeandleDumpDirectory=" + dumps,
                    "-XX:+JeandlePrintInlineTree", "-Xlog:jeandle=debug",
                    "-XX:CompileCommand=compileonly," + CLASS + "::latin1",
                    "-XX:CompileCommand=compileonly," + CLASS + "::mixed",
                    "-XX:CompileCommand=compileonly," + CLASS + "::unicode",
                    "-XX:CompileCommand=compileonly," + LATE_CALLER + "::nullable",
                    "-XX:CompileCommand=compileonly," + LATE_CALLER + "::latin1",
                    "-XX:CompileCommand=inline,java.lang.CharacterData::of"));
            if (mode.equals("disabled")) {
                options.add("-XX:DisableIntrinsic=_isDigit,_isLowerCase,_isUpperCase,_isWhitespace");
            } else if (mode.equals("flag-off")) {
                options.add("-XX:-UseCharacterCompareIntrinsics");
            } else if (mode.equals("method-off")) {
                options.add("-XX:CompileCommand=DisableIntrinsic," + CLASS + "::*,"
                        + "_isDigit,_isLowerCase,_isUpperCase,_isWhitespace");
                options.add("-XX:CompileCommand=DisableIntrinsic," + LATE_CALLER + "::*,"
                        + "_isDigit,_isLowerCase,_isUpperCase,_isWhitespace");
            }
        }
        options.addAll(List.of(CLASS, "child"));
        OutputAnalyzer output = ProcessTools.executeTestJava(options);
        digest(output);
        return output;
    }
}
