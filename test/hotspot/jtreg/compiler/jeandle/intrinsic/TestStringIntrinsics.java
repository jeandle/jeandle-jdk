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
 * @summary Verify all string intrinsic signatures, side effects and fallback semantics
 * @library /test/lib /
 * @compile --patch-module java.base=${test.src} java/lang/JeandleStringIntrinsicCaller.java
 * @run main/othervm/timeout=600 compiler.jeandle.intrinsic.TestStringIntrinsics
 */
package compiler.jeandle.intrinsic;

import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import jdk.test.lib.Platform;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestStringIntrinsics {
    private static final String CALLER = "java.lang.JeandleStringIntrinsicCaller";
    private static final String PASSED = "String intrinsic semantics PASSED";
    private static final boolean BIG = ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN;
    private static final Map<String, Method> METHODS = new HashMap<>();

    // Each entry identifies one VM ID, including overloads with the same name.
    private static final String[][] TARGETS = {
        {"compareToL", "StringLatin1.compareTo([B[B)I"},
        {"compareToU", "StringUTF16.compareTo([B[B)I"},
        {"compareToLU", "StringLatin1.compareToUTF16([B[B)I"},
        {"compareToUL", "StringUTF16.compareToLatin1([B[B)I"},
        {"indexOfL", "StringLatin1.indexOf([B[B)I"},
        {"indexOfU", "StringUTF16.indexOf([B[B)I"},
        {"indexOfUL", "StringUTF16.indexOfLatin1([B[B)I"},
        {"indexOfIL", "StringLatin1.indexOf([BI[BII)I"},
        {"indexOfIU", "StringUTF16.indexOf([BI[BII)I"},
        {"indexOfIUL", "StringUTF16.indexOfLatin1([BI[BII)I"},
        {"indexOfL_char", "StringLatin1.indexOfChar([BIII)I"},
        {"indexOfU_char", "StringUTF16.indexOfChar([BIII)I"},
        {"equalsL", "StringLatin1.equals([B[B)Z"},
        {"equalsU", "StringUTF16.equals([B[B)Z"},
        {"toBytesStringU", "StringUTF16.toBytes([CII)[B"},
        {"getCharsStringU", "StringUTF16.getChars([BII[CI)V"},
        {"compressStringC", "StringUTF16.compress([CI[BII)I"},
        {"compressStringB", "StringUTF16.compress([BI[BII)I"},
        {"inflateStringC", "StringLatin1.inflate([BI[CII)V"},
        {"inflateStringB", "StringLatin1.inflate([BI[BII)V"}
    };

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("probe")) {
            initialize();
            if (args[1].endsWith("_char")) {
                // Link invokeExact in the interpreter before threshold compilation.
                Object[] fixture = {new byte[16], 0, 0, 4};
                for (int i = 0; i < 1000; i++) equal(0, invoke(args[1], fixture), args[1]);
                System.out.println("WARM " + args[1]);
                charProbeEdges(args[1]);
            }
            probe(args[1]);
            System.out.println(PASSED);
            return;
        }
        if (args.length > 0 && args[0].equals("child")) {
            semantics(args[1].equals("valid"));
            return;
        }
        if (args.length > 0 && args[0].equals("valid")) {
            OutputAnalyzer reference = launch("interpreted", null, true);
            reference.shouldHaveExitValue(0).shouldContain(PASSED);
            verify("valid", transcript(reference), true);
            return;
        }
        OutputAnalyzer interpreted = launch("interpreted", null, false);
        interpreted.shouldHaveExitValue(0).shouldContain(PASSED);
        List<String> expected = transcript(interpreted);
        // Disabled mode is also a standalone baseline harness check.
        if (args.length == 0 || args[0].equals("disabled")) {
            verify("disabled", expected, false);
        }
        if (args.length == 0 || args[0].equals("enabled")) {
            OutputAnalyzer validReference = launch("interpreted", null, true);
            validReference.shouldHaveExitValue(0).shouldContain(PASSED);
            verify("valid", transcript(validReference), true);
            verify("enabled", expected, false);
            verifyProbes();
        }
    }

    private static OutputAnalyzer launch(String mode, Path log, boolean valid) throws Exception {
        ArrayList<String> options = new ArrayList<>(List.of(
                "--patch-module=java.base=" + System.getProperty("test.class.path")));
        if (mode.equals("interpreted")) {
            options.add("-Xint");
        } else {
            options.addAll(List.of("-Xbatch", "-Xcomp", "-XX:-TieredCompilation",
                    "-XX:-BackgroundCompilation", "-XX:+UseJeandleCompiler",
                    "-XX:+UnlockDiagnosticVMOptions",
                    "-XX:+LogCompilation", "-XX:LogFile=" + log,
                    "-Xlog:jeandle=debug",
                    "-XX:CompileCommand=compileonly," + CALLER + "::*"));
            // Keep malformed-input fallback in checked Java array accesses.
            // The standalone trusted char-access intrinsics omit bounds checks.
            String controls = "-_getCharStringU,-_putCharStringU";
            if (mode.equals("disabled")) {
                String ids = String.join(",", Arrays.stream(TARGETS)
                        .map(target -> "-_" + target[0]).toList());
                controls += "," + ids;
            }
            if (!valid) options.add("-XX:ControlIntrinsic=" + controls);
        }
        options.add(TestStringIntrinsics.class.getName());
        options.add("child");
        options.add(valid ? "valid" : "all");
        return ProcessTools.executeCommand(ProcessTools.createLimitedTestJavaProcessBuilder(options));
    }

    private static void verify(String mode, List<String> expected, boolean valid) throws Exception {
        Path log = Files.createTempFile("jeandle-string-" + mode, ".xml");
        OutputAnalyzer output = launch(mode, log, valid);
        Path childOutput = Path.of(log + ".stdout");
        Files.writeString(childOutput, output.getOutput());
        System.out.println(mode + ": child output=" + childOutput + "; compilation log=" + log);
        output.shouldHaveExitValue(0).shouldContain(PASSED);
        equal(expected, transcript(output), mode + " versus interpreted results and destinations");
        System.out.println(mode + ": " + expected.size() + " semantic cases match -Xint");
        String xml = Files.readString(log);
        for (String[] target : TARGETS) {
            requireCompiled(xml, target[0], log);
            if (mode.equals("disabled")) {
                require(!output.getOutput().contains(parsingMarker(target)),
                        "disabled intrinsic parsed: " + target[1]);
            }
        }
        System.out.println(mode + ": all 20 wrappers have Jeandle nmethods; "
                + (valid ? "default char-access settings verified" : "semantic checks verified"));
    }

    private static void requireCompiled(String xml, String wrapper, Path log) {
        String method = CALLER + " " + wrapper + " ";
        boolean compiled = xml.lines().anyMatch(line -> line.startsWith("<nmethod ")
                && line.contains("method='" + method)
                && line.matches(".*compiler='[Jj]eandle'.*"));
        require(compiled, "missing Jeandle nmethod for " + method + "; log=" + log);
    }

    private static String parsingMarker(String[] target) {
        int signature = target[1].indexOf('(');
        MethodType type = MethodType.fromMethodDescriptorString(target[1].substring(signature), null);
        String parameters = String.join(", ", type.parameterList().stream()
                .map(parameter -> parameter.isPrimitive() ? "j" + parameter.getName() : "jobject")
                .toList());
        return "java.lang." + target[1].substring(0, signature)
                + "(" + parameters + ")` is parsed as intrinsic";
    }

    private static void verifyProbes() throws Exception {
        List<String> missing = new ArrayList<>();
        for (String[] target : TARGETS) {
            Path log = Files.createTempFile("jeandle-string-probe-" + target[0], ".xml");
            boolean copy = target[0].equals("toBytesStringU") || target[0].equals("getCharsStringU");
            Path dump = copy ? Files.createTempDirectory("jeandle-string-copy-" + target[0]) : null;
            List<String> options = new ArrayList<>(List.of(
                    "--patch-module=java.base=" + System.getProperty("test.class.path"),
                    "-Xbatch", "-Xmixed",
                    target[0].endsWith("_char") ? "-XX:CompileThreshold=100" : "-Xcomp",
                    "-XX:-TieredCompilation", "-XX:-BackgroundCompilation",
                    "-XX:+UseJeandleCompiler", "-XX:+UnlockDiagnosticVMOptions",
                    "-XX:+LogCompilation", "-XX:LogFile=" + log, "-Xlog:jeandle=debug",
                    "-XX:CompileCommand=compileonly," + CALLER + "::" + target[0]));
            if (copy) {
                options.addAll(List.of("-XX:+JeandleDumpIR", "-XX:+JeandleDumpObjects",
                        "-XX:JeandleDumpDirectory=" + dump));
            }
            options.addAll(List.of(TestStringIntrinsics.class.getName(), "probe", target[0]));
            OutputAnalyzer output = ProcessTools.executeCommand(
                    ProcessTools.createLimitedTestJavaProcessBuilder(options));
            Files.writeString(Path.of(log + ".stdout"), output.getOutput());
            output.shouldHaveExitValue(0).shouldContain(PASSED);
            String xml = Files.readString(log);
            requireCompiled(xml, target[0], log);
            for (String[] other : TARGETS) {
                if (!other[0].equals(target[0])) {
                    require(!xml.contains("method='" + CALLER + " " + other[0] + " "),
                            "probe compiled another wrapper: " + other[0] + "; log=" + log);
                }
            }
            boolean parsed = output.getOutput().contains(parsingMarker(target));
            if (copy && parsed) verifyCopyObject(dump, target[0]);
            if (parsed && target[0].endsWith("_char")) {
                require(output.getOutput().indexOf(parsingMarker(target))
                                < output.getOutput().indexOf("WARM " + target[0]),
                        "private search was not compiled before guard cases: " + target[0]);
            }
            System.out.println("probe " + target[0] + ": Jeandle nmethod verified; parsed="
                    + parsed + "; log=" + log);
            if (!parsed) missing.add("_" + target[0] + " (" + target[1] + ")");
        }
        require(missing.isEmpty(), "missing wrapper-local string intrinsic parsing: " + missing);
    }

    private static void verifyCopyObject(Path dump, String wrapper) throws Exception {
        if (!Platform.isLinux() || !Platform.isX64()) return;
        String prefix = CALLER.replace('.', '_') + "_" + wrapper + "_";
        List<Path> objects;
        try (var files = Files.list(dump)) {
            objects = files.filter(path -> path.getFileName().toString().startsWith(prefix)
                    && path.toString().endsWith(".o")).toList();
        }
        require(!objects.isEmpty(), "missing copy object for " + wrapper + "; dump=" + dump);
        for (Path object : objects) {
            byte[] bytes = Files.readAllBytes(object);
            require(bytes.length >= 4 && bytes[0] == 0x7f && bytes[1] == 'E'
                    && bytes[2] == 'L' && bytes[3] == 'F', "expected an ELF object: " + object);
            String name = object.getFileName().toString();
            Path optimized = object.resolveSibling(name.substring(0, name.length() - 2) + "_optimized.ll");
            String ir = Files.readString(optimized);
            require(ir.lines().anyMatch(line ->
                    line.contains("call void @StubRoutines_jshort_disjoint_arraycopy(")
                            && line.contains(", i64 %")),
                    "copy probe must retain a dynamic element-safe copy: " + optimized);
            // The object string table must name the native copy implementation.
            // Inspect the actual codegen output as well as the optimized IR.
            require(new String(bytes, StandardCharsets.ISO_8859_1)
                            .contains("\0StubRoutines_jshort_disjoint_arraycopy\0"),
                    "dynamic copy has no element-safe stub symbol in the ELF object: " + object);
            if (wrapper.equals("toBytesStringU")) {
                // The allocation is tightly coupled to the copy: the payload
                // is written exactly once by the copy, never pre-zeroed.
                require(ir.lines().noneMatch(line -> line.contains("@jeandle.zero_heap_words")
                        || line.contains("@zero_heap_words_stub")),
                        "toBytes allocation must skip bulk payload zeroing: " + optimized);
            }
        }
    }

    private static void charProbeEdges(String wrapper) throws Exception {
        if (wrapper.equals("indexOfL_char")) {
            check(wrapper, 1, new byte[]{(byte) 128, (byte) 255, 0, 127}, -1, 0, 4);
            check(wrapper, 2, new byte[]{(byte) 128, (byte) 255, 0, 127}, 256, 0, 4);
            check(wrapper, -1, null, 0, 0, 0);
            checkFailure(wrapper, NullPointerException.class, null, 0, 0, 1);
            checkFailure(wrapper, ArrayIndexOutOfBoundsException.class, new byte[1], 1, -1, 1);
            checkFailure(wrapper, ArrayIndexOutOfBoundsException.class, new byte[1], 1, 0, 2);
        } else {
            check(wrapper, -1, utf(0xffff), -1, 0, 1);
            check(wrapper, -1, utf(0), 0x10000, 0, 1);
            check(wrapper, -1, utf(0xffff), Integer.MAX_VALUE, 0, 1);
            check(wrapper, -1, utf(1), 1, 1, 1);
            checkFailure(wrapper, NullPointerException.class, null, 0, 0, 0);
            checkFailure(wrapper, StringIndexOutOfBoundsException.class, utf(1), 1, -1, 1);
            checkFailure(wrapper, StringIndexOutOfBoundsException.class, utf(1), 1, 0, 2);
        }
        System.out.println("Private search guard cases PASSED: " + wrapper);
    }

    private static void checkFailure(String wrapper, Class<? extends Throwable> expected,
                                     Object... args) throws Exception {
        try {
            invoke(wrapper, args);
            throw new AssertionError(wrapper + ": expected " + expected.getName());
        } catch (InvocationTargetException e) {
            require(e.getCause().getClass() == expected,
                    wrapper + ": expected " + expected.getName() + ", got " + e.getCause());
            emit(wrapper, "throws " + expected.getName(), args);
        }
    }

    private static void probe(String wrapper) throws Exception {
        byte[] a = new byte[16];
        byte[] b = new byte[16];
        char[] chars = new char[16];
        switch (wrapper) {
            case "compareToL", "compareToU" -> check(wrapper, 0, a, b);
            case "compareToLU" -> check(wrapper, 8, a, b);
            case "compareToUL" -> check(wrapper, -8, a, b);
            case "equalsL", "equalsU" -> check(wrapper, true, a, b);
            case "indexOfL", "indexOfU", "indexOfUL" -> check(wrapper, 0, a, new byte[4]);
            case "indexOfIL", "indexOfIU", "indexOfIUL" -> check(wrapper, 0, a, 8, b, 4, 0);
            case "indexOfL_char", "indexOfU_char" -> check(wrapper, 0, a, 0, 0, 4);
            case "toBytesStringU" -> check(wrapper, new byte[8], chars, 0, 4);
            case "getCharsStringU" -> check(wrapper, null, a, 0, 4, chars, 0);
            case "compressStringC" -> check(wrapper, 4, chars, 0, b, 0, 4);
            case "compressStringB" -> check(wrapper, 4, a, 0, b, 0, 4);
            case "inflateStringC" -> check(wrapper, null, a, 0, chars, 0, 4);
            case "inflateStringB" -> check(wrapper, null, a, 0, b, 0, 4);
            default -> throw new AssertionError("unknown wrapper: " + wrapper);
        }
    }

    private static List<String> transcript(OutputAnalyzer output) {
        return output.getStdout().lines().filter(line -> line.startsWith("CASE ")).toList();
    }

    private static void initialize() throws Exception {
        // Load both helper classes and constant handles before compiling wrappers.
        Class.forName("java.lang.StringUTF16", true, null);
        Class.forName("java.lang.StringLatin1", true, null);
        for (Method method : Class.forName(CALLER).getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isPublic(method.getModifiers())) {
                METHODS.put(method.getName(), method);
            }
        }
    }

    private static void semantics(boolean valid) throws Exception {
        initialize();
        literals();
        reads();
        searchBoundaries();
        explicitCountOddStorage();
        conversions();
        if (!valid) fallbackCases();
        System.out.println(PASSED);
    }

    private static void literals() throws Exception {
        check("compareToL", 1, new byte[]{(byte) 255}, new byte[]{(byte) 254});
        check("compareToL", 255, new byte[]{(byte) 255}, new byte[]{0});
        check("compareToL", -2, new byte[]{1}, new byte[]{3});
        check("compareToU", 255, utf(255), utf(0));
        check("compareToU", 65535, utf(65535), utf(0));
        check("compareToLU", -2, new byte[]{1}, utf(3));
        check("compareToUL", 1, utf(255), new byte[]{(byte) 254});
        check("indexOfL", 2, new byte[]{1, 2, 7, 8, 7, 8}, new byte[]{7, 8});
        check("indexOfU", 2, utf(1, 2, 0x1234, 8, 0x1234, 8), utf(0x1234, 8));
        check("indexOfUL", 2, utf(1, 2, 7, 8, 7, 8), new byte[]{7, 8});
        check("indexOfIL", 2, new byte[]{1, 2, 7, 8, 7, 8}, 6, new byte[]{7, 8}, 2, 1);
        check("indexOfIU", 2, utf(1, 2, 7, 8, 7, 8), 6, utf(7, 8), 2, 1);
        check("indexOfIUL", 2, utf(1, 2, 7, 8, 7, 8), 6, new byte[]{7, 8}, 2, 1);
        check("indexOfL_char", 2, new byte[]{1, 2, (byte) 255, (byte) 255}, 255, 1, 4);
        check("indexOfU_char", 2, utf(1, 2, 0xffff, 0xffff), 0xffff, 1, 4);
        check("indexOfU_char", -1, utf(0), 0x10000, 0, 1);
        check("indexOfU_char", -1, utf(0xffff), -1, 0, 1);
        check("indexOfU_char", -1, utf(0xffff), Integer.MAX_VALUE, 0, 1);
        check("indexOfL_char", 0, new byte[]{0}, 256, 0, 1);
        byte[] dst = {90, 90, 90, 90, 90};
        check("compressStringC", 0, new char[]{255, 1, 256, 2}, 0, dst, 1, 4);
        equal(new byte[]{90, (byte) 255, 1, 90, 90}, dst, "compression failure prefix");
        byte[] bytes = (byte[]) check("toBytesStringU", utf(0x1234, 0xabcd),
                new char[]{9, 0x1234, 0xabcd, 8}, 1, 2);
        equal(utf(0x1234, 0xabcd), bytes, "native endian bytes");
    }

    private static void reads() throws Exception {
        Random random = new Random(20261021);
        for (int n : new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 15, 16, 17, 31, 32, 33, 80, 129, 257}) {
            byte[] a = new byte[n];
            random.nextBytes(a);
            char[] chars = new char[n];
            for (int i = 0; i < n; i++) {
                chars[i] = (char) ((a[i] & 255) + (i % 3 == 0 ? 0x1200 : 0));
            }
            byte[] u = utf(chars);
            byte[] b = a.clone();
            byte[] v = u.clone();
            readPair(a, b, u, v);
            readPair(a, a, u, u);
            if (n > 0) {
                for (int at : new int[]{0, n / 2, n - 1}) {
                    b = a.clone();
                    b[at] ^= 127;
                    char[] other = chars.clone();
                    other[at] ^= 0x7fff;
                    v = utf(other);
                    readPair(a, b, u, v);
                }
                for (int m : new int[]{1, Math.min(3, n), n}) {
                    for (int at : new int[]{0, (n - m) / 2, n - m}) {
                        byte[] needle = Arrays.copyOfRange(a, at, at + m);
                        byte[] wideNeedle = Arrays.copyOfRange(u, 2 * at, 2 * (at + m));
                        searches(a, u, needle, wideNeedle, n, m, 0);
                        searches(a, u, needle, wideNeedle, n, m, Math.min(n, at + 1));
                    }
                }
                // A repeated prefix tests candidate ordering and late mismatch.
                byte[] repeated = new byte[n];
                Arrays.fill(repeated, (byte) 1);
                repeated[n - 1] = 2;
                byte[] wideRepeated = utf(unsigned(repeated));
                searches(repeated, wideRepeated, new byte[]{1, 2}, utf(1, 2), n, 2, 0);
                for (int ch : new int[]{0, 127, 128, 255, -1, 256, 0xffff, 0x10000, Integer.MAX_VALUE}) {
                    check("indexOfL_char", findChar(a, ch, 0, n, false), a, ch, 0, n);
                    check("indexOfU_char", findChar(u, ch, 0, n, true), u, ch, 0, n);
                }
                check("indexOfL_char", -1, a, 255, n, n);
                check("indexOfU_char", -1, u, 255, n, n);
            }
            check("indexOfL", 0, a, new byte[0]);
            check("indexOfU", 0, u, new byte[0]);
            check("indexOfUL", 0, u, new byte[0]);
            // Mixed encoding comparisons must decode even when operands alias.
            if ((n & 1) == 0) {
                check("compareToLU", compare(a, false, a, true), a, a);
                check("compareToUL", compare(a, true, a, false), a, a);
            }
        }
    }

    private static void searchBoundaries() throws Exception {
        for (int n : new int[]{15, 16, 17, 31, 32, 33, 47, 48, 63, 64, 65}) {
            for (int m : new int[]{2, 3, 4, 8, 15}) {
                if (m > n) continue;
                byte[] needle = new byte[m];
                Arrays.fill(needle, (byte) 255);
                // Matching endpoints alone must never establish equality.
                needle[1] = (byte) 128;
                for (int at = 0; at <= n - m; at++) {
                    byte[] source = new byte[n];
                    Arrays.fill(source, (byte) 255);
                    System.arraycopy(needle, 0, source, at, m);
                    for (int from : new int[]{0, at, at + 1}) {
                        searches(source, utf(unsigned(source)), needle,
                                utf(unsigned(needle)), n, m, from);
                    }
                }
                byte[] source = new byte[n];
                Arrays.fill(source, (byte) 255);
                searches(source, utf(unsigned(source)), needle,
                        utf(unsigned(needle)), n, m, 0);
                if (m > 2) {
                    // Distinct endpoints do not replace the interior check.
                    needle[m - 1] = (byte) 254;
                    for (int i = 0; i < n; i++) source[i] = (byte) ((i & 1) == 0 ? 255 : 254);
                    searches(source, utf(unsigned(source)), needle,
                            utf(unsigned(needle)), n, m, 0);
                    System.arraycopy(needle, 0, source, n - m, m);
                    searches(source, utf(unsigned(source)), needle,
                            utf(unsigned(needle)), n, m, 1);
                    Arrays.fill(source, (byte) 255);
                }
                // Many candidates, including starts beyond the legal limit.
                Arrays.fill(needle, (byte) 255);
                searches(source, utf(unsigned(source)), needle,
                        utf(unsigned(needle)), n, m, n - m + 1);
            }
            byte[] source = new byte[n];
            for (int at = 0; at < n; at++) {
                Arrays.fill(source, (byte) 1);
                source[at] = (byte) 255;
                check("indexOfL_char", at, source, 255, 0, n);
                check("indexOfU_char", at, utf(unsigned(source)), 255, 0, n);
                check("indexOfL_char", -1, source, 255, at + 1, n);
            }
        }
    }

    private static void readPair(byte[] a, byte[] b, byte[] u, byte[] v) throws Exception {
        check("equalsL", Arrays.equals(a, b), a, b);
        check("equalsU", equalUtf(u, v), u, v);
        check("compareToL", compare(a, false, b, false), a, b);
        check("compareToU", compare(u, true, v, true), u, v);
        check("compareToLU", compare(a, false, v, true), a, v);
        check("compareToUL", compare(u, true, b, false), u, b);
    }

    private static void searches(byte[] a, byte[] u, byte[] b, byte[] v,
                                 int n, int m, int from) throws Exception {
        check("indexOfL", find(a, n, false, b, m, false, 0), a, b);
        check("indexOfU", find(u, n, true, v, m, true, 0), u, v);
        check("indexOfUL", find(u, n, true, b, m, false, 0), u, b);
        check("indexOfIL", find(a, n, false, b, m, false, from), a, n, b, m, from);
        check("indexOfIU", find(u, n, true, v, m, true, from), u, n, v, m, from);
        check("indexOfIUL", find(u, n, true, b, m, false, from), u, n, b, m, from);
    }

    private static void explicitCountOddStorage() throws Exception {
        for (int n : new int[]{0, 1, 3, 7, 16, 17, 32, 33}) {
            byte[] latin = new byte[n];
            Arrays.fill(latin, (byte) 7);
            if (n > 0) latin[n - 1] = 9;
            for (int sourceTail : new int[]{0, 1}) {
                byte[] source = Arrays.copyOf(utf(unsigned(latin)), 2 * n + sourceTail);
                if (sourceTail != 0) source[source.length - 1] = 99;
                for (int m : new int[]{1, 2, 3}) {
                    byte[] needle = new byte[m];
                    Arrays.fill(needle, (byte) 7);
                    needle[m - 1] = 9;
                    for (int targetTail : new int[]{0, 1}) {
                        byte[] target = Arrays.copyOf(utf(unsigned(needle)), 2 * m + targetTail);
                        if (targetTail != 0) target[target.length - 1] = 88;
                        for (int sourceCount : new int[]{n, Math.max(0, n - 1)}) {
                            for (int targetCount : new int[]{m, Math.max(1, m - 1)}) {
                                for (int from : new int[]{0, sourceCount / 2, sourceCount}) {
                                    check("indexOfIU", find(source, sourceCount, true, target, targetCount, true, from),
                                            source, sourceCount, target, targetCount, from);
                                    check("indexOfIUL", find(source, sourceCount, true, needle, targetCount, false, from),
                                            source, sourceCount, needle, targetCount, from);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private static void conversions() throws Exception {
        for (int n : new int[]{0, 1, 7, 8, 9, 15, 16, 17, 23, 24, 31, 32, 33, 48, 49, 80, 129,
                // Sizes around the copy kernel's inline-vector/libcall boundary.
                511, 512, 513, 4095, 4096, 4097}) {
            char[] source = new char[n + 4];
            for (int i = 0; i < source.length; i++) {
                source[i] = (char) ((i * 73 + 129) & 255);
            }
            byte[] narrow = new byte[source.length];
            for (int i = 0; i < narrow.length; i++) narrow[i] = (byte) source[i];
            byte[] wide = utf(source);
            check("toBytesStringU", Arrays.copyOfRange(wide, 2, 2 * (n + 1)), source, 1, n);
            char[] dstChars = new char[n + 5];
            Arrays.fill(dstChars, '\ua55a');
            char[] expectedChars = dstChars.clone();
            System.arraycopy(source, 1, expectedChars, 2, n);
            check("getCharsStringU", null, wide, 1, n + 1, dstChars, 2);
            equal(expectedChars, dstChars, "getChars complete destination");
            // A dense failure sweep emits the full arrays per position and is
            // quadratic in transcript size; large sizes sample the boundaries.
            int[] failures = new int[n <= 129 ? n + 1 : 16];
            if (n <= 129) {
                for (int i = 0; i < failures.length; i++) failures[i] = i - 1;
            } else {
                int[] sample = {-1, 0, 1, 6, 7, 8, 15, 16, 31, 32,
                        n / 2, n - 33, n - 16, n - 8, n - 2, n - 1};
                System.arraycopy(sample, 0, failures, 0, sample.length);
            }
            for (int fail : failures) {
                char[] input = source.clone();
                if (fail >= 0 && fail < n) input[1 + fail] = 0x1234;
                for (boolean bytes : new boolean[]{false, true}) {
                    byte[] dst = new byte[n + 5];
                    Arrays.fill(dst, (byte) 90);
                    byte[] expected = dst.clone();
                    int prefix = fail >= 0 && fail < n ? fail : n;
                    for (int i = 0; i < prefix; i++) expected[2 + i] = (byte) input[1 + i];
                    check(bytes ? "compressStringB" : "compressStringC",
                            prefix == n ? n : 0, bytes ? utf(input) : input, 1, dst, 2, n);
                    equal(expected, dst, "compress complete destination");
                }
            }
            dstChars = new char[n + 5];
            Arrays.fill(dstChars, '\ua55a');
            expectedChars = dstChars.clone();
            for (int i = 0; i < n; i++) expectedChars[2 + i] = (char) (narrow[1 + i] & 255);
            check("inflateStringC", null, narrow, 1, dstChars, 2, n);
            equal(expectedChars, dstChars, "inflate char destination");
            byte[] dstBytes = new byte[2 * (n + 5)];
            Arrays.fill(dstBytes, (byte) 90);
            byte[] expectedBytes = dstBytes.clone();
            for (int i = 0; i < n; i++) put(expectedBytes, 2 + i, narrow[1 + i] & 255);
            check("inflateStringB", null, narrow, 1, dstBytes, 2, n);
            equal(expectedBytes, dstBytes, "inflate byte destination");
        }
        // Conversion loops are sequential when the byte arrays overlap.
        for (int srcOff : new int[]{0, 1, 2}) {
            for (int dstOff : new int[]{0, 1, 2}) {
                byte[] initial = utf(1, 2, 3, 4, 5, 6, 7, 8);
                byte[] actual = initial.clone();
                byte[] expected = initial.clone();
                int compressed = 4;
                for (int i = 0; i < 4; i++) {
                    int c = unit(expected, srcOff + i, true);
                    if (c > 255) {
                        compressed = 0;
                        break;
                    }
                    expected[dstOff + i] = (byte) c;
                }
                check("compressStringB", compressed, actual, srcOff, actual, dstOff, 4);
                equal(expected, actual, "aliased compress complete destination");
                actual = initial.clone();
                expected = initial.clone();
                for (int i = 0; i < 4; i++) put(expected, dstOff + i, expected[srcOff + i] & 255);
                check("inflateStringB", null, actual, srcOff, actual, dstOff, 4);
                equal(expected, actual, "aliased inflate complete destination");
            }
        }
    }

    private static void fallbackCases() throws Exception {
        // These calls are compared with a separate -Xint VM, including the
        // exact exception class and every array element after partial writes.
        byte[] odd = {1, 2, 3};
        check("compareToLU", compare(odd, false, odd, true), odd, odd);
        check("compareToUL", compare(odd, true, odd, false), odd, odd);
        for (String name : new String[]{"equalsL", "equalsU", "compareToL", "compareToU",
                "compareToLU", "compareToUL"}) {
            observe(name, null, new byte[0]);
            observe(name, new byte[0], null);
            observe(name, new byte[]{1, 2, 9}, new byte[]{1, 2, 8});
            observe(name, new byte[]{1}, new byte[]{1, 0});
        }
        for (String name : new String[]{"indexOfL", "indexOfU", "indexOfUL"}) {
            check(name, 0, null, new byte[0]);
            observe(name, new byte[0], null);
            observe(name, null, new byte[]{1});
            observe(name, new byte[]{1, 2, 9}, new byte[]{1});
        }
        for (String name : new String[]{"indexOfIL", "indexOfIU", "indexOfIUL"}) {
            observe(name, new byte[8], 4, new byte[]{1, 0}, Integer.MIN_VALUE, 0);
            observe(name, new byte[8], 4, new byte[]{1, 0}, -1, 0);
            observe(name, new byte[8], -1, new byte[]{1, 0}, 1, 0);
            observe(name, new byte[8], Integer.MIN_VALUE, new byte[]{1, 0}, 1, 0);
            observe(name, new byte[8], 4, new byte[]{1, 0}, Integer.MAX_VALUE, 0);
            observe(name, new byte[8], 4, new byte[]{1, 0}, 1, -1);
            observe(name, new byte[8], Integer.MAX_VALUE, new byte[]{1, 0}, 1, 0);
            observe(name, new byte[8], 4, new byte[]{1, 0}, 1, Integer.MAX_VALUE);
            observe(name, new byte[8], 4, new byte[0], 0, 0);
            observe(name, null, 0, new byte[]{1, 0}, 1, 0);
        }
        observe("indexOfU_char", new byte[4], 1, -1, 2);
        observe("indexOfU_char", new byte[4], 1, 0, Integer.MAX_VALUE);
        observe("indexOfL_char", new byte[4], 1, -1, 2);
        observe("indexOfL_char", null, 1, 0, 0);
        observe("indexOfU_char", null, 1, 0, 0);
        observe("getCharsStringU", null, 5, 5, null, -1);
        observe("getCharsStringU", null, 5, 4, null, Integer.MAX_VALUE);
        observe("getCharsStringU", new byte[8], -1, 2, new char[4], 0);
        observe("getCharsStringU", new byte[8], 0, Integer.MAX_VALUE, new char[4], 0);
        observe("getCharsStringU", new byte[8], Integer.MIN_VALUE, Integer.MAX_VALUE, new char[4], 0);
        observe("getCharsStringU", new byte[]{1, 2, 9}, 0, 1, new char[2], 1);
        observe("getCharsStringU", utf(1, 2, 3), 0, 3, new char[2], 1);
        observe("getCharsStringU", utf(1, 2), 0, 2, null, 0);
        observe("toBytesStringU", null, Integer.MAX_VALUE, 0);
        observe("toBytesStringU", new char[]{1}, -1, 1);
        observe("toBytesStringU", new char[]{1}, 0, -1);
        for (String name : new String[]{"compressStringC", "compressStringB"}) {
            Object source = name.endsWith("C") ? new char[]{1, 2, 3} : utf(1, 2, 3);
            observe(name, source, 0, new byte[2], 1, 3);
            observe(name, source, -1, new byte[4], 0, 2);
            observe(name, source, 0, null, 0, 0);
            observe(name, null, 0, new byte[4], 0, 0);
            observe(name, source, 0, new byte[4], 0, -1);
            observe(name, source, Integer.MAX_VALUE, new byte[4], 0, 1);
            observe(name, source, 0, new byte[4], Integer.MAX_VALUE, 1);
            observe(name, source, 0, new byte[4], 0, Integer.MAX_VALUE);
        }
        observe("compressStringB", new byte[]{1, 0, 9}, 0, new byte[3], 1, 1);
        observe("inflateStringB", new byte[]{1}, 0, new byte[3], 0, 1);
        for (String name : new String[]{"inflateStringC", "inflateStringB"}) {
            Object dst = name.endsWith("C") ? new char[2] : new byte[4];
            observe(name, new byte[]{1, 2, 3}, 0, dst, 1, 3);
            observe(name, new byte[]{1, 2}, -1, dst, 0, 2);
            observe(name, null, 0, dst, 0, 0);
            observe(name, new byte[]{1}, 0, null, 0, 0);
            observe(name, new byte[]{1}, 0, dst, 0, -1);
            observe(name, new byte[]{1}, Integer.MAX_VALUE, dst, 0, 1);
            observe(name, new byte[]{1}, 0, dst, Integer.MAX_VALUE, 1);
            observe(name, new byte[]{1}, 0, dst, 0, Integer.MAX_VALUE);
        }
    }

    private static Object check(String name, Object expected, Object... args) throws Exception {
        Object actual = invoke(name, args);
        equal(expected, actual, name);
        emit(name, actual, args);
        return actual;
    }

    private static void observe(String name, Object... args) throws Exception {
        try {
            emit(name, invoke(name, args), args);
        } catch (InvocationTargetException e) {
            emit(name, "throws " + e.getCause().getClass().getName(), args);
        }
    }

    private static Object invoke(String name, Object[] args) throws Exception {
        return METHODS.get(name).invoke(null, args);
    }

    private static void emit(String name, Object result, Object[] args) {
        StringBuilder line = new StringBuilder("CASE ").append(name).append(" => ").append(show(result));
        for (Object arg : args) line.append(" | ").append(show(arg));
        System.out.println(line);
    }

    private static String show(Object value) {
        if (value instanceof byte[] a) return Arrays.toString(a);
        if (value instanceof char[] a) {
            int[] values = new int[a.length];
            for (int i = 0; i < a.length; i++) values[i] = a[i];
            return Arrays.toString(values);
        }
        return String.valueOf(value);
    }

    private static void equal(Object expected, Object actual, String message) {
        require(show(expected).equals(show(actual)),
                message + ": expected " + show(expected) + ", got " + show(actual));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static int unit(byte[] value, int index, boolean wide) {
        if (!wide) return value[index] & 255;
        int a = value[2 * index] & 255;
        int b = value[2 * index + 1] & 255;
        return BIG ? (a << 8) | b : a | (b << 8);
    }

    private static void put(byte[] value, int index, int c) {
        value[2 * index] = (byte) (BIG ? c >>> 8 : c);
        value[2 * index + 1] = (byte) (BIG ? c : c >>> 8);
    }

    private static byte[] utf(int... values) {
        byte[] bytes = new byte[2 * values.length];
        for (int i = 0; i < values.length; i++) put(bytes, i, values[i]);
        return bytes;
    }

    private static byte[] utf(char[] values) {
        byte[] bytes = new byte[2 * values.length];
        for (int i = 0; i < values.length; i++) put(bytes, i, values[i]);
        return bytes;
    }

    private static char[] unsigned(byte[] bytes) {
        char[] chars = new char[bytes.length];
        for (int i = 0; i < bytes.length; i++) chars[i] = (char) (bytes[i] & 255);
        return chars;
    }

    private static boolean equalUtf(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length / 2; i++) {
            if (unit(a, i, true) != unit(b, i, true)) return false;
        }
        return true;
    }

    private static int compare(byte[] a, boolean aw, byte[] b, boolean bw) {
        int an = a.length / (aw ? 2 : 1);
        int bn = b.length / (bw ? 2 : 1);
        for (int i = 0; i < Math.min(an, bn); i++) {
            int difference = unit(a, i, aw) - unit(b, i, bw);
            if (difference != 0) return difference;
        }
        return an - bn;
    }

    private static int find(byte[] a, int n, boolean aw, byte[] b, int m, boolean bw, int from) {
        for (int i = from; i <= n - m; i++) {
            int j = 0;
            while (j < m && unit(a, i + j, aw) == unit(b, j, bw)) j++;
            if (j == m) return i;
        }
        return -1;
    }

    private static int findChar(byte[] a, int ch, int from, int max, boolean wide) {
        int expected = wide ? ch : (byte) ch & 255;
        for (int i = from; i < max; i++) {
            if (unit(a, i, wide) == expected) return i;
        }
        return -1;
    }
}
