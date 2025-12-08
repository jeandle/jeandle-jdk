/*
 * Copyright (c) 2025, the Jeandle-JDK Authors. All Rights Reserved.
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

/**
 * @test
 * @summary Verify that Jeandle time tracing works with CITime on release builds
 * @library /test/lib
 * @run driver TestTimeTracing testCITime
 */

/**
 * @test
 * @summary Verify that Jeandle time tracing works with CITimeEach/CITimeVerbose on debug builds
 * @requires vm.debug
 * @library /test/lib
 * @run driver TestTimeTracing testCITimeVerbose
 */

/**
 * @test
 * @summary Verify that Jeandle time tracing works with all CITime options on debug builds
 * @requires vm.debug
 * @library /test/lib
 * @run driver TestTimeTracing testCITimeAll
 */

import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;
import java.util.ArrayList;
import java.util.List;

public class TestTimeTracing {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            throw new RuntimeException("Test mode not specified");
        }

        String testMode = args[0];
        switch (testMode) {
            case "testCITime":
                testCITime();
                break;
            case "testCITimeVerbose":
                testCITimeVerbose();
                break;
            case "testCITimeAll":
                testCITimeAll();
                break;
            case "runTest":
                runTest();
                break;
            default:
                throw new RuntimeException("Unknown test mode: " + testMode);
        }
    }

    /**
     * Test CITime on release builds.
     * Verifies the aggregated Jeandle compilation time statistics.
     */
    private static void testCITime() throws Exception {
        ArrayList<String> commandArgs = new ArrayList<>(List.of(
                "-Xcomp",
                "-XX:-TieredCompilation",
                "-XX:+UseJeandleCompiler",
                "-XX:CompileCommand=compileonly,TestTimeTracing::fibonacci",
                "-XX:CompileCommand=compileonly,TestTimeTracing::add",
                "-XX:CompileCommand=compileonly,TestTimeTracing::subtraction",
                "-XX:+CITime",
                TestTimeTracing.class.getName(),
                "runTest"
        ));

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(commandArgs);
        OutputAnalyzer output = ProcessTools.executeCommand(pb);

        output.shouldHaveExitValue(0);
        output.shouldContain("TEST PASSED");

        output.shouldMatch(".*Jeandle\\s*\\{.*speed:\\s*\\d+\\.\\d+\\s*bytes/s.*");
        output.shouldMatch(".*Jeandle Compile Time\\s*:\\s*\\d+\\.\\d+\\s*s.*");
        output.shouldMatch(".*Abstract Interpret\\s*:\\s*\\d+\\.\\d+\\s*s.*");
        output.shouldMatch(".*LLVM Optimize\\s*:\\s*\\d+\\.\\d+\\s*s.*");
        output.shouldMatch(".*LLVM CodeGen\\s*:\\s*\\d+\\.\\d+\\s*s.*");
        output.shouldMatch(".*Finalize\\s*:\\s*\\d+\\.\\d+\\s*s.*");
        output.shouldMatch(".*\\(Jeandle compilations:\\s*3\\).*");
    }

    /**
     * Test CITimeEach and CITimeVerbose on debug builds.
     * Verifies per-compilation phase timing and speed statistics.
     */
    private static void testCITimeVerbose() throws Exception {
        ArrayList<String> commandArgs = new ArrayList<>(List.of(
                "-Xcomp",
                "-XX:-TieredCompilation",
                "-XX:+UseJeandleCompiler",
                "-XX:CompileCommand=compileonly,TestTimeTracing::fibonacci",
                "-XX:CompileCommand=compileonly,TestTimeTracing::add",
                "-XX:CompileCommand=compileonly,TestTimeTracing::subtraction",
                "-XX:+CITimeEach",
                "-XX:+CITimeVerbose",
                TestTimeTracing.class.getName(),
                "runTest"
        ));

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(commandArgs);
        OutputAnalyzer output = ProcessTools.executeCommand(pb);

        output.shouldHaveExitValue(0);
        output.shouldContain("TEST PASSED");

        output.shouldMatch(".*\\[Jeandle Abstract Interpret,\\s*\\d+\\.\\d+\\s*secs\\].*");
        output.shouldMatch(".*\\[Jeandle LLVM Optimize,\\s*\\d+\\.\\d+\\s*secs\\].*");
        output.shouldMatch(".*\\[Jeandle LLVM CodeGen,\\s*\\d+\\.\\d+\\s*secs\\].*");
        output.shouldMatch(".*\\[Jeandle Finalize,\\s*\\d+\\.\\d+\\s*secs\\].*");
        output.shouldMatch(".*\\[Jeandle Compile,\\s*\\d+\\.\\d+\\s*secs\\].*");
        output.shouldMatch(".*\\d+\\s+seconds:\\s+\\d+\\.\\d+\\s+bytes/sec\\s*:.*bytes.*inlined.*");
    }

    /**
     * Test all CITime options together on debug builds.
     * Verifies both aggregated statistics and per-compilation details.
     */
    private static void testCITimeAll() throws Exception {
        ArrayList<String> commandArgs = new ArrayList<>(List.of(
                "-Xcomp",
                "-XX:-TieredCompilation",
                "-XX:+UseJeandleCompiler",
                "-XX:CompileCommand=compileonly,TestTimeTracing::fibonacci",
                "-XX:CompileCommand=compileonly,TestTimeTracing::add",
                "-XX:CompileCommand=compileonly,TestTimeTracing::subtraction",
                "-XX:+CITime",
                "-XX:+CITimeEach",
                "-XX:+CITimeVerbose",
                TestTimeTracing.class.getName(),
                "runTest"
        ));

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(commandArgs);
        OutputAnalyzer output = ProcessTools.executeCommand(pb);

        output.shouldHaveExitValue(0);
        output.shouldContain("TEST PASSED");

        output.shouldMatch(".*\\[Jeandle Abstract Interpret,\\s*\\d+\\.\\d+\\s*secs\\].*");
        output.shouldMatch(".*\\[Jeandle LLVM Optimize,\\s*\\d+\\.\\d+\\s*secs\\].*");
        output.shouldMatch(".*\\[Jeandle LLVM CodeGen,\\s*\\d+\\.\\d+\\s*secs\\].*");
        output.shouldMatch(".*\\[Jeandle Finalize,\\s*\\d+\\.\\d+\\s*secs\\].*");
        output.shouldMatch(".*\\[Jeandle Compile,\\s*\\d+\\.\\d+\\s*secs\\].*");
        output.shouldMatch(".*\\d+\\s+seconds:\\s+\\d+\\.\\d+\\s+bytes/sec\\s*:.*bytes.*inlined.*");
        output.shouldMatch(".*Jeandle\\s*\\{.*speed:\\s*\\d+\\.\\d+\\s*bytes/s.*");

        output.shouldMatch(".*Jeandle Compile Time\\s*:\\s*\\d+\\.\\d+\\s*s.*");
        output.shouldMatch(".*Abstract Interpret\\s*:\\s*\\d+\\.\\d+\\s*s.*");
        output.shouldMatch(".*LLVM Optimize\\s*:\\s*\\d+\\.\\d+\\s*s.*");
        output.shouldMatch(".*LLVM CodeGen\\s*:\\s*\\d+\\.\\d+\\s*s.*");
        output.shouldMatch(".*Finalize\\s*:\\s*\\d+\\.\\d+\\s*s.*");
        output.shouldMatch(".*\\(Jeandle compilations:\\s*3\\).*");
    }

    /**
     * The actual test logic that runs in the subprocess.
     */
    private static void runTest() {
        int fib = 0;
        for (int i = 0; i < 10; i++) {
            fib = fibonacci(i);
        }
        int sum = add();
        int sub = subtraction();

        int expectedFib = 34;
        int expectedSum = 499500;
        int expectedSub = 0;

        if (fib != expectedFib || sum != expectedSum || sub != expectedSub) {
            throw new RuntimeException(
                String.format("Test failed: fib=%d (expected %d), " +
                             "sum=%d (expected %d), sub=%d (expected %d)",
                             fib, expectedFib, sum, expectedSum, sub, expectedSub));
        }
        System.out.println("TEST PASSED");
    }

    public static int fibonacci(int n) {
        if (n == 0) {
            return 0;
        } else if (n == 1) {
            return 1;
        } else {
            return fibonacci(n - 1) + fibonacci(n - 2);
        }
    }

    public static int add() {
        int total = 0;
        for (int i = 1; i < 1000; i++) {
            total = total + i;
        }
        return total;
    }

    public static int subtraction() {
        int total = 1000;
        while (total > 0) {
            total = total - 1;
        }
        return total;
    }
}
