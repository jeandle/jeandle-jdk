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
 * @library /test/lib
 * @compile --patch-module java.base=${test.src} java/lang/JeandleCharacterIntrinsicCaller.java
 * @run main/othervm/timeout=180 compiler.jeandle.intrinsic.TestCharacterMemory
 */
package compiler.jeandle.intrinsic;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import jdk.test.lib.process.ProcessTools;

public class TestCharacterMemory {
    private static volatile boolean stop;

    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            Class<?> caller = Class.forName("java.lang.JeandleCharacterIntrinsicCaller");
            Method iso = caller.getMethod("encodeISO", char[].class, int.class,
                    byte[].class, int.class, int.class);
            Method ascii = caller.getMethod("encodeASCII", char[].class, int.class,
                    byte[].class, int.class, int.class);
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
        var output = ProcessTools.executeTestJava(
                "--patch-module=java.base=" + System.getProperty("test.class.path"),
                "-Xbatch", "-XX:-TieredCompilation", "-XX:CompileThreshold=100",
                "-XX:+UseJeandleCompiler", "-XX:+UnlockDiagnosticVMOptions",
                "-XX:+LogCompilation", "-XX:LogFile=" + log,
                "-XX:+JeandleDumpIR", "-XX:JeandleDumpDirectory=" + dump,
                "-XX:CompileCommand=compileonly,java.lang.JeandleCharacterIntrinsicCaller::*",
                TestCharacterMemory.class.getName(), "child");
        output.shouldHaveExitValue(0).shouldContain("Character memory PASSED");
        String xml = Files.readString(log);
        for (String method : List.of("encodeISO", "encodeASCII")) {
            if (xml.lines().noneMatch(line -> line.startsWith("<nmethod ")
                    && line.contains("compiler='jeandle'")
                    && line.contains("method='java.lang.JeandleCharacterIntrinsicCaller " + method + " "))) {
                throw new AssertionError("missing Jeandle method " + method);
            }
            String routine = method.equals("encodeISO") ? "encode_iso_array_stub" : "encode_ascii_array_stub";
            boolean found = false;
            try (var files = Files.list(dump)) {
                for (Path file : files.filter(f -> f.toString().endsWith("_optimized.ll")
                        && f.getFileName().toString().contains("_" + method + "_")).toList()) {
                    String ir = Files.readString(file);
                    int begin = ir.indexOf("define hotspotcc");
                    int end = ir.indexOf("\n}", begin);
                    String body = ir.substring(begin, end);
                    if (!body.contains("@" + routine + "(")) continue;
                    if (body.lines().anyMatch(line -> line.contains("addrspace(1)")
                            && (line.contains("load <") || line.contains("store <")))) {
                        throw new AssertionError("ordinary heap vector memory: " + file);
                    }
                    found = true;
                }
            }
            if (!found) throw new AssertionError("missing opaque native leaf " + routine);
        }
        System.out.println("Character memory evidence: " + log + "; " + dump);
    }
}
