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

/**
 * @test
 * @summary Const sections aligned beyond the word size stay on the exact path.
 *
 *          0.94% of the riscv64 micro methods (8 methods, among
 *          them mixedPool and switchPlusPool below) refused by the const planner
 *          with AlignmentBeyondBase: the planner was told the consts base is only
 *          word aligned, so every 16-byte aligned constant pool fell back to the
 *          48 KiB legacy reservation (2 stubs expansions, 69 locs reallocations).
 *          The base is in fact aligned to the plan's maximum alignment both in the
 *          CodeBuffer (initialize_section_size() aligns the start address) and in
 *          the nmethod (consts sit at content offset 0, which is CodeEntryAlignment
 *          aligned). This test pins that: no planner failure, no legacy method, no
 *          expansion, and the compiled methods compute the same values as the
 *          interpreter.
 *
 * @requires vm.debug == true
 * @library /test/lib
 * @run driver compiler.jeandle.TestCodeBufferConstBaseAlignment
 */

package compiler.jeandle;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestCodeBufferConstBaseAlignment {

    public static class Workload {
        static final double[] DOUBLE_TABLE = {1.0001, 2.0002, 3.0003, 4.0004, 5.0005, 6.0006, 7.0007, 8.0008};
        static final float[] FLOAT_TABLE = {1.5f, 2.5f, 3.5f, 4.5f, 5.5f, 6.5f, 7.5f, 8.5f};

        public static double switchPlusPool(double v, int op) {
            double base = v * 1.4142135623730951 + 2.718281828459045;
            switch (op) {
                case 0:  return base + 3.141592653589793;
                case 1:  return base - 1.7320508075688772;
                case 2:  return base + 0.5772156649015329;
                case 3:  return base - 0.6931471805599453;
                case 4:  return base + 1.618033988749895;
                case 5:  return base - 0.3010299956639812;
                case 6:  return base + 2.302585092994046;
                case 7:  return base - 1.2020569031595943;
                default: return base * 0.5;
            }
        }

        public static double poolOnly(double v) {
            return v * 3.141592653589793 + 2.718281828459045 - 1.4142135623730951
                 + 1.7320508075688772 - 0.5772156649015329 + 0.6931471805599453;
        }

        public static double mixedPool(double v) {
            double d = v * 3.141592653589793 + 2.718281828459045 - 1.4142135623730951;
            float f = (float) (v * 1.25f + 0.75f - 2.5f);
            return d + f;
        }

        public static double switchWithArray(int op, int i) {
            int k = i & 7;
            switch (op) {
                case 0:  return DOUBLE_TABLE[k];
                case 1:  return FLOAT_TABLE[k];
                case 2:  return DOUBLE_TABLE[k] + FLOAT_TABLE[k];
                case 3:  return FLOAT_TABLE[k] * 2.0;
                case 4:  return DOUBLE_TABLE[k] / 2.0;
                case 5:  return DOUBLE_TABLE[k] - FLOAT_TABLE[k];
                case 6:  return DOUBLE_TABLE[k] * 3.0;
                case 7:  return FLOAT_TABLE[k] + 0.25;
                default: return 0.0;
            }
        }

        public static double run() {
            double acc = 0.0;
            for (int i = 0; i < 20000; i++) {
                acc += switchPlusPool(i * 0.001, i % 9);
                acc += poolOnly(i * 0.002);
                acc += mixedPool(i * 0.003);
                acc += switchWithArray(i % 9, i);
            }
            return acc;
        }

        public static void main(String[] args) {
            System.out.println("ACC=" + Double.doubleToRawLongBits(run()));
        }
    }

    private static final Pattern CONST_TOTAL = Pattern.compile(
            "\\[JeandleConstTotal\\] Method: (\\S+), planned=(\\d+), allocated=(\\d+), actual=(\\d+),.*?fallback=(\\d+), align=(\\d+), expands=(\\d+)");

    public static void main(String[] args) throws Exception {
        String w = Workload.class.getName();
        ProcessBuilder pb = ProcessTools.createTestJavaProcessBuilder(
                "-XX:+UnlockDiagnosticVMOptions",
                "-XX:+UnlockExperimentalVMOptions",
                "-XX:+UseJeandleCompiler",
                "-XX:+JeandleCodeBufferInstrument",
                "-Xbatch",
                "-XX:CompileCommand=quiet",
                "-XX:CompileCommand=compileonly," + w + "::switchPlusPool",
                "-XX:CompileCommand=compileonly," + w + "::poolOnly",
                "-XX:CompileCommand=compileonly," + w + "::mixedPool",
                "-XX:CompileCommand=compileonly," + w + "::switchWithArray",
                "-cp", System.getProperty("java.class.path"),
                w);
        OutputAnalyzer out = new OutputAnalyzer(pb.start());
        out.shouldHaveExitValue(0);
        String stdout = out.getStdout();

        // Same result as this (non-Jeandle) VM computes.
        out.shouldContain("ACC=" + Double.doubleToRawLongBits(Workload.run()));

        out.shouldNotContain("AlignmentBeyondBase");
        Asserts.assertGT(summaryLong(stdout, "Methods reaching finalize"), 0L, "nothing was compiled");
        Asserts.assertEQ(summaryLong(stdout, "Planner failures"), 0L, "planner failures");
        Asserts.assertEQ(summaryLong(stdout, "Legacy path (48 KiB default)"), 0L, "legacy fallbacks");
        Asserts.assertEQ(summaryLong(stdout, "consts expansions after exact plan"), 0L, "consts expansions");

        int wide = 0;
        Matcher m = CONST_TOTAL.matcher(stdout);
        while (m.find()) {
            long fallback = Long.parseLong(m.group(5));
            long align = Long.parseLong(m.group(6));
            long expands = Long.parseLong(m.group(7));
            Asserts.assertEQ(fallback, 0L, m.group(1) + " fell back");
            Asserts.assertEQ(expands, 0L, m.group(1) + " expanded");
            Asserts.assertEQ(Long.parseLong(m.group(2)), Long.parseLong(m.group(4)), m.group(1) + " planned != actual");
            if (align > 8) {
                wide++;
            }
        }
        // riscv64 emits 16-byte aligned pools for mixedPool/switchPlusPool; other targets may not.
        System.out.println("methods with const alignment > 8: " + wide);
    }

    private static long summaryLong(String text, String label) {
        Matcher m = Pattern.compile(Pattern.quote(label) + ":\\s+(\\d+)").matcher(text);
        Asserts.assertTrue(m.find(), "summary line missing: " + label);
        return Long.parseLong(m.group(1));
    }
}
