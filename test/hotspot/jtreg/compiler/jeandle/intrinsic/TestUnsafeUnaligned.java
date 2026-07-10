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
 * @summary Test Jeandle intrinsics for Unsafe unaligned primitive get/put.
 *          C2 lowers these as Relaxed (MO_UNORDERED) with an unaligned access;
 *          Jeandle emits an unordered atomic load/store with align 1.
 * @modules java.base/jdk.internal.misc
 * @library /test/lib /
 * @build jdk.test.lib.Asserts
 * @run main/othervm compiler.jeandle.intrinsic.TestUnsafeUnaligned
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

public class TestUnsafeUnaligned {
    private static final String INTRINSIC_LOG_LINE = "is parsed as intrinsic";

    public static void main(String[] args) throws Exception {
        String dumpPath = Files.createTempDirectory("jeandle_test_unsafe_unaligned").toString();

        String wrapper = TestWrapper.class.getName();
        ArrayList<String> commandArgs = new ArrayList<>(List.of(
                "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
                "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
                "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dumpPath,
                "-XX:CompileCommand=compileonly," + wrapper + "::getIntUnalignedHeap",
                "-XX:CompileCommand=compileonly," + wrapper + "::getLongUnalignedHeap",
                "-XX:CompileCommand=compileonly," + wrapper + "::putIntUnalignedHeap",
                wrapper));

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(commandArgs);
        OutputAnalyzer output = ProcessTools.executeCommand(pb);

        output.shouldHaveExitValue(0);

        int count = countIntrinsicLines(output.getOutput());
        Asserts.assertEQ(count, 3, "Expected 3 intrinsic log lines, got " + count);

        // Unaligned access lowers as a non-atomic misaligned load/store (align 1),
        // matching C2's plain misaligned access (an atomic load with align < natural
        // would lower to an __atomic libcall).
        FileCheck getInt = new FileCheck(dumpPath,
                TestWrapper.class.getMethod("getIntUnalignedHeap"), false);
        getInt.checkPattern("load i32.*align 1");

        FileCheck getLong = new FileCheck(dumpPath,
                TestWrapper.class.getMethod("getLongUnalignedHeap"), false);
        getLong.checkPattern("load i64.*align 1");

        FileCheck putInt = new FileCheck(dumpPath,
                TestWrapper.class.getMethod("putIntUnalignedHeap"), false);
        putInt.checkPattern("store i32.*align 1");
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
            U.putInt(H, I_OFFSET, 0x76543210);
            Asserts.assertEquals(0x76543210, getIntUnalignedHeap());
            U.putLong(H, L_OFFSET, 0x1122334455667788L);
            Asserts.assertEquals(0x1122334455667788L, getLongUnalignedHeap());
            putIntUnalignedHeap();
            Asserts.assertEquals(0xCAFED00D, H.i);
            System.out.println("TestUnsafeUnaligned PASSED");
        }

        public static int getIntUnalignedHeap()   { return U.getIntUnaligned(H, I_OFFSET); }
        public static long getLongUnalignedHeap()  { return U.getLongUnaligned(H, L_OFFSET); }
        public static void putIntUnalignedHeap()   { U.putIntUnaligned(H, I_OFFSET, 0xCAFED00D); }
    }

    static class Holder { int i; long l; }
}
