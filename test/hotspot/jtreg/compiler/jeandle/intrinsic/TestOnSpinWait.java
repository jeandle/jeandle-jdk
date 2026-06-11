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
 * 2 along with this work; if a Free Software Foundation, write to the
 * Free Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA.
 *
 */

/*
 * @test
 * @summary Test the intrinsic implementation of Thread.onSpinWait()
 * @requires os.arch=="amd64" | os.arch=="x86_64" | os.arch=="aarch64"
 * @library /test/lib /
 * @build jdk.test.lib.Asserts
 * @run main/othervm compiler.jeandle.intrinsic.TestOnSpinWait
 */

package compiler.jeandle.intrinsic;

import compiler.jeandle.fileCheck.FileCheck;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestOnSpinWait {
    public static void main(String[] args) throws Exception {
        String dumpPath = Files.createTempDirectory("jeandle_test_spinwait").toString();
        String arch = System.getProperty("os.arch");

        ArrayList<String> commandArgs = new ArrayList<>(List.of(
                "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
                "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dumpPath,
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::spinWaitLoop",
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::main",
                TestWrapper.class.getName()));

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(commandArgs);
        OutputAnalyzer output = ProcessTools.executeCommand(pb);

        output.shouldHaveExitValue(0)
              .shouldContain("is parsed as intrinsic");

        // Verify arch-dependent spin wait hint in IR
        FileCheck checker = new FileCheck(dumpPath,
                TestWrapper.class.getMethod("spinWaitLoop"), false);
        if (arch.equals("amd64") || arch.equals("x86_64")) {
            checker.checkPattern("llvm\\.x86\\.sse2\\.pause");
        } else if (arch.equals("aarch64")) {
            checker.checkPattern("llvm\\.aarch64\\.hint");
        }
        // RISC-V is conditional (needs Zihintpause) — skip IR check on riscv64
    }

    static class TestWrapper {
        public static void main(String[] args) {
            // Smoke test: Thread.onSpinWait() is a hint with no observable side effects.
            // Just verify it doesn't crash or throw exceptions in a spin loop.
            for (int i = 0; i < 100; i++) {
                spinWaitLoop();
            }
            System.out.println("TestOnSpinWait PASSED");
        }

        // This method will be compiled by Jeandle with Thread.onSpinWait() intrinsic
        public static void spinWaitLoop() {
            Thread.onSpinWait();
        }
    }
}
