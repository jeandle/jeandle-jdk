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
 *
 */

/*
 * @test
 * @summary Test the StringUTF16.compress / StringLatin1.inflate intrinsics
 *          (char[] and byte[] variants) through their JDK callers: character
 *          data crossing the vector-block boundaries, non-Latin1 chars at
 *          every interesting position (compress must answer len-or-0), and
 *          offset sweeps via getChars / StringBuilder cross-coder appends.
 * @requires (os.arch=="amd64" | os.arch=="x86_64" | os.arch=="aarch64") & os.family!="windows"
 * @library /test/lib /
 * @build jdk.test.whitebox.WhiteBox jdk.test.whitebox.code.NMethod compiler.jeandle.intrinsic.StringIntrinsicTestSupport
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm compiler.jeandle.intrinsic.TestStringCopyPublicCallers
 * @run main/othervm compiler.jeandle.intrinsic.TestStringCopyPublicCallers -XX:+UseG1GC
 * @run main/othervm compiler.jeandle.intrinsic.TestStringCopyPublicCallers -XX:-UseCompressedOops
 */

package compiler.jeandle.intrinsic;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import jdk.test.lib.Asserts;
import jdk.test.lib.Utils;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

import static compiler.jeandle.intrinsic.StringIntrinsicTestSupport.*;

public class TestStringCopyPublicCallers {

    public static void main(String[] args) throws Exception {
        String dumpPath = Files.createTempDirectory(
                Path.of(System.getProperty("user.dir")),
                "jeandle_test_compress").toString();

        ArrayList<String> commandArgs = new ArrayList<>();
        Collections.addAll(commandArgs, Utils.getTestJavaOpts());
        // Per-@run VM flags for the spawned process arrive as program
        // arguments: flags on the @run line itself would only reach this
        // driver JVM, not the child that compiles the workload.
        Collections.addAll(commandArgs, args);
        commandArgs.addAll(List.of(
                "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler",
                "-XX:JeandleLLVMOptions=-jeandle-verify-safepoint-coverage=fatal",
                "-Xbootclasspath/a:.", "-XX:+UnlockDiagnosticVMOptions", "-XX:+WhiteBoxAPI",
                "-XX:CompileThreshold=100", "-XX:ReservedCodeCacheSize=256m",
                "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dumpPath,
                // The intrinsics fire at the direct call sites inside the JDK
                // wrapper methods; compile those callers.
                "-XX:CompileCommand=compileonly,java.lang.StringUTF16::compress",
                "-XX:CompileCommand=compileonly,java.lang.StringLatin1::inflate",
                "-XX:CompileCommand=compileonly,java.lang.StringLatin1::getChars",
                "-XX:CompileCommand=compileonly,java.lang.AbstractStringBuilder::inflate",
                // Execute the exact wrapper nmethods bound by begin/endChecks,
                // including calls from compiled String constructors. Keep the
                // five-argument intrinsic candidates eligible for lowering.
                "-XX:CompileCommand=dontinline,java.lang.StringUTF16::compress,([CII)[B",
                "-XX:CompileCommand=dontinline,java.lang.StringUTF16::compress,([BII)[B",
                "-XX:CompileCommand=dontinline,java.lang.StringLatin1::getChars,([BII[CI)V",
                "-XX:CompileCommand=dontinline,java.lang.AbstractStringBuilder::inflate,()V",
                // Include allocating callers as an end-to-end correctness check;
                // this test does not require a toBytes intrinsic.
                "-XX:CompileCommand=compileonly,java.lang.String::<init>",
                "-Xlog:deoptimization=debug",
                TestWrapper.class.getName(), dumpPath));

        OutputAnalyzer output = ProcessTools.executeCommand(
                ProcessTools.createLimitedTestJavaProcessBuilder(commandArgs));
        output.shouldHaveExitValue(0)
              .shouldContain("java.lang.StringUTF16.compress(jobject, jint, jobject, jint, jint)")
              .shouldContain("java.lang.StringLatin1.inflate(jobject, jint, jobject, jint, jint)")
              .shouldContain("is parsed as intrinsic")
              .shouldContain("TestStringCopyPublicCallers: ");
        // Scope residency checks to the workload. These JDK methods can also
        // be called by reflection, assertion messages and VM shutdown code.
        String stdout = output.getStdout();
        int begin = stdout.indexOf("STRING_COPY_CHECKS_BEGIN");
        int end = stdout.indexOf("STRING_COPY_CHECKS_END");
        Asserts.assertTrue(begin >= 0 && end > begin, "missing workload markers");
        String checks = stdout.substring(begin, end);
        Asserts.assertFalse(Pattern.compile(
                "trap_bci=\\d+ (intrinsic(?:_or_type_checked_inlining)?|null_check)\\s")
                .matcher(checks).find(), "unexpected intrinsic deopt during checks: " + checks);

        // Bind the optimized IR to callers checked with WhiteBox in the worker.
        assertContains(dumpPath, "java_lang_StringUTF16_compress",
                "encode_tail", "compress scalar loop not found in dumped IR");
        assertContains(dumpPath, "java_lang_StringLatin1_getChars",
                "inflate_tail", "inflate scalar loop not found in dumped IR");
        if (isAArch64()) {
            assertContains(dumpPath, "java_lang_StringUTF16_compress",
                    "encode_unrolled_body", "compress vector kernel not found in dumped IR");
            assertContains(dumpPath, "java_lang_StringUTF16_compress",
                    "encode_vec_body", "compress vector fail path not found in dumped IR");
            assertContains(dumpPath, "java_lang_StringLatin1_getChars",
                    "inflate_vec_body", "inflate vector kernel not found in dumped IR");
            assertContains(dumpPath, "java_lang_AbstractStringBuilder_inflate",
                    "inflate_last", "inflate overlapping tail not found in dumped IR");
        }
    }

    private static boolean isAArch64() {
        String arch = System.getProperty("os.arch");
        return arch.equals("aarch64") || arch.equals("arm64");
    }

    static class TestWrapper {
        static int v = "x".indexOf("x"); // force String helper classes to load
        static int checks = 0;

        public static void main(String[] args) throws Exception {
            Class<?> utf16 = Class.forName("java.lang.StringUTF16");
            Method[] callers = {
                utf16.getDeclaredMethod("compress", char[].class, int.class, int.class),
                utf16.getDeclaredMethod("compress", byte[].class, int.class, int.class),
                Class.forName("java.lang.StringLatin1").getDeclaredMethod(
                        "getChars", byte[].class, int.class, int.class, char[].class, int.class),
                Class.forName("java.lang.AbstractStringBuilder").getDeclaredMethod("inflate")
            };
            int[] sizes = {1, 2, 7, 8, 9, 15, 16, 17, 31, 32, 33, 63, 64, 65, 100, 500, 4097};
            for (int warm = 0; warm < 20000; warm++) {
                char[] c = latin(65, warm);
                String t = new String(c);
                char[] out = new char[65];
                t.getChars(0, 65, out, 0);
                StringBuilder sb = new StringBuilder(t);
                sb.append('中');
                // Also train inflate()'s already-UTF16 return before measuring
                // whether the compiled public caller stays resident.
                sb.append(t);
                checkChars(sb.toString(), (t + '中' + t).toCharArray(), 0);
                checkChars(new String(new char[] {'中', 'a'}).replace('中', 'b'),
                        new char[] {'b', 'a'}, 0);
            }
            int[] compileIds = beginChecks(args[0], callers);
            System.out.println("STRING_COPY_CHECKS_BEGIN");
            for (int size : sizes) {
                char[] good = latin(size, size + 3);

                // compressC via new String(char[]): success, and a non-Latin1
                // char at every interesting position -> stays UTF16.
                checkChars(new String(good), good, 0);
                if (size > 1) {
                    checkChars(new String(good, 1, size - 1), good, 1); // nonzero srcOff
                }
                for (int at : positions(size)) {
                    char[] mix = good.clone();
                    mix[at] = (char) (0x100 + at);
                    checkChars(new String(mix), mix, 0);
                    mix[at] = 0xFF; // boundary char is still Latin1
                    checkChars(new String(mix), mix, 0);
                }

                // compressB via String.replace shrinking UTF16 to Latin1.
                for (int at : positions(size)) {
                    char[] src = good.clone();
                    src[at] = 0x4e2d;
                    String s = new String(src);
                    char[] rep = src.clone();
                    rep[at] = 'q';
                    checkChars(s.replace('中', 'q'), rep, 0);
                }

                // inflateC via String.getChars on a Latin1 string, from-offset sweep.
                String lat = new String(latin(size, size + 11));
                for (int from = 0; from < size; from += Math.max(1, size / 3)) {
                    char[] out = new char[size + 4];
                    Arrays.fill(out, '#');
                    lat.getChars(from, size, out, 2);
                    char[] exp = new char[size + 4];
                    Arrays.fill(exp, '#');
                    for (int i = from; i < size; i++) {
                        exp[2 + i - from] = lat.charAt(i);
                    }
                    checks++;
                    Asserts.assertTrue(Arrays.equals(out, exp),
                            "getChars size=" + size + " from=" + from);
                }

                // inflateB via a Latin1 StringBuilder growing to UTF16
                // (ASB.inflate copies the existing content with dstOff 0).
                StringBuilder sb = new StringBuilder();
                sb.append(lat);
                sb.append('中');
                sb.append(lat);
                String expected = lat + '中' + lat;
                checkChars(sb.toString(), expected.toCharArray(), 0);
            }
            System.out.println("STRING_COPY_CHECKS_END");
            endChecks(compileIds, callers);
            System.out.println("TestStringCopyPublicCallers: " + checks + " checks");
        }

        // Compare against source chars directly: charAt is outside the
        // compileonly list, so the expected side never goes through a
        // compress/inflate intrinsic.
        private static void checkChars(String got, char[] src, int from) {
            checks++;
            int len = src.length - from;
            Asserts.assertEquals(len, got.length(), "length");
            for (int i = 0; i < len; i++) {
                Asserts.assertEquals(src[from + i], got.charAt(i), "char at " + i);
            }
        }

        private static char[] latin(int size, int seed) {
            char[] c = new char[size];
            for (int i = 0; i < size; i++) {
                c[i] = (char) ('a' + ((i * 17 + seed) % 25));
            }
            return c;
        }

        private static int[] positions(int size) {
            if (size == 0) {
                return new int[0];
            }
            java.util.TreeSet<Integer> s = new java.util.TreeSet<>();
            for (int p : new int[]{0, 1, 7, 8, 15, 16, 17, size / 2, size - 1}) {
                if (p >= 0 && p < size) {
                    s.add(p);
                }
            }
            return s.stream().mapToInt(Integer::intValue).toArray();
        }

    }
}
