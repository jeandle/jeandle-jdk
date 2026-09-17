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
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 */

/*
 * @test
 * @summary Dynamic array allocation must not speculate Object[] before LLVM folds the layout
 * @library /test/lib /
 * @build TestObjectClone compiler.jeandle.fileCheck.FileCheck
 * @run driver TestArrayCloneAllocation
 */

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import compiler.jeandle.fileCheck.FileCheck;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestArrayCloneAllocation {
    public static void main(String[] args) throws Exception {
        for (String gc : List.of("-XX:+UseSerialGC", "-XX:+UseG1GC")) {
            run(gc, List.of(), true);
            run(gc, List.of("-XX:-UseCompressedOops"), true);
            run(gc, List.of("-XX:-UseTLAB", "-XX:-ReduceInitialCardMarks"), true);
        }
        run("-XX:+UseG1GC", List.of("-XX:ControlIntrinsic=-_clone"), false);
    }

    private static void run(String gc, List<String> extra, boolean enabled) throws Exception {
        Path dump = Files.createTempDirectory("array_clone_allocation_");
        ArrayList<String> command = new ArrayList<>(List.of(
                "-XX:+UnlockDiagnosticVMOptions", "-Xbatch", "-Xcomp",
                "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler",
                "-Xms128m", "-Xmx128m", gc, "-Xlog:jeandle=debug,gc=info",
                "-XX:+JeandleDumpIR", "-XX:JeandleDumpDirectory=" + dump,
                "-XX:CompileCommand=compileonly,TestObjectClone$TestMethods::*"));
        command.addAll(extra);
        command.add("TestObjectClone$Workload");
        command.add("arrays");
        OutputAnalyzer out = ProcessTools.executeProcess(
                ProcessTools.createLimitedTestJavaProcessBuilder(command));
        out.shouldHaveExitValue(0).shouldContain("OBJECT_CLONE_PASS");
        out.shouldContain(gc.contains("Serial") ? "Using Serial" : "Using G1");
        String intrinsic = "Method `virtual jobject java.lang.Object.clone()` is parsed as intrinsic";
        if (!enabled) {
            out.shouldNotContain(intrinsic);
            FileCheck raw = new FileCheck(dump.toString(),
                    TestObjectClone.TestMethods.class.getDeclaredMethod("cloneIntArray", int[].class), false);
            raw.checkNotPattern("invoke.*@jeandle\\.new_array");
            return;
        }
        out.shouldContain(intrinsic);
        String[] names = { "Byte", "Short", "Int", "Long", "Boolean", "Char", "Float", "Double" };
        Class<?>[] types = { byte[].class, short[].class, int[].class, long[].class,
                             boolean[].class, char[].class, float[].class, double[].class };
        for (int i = 0; i < names.length; i++) {
            TestObjectClone.checkPrimitiveArrayFastPath(dump, "clone" + names[i] + "Array", types[i]);
        }
        TestObjectClone.checkPrimitiveArrayFastPath(dump, "cloneIntArrayElement", int[][].class);
        FileCheck raw = new FileCheck(dump.toString(),
                TestObjectClone.TestMethods.class.getDeclaredMethod("cloneObjectArray", Object[].class), false);
        raw.checkNotPattern("new_array\\.object_array_layout_matches");
        raw.checkNotPattern("new_array\\.class_check_deopt");
        raw.checkPatternAnywhere("new_array\\.log2_element_size = and i32");
        raw.checkPatternAnywhere("invoke.*@jeandle\\.new_array.*\\[ \"deopt\"\\(");
    }
}
