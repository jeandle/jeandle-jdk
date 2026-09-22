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
 * @summary Direct semantic, fallback, and lowering-path test for ISO/ASCII
 *          StringCoding prefix encoders
 * @requires (os.arch=="amd64" | os.arch=="x86_64" | os.arch=="aarch64") & os.family!="windows"
 * @library /test/lib /
 * @build jdk.test.whitebox.WhiteBox jdk.test.whitebox.code.NMethod compiler.jeandle.intrinsic.StringIntrinsicTestSupport
 * @compile --patch-module java.base=${test.src} java/lang/JeandleStringCodingEncodeCaller.java
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm compiler.jeandle.intrinsic.TestStringCodingEncodeDirect
 */

package compiler.jeandle.intrinsic;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import jdk.test.lib.Asserts;
import jdk.test.lib.Platform;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

import static compiler.jeandle.intrinsic.StringIntrinsicTestSupport.*;

public class TestStringCodingEncodeDirect {
    private static final String IDS = "-_encodeISOArray,-_encodeByteISOArray,"
            + "-_encodeAsciiArray";

    public static void main(String[] args) throws Exception {
        run(true, false);
        run(false, false);
        run(true, true);
        run(false, true);
    }

    private static void run(boolean enabled, boolean xcomp) throws Exception {
        String dump = Files.createTempDirectory(Path.of(System.getProperty("user.dir")),
                "jeandle_string_encode_"
                + (enabled ? "on" : "off") + (xcomp ? "_xcomp" : "_normal")).toString();
        ArrayList<String> command = new ArrayList<>(List.of(
                "-Xbatch", "-XX:ReservedCodeCacheSize=256m", "-XX:CompileThreshold=100",
                "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler",
                "-XX:+JeandleDoPEA",
                "-XX:JeandleLLVMOptions=-jeandle-verify-safepoint-coverage=fatal"
                        + " -jeandle-dump-pea-ir=encodeArrayLoop",
                "-Xbootclasspath/a:.", "-XX:+UnlockDiagnosticVMOptions", "-XX:+WhiteBoxAPI",
                "--patch-module=java.base=" + System.getProperty("test.class.path"),
                "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dump,
                "-XX:CompileCommand=compileonly,java.lang.JeandleStringCodingEncodeCaller::*",
                "-XX:CompileCommand=compileonly,sun.nio.cs.ISO_8859_1$Encoder::encodeArrayLoop"));
        if (xcomp) {
            command.add("-Xcomp");
        }
        System.out.println("String intrinsic test: enabled=" + enabled + ", mode="
                + (xcomp ? "-Xcomp" : "normal"));
        if (!enabled) {
            command.add("-XX:ControlIntrinsic=" + IDS);
        }
        command.add(Worker.class.getName());
        command.add(dump);
        OutputAnalyzer output = ProcessTools.executeCommand(
                ProcessTools.createLimitedTestJavaProcessBuilder(command));
        output.shouldHaveExitValue(0)
              .shouldContain("TestStringCodingEncodeDirect PASSED");
        assertMaterializationMarker(dump, "JeandleStringCodingEncodeCaller_encodeByte", enabled);
        assertMaterializationMarker(dump, "JeandleStringCodingEncodeCaller_encodeAscii", enabled);
        // This method reaches the intrinsic through encodeISOArray inlining.
        assertMaterializationMarkerAfterInlining(dump,
                "sun.nio.cs.ISO_8859_1$Encoder::encodeArrayLoop", enabled, output.getStderr());
        if (enabled) {
            // The String JavaOp must expand to scalar OR after O3 in default
            // mode, without disabling any LLVM optimization globally.
            if (Platform.isAArch64()) {
                assertContains(dump, "JeandleStringCodingEncodeCaller_encodeByte",
                        "encode_scalar_error.i", "missing local scalar error reduction: encodeByte");
                assertContains(dump, "JeandleStringCodingEncodeCaller_encodeAscii",
                        "encode_scalar_error.i", "missing local scalar error reduction: encodeAscii");
                assertContains(dump, "encodeArrayLoop",
                        "encode_scalar_error.i", "missing local scalar error reduction: encodeArrayLoop");
            }
            assertContains(dump, "JeandleStringCodingEncodeCaller_encodeByte",
                    "encode_unrolled_body", "missing 32-character path: encodeByte");
            assertContains(dump, "JeandleStringCodingEncodeCaller_encodeAscii",
                    "encode_unrolled_body", "missing 32-character path: encodeAscii");
            assertContains(dump, "encodeArrayLoop",
                    "encode_unrolled_body", "missing char ISO 32-character path");
            output.shouldContain("java.lang.StringCoding.implEncodeISOArray(jobject, jint, jobject, jint, jint)")
                  .shouldContain("java.lang.StringCoding.implEncodeAsciiArray(jobject, jint, jobject, jint, jint)")
                  .shouldContain("sun.nio.cs.ISO_8859_1$Encoder.implEncodeISOArray(jobject, jint, jobject, jint, jint)")
                  .shouldContain("is parsed as intrinsic");
            assertContains(dump, "encodeArrayLoop",
                    "store <8 x i8>", "missing char ISO encoder vector IR");
            assertContains(dump, "JeandleStringCodingEncodeCaller_encodeByte",
                    "store <8 x i8>", "missing byte ISO encoder vector IR");
            assertContains(dump, "JeandleStringCodingEncodeCaller_encodeAscii",
                    "encode_vec_body", "missing ASCII encoder vector IR");
        } else {
            for (String marker : new String[] {"encode_scalar_error.i",
                    "encode_unrolled_body", "encode_vec_body", "inflate_vec_body"}) {
                Asserts.assertFalse(contains(dump, "", marker), "disabled intrinsic emitted " + marker);
            }
            output.shouldNotContain("java.lang.StringCoding.implEncodeISOArray(jobject, jint, jobject, jint, jint) is parsed as intrinsic")
                  .shouldNotContain("java.lang.StringCoding.implEncodeAsciiArray(jobject, jint, jobject, jint, jint) is parsed as intrinsic")
                  .shouldNotContain("sun.nio.cs.ISO_8859_1$Encoder.implEncodeISOArray(jobject, jint, jobject, jint, jint) is parsed as intrinsic");
        }
    }

    public static class Worker {
        public static void main(String[] args) throws Exception {
            Method byteCaller = caller("encodeByte", byte[].class, byte[].class);
            Method asciiCaller = caller("encodeAscii", char[].class, byte[].class);
            Method isoCaller = Class.forName("sun.nio.cs.ISO_8859_1$Encoder")
                    .getDeclaredMethod("encodeArrayLoop", CharBuffer.class, ByteBuffer.class);
            warmup();
            assertCompiled(byteCaller, asciiCaller, isoCaller);
            testPrefixResults();
            // The public encoder and intrinsic-off Java loops may deopt once
            // on their first unmappable input. Compile with that profile.
            int[] compileIds = beginChecks(args[0], byteCaller, asciiCaller, isoCaller);
            testVectorBoundaries();
            testIso88591EncoderPath();
            endChecks(compileIds, byteCaller, asciiCaller, isoCaller);
            // Separate deliberate guard failures from compiled semantic checks.
            testColdInputsAndLongArrays();
            System.out.println("TestStringCodingEncodeDirect PASSED");
        }

        private static void warmup() throws Exception {
            char[] chars = latinChars(64);
            byte[] utf16 = utf16(chars);
            byte[] out = new byte[96];
            for (int i = 0; i < 20_000; i++) {
                encodeByte(utf16, 0, out, 1, chars.length);
                encodeAscii(chars, 0, out, 1, chars.length);
            }
            for (int i = 0; i < 200; i++) {
                encodeIso88591(latinChars(32));
            }
        }

        private static void testVectorBoundaries() throws Exception {
            for (int n : new int[] {1, 7, 8, 9, 15, 16, 17, 31, 32, 33,
                                   63, 64, 65, 255, 256, 257, 4095, 4096,
                                   4097, 16385}) {
                checkByteIso(latinChars(n), n, "ISO length=" + n);
                checkAscii(asciiChars(n), n, "ASCII length=" + n);
                checkIsoEncoder(latinChars(n), n, "ISO char length=" + n);
            }
            for (int bad = 0; bad < 97; bad++) {
                char[] iso = latinChars(97);
                iso[bad] = 0x100;
                checkByteIso(iso, bad, "ISO bad lane=" + bad);
                checkIsoEncoder(iso, bad, "ISO char bad lane=" + bad);
                for (char invalid : new char[] {0x80, 0x100, 0xd800, 0xffff}) {
                    char[] ascii = asciiChars(97);
                    ascii[bad] = invalid;
                    checkAscii(ascii, bad, "ASCII bad lane=" + bad + " char=" + (int) invalid);
                }
            }
        }

        private static void testPrefixResults() throws Exception {
            for (int bad : new int[] {0, 1, 15, 16, 31, 63}) {
                char[] iso = latinChars(70);
                iso[bad] = 0x4e2d;
                checkByteIso(iso, bad, "ISO byte bad=" + bad);
                checkIsoEncoder(iso, bad, "ISO encoder bad=" + bad);

                char[] ascii = asciiChars(70);
                ascii[bad] = 0x80;
                checkAscii(ascii, bad, "ASCII bad=" + bad);
            }
            checkByteIso(latinChars(64), 64, "ISO byte full");
            checkIsoEncoder(latinChars(64), 64, "ISO encoder full");
            checkAscii(asciiChars(64), 64, "ASCII full");
        }

        private static void checkByteIso(char[] chars, int expected, String message) {
            byte[] src = utf16(chars);
            byte[] actual = filled(chars.length + 8, (byte) 0x5a);
            byte[] reference = actual.clone();
            int expectedResult = referenceByteIso(src, 0, reference, 2, chars.length);
            int result = encodeByte(src, 0, actual, 2, chars.length);
            Asserts.assertEquals(expected, expectedResult, message + " reference result");
            Asserts.assertEquals(expectedResult, result, message + " result");
            Asserts.assertTrue(Arrays.equals(reference, actual), message + " effect");
        }

        private static void checkAscii(char[] chars, int expected, String message) {
            byte[] actual = filled(chars.length + 8, (byte) 0x5a);
            byte[] reference = actual.clone();
            int expectedResult = referenceAscii(chars, 0, reference, 2, chars.length);
            int result = encodeAscii(chars, 0, actual, 2, chars.length);
            Asserts.assertEquals(expected, expectedResult, message + " reference result");
            Asserts.assertEquals(expectedResult, result, message + " result");
            Asserts.assertTrue(Arrays.equals(reference, actual), message + " effect");
        }

        private static void checkIsoEncoder(char[] chars, int expected, String message) throws Exception {
            int consumed = encodeIso88591(chars);
            Asserts.assertEquals(expected, consumed, message);
        }

        private static void testColdInputsAndLongArrays() {
            Asserts.assertEquals(0, encodeByte(null, 0, null, 0, 0));
            Asserts.assertEquals(0, encodeAscii(null, 0, null, 0, 0));
            expectNpe(() -> encodeByte(null, 0, new byte[1], 0, 1));
            expectNpe(() -> encodeAscii(null, 0, new byte[1], 0, 1));

            char[] longChars = latinChars(4097);
            byte[] longBytes = utf16(longChars);
            byte[] actual = filled(4100, (byte) 0x5a);
            byte[] reference = actual.clone();
            int expected = referenceByteIso(longBytes, 0, reference, 1, longChars.length);
            int result = encodeByte(longBytes, 0, actual, 1, longChars.length);
            Asserts.assertEquals(expected, result, "long byte ISO result");
            Asserts.assertTrue(Arrays.equals(reference, actual), "long byte ISO effect");

            actual = filled(4100, (byte) 0x5a);
            reference = actual.clone();
            expected = referenceAscii(longChars, 0, reference, 1, longChars.length);
            result = encodeAscii(longChars, 0, actual, 1, longChars.length);
            Asserts.assertEquals(expected, result, "long ASCII result");
            Asserts.assertTrue(Arrays.equals(reference, actual), "long ASCII effect");
        }

        private static void testIso88591EncoderPath() throws Exception {
            char[] latin = latinChars(32);
            Asserts.assertEquals(latin.length, encodeIso88591(latin), "ISO encoder full result");
            char[] bad = latinChars(32);
            bad[17] = 0x4e2d;
            Asserts.assertEquals(17, encodeIso88591(bad), "ISO encoder prefix result");
        }

        private static int encodeByte(byte[] src, int srcOff, byte[] dst, int dstOff, int len) {
            return invoke("encodeByte", byte[].class, src, srcOff, dst, dstOff, len);
        }

        private static int encodeAscii(char[] src, int srcOff, byte[] dst, int dstOff, int len) {
            return invoke("encodeAscii", char[].class, src, srcOff, dst, dstOff, len);
        }

        private static int invoke(String name, Class<?> sourceType, Object src,
                                  int srcOff, byte[] dst, int dstOff, int len) {
            try {
                return (Integer) caller(name, sourceType, byte[].class)
                        .invoke(null, src, srcOff, dst, dstOff, len);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }

        private static int encodeIso88591(char[] chars) throws Exception {
            CharsetEncoder encoder = StandardCharsets.ISO_8859_1.newEncoder();
            CharBuffer input = CharBuffer.wrap(chars);
            ByteBuffer output = ByteBuffer.allocate(chars.length + 2);
            Arrays.fill(output.array(), (byte) 0x5a);
            CoderResult result = encoder.encode(input, output, true);
            if (input.position() != chars.length) {
                Asserts.assertTrue(result.isUnmappable() || result.isMalformed(),
                        "unexpected encoder result: " + result);
            } else {
                Asserts.assertTrue(result.isUnderflow(), "expected underflow: " + result);
            }
            Asserts.assertEquals(input.position(), output.position(), "ISO bytes produced");
            for (int i = 0; i < output.capacity(); i++) {
                byte expected = i < input.position() ? (byte) chars[i] : (byte) 0x5a;
                Asserts.assertEquals(expected, output.array()[i], "ISO output byte " + i);
            }
            return input.position();
        }

        private static int referenceByteIso(byte[] src, int srcOff, byte[] dst, int dstOff, int len) {
            for (int i = 0; i < len; i++) {
                char c = getUtf16(src, srcOff++);
                if (c > 0xff) {
                    return i;
                }
                dst[dstOff++] = (byte) c;
            }
            return len;
        }

        private static int referenceAscii(char[] src, int srcOff, byte[] dst, int dstOff, int len) {
            for (int i = 0; i < len; i++) {
                char c = src[srcOff++];
                if (c >= 0x80) {
                    return i;
                }
                dst[dstOff++] = (byte) c;
            }
            return len;
        }

        private static char[] latinChars(int n) {
            char[] result = new char[n];
            for (int i = 0; i < n; i++) {
                result[i] = (char) ((i * 17 + 3) & 0xff);
            }
            return result;
        }

        private static char[] asciiChars(int n) {
            char[] result = new char[n];
            for (int i = 0; i < n; i++) {
                result[i] = (char) ((i * 17 + 3) & 0x7f);
            }
            return result;
        }

        private static byte[] utf16(char[] chars) {
            byte[] result = new byte[chars.length << 1];
            for (int i = 0; i < chars.length; i++) {
                putUtf16(result, i, chars[i]);
            }
            return result;
        }

        private static char getUtf16(byte[] array, int index) {
            int p = index << 1;
            if (java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.BIG_ENDIAN) {
                return (char) (((array[p] & 0xff) << 8) | (array[p + 1] & 0xff));
            }
            return (char) ((array[p] & 0xff) | ((array[p + 1] & 0xff) << 8));
        }

        private static void putUtf16(byte[] array, int index, int value) {
            int p = index << 1;
            if (java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.BIG_ENDIAN) {
                array[p] = (byte) (value >>> 8);
                array[p + 1] = (byte) value;
            } else {
                array[p] = (byte) value;
                array[p + 1] = (byte) (value >>> 8);
            }
        }

        private static byte[] filled(int n, byte value) {
            byte[] result = new byte[n];
            Arrays.fill(result, value);
            return result;
        }

        private static void expectNpe(Action action) {
            try {
                action.run();
                throw new AssertionError("expected NPE");
            } catch (RuntimeException e) {
                Throwable cause = e;
                while (cause.getCause() != null) {
                    cause = cause.getCause();
                }
                Asserts.assertTrue(cause instanceof NullPointerException,
                        "expected NPE: " + cause);
            }
        }

        private interface Action {
            void run();
        }
    }
}
