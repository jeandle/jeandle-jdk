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
 * You should have received a copy of the GNU General Public License
 * version 2 along with this work; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 */

/*
 * @test
 * @key randomness
 * @summary Verify guarded and late-constant pow specializations against the selected runtime
 * @requires os.arch == "amd64" | os.arch == "x86_64" | os.arch == "aarch64"
 * @library /test/lib /
 * @run main/othervm compiler.jeandle.intrinsic.TestPowSpecializations
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

public class TestPowSpecializations {
    public static void main(String[] args) throws Exception {
        boolean x86 = System.getProperty("os.arch").equals("amd64")
                || System.getProperty("os.arch").equals("x86_64");
        run(false, x86);
        if (x86) run(true, true);
    }

    private static void run(boolean shared, boolean x86) throws Exception {
        String dump = Files.createTempDirectory("jeandle_pow_specializations").toString();
        ArrayList<String> command = new ArrayList<>(List.of(
                "-Xbatch", "-Xcomp", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler",
                "-XX:+JeandleUseHotspotIntrinsics", "-Xlog:jeandle=debug",
                "-XX:+UnlockDiagnosticVMOptions", "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dump));
        for (String method : List.of("powerOne", "squareRootMerged", "powerMinusOne", "powerVariable")) {
            command.add("-XX:CompileCommand=compileonly," + Kernel.class.getName() + "::" + method);
        }
        if (x86) command.add(shared ? "-XX:-UseLibmIntrinsic" : "-XX:+UseLibmIntrinsic");
        command.add(Kernel.class.getName());
        OutputAnalyzer output = ProcessTools.executeCommand(
                ProcessTools.createLimitedTestJavaProcessBuilder(command));
        output.shouldHaveExitValue(0);
        output.shouldContain("pow specializations bitwise checks passed");
        String runtime = x86 && !shared ? "call double @StubRoutines_dpow"
                : "call double inttoptr";
        FileCheck one = new FileCheck(dump, Kernel.class.getMethod("powerOne", double.class), true);
        one.checkPatternAnywhere("fcmp (ord|uno) double");
        one.checkPatternAnywhere(runtime);
        one.checkPatternAnywhere("phi double");
        FileCheck halfRaw = new FileCheck(dump,
                Kernel.class.getMethod("squareRootMerged", double.class, boolean.class), false);
        halfRaw.checkPatternAnywhere(runtime);
        halfRaw.checkNot("llvm.sqrt");
        FileCheck half = new FileCheck(dump,
                Kernel.class.getMethod("squareRootMerged", double.class, boolean.class), true);
        half.checkPatternAnywhere("call double @llvm.sqrt.f64");
        half.checkPatternAnywhere(runtime);
        FileCheck minus = new FileCheck(dump,
                Kernel.class.getMethod("powerMinusOne", double.class), true);
        minus.checkPatternAnywhere(runtime);
        if (x86 && !shared) {
            minus.checkNot("fdiv double");
        } else {
            minus.checkPatternAnywhere("fdiv double");
        }
        FileCheck variable = new FileCheck(dump,
                Kernel.class.getMethod("powerVariable", double.class, double.class), true);
        variable.checkPatternAnywhere(runtime);
        variable.checkNot("llvm.sqrt");
    }

    public static class Kernel {
        static double initializeMath = Math.abs(1.0);

        public static double powerOne(double x) { return Math.pow(x, 1.0); }
        public static double squareRootMerged(double x, boolean flag) {
            double exponent = flag ? 0.5 : 0.5;
            return Math.pow(x, exponent);
        }
        public static double powerMinusOne(double x) { return Math.pow(x, -1.0); }
        public static double powerVariable(double x, double exponent) { return Math.pow(x, exponent); }

        private static void check(double x, int index) {
            double one = Math.pow(x, 1.0);
            double half = Math.pow(x, 0.5);
            double minus = Math.pow(x, -1.0);
            assertBits("one", x, powerOne(x), one);
            assertBits("half true", x, squareRootMerged(x, true), half);
            assertBits("half false", x, squareRootMerged(x, false), half);
            assertBits("minus one", x, powerMinusOne(x), minus);
            double exponent = index % 2 == 0 ? 1.25 : -0.75;
            assertBits("variable", x, powerVariable(x, exponent), Math.pow(x, exponent));
        }

        private static void assertBits(String name, double x, double actual, double expected) {
            long bits = Double.doubleToRawLongBits(actual);
            long reference = Double.doubleToRawLongBits(expected);
            Asserts.assertTrue(bits == reference, name + " for 0x"
                    + Long.toHexString(Double.doubleToRawLongBits(x)) + ": 0x"
                    + Long.toHexString(bits) + " != 0x" + Long.toHexString(reference));
        }

        public static void main(String[] args) {
            long[] special = {
                0, Long.MIN_VALUE, 1, Long.MIN_VALUE | 1, 0x000fffffffffffffL,
                0x0010000000000000L, 0x3ff0000000000000L, 0xbff0000000000000L,
                0x7fefffffffffffffL, 0xffefffffffffffffL,
                0x7ff0000000000000L, 0xfff0000000000000L,
                0x7ff0000000000001L, 0xfff0000000000001L,
                0x7ff8000000000000L, 0xfff8000000000000L,
                0x7ff123456789abcdL, 0xfff123456789abcdL,
                0x7ffabcdef1234567L, 0xfffabcdef1234567L
            };
            int index = 0;
            for (long bits : special) check(Double.longBitsToDouble(bits), index++);
            long[] significands = {0, 1, 0x7ffffffffffffL, 0xfffffffffffffL};
            for (int exponent = 0; exponent < 2047; exponent++) {
                for (long fraction : significands) {
                    long bits = ((long)exponent << 52) | fraction;
                    check(Double.longBitsToDouble(bits), index++);
                    check(Double.longBitsToDouble(bits | Long.MIN_VALUE), index++);
                }
            }
            Random random = new Random(0x662131L);
            for (int i = 0; i < 20000; i++) check(Double.longBitsToDouble(random.nextLong()), index++);
            System.out.println("pow specializations bitwise checks passed: " + (index * 5));
        }
    }
}
