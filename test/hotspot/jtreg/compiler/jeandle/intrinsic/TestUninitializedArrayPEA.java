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
 */

/*
 * @test
 * @summary PEA must preserve initialization copied into an Unsafe uninitialized array
 * @requires vm.gc.G1
 * @modules java.base/jdk.internal.misc
 * @library /test/lib /
 * @build compiler.jeandle.fileCheck.FileCheck jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run driver compiler.jeandle.intrinsic.TestUninitializedArrayPEA
 */

package compiler.jeandle.intrinsic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import compiler.jeandle.fileCheck.FileCheck;
import jdk.internal.misc.Unsafe;
import jdk.test.lib.Asserts;
import jdk.test.lib.process.ProcessTools;
import jdk.test.whitebox.WhiteBox;

public class TestUninitializedArrayPEA {
    private static final Unsafe U = Unsafe.getUnsafe();
    private static volatile int[] sink;

    public static int read(int[] array) {
        return array[0];
    }

    public static int probe() {
        int[] array = (int[]) U.allocateUninitializedArray(int.class, 8);
        System.arraycopy(new int[8], 0, array, 0, 8);
        return read(array);
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            for (boolean pea : new boolean[] {true, false}) {
                Path dump = Files.createTempDirectory("uninitialized_pea_");
                String name = TestUninitializedArrayPEA.class.getName();
                var command = new ArrayList<>(List.of(
                        "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
                        "-XX:+UnlockDiagnosticVMOptions", "-XX:+WhiteBoxAPI", "-Xbootclasspath/a:.",
                        "-Xms64m", "-Xmx64m", "-XX:+UseG1GC", "-XX:-ZeroTLAB",
                        "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler",
                        pea ? "-XX:+JeandleDoPEA" : "-XX:-JeandleDoPEA",
                        "-XX:CompileThreshold=100", "-XX:+JeandleDumpIR",
                        "-XX:JeandleDumpDirectory=" + dump,
                        "-XX:CompileCommand=compileonly," + name + "::probe",
                        "-XX:CompileCommand=compileonly," + name + "::read",
                        "-XX:CompileCommand=compileonly,jdk.internal.misc.Unsafe::allocateUninitializedArray",
                        "-XX:CompileCommand=compileonly,jdk.internal.misc.Unsafe::allocateUninitializedArray0",
                        "-XX:CompileCommand=dontinline," + name + "::read", name, "child"));
                ProcessTools.executeProcess(ProcessTools.createLimitedTestJavaProcessBuilder(command))
                        .shouldHaveExitValue(0).shouldContain("320000 initialized reads verified");
                FileCheck intrinsic = new FileCheck(dump.toString(), Unsafe.class.getMethod(
                        "allocateUninitializedArray", Class.class, int.class), false);
                intrinsic.checkPattern("invoke hotspotcc .*@jeandle[.]new_array.*i1 true, i1 false");
                FileCheck initial = new FileCheck(dump.toString(),
                        TestUninitializedArrayPEA.class.getMethod("probe"), false);
                initial.checkPattern("invoke hotspotcc void @jeandle[.]copy");
                FileCheck optimized = new FileCheck(dump.toString(),
                        TestUninitializedArrayPEA.class.getMethod("probe"), true);
                optimized.checkNotPattern("(call|invoke).*@.*Unsafe_allocateUninitializedArray");
            }
            return;
        }
        for (int round = 0; round < 32; round++) {
            for (int i = 0; i < 50000; i++) {
                int[] array = new int[8];
                Arrays.fill(array, 0x13579bdf + round);
                sink = array;
            }
            sink = null;
            System.gc();
            for (int i = 0; i < 10000; i++) {
                Asserts.assertEquals(probe(), 0, "zero-source copy must initialize every element");
            }
        }
        WhiteBox wb = WhiteBox.getWhiteBox();
        Asserts.assertTrue(wb.isMethodCompiled(TestUninitializedArrayPEA.class.getMethod("probe")));
        Asserts.assertTrue(wb.isMethodCompiled(TestUninitializedArrayPEA.class.getMethod("read", int[].class)));
        System.out.println("320000 initialized reads verified");
    }
}
