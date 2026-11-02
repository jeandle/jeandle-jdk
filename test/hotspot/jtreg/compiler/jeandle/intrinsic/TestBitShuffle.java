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
 * @key randomness
 * @summary Test the intrinsic implementation of Integer/Long compress/expand
 * @requires vm.flavor == "server" & ((os.arch=="amd64" | os.arch=="x86_64") & vm.cpu.features ~= ".*bmi2.*")
 * @library /test/lib /
 * @build jdk.test.lib.Asserts
 * @run main/othervm compiler.jeandle.intrinsic.TestBitShuffle
 */

package compiler.jeandle.intrinsic;

import compiler.jeandle.fileCheck.FileCheck;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestBitShuffle {
    public static void main(String[] args) throws Exception {
        // Run 1: intrinsics enabled. The subprocess checks every value
        // against the interpreted reference implementation and its dumped
        // raw IR must contain the BMI2 PEXT/PDEP calls. The all-constant
        // call must be folded by the lowering: no PEXT, a constant return.
        String intrinsicDump = Files.createTempDirectory("jeandle_test_bit_shuffle_on").toString();
        OutputAnalyzer output = runSubprocess(intrinsicDump);
        output.shouldHaveExitValue(0);

        FileCheck intCompressChecker = new FileCheck(intrinsicDump,
                TestWrapper.class.getMethod("int_compress", int.class, int.class), false);
        intCompressChecker.checkPattern("define hotspotcc i32 .*TestBitShuffle.*int_compress.*");
        intCompressChecker.check("call i32 @llvm.x86.bmi.pext.32");

        FileCheck intExpandChecker = new FileCheck(intrinsicDump,
                TestWrapper.class.getMethod("int_expand", int.class, int.class), false);
        intExpandChecker.checkPattern("define hotspotcc i32 .*TestBitShuffle.*int_expand.*");
        intExpandChecker.check("call i32 @llvm.x86.bmi.pdep.32");

        FileCheck longCompressChecker = new FileCheck(intrinsicDump,
                TestWrapper.class.getMethod("long_compress", long.class, long.class), false);
        longCompressChecker.checkPattern("define hotspotcc i64 .*TestBitShuffle.*long_compress.*");
        longCompressChecker.check("call i64 @llvm.x86.bmi.pext.64");

        FileCheck longExpandChecker = new FileCheck(intrinsicDump,
                TestWrapper.class.getMethod("long_expand", long.class, long.class), false);
        longExpandChecker.checkPattern("define hotspotcc i64 .*TestBitShuffle.*long_expand.*");
        longExpandChecker.check("call i64 @llvm.x86.bmi.pdep.64");

        // Integer.compress(0x12345678, 0x0F0F0F0F) == 0x2468 == 9320. The
        // method name deliberately does not extend "int_compress": the
        // FileCheck dump lookup matches file names by method-name prefix.
        FileCheck constChecker = new FileCheck(intrinsicDump,
                TestWrapper.class.getMethod("int_const_compress"), false);
        constChecker.checkPattern("define hotspotcc i32 .*TestBitShuffle.*int_const_compress.*");
        constChecker.checkNot("llvm.x86.bmi.pext");
        constChecker.checkPattern("ret i32 9320");

        output.shouldContain("is parsed as intrinsic")
              .shouldContain("java.lang.Integer.compress")
              .shouldContain("java.lang.Integer.expand")
              .shouldContain("java.lang.Long.compress")
              .shouldContain("java.lang.Long.expand");

        // Run 2: control group with BMI2 disabled (there is no dedicated VM
        // flag gate for these intrinsic IDs). Results must stay correct
        // through the Java fallback and the raw IR must not contain any
        // PEXT/PDEP call.
        String controlDump = Files.createTempDirectory("jeandle_test_bit_shuffle_off").toString();
        output = runSubprocess(controlDump, "-XX:-UseBMI2Instructions");
        output.shouldHaveExitValue(0)
              .shouldNotContain("java.lang.Integer.compress")
              .shouldNotContain("java.lang.Integer.expand")
              .shouldNotContain("java.lang.Long.compress")
              .shouldNotContain("java.lang.Long.expand");

        intCompressChecker = new FileCheck(controlDump,
                TestWrapper.class.getMethod("int_compress", int.class, int.class), false);
        intCompressChecker.checkNot("llvm.x86.bmi.pext");

        intExpandChecker = new FileCheck(controlDump,
                TestWrapper.class.getMethod("int_expand", int.class, int.class), false);
        intExpandChecker.checkNot("llvm.x86.bmi.pdep");

        longCompressChecker = new FileCheck(controlDump,
                TestWrapper.class.getMethod("long_compress", long.class, long.class), false);
        longCompressChecker.checkNot("llvm.x86.bmi.pext");

        longExpandChecker = new FileCheck(controlDump,
                TestWrapper.class.getMethod("long_expand", long.class, long.class), false);
        longExpandChecker.checkNot("llvm.x86.bmi.pdep");
    }

    private static OutputAnalyzer runSubprocess(String dumpDir, String... extraFlags)
            throws Exception {
        // Extra flags must precede the main class name or they become
        // program arguments instead of JVM options.
        ArrayList<String> commandArgs = new ArrayList<>(List.of(
                "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
                "-Xlog:jeandle=debug", "-XX:+UnlockDiagnosticVMOptions", "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dumpDir,
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::int_compress",
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::int_expand",
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::long_compress",
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::long_expand",
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::int_const_compress"));
        commandArgs.addAll(List.of(extraFlags));
        commandArgs.add(TestWrapper.class.getName());

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(commandArgs);
        return ProcessTools.executeCommand(pb);
    }

    static class TestWrapper {
        static int vi = Integer.bitCount(1);   // Force load java.lang.Integer
        static long vl = Long.bitCount(1L);    // Force load java.lang.Long

        // Both the fixed expected value and the interpreted reference are
        // checked: the fixed cases pin the specification value, the
        // reference guards against a wrong-but-matching constant.
        private static void checkCompressI(String what, int v, int m, int expected) {
            Asserts.assertEquals(int_compress(v, m), expected, what);
            Asserts.assertEquals(int_compress(v, m), ref_int_compress(v, m), what + " (vs interpreted)");
        }

        private static void checkExpandI(String what, int v, int m, int expected) {
            Asserts.assertEquals(int_expand(v, m), expected, what);
            Asserts.assertEquals(int_expand(v, m), ref_int_expand(v, m), what + " (vs interpreted)");
        }

        private static void checkCompressL(String what, long v, long m, long expected) {
            Asserts.assertEquals(long_compress(v, m), expected, what);
            Asserts.assertEquals(long_compress(v, m), ref_long_compress(v, m), what + " (vs interpreted)");
        }

        private static void checkExpandL(String what, long v, long m, long expected) {
            Asserts.assertEquals(long_expand(v, m), expected, what);
            Asserts.assertEquals(long_expand(v, m), ref_long_expand(v, m), what + " (vs interpreted)");
        }

        private static void checkPairI(int v, int m) {
            Asserts.assertEquals(int_compress(v, m), ref_int_compress(v, m),
                    "int compress mismatch for (" + v + ", " + m + ")");
            Asserts.assertEquals(int_expand(v, m), ref_int_expand(v, m),
                    "int expand mismatch for (" + v + ", " + m + ")");
        }

        private static void checkPairL(long v, long m) {
            Asserts.assertEquals(long_compress(v, m), ref_long_compress(v, m),
                    "long compress mismatch for (" + v + ", " + m + ")");
            Asserts.assertEquals(long_expand(v, m), ref_long_expand(v, m),
                    "long expand mismatch for (" + v + ", " + m + ")");
        }

        public static void main(String[] args) {
            // --- known vectors from the javadoc-style definition ---
            // 0xFF00FFF0 selects the hex digits at positions 1, 2, 3, 6, 7
            // of 0xCAFEBABE; the selected digits stay in order, contiguous
            // from position 0.
            checkCompressI("compress(0xCAFEBABE, 0xFF00FFF0)", 0xCAFEBABE, 0xFF00FFF0, 0xCABAB);
            checkExpandI("expand(0x0000CABAB, 0xFF00FFF0)", 0x0000CABAB, 0xFF00FFF0, 0xCA00BAB0);
            checkCompressL("compress(0xCAFEBABEL, 0xFF00FFF0L)", 0xCAFEBABEL, 0xFF00FFF0L, 0xCABABL);
            checkExpandL("expand(0x0000CABABL, 0xFF00FFF0L)", 0x0000CABABL, 0xFF00FFF0L, 0xCA00BAB0L);
            // 0x0F0F0F0F keeps each byte's low nibble and packs them.
            checkCompressI("compress(0x12345678, 0x0F0F0F0F)", 0x12345678, 0x0F0F0F0F, 0x2468);

            // --- the all-constant call folds to the same value at runtime ---
            Asserts.assertEquals(0x2468, int_const_compress(),
                    "const compress(0x12345678, 0x0F0F0F0F)");

            int[] intValues = {0, -1, 1, 0x12345678, 0xCAFEBABE, 0x55555555,
                               Integer.MIN_VALUE, Integer.MAX_VALUE};
            int[] intMasks = {0, -1, 1, 0x55555555, 0xAAAAAAAA, 0x0F0F0F0F,
                              0xFF00FFF0, Integer.MIN_VALUE, Integer.MAX_VALUE};

            // --- mask special values, every combination vs interpreted ---
            for (int v : intValues) {
                for (int m : intMasks) {
                    checkPairI(v, m);
                }
            }

            // --- pinned special-value identities ---
            for (int v : intValues) {
                Asserts.assertEquals(0, int_compress(v, 0), "compress(v, 0) -> 0");
                Asserts.assertEquals(v, int_compress(v, -1), "compress(v, -1) -> v");
                Asserts.assertEquals(0, int_expand(v, 0), "expand(v, 0) -> 0");
                Asserts.assertEquals(v, int_expand(v, -1), "expand(v, -1) -> v");
            }
            for (int m : intMasks) {
                Asserts.assertEquals(m, int_expand(-1, m), "expand(-1, m) -> m");
            }

            // --- single-bit masks at every position ---
            for (int n = 0; n < Integer.SIZE; n++) {
                int bit = 1 << n;
                for (int v : intValues) {
                    Asserts.assertEquals((v >>> n) & 1, int_compress(v, bit),
                            "compress(v, 1 << " + n + ") == (v >>> n) & 1");
                    Asserts.assertEquals((v & 1) << n, int_expand(v, bit),
                            "expand(v, 1 << " + n + ") == (v & 1) << n");
                    Asserts.assertEquals(v >>> n, int_compress(v, -1 << n),
                            "compress(v, -1 << " + n + ") == v >>> n");
                }
                Asserts.assertEquals(bit, int_expand(1, bit),
                        "expand(1, 1 << " + n + ") == 1 << n");
            }

            // --- long analogs of the special masks ---
            long[] longValues = {0L, -1L, 1L, 0x0123456789ABCDEFL, 0xCAFEBABEL,
                                 0x5555555555555555L, Long.MIN_VALUE, Long.MAX_VALUE};
            long[] longMasks = {0L, -1L, 1L, 0x5555555555555555L, 0xAAAAAAAAAAAAAAAAL,
                                0x0F0F0F0F0F0F0F0FL, 0xFF00FF00FF00FFF0L,
                                Long.MIN_VALUE, Long.MAX_VALUE};
            for (long v : longValues) {
                for (long m : longMasks) {
                    checkPairL(v, m);
                }
            }
            for (long v : longValues) {
                Asserts.assertEquals(0L, long_compress(v, 0L), "compress(v, 0L) -> 0");
                Asserts.assertEquals(v, long_compress(v, -1L), "compress(v, -1L) -> v");
                Asserts.assertEquals(0L, long_expand(v, 0L), "expand(v, 0L) -> 0");
                Asserts.assertEquals(v, long_expand(v, -1L), "expand(v, -1L) -> v");
            }
            for (long m : longMasks) {
                Asserts.assertEquals(m, long_expand(-1L, m), "expand(-1L, m) -> m");
            }
            for (int n = 0; n < Long.SIZE; n++) {
                long bit = 1L << n;
                for (long v : longValues) {
                    Asserts.assertEquals((v >>> n) & 1, long_compress(v, bit),
                            "compress(v, 1L << " + n + ") == (v >>> n) & 1");
                    Asserts.assertEquals((v & 1) << n, long_expand(v, bit),
                            "expand(v, 1L << " + n + ") == (v & 1) << n");
                    Asserts.assertEquals(v >>> n, long_compress(v, -1L << n),
                            "compress(v, -1L << " + n + ") == v >>> n");
                }
                Asserts.assertEquals(bit, long_expand(1L, bit),
                        "expand(1L, 1L << " + n + ") == 1L << n");
            }

            // --- 1000 random pairs per operation vs interpreted execution ---
            Random random = new Random();
            for (int i = 0; i < 1000; i++) {
                checkPairI(random.nextInt(), random.nextInt());
            }
            for (int i = 0; i < 1000; i++) {
                checkPairL(random.nextLong(), random.nextLong());
            }

            // --- compose: expand then compress is a projection ---
            for (int i = 0; i < 200; i++) {
                int x = random.nextInt();
                int m = random.nextInt();
                Asserts.assertEquals(int_compress(int_expand(x, m), m),
                        x & int_compress(m, m),
                        "compress(expand(x, m), m) == x & compress(m, m)");
                Asserts.assertEquals(int_expand(int_compress(x, m), m), x & m,
                        "expand(compress(x, m), m) == x & m");
            }
            for (int i = 0; i < 200; i++) {
                long x = random.nextLong();
                long m = random.nextLong();
                Asserts.assertEquals(long_compress(long_expand(x, m), m),
                        x & long_compress(m, m),
                        "compress(expand(x, m), m) == x & compress(m, m)");
                Asserts.assertEquals(long_expand(long_compress(x, m), m), x & m,
                        "expand(compress(x, m), m) == x & m");
            }

            System.out.println("TestBitShuffle PASSED");
        }

        public static int int_compress(int i, int mask) {
            return Integer.compress(i, mask);
        }

        public static int int_expand(int i, int mask) {
            return Integer.expand(i, mask);
        }

        public static long long_compress(long i, long mask) {
            return Long.compress(i, mask);
        }

        public static long long_expand(long i, long mask) {
            return Long.expand(i, mask);
        }

        // Two literal constants: the lowering sees two ConstantInt operands
        // and must fold the call instead of emitting PEXT.
        public static int int_const_compress() {
            return Integer.compress(0x12345678, 0x0F0F0F0F);
        }

        // Not listed in CompileCommand, so these stay interpreted and act as
        // the reference implementation.
        public static int ref_int_compress(int i, int mask) {
            return Integer.compress(i, mask);
        }

        public static int ref_int_expand(int i, int mask) {
            return Integer.expand(i, mask);
        }

        public static long ref_long_compress(long i, long mask) {
            return Long.compress(i, mask);
        }

        public static long ref_long_expand(long i, long mask) {
            return Long.expand(i, mask);
        }
    }
}
