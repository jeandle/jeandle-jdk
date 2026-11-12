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
 * @summary Clone orders scalar and bulk payload initialization before publication
 * @library /test/lib /
 * @build compiler.jeandle.fileCheck.FileCheck jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run driver TestClonePublication
 */

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import compiler.jeandle.fileCheck.FileCheck;
import jdk.test.lib.Asserts;
import jdk.test.lib.Platform;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;
import jdk.test.whitebox.WhiteBox;

public class TestClonePublication {
    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            int value = Integer.parseInt(args[0]);
            Scalar scalarSource = new Scalar(value);
            Scalar scalar = scalarSource.copy();
            Asserts.assertTrue(scalar != scalarSource, "scalar identity");
            Asserts.assertEquals(scalar.f, value, "scalar final field");
            Bulk bulkSource = new Bulk(value);
            Bulk bulk = bulkSource.copy();
            Asserts.assertTrue(bulk != bulkSource, "bulk identity");
            Asserts.assertEquals(bulk.f00, value + 0, "bulk final field 0");
            Asserts.assertEquals(bulk.f01, value + 1, "bulk final field 1");
            Asserts.assertEquals(bulk.f02, value + 2, "bulk final field 2");
            Asserts.assertEquals(bulk.f03, value + 3, "bulk final field 3");
            Asserts.assertEquals(bulk.f04, value + 4, "bulk final field 4");
            Asserts.assertEquals(bulk.f05, value + 5, "bulk final field 5");
            Asserts.assertEquals(bulk.f06, value + 6, "bulk final field 6");
            Asserts.assertEquals(bulk.f07, value + 7, "bulk final field 7");
            Asserts.assertEquals(bulk.f08, value + 8, "bulk final field 8");
            Asserts.assertEquals(bulk.f09, value + 9, "bulk final field 9");
            Asserts.assertEquals(bulk.f10, value + 10, "bulk final field 10");
            Asserts.assertEquals(bulk.f11, value + 11, "bulk final field 11");
            for (Class<?> holder : List.of(Scalar.class, Bulk.class)) {
                Asserts.assertTrue(WhiteBox.getWhiteBox().isMethodCompiled(holder.getDeclaredMethod("copy")),
                        "clone method must have installed compiled code: " + holder.getName());
            }
            System.out.println("CLONE_PUBLICATION_PASS");
            return;
        }
        Path dump = Files.createTempDirectory("clone_publication_");
        List<String> command = List.of(
                "-Xbootclasspath/a:.", "-XX:+UnlockDiagnosticVMOptions", "-XX:+WhiteBoxAPI",
                "-Xcomp", "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler",
                "-XX:+ReduceInitialCardMarks",
                "-XX:+CIPrintCompilerName", "-Xlog:jeandle=debug,jit+compilation=debug",
                "-XX:+JeandleDumpIR", "-XX:JeandleDumpDirectory=" + dump,
                "-XX:CompileCommand=compileonly," + Scalar.class.getName() + "::copy",
                "-XX:CompileCommand=compileonly," + Bulk.class.getName() + "::copy",
                TestClonePublication.class.getName(), "1511506142");
        OutputAnalyzer output = ProcessTools.executeProcess(
                ProcessTools.createLimitedTestJavaProcessBuilder(command));
        output.shouldHaveExitValue(0).shouldContain("CLONE_PUBLICATION_PASS");
        for (Class<?> holder : List.of(Scalar.class, Bulk.class)) {
            output.shouldMatch("Jeandle:.*" + Pattern.quote(holder.getName() + "::copy")
                    + " \\([0-9]+ bytes\\)");
            FileCheck raw = new FileCheck(dump.toString(), holder.getDeclaredMethod("copy"), false);
            raw.checkPattern("(?:call|invoke).*@jeandle\\.copy");
            raw.checkPattern("call.*@jeandle\\.membar_storestore");
            FileCheck optimized = new FileCheck(dump.toString(), holder.getDeclaredMethod("copy"), true);
            if (holder == Scalar.class) {
                optimized.checkPattern("store atomic i32 %clone\\.instance\\.load");
            } else {
                optimized.checkPattern("call void @StubRoutines_jlong_disjoint_arraycopy");
            }
            optimized.checkNextPattern(Platform.isAArch64()
                    ? "call void @llvm\\.aarch64\\.dmb\\(i32 10\\)"
                    : "fence release");
            optimized.checkPattern("ret ptr addrspace\\(1\\)");
        }
    }

    static final class Scalar implements Cloneable {
        final int f;

        Scalar(int value) {
            f = value;
        }

        Scalar copy() throws CloneNotSupportedException {
            return (Scalar) super.clone();
        }
    }

    static final class Bulk implements Cloneable {
        final int f00;
        final int f01;
        final int f02;
        final int f03;
        final int f04;
        final int f05;
        final int f06;
        final int f07;
        final int f08;
        final int f09;
        final int f10;
        final int f11;

        Bulk(int value) {
            f00 = value + 0;
            f01 = value + 1;
            f02 = value + 2;
            f03 = value + 3;
            f04 = value + 4;
            f05 = value + 5;
            f06 = value + 6;
            f07 = value + 7;
            f08 = value + 8;
            f09 = value + 9;
            f10 = value + 10;
            f11 = value + 11;
        }

        Bulk copy() throws CloneNotSupportedException {
            return (Bulk) super.clone();
        }
    }
}
