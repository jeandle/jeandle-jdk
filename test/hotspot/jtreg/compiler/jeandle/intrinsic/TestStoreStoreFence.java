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
 * @summary Test the Jeandle intrinsic for Unsafe.storeStoreFence
 * @requires os.arch=="amd64" | os.arch=="x86_64" | os.arch=="aarch64"
 * @modules java.base/jdk.internal.misc
 * @library /test/lib /
 * @build compiler.jeandle.fileCheck.FileCheck
 * @run main/othervm compiler.jeandle.intrinsic.TestStoreStoreFence
 */

package compiler.jeandle.intrinsic;

import compiler.jeandle.fileCheck.FileCheck;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import jdk.internal.misc.Unsafe;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestStoreStoreFence {
    private static final String INTRINSIC_LOG =
            "Method `virtual void jdk.internal.misc.Unsafe.storeStoreFence()` is parsed as intrinsic";

    public static void main(String[] args) throws Exception {
        RunResult enabled = run(true, false);
        RunResult disabled = run(false, false);
        RunResult inlineUnsafeDisabled = run(false, true);

        enabled.output.shouldContain(INTRINSIC_LOG)
                      .shouldContain("TestStoreStoreFence PASSED");
        disabled.output.shouldNotContain(INTRINSIC_LOG)
                       .shouldContain("TestStoreStoreFence PASSED");
        inlineUnsafeDisabled.output.shouldNotContain(INTRINSIC_LOG)
                            .shouldContain("TestStoreStoreFence PASSED");

        FileCheck enabledCheck = new FileCheck(enabled.dumpDir.toString(),
                Runner.class.getMethod("main", String[].class), true);
        enabledCheck.checkPattern("fence syncscope\\(\"singlethread\"\\) release");
        enabledCheck.checkNotPattern("fence syncscope\\(\"singlethread\"\\) seq_cst");
        if (System.getProperty("os.arch").equals("aarch64")) {
            enabledCheck.checkPattern(
                    "call void @llvm\\.aarch64\\.dmb\\(i32 10\\)");
            enabledCheck.checkPattern("memory\\(inaccessiblemem: write\\)");
        } else {
            enabledCheck.checkNotPattern("llvm\\.aarch64\\.dmb");
        }

        FileCheck disabledCheck = new FileCheck(disabled.dumpDir.toString(),
                Runner.class.getMethod("main", String[].class), true);
        disabledCheck.checkNotPattern(
                "call void @llvm\\.aarch64\\.dmb\\(i32 10\\)");
        disabledCheck.checkNotPattern("fence syncscope\\(\"singlethread\"\\)");
        FileCheck inlineUnsafeDisabledCheck = new FileCheck(
                inlineUnsafeDisabled.dumpDir.toString(),
                Runner.class.getMethod("main", String[].class), true);
        inlineUnsafeDisabledCheck.checkNotPattern(
                "call void @llvm\\.aarch64\\.dmb\\(i32 10\\)");
        inlineUnsafeDisabledCheck.checkNotPattern(
                "fence syncscope\\(\"singlethread\"\\)");
        FileCheck inlineUnsafeFallbackCheck = new FileCheck(
                inlineUnsafeDisabled.dumpDir.toString(),
                Runner.class.getMethod("storeStoreFence", Unsafe.class), true);
        inlineUnsafeFallbackCheck.checkPattern("jdk_internal_misc_Unsafe_fullFence");
    }

    private static RunResult run(boolean enabled, boolean inlineUnsafeOff) throws Exception {
        Path dumpDir = Files.createTempDirectory("jeandle_store_store_fence_"
                + (enabled ? "on" : "off"));
        ArrayList<String> args = new ArrayList<>(List.of(
                "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
                "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
                "-XX:+UnlockDiagnosticVMOptions",
                "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR", "-XX:+JeandleDumpObjects",
                "-XX:JeandleDumpDirectory=" + dumpDir,
                "-XX:CompileCommand=compileonly," + Runner.class.getName() + "::storeStoreFence",
                "-XX:CompileCommand=compileonly," + Runner.class.getName() + "::fullFence",
                "-XX:CompileCommand=compileonly," + Runner.class.getName() + "::loadFence",
                "-XX:CompileCommand=compileonly," + Runner.class.getName() + "::storeFence",
                "-XX:CompileCommand=compileonly," + Runner.class.getName() + "::main"));
        if (inlineUnsafeOff) {
            args.add("-XX:-InlineUnsafeOps");
        } else {
            args.add("-XX:ControlIntrinsic=" + (enabled ? "+_storeStoreFence" : "-_storeStoreFence"));
        }
        args.add(Runner.class.getName());
        OutputAnalyzer output = ProcessTools.executeCommand(
                ProcessTools.createLimitedTestJavaProcessBuilder(args));
        output.shouldHaveExitValue(0);
        return new RunResult(output, dumpDir);
    }

    private record RunResult(OutputAnalyzer output, Path dumpDir) { }

    public static class Runner {
        private static final Unsafe U = Unsafe.getUnsafe();
        private static int before;
        private static int after;

        public static void main(String[] args) {
            for (int i = 1; i <= 10_000; i++) {
                before = i;
                storeStoreFence();
                after = before;
                if (after != i) {
                    throw new RuntimeException("unexpected value after fence: " + after);
                }
            }
            checkNullReceivers();
            System.out.println("TestStoreStoreFence PASSED");
        }

        private static void checkNullReceivers() {
            expectNullPointerException(() -> fullFence(null));
            expectNullPointerException(() -> loadFence(null));
            expectNullPointerException(() -> storeFence(null));
            expectNullPointerException(() -> storeStoreFence(null));
        }

        private static void expectNullPointerException(Runnable action) {
            try {
                action.run();
                throw new AssertionError("null Unsafe receiver did not throw");
            } catch (NullPointerException expected) {
                // Expected invokevirtual receiver semantics.
            }
        }

        public static void fullFence(Unsafe unsafe) {
            unsafe.fullFence();
        }

        public static void loadFence(Unsafe unsafe) {
            unsafe.loadFence();
        }

        public static void storeFence(Unsafe unsafe) {
            unsafe.storeFence();
        }

        public static void storeStoreFence(Unsafe unsafe) {
            unsafe.storeStoreFence();
        }

        public static void storeStoreFence() {
            U.storeStoreFence();
        }
    }
}
