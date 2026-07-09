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
 * @summary Test the intrinsic implementation of Unsafe.writeback0,
 *          writebackPreSync0, and writebackPostSync0
 * @requires os.arch=="amd64" | os.arch=="x86_64" | os.arch=="aarch64"
 * @library /test/lib /
 * @modules java.base/jdk.internal.misc
 * @build jdk.test.lib.Asserts
 * @run main/othervm compiler.jeandle.intrinsic.TestWriteback0
 */

package compiler.jeandle.intrinsic;

import compiler.jeandle.fileCheck.FileCheck;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import jdk.internal.misc.Unsafe;
import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestWriteback0 {
    // Specific intrinsic patterns for precise validation
    private static final String[] INTRINSIC_PATTERNS = {
        "Unsafe.writeback0.*is parsed as intrinsic",
        "Unsafe.writebackPreSync0.*is parsed as intrinsic",
        "Unsafe.writebackPostSync0.*is parsed as intrinsic"
    };

    public static void main(String[] args) throws Exception {
        testJeandleIntrinsic();
        testFallbackToJNI();
    }

    // Test Jeandle intrinsic implementation
    // Directly compile Unsafe.writebackMemory to avoid relying on inlining
    private static void testJeandleIntrinsic() throws Exception {
        String dumpPath = Files.createTempDirectory("jeandle_test_writeback0").toString();

        ArrayList<String> commandArgs = new ArrayList<>(List.of(
                "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
                "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler",
                "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dumpPath,
                // Compile writebackMemory directly, not testWriteback (which needs inlining)
                "-XX:CompileCommand=compileonly,jdk.internal.misc.Unsafe::writebackMemory",
                TestWrapper.class.getName()));

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(commandArgs);
        OutputAnalyzer output = ProcessTools.executeCommand(pb);

        output.shouldHaveExitValue(0);

        // Verify each intrinsic is parsed
        for (String pattern : INTRINSIC_PATTERNS) {
            boolean found = Pattern.compile(pattern)
                .matcher(output.getOutput())
                .find();
            Asserts.assertTrue(found, "Missing intrinsic: " + pattern);
        }

        // Verify LLVM IR contains platform-specific intrinsics.
        // writebackMemory is compiled directly, so intrinsics are lowered even in
        // non-optimized IR (no inlining required).
        String arch = System.getProperty("os.arch");
        FileCheck checker = new FileCheck(dumpPath,
                Unsafe.class.getMethod("writebackMemory", long.class, long.class), false);
        if (arch.equals("amd64") || arch.equals("x86_64")) {
            checker.checkPattern("@llvm\\.x86\\.clwb");
            checker.checkPattern("@llvm\\.x86\\.sse\\.sfence");
        } else if (arch.equals("aarch64")) {
            checker.checkPattern("dc cvap");
            checker.checkPattern("@llvm\\.aarch64\\.dmb");
        }
    }

    // Test fallback when intrinsic is not supported
    private static void testFallbackToJNI() throws Exception {
        ArrayList<String> commandArgs = new ArrayList<>(List.of(
                "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
                "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::testWriteback",
                // Force disable intrinsic to simulate unsupported platform
                "-XX:CompileCommand=option," + TestWrapper.class.getName() + "::testWriteback,DisableIntrinsic,_writeback0",
                TestWrapper.class.getName()));

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(commandArgs);
        OutputAnalyzer output = ProcessTools.executeCommand(pb);

        output.shouldHaveExitValue(0);
        output.shouldContain("TestWriteback0 PASSED");
    }

    static class TestWrapper {
        private static final Unsafe U = Unsafe.getUnsafe();

        public static void main(String[] args) throws Exception {
            // Check if writeback is supported on this platform
            if (U.dataCacheLineFlushSize() == 0) {
                System.out.println("writeback not supported, skipping");
                return;
            }

            int cacheLineSize = U.dataCacheLineFlushSize();
            long addr = U.allocateMemory(1024);

            try {
                // Test 1: Zero length (should be no-op)
                testWriteback(addr, 0);

                // Test 2: Single cache line
                testWriteback(addr, cacheLineSize);

                // Test 3: Multiple cache lines
                testWriteback(addr, cacheLineSize * 4);

                // Test 4: Partial cache line (implementation should handle this)
                testWriteback(addr, cacheLineSize / 2);

                // Test 5: Offset within a page (non-cache-line-aligned)
                long offsetAddr = addr + 123;
                testWriteback(offsetAddr, cacheLineSize);

                // Test 6: Large range covering many cache lines
                testWriteback(addr, 1024);

                // Warm up to trigger compilation
                for (int i = 0; i < 20_000; i++) {
                    testWriteback(addr, cacheLineSize);
                }

                System.out.println("TestWriteback0 PASSED");
            } finally {
                U.freeMemory(addr);
            }
        }

        // writeback0, writebackPreSync0, and writebackPostSync0 are all private
        // on Unsafe; call writebackMemory which internally calls all three.
        public static void testWriteback(long address, long length) {
            U.writebackMemory(address, length);
        }
    }
}
