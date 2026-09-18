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
 * @summary Test that Jeandle uncommon traps converge after trap profiling records a recompile
 * @requires vm.debug
 * @library /test/lib
 * @run driver compiler.jeandle.deoptimize.TestUncommonTrapConvergence
 */

package compiler.jeandle.deoptimize;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestUncommonTrapConvergence {
    private static final int ROUNDS = 6;
    private static final String CHECKED_INSTALL =
            "(?m)^.*\\[nmethod,install\\].*Installing method \\(4\\) " +
            Pattern.quote(TestUncommonTrapConvergence.class.getName()) +
            "\\.checked\\(II\\)I\\s*$";
    private static volatile int sink;

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("worker")) {
            runWorker();
            return;
        }

        OutputAnalyzer profiled = runChild("-XX:+ProfileTraps");
        int profiledCompilations = countMatches(profiled.getOutput(),
                "(?m)^.*Jeandle:\\s+\\d+\\s+b\\s+.*::checked \\(6 bytes\\)$");
        int profiledInstalls = countMatches(profiled.getOutput(), CHECKED_INSTALL);
        int profiledTraps = countMatches(profiled.getOutput(),
                "(?m)^.*Jeandle:\\s+\\d+.*::checked \\(6 bytes\\)\\s+made not entrant$");
        if (profiledCompilations < 2) {
            throw new RuntimeException("Expected the Jeandle method to be compiled and recompiled, found " +
                                       profiledCompilations + " compilations");
        }
        if (profiledInstalls < 2) {
            throw new RuntimeException("Expected the Jeandle method to be installed and reinstalled, found " +
                                       profiledInstalls + " installations");
        }
        if (profiledTraps != 1) {
            throw new RuntimeException("Expected one profiled range-check trap, found " + profiledTraps);
        }

        OutputAnalyzer unprofiled = runChild("-XX:-ProfileTraps");
        int unprofiledCompilations = countMatches(unprofiled.getOutput(),
                "(?m)^.*Jeandle:\\s+\\d+\\s+b\\s+.*::checked \\(6 bytes\\)$");
        int unprofiledInstalls = countMatches(unprofiled.getOutput(), CHECKED_INSTALL);
        int unprofiledTraps = countMatches(unprofiled.getOutput(),
                "(?m)^.*Jeandle:\\s+\\d+.*::checked \\(6 bytes\\)\\s+made not entrant$");
        if (unprofiledCompilations < ROUNDS) {
            throw new RuntimeException("Expected repeated Jeandle compilations without profiling, found " +
                                       unprofiledCompilations + " compilations");
        }
        if (unprofiledInstalls < ROUNDS) {
            throw new RuntimeException("Expected repeated Jeandle installations without profiling, found " +
                                       unprofiledInstalls);
        }
        if (unprofiledTraps < ROUNDS - 1) {
            throw new RuntimeException("Expected repeated range-check traps without profiling, found " +
                                       unprofiledTraps);
        }
    }

    private static OutputAnalyzer runChild(String profileTrapsOption) throws Exception {
        ProcessBuilder processBuilder = ProcessTools.createLimitedTestJavaProcessBuilder(
                "-Xbatch",
                "-XX:-BackgroundCompilation",
                "-XX:-TieredCompilation",
                "-XX:+UseJeandleCompiler",
                "-XX:+UnlockDiagnosticVMOptions",
                "-XX:+CIPrintCompilerName",
                "-XX:CompileThreshold=100",
                profileTrapsOption,
                "-Xlog:deoptimization=debug,jit+compilation=debug,nmethod+install=info",
                "-XX:CompileCommand=compileonly," + TestUncommonTrapConvergence.class.getName() + "::checked",
                TestUncommonTrapConvergence.class.getName(),
                "worker");
        OutputAnalyzer output = ProcessTools.executeCommand(processBuilder);
        output.shouldHaveExitValue(0);
        return output;
    }

    private static int countMatches(String output, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(output);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static int checked(int index, int length) {
        return Objects.checkIndex(index, length);
    }

    private static void runWorker() {
        for (int round = 0; round < ROUNDS; round++) {
            for (int i = 0; i < 20_000; i++) {
                sink += checked(i & 7, 8);
            }
            try {
                checked(-1, 8);
                throw new RuntimeException("Expected IndexOutOfBoundsException");
            } catch (IndexOutOfBoundsException expected) {
            }
        }
        System.out.println("sink=" + sink);
    }
}
