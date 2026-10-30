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
 * @summary String vector comparisons must retain walkable frames when their guards deoptimize
 * @library /test/lib /
 * @compile --patch-module java.base=${test.src} java/lang/JeandleStringIntrinsicCaller.java
 * @run main/othervm/timeout=180 compiler.jeandle.intrinsic.TestStringIntrinsicDeoptimization
 * @run main/othervm/timeout=180 compiler.jeandle.intrinsic.TestStringIntrinsicDeoptimization preserveFramePointer
 */
package compiler.jeandle.intrinsic;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestStringIntrinsicDeoptimization {
    private static final String CALLER = "java.lang.JeandleStringIntrinsicCaller";
    private static final String[] METHODS = {"compareToL", "compareToLU", "compareToUL", "compareToU"};

    public static void main(String[] args) throws Exception {
        if (args.length != 0 && args[0].equals("child")) {
            exercise();
            System.out.println("String comparison guard deoptimization PASSED");
            return;
        }
        Path log = Files.createTempFile("jeandle-string-guard-deopt", ".xml");
        Path dumps = Files.createTempDirectory("jeandle-string-compare-ir");
        OutputAnalyzer output = ProcessTools.executeTestJava(
                "--patch-module=java.base=" + System.getProperty("test.class.path"),
                "-Xcomp", "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler",
                "-XX:" + (args.length == 0 ? "-" : "+") + "PreserveFramePointer",
                "-XX:+UnlockDiagnosticVMOptions", "-XX:+LogCompilation", "-XX:LogFile=" + log,
                "-XX:+JeandleDumpIR", "-XX:+JeandleDumpObjects", "-XX:JeandleDumpDirectory=" + dumps,
                "-XX:CompileCommand=compileonly," + CALLER + "::*",
                TestStringIntrinsicDeoptimization.class.getName(), "child");
        output.shouldHaveExitValue(0).shouldContain("String comparison guard deoptimization PASSED");
        String xml = Files.readString(log);
        for (String method : METHODS) {
            String nmethod = xml.lines().filter(line -> line.contains("<nmethod")
                    && line.contains("compiler='jeandle'")
                    && line.contains(CALLER + " " + method + " ")).findFirst().orElseThrow(
                            () -> new AssertionError("Missing Jeandle compilation of " + method + ": " + log));
            Matcher compileId = Pattern.compile("compile_id='([0-9]+)'").matcher(nmethod);
            if (!compileId.find()) {
                throw new AssertionError("Missing compilation id: " + nmethod);
            }
            String id = "compile_id='" + compileId.group(1) + "'";
            if (!xml.lines().anyMatch(line -> line.startsWith("<uncommon_trap thread=")
                    && line.contains(id)
                    && line.contains("reason='intrinsic_or_type_checked_inlining'"))) {
                throw new AssertionError("Missing executed intrinsic guard trap for " + method + ": " + log);
            }
        }
        try (var files = Files.list(dumps)) {
            var optimized = files.filter(path -> path.toString().endsWith("_optimized.ll")).toList();
            for (String method : METHODS) {
                Path methodIR = optimized.stream().filter(path -> path.getFileName().toString()
                        .startsWith("java_lang_JeandleStringIntrinsicCaller_" + method + "_"))
                        .findFirst().orElseThrow(() -> new AssertionError("Missing comparison IR for " + method));
                String ir = Files.readString(methodIR);
                Matcher root = Pattern.compile("(?m)^define [^\n]*\\.root\"\\([^\n]* #(\\d+) gc \"hotspotgc\"")
                        .matcher(ir);
                if (!root.find() || !Pattern.compile("(?m)^attributes #" + root.group(1)
                        + " = \\{[^\n]*\"no-realign-stack\"[^\n]*\\}").matcher(ir).find()) {
                    throw new AssertionError("Comparison frame permits stack realignment: " + methodIR);
                }
                // Wide comparison kernels reload the mismatching characters as
                // scalars at the lane offset; the narrow 8/4-lane tails still
                // extract from the vector. Either shape proves the kernel
                // recovers the exact mismatch character.
                if (!Pattern.compile("extractelement <\\d+ x i\\d+>").matcher(ir).find()) {
                    throw new AssertionError("Missing vector mismatch character extraction: " + methodIR);
                }
            }
            Path latinIR = optimized.stream().filter(path -> path.getFileName().toString()
                    .startsWith("java_lang_JeandleStringIntrinsicCaller_compareToL_"))
                    .findFirst().orElseThrow(() -> new AssertionError("Missing Latin1 comparison IR: " + dumps));
            String ir = Files.readString(latinIR);
            if (!ir.contains("load <32 x i8>")
                    || !Pattern.compile("icmp (?:eq|ne) <32 x i8>").matcher(ir).find()) {
                throw new AssertionError("Missing 32-byte Latin1 bulk comparison: " + latinIR);
            }
        }
    }

    private static void exercise() throws Exception {
        Class<?> caller = Class.forName(CALLER);
        for (String name : METHODS) {
            Method method = caller.getMethod(name, byte[].class, byte[].class);
            if (name.equals("compareToL")) {
                exerciseLatin1(method);
                continue;
            }
            boolean leftUTF16 = !name.equals("compareToLU");
            boolean rightUTF16 = !name.equals("compareToUL");
            byte[] left = new byte[leftUTF16 ? 64 : 32];
            byte[] right = new byte[rightUTF16 ? 64 : 32];
            check(method, left, right, leftUTF16, rightUTF16);
            // Exercise both vector mismatch lanes before taking the odd-array
            // guard. A variable vector extraction must not create a frame
            // that the deoptimizer cannot walk.
            for (int lane : new int[]{0, 15, 16, 31}) {
                int offset = rightUTF16 ? lane * 2 : lane;
                right[offset] = 7;
                check(method, left, right, leftUTF16, rightUTF16);
                right[offset] = 0;
            }
            byte[] odd = {1, 0, 2};
            check(method, odd, odd, leftUTF16, rightUTF16);
        }
    }

    private static void exerciseLatin1(Method method) throws Exception {
        for (int length : new int[]{0, 1, 15, 16, 17, 31, 32, 33, 63, 64, 65, 95, 96, 97, 129}) {
            byte[] left = new byte[length];
            for (int i = 0; i < length; i++) {
                left[i] = (byte) (129 + 73 * i);
            }
            byte[] right = left.clone();
            check(method, left, right, false, false);
            for (int lane = 0; lane < length; lane++) {
                right[lane] = (byte) (left[lane] ^ 0x80);
                check(method, left, right, false, false);
                check(method, right, left, false, false);
                right[lane] = left[lane];
            }
            if (length != 0) {
                byte[] prefix = Arrays.copyOf(left, length - 1);
                check(method, left, prefix, false, false);
                check(method, prefix, left, false, false);
            }
            if (length > 1) {
                right[0] = (byte) (left[0] ^ 0x80);
                right[length - 1] = (byte) (left[length - 1] ^ 0x80);
                check(method, left, right, false, false);
            }
        }
        // Take a guard after exercising every bulk mismatch lane, so the
        // deoptimizer must walk the same frame that contains the vector path.
        try {
            method.invoke(null, null, new byte[64]);
            throw new AssertionError("Expected NullPointerException for a null Latin1 array");
        } catch (InvocationTargetException e) {
            if (!(e.getCause() instanceof NullPointerException)) {
                throw e;
            }
        }
    }

    private static void check(Method method, byte[] a, byte[] b, boolean au, boolean bu) throws Exception {
        int an = a.length >> (au ? 1 : 0);
        int bn = b.length >> (bu ? 1 : 0);
        int expected = an - bn;
        for (int i = 0; i < Math.min(an, bn); i++) {
            int x = character(a, i, au);
            int y = character(b, i, bu);
            if (x != y) {
                expected = x - y;
                break;
            }
        }
        int actual = (int) method.invoke(null, a, b);
        if (actual != expected) {
            throw new AssertionError(method.getName() + ": " + actual + " != " + expected);
        }
    }

    private static int character(byte[] a, int index, boolean utf16) {
        if (!utf16) return a[index] & 255;
        int first = a[2 * index] & 255;
        int second = a[2 * index + 1] & 255;
        return ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN
                ? first | second << 8 : first << 8 | second;
    }
}
