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
 * version 2 more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 */

/*
 * @test
 * @summary Test Jeandle intrinsics for Unsafe plain (Relaxed) primitive get/put.
 *          C2 lowers these as MO_UNORDERED -> LLVM atomic load/store, unordered.
 * @modules java.base/jdk.internal.misc
 * @library /test/lib /
 * @build jdk.test.lib.Asserts
 * @run main/othervm compiler.jeandle.intrinsic.TestUnsafePrimitiveGetPut
 */

package compiler.jeandle.intrinsic;

import compiler.jeandle.fileCheck.FileCheck;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import jdk.internal.misc.Unsafe;
import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestUnsafePrimitiveGetPut {
    private static final String INTRINSIC_LOG_LINE = "is parsed as intrinsic";

    public static void main(String[] args) throws Exception {
        String dumpPath = Files.createTempDirectory("jeandle_test_unsafe_plain").toString();

        String wrapper = TestWrapper.class.getName();
        ArrayList<String> commandArgs = new ArrayList<>(List.of(
                "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
                "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
                "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dumpPath,
                "-XX:CompileCommand=compileonly," + wrapper + "::getIntHeap",
                "-XX:CompileCommand=compileonly," + wrapper + "::getLongHeap",
                "-XX:CompileCommand=compileonly," + wrapper + "::putIntHeap",
                "-XX:CompileCommand=compileonly," + wrapper + "::getIntNative",
                wrapper));

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(commandArgs);
        OutputAnalyzer output = ProcessTools.executeCommand(pb);

        output.shouldHaveExitValue(0);

        // 4 distinct intrinsic call sites, each intrinsified once.
        int count = countIntrinsicLines(output.getOutput());
        Asserts.assertEQ(count, 4, "Expected 4 intrinsic log lines, got " + count);

        // Non-constant heap base -> GEP + unordered load.
        FileCheck getIntHeap = new FileCheck(dumpPath,
                TestWrapper.class.getMethod("getIntHeap"), false);
        getIntHeap.checkPattern("load atomic i32.*unordered");

        FileCheck getLongHeap = new FileCheck(dumpPath,
                TestWrapper.class.getMethod("getLongHeap"), false);
        getLongHeap.checkPattern("load atomic i64.*unordered");

        FileCheck putIntHeap = new FileCheck(dumpPath,
                TestWrapper.class.getMethod("putIntHeap"), false);
        putIntHeap.checkPattern("store atomic i32.*unordered");

        // Constant-null base -> native path: inttoptr + load, no branch.
        FileCheck getIntNative = new FileCheck(dumpPath,
                TestWrapper.class.getMethod("getIntNative"), false);
        getIntNative.checkPattern("inttoptr");
    }

    private static int countIntrinsicLines(String output) {
        int n = 0;
        for (String line : output.split("\n")) {
            if (line.contains(INTRINSIC_LOG_LINE)) {
                n++;
            }
        }
        return n;
    }

    static class TestWrapper {
        static final Unsafe U = Unsafe.getUnsafe();
        static final long NATIVE = U.allocateMemory(64);
        static final long I_OFFSET = fieldOffset("i");
        static final long L_OFFSET = fieldOffset("l");
        static final Holder H = new Holder();

        static long fieldOffset(String name) {
            try {
                return U.objectFieldOffset(Holder.class.getDeclaredField(name));
            } catch (NoSuchFieldException e) {
                throw new RuntimeException(e);
            }
        }

        public static void main(String[] args) {
            // Correctness: on-heap field access.
            U.putInt(H, I_OFFSET, 0x12345678);
            Asserts.assertEquals(0x12345678, getIntHeap());
            Asserts.assertEquals(0x12345678, H.i);
            putIntHeap();
            Asserts.assertEquals(99, H.i);
            U.putLong(H, L_OFFSET, 0x123456789ABCDEF0L);
            Asserts.assertEquals(0x123456789ABCDEF0L, getLongHeap());
            // Correctness: native memory access (null base).
            U.putInt(null, NATIVE, 0xCAFE);
            Asserts.assertEquals(0xCAFE, getIntNative());
            System.out.println("TestUnsafePrimitiveGetPut PASSED");
        }

        public static int getIntHeap()    { return U.getInt(H, I_OFFSET); }
        public static long getLongHeap()  { return U.getLong(H, L_OFFSET); }
        public static void putIntHeap()   { U.putInt(H, I_OFFSET, 99); }
        public static int getIntNative()  { return U.getInt(null, NATIVE); }
    }

    static class Holder { int i; long l; }
}
