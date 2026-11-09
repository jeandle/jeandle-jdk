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
 * @summary String comparisons use early exits and recover mismatch characters without vector spills
 * @library /test/lib /
 * @compile --patch-module java.base=${test.src} java/lang/JeandleStringIntrinsicCaller.java
 * @run main/othervm/timeout=180 compiler.jeandle.intrinsic.TestStringCompareFastPaths
 */
package compiler.jeandle.intrinsic;

import java.lang.reflect.Method;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.regex.Pattern;

import jdk.test.lib.process.ProcessTools;

public class TestStringCompareFastPaths {
    private static final String CALLER = "java.lang.JeandleStringIntrinsicCaller";
    private static final String[] METHODS = {"compareToL", "compareToU", "compareToLU", "compareToUL",
                                             "equalsL", "equalsU"};
    private static final int[] LENGTHS = {0, 1, 2, 3, 4, 5, 7, 8, 9, 15, 16, 17,
                                        31, 32, 33, 63, 64, 65, 129};

    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            exercise();
            System.out.println("String comparison fast paths PASSED");
            return;
        }
        Path dumps = Files.createTempDirectory("jeandle-string-compare-fast");
        Path log = dumps.resolve("compilation.xml");
        ProcessTools.executeTestJava(
                "--patch-module=java.base=" + System.getProperty("test.class.path"),
                "-Xcomp", "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler",
                "-XX:+UnlockDiagnosticVMOptions", "-XX:+LogCompilation", "-XX:LogFile=" + log,
                "-XX:+JeandleDumpIR", "-XX:JeandleDumpDirectory=" + dumps,
                "-XX:CompileCommand=compileonly," + CALLER + "::compareTo*",
                "-XX:CompileCommand=compileonly," + CALLER + "::equals*",
                TestStringCompareFastPaths.class.getName(), "child")
                .shouldHaveExitValue(0).shouldContain("String comparison fast paths PASSED");
        String xml = Files.readString(log);
        try (var paths = Files.list(dumps)) {
            var files = paths.filter(path -> path.toString().endsWith("_optimized.ll")).toList();
            for (String name : METHODS) {
                if (!xml.lines().anyMatch(line -> line.contains("<nmethod")
                        && line.contains("compiler='jeandle'") && line.contains(CALLER + " " + name + " "))) {
                    throw new AssertionError("Missing executed Jeandle comparison: " + name);
                }
                Path file = files.stream().filter(path -> path.getFileName().toString()
                        .startsWith("java_lang_JeandleStringIntrinsicCaller_" + name + "_"))
                        .findFirst().orElseThrow(() -> new AssertionError("Missing comparison IR: " + name));
                checkIR(name, Files.readString(file));
            }
        }
    }

    private static void checkIR(String name, String ir) {
        if (name.startsWith("equals")) {
            if (!ir.contains("load <")
                    || !Pattern.compile("icmp eq ptr addrspace\\(1\\) %[\\w.]+, %[\\w.]+")
                            .matcher(ir).find()) {
                throw new AssertionError("Missing live equality kernel with runtime identity: " + name);
            }
            return;
        }
        if (!ir.contains("load <8 x i") || !ir.contains("@llvm.cttz.")) {
            throw new AssertionError("Missing live vector mismatch recovery: " + name);
        }
        if (Pattern.compile("extractelement <(?:4|8) x i16>[^\\n]*, i(?:32|64) %").matcher(ir).find()) {
            throw new AssertionError("Variable vector mismatch extraction spills short comparisons: " + name);
        }
        if (name.equals("compareToL") || name.equals("compareToU")) {
            if (!Pattern.compile("icmp eq ptr addrspace\\(1\\) %[\\w.]+, %[\\w.]+").matcher(ir).find()) {
                throw new AssertionError("Missing runtime backing identity comparison: " + name);
            }
            if (!Pattern.compile("%first\\.diff[^\\n]* = sub (?:nuw |nsw )*i32").matcher(ir).find()) {
                throw new AssertionError("Missing exact first-character difference: " + name);
            }
            if (!Pattern.compile("%first\\.long[\\w.]* = icmp (?:samesign )?"
                    + "(?:uge i32 %[\\w.]+, 16|ugt i32 %[\\w.]+, 15)").matcher(ir).find()) {
                throw new AssertionError("Short comparison does not bypass the first-character probe: " + name);
            }
        }
        if (!name.equals("compareToL")
                && !Pattern.compile("lshr i64 %n4\\.").matcher(ir).find()) {
            throw new AssertionError("Missing packed four-word mismatch recovery: " + name);
        }
        if (!Pattern.compile("@llvm\\.(?:cttz|ctlz)\\.i64\\(i64 %n4\\.").matcher(ir).find()) {
            throw new AssertionError("Missing integer four-word mismatch detection: " + name);
        }
        if (!name.equals("compareToL")
                && ir.contains("load <32 x i16>")
                && (!Pattern.compile("%vw32\\.pos[\\w.]* = phi i32").matcher(ir).find()
                    || !Pattern.compile("%vw32\\.more[\\w.]* = icmp (?:samesign )?"
                            + "(?:uge i32 %vw32\\.left[\\w.]*, 32|ugt i32 %vw32\\.left[\\w.]*, 31)")
                            .matcher(ir).find()
                    || !Pattern.compile("br i1 %vw32\\.more[\\w.]*, label %vw32\\.a\\.select")
                            .matcher(ir).find())) {
            throw new AssertionError("Wide comparisons repeat the width dispatch: " + name);
        }
    }

    private static void exercise() throws Exception {
        Class<?> caller = Class.forName(CALLER);
        for (String name : METHODS) {
            Method method = caller.getMethod(name, byte[].class, byte[].class);
            boolean au = name.equals("compareToU") || name.equals("compareToUL") || name.equals("equalsU");
            boolean bu = name.equals("compareToU") || name.equals("compareToLU") || name.equals("equalsU");
            for (int length : LENGTHS) {
                char[] chars = new char[length];
                for (int i = 0; i < length; i++) {
                    chars[i] = (char) (128 + (i * 73 & 127));
                }
                byte[] left = encode(chars, au);
                byte[] right = encode(chars, bu);
                char[] originalChars = chars.clone();
                check(method, left, right, au, bu);
                if (au == bu || (left.length & 1) == 0) {
                    check(method, left, left, au, bu);
                }
                for (int lane = 0; lane < length; lane++) {
                    char original = chars[lane];
                    chars[lane] ^= 0x80;
                    right = encode(chars, bu);
                    check(method, left, right, au, bu);
                    check(method, encode(chars, au), encode(originalChars, bu), au, bu);
                    if (bu) {
                        chars[lane] = (char) (original + 0x8000);
                        check(method, left, encode(chars, true), au, true);
                    }
                    if (au) {
                        chars[lane] = (char) (original + 0x8000);
                        check(method, encode(chars, true), encode(originalChars, bu), true, bu);
                    }
                    chars[lane] = original;
                }
                if (length != 0) {
                    check(method, left, encode(Arrays.copyOf(chars, length - 1), bu), au, bu);
                }
                // Genuine wide values exercise unsigned code units in both
                // directions, independently of the common Latin1 fixture.
                if (au && bu) {
                    Arrays.fill(chars, '\uffff');
                    check(method, encode(chars, true), right, true, true);
                    check(method, right, encode(chars, true), true, true);
                }
            }
        }
    }

    private static void check(Method method, byte[] left, byte[] right, boolean au, boolean bu)
            throws Exception {
        int an = left.length >> (au ? 1 : 0);
        int bn = right.length >> (bu ? 1 : 0);
        int expected = an - bn;
        for (int i = 0; i < Math.min(an, bn); i++) {
            int difference = character(left, i, au) - character(right, i, bu);
            if (difference != 0) {
                expected = difference;
                break;
            }
        }
        Object actual = method.invoke(null, left, right);
        Object result = method.getReturnType() == boolean.class ? (Object) (expected == 0) : expected;
        if (!actual.equals(result)) {
            throw new AssertionError(method.getName() + ": " + actual + " != " + expected);
        }
    }

    private static int character(byte[] bytes, int index, boolean wide) {
        if (!wide) {
            return bytes[index] & 255;
        }
        int low = bytes[2 * index] & 255;
        int high = bytes[2 * index + 1] & 255;
        return ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? low | high << 8 : low << 8 | high;
    }

    private static byte[] encode(char[] chars, boolean wide) {
        byte[] bytes = new byte[chars.length * (wide ? 2 : 1)];
        for (int i = 0; i < chars.length; i++) {
            if (wide) {
                boolean little = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;
                bytes[2 * i] = (byte) (little ? chars[i] : chars[i] >>> 8);
                bytes[2 * i + 1] = (byte) (little ? chars[i] >>> 8 : chars[i]);
            } else {
                bytes[i] = (byte) chars[i];
            }
        }
        return bytes;
    }
}
