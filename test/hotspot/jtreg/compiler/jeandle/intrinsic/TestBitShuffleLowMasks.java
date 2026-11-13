/*
 * Copyright (c) 2026, the Jeandle-JDK Authors. All Rights Reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License version 2 for more details (a copy of the
 * LICENSE file that accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 */

/*
 * @test
 * @summary Verify low-prefix mask folding and bitshuffle numeric results
 * @requires vm.flavor == "server" & ((os.arch=="amd64" | os.arch=="x86_64") & vm.cpu.features ~= ".*bmi2.*")
 * @library /test/lib /
 * @build jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm compiler.jeandle.intrinsic.TestBitShuffleLowMasks
 */

package compiler.jeandle.intrinsic;

import compiler.jeandle.fileCheck.FileCheck;
import java.nio.file.Files;
import java.util.List;
import java.util.Random;
import jdk.test.lib.Asserts;
import jdk.test.lib.process.ProcessTools;

public class TestBitShuffleLowMasks {
    public static void main(String[] args) throws Exception {
        String dump = Files.createTempDirectory("bitshuffle_low_masks").toString();
        ProcessTools.executeCommand(ProcessTools.createLimitedTestJavaProcessBuilder(
                "-XX:+UnlockDiagnosticVMOptions", "-Xcomp", "-Xbatch", "-Xbootclasspath/a:.", "-XX:+WhiteBoxAPI", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler",
                "-XX:+UnlockDiagnosticVMOptions", "-XX:+JeandleDumpIR", "-Xlog:jeandle=debug",
                "-XX:JeandleDumpDirectory=" + dump,
                "-XX:CompileCommand=compileonly," + Kernel.class.getName() + "::test*",
                Kernel.class.getName())).shouldHaveExitValue(0).shouldContain("low masks passed");
        for (String name : List.of("testCompressI", "testExpandI", "testCompressL", "testExpandL")) {
            FileCheck fc = new FileCheck(dump, Kernel.class.getMethod(name,
                    name.endsWith("I") ? int.class : long.class, int.class), true);
            fc.checkNot("llvm.x86.bmi");
            fc.checkPatternAnywhere("and i(32|64)");
        }
    }

    public static class Kernel {
        static int loadInteger = Integer.bitCount(17);
        static long loadLong = Long.bitCount(17);
        public static int testCompressI(int x, int s) { return Integer.compress(x, -1 >>> s); }
        public static int testExpandI(int x, int s) { return Integer.expand(x, (1 << s) - 1); }
        public static long testCompressL(long x, int s) { return Long.compress(x, -1L >>> s); }
        public static long testExpandL(long x, int s) { return Long.expand(x, (1L << s) - 1); }
        public static boolean testBound(int x, int m) { return Integer.compress(x, 0x55555555) < 65536; }
        public static boolean testNonnegative(int x, int m) { return Integer.expand(x, m & Integer.MAX_VALUE) >= 0; }
        public static int testZero(int m) { return Integer.compress(0, m); }
        public static void main(String[] args) {
            Random r = new Random(20261112);
            int[] counts = {0, 1, 31, 32, 63, 64, -1, Integer.MIN_VALUE, Integer.MAX_VALUE};
            for (int i = 0; i < 20000; i++) {
                int x = r.nextInt(), m = r.nextInt();
                long l = r.nextLong();
                int s = i < counts.length ? counts[i] : r.nextInt();
                Asserts.assertEQ(testCompressI(x, s), x & (-1 >>> s));
                Asserts.assertEQ(testExpandI(x, s), x & ((1 << s) - 1));
                Asserts.assertEQ(testCompressL(l, s), l & (-1L >>> s));
                Asserts.assertEQ(testExpandL(l, s), l & ((1L << s) - 1));
                Asserts.assertTrue(testBound(x, m));
                Asserts.assertTrue(testNonnegative(x, m));
                Asserts.assertEQ(testZero(m), 0);
            }
            for (var method : Kernel.class.getDeclaredMethods()) {
                if (method.getName().startsWith("test")) {
                    var wb = jdk.test.whitebox.WhiteBox.getWhiteBox();
                    Asserts.assertTrue(wb.isMethodCompiled(method), method + " must stay compiled");
                    Asserts.assertEQ(wb.getMethodCompilationLevel(method), 4,
                            method + " must execute with the optimizing compiler");
                }
            }
            System.out.println("low masks passed");
        }
    }
}
