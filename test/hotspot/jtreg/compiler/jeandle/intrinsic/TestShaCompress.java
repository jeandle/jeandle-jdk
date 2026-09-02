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
 * @summary Test Jeandle lowering of single-block SHA compression intrinsics.
 * @requires os.arch == "amd64" | os.arch == "x86_64" | os.arch == "aarch64"
 * @library /test/lib /
 * @build jdk.test.lib.Asserts
 * @run main/othervm compiler.jeandle.intrinsic.TestShaCompress
 */

package compiler.jeandle.intrinsic;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import com.sun.management.HotSpotDiagnosticMXBean;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestShaCompress {
    private static final HexFormat HEX = HexFormat.of();
    private static final String[] INTRINSIC_FLAGS = {
            "UseSHA1Intrinsics", "UseSHA256Intrinsics",
            "UseSHA512Intrinsics", "UseSHA3Intrinsics"
    };
    private static final int[] TEST_LENGTHS = {
            1, 55, 56, 63, 64, 65, 127, 128, 255, 256
    };

    public static void main(String[] args) throws Exception {
        OutputAnalyzer reference = ProcessTools.executeCommand(
                ProcessTools.createLimitedTestJavaProcessBuilder(
                        "-XX:+UnlockDiagnosticVMOptions",
                        "-XX:-UseSHA1Intrinsics", "-XX:-UseSHA256Intrinsics",
                        "-XX:-UseSHA512Intrinsics", "-XX:-UseSHA3Intrinsics",
                        TestWrapper.class.getName()));
        reference.shouldHaveExitValue(0).shouldContain("TestShaCompress PASSED");

        Path dumpPath = Files.createTempDirectory("jeandle_sha_compress");
        List<String> commandArgs = new java.util.ArrayList<>(List.of(
                "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
                "-XX:+UnlockDiagnosticVMOptions", "-XX:+UseSHA1Intrinsics",
                "-XX:+UseSHA256Intrinsics", "-XX:+UseSHA512Intrinsics", "-XX:+UseSHA3Intrinsics",
                "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dumpPath,
                "-XX:CompileCommand=compileonly,sun/security/provider/DigestBase.implCompressMultiBlock0",
                "-XX:CompileCommand=compileonly,sun/security/provider/SHA.implCompress0",
                "-XX:CompileCommand=compileonly,sun/security/provider/SHA.implCompress",
                "-XX:CompileCommand=compileonly,sun/security/provider/SHA2.implCompress0",
                "-XX:CompileCommand=compileonly,sun/security/provider/SHA2.implCompress",
                "-XX:CompileCommand=compileonly,sun/security/provider/SHA5.implCompress0",
                "-XX:CompileCommand=compileonly,sun/security/provider/SHA5.implCompress",
                "-XX:CompileCommand=compileonly,sun/security/provider/SHA3.implCompress0",
                "-XX:CompileCommand=compileonly,sun/security/provider/SHA3.implCompress",
                TestWrapper.class.getName()));

        OutputAnalyzer output = ProcessTools.executeCommand(
                ProcessTools.createLimitedTestJavaProcessBuilder(commandArgs));
        output.shouldHaveExitValue(0).shouldContain("TestShaCompress PASSED");
        List<String> referenceDigests = digestLines(reference);
        Asserts.assertEquals(7 * TEST_LENGTHS.length, referenceDigests.size(),
                "Unexpected number of reference SHA digests");
        Asserts.assertEquals(referenceDigests, digestLines(output),
                "SHA digests differ from the all-intrinsics-disabled reference run");

        String ir = Files.walk(dumpPath)
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".ll"))
                .map(path -> {
                    try {
                        return Files.readString(path);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .reduce("", String::concat);
        assertRoutineMatchesFlag(output, ir, "UseSHA1Intrinsics",
                "StubRoutines_sha1_implCompress", "SHA-1");
        assertRoutineMatchesFlag(output, ir, "UseSHA256Intrinsics",
                "StubRoutines_sha256_implCompress", "SHA-256");
        assertRoutineMatchesFlag(output, ir, "UseSHA512Intrinsics",
                "StubRoutines_sha512_implCompress", "SHA-512");
        assertRoutineMatchesFlag(output, ir, "UseSHA3Intrinsics",
                "StubRoutines_sha3_implCompress", "SHA-3");
    }

    private static List<String> digestLines(OutputAnalyzer output) {
        return output.getOutput().lines()
                .filter(line -> line.startsWith("DIGEST "))
                .toList();
    }

    private static void assertRoutineMatchesFlag(OutputAnalyzer output, String ir,
                                                  String flag, String routine,
                                                  String algorithm) {
        String prefix = "FLAG " + flag + "=";
        String flagLine = output.getOutput().lines()
                .filter(line -> line.startsWith(prefix))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing effective VM flag: " + flag));
        boolean enabled = Boolean.parseBoolean(flagLine.substring(prefix.length()));
        Asserts.assertEquals(enabled, ir.contains(routine),
                algorithm + " direct routine presence must match effective " + flag);
    }

    static class TestWrapper {
        public static void main(String[] args) throws Exception {
            HotSpotDiagnosticMXBean diagnostic = ManagementFactory.getPlatformMXBean(
                    HotSpotDiagnosticMXBean.class);
            for (String flag : INTRINSIC_FLAGS) {
                System.out.println("FLAG " + flag + "=" + diagnostic.getVMOption(flag).getValue());
            }

            check("SHA-1", "da39a3ee5e6b4b0d3255bfef95601890afd80709",
                    "4fd6558b2a93925fb7129447e1d1fac8cff56287");
            check("SHA-256", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                    "4f1757ae4bffbae86d775b831765b75af154d52f7deaa46dd378051a2d3ad57f");
            check("SHA-512", "cf83e1357eefb8bdf1542850d66d8007d620e4050b5715dc83f4a921d36ce9ce47d0d13c5d85f2b0ff8318d2877eec2f63b931bd47417a81a538327af927da3e",
                    "1809db04d02717483e04bc4333a14308bd2d0213ba7bf2c63f11eb1b8a0af8252e67fd104fd466fb95f945539824d8e4183155fa5ced0bee3dad46d9384a0bd5");
            check("SHA3-224", "6b4e03423667dbb73b6e15454f0eb1abd4597f9a1b078e3f5b5a6bc7",
                    "f34ec2c74ce29ecef3b5ff52f89fd5adf62c840cdc2c5ccdb93523f9");
            check("SHA3-256", "a7ffc6f8bf1ed76651c14756a061d662f580ff4de43b49fa82d80a4b80f8434a",
                    "fe8077efdd5ceeabbdc158395484c5d553489b9718fe4a1f28b6821c358f4aca");
            check("SHA3-384", "0c63a75b845e4f7d01107d852e4c2485c51a50aaaa94fc61995e71bbee983a2ac3713831264adb47fb6bd1e058d5f004",
                    "a6f86672297e96e989aa3eff04b0c927be7ac2a072040ac2a9017e329cdb614b6fa7760d0f31ac970e3591d7cf4d90e3");
            check("SHA3-512", "a69f73cca23a9ac5c8b567dc185a756e97c982164fe25859e0d1dcc1475c80a615b2123af1f5f94c11e3e9402c3ac558f500199d95b6d3e301758586281dcd26",
                    "c4148471d4c463929e5af7df25fb4f66875ddf1c473e277e0170665afef3363984e9be12b11949f87a1d00b9e9c330b0fb3f64f31496037ca153f33fc2a5f382");
            System.out.println("TestShaCompress PASSED");
        }

        private static void check(String algorithm, String emptyExpected,
                                  String vectorExpected) throws Exception {
            byte[] empty = digest(algorithm, new byte[0], 0);
            Asserts.assertEquals(emptyExpected, HEX.formatHex(empty), algorithm + " empty digest");

            byte[] vector = new byte[129];
            for (int i = 0; i < vector.length; i++) {
                vector[i] = (byte) (i * 37 + 11);
            }
            byte[] padded = new byte[vector.length + 3];
            System.arraycopy(vector, 0, padded, 3, vector.length);
            byte[] actual = digest(algorithm, padded, 3);
            Asserts.assertEquals(vectorExpected, HEX.formatHex(actual),
                    algorithm + " boundary/non-aligned digest");

            for (int length : TEST_LENGTHS) {
                byte[] data = new byte[length + 2];
                for (int i = 0; i < length; i++) {
                    data[i + 2] = (byte) (i * 37 + 11);
                }
                System.out.println("DIGEST " + algorithm + " " + length + " "
                        + HEX.formatHex(digest(algorithm, data, 2)));
            }
        }

        private static byte[] digest(String algorithm, byte[] data, int offset) throws Exception {
            MessageDigest md = MessageDigest.getInstance(algorithm, "SUN");
            md.update(data, offset, data.length - offset);
            return md.digest();
        }
    }
}
