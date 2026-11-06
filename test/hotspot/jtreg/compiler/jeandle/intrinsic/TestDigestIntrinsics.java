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
 * @summary Test Jeandle lowering of MD5 single-block and DigestBase
 *          multi-block compression intrinsics, including the mixed-algorithm
 *          guard/fallback path.
 * @requires os.arch == "amd64" | os.arch == "x86_64" | os.arch == "aarch64"
 * @library /test/lib /
 * @build jdk.test.lib.Asserts
 * @run main/othervm compiler.jeandle.intrinsic.TestDigestIntrinsics
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
import jdk.test.lib.Platform;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestDigestIntrinsics {
    private static final HexFormat HEX = HexFormat.of();
    private static final String[] INTRINSIC_FLAGS = {
            "UseMD5Intrinsics", "UseSHA1Intrinsics", "UseSHA256Intrinsics",
            "UseSHA512Intrinsics", "UseSHA3Intrinsics"
    };
    // Multi-block lengths: every algorithm must take the
    // implCompressMultiBlock0 path (len >= blockSize) several times over.
    private static final int[] TEST_LENGTHS = {
            64, 65, 128, 200, 1000, 4096
    };

    public static void main(String[] args) throws Exception {
        OutputAnalyzer reference = ProcessTools.executeCommand(
                ProcessTools.createLimitedTestJavaProcessBuilder(
                        "-XX:+UnlockDiagnosticVMOptions",
                        "-XX:-UseMD5Intrinsics", "-XX:-UseSHA1Intrinsics",
                        "-XX:-UseSHA256Intrinsics", "-XX:-UseSHA512Intrinsics",
                        "-XX:-UseSHA3Intrinsics",
                        TestWrapper.class.getName()));
        reference.shouldHaveExitValue(0).shouldContain("TestDigestIntrinsics PASSED");

        Path dumpPath = Files.createTempDirectory("jeandle_digest_intrinsics");
        List<String> commandArgs = new java.util.ArrayList<>(List.of(
                "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
                "-XX:+UnlockDiagnosticVMOptions", "-XX:+UseMD5Intrinsics",
                "-XX:+UseSHA1Intrinsics", "-XX:+UseSHA256Intrinsics",
                "-XX:+UseSHA512Intrinsics", "-XX:+UseSHA3Intrinsics",
                "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dumpPath,
                "-XX:CompileCommand=compileonly,sun/security/provider/DigestBase.implCompressMultiBlock",
                "-XX:CompileCommand=compileonly,sun/security/provider/DigestBase.implCompressMultiBlock0",
                "-XX:CompileCommand=compileonly,sun/security/provider/MD5.implCompress*",
                "-XX:CompileCommand=compileonly,sun/security/provider/SHA.implCompress*",
                "-XX:CompileCommand=compileonly,sun/security/provider/SHA2.implCompress*",
                "-XX:CompileCommand=compileonly,sun/security/provider/SHA5.implCompress*",
                "-XX:CompileCommand=compileonly,sun/security/provider/SHA3.implCompress*",
                TestWrapper.class.getName()));

        OutputAnalyzer output = ProcessTools.executeCommand(
                ProcessTools.createLimitedTestJavaProcessBuilder(commandArgs));
        output.shouldHaveExitValue(0).shouldContain("TestDigestIntrinsics PASSED");
        List<String> referenceDigests = digestLines(reference);
        Asserts.assertTrue(!referenceDigests.isEmpty(), "no reference digests collected");
        Asserts.assertEquals(referenceDigests, digestLines(output),
                "digests differ from the all-intrinsics-disabled reference run");

        String ir;
        try (var files = Files.walk(dumpPath)) {
            ir = files
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
        }
        // Single-block MD5 routine presence must match the effective flag.
        // The call form with the open paren is not a prefix of the
        // multi-block routine name, so this assertion cannot be satisfied
        // by the multi-block path.
        assertRoutineMatchesFlag(output, ir, "UseMD5Intrinsics",
                "StubRoutines_md5_implCompress(", "MD5 single-block");
        // Multi-block routines are gated per algorithm: the flag disables the
        // branch; a missing platform stub (x86 never generates sha3 MB)
        // disables it as well. On x86 UseSHA3Intrinsics is force-disabled,
        // which keeps the flag/presence equality true on every arch.
        assertRoutineMatchesFlag(output, ir, "UseMD5Intrinsics",
                "StubRoutines_md5_implCompressMB", "MD5 multi-block");
        assertRoutineMatchesFlag(output, ir, "UseSHA1Intrinsics",
                "StubRoutines_sha1_implCompressMB", "SHA-1 multi-block");
        assertRoutineMatchesFlag(output, ir, "UseSHA256Intrinsics",
                "StubRoutines_sha256_implCompressMB", "SHA-256 multi-block");
        assertRoutineMatchesFlag(output, ir, "UseSHA512Intrinsics",
                "StubRoutines_sha512_implCompressMB", "SHA-512 multi-block");
        assertRoutineMatchesFlag(output, ir, "UseSHA3Intrinsics",
                "StubRoutines_sha3_implCompressMB", "SHA-3 multi-block");

        // Match the integer widths consumed by each assembler entry. x86 MD5
        // saves ofs/limit with movl; other digest entries read full registers.
        var declarations = java.util.regex.Pattern.compile(
                "(?m)^declare [^\\n]*@(StubRoutines_(?:md5|sha)[^(]*)\\(([^\\n]*)\\)")
                .matcher(ir);
        while (declarations.find()) {
            String routine = declarations.group(1);
            String[] parameters = declarations.group(2).split(", ");
            boolean multiBlock = routine.endsWith("implCompressMB");
            boolean sha3 = routine.startsWith("StubRoutines_sha3_");
            int integerCount = (multiBlock ? 2 : 0) + (sha3 ? 1 : 0);
            Asserts.assertEquals(parameters.length, 2 + integerCount,
                    "unexpected digest stub arity: " + declarations.group());
            String integerType = Platform.isX64()
                    && routine.equals("StubRoutines_md5_implCompressMB") ? "i32" : "i64";
            for (int i = 2; i < parameters.length; i++) {
                Asserts.assertEquals(parameters[i], integerType,
                        "unexpected digest stub argument width: " + declarations.group());
            }
        }

        // Whenever a multi-block branch was lowered, its receiver-type guard
        // must carry C2-style PROB_MIN miss weights: C2 marks every
        // inline_digestBase_implCompressMB_predicate failure edge PROB_MIN
        // (1e-6), which is 999999 : 1 in LLVM integer weights.
        if (ir.contains("implCompressMB(")) {
            Asserts.assertTrue(
                    ir.contains("!{!\"branch_weights\", i32 999999, i32 1}"),
                    "digest MB guard branches must carry PROB_MIN miss weights");
        }
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

            // Ground-truth single-block vectors. Every algorithm is probed
            // here so all provider classes are loaded before the first
            // implCompressMultiBlock compilation below; the multi-block
            // lowering closes a branch whose receiver class is not yet
            // loaded (mirroring C2's predicate klass check).
            check("MD5", "abc",
                    "900150983cd24fb0d6963f7d28e17f72");
            check("SHA-1", "abc",
                    "a9993e364706816aba3e25717850c26c9cd0d89d");
            check("SHA-256", "abc",
                    "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
            check("SHA-512", "abc",
                    "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a"
                  + "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f");
            check("SHA3-256", "abc",
                    "3a985da74fe225b2045c172d6bd390bd855f086e3e9d525b46bfe24511431532");

            // Multi-block digests at non-zero array offsets; results are
            // compared against the intrinsics-disabled reference via the
            // DIGEST lines.
            for (String algorithm : new String[]{
                    "MD5", "SHA-1", "SHA-256", "SHA-512", "SHA3-256"}) {
                for (int length : TEST_LENGTHS) {
                    byte[] data = new byte[length + 3];
                    for (int i = 0; i < length; i++) {
                        data[i + 3] = (byte) (i * 37 + 11);
                    }
                    System.out.println("DIGEST " + algorithm + " " + length + " "
                            + HEX.formatHex(digest(algorithm, data, 3)));
                }
            }

            // Mixed-algorithm call site: the same compiled
            // DigestBase.implCompressMultiBlock sees both MD5 and SHA-256
            // receivers, so both instanceof guards select their own stub in
            // one compiled body (the SHA-256 receiver traverses the guard
            // chain past the md5 guard). The Java fallback path itself is
            // pinned by the SHA3-256 rows above: on x86 the sha3 branch is
            // compile-time-closed, every guard misses and the fallback runs;
            // its correctness is covered by the DIGEST comparison against
            // the intrinsics-disabled reference.
            MessageDigest md5 = MessageDigest.getInstance("MD5", "SUN");
            MessageDigest sha = MessageDigest.getInstance("SHA-256", "SUN");
            byte[] data = new byte[300];
            for (int i = 0; i < data.length; i++) {
                data[i] = (byte) (i * 29 + 7);
            }
            for (int i = 0; i < 20; i++) {
                System.out.println("DIGEST MIXED " + i + " md5 "
                        + HEX.formatHex(updateFresh(md5, data)));
                System.out.println("DIGEST MIXED " + i + " sha "
                        + HEX.formatHex(updateFresh(sha, data)));
            }

            System.out.println("TestDigestIntrinsics PASSED");
        }

        private static byte[] updateFresh(MessageDigest md, byte[] data) {
            md.reset();
            md.update(data, 0, data.length);
            return md.digest();
        }

        private static void check(String algorithm, String input, String expected)
                throws Exception {
            byte[] actual = digest(algorithm, input.getBytes("ASCII"), 0);
            Asserts.assertEquals(expected, HEX.formatHex(actual), algorithm + " digest");
        }

        private static byte[] digest(String algorithm, byte[] data, int offset)
                throws Exception {
            MessageDigest md = MessageDigest.getInstance(algorithm, "SUN");
            md.update(data, offset, data.length - offset);
            return md.digest();
        }
    }
}
