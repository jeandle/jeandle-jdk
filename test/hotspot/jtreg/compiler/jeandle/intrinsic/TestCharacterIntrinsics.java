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
 * @summary Character and encoding intrinsic paths, prefix writes and Java fallback
 * @library /test/lib /
 * @build compiler.jeandle.intrinsic.CharacterIntrinsicTestSupport
 * @compile --patch-module java.base=${test.src} java/lang/JeandleCharacterIntrinsicCaller.java
 * @run main/othervm/timeout=600 compiler.jeandle.intrinsic.TestCharacterIntrinsics
 * @run main/othervm/timeout=600 -Dtest.disableEncodingIntrinsics=true compiler.jeandle.intrinsic.TestCharacterIntrinsics
 */
package compiler.jeandle.intrinsic;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Pattern;
import jdk.test.lib.Platform;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestCharacterIntrinsics {
    private static final String CALLER = "java.lang.JeandleCharacterIntrinsicCaller";
    private static final String[][] TARGETS = {
        {"encodeISO", "sun.nio.cs.ISO_8859_1$Encoder.implEncodeISOArray", "_encodeISOArray"},
        {"encodeISOBytes", "java.lang.StringCoding.implEncodeISOArray", "_encodeByteISOArray"},
        {"encodeASCII", "java.lang.StringCoding.implEncodeAsciiArray", "_encodeAsciiArray"},
        {"countPositives", "java.lang.StringCoding.countPositives", "_countPositives"},
        {"isDigit", "java.lang.CharacterDataLatin1.isDigit", "_isDigit"},
        {"isLowerCase", "java.lang.CharacterDataLatin1.isLowerCase", "_isLowerCase"},
        {"isUpperCase", "java.lang.CharacterDataLatin1.isUpperCase", "_isUpperCase"},
        {"isWhitespace", "java.lang.CharacterDataLatin1.isWhitespace", "_isWhitespace"}
    };
    private static final boolean LE = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;
    private static final Map<String, Method> METHODS = new HashMap<>();
    private static long digest;
    private static boolean exactScan;

    public static void main(String[] args) throws Throwable {
        if (args.length != 0 && (args[0].equals("child") || args[0].equals("hot-alias"))) {
            CharacterIntrinsicTestSupport.reportEncodingSupport();
            exactScan = args.length > 1 && args[1].equals("exact-scan");
            Class<?> caller = Class.forName(CALLER);
            for (Method method : caller.getMethods()) METHODS.put(method.getName(), method);
            if (args[0].equals("hot-alias")) {
                hotAlias();
                return;
            }
            warmup();
            System.out.println("CHARACTER COMPILED EXECUTION");
            exercise();
            System.out.println("Character semantics PASSED digest=" + digest);
            return;
        }
        OutputAnalyzer reference = launch("reference", null, null);
        reference.shouldHaveExitValue(0).shouldContain("Character semantics PASSED");
        String expected = result(reference);
        for (String mode : List.of("disabled", "enabled", "sse", "scalar", "pea-off", "flag-off", "method-off", "encode-off")) {
            if (mode.equals("sse") && !Platform.isX64()) continue;
            Path log = Files.createTempFile("character-" + mode, ".xml");
            Path dump = Files.createTempDirectory("character-" + mode + "-ir");
            OutputAnalyzer output = launch(mode, log, dump);
            Files.writeString(Path.of(log + ".stdout"), output.getOutput());
            output.shouldHaveExitValue(0).shouldContain(expected);
            String xml = Files.readString(log);
            String text = output.getOutput();
            boolean encodingEnabled = CharacterIntrinsicTestSupport.encodingEnabled(output);
            List<String> missing = new ArrayList<>();
            for (String[] target : TARGETS) {
                require(xml.lines().anyMatch(line -> line.startsWith("<nmethod ")
                        && line.contains("compiler='jeandle'")
                        && line.contains("method='" + CALLER + " " + target[0] + " ")),
                        "missing compiled wrapper " + target[0] + "; " + log);
                boolean parsed = text.lines().anyMatch(line -> line.contains(target[1] + "(")
                        && line.contains("is parsed as intrinsic"));
                boolean enabled = !List.of("disabled", "method-off").contains(mode)
                        && !(mode.equals("flag-off") && target[0].startsWith("is"))
                        && (!target[0].startsWith("encode") || encodingEnabled);
                if (enabled && !parsed) missing.add(target[2]);
                if (!enabled) require(!parsed, "disabled intrinsic parsed: " + target[2]);
            }
            require(xml.lines().anyMatch(line -> line.startsWith("<nmethod ")
                    && line.contains("compiler='jeandle'")
                    && line.contains("method='" + CALLER + " publicText ")), "missing compiled publicText");
            if (!List.of("disabled", "method-off").contains(mode)) {
                boolean publicScan = false;
                try (var files = Files.list(dump)) {
                    for (Path file : files.filter(f -> f.getFileName().toString().contains("_publicText_")
                            && f.toString().endsWith("_optimized.ll")).toList()) {
                        String ir = Files.readString(file);
                        int begin = ir.indexOf("define hotspotcc");
                        int end = ir.indexOf("\n}", begin);
                        publicScan |= ir.substring(begin, end).contains("call i32 @count_positives_stub(");
                    }
                }
                require(publicScan, "public UTF8 decoding did not reach countPositives: " + dump);
            }
            require(xml.lines().anyMatch(line -> line.startsWith("<nmethod ")
                    && line.contains("compiler='jeandle'")
                    && line.contains("method='" + CALLER + " classify ")), "missing compiled classify");
            if (List.of("enabled", "sse", "scalar", "pea-off").contains(mode)) {
                boolean combined = false;
                try (var files = Files.list(dump)) {
                    for (Path file : files.filter(f -> f.getFileName().toString().contains("_classify_")
                            && f.toString().endsWith("_optimized.ll")).toList()) {
                        String ir = Files.readString(file);
                        int begin = ir.indexOf("define hotspotcc");
                        int end = ir.indexOf("\n}", begin);
                        String body = ir.substring(begin, end);
                        combined |= body.contains("@jeandle.latin1_properties")
                                && body.lines().filter(line -> line.contains("load i8, ptr ")).count() == 1;
                    }
                }
                require(combined, "Latin1 classification did not share one property lookup: " + dump);
            }
            System.out.println(mode + ": " + log + "; IR: " + dump);
            require(missing.isEmpty(), "missing character intrinsic parsing: " + missing);
        }
        Path log = Files.createTempFile("character-hot-alias", ".xml");
        Path dump = Files.createTempDirectory("character-hot-alias-ir");
        OutputAnalyzer output = launch("hot-alias", log, dump);
        Files.writeString(Path.of(log + ".stdout"), output.getOutput());
        output.shouldHaveExitValue(0).shouldContain("Hot alias PASSED");
        boolean encodingEnabled = CharacterIntrinsicTestSupport.encodingEnabled(output);
        String xml = Files.readString(log);
        var traps = Pattern.compile("<uncommon_trap [^>]*reason='intrinsic_or_type_checked_inlining'[^>]*>.*?</uncommon_trap>",
                Pattern.DOTALL).matcher(xml);
        int count = 0;
        while (traps.find()) {
            if (traps.group().contains("method='" + CALLER + " encodeISOBytes ")) count++;
        }
        require(encodingEnabled ? count > 0 && count <= 8 : count == 0,
                "unexpected hot alias traps: " + count + "; " + log);
        require(xml.lines().filter(line -> line.startsWith("<nmethod ")
                && line.contains("compiler='jeandle'")
                && line.contains("method='" + CALLER + " encodeISOBytes ")).count() >= (encodingEnabled ? 2 : 1),
                "missing hot alias compilation/recompilation: " + log);
        System.out.println("hot-alias: " + log + "; traps=" + count + "; IR: " + dump);
    }

    private static OutputAnalyzer launch(String mode, Path log, Path dump) throws Exception {
        List<String> options = new ArrayList<>(List.of(
                "--patch-module=java.base=" + System.getProperty("test.class.path")));
        if (mode.equals("reference")) {
            options.add("-Xint");
        } else {
            options.addAll(List.of("-Xbatch", "-XX:-TieredCompilation", "-XX:CompileThreshold=100",
                    "-XX:+UseJeandleCompiler", "-XX:+UnlockDiagnosticVMOptions",
                    "-XX:+LogCompilation", "-XX:LogFile=" + log, "-Xlog:jeandle=debug",
                    "-XX:+JeandleDumpIR", "-XX:JeandleDumpDirectory=" + dump,
                    "-XX:CompileCommand=compileonly," + CALLER + "::*"));
            String controls = "-_getCharStringU";
            if (mode.equals("disabled")) {
                controls += "," + String.join(",", Arrays.stream(TARGETS).map(t -> "-" + t[2]).toList());
            }
            options.add("-XX:ControlIntrinsic=" + controls);
            if (mode.equals("sse")) options.add("-XX:UseAVX=0");
            if (mode.equals("encode-off")) options.add("-XX:-SpecialEncodeISOArray");
            if (mode.equals("scalar")) options.add("-XX:MaxVectorSize=8");
            if (mode.equals("pea-off")) options.add("-XX:JeandleLLVMOptions=-jeandle-pea-iterations=0");
            if (mode.equals("flag-off")) options.add("-XX:-UseCharacterCompareIntrinsics");
            if (mode.equals("method-off")) {
                String ids = String.join(" ", Arrays.stream(TARGETS).map(t -> t[2]).toList());
                options.add("-XX:CompileCommand=DisableIntrinsic," + CALLER + "::*," + ids);
            }
        }
        CharacterIntrinsicTestSupport.configureChild(options);
        options.addAll(List.of(TestCharacterIntrinsics.class.getName(), mode.equals("hot-alias") ? "hot-alias" : "child",
                List.of("enabled", "sse", "scalar", "pea-off", "flag-off", "encode-off").contains(mode) ? "exact-scan" : "contract-scan"));
        return ProcessTools.executeCommand(ProcessTools.createLimitedTestJavaProcessBuilder(options));
    }

    private static void hotAlias() throws Throwable {
        byte[] source = utf16(new char[32], false);
        byte[] destination = new byte[32];
        for (int i = 0; i < 4000; i++) invoke("encodeISOBytes", source, 16, destination, 0, 16);
        for (int i = 0; i < 10000; i++) {
            require((int) invoke("encodeISOBytes", source, 16, source, 0, 16) == 16, "hot alias result");
        }
        System.out.println("Hot alias PASSED");
    }

    private static String result(OutputAnalyzer output) {
        return output.getOutput().lines().filter(line -> line.startsWith("Character semantics PASSED"))
                .findFirst().orElseThrow();
    }

    private static Object invoke(String name, Object... args) throws Throwable {
        try {
            return METHODS.get(name).invoke(null, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static void warmup() throws Throwable {
        char[] chars = new char[129];
        byte[] bytes = new byte[258];
        byte[] dst = new byte[129];
        Arrays.fill(chars, 'a');
        for (int i = 0; i < 4000; i++) {
            chars[1] = (char) (i & 127);
            put(bytes, 1, chars[1]);
            invoke("encodeISO", chars, 1, dst, 1, 96);
            invoke("encodeISOBytes", bytes, 1, dst, 1, 96);
            invoke("encodeASCII", chars, 1, dst, 1, 96);
            invoke("countPositives", dst, 1, 96);
            invoke("classify", i & 255);
            invoke("publicText", (i & 1) == 0 ? "ASCII 09 Aa\t" : "caf\u00e9 \u0100\ud83d\ude00", i % 3);
            for (int t = 4; t < TARGETS.length; t++) invoke(TARGETS[t][0], i & 255);
        }
    }

    private static void exercise() throws Throwable {
        for (int ch = 0; ch < 256; ch++) {
            predicates(ch);
            int expected = (Character.isDigit(ch) ? 1 : 0) + (Character.isLowerCase(ch) ? 2 : 0)
                    + (Character.isUpperCase(ch) ? 4 : 0) + (Character.isWhitespace(ch) ? 8 : 0);
            require((int) invoke("classify", ch) == expected, "combined predicates: " + ch);
        }
        for (int ch : new int[] {-1, -65536, -65439, 256, 65535, 65536, 65536 + 'a',
                65536 + '0', Integer.MIN_VALUE, Integer.MAX_VALUE}) predicates(ch);
        try {
            invoke("nullReceiver", '0');
            throw new AssertionError("missing receiver NPE");
        } catch (NullPointerException expected) {
            digest++;
        }
        Random random = new Random(0x1a71);
        for (int n : new int[] {0, 1, 7, 8, 15, 16, 17, 31, 32, 33, 63, 64, 65, 129, 257}) {
            char[] chars = new char[n + 4];
            for (int kind = 0; kind < 3; kind++) {
                for (int bad : new int[] {-1, 0, n / 2, n - 1}) {
                    for (int i = 0; i < chars.length; i++) chars[i] = (char) random.nextInt(kind == 2 ? 128 : 256);
                    if (bad >= 0 && bad < n) chars[bad + 1] = (char) (kind == 2 ? 128 : 256);
                    Object src = kind == 1 ? utf16(chars, true) : chars;
                    byte[] dst = new byte[n + 4];
                    Arrays.fill(dst, (byte) 85);
                    encodeCase(kind, src, 1, dst, 2, n);
                }
            }
            byte[] scan = new byte[n + 3];
            Arrays.fill(scan, (byte) 127);
            for (int bad : new int[] {-1, 0, n / 2, n - 1}) {
                Arrays.fill(scan, (byte) 127);
                if (bad >= 0 && bad < n) scan[bad + 1] = (byte) 128;
                int actual = (int) invoke("countPositives", scan, 1, n);
                int first = 0;
                while (first < n && scan[first + 1] >= 0) first++;
                require(first == n ? actual == n : 0 <= actual && actual <= first,
                        "scan prefix contract: " + n + "/" + bad + " -> " + actual);
                digest = 31 * digest + first;
            }
        }
        scanPositions();
        denseEncodingFailures();
        for (int[] range : new int[][] {{0, 0}, {-1, 0}, {Integer.MAX_VALUE, 0},
                {0, -1}, {1, -1}, {Integer.MAX_VALUE, 2}, {1, Integer.MIN_VALUE}}) {
            byte[] bytes = {1, 2, -1, 4};
            Object expected;
            try {
                int end = range[0] + range[1];
                int p = range[0];
                while (p < end && bytes[p] >= 0) p++;
                expected = p < end ? p - range[0] : range[1];
            } catch (ArrayIndexOutOfBoundsException e) {
                expected = e.getClass();
            }
            Object actual;
            try { actual = invoke("countPositives", bytes, range[0], range[1]); }
            catch (ArrayIndexOutOfBoundsException e) { actual = e.getClass(); }
            require(expected.equals(actual), "scan fallback: " + Arrays.toString(range));
        }
        require((int) invoke("countPositives", null, -1, 0) == 0, "empty null scan");
        for (int kind = 0; kind < 3; kind++) {
            Object src = kind == 1 ? utf16(new char[] {'A', 'B', '\u0100', 'D'}, false)
                                   : new char[] {'A', 'B', '\u0100', 'D'};
            for (int[] range : new int[][] {{0, 0, 4}, {0, 0, 0}, {0, 0, -1}, {-1, 0, 3},
                    {0, -1, 3}, {0, 2, 3}, {3, 0, 3}, {Integer.MAX_VALUE, 0, 2},
                    {0, Integer.MAX_VALUE, 2}, {0, 0, Integer.MAX_VALUE}}) {
                encodeCase(kind, src, range[0], new byte[3], range[1], range[2]);
            }
            encodeCase(kind, null, -1, null, -1, 0);
            encodeCase(kind, null, 0, new byte[2], 0, 1);
            encodeCase(kind, src, 0, null, 0, 1);
            Object invalidFirst = kind == 1 ? utf16(new char[] {'\u0100'}, false) : new char[] {'\u0100'};
            encodeCase(kind, invalidFirst, 0, new byte[0], 0, 1);
        }
        for (int dp : new int[] {0, 1, 3, 8}) {
            byte[] alias = utf16(new char[] {'a', 'b', 'c', 'd', 'e', 'f'}, false);
            encodeCase(1, alias, 0, alias, dp, 4);
        }
        for (String value : List.of("", "plain ASCII", "caf\u00e9", "\u00ff\u0100", "a\ud83d\ude00b")) {
            for (var charset : List.of(StandardCharsets.ISO_8859_1, StandardCharsets.US_ASCII, StandardCharsets.UTF_8)) {
                int mode = charset.equals(StandardCharsets.ISO_8859_1) ? 0
                        : charset.equals(StandardCharsets.US_ASCII) ? 1 : 2;
                digest = 31 * digest + (int) invoke("publicText", value, mode);
                byte[] encoded = value.getBytes(charset);
                var buffer = charset.encode(value);
                byte[] expected = new byte[buffer.remaining()];
                buffer.get(expected);
                require(Arrays.equals(encoded, expected), "public encoding");
                digest = 31 * digest + Arrays.hashCode(encoded);
            }
        }
    }

    private static void scanPositions() throws Throwable {
        for (int n : new int[] {0, 1, 7, 8, 15, 16, 17, 31, 32, 33, 63, 64, 65, 96, 97, 129}) {
            for (int offset : new int[] {0, 1, 3, 15}) {
                byte[] source = new byte[offset + n];
                for (int first = 0; first <= n; first++) {
                    Arrays.fill(source, (byte) 127);
                    Arrays.fill(source, 0, offset, (byte) -1);
                    if (first < n) source[offset + first] = (byte) -128;
                    int actual = (int) invoke("countPositives", source, offset, n);
                    require(first == n ? actual == n : 0 <= actual && actual <= first,
                            "scan range: offset=" + offset + " n=" + n + " first=" + first + " actual=" + actual);
                    // The native kernels refine a dirty block to its first negative lane.
                    if (exactScan) require(actual == first,
                            "scan refinement: offset=" + offset + " n=" + n + " first=" + first + " actual=" + actual);
                    digest = 31 * digest + first;
                }
            }
        }
    }

    private static void denseEncodingFailures() throws Throwable {
        for (int kind = 0; kind < 3; kind++) {
            for (int n : new int[] {0, 1, 7, 8, 15, 16, 17, 31, 32, 33, 63, 64, 65, 129}) {
                for (int prefix : new int[] {0, 1, n / 2, n}) {
                    char[] chars = new char[n + 3];
                    Arrays.fill(chars, 'x');
                    if (prefix <= n) Arrays.fill(chars, prefix + 1, n + 1,
                            (char) (kind == 2 ? 128 : 256));
                    Object source = kind == 1 ? utf16(chars, true) : chars;
                    byte[] destination = new byte[n + 5];
                    Arrays.fill(destination, (byte) 85);
                    encodeCase(kind, source, 1, destination, 3, n);
                }
            }
        }
        for (int n : new int[] {16, 1024}) {
            for (int stride : new int[] {0, 1, 8}) {
                char[] chars = new char[n];
                byte[] expected = new byte[n];
                for (int i = 0; i < n; i++) {
                    boolean invalid = stride != 0 && i % stride == stride - 1;
                    chars[i] = invalid ? '\u4e2d' : '\u00e9';
                    expected[i] = invalid ? (byte) '?' : (byte) 0xe9;
                }
                require(Arrays.equals(new String(chars).getBytes(StandardCharsets.ISO_8859_1), expected),
                        "public ISO replacement: " + n + "/" + stride);
                var encoder = StandardCharsets.ISO_8859_1.newEncoder()
                        .onMalformedInput(CodingErrorAction.REPLACE)
                        .onUnmappableCharacter(CodingErrorAction.REPLACE);
                byte[] destination = new byte[n + 4];
                Arrays.fill(destination, (byte) 85);
                ByteBuffer output = ByteBuffer.wrap(destination, 2, n);
                CharBuffer input = CharBuffer.wrap(chars);
                require(encoder.encode(input, output, true).isUnderflow()
                                && encoder.flush(output).isUnderflow() && !input.hasRemaining()
                                && output.position() == n + 2,
                        "ISO encoder positions: " + n + "/" + stride);
                byte[] expectedDestination = new byte[n + 4];
                Arrays.fill(expectedDestination, (byte) 85);
                System.arraycopy(expected, 0, expectedDestination, 2, n);
                require(Arrays.equals(destination, expectedDestination), "ISO encoder replacement and suffix");
                digest = 31 * digest + Arrays.hashCode(destination);
            }
        }
    }

    private static void predicates(int ch) throws Throwable {
        for (int t = 4; t < TARGETS.length; t++) {
            int c = t == 4 ? ch : (char) ch;
            if (t != 4 && c > 255) {
                try {
                    invoke(TARGETS[t][0], ch);
                    throw new AssertionError("missing Latin1 table exception: " + ch);
                } catch (ArrayIndexOutOfBoundsException expected) {
                    digest++;
                }
            } else {
                boolean expected = switch (t) {
                    case 4 -> '0' <= ch && ch <= '9';
                    case 5 -> Character.isLowerCase(c);
                    case 6 -> Character.isUpperCase(c);
                    default -> Character.isWhitespace(c);
                };
                boolean actual = (boolean) invoke(TARGETS[t][0], ch);
                require(actual == expected, TARGETS[t][0] + "(" + ch + ")");
                digest = 31 * digest + (actual ? 1 : 0);
            }
        }
    }

    private static void encodeCase(int kind, Object src, int sp, byte[] dst, int dp, int n) throws Throwable {
        byte[] expectedDst = dst == null ? null : dst.clone();
        byte[] actualDst = dst == null ? null : dst.clone();
        Object expectedSrc = src == dst ? expectedDst : src;
        Object actualSrc = src == dst ? actualDst : src;
        Object expected;
        try {
            int i = 0;
            for (; i < n; i++) {
                int c;
                if (kind == 1) {
                    byte[] bytes = (byte[]) expectedSrc;
                    int p = (sp + i) << 1;
                    c = (bytes[p] & 255) << (LE ? 0 : 8) | (bytes[p + 1] & 255) << (LE ? 8 : 0);
                } else {
                    c = ((char[]) expectedSrc)[sp + i];
                }
                if (c > (kind == 2 ? 127 : 255)) break;
                expectedDst[dp + i] = (byte) c;
            }
            expected = i;
        } catch (NullPointerException | ArrayIndexOutOfBoundsException e) {
            expected = e.getClass();
        }
        Object actual;
        try {
            actual = invoke(TARGETS[kind][0], actualSrc, sp, actualDst, dp, n);
        } catch (NullPointerException | ArrayIndexOutOfBoundsException e) {
            actual = e.getClass();
        }
        require(expected.equals(actual) && Arrays.equals(expectedDst, actualDst),
                TARGETS[kind][0] + " sp=" + sp + " dp=" + dp + " n=" + n
                        + " expected=" + expected + "/" + Arrays.toString(expectedDst)
                        + " actual=" + actual + "/" + Arrays.toString(actualDst));
        digest = 31 * digest + Arrays.hashCode(actualDst);
        digest = 31 * digest + (actual instanceof Integer value ? value : 7);
    }

    private static byte[] utf16(char[] chars, boolean odd) {
        byte[] result = new byte[chars.length * 2 + (odd ? 1 : 0)];
        for (int i = 0; i < chars.length; i++) put(result, i, chars[i]);
        return result;
    }

    private static void put(byte[] bytes, int index, char ch) {
        bytes[2 * index] = (byte) (ch >> (LE ? 0 : 8));
        bytes[2 * index + 1] = (byte) (ch >> (LE ? 8 : 0));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
