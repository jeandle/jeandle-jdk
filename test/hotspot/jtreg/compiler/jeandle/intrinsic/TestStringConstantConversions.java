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
 * @summary Material constant conversions select bounded native or atomic scalar paths without changing contents
 * @library /test/lib /
 * @compile --patch-module java.base=${test.src} java/lang/JeandleStringIntrinsicCaller.java
 * @compile --patch-module java.base=${test.classes} TestStringConstantConversions.java
 * @run main/othervm/timeout=240 compiler.jeandle.intrinsic.TestStringConstantConversions
 */
package compiler.jeandle.intrinsic;

import java.lang.JeandleStringIntrinsicCaller;
import java.lang.reflect.Method;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import jdk.test.lib.process.ProcessTools;

public class TestStringConstantConversions {
    private static final int[] COUNTS = {7, 8, 9, 16, 17};

    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            exercise();
            System.out.println("String constant conversions PASSED");
            return;
        }
        Path patch = Files.createTempDirectory("string-constant-patch");
        Path helper = patch.resolve("java/lang/JeandleStringIntrinsicCaller.class");
        Files.createDirectories(helper.getParent());
        try (var input = TestStringConstantConversions.class.getClassLoader()
                .getResourceAsStream("java/lang/JeandleStringIntrinsicCaller.class")) {
            if (input == null) throw new AssertionError("Missing compiled String helper");
            Files.copy(input, helper);
        }
        Path dumps = Files.createTempDirectory("string-constant-conversions");
        Path log = dumps.resolve("compilation.xml");
        var options = new ArrayList<>(List.of(
                "--patch-module=java.base=" + patch,
                "-Xmixed", "-Xbatch", "-XX:CompileThreshold=100", "-XX:-TieredCompilation",
                "-XX:+UseJeandleCompiler", "-XX:+UnlockDiagnosticVMOptions",
                "-XX:+LogCompilation", "-XX:LogFile=" + log,
                "-XX:+JeandleDumpIR", "-XX:JeandleDumpDirectory=" + dumps));
        for (int count : COUNTS) {
            for (String operation : List.of("compressB", "compressC", "inflateB", "inflateC")) {
                options.add("-XX:CompileCommand=compileonly," + TestStringConstantConversions.class.getName()
                        + "::" + operation + count);
            }
        }
        for (String operation : List.of("compressStringB", "compressStringC", "inflateStringB", "inflateStringC")) {
            options.add("-XX:CompileCommand=inline,java.lang.JeandleStringIntrinsicCaller::" + operation);
        }
        options.add(TestStringConstantConversions.class.getName());
        options.add("child");
        ProcessTools.executeTestJava(options.toArray(String[]::new))
                .shouldHaveExitValue(0).shouldContain("String constant conversions PASSED");
        String xml = Files.readString(log);
        for (int count : COUNTS) {
            for (String operation : List.of("compressB", "compressC", "inflateB", "inflateC")) {
                String method = operation + count;
                require(xml.lines().anyMatch(line -> line.contains("<nmethod")
                        && line.contains("compiler='jeandle'") && line.contains(" " + method + " ")),
                        "Missing executed Jeandle method " + method);
                String stem = TestStringConstantConversions.class.getName().replace('.', '_') + "_" + method + "_";
                try (var paths = Files.list(dumps)) {
                    List<Path> files = paths.filter(path -> path.getFileName().toString().startsWith(stem)
                            && path.toString().endsWith("_optimized.ll")).toList();
                    require(!files.isEmpty(), "Missing optimized IR " + method);
                    for (Path file : files) {
                        String ir = Files.readString(file);
                        String routine = operation.startsWith("compress") ? "string_compress_stub" : "string_inflate_stub";
                        boolean nativeCall = Pattern.compile("call[^\\n]*@" + routine + "\\(").matcher(ir).find();
                        require(nativeCall == (count > 8), "Unexpected conversion path " + method + ": " + file);
                        require(!ir.contains("load <") && !ir.contains("store <"),
                                "Shared conversion exposed ordinary vector memory " + method);
                    }
                }
            }
        }
        System.out.println("Constant conversion IR: " + dumps);
    }

    private static void exercise() throws Exception {
        for (int count : COUNTS) {
            for (String operation : List.of("compressB", "compressC", "inflateB", "inflateC")) {
                boolean compress = operation.startsWith("compress");
                boolean bytes = operation.endsWith("B");
                Class<?> sourceType = compress && !bytes ? char[].class : byte[].class;
                Class<?> destinationType = !compress && !bytes ? char[].class : byte[].class;
                Method method = TestStringConstantConversions.class.getMethod(operation + count,
                        sourceType, destinationType);
                char[] chars = new char[40];
                byte[] sourceBytes = new byte[80];
                byte[] destinationBytes = new byte[80];
                char[] destinationChars = new char[40];
                for (int i = 0; i < 3000; i++) {
                    Arrays.fill(chars, (char) 97);
                    Arrays.fill(sourceBytes, (byte) 97);
                    if (compress && bytes) for (int j = 0; j < 40; j++) put(sourceBytes, j, 97);
                    Object source = sourceType == char[].class ? chars : sourceBytes;
                    Object destination = destinationType == char[].class ? destinationChars : destinationBytes;
                    method.invoke(null, source, destination);
                }
                for (int failure = -1; failure < count; failure++) {
                    Arrays.fill(chars, (char) 233);
                    Arrays.fill(sourceBytes, (byte) 233);
                    if (compress && bytes) for (int j = 0; j < 40; j++) put(sourceBytes, j, 233);
                    Arrays.fill(destinationBytes, (byte) 0x37);
                    Arrays.fill(destinationChars, (char) 0x3737);
                    if (compress && failure >= 0) {
                        chars[failure + 1] = 0x12ff;
                        if (bytes) put(sourceBytes, failure + 1, 0x12ff);
                    }
                    Object source = sourceType == char[].class ? chars : sourceBytes;
                    Object destination = destinationType == char[].class ? destinationChars : destinationBytes;
                    Object result = method.invoke(null, source, destination);
                    if (compress) require(((Integer) result) == (failure < 0 ? count : 0), "Compression count");
                    int committed = compress && failure >= 0 ? failure : count;
                    for (int j = 0; j < 40; j++) {
                        int expected = j >= 2 && j < committed + 2 ? 233 : 0x3737;
                        if (compress) {
                            int byteExpected = j >= 2 && j < committed + 2 ? 233 : 0x37;
                            require((destinationBytes[j] & 255) == byteExpected, "Compression prefix/suffix");
                        } else if (bytes) {
                            require(get(destinationBytes, j) == expected, "Byte inflation contents");
                        } else {
                            require(destinationChars[j] == expected, "Char inflation contents");
                        }
                    }
                }
            }
        }
    }

    private static void put(byte[] array, int index, int value) {
        boolean little = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;
        array[2 * index] = (byte) (little ? value : value >>> 8);
        array[2 * index + 1] = (byte) (little ? value >>> 8 : value);
    }

    private static int get(byte[] array, int index) {
        int first = array[2 * index] & 255;
        int second = array[2 * index + 1] & 255;
        return ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? first | second << 8 : first << 8 | second;
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static int compressB7(byte[] source, byte[] destination) {
        return JeandleStringIntrinsicCaller.compressStringB(source, 1, destination, 2, 7);
    }

    public static int compressC7(char[] source, byte[] destination) {
        return JeandleStringIntrinsicCaller.compressStringC(source, 1, destination, 2, 7);
    }

    public static void inflateB7(byte[] source, byte[] destination) {
        JeandleStringIntrinsicCaller.inflateStringB(source, 1, destination, 2, 7);
    }

    public static void inflateC7(byte[] source, char[] destination) {
        JeandleStringIntrinsicCaller.inflateStringC(source, 1, destination, 2, 7);
    }

    public static int compressB8(byte[] source, byte[] destination) {
        return JeandleStringIntrinsicCaller.compressStringB(source, 1, destination, 2, 8);
    }

    public static int compressC8(char[] source, byte[] destination) {
        return JeandleStringIntrinsicCaller.compressStringC(source, 1, destination, 2, 8);
    }

    public static void inflateB8(byte[] source, byte[] destination) {
        JeandleStringIntrinsicCaller.inflateStringB(source, 1, destination, 2, 8);
    }

    public static void inflateC8(byte[] source, char[] destination) {
        JeandleStringIntrinsicCaller.inflateStringC(source, 1, destination, 2, 8);
    }

    public static int compressB9(byte[] source, byte[] destination) {
        return JeandleStringIntrinsicCaller.compressStringB(source, 1, destination, 2, 9);
    }

    public static int compressC9(char[] source, byte[] destination) {
        return JeandleStringIntrinsicCaller.compressStringC(source, 1, destination, 2, 9);
    }

    public static void inflateB9(byte[] source, byte[] destination) {
        JeandleStringIntrinsicCaller.inflateStringB(source, 1, destination, 2, 9);
    }

    public static void inflateC9(byte[] source, char[] destination) {
        JeandleStringIntrinsicCaller.inflateStringC(source, 1, destination, 2, 9);
    }

    public static int compressB16(byte[] source, byte[] destination) {
        return JeandleStringIntrinsicCaller.compressStringB(source, 1, destination, 2, 16);
    }

    public static int compressC16(char[] source, byte[] destination) {
        return JeandleStringIntrinsicCaller.compressStringC(source, 1, destination, 2, 16);
    }

    public static void inflateB16(byte[] source, byte[] destination) {
        JeandleStringIntrinsicCaller.inflateStringB(source, 1, destination, 2, 16);
    }

    public static void inflateC16(byte[] source, char[] destination) {
        JeandleStringIntrinsicCaller.inflateStringC(source, 1, destination, 2, 16);
    }

    public static int compressB17(byte[] source, byte[] destination) {
        return JeandleStringIntrinsicCaller.compressStringB(source, 1, destination, 2, 17);
    }

    public static int compressC17(char[] source, byte[] destination) {
        return JeandleStringIntrinsicCaller.compressStringC(source, 1, destination, 2, 17);
    }

    public static void inflateB17(byte[] source, byte[] destination) {
        JeandleStringIntrinsicCaller.inflateStringB(source, 1, destination, 2, 17);
    }

    public static void inflateC17(byte[] source, char[] destination) {
        JeandleStringIntrinsicCaller.inflateStringC(source, 1, destination, 2, 17);
    }
}
