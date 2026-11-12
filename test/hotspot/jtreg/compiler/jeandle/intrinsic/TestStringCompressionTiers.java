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
 * @summary String compression preserves every legal prefix with restricted SIMD features
 * @requires (os.arch == "amd64" | os.arch == "x86_64")
 * @library /test/lib /
 * @compile --patch-module java.base=${test.src} java/lang/JeandleStringIntrinsicCaller.java
 * @run main/othervm/timeout=300 compiler.jeandle.intrinsic.TestStringCompressionTiers
 */
package compiler.jeandle.intrinsic;

import java.lang.reflect.Method;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import jdk.test.lib.process.ProcessTools;

public class TestStringCompressionTiers {
    private static final String CALLER = "java.lang.JeandleStringIntrinsicCaller";

    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            exercise();
            System.out.println("String compression prefixes PASSED");
            return;
        }
        for (List<String> features : List.of(
                List.<String>of(),
                List.of("-XX:UseAVX=0"),
                List.of("-XX:UseAVX=1"),
                List.of("-XX:UseAVX=2", "-XX:MaxVectorSize=16"),
                List.of("-XX:UseAVX=2", "-XX:MaxVectorSize=32"),
                List.of("-XX:UseAVX=0", "-XX:-UseSSE42Intrinsics"))) {
            Path dumps = Files.createTempDirectory("jeandle-string-compression");
            Path log = dumps.resolve("compilation.xml");
            var options = new ArrayList<>(List.of(
                    "--patch-module=java.base=" + System.getProperty("test.class.path"),
                    "-Xcomp", "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler",
                    "-XX:+UnlockDiagnosticVMOptions", "-XX:+LogCompilation", "-XX:LogFile=" + log,
                    "-XX:+JeandleDumpIR", "-XX:JeandleDumpDirectory=" + dumps,
                    "-XX:CompileCommand=compileonly," + CALLER + "::compressString*"));
            options.addAll(features);
            options.addAll(List.of(TestStringCompressionTiers.class.getName(), "child"));
            ProcessTools.executeTestJava(options.toArray(String[]::new))
                    .shouldHaveExitValue(0).shouldContain("String compression prefixes PASSED");
            String xml = Files.readString(log);
            for (String name : new String[]{"compressStringB", "compressStringC"}) {
                if (!xml.lines().anyMatch(line -> line.contains("<nmethod")
                        && line.contains("compiler='jeandle'") && line.contains(CALLER + " " + name + " "))) {
                    throw new AssertionError("Missing executed compression: " + name + ", " + features);
                }
                try (var files = Files.list(dumps)) {
                    Path ir = files.filter(path -> path.getFileName().toString()
                            .startsWith("java_lang_JeandleStringIntrinsicCaller_" + name + "_")
                            && path.toString().endsWith("_optimized.ll")).findFirst().orElseThrow();
                    if (!Files.readString(ir).contains("@string_compress_stub(")) {
                        throw new AssertionError("Missing native compression call: " + ir);
                    }
                }
            }
        }
    }

    private static void exercise() throws Exception {
        Class<?> caller = Class.forName(CALLER);
        Method chars = caller.getMethod("compressStringC", char[].class, int.class,
                byte[].class, int.class, int.class);
        Method bytes = caller.getMethod("compressStringB", byte[].class, int.class,
                byte[].class, int.class, int.class);
        for (int count = 0; count <= 129; count++) {
            for (int failure = -1; failure < count; failure++) {
                for (int sourceOffset : new int[]{1, 3}) {
                    char[] source = new char[count + 6];
                    for (int i = 0; i < source.length; i++) source[i] = (char) (128 + (i * 73 & 127));
                    if (failure >= 0) source[sourceOffset + failure] = '\u8000';
                    byte[] encoded = encode(source);
                    for (int destinationOffset : new int[]{2, 5}) {
                        for (Method method : new Method[]{chars, bytes}) {
                            byte[] destination = new byte[count + 9];
                            Arrays.fill(destination, (byte) 0x5a);
                            byte[] expected = destination.clone();
                            int prefix = failure < 0 ? count : failure;
                            for (int i = 0; i < prefix; i++) {
                                expected[destinationOffset + i] = (byte) source[sourceOffset + i];
                            }
                            int actual = (int) method.invoke(null, method == chars ? source : encoded,
                                    sourceOffset, destination, destinationOffset, count);
                            if (actual != (failure < 0 ? count : 0) || !Arrays.equals(destination, expected)) {
                                throw new AssertionError(method.getName() + ": count=" + count
                                        + ", failure=" + failure + ", sourceOffset=" + sourceOffset
                                        + ", destinationOffset=" + destinationOffset);
                            }
                        }
                    }
                }
            }
        }
    }

    private static byte[] encode(char[] chars) {
        byte[] bytes = new byte[2 * chars.length];
        boolean little = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;
        for (int i = 0; i < chars.length; i++) {
            bytes[2 * i] = (byte) (little ? chars[i] : chars[i] >>> 8);
            bytes[2 * i + 1] = (byte) (little ? chars[i] >>> 8 : chars[i]);
        }
        return bytes;
    }
}
