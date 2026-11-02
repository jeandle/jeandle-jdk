/*
 * Copyright (c) 2026, the Jeandle-JDK Authors. All Rights Reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 */

/*
 * @test
 * @summary Test Jeandle lowering of AES intrinsics: single-block
 *          encrypt/decrypt, CBC, ECB (CTR/GCM/GHASH are added by a later
 *          revision of this test). The conjoint decrypt buffers exercise
 *          the guard-miss Java fallback, which must produce identical
 *          output.
 * @requires os.arch == "amd64" | os.arch == "x86_64"
 * @library /test/lib /
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *      compiler.jeandle.intrinsic.TestAesIntrinsics
 */

package compiler.jeandle.intrinsic;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.sun.management.HotSpotDiagnosticMXBean;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;
import jdk.test.whitebox.cpuinfo.CPUInfo;

public class TestAesIntrinsics {

    public static void main(String[] args) throws Exception {
        OutputAnalyzer reference = ProcessTools.executeCommand(
                ProcessTools.createLimitedTestJavaProcessBuilder(
                        "-XX:+UnlockDiagnosticVMOptions",
                        "-XX:-UseAESIntrinsics", "-XX:-UseAESCTRIntrinsics",
                        "-XX:-UseGHASHIntrinsics",
                        TestWrapper.class.getName()));
        reference.shouldHaveExitValue(0).shouldContain("TestAesIntrinsics PASSED");

        Path dumpPath = Files.createTempDirectory("jeandle_aes_intrinsics");
        List<String> commandArgs = new java.util.ArrayList<>(List.of(
                "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler",
                "-XX:CompileThreshold=1000",
                "-XX:+UnlockDiagnosticVMOptions", "-XX:+UseAESIntrinsics",
                "-XX:+UseAESCTRIntrinsics", "-XX:+UseGHASHIntrinsics",
                "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dumpPath,
                "-XX:CompileCommand=compileonly,com/sun/crypto/provider/AESCrypt.*",
                "-XX:CompileCommand=compileonly,com/sun/crypto/provider/CipherBlockChaining.*",
                "-XX:CompileCommand=compileonly,com/sun/crypto/provider/ElectronicCodeBook.*",
                TestWrapper.class.getName()));

        OutputAnalyzer output = ProcessTools.executeCommand(
                ProcessTools.createLimitedTestJavaProcessBuilder(commandArgs));
        output.shouldHaveExitValue(0).shouldContain("TestAesIntrinsics PASSED");
        Asserts.assertEquals(cipherLines(reference), cipherLines(output),
                "AES results differ from the intrinsics-disabled reference run");

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
        // prefix of another one here.
        boolean aesFlag = effectiveFlag(output, "UseAESIntrinsics");
        // aescrypt/CBC stubs are registered whenever UseAESIntrinsics is on.
        Asserts.assertEquals(aesFlag, ir.contains("StubRoutines_aescrypt_encryptBlock("),
                "aescrypt encrypt routine presence must match UseAESIntrinsics");
        Asserts.assertEquals(aesFlag, ir.contains("StubRoutines_aescrypt_decryptBlock("),
                "aescrypt decrypt routine presence must match UseAESIntrinsics");
        Asserts.assertEquals(aesFlag, ir.contains("StubRoutines_cipherBlockChaining_encryptAESCrypt("),
                "CBC encrypt routine presence must match UseAESIntrinsics");
        Asserts.assertEquals(aesFlag, ir.contains("StubRoutines_cipherBlockChaining_decryptAESCrypt("),
                "CBC decrypt routine presence must match UseAESIntrinsics");
        // ECB stubs are only generated with AVX512 VAES on x86.
        boolean ecbStubExpected = aesFlag && hasAvx512Vaes();
        Asserts.assertEquals(ecbStubExpected, ir.contains("StubRoutines_electronicCodeBook_encryptAESCrypt("),
                "ECB encrypt routine presence must match UseAESIntrinsics && avx512vaes");
        Asserts.assertEquals(ecbStubExpected, ir.contains("StubRoutines_electronicCodeBook_decryptAESCrypt("),
                "ECB decrypt routine presence must match UseAESIntrinsics && avx512vaes");
    }

    // The x86 ECB stubs require the same feature set that
    // StubGenerator::generate_aes_stubs gates on.
    private static boolean hasAvx512Vaes() {
        List<String> features = CPUInfo.getFeatures();
        return containsFuzzy(features, "vaes") && containsFuzzy(features, "avx512vl")
                && containsFuzzy(features, "avx512dq");
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

    private static List<String> cipherLines(OutputAnalyzer output) {
        return output.getOutput().lines()
                .filter(line -> line.startsWith("CRYPT "))
                .toList();
    }

    static class TestWrapper {
        static final byte[] KEY = hex("000102030405060708090a0b0c0d0e0f");
        static final byte[] IV = hex("101112131415161718191a1b1c1d1e1f");
        static final int[] LENGTHS = {16, 64, 256, 1024}; // 1, 4, 16, 64 blocks
        // Hot enough for the mode methods to pass -XX:CompileThreshold.
        static final int WARMUP = 2000;

        public static void main(String[] args) throws Exception {
            System.out.println("FLAG UseAESIntrinsics=" + flag("UseAESIntrinsics"));
            System.out.println("FLAG UseAESCTRIntrinsics=" + flag("UseAESCTRIntrinsics"));
            System.out.println("FLAG UseGHASHIntrinsics=" + flag("UseGHASHIntrinsics"));

            // Warmup: loads the classes the mode methods reference (e.g.
            // sun.security.util.ArrayUtil) and makes every mode method hot so
            // it is compiled with fully resolved constant pools. The recorded
            // runs below then execute the compiled guarded intrinsics.
            byte[] warm = data(64);
            for (int i = 0; i < WARMUP; i++) {
                run(null, "AES/ECB/NoPadding", warm, false);
                run(null, "AES/ECB/NoPadding", warm, true);
                run(null, "AES/CBC/NoPadding", warm, false);
                run(null, "AES/CBC/NoPadding", warm, true);
                runConjoint(null, "AES/CBC/NoPadding", warm);
            }

            for (int length : LENGTHS) {
                byte[] plain = data(length);
                run("ECB", "AES/ECB/NoPadding", plain, false);
                run("ECB", "AES/ECB/NoPadding", plain, true);
                run("CBC", "AES/CBC/NoPadding", plain, false);
                run("CBC", "AES/CBC/NoPadding", plain, true);
                // Conjoint buffers: CBC decrypt's src==dst guard must route
                // to the Java fallback and still produce the right plaintext.
                runConjoint("CBC", "AES/CBC/NoPadding", plain);
            }
            System.out.println("TestAesIntrinsics PASSED");
        }

        private static String flag(String name) throws Exception {
            return ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class)
                    .getVMOption(name).getValue().toString();
        }

        static void run(String label, String transform, byte[] plain,
                        boolean decrypt) throws Exception {
            Cipher enc = Cipher.getInstance(transform, "SunJCE");
            enc.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(KEY, "AES"),
                    transform.contains("CBC") ? new IvParameterSpec(IV) : null);
            byte[] cipherText = enc.doFinal(plain);
            if (!decrypt) {
                if (label != null) {
                    System.out.println("CRYPT " + label + " enc " + plain.length + " "
                            + hexOf(cipherText));
                }
                return;
            }
            Cipher dec = Cipher.getInstance(transform, "SunJCE");
            dec.init(Cipher.DECRYPT_MODE, new SecretKeySpec(KEY, "AES"),
                    transform.contains("CBC") ? new IvParameterSpec(IV) : null);
            byte[] plainBack = dec.doFinal(cipherText);
            if (label != null) {
                System.out.println("CRYPT " + label + " dec " + plain.length + " "
                        + hexOf(plainBack));
            }
        }

        static void runConjoint(String label, String transform, byte[] plain)
                throws Exception {
            Cipher enc = Cipher.getInstance(transform, "SunJCE");
            enc.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(KEY, "AES"),
                    new IvParameterSpec(IV));
            byte[] buf = enc.doFinal(plain);
            Cipher dec = Cipher.getInstance(transform, "SunJCE");
            dec.init(Cipher.DECRYPT_MODE, new SecretKeySpec(KEY, "AES"),
                    new IvParameterSpec(IV));
            // src == dst: exercises the conjoint guard on the decrypt
            // intrinsic fast path.
            int n = dec.doFinal(buf, 0, buf.length, buf, 0);
            if (label != null) {
                System.out.println("CRYPT " + label + " conj " + n + " " + hexOf(buf));
            }
        }

        static byte[] data(int length) {
            byte[] data = new byte[length];
            for (int i = 0; i < length; i++) {
                data[i] = (byte) (i * 37 + 11);
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
    }
}
