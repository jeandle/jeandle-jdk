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
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 */

/*
 * @test TestUnsafeAccess
 * @summary has_unsafe_access must be set so the signal handler converts
 *          SIGBUS from a truncated mmap'd file access into InternalError
 *          rather than crashing the VM.
 * @library /test/lib
 * @modules java.base/jdk.internal.misc
 * @run driver compiler.jeandle.TestUnsafeAccess
 */

package compiler.jeandle;

import jdk.internal.misc.Unsafe;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;

public class TestUnsafeAccess {

    static final Unsafe UNSAFE = Unsafe.getUnsafe();

    // Compiled by Jeandle. Reads from a raw native address.
    // If has_unsafe_access is set correctly, a SIGBUS from a
    // truncated mmap region is converted to NullPointerException.
    static int unsafeRead(long addr) {
        return UNSAFE.getInt(addr);
    }

    // Valid round-trip through allocated native memory.
    static int validRoundTrip() {
        long addr = UNSAFE.allocateMemory(8);
        try {
            UNSAFE.putInt(addr, 0xCAFE);
            return UNSAFE.getInt(addr);
        } finally {
            UNSAFE.freeMemory(addr);
        }
    }
    static final String CLASS = TestUnsafeAccess.class.getName();



    static final List<String> JEANDLE_FLAGS = List.of(
        "-Xcomp",
        "-XX:-TieredCompilation",
        "-XX:-BackgroundCompilation",
        "-XX:+UseJeandleCompiler",
        "-Xlog:jit+compilation=debug",
        "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
        "--add-opens=java.base/java.nio=ALL-UNNAMED",
        "-XX:CompileCommand=compileonly," + CLASS + "::unsafeRead",
        "-XX:CompileCommand=compileonly," + CLASS + "::validRoundTrip"
    );

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            runDriver();
            return;
        }

        switch (args[0]) {
            case "testValid" -> {
                int result = validRoundTrip();
                if (result != 0xCAFE) {
                    throw new RuntimeException("Expected 0xCAFE, got: " + Integer.toHexString(result));
                }
                System.out.println("Valid round-trip OK: " + Integer.toHexString(result));
            }
            case "testSIGBUS" -> {
                // Map a file into memory, then truncate it to zero bytes.
                // Reading from the mapped region now has no backing storage:
                // this is the canonical way to induce SIGBUS on Linux x86.
                Path tmp = Files.createTempFile("jeandle-unsafe-", ".bin");
                try {
                    // Write one page so mmap has something to map initially
                    byte[] page = new byte[4096];
                    Files.write(tmp, page);

                    java.nio.MappedByteBuffer mbb;
                    try (RandomAccessFile raf = new RandomAccessFile(tmp.toFile(), "rw");
                         FileChannel ch = raf.getChannel()) {
                        mbb = ch.map(FileChannel.MapMode.READ_WRITE, 0, 4096);
                    }
                    Field addressField = java.nio.Buffer.class.getDeclaredField("address");
                    addressField.setAccessible(true);
                    long addr = (long) addressField.get(mbb);

                    // Truncate file to 0 — the mapped page now has no backing storage
                    try (RandomAccessFile raf = new RandomAccessFile(tmp.toFile(), "rw")) {
                        raf.setLength(0);
                    }

                    // Reading from addr now triggers SIGBUS.
                    // With has_unsafe_access=true the signal handler converts it to NPE.

                    try {
                        int v = unsafeRead(addr);
                        throw new RuntimeException("Expected InternalError, got: " + v);
                    } catch (InternalError e) {
                        System.out.println("SIGBUS correctly converted to InternalError: " + e.getMessage());
                    }
                } finally {
                    Files.deleteIfExists(tmp);
                }
            }
        }
    }

    static void runDriver() throws Exception {
        // 1. Valid access
        OutputAnalyzer out = run("testValid");
        out.shouldHaveExitValue(0);
        out.shouldContain("compiler.jeandle.TestUnsafeAccess::validRoundTrip");
        out.shouldContain("Valid round-trip OK");

        // 2. SIGBUS from truncated mmap — VM must survive and throw NPE
        out = run("testSIGBUS");
        out.shouldContain("compiler.jeandle.TestUnsafeAccess::unsafeRead");  // confirm compiled
        out.shouldHaveExitValue(0);  // VM survived
        out.shouldContain("SIGBUS correctly converted to InternalError");
    }

    static OutputAnalyzer run(String testCase) throws Exception {
        List<String> cmd = new ArrayList<>(JEANDLE_FLAGS);
        cmd.add(CLASS);
        cmd.add(testCase);
        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(cmd);
        return ProcessTools.executeProcess(pb);
    }
}
