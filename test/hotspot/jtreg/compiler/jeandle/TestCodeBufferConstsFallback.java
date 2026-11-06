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

/**
 * @test
 * @summary A const-section planner failure degrades to the legacy 48 KiB
 *          reservation instead of failing the compilation.
 *
 *          JeandleForceConstPlanFallback forces the planner to report a
 *          failure. The expected behaviour is a purely observable capacity
 *          regression - every finalized method reserves the legacy
 *          6144 * wordSize consts block again - while the compiled code stays
 *          correct. The workload run in the child VM compares every
 *          Jeandle-compiled result against an interpreted copy, so a fallback
 *          that mis-emits constants fails the test rather than merely
 *          wasting memory.
 *
 * @requires vm.debug == true
 * @library /test/lib
 * @build compiler.jeandle.TestCodeBufferConstValues
 * @run driver compiler.jeandle.TestCodeBufferConstsFallback
 */

package compiler.jeandle;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestCodeBufferConstsFallback {

    public static void main(String[] args) throws Exception {
        ProcessBuilder pb = ProcessTools.createTestJavaProcessBuilder(
                "-XX:+UnlockDiagnosticVMOptions",
                "-XX:+UseJeandleCompiler",
                "-XX:+JeandleCodeBufferInstrument",
                "-XX:+JeandleForceConstPlanFallback",
                "-Xbatch",
                // Same restriction as in TestCodeBufferConstsExactAlloc, so the
                // two runs differ only by the forced planner failure.
                "-XX:CompileCommand=compileonly,compiler.jeandle.TestCodeBufferConstValues::computeDouble",
                "-XX:CompileCommand=compileonly,compiler.jeandle.TestCodeBufferConstValues::computeFloat",
                "-XX:CompileCommand=compileonly,compiler.jeandle.TestCodeBufferConstValues::computeOopReloc",
                "-cp", System.getProperty("java.class.path"),
                "compiler.jeandle.TestCodeBufferConstValues");

        OutputAnalyzer out = new OutputAnalyzer(pb.start());
        // The child compares compiled results against interpreted ones, so a
        // zero exit already means the fallback emitted correct constants.
        out.shouldHaveExitValue(0);
        String stdout = out.getStdout();

        long methods = summaryLong(stdout, "Methods reaching finalize");
        long exact   = summaryLong(stdout, "Exact path (from ConstSectionPlan)");
        long legacy  = summaryLong(stdout, "Legacy path (48 KiB default)");

        Asserts.assertGT(methods, 0L, "no method reached finalize()");
        Asserts.assertEQ(exact, 0L, "with the planner forced to fail no method may take the exact path");
        Asserts.assertEQ(legacy, methods,
                "with the planner forced to fail every method must use the legacy reserve");

        // The degradation is measurable and is the only visible effect.
        long usage    = instrumentationLong(stdout, "usage", "bytes /");
        long capacity = instrumentationLong(stdout, "/ capacity", "bytes,");
        Asserts.assertGT(usage, 0L, "the workload must emit at least one const byte");
        Asserts.assertGT(capacity, usage,
                "legacy reserve must over-allocate relative to the emitted consts");

        // ... and it must not have triggered a consts expansion either.
        for (String line : stdout.split("\\R")) {
            if (line.contains("Expand:") && line.contains("consts ")) {
                String rest = line.substring(line.indexOf("consts ") + "consts ".length());
                int comma = rest.indexOf(',');
                Asserts.assertEQ(Integer.parseInt(rest.substring(0, comma).trim()), 0,
                        "no consts expansion expected on the legacy path");
            }
        }
    }

    private static long summaryLong(String stdout, String key) {
        for (String line : stdout.split("\\R")) {
            int idx = line.indexOf(key + ":");
            if (idx >= 0) {
                return Long.parseLong(line.substring(idx + key.length() + 1).trim());
            }
        }
        throw new RuntimeException("missing layout summary key: " + key);
    }

    private static long instrumentationLong(String stdout, String before, String after) {
        for (String line : stdout.split("\\R")) {
            if (!line.contains("Consts:")) {
                // Only the "Consts: usage N bytes / capacity N bytes, waste X%" line
                // is meant here. The summary also prints a
                // "Locs elements: used N / capacity N" line that reuses the
                // "/ capacity" token; without this anchor the lookup would match it
                // and fail with "malformed consts line".
                continue;
            }
            int idx = line.indexOf(before);
            if (idx >= 0) {
                String rest = line.substring(idx + before.length());
                int end = rest.indexOf(after);
                if (end < 0) {
                    throw new RuntimeException("malformed consts line: " + line);
                }
                return Long.parseLong(rest.substring(0, end).trim());
            }
        }
        throw new RuntimeException("missing instrumentation key: " + before);
    }
}
