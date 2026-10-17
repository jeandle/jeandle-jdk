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

package compiler.jeandle;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @test
 * @summary Verify Jeandle exact instruction section (insts) capacity modeling and prolog upper bound
 * @library /test/lib
 * @requires vm.compiler2.enabled
 * @run driver compiler.jeandle.TestCodeBufferInstsProlog
 */
public class TestCodeBufferInstsProlog {

    private static final Pattern INSTS_PLAN_LINE = Pattern.compile(
        "\\[JeandleInstsPlan\\]\\s+method=(\\S+)\\s+planned_prolog=(\\d+)\\s+actual_prolog=(\\d+)\\s+text_size=(\\d+)\\s+insts_payload=(\\d+)\\s+legacy_insts=(\\d+)\\s+saved=(\\d+)"
    );

    // --- Workload methods covering diverse prolog shapes ---

    public static int computeStatic(int a, int b) {
        return a * 31 + b * 17 + 42;
    }

    public int computeVirtual(int x) {
        return x ^ 0x5a5a5a5a;
    }

    public static int computeOSR(int limit) {
        int sum = 0;
        for (int i = 0; i < limit; i++) {
            sum += (i * 3) ^ (i >> 1);
        }
        return sum;
    }

    // Deep stack frame method with many locals to stress stack overflow bang
    public static long computeDeepFrame(long a, long b, long c, long d, long e, long f, long g, long h) {
        long v1 = a + b;
        long v2 = c + d;
        long v3 = e + f;
        long v4 = g + h;
        long v5 = v1 * 3 + v2;
        long v6 = v3 * 7 + v4;
        long v7 = v5 ^ v6;
        long v8 = v7 + 100;
        return v1 + v2 + v3 + v4 + v5 + v6 + v7 + v8;
    }

    static class ClinitTarget {
        static final int CONSTANT_VAL = computeStatic(10, 20);
        static int dynamicVal = 99;
        static {
            dynamicVal = CONSTANT_VAL + 1;
        }
    }

    public static int computeClinitAccess() {
        return ClinitTarget.dynamicVal;
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--workload")) {
            runWorkload();
            return;
        }

        // Subprocess under test with Jeandle compiler and telemetry enabled
        ProcessBuilder pb = ProcessTools.createTestJavaProcessBuilder(
                "-XX:+UnlockDiagnosticVMOptions",
                "-XX:+UnlockExperimentalVMOptions",
                "-XX:+UseJeandleCompiler",
                "-XX:+JeandleCodeBufferInstrument",
                "-Xcomp",
                "-XX:CompileCommand=compileonly,compiler.jeandle.TestCodeBufferInstsProlog::computeStatic",
                "-XX:CompileCommand=compileonly,compiler.jeandle.TestCodeBufferInstsProlog::computeVirtual",
                "-XX:CompileCommand=compileonly,compiler.jeandle.TestCodeBufferInstsProlog::computeOSR",
                "-XX:CompileCommand=compileonly,compiler.jeandle.TestCodeBufferInstsProlog::computeDeepFrame",
                "-XX:CompileCommand=compileonly,compiler.jeandle.TestCodeBufferInstsProlog::computeClinitAccess",
                "-cp", System.getProperty("java.class.path"),
                "compiler.jeandle.TestCodeBufferInstsProlog",
                "--workload"
        );

        OutputAnalyzer out = new OutputAnalyzer(pb.start());
        out.shouldHaveExitValue(0);
        String stdout = out.getStdout();

        int parsedMethods = 0;
        long totalSaved = 0;

        Matcher matcher = INSTS_PLAN_LINE.matcher(stdout);
        while (matcher.find()) {
            String method = matcher.group(1);
            long plannedProlog = Long.parseLong(matcher.group(2));
            long actualProlog = Long.parseLong(matcher.group(3));
            long textSize = Long.parseLong(matcher.group(4));
            long instsPayload = Long.parseLong(matcher.group(5));
            long legacyInsts = Long.parseLong(matcher.group(6));
            long saved = Long.parseLong(matcher.group(7));

            parsedMethods++;
            totalSaved += saved;

            // Invariant 1: Actual prolog must NEVER exceed planned upper bound
            Asserts.assertLTE(actualProlog, plannedProlog,
                    "Method " + method + ": actual prolog " + actualProlog
                    + " exceeded planned bound " + plannedProlog);

            // Invariant 2: Capacity must strictly contain text size + actual prolog
            Asserts.assertGTE(instsPayload, textSize + actualProlog,
                    "Method " + method + ": insts payload " + instsPayload
                    + " cannot fit text (" + textSize + ") + prolog (" + actualProlog + ")");

            // Invariant 3: Tightened reservation must save significant space vs legacy 2048
            Asserts.assertGT(saved, 1500L,
                    "Method " + method + ": expected >1500 bytes saved vs legacy 2048, but got " + saved);
        }

        Asserts.assertGT(parsedMethods, 0, "No [JeandleInstsPlan] telemetry was parsed");
        System.out.println("TestCodeBufferInstsProlog PASSED: verified " + parsedMethods
                + " compiled methods, total insts slack eliminated: " + totalSaved + " bytes.");
    }

    private static void runWorkload() {
        TestCodeBufferInstsProlog instance = new TestCodeBufferInstsProlog();
        int r1 = computeStatic(5, 10);
        int r2 = instance.computeVirtual(42);
        int r3 = computeOSR(1000);
        long r4 = computeDeepFrame(1, 2, 3, 4, 5, 6, 7, 8);
        int r5 = computeClinitAccess();
        if (r1 == 0 || r2 == 0 || r3 == 0 || r4 == 0 || r5 == 0) {
            throw new RuntimeException("Unexpected zero result");
        }
    }
}
