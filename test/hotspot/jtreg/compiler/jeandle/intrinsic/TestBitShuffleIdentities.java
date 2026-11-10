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
 * @summary Verify dynamic compress/expand identities and their optimized IR
 * @requires vm.flavor == "server" & ((os.arch=="amd64" | os.arch=="x86_64") & vm.cpu.features ~= ".*bmi2.*")
 * @library /test/lib /
 * @run main/othervm compiler.jeandle.intrinsic.TestBitShuffleIdentities
 */

package compiler.jeandle.intrinsic;

import compiler.jeandle.fileCheck.FileCheck;
import java.nio.file.Files;
import java.util.Random;
import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestBitShuffleIdentities {
    public static void main(String[] args) throws Exception {
        String dump = Files.createTempDirectory("jeandle_bit_shuffle_identities").toString();
        OutputAnalyzer output = ProcessTools.executeCommand(
                ProcessTools.createLimitedTestJavaProcessBuilder(
                        "-Xbatch", "-Xcomp", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler",
                        "-XX:+UnlockDiagnosticVMOptions", "-XX:+JeandleDumpIR",
                        "-XX:JeandleDumpDirectory=" + dump,
                        "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::test*",
                        TestWrapper.class.getName()));
        output.shouldHaveExitValue(0).shouldContain("Bitshuffle identities passed");
        for (String prefix : new String[] {"I", "L"}) {
            boolean isLong = prefix.equals("L");
            int width = isLong ? 64 : 32;
            Class<?> type = isLong ? long.class : int.class;
            for (String operation : new String[] {"Ec", "Ce", "Ones", "Sc", "Sd", "Hc", "Hd"}) {
                String name = "test" + prefix + operation;
                var method = operation.equals("Ones")
                        ? TestWrapper.class.getMethod(name, type)
                        : TestWrapper.class.getMethod(name, type,
                                operation.equals("Ec") || operation.equals("Ce") ? type : int.class);
                FileCheck raw = new FileCheck(dump, method, false);
                raw.check("call i" + width + " @llvm.x86.bmi.");
                FileCheck optimized = new FileCheck(dump, method, true);
                optimized.checkPattern("define hotspotcc .*i" + width + " .*" + name + "\\(");
                optimized.checkNot("call i" + width + " @llvm.x86.bmi.pdep.");
                if (operation.equals("Ce")) {
                    optimized.check("call i" + width + " @llvm.x86.bmi.pext.");
                    optimized.check("and i" + width);
                } else {
                    optimized.checkNot("call i" + width + " @llvm.x86.bmi.pext.");
                    switch (operation) {
                        case "Ec" -> optimized.check("and i" + width);
                        case "Sc", "Hc" -> optimized.check("lshr i" + width);
                        case "Sd", "Hd" -> optimized.checkPattern("shl(?: nuw| nsw)* i" + width);
                        case "Ones" -> optimized.check("ret i" + width);
                        default -> throw new AssertionError(operation);
                    }
                }
            }
        }
    }

    public static class TestWrapper {
        static int loadInteger = Integer.bitCount(1);
        static long loadLong = Long.bitCount(1L);

        // These bit-by-bit oracles do not use compress/expand or the
        // algebraic identities being optimized, and stay interpreted.
        private static long compress(long x, long mask, int width) {
            long result = 0;
            int target = 0;
            for (int bit = 0; bit < width; bit++) {
                if (((mask >>> bit) & 1) != 0) {
                    result |= ((x >>> bit) & 1) << target;
                    target++;
                }
            }
            return result;
        }

        private static long expand(long x, long mask, int width) {
            long result = 0;
            int source = 0;
            for (int bit = 0; bit < width; bit++) {
                if (((mask >>> bit) & 1) != 0) {
                    result |= ((x >>> source) & 1) << bit;
                    source++;
                }
            }
            return result;
        }

        private static void check(int x, int mask, long y, long longMask, int shift) {
            Asserts.assertEquals(testIEc(x, mask), (int) expand(compress(x, mask, 32), mask, 32));
            Asserts.assertEquals(testICe(x, mask), (int) compress(expand(x, mask, 32), mask, 32));
            Asserts.assertEquals(testIOnes(mask), (int) expand(-1, mask, 32));
            Asserts.assertEquals(testISc(x, shift), (int) compress(x, 1 << shift, 32));
            Asserts.assertEquals(testISd(x, shift), (int) expand(x, 1 << shift, 32));
            Asserts.assertEquals(testIHc(x, shift), (int) compress(x, -1 << shift, 32));
            Asserts.assertEquals(testIHd(x, shift), (int) expand(x, -1 << shift, 32));
            Asserts.assertEquals(testLEc(y, longMask), expand(compress(y, longMask, 64), longMask, 64));
            Asserts.assertEquals(testLCe(y, longMask), compress(expand(y, longMask, 64), longMask, 64));
            Asserts.assertEquals(testLOnes(longMask), expand(-1L, longMask, 64));
            Asserts.assertEquals(testLSc(y, shift), compress(y, 1L << shift, 64));
            Asserts.assertEquals(testLSd(y, shift), expand(y, 1L << shift, 64));
            Asserts.assertEquals(testLHc(y, shift), compress(y, -1L << shift, 64));
            Asserts.assertEquals(testLHd(y, shift), expand(y, -1L << shift, 64));
        }

        public static void main(String[] args) {
            int[] values = {0, -1, 1, Integer.MIN_VALUE, Integer.MAX_VALUE, 0x55555555, 0xaaaaaaaa};
            long[] longValues = {0, -1, 1, Long.MIN_VALUE, Long.MAX_VALUE,
                                 0x5555555555555555L, 0xaaaaaaaaaaaaaaaaL};
            int[] shifts = {-1, 0, 1, 31, 32, 33, 63, 64, 65, Integer.MIN_VALUE, Integer.MAX_VALUE};
            for (int i = 0; i < values.length; i++) {
                for (int m = 0; m < values.length; m++) {
                    for (int shift : shifts) {
                        check(values[i], values[m], longValues[i], longValues[m], shift);
                    }
                }
            }
            for (int bit = 0; bit < Long.SIZE; bit++) {
                check(-1, 1 << bit, -1L, 1L << bit, bit);
                check(Integer.MIN_VALUE, -1 << bit, Long.MIN_VALUE, -1L << bit, bit);
            }
            Random random = new Random(0x1d3a71eL);
            for (int i = 0; i < 4096; i++) {
                check(random.nextInt(), random.nextInt(), random.nextLong(),
                        random.nextLong(), random.nextInt());
            }
            System.out.println("Bitshuffle identities passed");
        }

        public static int testIEc(int x, int m) {
            return Integer.expand(Integer.compress(x, m), m);
        }

        public static int testICe(int x, int m) {
            return Integer.compress(Integer.expand(x, m), m);
        }

        public static int testIOnes(int m) {
            return Integer.expand(-1, m);
        }

        public static int testISc(int x, int n) {
            return Integer.compress(x, 1 << n);
        }

        public static int testISd(int x, int n) {
            return Integer.expand(x, 1 << n);
        }

        public static int testIHc(int x, int n) {
            return Integer.compress(x, -1 << n);
        }

        public static int testIHd(int x, int n) {
            return Integer.expand(x, -1 << n);
        }

        public static long testLEc(long x, long m) {
            return Long.expand(Long.compress(x, m), m);
        }

        public static long testLCe(long x, long m) {
            return Long.compress(Long.expand(x, m), m);
        }

        public static long testLOnes(long m) {
            return Long.expand(-1L, m);
        }

        public static long testLSc(long x, int n) {
            return Long.compress(x, 1L << n);
        }

        public static long testLSd(long x, int n) {
            return Long.expand(x, 1L << n);
        }

        public static long testLHc(long x, int n) {
            return Long.compress(x, -1L << n);
        }

        public static long testLHd(long x, int n) {
            return Long.expand(x, -1L << n);
        }
    }
}
