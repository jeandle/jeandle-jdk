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
 * @summary Test Jeandle intrinsics for Unsafe opaque primitive get/put.
 *          C2 lowers these as MO_RELAXED -> LLVM atomic load/store, monotonic.
 * @modules java.base/jdk.internal.misc
 * @library /test/lib /
 * @build jdk.test.lib.Asserts
 * @run main/othervm compiler.jeandle.intrinsic.TestUnsafeOpaque
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

public class TestUnsafeOpaque {
    private static final String INTRINSIC_LOG_LINE = "is parsed as intrinsic";

    public static void main(String[] args) throws Exception {
        String dumpPath = Files.createTempDirectory("jeandle_test_unsafe_opaque").toString();

        String wrapper = TestWrapper.class.getName();
        ArrayList<String> commandArgs = new ArrayList<>(List.of(
                "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
                "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
                "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dumpPath,
                "-XX:CompileCommand=compileonly," + wrapper + "::getIntOpaqueHeap",
                "-XX:CompileCommand=compileonly," + wrapper + "::putLongOpaqueHeap",
                "-XX:CompileCommand=compileonly," + wrapper + "::getIntOpaqueNative",
                wrapper));

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(commandArgs);
        OutputAnalyzer output = ProcessTools.executeCommand(pb);

        output.shouldHaveExitValue(0);

        int count = countIntrinsicLines(output.getOutput());
        Asserts.assertEQ(count, 3, "Expected 3 intrinsic log lines, got " + count);

        FileCheck getHeap = new FileCheck(dumpPath,
                TestWrapper.class.getMethod("getIntOpaqueHeap"), false);
        getHeap.checkPattern("load atomic i32.*monotonic");

        FileCheck putHeap = new FileCheck(dumpPath,
                TestWrapper.class.getMethod("putLongOpaqueHeap"), false);
        putHeap.checkPattern("store atomic i64.*monotonic");

        FileCheck getNative = new FileCheck(dumpPath,
                TestWrapper.class.getMethod("getIntOpaqueNative"), false);
        getNative.checkPattern("load atomic i32.*monotonic");
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
        static final long I_OFFSET;
        static final long L_OFFSET;
        static final Holder H = new Holder();
        static {
            try {
                I_OFFSET = U.objectFieldOffset(Holder.class.getDeclaredField("i"));
                L_OFFSET = U.objectFieldOffset(Holder.class.getDeclaredField("l"));
            } catch (NoSuchFieldException e) {
                throw new RuntimeException(e);
            }
        }

        public static void main(String[] args) {
            U.putInt(H, I_OFFSET, 0x21436587);
            Asserts.assertEquals(0x21436587, getIntOpaqueHeap());
            putLongOpaqueHeap();
            Asserts.assertEquals(0x0F0E0D0C0B0A0908L, H.l);
            U.putInt(null, NATIVE, 0xBEEF);
            Asserts.assertEquals(0xBEEF, getIntOpaqueNative());
            System.out.println("TestUnsafeOpaque PASSED");
        }

        public static int getIntOpaqueHeap()   { return U.getIntOpaque(H, I_OFFSET); }
        public static void putLongOpaqueHeap()  { U.putLongOpaque(H, L_OFFSET, 0x0F0E0D0C0B0A0908L); }
        public static int getIntOpaqueNative()  { return U.getIntOpaque(null, NATIVE); }
    }

    static class Holder { int i; long l; }
}
