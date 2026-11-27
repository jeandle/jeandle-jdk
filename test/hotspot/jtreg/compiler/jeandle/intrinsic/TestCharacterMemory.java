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

/*
 * @test
 * @summary Character encoders read indivisible char elements under concurrent mutation
 * @library /test/lib /
 * @build compiler.jeandle.intrinsic.CharacterIntrinsicTestSupport
 * @compile --patch-module java.base=${test.src} java/lang/JeandleCharacterIntrinsicCaller.java
 * @run main/othervm/timeout=180 compiler.jeandle.intrinsic.TestCharacterMemory
 * @run main/othervm/timeout=180 -Dtest.disableEncodingIntrinsics=true compiler.jeandle.intrinsic.TestCharacterMemory
 */
package compiler.jeandle.intrinsic;

import java.lang.reflect.Method;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import jdk.test.lib.process.ProcessTools;

public class TestCharacterMemory {
    private static volatile boolean stop;

    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            CharacterIntrinsicTestSupport.reportEncodingSupport();
            Class<?> caller = Class.forName("java.lang.JeandleCharacterIntrinsicCaller");
            Method iso = caller.getMethod("encodeISO", char[].class, int.class,
                    byte[].class, int.class, int.class);
            Method ascii = caller.getMethod("encodeASCII", char[].class, int.class,
                    byte[].class, int.class, int.class);
            Method isoBytes = caller.getMethod("encodeISOBytes", byte[].class, int.class,
                    byte[].class, int.class, int.class);
            byte[] utf16 = new byte[128];
            byte[] bytes = new byte[64];
            int high = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? 1 : 0;
            for (int i = 0; i < 3000; i++) {
                utf16[high] = (byte) (i & 1);
                isoBytes.invoke(null, utf16, 0, bytes, 0, 64);
            }
            utf16[high] = 1;
            for (int i = 0; i < 100; i++) {
                int prefix = (int) isoBytes.invoke(null, utf16, 0, null,
                        Integer.MIN_VALUE, Integer.MAX_VALUE);
                if (prefix != 0) throw new AssertionError("UTF16 first-character rejection");
            }
            for (Method method : List.of(iso, ascii)) {
                char[] src = new char[64];
                Arrays.fill(src, 'x');
                byte[] dst = new byte[66];
                char valid = method == iso ? '\u00ff' : '\u007f';
                char invalid = method == iso ? '\uff00' : '\u8000';
                for (int i = 0; i < 3000; i++) {
                    src[0] = (i & 1) == 0 ? valid : invalid;
                    method.invoke(null, src, 0, dst, 1, 64);
                }
                // An unmappable first character does not access the destination
                // or the remainder of the requested source range.
                src[0] = invalid;
                for (int i = 0; i < 100; i++) {
                    int prefix = (int) method.invoke(null, src, 0, null,
                            Integer.MIN_VALUE, Integer.MAX_VALUE);
                    if (prefix != 0) throw new AssertionError("first-character rejection");
                }
                stop = false;
                Thread writer = new Thread(() -> {
                    while (!stop) { src[0] = valid; src[0] = invalid; }
                });
                writer.start();
                try {
                    for (int i = 0; i < 20000; i++) {
                        Arrays.fill(dst, (byte) 7);
                        int prefix = (int) method.invoke(null, src, 0, dst, 1, 64);
                        if (prefix != 0 && prefix != 64
                                || dst[1] != (prefix == 0 ? 7 : (byte) valid)
                                || dst[0] != 7 || dst[65] != 7) {
                            throw new AssertionError("torn char or prefix store: " + method + "/" + prefix);
                        }
                    }
                } finally { stop = true; writer.join(); }
            }
            System.out.println("Character memory PASSED");
            return;
        }
        Path log = Files.createTempFile("character-memory", ".xml");
        Path dump = Files.createTempDirectory("character-memory-ir");
        var options = new ArrayList<>(List.of(
                "--patch-module=java.base=" + System.getProperty("test.class.path"),
                "-Xbatch", "-XX:-TieredCompilation", "-XX:CompileThreshold=100",
                "-XX:+UseJeandleCompiler", "-XX:+UnlockDiagnosticVMOptions",
                "-XX:+LogCompilation", "-XX:LogFile=" + log,
                "-XX:+JeandleDumpIR", "-XX:JeandleDumpDirectory=" + dump,
                "-XX:CompileCommand=compileonly,java.lang.JeandleCharacterIntrinsicCaller::*",
                TestCharacterMemory.class.getName(), "child"));
        CharacterIntrinsicTestSupport.configureChild(options);
        var output = ProcessTools.executeTestJava(options.toArray(String[]::new));
        output.shouldHaveExitValue(0).shouldContain("Character memory PASSED");
        boolean encodingEnabled = CharacterIntrinsicTestSupport.encodingEnabled(output);
        String xml = Files.readString(log);
        var traps = Pattern.compile("<uncommon_trap [^>]*reason='intrinsic_or_type_checked_inlining'[^>]*>.*?</uncommon_trap>",
                Pattern.DOTALL).matcher(xml);
        while (traps.find()) {
            if (traps.group().contains("method='java.lang.JeandleCharacterIntrinsicCaller ")) {
                throw new AssertionError("first-character rejection deoptimized: " + traps.group());
            }
        }
        for (String method : List.of("encodeISO", "encodeASCII", "encodeISOBytes")) {
            if (xml.lines().noneMatch(line -> line.startsWith("<nmethod ")
                    && line.contains("compiler='jeandle'")
                    && line.contains("method='java.lang.JeandleCharacterIntrinsicCaller " + method + " "))) {
                throw new AssertionError("missing Jeandle method " + method);
            }
            String routine = !method.equals("encodeASCII") ? "encode_iso_array_stub" : "encode_ascii_array_stub";
            boolean found = false;
            try (var files = Files.list(dump)) {
                for (Path file : files.filter(f -> f.toString().endsWith("_optimized.ll")
                        && f.getFileName().toString().contains("_" + method + "_")).toList()) {
                    String ir = Files.readString(file);
                    int begin = ir.indexOf("define hotspotcc");
                    int end = ir.indexOf("\n}", begin);
                    String body = ir.substring(begin, end);
                    if (!encodingEnabled) {
                        if (body.contains("@" + routine + "(")) {
                            throw new AssertionError("disabled encoder reached native leaf: " + file);
                        }
                        found = true;
                        continue;
                    }
                    if (!body.contains("@" + routine + "(")) continue;
                    if (!body.contains("load atomic i16")
                            || !body.matches("(?s).*icmp (?:ugt|ult) i16 .*")) {
                        throw new AssertionError("missing inline first-character range test: " + file);
                    }
                    if (body.lines().anyMatch(line -> line.contains("addrspace(1)")
                            && (line.contains("load <") || line.contains("store <")))) {
                        throw new AssertionError("ordinary heap vector memory: " + file);
                    }
                    found = true;
                }
            }
            if (!found) throw new AssertionError("missing encoding path for " + method);
        }
        System.out.println("Character memory evidence: " + log + "; " + dump);
    }
}
