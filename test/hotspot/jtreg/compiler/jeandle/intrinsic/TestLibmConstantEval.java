/*
 * Copyright (c) 2026, The Jeandle-JDK Authors. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy of the License is included in the
 * LICENSE file that accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 */

/*
 * @test
 * @summary Non-trivial constant libm arguments fold to the runtime entry's own values
 * @requires os.arch == "amd64" | os.arch == "x86_64" | os.arch == "aarch64"
 * @library /test/lib /
 * @run main/othervm compiler.jeandle.intrinsic.TestLibmConstantEval
 */

package compiler.jeandle.intrinsic;

import compiler.jeandle.fileCheck.FileCheck;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestLibmConstantEval {

    public static void main(String[] args) throws Exception {
        String dump = Files.createTempDirectory("jeandle_libm_const").toString();
        ArrayList<String> command = new ArrayList<>(List.of(
                "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler",
                "-XX:+JeandleUseHotspotIntrinsics", "-Xlog:jeandle=debug",
                "-XX:+UnlockDiagnosticVMOptions", "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dump,
                "-XX:CompileCommand=compileonly," + Kernel.class.getName() + "::*"));
        command.add(Kernel.class.getName());
        OutputAnalyzer output = ProcessTools.executeCommand(
                ProcessTools.createLimitedTestJavaProcessBuilder(command));
        output.shouldHaveExitValue(0);
        output.shouldContain("libm constant eval checks passed");

        for (String name : List.of("sinConstant", "powConstant", "log10Constant")) {
            Method method = Kernel.class.getMethod(name, double.class);
            String definition = "define hotspotcc double .*_" + name + "\\(D\\)D.*";
            // The raw dump still carries the runtime call before optimization
            // (stub entry or SharedRuntime inttoptr, platform dependent).
            FileCheck raw = new FileCheck(dump, method, false);
            raw.selectFunction(definition);
            raw.checkPatternAnywhere("call double (@StubRoutines_d|@SharedRuntime_d|inttoptr)");
            // The optimized dump folds the call into a hex double constant.
            // Unused runtime aliases may remain outside the compiled function.
            FileCheck optimized = new FileCheck(dump, method, true);
            optimized.selectFunction(definition);
            optimized.checkNotPattern("(call|invoke) double");
            optimized.checkPatternAnywhere("fadd double .*0x");
        }
    }

    public static class Kernel {
        static double initializeMath = Math.abs(1.0);

        public static double sinConstant(double x) { return Math.sin(0.7) + x; }
        public static double powConstant(double x) { return Math.pow(2.5, 3.25) + x; }
        public static double log10Constant(double x) { return Math.log10(3.3) + x; }

        public static void main(String[] args) {
            long expectedSin = Double.doubleToRawLongBits(Math.sin(0.7));
            long expectedPow = Double.doubleToRawLongBits(Math.pow(2.5, 3.25));
            long expectedLog10 = Double.doubleToRawLongBits(Math.log10(3.3));
            double probe = 0.0;
            for (int i = 0; i < 20_000; i++) {
                probe += sinConstant(0.0) + powConstant(0.0) + log10Constant(0.0);
            }
            // Compiled (folded) results must be bit-identical to the
            // interpreter's own evaluation of the same runtime entries.
            Asserts.assertEQ(Double.doubleToRawLongBits(sinConstant(0.0)), expectedSin,
                    "sin(0.7) bits differ from the interpreter");
            Asserts.assertEQ(Double.doubleToRawLongBits(powConstant(0.0)), expectedPow,
                    "pow(2.5, 3.25) bits differ from the interpreter");
            Asserts.assertEQ(Double.doubleToRawLongBits(log10Constant(0.0)), expectedLog10,
                    "log10(3.3) bits differ from the interpreter");
            Asserts.assertTrue(probe == probe, "probe is finite");
            System.out.println("libm constant eval checks passed");
        }
    }
}
