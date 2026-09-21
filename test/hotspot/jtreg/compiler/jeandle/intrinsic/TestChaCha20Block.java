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
 * @requires os.family == "linux"
 * @requires (os.simpleArch == "x64" & vm.cpu.features ~= ".*avx.*") |
 *           (os.arch == "aarch64" & vm.cpu.features ~= ".*simd.*")
 * @summary Test Jeandle ChaCha20 lowering and enabled/disabled cipher semantics
 * @library /test/lib /
 * @run main/othervm -XX:+UseJeandleCompiler
 *      --add-opens=java.base/com.sun.crypto.provider=ALL-UNNAMED
 *      compiler.jeandle.intrinsic.TestChaCha20Block worker
 * @run driver compiler.jeandle.intrinsic.TestChaCha20Block
 */

package compiler.jeandle.intrinsic;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.crypto.Cipher;
import javax.crypto.spec.ChaCha20ParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestChaCha20Block {
    private static final String INTRINSIC_LOG =
            "com.sun.crypto.provider.ChaCha20Cipher.implChaCha20Block";
    private static final String WRAPPER_NAME =
            "com_sun_crypto_provider_ChaCha20Cipher_chaCha20Block";
    private static final String INTRINSIC_RECORD =
            "(?m)^.*\\[jeandle\\].*Method `[^\\r\\n`]*" + Pattern.quote(INTRINSIC_LOG) +
            "\\([^\\r\\n`]*` is parsed as intrinsic\\r?$";
    private static final Pattern STUB_CALL = Pattern.compile(
            "(?m)\\bcall\\b[^\\r\\n]*@StubRoutines_chacha20Block\\(");
    private static final Pattern ROOT_FUNCTION = Pattern.compile(
            "(?ms)^define\\b[^\\r\\n]*@\"" + Pattern.quote(WRAPPER_NAME + "([IJ[B)I.") +
            "\\d+\\.root\"\\([^\\r\\n]*\\{\\r?\\n(.*?)^\\}");
    private static final Pattern CIPHERTEXT_DIGEST = Pattern.compile(
            "(?m)^CIPHERTEXT_DIGEST=([0-9a-f]{64})\\r?$");

    public static void main(String[] args) throws Exception {
        if (args.length != 0 && args[0].equals("worker")) {
            String digest = runFunctionalTests();
            verifyWrapperFailures();
            System.out.println("CIPHERTEXT_DIGEST=" + digest);
            return;
        }
        String enabled = verifyMode(true);
        String disabled = verifyMode(false);
        Asserts.assertEquals(enabled, disabled,
                "intrinsic-on ciphertext differs from the Java fallback");
    }

    private static String verifyMode(boolean enabled) throws Exception {
        Path dumpDirectory = Files.createTempDirectory(
                enabled ? "jeandle_chacha20_on" : "jeandle_chacha20_off");
        ArrayList<String> command = new ArrayList<>(List.of(
                "-Xbatch", "-Xcomp", "-XX:-TieredCompilation",
                "-XX:+UnlockDiagnosticVMOptions", "-XX:+UseJeandleCompiler",
                "-XX:+UseChaCha20Intrinsics",
                "--add-opens=java.base/com.sun.crypto.provider=ALL-UNNAMED",
                "-Xlog:jeandle=debug",
                "-XX:+JeandleDumpIR", "-XX:+JeandleDumpObjects",
                "-XX:JeandleDumpDirectory=" + dumpDirectory,
                "-XX:CompileCommand=quiet",
                "-XX:CompileCommand=compileonly,com.sun.crypto.provider.ChaCha20Cipher::chaCha20Block"));
        if (!enabled) {
            command.add("-XX:ControlIntrinsic=-_chacha20Block");
        }
        command.add(TestChaCha20Block.class.getName());
        command.add("worker");

        OutputAnalyzer output = ProcessTools.executeCommand(
                ProcessTools.createLimitedTestJavaProcessBuilder(command));
        output.shouldHaveExitValue(0);
        if (enabled) {
            output.shouldMatch(INTRINSIC_RECORD);
        } else {
            output.shouldNotMatch(INTRINSIC_RECORD);
        }

        verifyLowering(dumpDirectory, enabled);
        Matcher digest = CIPHERTEXT_DIGEST.matcher(output.getStdout());
        if (!digest.find()) {
            throw new AssertionError("worker did not report a ciphertext digest");
        }
        String result = digest.group(1);
        if (digest.find()) {
            throw new AssertionError("worker reported more than one ciphertext digest");
        }
        return result;
    }

    private static void verifyLowering(Path dumpDirectory, boolean enabled) throws Exception {
        List<Path> irFiles;
        try (Stream<Path> files = Files.list(dumpDirectory)) {
            irFiles = files.filter(path -> {
                String name = path.getFileName().toString();
                return name.startsWith(WRAPPER_NAME + "_") && name.endsWith(".ll")
                        && !name.endsWith("_optimized.ll")
                        && !name.endsWith("_inline_callees.ll");
            }).sorted().toList();
        }
        if (irFiles.isEmpty()) {
            throw new AssertionError("no Jeandle IR was dumped for ChaCha20 wrapper");
        }
        // Inspect one raw root function. A declaration, an inlinee dump, or
        // matching fragments from different compilations do not prove lowering.
        Path path = irFiles.get(irFiles.size() - 1);
        Matcher function = ROOT_FUNCTION.matcher(Files.readString(path));
        if (!function.find()) {
            throw new AssertionError("no ChaCha20 wrapper root function in " + path);
        }
        String body = function.group(1);
        boolean hasStubCall = STUB_CALL.matcher(body).find();
        if (enabled && (!hasStubCall || !body.contains("chacha20_state_base")
                || !body.contains("chacha20_result_base"))) {
            throw new AssertionError("incomplete ChaCha20 lowering in " + path);
        }
        if (!enabled && hasStubCall) {
            throw new AssertionError("disabled wrapper still calls the ChaCha20 stub in " + path);
        }
    }

    // Validate the trusted entry contract, not direct calls that bypass it.
    private static void verifyWrapperFailures() throws Exception {
        Class<?> holder = Class.forName("com.sun.crypto.provider.ChaCha20Cipher");
        Method wrapper = holder.getDeclaredMethod("chaCha20Block",
                int[].class, long.class, byte[].class);
        wrapper.setAccessible(true);
        for (int stateLength : new int[] { 0, 15, 17 }) {
            expectWrapperFailure(wrapper, new int[stateLength], new byte[1024],
                    IllegalArgumentException.class);
        }
        for (int resultLength : new int[] { 0, 63, 64, 1023, 1025 }) {
            expectWrapperFailure(wrapper, new int[16], new byte[resultLength],
                    IllegalArgumentException.class);
        }
        expectWrapperFailure(wrapper, null, new byte[1024], NullPointerException.class);
        expectWrapperFailure(wrapper, new int[16], null, NullPointerException.class);
    }

    private static void expectWrapperFailure(Method wrapper, int[] state, byte[] result,
                                             Class<? extends Throwable> expected) throws Exception {
        int[] originalState = state == null ? null : state.clone();
        byte[] originalResult = result == null ? null : result.clone();
        try {
            wrapper.invoke(null, state, 1L, result);
            throw new AssertionError("expected wrapper failure: " + expected.getSimpleName());
        } catch (InvocationTargetException exception) {
            Asserts.assertTrue(expected.isInstance(exception.getCause()),
                    "expected " + expected.getSimpleName() + ", got " + exception.getCause());
        }
        Asserts.assertTrue(Arrays.equals(state, originalState),
                "rejected wrapper call modified the state");
        Asserts.assertTrue(Arrays.equals(result, originalResult),
                "rejected wrapper call modified the result");
    }

    private static String runFunctionalTests() throws Exception {
        HexFormat hex = HexFormat.of();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] key = hex.parseHex(
                "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
        byte[] nonce = hex.parseHex("000000000000004a00000000");
        byte[] plain = hex.parseHex(
                "4c616469657320616e642047656e746c656d656e206f662074686520636c6173" +
                "73206f66202739393a204966204920636f756c64206f6666657220796f75206f" +
                "6e6c79206f6e652074697020666f7220746865206675747572652c2073756e73" +
                "637265656e20776f756c642062652069742e");
        byte[] expected = hex.parseHex(
                "6e2e359a2568f98041ba0728dd0d6981e97e7aec1d4360c20a27afccfd9fae0b" +
                "f91b65c5524733ab8f593dabcd62b3571639d624e65152ab8f530c359f0861d8" +
                "07ca0dbf500d6a6156a38e088a22b65e52bc514d16ccf806818ce91ab7793736" +
                "5af90bbf74a35be6b40b8eedf2785e42874d");
        byte[] actual = crypt(Cipher.ENCRYPT_MODE, key, nonce, 1, plain);
        if (!Arrays.equals(expected, actual)) {
            throw new AssertionError("RFC 7539 ChaCha20 vector mismatch");
        }
        digest.update(actual);

        Random random = new Random(0x4348414348413230L);
        int[] lengths = {0, 1, 63, 64, 65, 127, 128, 129, 255, 256, 257, 1023, 1024, 1025, 4097};
        int cases = 1;
        for (int length : lengths) {
            for (int iteration = 0; iteration < 8; iteration++) {
                byte[] randomKey = new byte[32];
                byte[] randomNonce = new byte[12];
                byte[] input = new byte[length];
                random.nextBytes(randomKey);
                random.nextBytes(randomNonce);
                random.nextBytes(input);
                int counter = random.nextInt(1 << 20);
                byte[] encrypted = crypt(Cipher.ENCRYPT_MODE, randomKey, randomNonce, counter, input);
                digest.update(encrypted);
                byte[] decrypted = crypt(Cipher.DECRYPT_MODE, randomKey, randomNonce, counter, encrypted);
                if (!Arrays.equals(input, decrypted)) {
                    throw new AssertionError("random ChaCha20 round trip mismatch at length " + length);
                }
                cases++;
            }
        }
        System.out.println("FUNCTIONAL_CASES=" + cases);
        return hex.formatHex(digest.digest());
    }

    private static byte[] crypt(int mode, byte[] key, byte[] nonce, int counter, byte[] input)
            throws Exception {
        Cipher cipher = Cipher.getInstance("ChaCha20", "SunJCE");
        cipher.init(mode, new SecretKeySpec(key, "ChaCha20"),
                new ChaCha20ParameterSpec(nonce, counter));
        return cipher.doFinal(input);
    }
}
