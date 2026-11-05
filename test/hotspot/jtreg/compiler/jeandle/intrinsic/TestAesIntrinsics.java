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
 *          encrypt/decrypt, CBC, ECB, CTR and GCM/GHASH. DES/CBC and DES/CTR
 *          payloads drive the embeddedCipher instanceof-miss Java fallback of
 *          the compiled CBC and CTR mode methods, and a reflective conjoint
 *          decrypt drives the CBC decrypt src==dst fallback edge (CipherCore
 *          copies conjoint Cipher inputs, so the package-private mode API is
 *          used directly). All outputs must match the intrinsics-disabled
 *          reference run.
 * @requires os.arch == "amd64" | os.arch == "x86_64"
 * @library /test/lib /
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *      compiler.jeandle.intrinsic.TestAesIntrinsics
 */

package compiler.jeandle.intrinsic;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.CRC32;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
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
                        "--add-opens", "java.base/com.sun.crypto.provider=ALL-UNNAMED",
                        "-XX:+UnlockDiagnosticVMOptions", "-XX:+WhiteBoxAPI",
                        "-Xbootclasspath/a:.",
                        "-XX:-UseAESIntrinsics", "-XX:-UseAESCTRIntrinsics",
                        "-XX:-UseGHASHIntrinsics",
                        TestWrapper.class.getName()));
        reference.shouldHaveExitValue(0).shouldContain("TestAesIntrinsics PASSED");

        Path dumpPath = Files.createTempDirectory("jeandle_aes_intrinsics");
        List<String> commandArgs = new java.util.ArrayList<>(List.of(
                "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler",
                "-XX:+UnlockDiagnosticVMOptions", "-XX:+WhiteBoxAPI", "-Xbootclasspath/a:.",
                "-XX:CompileThreshold=1000",
                "--add-opens", "java.base/com.sun.crypto.provider=ALL-UNNAMED",
                "-XX:+UseAESIntrinsics",
                "-XX:+UseAESCTRIntrinsics", "-XX:+UseGHASHIntrinsics",
                "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dumpPath,
                "-XX:CompileCommand=compileonly,com/sun/crypto/provider/AESCrypt.*",
                "-XX:CompileCommand=compileonly,com/sun/crypto/provider/CipherBlockChaining.*",
                "-XX:CompileCommand=compileonly,com/sun/crypto/provider/ElectronicCodeBook.*",
                "-XX:CompileCommand=compileonly,com/sun/crypto/provider/CounterMode.*",
                "-XX:CompileCommand=compileonly,com/sun/crypto/provider/GCTR.*",
                "-XX:CompileCommand=compileonly,com/sun/crypto/provider/GaloisCounterMode.*",
                "-XX:CompileCommand=compileonly,com/sun/crypto/provider/GHASH.*",
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
        boolean ecbStubExpected = aesFlag && effectiveFlag(output, "AVX512VAES");
        Asserts.assertEquals(ecbStubExpected, ir.contains("StubRoutines_electronicCodeBook_encryptAESCrypt("),
                "ECB encrypt routine presence must match UseAESIntrinsics && avx512vaes");
        Asserts.assertEquals(ecbStubExpected, ir.contains("StubRoutines_electronicCodeBook_decryptAESCrypt("),
                "ECB decrypt routine presence must match UseAESIntrinsics && avx512vaes");
        boolean ctrFlag = effectiveFlag(output, "UseAESCTRIntrinsics");
        Asserts.assertEquals(ctrFlag, ir.contains("StubRoutines_counterMode_AESCrypt("),
                "CTR routine presence must match UseAESCTRIntrinsics");
        // The combined GCM stub is only generated with AVX512 VAES on x86.
        boolean gcmStubExpected = aesFlag && effectiveFlag(output, "AVX512VAES");
        Asserts.assertEquals(gcmStubExpected,
                ir.contains("StubRoutines_galoisCounterMode_AESCrypt("),
                "GCM routine presence must match UseAESIntrinsics && avx512vaes");
        boolean ghashFlag = effectiveFlag(output, "UseGHASHIntrinsics");
        Asserts.assertEquals(ghashFlag, ir.contains("StubRoutines_ghash_processBlocks("),
                "GHASH routine presence must match UseGHASHIntrinsics");
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
        // 12 bytes, the standard GCM nonce size; a fixed nonce keeps the
        // recorded seal/open output identical across both subprocesses.
        static final byte[] IV_GCM = hex("202122232425262728292a2b");
        static final byte[] DES_KEY = hex("0123456789abcdef");
        static final byte[] DES_IV = hex("3031323334353637");
        static final int[] LENGTHS = {16, 64, 256, 1024}; // 1, 4, 16, 64 blocks
        // The combined AES-GCM intrinsic is only reached for inputs of at
        // least GaloisCounterMode.PARALLEL_LEN (7680) bytes.
        static final int[] GCM_LENGTHS = {7680, 15360};
        static final int[] DES_LENGTHS = {16, 64}; // DES 8-byte blocks
        // Hot enough for the mode methods to pass -XX:CompileThreshold.
        static final int WARMUP = 2000;

        public static void main(String[] args) throws Exception {
            System.out.println("FLAG AVX512VAES=" + hasAvx512Vaes());
            System.out.println("FLAG UseAESIntrinsics=" + flag("UseAESIntrinsics"));
            System.out.println("FLAG UseAESCTRIntrinsics=" + flag("UseAESCTRIntrinsics"));
            System.out.println("FLAG UseGHASHIntrinsics=" + flag("UseGHASHIntrinsics"));

            // Warmup: loads the classes the mode methods reference (e.g.
            // sun.security.util.ArrayUtil) and makes every mode method hot so
            // it is compiled with fully resolved constant pools. The recorded
            // runs below then execute the compiled guarded intrinsics.
            byte[] warm = data(64);
            // Large enough for the combined AES-GCM intrinsic to run.
            byte[] warmGcm = data(GCM_LENGTHS[0]);
            for (int i = 0; i < WARMUP; i++) {
                run(null, "AES/ECB/NoPadding", warm, false);
                run(null, "AES/ECB/NoPadding", warm, true);
                run(null, "AES/CBC/NoPadding", warm, false);
                run(null, "AES/CBC/NoPadding", warm, true);
                runConjoint(null, "AES/CBC/NoPadding", warm);
                run(null, "AES/CTR/NoPadding", warm, false);
                run(null, "AES/CTR/NoPadding", warm, true);
                runGcm(null, warmGcm);
            }

            for (int length : LENGTHS) {
                byte[] plain = data(length);
                run("ECB", "AES/ECB/NoPadding", plain, false);
                run("ECB", "AES/ECB/NoPadding", plain, true);
                run("CBC", "AES/CBC/NoPadding", plain, false);
                run("CBC", "AES/CBC/NoPadding", plain, true);
                runConjoint("CBC", "AES/CBC/NoPadding", plain);
                run("CTR", "AES/CTR/NoPadding", plain, false);
                run("CTR", "AES/CTR/NoPadding", plain, true);
            }

            for (int length : GCM_LENGTHS) {
                runGcm("GCM", data(length));
            }

            // DES/CBC builds CipherBlockChaining and DES/CTR builds
            // CounterMode around a DESCrypt, so the already-compiled AES/CBC
            // and AES/CTR mode methods run their instanceof guard-miss path:
            // every call goes through the Java fallback.
            for (int length : DES_LENGTHS) {
                byte[] plain = data(length);
                runDes("DES/CBC/NoPadding", "DES", plain, false);
                runDes("DES/CBC/NoPadding", "DES", plain, true);
                runDes("DES/CTR/NoPadding", "DESCTR", plain, false);
                runDes("DES/CTR/NoPadding", "DESCTR", plain, true);
            }

            // The conjoint decrypt edge of the CBC intrinsic.
            runReflectiveConjoint("CBC");
            System.out.println("TestAesIntrinsics PASSED");
        }

        private static String flag(String name) throws Exception {
            return ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class)
                    .getVMOption(name).getValue().toString();
        }

        static void run(String label, String transform, byte[] plain,
                        boolean decrypt) throws Exception {
            // ECB is the only transform without an IV; a missing IV would be
            // randomly derived on encrypt (nondeterministic output) and
            // rejected on decrypt.
            IvParameterSpec iv =
                    transform.contains("ECB") ? null : new IvParameterSpec(IV);
            Cipher enc = Cipher.getInstance(transform, "SunJCE");
            enc.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(KEY, "AES"), iv);
            byte[] cipherText = enc.doFinal(plain);
            if (!decrypt) {
                if (label != null) {
                    System.out.println("CRYPT " + label + " enc " + plain.length + " "
                            + hexOf(cipherText));
                }
                return;
            }
            Cipher dec = Cipher.getInstance(transform, "SunJCE");
            dec.init(Cipher.DECRYPT_MODE, new SecretKeySpec(KEY, "AES"), iv);
            byte[] plainBack = dec.doFinal(cipherText);
            if (label != null) {
                System.out.println("CRYPT " + label + " dec " + plain.length + " "
                        + hexOf(plainBack));
            }
        }

        static void runDes(String transform, String label, byte[] plain,
                           boolean decrypt) throws Exception {
            Cipher enc = Cipher.getInstance(transform, "SunJCE");
            enc.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(DES_KEY, "DES"),
                    new IvParameterSpec(DES_IV));
            byte[] cipherText = enc.doFinal(plain);
            if (!decrypt) {
                System.out.println("CRYPT " + label + " enc " + plain.length + " "
                        + hexOf(cipherText));
                return;
            }
            Cipher dec = Cipher.getInstance(transform, "SunJCE");
            dec.init(Cipher.DECRYPT_MODE, new SecretKeySpec(DES_KEY, "DES"),
                    new IvParameterSpec(DES_IV));
            byte[] plainBack = dec.doFinal(cipherText);
            System.out.println("CRYPT " + label + " dec " + plain.length + " "
                    + hexOf(plainBack));
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
            // src == dst through the public Cipher API: CipherCore copies
            // conjoint inputs to an internal buffer, so the mode method sees
            // disjoint arrays and takes the stub path. This pins the
            // end-to-end result of that pipeline; the conjoint edge itself
            // is driven reflectively below.
            int n = dec.doFinal(buf, 0, buf.length, buf, 0);
            if (label != null) {
                System.out.println("CRYPT " + label + " conj " + n + " " + hexOf(buf));
            }
        }

        // CipherCore never hands conjoint buffers to the mode method, so the
        // src==dst edge of the CBC decrypt intrinsic is driven through the
        // package-private mode API directly: CipherBlockChaining.decrypt is
        // invoked reflectively with the same array as cipher and plain.
        static void runReflectiveConjoint(String label) throws Exception {
            Class<?> symCls = Class.forName("com.sun.crypto.provider.SymmetricCipher");
            Class<?> aesCls = Class.forName("com.sun.crypto.provider.AESCrypt");
            Class<?> cbcCls = Class.forName("com.sun.crypto.provider.CipherBlockChaining");

            Constructor<?> aesCtor = aesCls.getDeclaredConstructor();
            aesCtor.setAccessible(true);
            Constructor<?> cbcCtor = cbcCls.getDeclaredConstructor(symCls);
            cbcCtor.setAccessible(true);
            Object cbc = cbcCtor.newInstance(aesCtor.newInstance());

            Method init = cbcCls.getDeclaredMethod("init", boolean.class,
                    String.class, byte[].class, byte[].class);
            init.setAccessible(true);
            init.invoke(cbc, /*decrypting=*/true, "AES", KEY, IV);
            Method reset = cbcCls.getDeclaredMethod("reset");
            reset.setAccessible(true);
            reset.invoke(cbc);
            Method decrypt = cbcCls.getDeclaredMethod("decrypt", byte[].class,
                    int.class, int.class, byte[].class, int.class);
            decrypt.setAccessible(true);

            Cipher enc = Cipher.getInstance("AES/CBC/NoPadding", "SunJCE");
            enc.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(KEY, "AES"),
                    new IvParameterSpec(IV));
            byte[] buf = enc.doFinal(data(64));
            int n = (Integer) decrypt.invoke(cbc, buf, 0, buf.length, buf, 0);
            System.out.println("CRYPT " + label + " rconj " + n + " " + hexOf(buf));
        }

        // Seal and open through the Cipher API: bulk data drives the combined
        // AES-GCM intrinsic while the trailing partial segments drive the
        // separate GCTR and GHASH intrinsics. The fixed nonce keeps the
        // recorded output identical across both subprocesses.
        static void runGcm(String label, byte[] plain) throws Exception {
            Cipher enc = Cipher.getInstance("AES/GCM/NoPadding", "SunJCE");
            enc.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(KEY, "AES"),
                    new GCMParameterSpec(128, IV_GCM));
            byte[] aad = data(48);
            enc.updateAAD(aad);
            byte[] sealed = enc.doFinal(plain);
            Cipher dec = Cipher.getInstance("AES/GCM/NoPadding", "SunJCE");
            dec.init(Cipher.DECRYPT_MODE, new SecretKeySpec(KEY, "AES"),
                    new GCMParameterSpec(128, IV_GCM));
            dec.updateAAD(aad);
            byte[] open = dec.doFinal(sealed);
            if (label != null) {
                // The GCM payloads are tens of KB; a checksum keeps the
                // recorded lines small while still covering every byte.
                System.out.println("CRYPT " + label + " seal " + plain.length + " "
                        + digestOf(sealed));
                System.out.println("CRYPT " + label + " open " + plain.length + " "
                        + digestOf(open));
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

        static String digestOf(byte[] data) {
            CRC32 crc = new CRC32();
            crc.update(data);
            return String.format("%08x", crc.getValue());
        }
    }
}
