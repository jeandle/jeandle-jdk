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
 * @summary Failing encoder profiles retain bytecode specialization and recover on profile changes
 * @library /test/lib /
 * @build jdk.test.whitebox.WhiteBox
 * @compile --patch-module java.base=${test.src} java/lang/JeandleCharacterIntrinsicCaller.java
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm/timeout=360 -XX:-UseJeandleCompiler compiler.jeandle.intrinsic.TestCharacterEncodingProfile
 */
package compiler.jeandle.intrinsic;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;
import jdk.test.whitebox.WhiteBox;

public class TestCharacterEncodingProfile {
    private static final String CLASS = TestCharacterEncodingProfile.class.getName();
    private static final String BRIDGE = "java.lang.JeandleCharacterIntrinsicCaller";
    private static final boolean LITTLE = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;
    private static class Handles {
        private static final MethodHandle BYTES = bridge("encodeISOBytes", byte[].class);
        private static final MethodHandle CHARS = bridge("encodeISO", char[].class);
        private static final MethodHandle ASCII = bridge("encodeASCII", char[].class);
    }

    public static int encodeBytes(byte[] src, byte[] dst) throws Throwable {
        return (int) Handles.BYTES.invokeExact(src, 0, dst, 0, 16);
    }

    public static int encodeChars(char[] src, byte[] dst) throws Throwable {
        return (int) Handles.CHARS.invokeExact(src, 0, dst, 0, 16);
    }

    public static int encodeASCII(char[] src, byte[] dst) throws Throwable {
        return (int) Handles.ASCII.invokeExact(src, 0, dst, 0, 16);
    }

    public static void main(String[] args) throws Throwable {
        if (args.length != 0) {
            exercise(args[0], Path.of(args[1]));
            return;
        }
        for (String mode : List.of("dense", "shared", "no-profile", "cold")) {
            Path dumps = Files.createTempDirectory("encoding-profile-" + mode);
            Path log = dumps.resolve("compilation.xml");
            var flags = new ArrayList<>(List.of(
                    "--patch-module=java.base=" + System.getProperty("test.class.path"),
                    "-Xbootclasspath/a:.", "-XX:+UnlockDiagnosticVMOptions", "-XX:+WhiteBoxAPI",
                    "-Xbatch", "-XX:-TieredCompilation", "-XX:CompileThreshold=100000",
                    "-XX:+UseJeandleCompiler", "-XX:+LogCompilation", "-XX:LogFile=" + log,
                    "-XX:+JeandleDumpIR", "-XX:JeandleDumpDirectory=" + dumps,
                    "-Xlog:jeandle=debug", "-XX:CompileCommand=compileonly," + CLASS + "::encode*",
                    "-XX:CompileCommand=inline," + BRIDGE + "::*"));
            if (mode.equals("no-profile")) flags.add("-XX:-JeandleUseProfile");
            flags.addAll(List.of(CLASS, mode, dumps.toString()));
            OutputAnalyzer output = ProcessTools.executeCommand(
                    ProcessTools.createLimitedTestJavaProcessBuilder(flags));
            Files.writeString(dumps.resolve("stdout.log"), output.getOutput());
            output.shouldHaveExitValue(0).shouldContain("ENCODING PROFILE PASSED " + mode);
            String xml = Files.readString(log);
            for (String name : List.of("encodeBytes", "encodeChars", "encodeASCII")) {
                if (!xml.lines().anyMatch(line -> line.startsWith("<nmethod ")
                        && line.contains("compiler='jeandle'")
                        && line.contains("method='" + CLASS + " " + name + " "))) {
                    throw new AssertionError("Missing Jeandle compilation: " + name + "; " + dumps);
                }
            }
            var traps = Pattern.compile("<uncommon_trap [^>]*reason='unstable_if'[^>]*>.*?</uncommon_trap>",
                    Pattern.DOTALL).matcher(xml);
            int encoderTraps = 0;
            while (traps.find()) {
                if (traps.group().contains("method='java.lang.StringCoding implEncode")
                        || traps.group().contains("method='sun.nio.cs.ISO_8859_1$Encoder implEncode")) {
                    encoderTraps++;
                }
            }
            if (encoderTraps > 8 || mode.equals("dense") && encoderTraps != 3) {
                throw new AssertionError("Unexpected encoder profile traps: " + encoderTraps + "; " + dumps);
            }
            System.out.println(mode + " evidence: " + dumps);
        }
    }

    private static void exercise(String mode, Path dumps) throws Throwable {
        byte[] dst = new byte[16];
        if (mode.equals("shared")) {
            char[] chars = new char[16];
            byte[] bytes = new byte[32];
            Arrays.fill(chars, 'A');
            for (int i = 0; i < 16; i++) bytes[2 * i + (LITTLE ? 0 : 1)] = 65;
            for (int i = 0; i < 80000; i++) {
                int ignored = (int) Handles.BYTES.invokeExact(bytes, 0, dst, 0, 16);
                ignored += (int) Handles.CHARS.invokeExact(chars, 0, dst, 0, 16);
                ignored += (int) Handles.ASCII.invokeExact(chars, 0, dst, 0, 16);
                if (ignored != 48) throw new AssertionError("shared clean caller");
            }
        }
        // Resolve the MethodHandle adapters even for the immature-profile case.
        for (int i = 0; i < (mode.equals("cold") ? 1 : 80000); i++) check(0);
        for (String name : List.of("encodeBytes", "encodeChars", "encodeASCII")) {
            Method method = TestCharacterEncodingProfile.class.getMethod(name,
                    name.equals("encodeBytes") ? byte[].class : char[].class, byte[].class);
            WhiteBox wb = WhiteBox.getWhiteBox();
            if (!wb.enqueueMethodForCompilation(method, 4) || !wb.isMethodCompiled(method)) {
                throw new AssertionError("Could not compile " + name);
            }
            check(0);
            var irFiles = new ArrayList<Path>();
            try (var files = Files.list(dumps)) {
                irFiles.addAll(files.filter(p -> p.getFileName().toString().startsWith(
                        CLASS.replace('.', '_') + "_" + name + "_"))
                        .filter(p -> p.toString().endsWith("_optimized.ll")).toList());
            }
            if (irFiles.isEmpty()) throw new AssertionError("No IR for " + name);
            for (Path file : irFiles) {
                String ir = Files.readString(file);
                int begin = ir.indexOf("define hotspotcc");
                String body = ir.substring(begin, ir.indexOf("\n}", begin));
                boolean stub = body.contains("@encode_iso_array_stub(")
                        || body.contains("@encode_ascii_array_stub(");
                if (stub == mode.equals("dense")) {
                    throw new AssertionError("Wrong encoding specialization for " + mode + ": " + file);
                }
            }
        }
        // Exercise each compiled failure path, then force the formerly unobserved
        // successful branch and a mixed prefix. Verify every destination byte.
        for (int phase : new int[] {0, 16, 7}) {
            for (int i = 0; i < 2000; i++) check(phase);
            // A successful branch may invalidate the original strict-zero
            // speculation. Exercise the next phase in compiled code again.
            for (String name : List.of("encodeBytes", "encodeChars", "encodeASCII")) {
                Method method = TestCharacterEncodingProfile.class.getMethod(name,
                        name.equals("encodeBytes") ? byte[].class : char[].class, byte[].class);
                WhiteBox wb = WhiteBox.getWhiteBox();
                if (!wb.isMethodCompiled(method)) {
                    if (!wb.enqueueMethodForCompilation(method, 4) || !wb.isMethodCompiled(method)) {
                        throw new AssertionError("Could not recompile after prefix " + phase + ": " + name);
                    }
                }
            }
            check(phase);
            System.out.println("ENCODING PROFILE PHASE prefix=" + phase);
        }
        System.out.println("ENCODING PROFILE PASSED " + mode);
    }

    private static void check(int prefix) throws Throwable {
        char[] chars = new char[16];
        byte[] bytes = new byte[32];
        for (int i = 0; i < 16; i++) {
            int value = i < prefix ? 65 : 0x400;
            chars[i] = (char) value;
            bytes[2 * i] = (byte) (value >>> (LITTLE ? 0 : 8));
            bytes[2 * i + 1] = (byte) (value >>> (LITTLE ? 8 : 0));
        }
        for (int kind = 0; kind < 3; kind++) {
            byte[] dst = new byte[16];
            Arrays.fill(dst, (byte) 7);
            int actual = switch (kind) {
                case 0 -> encodeBytes(bytes, dst);
                case 1 -> encodeChars(chars, dst);
                default -> encodeASCII(chars, dst);
            };
            if (actual != prefix) throw new AssertionError("prefix=" + actual + ", expected=" + prefix);
            for (int i = 0; i < 16; i++) {
                if (dst[i] != (i < prefix ? 65 : 7)) throw new AssertionError("suffix at " + i);
            }
        }
    }

    private static MethodHandle bridge(String name, Class<?> source) {
        try {
            return MethodHandles.publicLookup().findStatic(Class.forName(BRIDGE), name,
                    MethodType.methodType(int.class, source, int.class, byte[].class, int.class, int.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
