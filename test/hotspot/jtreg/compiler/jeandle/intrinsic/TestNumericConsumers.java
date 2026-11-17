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
 * @summary Verify numeric intrinsic consumers and exact libm identities
 * @requires os.arch=="amd64" | os.arch=="x86_64" | os.arch=="aarch64"
 * @library /test/lib /
 * @build jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm compiler.jeandle.intrinsic.TestNumericConsumers
 */

package compiler.jeandle.intrinsic;

import compiler.jeandle.fileCheck.FileCheck;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import jdk.test.lib.Asserts;
import jdk.test.lib.Platform;
import jdk.test.lib.process.ProcessTools;

public class TestNumericConsumers {
    public static void main(String[] args) throws Exception {
        run(false);
        if (Platform.isX64()) run(true);
    }

    private static FileCheck check(String dump, String name, Class<?>... types) throws Exception {
        return new FileCheck(dump, Kernel.class.getMethod(name, types), true);
    }

    private static void run(boolean shared) throws Exception {
        String dump = Files.createTempDirectory("numeric_consumers").toString();
        ArrayList<String> command = new ArrayList<>(List.of(
                "-XX:+UnlockDiagnosticVMOptions", "-Xcomp", "-Xbatch", "-Xbootclasspath/a:.", "-XX:+WhiteBoxAPI", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler",
                "-XX:+JeandleUseHotspotIntrinsics", "-XX:+UseSignumIntrinsic",
                "-XX:+UnlockDiagnosticVMOptions", "-XX:+JeandleDumpIR", "-Xlog:jeandle=debug",
                "-XX:JeandleDumpDirectory=" + dump,
                "-XX:CompileCommand=compileonly," + Kernel.class.getName() + "::test*"));
        if (Platform.isX64()) command.add(shared ? "-XX:-UseLibmIntrinsic" : "-XX:+UseLibmIntrinsic");
        command.add(Kernel.class.getName());
        ProcessTools.executeCommand(ProcessTools.createLimitedTestJavaProcessBuilder(command))
                .shouldHaveExitValue(0).shouldContain("numeric consumers passed");
        for (String method : List.of("testRoundInt", "testRoundNarrow")) {
            FileCheck fc = check(dump, method, int.class);
            fc.checkNot("sitofp");
            fc.checkNot("uitofp");
            fc.checkNotPattern("call i(32|64) @llvm.fptosi");
            fc.checkNot("ashr");
            fc.checkNot("jeandle.round");
        }
        for (String method : List.of("testPositive", "testIsOne")) {
            FileCheck fc = check(dump, method, double.class);
            fc.checkPatternAnywhere("fcmp ogt double");
            fc.checkNot("llvm.copysign");
        }
        FileCheck fc = check(dump, "testNegativeF", float.class);
        fc.checkPatternAnywhere("fcmp olt float");
        fc.checkNot("llvm.copysign");
        fc = check(dump, "testTwice", double.class);
        fc.check("call double @llvm.copysign.f64");
        String prefix = Kernel.class.getName().replace('.', '_') + "_testTwice_";
        try (var files = Files.list(java.nio.file.Path.of(dump))) {
            var ir = files.filter(p -> p.getFileName().toString().startsWith(prefix)
                    && p.toString().endsWith("_optimized.ll")).findFirst().orElseThrow();
            long copies = Files.readAllLines(ir).stream()
                    .filter(line -> line.contains("call double @llvm.copysign.f64")).count();
            Asserts.assertEQ(copies, 1L);
        }
        for (String method : List.of("testSin", "testCos", "testTan", "testLog", "testLog10", "testExp")) {
            fc = check(dump, method);
            fc.checkNot("call double");
            fc.checkNot("invoke");
        }
        check(dump, "testRawCopy", long.class, long.class).checkNot("call double @llvm.copysign");
        check(dump, "testRawFloat", int.class, int.class).checkNot("call float @llvm.copysign");
        for (String method : List.of("testDeadSin", "testDeadPow")) {
            fc = check(dump, method, double.class);
            fc.checkNot("call double");
            fc.checkNot("invoke");
        }
    }

    public static class Kernel {
        static double loadMath = Math.abs(1.0);
        public static long testRoundInt(int x) { return Math.round((double) x); }
        public static int testRoundNarrow(int x) { return Math.round((float) (x & 65535)); }
        public static int testRoundLossy(int x) { return Math.round((float) x); }
        public static long testRoundLong(long x) { return Math.round((double) x); }
        public static boolean testPositive(double x) { return Math.signum(x) > 0.0; }
        public static boolean testIsOne(double x) { return Math.signum(x) == 1.0; }
        public static boolean testNegativeF(float x) { return Math.signum(x) == -1.0f; }
        public static double testTwice(double x) { return Math.signum(Math.signum(x)); }
        public static double testSin() { return Math.sin(-0.0); }
        public static double testCos() { return Math.cos(-0.0); }
        public static double testTan() { return Math.tan(-0.0); }
        public static double testLog() { return Math.log(1.0); }
        public static double testLog10() { return Math.log10(1.0); }
        public static double testExp() { return Math.exp(-0.0); }
        public static long testRawCopy(long m, long s) {
            return Double.doubleToRawLongBits(Math.copySign(
                    Double.longBitsToDouble(m), Double.longBitsToDouble(s)));
        }
        public static int testRawFloat(int m, int s) {
            return Float.floatToRawIntBits(Math.copySign(
                    Float.intBitsToFloat(m), Float.intBitsToFloat(s)));
        }
        public static int testDeadSin(double x) { Math.sin(x); return 17; }
        public static int testDeadPow(double x) { Math.pow(x, x); return 19; }

        private static void verify(int x, long l, double d, float f) {
            Asserts.assertEQ(testRawCopy(l, Double.doubleToRawLongBits(d)),
                    (l & Long.MAX_VALUE) | (Double.doubleToRawLongBits(d) & Long.MIN_VALUE));
            Asserts.assertEQ(testRawFloat(x, Float.floatToRawIntBits(f)),
                    (x & Integer.MAX_VALUE) | (Float.floatToRawIntBits(f) & Integer.MIN_VALUE));
            Asserts.assertEQ(testRoundInt(x), (long) x);
            Asserts.assertEQ(testRoundNarrow(x), x & 65535);
            Asserts.assertEQ(testRoundLossy(x), Math.round((float) x));
            Asserts.assertEQ(testRoundLong(l), Math.round((double) l));
            Asserts.assertEQ(testPositive(d), d > 0.0);
            Asserts.assertEQ(testIsOne(d), d > 0.0);
            Asserts.assertEQ(testNegativeF(f), f < 0.0f);
            Asserts.assertEQ(Double.doubleToRawLongBits(testTwice(d)),
                    Double.doubleToRawLongBits(Math.signum(d)));
            Asserts.assertEQ(testDeadSin(d), 17);
            Asserts.assertEQ(testDeadPow(d), 19);
        }

        public static void main(String[] args) {
            long[] bits = {0, Long.MIN_VALUE, 1, -1, 0x7ff0000000000000L,
                    0xfff0000000000000L, 0x7ff0000000000001L, 0xfff8123456789abcL,
                    0x3fe0000000000000L, 0xbfe0000000000000L, 0x4330000000000000L};
            int[] ints = {0, 1, -1, Integer.MIN_VALUE, Integer.MAX_VALUE,
                    16777215, 16777216, 16777217, -16777217};
            for (long b : bits) for (int x : ints)
                verify(x, b, Double.longBitsToDouble(b), Float.intBitsToFloat((int) b));
            Random r = new Random(20261112);
            for (int i = 0; i < 10000; i++)
                verify(r.nextInt(), r.nextLong(), Double.longBitsToDouble(r.nextLong()),
                        Float.intBitsToFloat(r.nextInt()));
            Asserts.assertEQ(Double.doubleToRawLongBits(testSin()), Long.MIN_VALUE);
            Asserts.assertEQ(Double.doubleToRawLongBits(testTan()), Long.MIN_VALUE);
            Asserts.assertEQ(Double.doubleToRawLongBits(testCos()), Double.doubleToRawLongBits(1.0));
            Asserts.assertEQ(Double.doubleToRawLongBits(testExp()), Double.doubleToRawLongBits(1.0));
            Asserts.assertEQ(Double.doubleToRawLongBits(testLog()), 0L);
            Asserts.assertEQ(Double.doubleToRawLongBits(testLog10()), 0L);
            for (var method : Kernel.class.getDeclaredMethods()) {
                if (method.getName().startsWith("test")) {
                    var wb = jdk.test.whitebox.WhiteBox.getWhiteBox();
                    Asserts.assertTrue(wb.isMethodCompiled(method), method + " must stay compiled");
                    Asserts.assertEQ(wb.getMethodCompilationLevel(method), 4,
                            method + " must execute with the optimizing compiler");
                }
            }
            System.out.println("numeric consumers passed");
        }
    }
}
