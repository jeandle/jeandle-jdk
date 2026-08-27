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
 * @summary Verify Jeandle lowering of System.currentTimeMillis
 * @library /test/lib /
 * @build compiler.jeandle.fileCheck.FileCheck
 * @run main/othervm compiler.jeandle.intrinsic.TestCurrentTimeMillisIntrinsic
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

public class TestCurrentTimeMillisIntrinsic {
    private static final String INTRINSIC_LOG =
            "Method `static jlong java.lang.System.currentTimeMillis()` is parsed as intrinsic";

    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            runSemantics();
            return;
        }
        runCase("enabled", true, null);
        // Force runtime entries outside the rel32 range on x86 to cover
        // fixed-size routine-call patching through a trampoline as well.
        runCase("forced_unreachable", true, "-XX:+ForceUnreachable");
        runCase("control_intrinsic_disabled", false,
                "-XX:ControlIntrinsic=-_currentTimeMillis");
        runCase("inline_natives_disabled", false, "-XX:-InlineNatives");
    }

    private static void runCase(String name, boolean intrinsicEnabled,
                                String additionalVmOption) throws Exception {
        Path dumpPath = Files.createTempDirectory("jeandle_current_time_millis_" + name + "_ir");
        List<String> command = new ArrayList<>(List.of(
                "-Xbatch", "-Xcomp", "-XX:-TieredCompilation", "-XX:-BackgroundCompilation",
                "-XX:+UseJeandleCompiler", "-Xlog:jeandle=debug,jit+compilation=debug",
                "-XX:CompileCommand=compileonly," + TestMethods.class.getName()
                        + "::currentTimeMillis",
                "-XX:CompileCommand=compileonly," + TestMethods.class.getName()
                        + "::currentTimeMillisTwice",
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
            checkInstalledByJeandle(output, "currentTimeMillis");
            checkInstalledByJeandle(output, "currentTimeMillisTwice");
        } else {
            output.shouldNotContain(INTRINSIC_LOG);
        }

        FileCheck checker = new FileCheck(dumpPath.toString(),
                TestMethods.class.getDeclaredMethod("currentTimeMillis"), false);
        if (intrinsicEnabled) {
            checker.checkPattern("call i64 @os_javaTimeMillis\\(\\)");
        } else {
            checker.checkNotPattern("call i64 @os_javaTimeMillis\\(\\)");
        }
        FileCheck twice = new FileCheck(dumpPath.toString(),
                TestMethods.class.getDeclaredMethod("currentTimeMillisTwice"), true);
        if (intrinsicEnabled) {
            twice.checkPattern("call i64 @os_javaTimeMillis\\(\\)");
            twice.checkPattern("call i64 @os_javaTimeMillis\\(\\)");
        } else {
            twice.checkNotPattern("call i64 @os_javaTimeMillis\\(\\)");
        }
    }

    private static void checkInstalledByJeandle(OutputAnalyzer output, String method) {
        output.shouldMatch("(?s).*Jeandle:.*" +
                Pattern.quote(TestMethods.class.getName() + "::" + method) + ".*");
    }

    private static void runSemantics() {
        for (int i = 0; i < 20_000; i++) {
            Asserts.assertGT(TestMethods.currentTimeMillis(), 0L);
        }
    }

    static class TestMethods {
        static long currentTimeMillis() {
            return System.currentTimeMillis();
        }

        static long currentTimeMillisTwice() {
            return System.currentTimeMillis() - System.currentTimeMillis();
        }

        public static void main(String[] args) {
            // Ensure System is initialized before the compile-only wrapper is first invoked.
            System.getProperty("java.version");
            runSemantics();
            currentTimeMillisTwice();
        }
    }
}
