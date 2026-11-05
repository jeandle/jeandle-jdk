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
 * Inc., 51 Franklin St, Fifth Floor, MA 02110-1301 USA.
 */

/*
 * @test
 * @summary Test Jeandle lowering of the ChaCha20 block intrinsic and the
 *          Poly1305 multi-block intrinsic (driven through the
 *          ChaCha20-Poly1305 AEAD). All outputs must match the
 *          intrinsics-disabled reference run and the RFC 8439 2.4.2 vector.
 * @requires os.arch == "amd64" | os.arch == "x86_64"
 * @library /test/lib /
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *      compiler.jeandle.intrinsic.TestChaCha20Intrinsics
 */

package compiler.jeandle.intrinsic;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.CRC32;

import javax.crypto.Cipher;
import javax.crypto.spec.ChaCha20ParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.sun.management.HotSpotDiagnosticMXBean;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;
import jdk.test.whitebox.cpuinfo.CPUInfo;

public class TestChaCha20Intrinsics {

    public static void main(String[] args) throws Exception {
        OutputAnalyzer reference = ProcessTools.executeCommand(
                ProcessTools.createLimitedTestJavaProcessBuilder(
                        "-XX:+UnlockDiagnosticVMOptions", "-XX:+WhiteBoxAPI",
                        "-Xbootclasspath/a:.",
                        "-XX:-UseChaCha20Intrinsics", "-XX:-UsePoly1305Intrinsics",
                        TestWrapper.class.getName()));
        reference.shouldHaveExitValue(0).shouldContain("TestChaCha20Intrinsics PASSED");

        Path dumpPath = Files.createTempDirectory("jeandle_chacha_intrinsics");
        List<String> commandArgs = new java.util.ArrayList<>(List.of(
                "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler",
                "-XX:+UnlockDiagnosticVMOptions", "-XX:+WhiteBoxAPI", "-Xbootclasspath/a:.",
                "-XX:CompileThreshold=1000",
                "-XX:+UseChaCha20Intrinsics",
                "-XX:+UsePoly1305Intrinsics",
                "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dumpPath,
                "-XX:CompileCommand=compileonly,com/sun/crypto/provider/ChaCha20Cipher.*",
                "-XX:CompileCommand=compileonly,com/sun/crypto/provider/Poly1305.*",
                TestWrapper.class.getName()));

        OutputAnalyzer output = ProcessTools.executeCommand(
                ProcessTools.createLimitedTestJavaProcessBuilder(commandArgs));
        output.shouldHaveExitValue(0).shouldContain("TestChaCha20Intrinsics PASSED");
        Asserts.assertEquals(cryptoLines(reference), cryptoLines(output),
                "ChaCha20/Poly1305 results differ from the reference run");

        String ir;
        try (var walk = Files.walk(dumpPath)) {
            ir = walk.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".ll"))
                    .map(path -> {
                        try {
                            return Files.readString(path);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .reduce("", String::concat);
        }
        // Routine probes carry the open paren so only the exact routine name
        // can satisfy the substring check; a distinct routine name is never a
        // prefix of another one here. The chacha20Block stub is registered on
        // every x86-64 with AVX whenever UseChaCha20Intrinsics is on.
        boolean chachaFlag = effectiveFlag(output, "UseChaCha20Intrinsics");
        Asserts.assertEquals(chachaFlag, ir.contains("StubRoutines_chacha20Block("),
                "ChaCha20 routine presence must match UseChaCha20Intrinsics");
        // UsePoly1305Intrinsics only stays enabled with AVX512 IFMA + BW/VL,
        // the feature set StubGenerator's poly1305 vector loop requires.
        boolean polyFlag = effectiveFlag(output, "UsePoly1305Intrinsics");
        boolean polyStubExpected = polyFlag && effectiveFlag(output, "AVX512IFMA");
        Asserts.assertEquals(polyStubExpected,
                ir.contains("StubRoutines_poly1305_processBlocks("),
                "Poly1305 routine presence must match UsePoly1305Intrinsics && avx512ifma");
    }

    // Mirrors the predicate VM_Version uses to keep UsePoly1305Intrinsics
    // on. The feature list spells the extension with a separator
    // ("avx512_ifma"), so the fuzzy substring uses the suffix alone.
    private static boolean hasAvx512IfmaVlbw() {
        List<String> features = CPUInfo.getFeatures();
        return containsFuzzy(features, "ifma")
                && containsFuzzy(features, "avx512bw")
                && containsFuzzy(features, "avx512vl");
    }

    private static boolean containsFuzzy(List<String> list, String sub) {
        for (String s : list) {
            if (s.contains(sub)) return true;
        }
        return false;
    }

    private static boolean effectiveFlag(OutputAnalyzer output, String flag) {
        String prefix = "FLAG " + flag + "=";
        String line = output.getOutput().lines()
                .filter(l -> l.startsWith(prefix)).findFirst()
                .orElseThrow(() -> new AssertionError("Missing effective VM flag: " + flag));
        return Boolean.parseBoolean(line.substring(prefix.length()));
    }

    private static List<String> cryptoLines(OutputAnalyzer output) {
        return output.getOutput().lines()
                .filter(line -> line.startsWith("CRYPT "))
                .toList();
    }

    static class TestWrapper {
        // RFC 8439 2.4.2 key, nonce and plaintext; the expected ciphertext is
        // asserted so both subprocesses are pinned to the RFC vector.
        static final byte[] KEY = hex(
                "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
        static final byte[] NONCE = hex("000000000000004a00000000");
        static final byte[] RFC_PLAIN = hex(
                "4c616469657320616e642047656e746c656d656e206f662074686520636c6173" +
                "73206f66202739393a204966204920636f756c64206f6666657220796f75206f" +
                "6e6c79206f6e652074697020666f7220746865206675747572652c2073756e73" +
                "637265656e20776f756c642062652069742e");
        static final byte[] RFC_CIPHER = hex(
                "6e2e359a2568f98041ba0728dd0d6981e97e7aec1d4360c20a27afccfd9fae0b" +
                "f91b65c5524733ab8f593dabcd62b3571639d624e65152ab8f530c359f0861d8" +
                "07ca0dbf500d6a6156a38e088a22b65e52bc514d16ccf806818ce91ab7793736" +
                "5af90bbf74a35be6b40b8eedf2785e42874d");

        // Poly1305.processMultipleBlocks needs at least two 16-byte MAC input
        // blocks, i.e. a plaintext of at least 32 bytes: every recorded AEAD
        // length below qualifies.
        static final int[] LENGTHS = {64, 256, 1024, 8192};
        // Hot enough for the cipher and authenticator methods to pass
        // -XX:CompileThreshold.
        static final int WARMUP = 2000;

        public static void main(String[] args) throws Exception {
            System.out.println("FLAG AVX512IFMA=" + hasAvx512IfmaVlbw());
            System.out.println("FLAG UseChaCha20Intrinsics=" + flag("UseChaCha20Intrinsics"));
            System.out.println("FLAG UsePoly1305Intrinsics=" + flag("UsePoly1305Intrinsics"));

            // Warmup (silent): drive compilation of the ChaCha20 and
            // ChaCha20-Poly1305 paths past CompileThreshold.
            for (int i = 0; i < WARMUP; i++) {
                exercise(false, 64);
                exercise(false, 1024);
            }

            // Recorded runs.
            for (int length : LENGTHS) {
                exercise(true, length);
            }

            // RFC 8439 2.4.2 vector (single 114-byte message).
            Cipher enc = Cipher.getInstance("ChaCha20", "SunJCE");
            enc.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(KEY, "ChaCha20"),
                    new ChaCha20ParameterSpec(NONCE, 1));
            byte[] sealed = enc.doFinal(RFC_PLAIN);
            Asserts.assertEquals(hexOf(RFC_CIPHER), hexOf(sealed),
                    "RFC 8439 2.4.2 ChaCha20 ciphertext");
            System.out.println("CRYPT chacha rfc " + hexOf(sealed));

            System.out.println("TestChaCha20Intrinsics PASSED");
        }

        // ChaCha20-only encryption drives implChaCha20Block; the AEAD seal
        // additionally drives Poly1305.processMultipleBlocks on the
        // ciphertext produced so far.
        private static void exercise(boolean record, int length) throws Exception {
            byte[] plain = data(length);
            Cipher c = Cipher.getInstance("ChaCha20", "SunJCE");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(KEY, "ChaCha20"),
                    new ChaCha20ParameterSpec(NONCE, 1));
            byte[] out = c.doFinal(plain);
            Cipher aead = Cipher.getInstance("ChaCha20-Poly1305", "SunJCE");
            aead.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(KEY, "ChaCha20"),
                    new IvParameterSpec(NONCE));
            byte[] sealed = aead.doFinal(plain);
            if (record) {
                System.out.println("CRYPT chacha enc " + length + " " + hexOf(out));
                System.out.println("CRYPT aead seal " + length + " " + digestOf(sealed));
            }
        }

        private static String flag(String name) throws Exception {
            return ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class)
                    .getVMOption(name).getValue().toString();
        }

        static byte[] data(int length) {
            byte[] data = new byte[length];
            for (int i = 0; i < length; i++) {
                data[i] = (byte) (i * 41 + 5);
            }
            return data;
        }

        static byte[] hex(String s) {
            byte[] out = new byte[s.length() / 2];
            for (int i = 0; i < out.length; i++) {
                out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
            }
            return out;
        }

        static String hexOf(byte[] data) {
            StringBuilder sb = new StringBuilder();
            for (byte b : data) sb.append(String.format("%02x", b));
            return sb.toString();
        }

        static String digestOf(byte[] data) {
            CRC32 crc = new CRC32();
            crc.update(data);
            return String.format("%08x", crc.getValue());
        }
    }
}
