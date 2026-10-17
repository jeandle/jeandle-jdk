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

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @test
 * @summary Verify Jeandle exact stubs section capacity modeling, handler bounds, and zero expansion
 * @library /test/lib
 * @requires vm.compiler2.enabled
 * @run driver compiler.jeandle.TestCodeBufferStubsStats
 */
public class TestCodeBufferStubsStats {

    private static final Pattern STUBS_PLAN_LINE = Pattern.compile(
        "\\[JeandleStubsPlan\\]\\s+method=(\\S+)\\s+planned_stubs=(\\d+)\\s+actual_stubs=(\\d+)\\s+legacy_stubs=(\\d+)\\s+static_calls=(\\d+)\\s+external_calls=(\\d+)\\s+has_mh=(\\d+)\\s+saved_stubs=(\\d+)"
    );

    // --- Workload methods covering diverse call-site densities and handlers ---

    // 1. Pure computation, 0 calls
    public static int testNoCalls(int a, int b) {
        return (a * 31) ^ (b * 17) + 42;
    }

    // Target for static invocations
    public static int targetStub(int x) {
        return x + 1;
    }

    // 2. Single static call
    public static int testSingleCall(int x) {
        return targetStub(x);
    }

    // 3. 12 static calls - in legacy 160B model, this triggers buffer expansion (>160B)
    public static int testManyCalls(int x) {
        int r = targetStub(x);
        r = targetStub(r);
        r = targetStub(r);
        r = targetStub(r);
        r = targetStub(r);
        r = targetStub(r);
        r = targetStub(r);
        r = targetStub(r);
        r = targetStub(r);
        r = targetStub(r);
        r = targetStub(r);
        r = targetStub(r);
        return r;
    }

    // 4. Exception handler stress
    public static int testExceptionHandler(int x) {
        try {
            if (x < 0) {
                throw new IllegalArgumentException("negative: " + x);
            }
            return targetStub(x);
        } catch (IllegalArgumentException e) {
            return -1;
        } catch (Exception e) {
            return -2;
        }
    }

    // 5. MethodHandle invocation
    private static MethodHandle MH_TARGET;
    static {
        try {
            MH_TARGET = MethodHandles.lookup().findStatic(
                TestCodeBufferStubsStats.class, "targetStub",
                MethodType.methodType(int.class, int.class)
            );
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public static int testMethodHandle(int x) throws Throwable {
        return (int) MH_TARGET.invokeExact(x);
    }

    public static void main(String[] args) throws Throwable {
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
                "-XX:CompileCommand=dontinline,compiler.jeandle.TestCodeBufferStubsStats::targetStub",
                "-XX:CompileCommand=compileonly,compiler.jeandle.TestCodeBufferStubsStats::testNoCalls",
                "-XX:CompileCommand=compileonly,compiler.jeandle.TestCodeBufferStubsStats::targetStub",
                "-XX:CompileCommand=compileonly,compiler.jeandle.TestCodeBufferStubsStats::testSingleCall",
                "-XX:CompileCommand=compileonly,compiler.jeandle.TestCodeBufferStubsStats::testManyCalls",
                "-XX:CompileCommand=compileonly,compiler.jeandle.TestCodeBufferStubsStats::testExceptionHandler",
                "-XX:CompileCommand=compileonly,compiler.jeandle.TestCodeBufferStubsStats::testMethodHandle",
                "-cp", System.getProperty("java.class.path"),
                "compiler.jeandle.TestCodeBufferStubsStats",
                "--workload"
        );

        OutputAnalyzer out = new OutputAnalyzer(pb.start());
        out.shouldHaveExitValue(0);
        String stdout = out.getStdout();

        int parsedMethods = 0;
        boolean foundManyCalls = false;
        boolean foundZeroCalls = false;

        Matcher matcher = STUBS_PLAN_LINE.matcher(stdout);
        while (matcher.find()) {
            String method = matcher.group(1);
            long plannedStubs = Long.parseLong(matcher.group(2));
            long actualStubs = Long.parseLong(matcher.group(3));
            long legacyStubs = Long.parseLong(matcher.group(4));
            long staticCalls = Long.parseLong(matcher.group(5));
            long externalCalls = Long.parseLong(matcher.group(6));
            int hasMh = Integer.parseInt(matcher.group(7));
            long savedStubs = Long.parseLong(matcher.group(8));

            parsedMethods++;

            // Invariant 1: Actual stubs must NEVER exceed planned upper bound
            Asserts.assertLTE(actualStubs, plannedStubs,
                    "Method " + method + ": actual stubs " + actualStubs
                    + " exceeded planned bound " + plannedStubs);

            // Invariant 2: For 0 calls, planned stubs should be small (handlers only)
            if (staticCalls == 0) {
                foundZeroCalls = true;
                Asserts.assertLTE(actualStubs, 32L,
                        "Method " + method + ": 0-call stubs should fit in 32B");
            }

            // Invariant 3: For many calls (>8 calls), planned stubs dynamically scales beyond 160B
            if (staticCalls >= 12) {
                foundManyCalls = true;
                Asserts.assertGT(plannedStubs, legacyStubs,
                        "Method " + method + ": high call-site density must plan > legacy 160B");
            }
        }

        Asserts.assertGT(parsedMethods, 0, "No [JeandleStubsPlan] telemetry was parsed");
        Asserts.assertTrue(foundZeroCalls, "Should have observed 0-call method");
        Asserts.assertTrue(foundManyCalls, "Should have observed many-calls method with planned > 160B");

        System.out.println("TestCodeBufferStubsStats PASSED: verified " + parsedMethods
                + " compiled methods with strict upper-bound invariant preservation.");
    }

    private static void runWorkload() throws Throwable {
        int r0 = testNoCalls(10, 20);
        int r1 = testSingleCall(5);
        int r2 = testManyCalls(1);
        int r3 = testExceptionHandler(42);
        int r4 = testExceptionHandler(-5);
        int r5 = testMethodHandle(99);
        if (r0 == 0 || r1 == 0 || r2 == 0 || r3 == 0 || r4 != -1 || r5 == 0) {
            throw new RuntimeException("Unexpected workload execution result");
        }
    }
}
