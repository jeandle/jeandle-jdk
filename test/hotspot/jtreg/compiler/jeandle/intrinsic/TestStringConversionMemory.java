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
 * @summary String conversions preserve element atomicity for shared arrays
 * @library /test/lib
 * @run main/othervm/timeout=180 compiler.jeandle.intrinsic.TestStringConversionMemory
 */
package compiler.jeandle.intrinsic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import jdk.test.lib.process.ProcessTools;

public class TestStringConversionMemory {
    private static volatile boolean stop;
    private static volatile String sink;

    public static String capture(char[] source) {
        return new String(source);
    }

    public static void extract(String source, char[] destination) {
        source.getChars(0, source.length(), destination, 1);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            exercise();
            System.out.println("String conversion memory PASSED");
            return;
        }
        Path log = Files.createTempFile("string-conversion-memory", ".xml");
        Path dump = Files.createTempDirectory("string-conversion-memory-ir");
        var options = new ArrayList<>(List.of(
                "-Xmixed", "-Xbatch", "-XX:CompileThreshold=100", "-XX:-TieredCompilation",
                "-XX:+UseJeandleCompiler", "-XX:+UnlockDiagnosticVMOptions",
                "-XX:+LogCompilation", "-XX:LogFile=" + log,
                "-XX:+JeandleDumpIR", "-XX:JeandleDumpDirectory=" + dump,
                "-XX:CompileCommand=inline,java.lang.String::<init>",
                "-XX:CompileCommand=inline,java.lang.String::getChars"));
        for (String method : List.of("capture", "extract")) {
            options.add("-XX:CompileCommand=compileonly," + TestStringConversionMemory.class.getName()
                    + "::" + method);
        }
        options.add(TestStringConversionMemory.class.getName());
        options.add("child");
        var output = ProcessTools.executeTestJava(options.toArray(String[]::new));
        Files.writeString(Path.of(log + ".stdout"), output.getOutput());
        output.shouldHaveExitValue(0).shouldContain("String conversion memory PASSED");
        String xml = Files.readString(log);
        requireLeafBody(xml, dump, "capture", "string_compress_stub", "StubRoutines_jshort_disjoint_arraycopy");
        requireLeafBody(xml, dump, "extract", "string_inflate_stub", "StubRoutines_jshort_disjoint_arraycopy");
        System.out.println("String conversion IR: " + dump + "; compilation log: " + log);
    }

    private static void requireLeafBody(String xml, Path dump, String method, String... routines)
            throws Exception {
        require(xml.lines().anyMatch(line -> line.startsWith("<nmethod ")
                && line.contains("compiler='jeandle'")
                && line.contains("method='" + TestStringConversionMemory.class.getName() + " " + method + " ")),
                "missing Jeandle nmethod: " + method);
        String stem = TestStringConversionMemory.class.getName().replace('.', '_') + "_" + method + "_";
        boolean found = false;
        try (var files = Files.list(dump)) {
            for (Path file : files.filter(path -> path.getFileName().toString().startsWith(stem)
                    && path.getFileName().toString().endsWith("_optimized.ll")).toList()) {
                String ir = Files.readString(file);
                int begin = ir.indexOf("define hotspotcc");
                int end = ir.indexOf("\n}", begin);
                require(begin >= 0 && end > begin, "missing compiled method body: " + file);
                String body = ir.substring(begin, end);
                if (!Arrays.stream(routines).allMatch(name -> body.contains("@" + name + "("))) continue;
                require(body.lines().noneMatch(line -> line.contains("addrspace(1)")
                        && (line.contains("load <") || line.contains("store <"))),
                        "shared array conversion exposed ordinary vector memory: " + file);
                require(!body.contains("@llvm.memcpy."), "shared array copy exposed ordinary memcpy: " + file);
                found = true;
            }
        }
        require(found, "missing opaque conversion leaf calls in " + method + "; IR: " + dump);
    }

    private static void exercise() throws Exception {
        char[] source = new char[64];
        Arrays.fill(source, 'x');
        char[] destination = new char[66];
        String latin = "\u00ff".repeat(64);
        String wide = "\u0300".repeat(64);
        for (int i = 0; i < 2000; i++) {
            source[0] = (i & 1) == 0 ? 'x' : '\u0100';
            sink = capture(source);
            extract((i & 1) == 0 ? latin : wide, destination);
        }
        source[0] = '\u0100';
        stop = false;
        Thread writer = new Thread(() -> {
            while (!stop) {
                source[0] = '\u0100';
                source[0] = '\u0200';
            }
        });
        writer.start();
        try {
            for (int i = 0; i < 10000; i++) {
                String value = capture(source);
                char first = value.charAt(0);
                require(value.length() == source.length && (first == '\u0100' || first == '\u0200'),
                        "constructor observed a torn char: " + (int) first);
                sink = value;
            }
        } finally {
            stop = true;
            writer.join();
        }
        destination[1] = '\u00ff';
        stop = false;
        Thread copier = new Thread(() -> {
            while (!stop) {
                extract(latin, destination);
                extract(wide, destination);
            }
        });
        copier.start();
        try {
            for (int i = 0; i < 100000; i++) {
                char first = destination[1];
                require(first == '\u00ff' || first == '\u0300', "getChars stored a torn char: " + (int) first);
            }
        } finally {
            stop = true;
            copier.join();
        }
        require(destination[0] == 0 && destination[65] == 0, "getChars wrote outside destination range");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
