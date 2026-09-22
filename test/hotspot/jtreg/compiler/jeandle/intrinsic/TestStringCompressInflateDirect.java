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
 * @summary Direct semantic, fallback, and lowering-path test for String UTF16
 *          compress and String Latin1 inflate intrinsics
 * @requires (os.arch=="amd64" | os.arch=="x86_64" | os.arch=="aarch64") & os.family!="windows"
 * @library /test/lib /
 * @build jdk.test.whitebox.WhiteBox jdk.test.whitebox.code.NMethod compiler.jeandle.intrinsic.StringIntrinsicTestSupport
 * @compile --patch-module java.base=${test.src} java/lang/JeandleStringCodingEncodeCaller.java
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm compiler.jeandle.intrinsic.TestStringCompressInflateDirect
 */

package compiler.jeandle.intrinsic;

import java.lang.reflect.Method;
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

public class TestStringCompressInflateDirect {
    private static final String IDS = "-_compressStringC,-_compressStringB,"
            + "-_inflateStringC,-_inflateStringB";

    public static void main(String[] args) throws Exception {
        run(true, false);
        run(false, false);
        run(true, true);
        run(false, true);
    }

    private static void run(boolean enabled, boolean xcomp) throws Exception {
        String dump = Files.createTempDirectory(Path.of(System.getProperty("user.dir")),
                "jeandle_string_copy_"
                + (enabled ? "on" : "off") + (xcomp ? "_xcomp" : "_normal")).toString();
        // Check intrinsic loop safepoint policy even with a release LLVM build.
        ArrayList<String> command = new ArrayList<>(List.of(
                "-Xbatch", "-XX:ReservedCodeCacheSize=256m", "-XX:CompileThreshold=100",
                "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler",
                "-XX:JeandleLLVMOptions=-jeandle-verify-safepoint-coverage=fatal",
                "-Xbootclasspath/a:.", "-XX:+UnlockDiagnosticVMOptions", "-XX:+WhiteBoxAPI",
                "--patch-module=java.base=" + System.getProperty("test.class.path"),
                "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dump,
                "-XX:CompileCommand=compileonly,java.lang.JeandleStringCodingEncodeCaller::*"));
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
              .shouldContain("TestStringCompressInflateDirect PASSED");
        for (String method : List.of("compressChars", "compressBytes", "inflateChars", "inflateBytes")) {
            assertMaterializationMarker(dump, "JeandleStringCodingEncodeCaller_" + method, enabled);
        }
        if (enabled) {
            // The String JavaOp must expand to scalar OR after O3 in default
            // mode, without disabling any LLVM optimization globally.
            if (Platform.isAArch64()) {
                assertContains(dump, "JeandleStringCodingEncodeCaller_compressChars",
                        "encode_scalar_error.i", "missing local scalar error reduction: compressChars");
                assertContains(dump, "JeandleStringCodingEncodeCaller_compressBytes",
                        "encode_scalar_error.i", "missing local scalar error reduction: compressBytes");
            }
            assertContains(dump, "JeandleStringCodingEncodeCaller_inflateBytes",
                    "inflate_unrolled_body", "missing 64-byte inflate path");
            assertContains(dump, "JeandleStringCodingEncodeCaller_inflateChars",
                    "inflate_unrolled_body", "missing 64-byte char inflate path");
            assertContains(dump, "JeandleStringCodingEncodeCaller_compressChars",
                    "encode_unrolled_body", "missing 32-character path: compressChars");
            assertContains(dump, "JeandleStringCodingEncodeCaller_compressBytes",
                    "encode_unrolled_body", "missing 32-character path: compressBytes");
            output.shouldContain("java.lang.StringUTF16.compress(jobject, jint, jobject, jint, jint)")
                  .shouldContain("java.lang.StringLatin1.inflate(jobject, jint, jobject, jint, jint)")
                  .shouldContain("is parsed as intrinsic");
            assertContains(dump, "JeandleStringCodingEncodeCaller_compressChars",
                    "store <8 x i8>", "missing char[] compress vector IR");
            assertContains(dump, "JeandleStringCodingEncodeCaller_inflateChars",
                    "store <8 x i16>", "missing char[] inflate vector IR");
            assertContains(dump, "JeandleStringCodingEncodeCaller_compressBytes",
                    "store <8 x i8>", "missing byte[] compress IR");
            assertContains(dump, "JeandleStringCodingEncodeCaller_inflateBytes",
                    "store <8 x i16>", "missing byte[] inflate IR");
        } else {
            for (String marker : new String[] {"encode_scalar_error.i", "encode_unrolled_body",
                    "encode_vec_body", "inflate_unrolled_body", "inflate_vec_body"}) {
                Asserts.assertFalse(contains(dump, "", marker), "disabled intrinsic emitted " + marker);
            }
            output.shouldNotContain("java.lang.StringUTF16.compress(jobject, jint, jobject, jint, jint) is parsed as intrinsic")
                  .shouldNotContain("java.lang.StringLatin1.inflate(jobject, jint, jobject, jint, jint) is parsed as intrinsic");
        }
    }

    public static class Worker {
        public static void main(String[] args) throws Exception {
            Method[] callers = {
                caller("compressChars", char[].class, byte[].class),
                caller("compressBytes", byte[].class, byte[].class),
                caller("inflateChars", byte[].class, char[].class),
                caller("inflateBytes", byte[].class, byte[].class)
            };
            warmup();
            // Train the ordinary Java loops' first unmappable-character exit
            // too, before binding intrinsic-off checks to one compilation.
            testCompressChars();
            testCompressBytesNativeEndian();
            int[] compileIds = beginChecks(args[0], callers);
            testCompressChars();
            testCompressBytesNativeEndian();
            testInflateChars();
            testInflateBytesNativeEndian();
            testVectorBoundaries();
            endChecks(compileIds, callers);
            // Negative control: being compiled again must not count as the
            // same nmethod, even while the old intrinsic IR is still present.
            jdk.test.whitebox.WhiteBox.getWhiteBox().deoptimizeMethod(callers[0]);
            compile(callers[0]);
            RuntimeException stale = Asserts.assertThrows(RuntimeException.class,
                    () -> endChecks(compileIds, callers));
            Asserts.assertTrue(stale.getMessage().contains("nmethod changed"), stale.getMessage());
            System.out.println("STRING_COPY_STALE_NMETHOD_REJECTED");
            // The remaining cases intentionally leave the intrinsic via deopt.
            testRangeFallbacks();
            testColdInputsAndLongArrays();
            System.out.println("TestStringCompressInflateDirect PASSED");
        }

        private static void warmup() throws Exception {
            char[] chars = chars(80);
            byte[] utf16 = utf16(chars);
            byte[] out = new byte[100];
            char[] charsOut = new char[100];
            byte[] utf16Out = new byte[200];
            for (int i = 0; i < 20_000; i++) {
                compressChars(chars, 3, out, 2, 64);
                compressBytes(utf16, 3, out, 2, 64);
                inflateChars(out, 2, charsOut, 3, 64);
                inflateBytes(out, 2, utf16Out, 3, 64);
            }
        }

        private static void testCompressChars() throws Exception {
            char[] source = chars(40);
            byte[] expected = filled(52, (byte) 0x5a);
            byte[] actual = expected.clone();
            int ref = referenceCompressChars(source, 3, expected, 5, 28);
            int result = compressChars(source, 3, actual, 5, 28);
            Asserts.assertEquals(ref, result);
            Asserts.assertTrue(Arrays.equals(expected, actual), "char[] compress effect");
            source[14] = 0x4e2d;
            Arrays.fill(expected, (byte) 0x5a);
            actual = expected.clone();
            ref = referenceCompressChars(source, 3, expected, 5, 28);
            result = compressChars(source, 3, actual, 5, 28);
            Asserts.assertEquals(0, ref, "StringUTF16 compress failure result");
            Asserts.assertEquals(ref, result);
            Asserts.assertTrue(Arrays.equals(expected, actual), "char[] compress failure effect");
        }

        private static void testCompressBytesNativeEndian() throws Exception {
            char[] chars = chars(40);
            for (char c : chars) {
                Asserts.assertTrue(c <= 0xff, "test source must be Latin-1");
            }
            byte[] source = utf16(chars);
            byte[] expected = filled(52, (byte) 0x5a);
            byte[] actual = expected.clone();
            int ref = referenceCompressBytes(source, 3, expected, 5, 28);
            int result = compressBytes(source, 3, actual, 5, 28);
            Asserts.assertEquals(ref, result);
            Asserts.assertTrue(Arrays.equals(expected, actual), "byte[] compress effect");
            putUtf16(source, 17, (char) 0x4e2d);
            Arrays.fill(expected, (byte) 0x5a);
            actual = expected.clone();
            ref = referenceCompressBytes(source, 3, expected, 5, 28);
            result = compressBytes(source, 3, actual, 5, 28);
            Asserts.assertEquals(0, ref, "byte[] compress failure result");
            Asserts.assertEquals(ref, result);
            Asserts.assertTrue(Arrays.equals(expected, actual), "byte[] compress failure effect");
        }

        private static void testInflateChars() throws Exception {
            byte[] source = latin(40);
            char[] expected = new char[50];
            char[] actual = new char[50];
            Arrays.fill(expected, (char) 0x5a5a);
            Arrays.fill(actual, (char) 0x5a5a);
            referenceInflateChars(source, 4, expected, 6, 28);
            inflateChars(source, 4, actual, 6, 28);
            Asserts.assertTrue(Arrays.equals(expected, actual), "char[] inflate effect");
        }

        private static void testInflateBytesNativeEndian() throws Exception {
            byte[] source = latin(40);
            byte[] expected = filled(104, (byte) 0x5a);
            byte[] actual = expected.clone();
            referenceInflateBytes(source, 4, expected, 6, 28);
            inflateBytes(source, 4, actual, 6, 28);
            Asserts.assertTrue(Arrays.equals(expected, actual), "byte[] inflate effect");
            for (int i = 0; i < 28; i++) {
                Asserts.assertEquals((char) (source[4 + i] & 0xff), getUtf16(actual, 6 + i),
                        "inflate code unit " + i);
            }
        }

        // Nonzero offsets, exact end-of-array reads, vector tails, and large
        // ranges exercise the unrolled vector loops and their smaller tails.
        private static void testVectorBoundaries() throws Exception {
            for (int n : new int[] {1, 3, 4, 7, 8, 9, 15, 16, 17, 31, 32, 33,
                                   63, 64, 65, 79, 80, 81, 255, 256, 257,
                                   4095, 4096, 4097, 16385}) {
                char[] source = chars(n + 3);
                byte[] sourceBytes = utf16(source);
                byte[] expected = filled(n + 7, (byte) 0x5a);
                referenceCompressChars(source, 3, expected, 5, n);
                byte[] actual = filled(n + 7, (byte) 0x5a);
                Asserts.assertEquals(n, compressChars(source, 3, actual, 5, n));
                Asserts.assertTrue(Arrays.equals(expected, actual), "compress chars n=" + n);
                Arrays.fill(actual, (byte) 0x5a);
                Asserts.assertEquals(n, compressBytes(sourceBytes, 3, actual, 5, n));
                Asserts.assertTrue(Arrays.equals(expected, actual), "compress bytes n=" + n);

                byte[] latin = latin(n + 3);
                byte[] wide = filled((n + 7) * 2, (byte) 0x5a);
                byte[] wideExpected = wide.clone();
                referenceInflateBytes(latin, 3, wideExpected, 5, n);
                inflateBytes(latin, 3, wide, 5, n);
                Asserts.assertTrue(Arrays.equals(wideExpected, wide), "inflate bytes n=" + n);
                char[] wideChars = new char[n + 7];
                Arrays.fill(wideChars, (char) 0x5a5a);
                char[] charsExpected = wideChars.clone();
                referenceInflateChars(latin, 3, charsExpected, 5, n);
                inflateChars(latin, 3, wideChars, 5, n);
                Asserts.assertTrue(Arrays.equals(charsExpected, wideChars), "inflate chars n=" + n);
            }
            // Every lane around SSE/AVX/NEON boundaries must preserve the
            // legal prefix and leave the first bad character and suffix alone.
            for (int bad = 0; bad < 97; bad++) {
                char[] source = chars(100);
                source[3 + bad] = 0x100;
                byte[] expected = filled(104, (byte) 0x5a);
                referenceCompressChars(source, 3, expected, 5, 97);
                byte[] actual = filled(104, (byte) 0x5a);
                Asserts.assertEquals(0, compressChars(source, 3, actual, 5, 97));
                Asserts.assertTrue(Arrays.equals(expected, actual), "bad char lane=" + bad);
                Arrays.fill(actual, (byte) 0x5a);
                Asserts.assertEquals(0, compressBytes(utf16(source), 3, actual, 5, 97));
                Asserts.assertTrue(Arrays.equals(expected, actual), "bad byte lane=" + bad);
            }
        }

        private static void testRangeFallbacks() throws Exception {
            char[] source = chars(16);
            for (int off : new int[] {-1, Integer.MAX_VALUE}) {
                expectBounds(() -> compressChars(source, off, new byte[16], 0, 1));
                expectBounds(() -> compressBytes(utf16(source), off, new byte[16], 0, 1));
                expectBounds(() -> inflateChars(latin(16), off, new char[16], 0, 1));
                expectBounds(() -> inflateBytes(latin(16), 0, new byte[32], off, 1));
            }
            // Prechecking the full range must reexecute before any write so
            // Java retains its partial writes and original exception order.
            byte[] actual = filled(3, (byte) 0x5a);
            expectBounds(() -> compressChars(source, 0, actual, 1, 8));
            Asserts.assertEquals((byte) 0x5a, actual[0]);
            Asserts.assertEquals((byte) source[0], actual[1]);
            Asserts.assertEquals((byte) source[1], actual[2]);
        }

        private static void expectBounds(Throwing action) throws Exception {
            try {
                action.run();
                throw new AssertionError("expected bounds exception");
            } catch (java.lang.reflect.InvocationTargetException e) {
                Asserts.assertTrue(e.getCause() instanceof IndexOutOfBoundsException,
                        "expected bounds exception: " + e.getCause());
            }
        }

        private static void testColdInputsAndLongArrays() throws Exception {
            Asserts.assertEquals(0, compressChars(null, 0, null, 0, 0));
            expectNpe(() -> compressChars(null, 0, new byte[1], 0, 1));
            expectNpe(() -> inflateChars(null, 0, new char[1], 0, 1));
            char[] longChars = chars(4097);
            byte[] expected = new byte[4100];
            byte[] actual = expected.clone();
            int ref = referenceCompressChars(longChars, 0, expected, 1, 4097);
            int result = compressChars(longChars, 0, actual, 1, 4097);
            Asserts.assertEquals(ref, result);
            Asserts.assertTrue(Arrays.equals(expected, actual), "long compress");
        }

        private static int compressChars(char[] a, int so, byte[] b, int doff, int len) throws Exception {
            return (Integer) caller("compressChars", char[].class, byte[].class)
                    .invoke(null, a, so, b, doff, len);
        }

        private static int compressBytes(byte[] a, int so, byte[] b, int doff, int len) throws Exception {
            return (Integer) caller("compressBytes", byte[].class, byte[].class)
                    .invoke(null, a, so, b, doff, len);
        }

        private static void inflateChars(byte[] a, int so, char[] b, int doff, int len) throws Exception {
            caller("inflateChars", byte[].class, char[].class).invoke(null, a, so, b, doff, len);
        }

        private static void inflateBytes(byte[] a, int so, byte[] b, int doff, int len) throws Exception {
            caller("inflateBytes", byte[].class, byte[].class).invoke(null, a, so, b, doff, len);
        }

        private static int referenceCompressChars(char[] src, int so, byte[] dst, int doff, int len) {
            for (int i = 0; i < len; i++) {
                char c = src[so++];
                if (c > 0xff) {
                    return 0;
                }
                dst[doff++] = (byte) c;
            }
            return len;
        }

        private static int referenceCompressBytes(byte[] src, int so, byte[] dst, int doff, int len) {
            for (int i = 0; i < len; i++) {
                char c = getUtf16(src, so++);
                if (c > 0xff) {
                    return 0;
                }
                dst[doff++] = (byte) c;
            }
            return len;
        }

        private static void referenceInflateChars(byte[] src, int so, char[] dst, int doff, int len) {
            for (int i = 0; i < len; i++) {
                dst[doff++] = (char) (src[so++] & 0xff);
            }
        }

        private static void referenceInflateBytes(byte[] src, int so, byte[] dst, int doff, int len) {
            for (int i = 0; i < len; i++) {
                putUtf16(dst, doff++, (char) (src[so++] & 0xff));
            }
        }

        private static char[] chars(int n) {
            char[] a = new char[n];
            for (int i = 0; i < n; i++) {
                a[i] = (char) (0x20 + (i * 37 & 0x7f));
            }
            return a;
        }

        private static byte[] latin(int n) {
            byte[] a = new byte[n];
            for (int i = 0; i < n; i++) {
                a[i] = (byte) (i * 29);
            }
            return a;
        }

        private static byte[] utf16(char[] a) {
            byte[] b = new byte[a.length * 2];
            for (int i = 0; i < a.length; i++) {
                putUtf16(b, i, a[i]);
            }
            return b;
        }

        private static char getUtf16(byte[] a, int i) {
            int p = i << 1;
            if (java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.BIG_ENDIAN) {
                return (char) (((a[p] & 0xff) << 8) | (a[p + 1] & 0xff));
            }
            return (char) ((a[p] & 0xff) | ((a[p + 1] & 0xff) << 8));
        }

        private static void putUtf16(byte[] a, int i, char c) {
            int p = i << 1;
            if (java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.BIG_ENDIAN) {
                a[p] = (byte) (c >>> 8);
                a[p + 1] = (byte) c;
            } else {
                a[p] = (byte) c;
                a[p + 1] = (byte) (c >>> 8);
            }
        }

        private static byte[] filled(int n, byte x) {
            byte[] a = new byte[n];
            Arrays.fill(a, x);
            return a;
        }

        private static void expectNpe(Throwing action) throws Exception {
            try {
                action.run();
                throw new AssertionError("expected NPE");
            } catch (java.lang.reflect.InvocationTargetException e) {
                Asserts.assertTrue(e.getCause() instanceof NullPointerException,
                        "expected NPE: " + e.getCause());
            }
        }

        @FunctionalInterface
        private interface Throwing {
            void run() throws Exception;
        }
    }
}
