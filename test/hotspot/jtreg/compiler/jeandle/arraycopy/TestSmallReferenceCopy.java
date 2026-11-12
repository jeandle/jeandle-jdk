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
 * @summary Small fresh reference copies retain oop typing, GC barriers and publication ordering
 * @requires vm.gc.Serial & vm.gc.G1
 * @library /test/lib /
 * @build compiler.jeandle.fileCheck.FileCheck jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run driver compiler.jeandle.arraycopy.TestSmallReferenceCopy
 */

package compiler.jeandle.arraycopy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import compiler.jeandle.fileCheck.FileCheck;
import jdk.test.lib.Asserts;
import jdk.test.lib.Platform;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;
import jdk.test.whitebox.WhiteBox;

public class TestSmallReferenceCopy {
    private static final List<String> METHODS = List.of("copyOf8", "copyOfRange8", "copyOf9");

    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            Workload.run(args[0].equals("pretenure"));
            return;
        }
        for (String gc : List.of("-XX:+UseG1GC", "-XX:+UseSerialGC")) {
            for (boolean compressed : List.of(true, false)) {
                for (boolean reduceInitialCardMarks : List.of(true, false)) {
                    runCase(gc, compressed, reduceInitialCardMarks);
                }
            }
        }
        runCase("-XX:+UseSerialGC", true, true,
                "-XX:-UseTLAB", "-XX:PretenureSizeThreshold=32");
    }

    private static void runCase(String gc, boolean compressed,
                                boolean reduceInitialCardMarks,
                                String... extraOptions) throws Exception {
        Path dump = Files.createTempDirectory("small_reference_copy_");
        ArrayList<String> command = new ArrayList<>(List.of(
                "-Xbootclasspath/a:.", "-XX:+UnlockDiagnosticVMOptions", "-XX:+WhiteBoxAPI",
                "-Xcomp", "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler",
                "-XX:+JeandleDoPEA", "-Xms32m", "-Xmx32m", gc,
                compressed ? "-XX:+UseCompressedOops" : "-XX:-UseCompressedOops",
                reduceInitialCardMarks ? "-XX:+ReduceInitialCardMarks" : "-XX:-ReduceInitialCardMarks",
                "-XX:+CIPrintCompilerName", "-Xlog:jit+compilation=debug",
                "-XX:+JeandleDumpIR", "-XX:JeandleDumpDirectory=" + dump));
        for (String name : METHODS) {
            command.add("-XX:CompileCommand=compileonly," + Workload.class.getName() + "::" + name);
        }
        command.addAll(List.of(extraOptions));
        command.add(TestSmallReferenceCopy.class.getName());
        command.add(extraOptions.length == 0 ? "workload" : "pretenure");
        OutputAnalyzer output = ProcessTools.executeProcess(
                ProcessTools.createLimitedTestJavaProcessBuilder(command));
        output.shouldHaveExitValue(0).shouldContain("SMALL_REFERENCE_COPY_PASS");
        for (String name : METHODS) {
            output.shouldMatch("Jeandle:.*" + Pattern.quote(Workload.class.getName() + "::" + name)
                    + " \\([0-9]+ bytes\\)");
            FileCheck optimized = new FileCheck(dump.toString(),
                    Workload.class.getDeclaredMethod(name, Object.class, Object.class), true);
            if (reduceInitialCardMarks && !name.equals("copyOf9")) {
                int oopSpace = compressed ? 3 : 1;
                optimized.checkPattern("load atomic volatile ptr addrspace\\(" + oopSpace + "\\)");
                optimized.checkPattern("store atomic ptr addrspace\\(" + oopSpace + "\\)");
            } else {
                optimized.checkPattern("call void @StubRoutines_(?:arrayof_)?oop_disjoint_arraycopy_uninit");
            }
            optimized.checkPattern(Platform.isAArch64()
                    ? "call void @llvm\\.aarch64\\.dmb\\(i32 10\\)" : "fence release");
            optimized.checkPatternAnywhere("ret ptr addrspace\\(1\\)");
        }
    }

    static class Workload {
        static Object[] copyOf8(Object a, Object b) {
            return Arrays.copyOf(new Object[] {a, b, a, b, a, b, a, b}, 8, Object[].class);
        }

        static Object[] copyOfRange8(Object a, Object b) {
            return Arrays.copyOfRange(new Object[] {b, a, b, a, b, a, b, a, b}, 1, 9, Object[].class);
        }

        static Object[] copyOf9(Object a, Object b) {
            return Arrays.copyOf(new Object[] {a, b, a, b, a, b, a, b, a}, 9, Object[].class);
        }

        static void run(boolean pretenure) throws Exception {
            for (int iteration = 0; iteration < 100_000; iteration++) {
                Object a = new Object();
                Object b = new Object();
                Object[][] copies = {copyOf8(a, b), copyOfRange8(a, b), copyOf9(a, b)};
                if (pretenure && iteration % 1024 == 0) {
                    if (iteration == 0) {
                        Asserts.assertFalse(WhiteBox.getWhiteBox().isObjectInOldGen(a),
                                "first reference must be young before collection");
                        Asserts.assertFalse(WhiteBox.getWhiteBox().isObjectInOldGen(b),
                                "second reference must be young before collection");
                    }
                    for (Object[] copy : copies) {
                        Asserts.assertTrue(WhiteBox.getWhiteBox().isObjectInOldGen(copy),
                                "pretenured copy must be in the old generation");
                    }
                    WhiteBox.getWhiteBox().youngGC();
                }
                for (int index = 0; index < copies.length; index++) {
                    Object[] copy = copies[index];
                    Asserts.assertEquals(copy.length, index == 2 ? 9 : 8);
                    for (int element = 0; element < copy.length; element++) {
                        Asserts.assertTrue(copy[element] == ((element & 1) == 0 ? a : b),
                                "shallow reference copy changed element " + element);
                    }
                }
            }
            for (String name : METHODS) {
                Asserts.assertTrue(WhiteBox.getWhiteBox().isMethodCompiled(
                        Workload.class.getDeclaredMethod(name, Object.class, Object.class)),
                        "copy method must have installed compiled code: " + name);
            }
            System.out.println("SMALL_REFERENCE_COPY_PASS");
        }
    }
}
