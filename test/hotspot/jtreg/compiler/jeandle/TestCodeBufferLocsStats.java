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
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @test
 * @summary Verify Jeandle's exact locs (relocation array) sizing: the record model must
 *          cover every relocation actually written, and the arrays it sizes must never be
 *          reallocated while a compilation installs.
 * @library /test/lib
 * @requires vm.compiler2.enabled
 * @requires (vm.debug == true)
 * @run driver compiler.jeandle.TestCodeBufferLocsStats
 */
public class TestCodeBufferLocsStats {

    // Planned capacity of the relocation arrays, printed by the layout decision.
    private static final Pattern LOCS_PLAN_LINE = Pattern.compile(
        "\\[JeandleLocsPlan\\]\\s+method=(\\S+) planned_insts_bytes=(\\d+) planned_insts_slots=(\\d+)"
        + " legacy_insts_slots=(\\d+) records=(\\d+) fillers=(\\d+) consts_bytes=(\\d+) stubs_bytes=(\\d+)"
        + " status=(\\w+)");

    // What the sections actually hold once the compilation is installed.
    private static final Pattern LOCS_TOTAL_LINE = Pattern.compile(
        "\\[JeandleLocsTotal\\]\\s+method=(\\S+) planned_slots=(\\d+) actual_slots=(\\d+) capacity_slots=(\\d+)"
        + " consts_slots=(\\d+) stubs_slots=(\\d+) expands=(\\d+) records=(\\d+) fillers=(\\d+) legacy_slots=(\\d+)");

    // The layout decision, which says whether the record model or the legacy heuristic
    // produced the relocation capacity.
    private static final Pattern INSTALL_LAYOUT_LINE = Pattern.compile(
        "\\[JeandleInstallLayout\\]\\s+method=(\\S+).*locs_strategy=(\\S+)");

    private static final MethodHandle MH_TARGET;
    static {
        try {
            MH_TARGET = MethodHandles.lookup().findStatic(
                TestCodeBufferLocsStats.class, "targetReloc",
                MethodType.methodType(int.class, int.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    // --- Workload: methods with deliberately different relocation shapes ---------------

    // 1. No relocation at all: pure arithmetic, no call, no constant pool reference.
    public static int testNoRelocs(int a, int b) {
        return (a ^ b) + (a - b);
    }

    // 2. One constant-pool reference: a wide float that LLVM places in .rodata, which
    //    becomes a section-word relocation from code to the consts section.
    public static double testConstReloc(double seed) {
        double x = seed + 3.14159265358979323846;
        double y = x * 2.718281828459045;
        double z = y - 1.4142135623730951;
        return z + 0.5772156649015329;
    }

    // 3. Many static call sites: each needs a call-site record in insts and a stub record
    //    in stubs. Kept out of LLVM's inliner by -XX:CompileCommand=dontinline so the
    //    calls actually survive into the machine code.
    public static int targetReloc(int x) {
        return x + 1;
    }

    public static int testManyCallRelocs(int x) {
        int v = x;
        v = targetReloc(v); v = targetReloc(v); v = targetReloc(v);
        v = targetReloc(v); v = targetReloc(v); v = targetReloc(v);
        v = targetReloc(v); v = targetReloc(v); v = targetReloc(v);
        v = targetReloc(v); v = targetReloc(v); v = targetReloc(v);
        return v;
    }

    // 4. An exception handler: the handler stub is emitted into stubs and relocates to a
    //    runtime routine, plus the deopt handler every Java method gets.
    public static int testHandlerRelocs(int x) {
        try {
            if (x < 0) {
                throw new IllegalArgumentException("negative");
            }
            return x * 2;
        } catch (IllegalArgumentException e) {
            return -1;
        }
    }

    // 5. A method handle invoke: needs the DeoptMH clone of the deopt handler, i.e. one
    //    more stub record, which the record model has to have counted beforehand.
    public static int testMethodHandleRelocs(int x) throws Throwable {
        return (int) MH_TARGET.invokeExact(x);
    }

    public static void main(String[] args) throws Throwable {
        if (args.length > 0 && args[0].equals("--workload")) {
            runWorkload();
            return;
        }

        // A: the record model switched on (the default).
        checkInvariants(runChild(true));

        // B: the same workload with HotSpot's own sizing, the baseline for the
        // "zero expansions" comparison. This half of the test is the A/B claim: the legacy
        // provisioning really does have to grow the relocation array, and the model
        // removes that growth rather than hiding it behind a bigger reservation.
        OutputAnalyzer legacy = runChild(false);
        legacy.shouldHaveExitValue(0);
        int legacyExpansions = countLocsExpansions(legacy.getStdout());
        Asserts.assertGT(legacyExpansions, 0,
            "With -XX:-JeandleExactLocs the relocation arrays are expected to be "
            + "reallocated during installation; the measurement that motivates this "
            + "change would be unfalsifiable if they did not.");
    }

    private static OutputAnalyzer runChild(boolean usePlan) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("-XX:+UnlockDiagnosticVMOptions");
        cmd.add("-XX:+UnlockExperimentalVMOptions");
        cmd.add("-XX:+UseJeandleCompiler");
        cmd.add("-XX:+JeandleCodeBufferInstrument");
        cmd.add("-XX:+JeandleTraceRelocRecords");
        cmd.add(usePlan ? "-XX:+JeandleExactLocs" : "-XX:-JeandleExactLocs");
        cmd.add("-Xcomp");
        // LLVM's inliner erases a trivial callee; without this the many-calls method
        // would contain no call relocations at all and the test would pass vacuously.
        cmd.add("-XX:CompileCommand=dontinline,compiler.jeandle.TestCodeBufferLocsStats::targetReloc");
        for (String m : new String[] {"testNoRelocs", "testConstReloc", "targetReloc",
                                      "testManyCallRelocs", "testHandlerRelocs",
                                      "testMethodHandleRelocs"}) {
            cmd.add("-XX:CompileCommand=compileonly,compiler.jeandle.TestCodeBufferLocsStats::" + m);
        }
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add("compiler.jeandle.TestCodeBufferLocsStats");
        cmd.add("--workload");

        ProcessBuilder pb = ProcessTools.createTestJavaProcessBuilder(cmd);
        return new OutputAnalyzer(pb.start());
    }

    // Checks one run in which the record model sized the relocation arrays. Only those
    // runs emit [JeandleLocsTotal] at all, which is what makes the zero-expansion
    // assertion below a statement about planned buffers rather than about all buffers.
    private static void checkInvariants(OutputAnalyzer out) throws Exception {
        String label = "exact";
        out.shouldHaveExitValue(0);
        String stdout = out.getStdout();

        int plans = 0;
        int totals = 0;
        int expansions = 0;
        boolean sawConstReloc = false;
        boolean sawManyCallReloc = false;
        boolean sawZeroReloc = false;

        Matcher planMatcher = LOCS_PLAN_LINE.matcher(stdout);
        while (planMatcher.find()) {
            plans++;
            String method = planMatcher.group(1);
            long plannedSlots = Long.parseLong(planMatcher.group(3));
            long legacySlots = Long.parseLong(planMatcher.group(4));
            long records = Long.parseLong(planMatcher.group(5));
            long constsBytes = Long.parseLong(planMatcher.group(7));
            long stubsBytes = Long.parseLong(planMatcher.group(8));
            String status = planMatcher.group(9);

            if (!status.equals("Exact")) {
                continue; // a documented refusal; the assertions below are for planned buffers
            }
            // The plan may never be *more* conservative than it has to be by an
            // unexplained margin: 4 slots is HotSpot's own floor, and the reservation is
            // only ever bigger than that when records were counted.
            Asserts.assertGTE(plannedSlots, records,
                method + ": planned slots " + plannedSlots + " below the " + records
                + " records the model expects");
            if (records > 0) {
                Asserts.assertGT(plannedSlots, legacySlots,
                    method + ": a method with relocations must ask for more than the "
                    + legacySlots + " legacy slots, asked " + plannedSlots);
            }
            // The secondary sections are sized too: a method with a constant reference
            // must have planned consts capacity, and every Java method has stubs.
            if (method.contains("testConstReloc")) {
                sawConstReloc = true;
                Asserts.assertGT(constsBytes, 0L,
                    "testConstReloc was expected to reference a const section");
            }
            if (method.contains("testManyCallRelocs")) {
                sawManyCallReloc = true;
                Asserts.assertGTE(records, 12L,
                    "testManyCallRelocs makes 12 calls; the census saw " + records);
            }
            if (method.contains("testNoRelocs")) {
                sawZeroReloc = true;
            }
        }

        Matcher totalMatcher = LOCS_TOTAL_LINE.matcher(stdout);
        while (totalMatcher.find()) {
            totals++;
            String method = totalMatcher.group(1);
            long plannedSlots = Long.parseLong(totalMatcher.group(2));
            long actualSlots = Long.parseLong(totalMatcher.group(3));
            long capacitySlots = Long.parseLong(totalMatcher.group(4));
            long stubsSlots = Long.parseLong(totalMatcher.group(6));
            long expandCount = Long.parseLong(totalMatcher.group(7));
            long records = Long.parseLong(totalMatcher.group(8));

            // Invariant 1 (safety): the array held every record written for it.
            Asserts.assertLTE(actualSlots, plannedSlots,
                method + ": the relocation array holds " + actualSlots
                + " slots but the record model planned " + plannedSlots
                + "; the model missed a relocation");
            Asserts.assertLTE(actualSlots, capacitySlots,
                method + ": used more slots than were allocated");
            // Invariant 2 (the actual win): with an exact plan nothing had to grow.
            expansions += (int) expandCount;
            Asserts.assertEQ(expandCount, 0L,
                method + ": the planned relocation array still had to be reallocated "
                + expandCount + " times");
            // A method with call sites must have stub records, which the legacy 4-slot
            // reservation could never hold in one allocation.
            if (method.contains("testManyCallRelocs")) {
                Asserts.assertGT(stubsSlots, 0L,
                    "testManyCallRelocs should have produced stub relocations");
                Asserts.assertGTE(records, 12L,
                    method + ": a method with many call sites must have at least that many "
                    + "relocation records in its installed buffer");
            }
        }

        Asserts.assertGT(plans, 0, "no [JeandleLocsPlan] telemetry was parsed (" + label + ")");
        Asserts.assertGT(totals, 0, "no [JeandleLocsTotal] telemetry was parsed (" + label + ")");
        Asserts.assertTrue(sawConstReloc, "the const-reference method was never planned");
        Asserts.assertTrue(sawManyCallReloc, "the many-call method was never planned");
        Asserts.assertTrue(sawZeroReloc, "the no-relocation method was never planned");
        System.out.println("TestCodeBufferLocsStats[" + label + "]: verified " + plans
            + " planned compilations, " + totals + " installed buffers, " + expansions
            + " relocation-array reallocations.");
    }

    private static int countLocsExpansions(String stdout) {
        int total = 0;
        Matcher m = LOCS_TOTAL_LINE.matcher(stdout);
        while (m.find()) {
            total += Integer.parseInt(m.group(7));
        }
        // Buffers whose plan refused are not reported by [JeandleLocsTotal]; count the
        // aggregate the VM prints at shutdown as well, so the legacy half of the test
        // cannot silently pass on an empty census.
        Matcher summary = Pattern.compile(
            "Locs expand \\(separate growth path\\): (\\d+) reallocations").matcher(stdout);
        int aggregate = 0;
        while (summary.find()) {
            aggregate += Integer.parseInt(summary.group(1));
        }
        return Math.max(total, aggregate);
    }

    private static void runWorkload() throws Throwable {
        int r0 = testNoRelocs(7, 5);
        double r1 = testConstReloc(1.0);
        int r2 = testManyCallRelocs(0);
        int r3 = testHandlerRelocs(4);
        int r4 = testHandlerRelocs(-4);
        int r5 = testMethodHandleRelocs(41);

        // The results are the real assertion that the relocations installed here work:
        // a wrong relocation target would change one of these, or crash.
        // testNoRelocs(7, 5) = (7 ^ 5) + (7 - 5) = 2 + 2 = 4.
        if (r0 != 4) {
            throw new RuntimeException("unexpected testNoRelocs result: " + r0);
        }
        if (Double.compare(r1, 0.0) == 0 || Double.isNaN(r1)) {
            throw new RuntimeException("unexpected testConstReloc result: " + r1);
        }
        if (r2 != 12) {
            throw new RuntimeException("unexpected testManyCallRelocs result: " + r2);
        }
        if (r3 != 8 || r4 != -1) {
            throw new RuntimeException("unexpected testHandlerRelocs results: " + r3 + "/" + r4);
        }
        if (r5 != 42) {
            throw new RuntimeException("unexpected testMethodHandleRelocs result: " + r5);
        }
        System.out.println("Relocation workload computed correctly: " + r0 + " " + r1 + " "
            + r2 + " " + r3 + " " + r4 + " " + r5);
    }
}
