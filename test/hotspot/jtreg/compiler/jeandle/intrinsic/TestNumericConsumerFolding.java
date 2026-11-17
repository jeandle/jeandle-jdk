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
 * @key randomness
 * @summary Verify exact round and numeric comparison consumer folding
 * @requires (os.arch == "amd64" | os.arch == "x86_64") & vm.cpu.features ~= ".*bmi2.*"
 * @library /test/lib /
 * @run main/othervm compiler.jeandle.intrinsic.TestNumericConsumerFolding
 */

package compiler.jeandle.intrinsic;

import compiler.jeandle.fileCheck.FileCheck;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.List;
import java.util.Random;
import jdk.test.lib.Asserts;
import jdk.test.lib.process.ProcessTools;

public class TestNumericConsumerFolding {
    public static void main(String[] args) throws Exception {
        String dump = Files.createTempDirectory("numeric_consumer_folding").toString();
        ProcessTools.executeTestJava(List.of(
                "-Xbatch", "-Xcomp", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler",
                "-XX:+UnlockDiagnosticVMOptions", "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dump,
                "-XX:CompileCommand=compileonly," + Kernel.class.getName() + "::kernel*",
                Kernel.class.getName()).toArray(new String[0]))
                .shouldHaveExitValue(0).shouldContain("Numeric consumer checks passed");
        for (String name : List.of("kernelRoundFloat", "kernelRoundDouble")) {
            Class<?> type = name.endsWith("Float") ? int.class : long.class;
            Method method = Kernel.class.getMethod(name, type);
            FileCheck raw = new FileCheck(dump, method, false);
            raw.checkPattern("define hotspotcc .*" + name + ".*\\.root");
            raw.checkPattern("call .*@jeandle\\.round\\.");
            FileCheck optimized = new FileCheck(dump, method, true);
            optimized.checkPattern("define hotspotcc .*" + name + ".*");
            optimized.check(name.endsWith("Float") ? "shl i32" : "and i64");
            optimized.checkPattern("ret i(?:32|64) %");
            optimized.checkNot("sitofp");
            optimized.checkNotPattern("call .*@llvm\\.fptosi\\.sat");
            optimized.checkNot("@__llvm_deoptimize");
        }
        for (String name : List.of("kernelCompressInt", "kernelCompressLong")) {
            Class<?> type = name.endsWith("Int") ? int.class : long.class;
            Method method = Kernel.class.getMethod(name, type, type);
            FileCheck raw = new FileCheck(dump, method, false);
            raw.checkPattern("define hotspotcc .*" + name + ".*\\.root");
            raw.checkPattern("call .*@llvm\\.x86\\.bmi\\.pext\\.");
            FileCheck optimized = new FileCheck(dump, method, true);
            optimized.checkPattern("define hotspotcc .*" + name + ".*");
            optimized.check("and i");
            optimized.checkNotPattern("call .*@llvm\\.x86\\.bmi\\.pext\\.");
            optimized.checkNot("@__llvm_deoptimize");
        }
        for (String name : List.of("kernelSignumFloat", "kernelSignumDouble")) {
            Class<?> type = name.endsWith("Float") ? float.class : double.class;
            Method method = Kernel.class.getMethod(name, type);
            FileCheck raw = new FileCheck(dump, method, false);
            raw.checkPattern("define hotspotcc .*" + name + ".*\\.root");
            raw.checkPattern("call .*@llvm\\.copysign\\.");
            FileCheck optimized = new FileCheck(dump, method, true);
            optimized.checkPattern("define hotspotcc .*" + name + ".*");
            optimized.check("fcmp ogt");
            optimized.checkNot("call float @llvm.copysign");
            optimized.checkNot("call double @llvm.copysign");
            optimized.checkNot("@__llvm_deoptimize");
        }
        FileCheck bounded = new FileCheck(dump,
                Kernel.class.getMethod("kernelSignumBound", double.class), true);
        bounded.checkPattern("define hotspotcc .*kernelSignumBound.*");
        bounded.check("ret i32 0");
        bounded.checkNot("call double @llvm.copysign");
        bounded.checkNot("@__llvm_deoptimize");
    }

    public static class Kernel {
        public static int kernelRoundFloat(int x) { return Math.round((float) (x << 8)); }
        public static long kernelRoundDouble(long x) { return Math.round((double) (x & ~1023L)); }
        public static boolean kernelCompressInt(int x, int m) { return Integer.compress(x, m) == 0; }
        public static boolean kernelCompressLong(long x, long m) { return Long.compress(x, m) != 0; }
        public static boolean kernelSignumFloat(float x) { return Math.signum(x) > 0.5f; }
        public static boolean kernelSignumDouble(double x) { return Math.signum(x) > 0.5; }
        public static boolean kernelSignumBound(double x) { return Math.signum(x) > 1.0; }
        public static int kernelLossyFloat(int x) { return Math.round((float) x); }
        public static long kernelLossyDouble(long x) { return Math.round((double) x); }

        private static void checkInt(int x, int mask) {
            Asserts.assertEQ(kernelRoundFloat(x), x << 8);
            Asserts.assertEQ(kernelCompressInt(x, mask), (x & mask) == 0);
        }

        private static void checkLong(long x, long mask) {
            Asserts.assertEQ(kernelRoundDouble(x), x & ~1023L);
            Asserts.assertEQ(kernelCompressLong(x, mask), (x & mask) != 0);
            double value = Double.longBitsToDouble(x);
            Asserts.assertEQ(kernelSignumDouble(value), value > 0.0);
            Asserts.assertFalse(kernelSignumBound(value));
            float single = Float.intBitsToFloat((int) x);
            Asserts.assertEQ(kernelSignumFloat(single), single > 0.0f);
        }

        public static void main(String[] args) {
            int[] intEdges = {0, 1, -1, Integer.MIN_VALUE, Integer.MAX_VALUE, 16777217, -16777217};
            long[] longEdges = {0, 1, -1, Long.MIN_VALUE, Long.MAX_VALUE, 9007199254740993L,
                    -9007199254740993L, 0x7ff0000000000000L, 0xfff0000000000000L,
                    0x7ff8000000001234L, 0xfff0000000000001L, 0x3fe0000000000000L};
            for (int x : intEdges) for (int mask : intEdges) checkInt(x, mask);
            for (long x : longEdges) for (long mask : longEdges) checkLong(x, mask);
            Asserts.assertEQ(kernelLossyFloat(16777217), 16777216);
            Asserts.assertEQ(kernelLossyFloat(Integer.MAX_VALUE), Integer.MAX_VALUE);
            Asserts.assertEQ(kernelLossyDouble(9007199254740993L), 9007199254740992L);
            Asserts.assertEQ(kernelLossyDouble(Long.MAX_VALUE), Long.MAX_VALUE);
            Random random = new Random(662131);
            for (int i = 0; i < 20000; i++) {
                checkInt(random.nextInt(), random.nextInt());
                checkLong(random.nextLong(), random.nextLong());
            }
            System.out.println("Numeric consumer checks passed");
        }
    }
}
