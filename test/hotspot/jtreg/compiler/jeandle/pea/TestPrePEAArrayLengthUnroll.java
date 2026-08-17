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
 * version 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 */

/*
 * @test
 * @summary Pre-PEA high-tier cluster: JavaOpLengthFolding folds
 *          jeandle.arraylength(new_array(...)) to the allocation's length,
 *          making the loop trip count constant so the pre-PEA aggressive
 *          full unroll straight-lines the element stores; PEA then
 *          virtualizes the whole array. Also runs a -XX:-JeandleDoPEA
 *          control that only checks functional correctness.
 * @library /test/lib /
 * @build jdk.test.lib.Asserts
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.pea.TestPrePEAArrayLengthUnroll
 */

package compiler.jeandle.pea;

import compiler.jeandle.fileCheck.FileCheck;
import java.util.ArrayList;
import java.util.List;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestPrePEAArrayLengthUnroll {
    public static void main(String[] args) throws Exception {
        String dump_path = System.getProperty("user.dir");
        String wrapper = "compiler.jeandle.pea.TestPrePEAArrayLengthUnroll$TestWrapper";
        ArrayList<String> command_args = new ArrayList<String>(List.of(
                "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
                "-XX:+JeandleDoPEA",
                "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dump_path,
                "-XX:-UseCompressedOops", "-XX:-UseCompressedClassPointers",
                "-XX:CompileCommand=compileonly," + wrapper + "::test",
                wrapper));

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(command_args);
        OutputAnalyzer output = ProcessTools.executeCommand(pb);
        output.shouldHaveExitValue(0);

        // The loop is fully unrolled pre-PEA and the int[] allocation is
        // fully virtualized: no new_array may survive in the optimized IR.
        FileCheck checker = new FileCheck(dump_path,
                TestWrapper.class.getMethod("test"),
                /*optimized=*/true);
        checker.checkPattern("define hotspotcc.*i32 .*test.*");
        checker.checkNot("jeandle.new_array");
        checker.checkNot("jeandle.arraylength");

        output.shouldContain("TestPrePEAArrayLengthUnroll result: 3");

        // Control: with PEA disabled the gated cluster must not change
        // functional behavior.
        ArrayList<String> control_args = new ArrayList<String>(List.of(
                "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
                "-XX:-JeandleDoPEA",
                "-XX:-UseCompressedOops", "-XX:-UseCompressedClassPointers",
                "-XX:CompileCommand=compileonly," + wrapper + "::test",
                wrapper));
        OutputAnalyzer control = ProcessTools.executeCommand(
                ProcessTools.createLimitedTestJavaProcessBuilder(control_args));
        control.shouldHaveExitValue(0);
        control.shouldContain("TestPrePEAArrayLengthUnroll result: 3");
    }

    public static class TestWrapper {
        public static void main(String[] args) {
            int r = test();
            System.out.println("TestPrePEAArrayLengthUnroll result: " + r);
            Asserts.assertEquals(r, 3);
        }

        // Compiled by Jeandle. The array never escapes: after the pre-PEA
        // full unroll, every element store is at a constant offset and PEA
        // virtualizes the whole array.
        public static int test() {
            int[] arr = new int[10];
            for (int i = 0; i < arr.length; i++) {
                arr[i] = i;
            }
            return arr[3];
        }
    }
}
