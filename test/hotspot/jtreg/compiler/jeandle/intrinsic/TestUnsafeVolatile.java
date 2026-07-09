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
 * @summary Test Jeandle intrinsics for Unsafe volatile primitive get/put.
 *          C2 lowers these as MO_SEQ_CST -> LLVM atomic load/store, seq_cst.
 *          Long/Double volatile variants are deferred (not in scope).
 * @modules java.base/jdk.internal.misc
 * @library /test/lib /
 * @build jdk.test.lib.Asserts
 * @run main/othervm compiler.jeandle.intrinsic.TestUnsafeVolatile
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

public class TestUnsafeVolatile {
    private static final String INTRINSIC_LOG_LINE = "is parsed as intrinsic";

    public static void main(String[] args) throws Exception {
        String dumpPath = Files.createTempDirectory("jeandle_test_unsafe_volatile").toString();

        String wrapper = TestWrapper.class.getName();
        ArrayList<String> commandArgs = new ArrayList<>(List.of(
                "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
                "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
                "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dumpPath,
                "-XX:CompileCommand=compileonly," + wrapper + "::getIntVolatileHeap",
                "-XX:CompileCommand=compileonly," + wrapper + "::putIntVolatileHeap",
                "-XX:CompileCommand=compileonly," + wrapper + "::getIntVolatileNative",
                wrapper));

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(commandArgs);
        OutputAnalyzer output = ProcessTools.executeCommand(pb);

        output.shouldHaveExitValue(0);

        int count = countIntrinsicLines(output.getOutput());
        Asserts.assertEQ(count, 3, "Expected 3 intrinsic log lines, got " + count);

        FileCheck getHeap = new FileCheck(dumpPath,
                TestWrapper.class.getMethod("getIntVolatileHeap"), false);
        getHeap.checkPattern("load atomic i32.*seq_cst");

        FileCheck putHeap = new FileCheck(dumpPath,
                TestWrapper.class.getMethod("putIntVolatileHeap"), false);
        putHeap.checkPattern("store atomic i32.*seq_cst");

        FileCheck getNative = new FileCheck(dumpPath,
                TestWrapper.class.getMethod("getIntVolatileNative"), false);
        getNative.checkPattern("load atomic i32.*seq_cst");
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
        static final Holder H = new Holder();
        static {
            try {
                I_OFFSET = U.objectFieldOffset(Holder.class.getDeclaredField("i"));
            } catch (NoSuchFieldException e) {
                throw new RuntimeException(e);
            }
        }

        public static void main(String[] args) {
            U.putIntVolatile(H, I_OFFSET, 0x11223344);
            Asserts.assertEquals(0x11223344, getIntVolatileHeap());
            putIntVolatileHeap();
            Asserts.assertEquals(0x55667788, H.i);
            U.putIntVolatile(null, NATIVE, 0xAABB);
            Asserts.assertEquals(0xAABB, getIntVolatileNative());
            System.out.println("TestUnsafeVolatile PASSED");
        }

        public static int getIntVolatileHeap()   { return U.getIntVolatile(H, I_OFFSET); }
        public static void putIntVolatileHeap()   { U.putIntVolatile(H, I_OFFSET, 0x55667788); }
        public static int getIntVolatileNative()  { return U.getIntVolatile(null, NATIVE); }
    }

    static class Holder { volatile int i; }
}
