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
 * @summary PEA virtual array with arraylength and bounds-checked element
 *          access: jeandle.arraylength is lower-phase="1" so the call
 *          reaches PEA and is folded to the known length (the frontend
 *          emits this op both for the arraylength bytecode and for bounds
 *          checks). At lower-phase="0" the expanded raw header load only
 *          had the processLoad fallback.
 * @library /test/lib /
 * @build jdk.test.lib.Asserts
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.pea.TestVirtualArrayLengthPEA
 */

package compiler.jeandle.pea;

import compiler.jeandle.fileCheck.FileCheck;
import java.util.ArrayList;
import java.util.List;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestVirtualArrayLengthPEA {
    public static void main(String[] args) throws Exception {
        String dump_path = System.getProperty("user.dir");
        String wrapper = "compiler.jeandle.pea.TestVirtualArrayLengthPEA$TestWrapper";
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

        // The int[] allocation is fully virtualized: the arraylength call
        // (arraylength bytecode) and every bounds check fold away together
        // with the element stores/loads.
        FileCheck checker = new FileCheck(dump_path,
                TestWrapper.class.getMethod("test"),
                /*optimized=*/true);
        checker.checkPattern("define hotspotcc.*i32 .*test.*");
        checker.checkNot("jeandle.new_array");

        output.shouldContain("TestVirtualArrayLengthPEA result: 12");
    }

    public static class TestWrapper {
        public static void main(String[] args) {
            int r = test();
            System.out.println("TestVirtualArrayLengthPEA result: " + r);
            Asserts.assertEquals(r, 12);
        }

        // Compiled by Jeandle. The array never escapes: its length and all
        // element accesses (each carrying a bounds check) fold to constants.
        public static int test() {
            int[] arr = new int[3];
            arr[0] = 1;
            arr[1] = 2;
            arr[2] = 3;
            return arr.length + arr[0] + arr[1] + arr[2] + arr.length;
        }
    }
}
