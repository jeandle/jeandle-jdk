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
 * @summary Use vector stores for short constant array clears without overwriting headers or tails
 * @requires os.arch == "amd64" | os.arch == "x86_64"
 * @library /test/lib /
 * @run main compiler.jeandle.bytecodeTranslate.alloc.TestConstantArrayZeroing
 * @run main compiler.jeandle.bytecodeTranslate.alloc.TestConstantArrayZeroing -XX:UseAVX=0
 * @run main compiler.jeandle.bytecodeTranslate.alloc.TestConstantArrayZeroing -XX:-UseCompressedClassPointers
 * @run main compiler.jeandle.bytecodeTranslate.alloc.TestConstantArrayZeroing -XX:MaxVectorSize=8
 */

package compiler.jeandle.bytecodeTranslate.alloc;

import compiler.jeandle.fileCheck.FileCheck;
import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class TestConstantArrayZeroing {
    public static void main(String[] args) throws Exception {
        String dumpPath = Files.createTempDirectory("constant_array_zeroing").toString();
        var options = new ArrayList<>(List.of(
                "-Xcomp", "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler",
                "-XX:+UseTLAB", "-XX:-ZeroTLAB", "-XX:+UseSerialGC", "-Xms16m", "-Xmx16m",
                "-XX:+JeandleDumpIR", "-XX:JeandleDumpDirectory=" + dumpPath,
                "-XX:CompileCommand=compileonly," + TestMethods.class.getName() + "::test*"));
        options.addAll(Arrays.asList(args));
        options.add(TestMethods.class.getName());
        OutputAnalyzer output = ProcessTools.executeCommand(
                ProcessTools.createLimitedTestJavaProcessBuilder(options));
        output.shouldHaveExitValue(0);

        if (!Arrays.asList(args).contains("-XX:MaxVectorSize=8")) {
            for (String method : List.of("test4", "test8", "testInts")) {
                FileCheck check = new FileCheck(dumpPath, TestMethods.class.getMethod(method), true);
                check.checkPattern("store volatile <[24] x i64> zeroinitializer, ptr addrspace\\(1\\) .*align 8");
                check.checkNot("store volatile i128");
                check.checkNot("@llvm.memset");
                check.checkNot("@zero_heap_words_stub");
            }
        }
    }

    public static class TestMethods {
        public static long[] test0() { return new long[0]; }
        public static long[] test1() { return new long[1]; }
        public static long[] test2() { return new long[2]; }
        public static long[] test3() { return new long[3]; }
        public static long[] test4() { return new long[4]; }
        public static long[] test5() { return new long[5]; }
        public static long[] test6() { return new long[6]; }
        public static long[] test7() { return new long[7]; }
        public static long[] test8() { return new long[8]; }
        public static long[] test9() { return new long[9]; }
        public static int[] testInts() { return new int[16]; }
        public static byte[] testBytes() { return new byte[17]; }
        public static Object[] testObjects() { return new Object[7]; }

        public static void main(String[] args) {
            for (int iteration = 0; iteration < 20_000; iteration++) {
                long[][] arrays = { test0(), test1(), test2(), test3(), test4(),
                                    test5(), test6(), test7(), test8(), test9() };
                for (int length = 0; length < arrays.length; length++) {
                    long[] array = arrays[length];
                    Asserts.assertEquals(array.length, length);
                    for (long value : array) {
                        Asserts.assertEquals(value, 0L);
                    }
                    Arrays.fill(array, -1L);
                }
                int[] ints = testInts();
                Asserts.assertEquals(ints.length, 16);
                for (int value : ints) {
                    Asserts.assertEquals(value, 0);
                }
                Arrays.fill(ints, -1);
                byte[] bytes = testBytes();
                Asserts.assertEquals(bytes.length, 17);
                for (byte value : bytes) {
                    Asserts.assertEquals(value, (byte) 0);
                }
                Arrays.fill(bytes, (byte) -1);
                Object[] objects = testObjects();
                Asserts.assertEquals(objects.length, 7);
                for (Object value : objects) {
                    Asserts.assertNull(value);
                }
                Arrays.fill(objects, objects);
            }
        }
    }
}
