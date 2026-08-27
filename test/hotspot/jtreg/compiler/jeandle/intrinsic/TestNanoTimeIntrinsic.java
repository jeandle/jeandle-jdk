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
 */

/*
 * @test id=semantic
 * @summary Verify Jeandle lowering of System.nanoTime
 * @library /test/lib /
 * @build compiler.jeandle.fileCheck.FileCheck
 * @run main/othervm compiler.jeandle.intrinsic.TestNanoTimeIntrinsic
 */

package compiler.jeandle.intrinsic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import compiler.jeandle.fileCheck.FileCheck;
import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestNanoTimeIntrinsic {
    private static final String INTRINSIC_LOG =
            "Method `static jlong java.lang.System.nanoTime()` is parsed as intrinsic";

    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            runSemantics();
            return;
        }
        runCase("enabled", true, null);
        // Force runtime entries outside the rel32 range on x86 to cover
        // fixed-size routine-call patching through a trampoline as well.
        runCase("forced_unreachable", true, "-XX:+ForceUnreachable");
        runCase("control_intrinsic_disabled", false, "-XX:ControlIntrinsic=-_nanoTime");
        runCase("inline_natives_disabled", false, "-XX:-InlineNatives");
    }

    private static void runCase(String name, boolean intrinsicEnabled,
                                String additionalVmOption) throws Exception {
        Path dumpPath = Files.createTempDirectory("jeandle_nano_time_" + name + "_ir");
        List<String> command = new ArrayList<>(List.of(
                "-Xbatch", "-Xcomp", "-XX:-TieredCompilation", "-XX:-BackgroundCompilation",
                "-XX:+UseJeandleCompiler", "-Xlog:jeandle=debug,jit+compilation=debug",
                "-XX:CompileCommand=compileonly," + TestMethods.class.getName() + "::nanoTime",
                "-XX:CompileCommand=compileonly," + TestMethods.class.getName() + "::nanoTimeTwice",
                "-XX:+UnlockDiagnosticVMOptions", "-XX:+CIPrintCompilerName", "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dumpPath));
        if (additionalVmOption != null) {
            command.add(additionalVmOption);
        }
        command.add(TestMethods.class.getName());
        command.add("child");

        OutputAnalyzer output = ProcessTools.executeCommand(
                ProcessTools.createLimitedTestJavaProcessBuilder(command));
        output.shouldHaveExitValue(0);
        if (intrinsicEnabled) {
            output.shouldContain(INTRINSIC_LOG);
            checkInstalledByJeandle(output, "nanoTime");
            checkInstalledByJeandle(output, "nanoTimeTwice");
        } else {
            output.shouldNotContain(INTRINSIC_LOG);
        }

        FileCheck checker = new FileCheck(dumpPath.toString(),
                TestMethods.class.getDeclaredMethod("nanoTime"), false);
        if (intrinsicEnabled) {
            checker.checkPattern("call i64 @os_javaTimeNanos\\(\\)");
        } else {
            checker.checkNotPattern("call i64 @os_javaTimeNanos\\(\\)");
        }
        FileCheck twice = new FileCheck(dumpPath.toString(),
                TestMethods.class.getDeclaredMethod("nanoTimeTwice"), true);
        if (intrinsicEnabled) {
            twice.checkPattern("call i64 @os_javaTimeNanos\\(\\)");
            twice.checkPattern("call i64 @os_javaTimeNanos\\(\\)");
        } else {
            twice.checkNotPattern("call i64 @os_javaTimeNanos\\(\\)");
        }
    }

    private static void checkInstalledByJeandle(OutputAnalyzer output, String method) {
        output.shouldMatch("(?s).*Jeandle:.*" +
                Pattern.quote(TestMethods.class.getName() + "::" + method) + ".*");
    }

    private static void runSemantics() {
        long previous = TestMethods.nanoTime();
        for (int i = 0; i < 20_000; i++) {
            long current = TestMethods.nanoTime();
            Asserts.assertGTE(current - previous, 0L);
            previous = current;
        }
    }

    static class TestMethods {
        static long nanoTime() {
            return System.nanoTime();
        }

        static long nanoTimeTwice() {
            return System.nanoTime() - System.nanoTime();
        }

        public static void main(String[] args) {
            System.getProperty("java.version");
            runSemantics();
            nanoTimeTwice();
        }
    }
}
