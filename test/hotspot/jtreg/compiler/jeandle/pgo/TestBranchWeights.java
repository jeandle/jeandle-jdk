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
 * @summary MDO BranchData / MultiBranchData are lowered to !prof branch_weights
 *          on conditional branches and switches.
 * @library /test/lib /
 * @build jdk.test.whitebox.WhiteBox compiler.jeandle.fileCheck.FileCheck
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *      -XX:+UseJeandleCompiler -XX:+JeandleUseProfile -XX:+JeandleDumpIR
 *      -XX:CompileCommand=compileonly,compiler.jeandle.pgo.TestBranchWeights::hotIf
 *      -XX:CompileCommand=compileonly,compiler.jeandle.pgo.TestBranchWeights::hotSwitch
 *      -Xlog:compilation*=info
 *      compiler.jeandle.pgo.TestBranchWeights
 */

package compiler.jeandle.pgo;

import compiler.jeandle.fileCheck.FileCheck;
import jdk.test.whitebox.WhiteBox;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class TestBranchWeights {
    private static final WhiteBox WB = WhiteBox.getWhiteBox();
    private static final int TIER4 = 4;  // CompLevel_full_optimization (Jeandle)

    static volatile int sink;

    // Conditional branch: x < 1000 is taken ~99% of the time during warmup, so the
    // MDO BranchData should record a strongly skewed (but two-sided) taken/not_taken ratio.
    static int hotIf(int x) {
        if (x < 1000) {
            return x + 1;
        }
        return x - 1;
    }

    // Dense switch over 0..3 -> tableswitch; MDO MultiBranchData records per-case counts.
    static int hotSwitch(int k) {
        switch (k) {
            case 0:  return 10;
            case 1:  return 11;
            case 2:  return 12;
            case 3:  return 13;
            default: return 99;
        }
    }

    private static void warmup() {
        long acc = 0;
        for (int i = 0; i < 50_000; i++) {
            int x = (i % 100 == 0) ? 5000 : (i % 1000);  // hotIf branch ~1% to the >=1000 side
            acc += hotIf(x);
            acc += hotSwitch(i & 3);                      // exercise every switch case
        }
        sink = (int) acc;
    }

    // Force a fresh tier-4 (Jeandle) compile and wait until its IR dump appears on disk.
    // Waiting on the dump file -- rather than WB.isMethodCompiled / getMethodCompilationLevel
    // -- is race-free: a transient C1 (tier-3) compile, or a stale tier-4 level reading after
    // deoptimizeMethod, would otherwise let the wait fall through before the Jeandle dump exists.
    private static void compileAndAwaitDump(Method m, String dir) throws Exception {
        String prefix = m.getDeclaringClass().getName().replace('.', '_') + "_" + m.getName();
        clearDumps(dir, prefix);
        WB.deoptimizeMethod(m);
        WB.enqueueMethodForCompilation(m, TIER4);
        long start = System.currentTimeMillis();
        long deadline = start + 300_000;
        long nextLog = start + 1_000;
        // The optimized dump is written last, so its presence implies the pre-opt dump exists too.
        while (!dumpPresent(dir, prefix)) {
            long now = System.currentTimeMillis();
            if (now > deadline) {
                int lvl = WB.getMethodCompilationLevel(m);
                // Collect directory snapshot for post-mortem
                java.util.List<String> llFiles = java.util.Collections.emptyList();
                try (java.util.stream.Stream<Path> s = Files.list(Paths.get(dir))) {
                    llFiles = s.map(p -> p.getFileName().toString())
                               .filter(n -> n.endsWith(".ll"))
                               .collect(Collectors.toList());
                } catch (Exception ignored) {}
                throw new RuntimeException("Timeout: no Jeandle IR dump for " + m.getName()
                        + " (final level=" + lvl + ", elapsed=" + (now - start) + "ms"
                        + ", ll-files=" + llFiles + ")");
            }
            if (now >= nextLog) {
                int lvl = WB.getMethodCompilationLevel(m);
                boolean queued = WB.isMethodQueuedForCompilation(m);
                System.out.println("[await-dump] t=" + (now - start) + "ms level=" + lvl
                        + " queued=" + queued + " dumpPresent=" + dumpPresent(dir, prefix));
                nextLog = now + 1_000;
            }
            if (WB.getMethodCompilationLevel(m) != TIER4) {
                WB.enqueueMethodForCompilation(m, TIER4);  // re-enqueue if it raced/dropped
            }
            Thread.sleep(20);
        }
        System.out.println("[await-dump] done in " + (System.currentTimeMillis() - start) + "ms");
    }

    // Parse the first branch_weights node in the method's pre-opt dump and assert the
    // first weight (taken / successor-0) is strictly less than the second (not-taken).
    private static void assertBranchPolarityTakenLess(String dir, Method m) throws Exception {
        String prefix = m.getDeclaringClass().getName().replace('.', '_') + "_" + m.getName();
        Path ll;
        try (Stream<Path> s = Files.list(Paths.get(dir))) {
            ll = s.filter(p -> {
                    String n = p.getFileName().toString();
                    return n.startsWith(prefix) && n.endsWith(".ll") && !n.endsWith("_optimized.ll");
                }).findFirst().orElseThrow(() -> new RuntimeException("no pre-opt dump for " + m.getName()));
        }
        Pattern bw = Pattern.compile("branch_weights\",\\s*i32\\s*(\\d+),\\s*i32\\s*(\\d+)");
        for (String line : Files.readAllLines(ll)) {
            Matcher mm = bw.matcher(line);
            if (mm.find()) {
                long taken = Long.parseLong(mm.group(1));
                long notTaken = Long.parseLong(mm.group(2));
                if (!(taken < notTaken)) {
                    throw new RuntimeException("hotIf branch_weights polarity wrong: taken=" + taken
                        + " not_taken=" + notTaken + " (expected taken << not_taken)");
                }
                return;
            }
        }
        throw new RuntimeException("no branch_weights node found for " + m.getName());
    }

    private static boolean dumpPresent(String dir, String prefix) throws Exception {
        // Await both the pre- and post-opt dumps. The unoptimized .ll is
        // written before optimization, the _optimized.ll after; under high
        // jtreg concurrency the dirent for the unoptimized file can lag.
        try (Stream<Path> s = Files.list(Paths.get(dir))) {
            boolean[] seen = new boolean[2];
            s.forEach(p -> {
                String n = p.getFileName().toString();
                if (!n.startsWith(prefix)) return;
                if (n.endsWith("_optimized.ll")) seen[1] = true;
                else if (n.endsWith(".ll")) seen[0] = true;
            });
            return seen[0] && seen[1];
        }
    }

    private static void clearDumps(String dir, String prefix) throws Exception {
        try (Stream<Path> s = Files.list(Paths.get(dir))) {
            List<Path> dumps = s.filter(p -> {
                String n = p.getFileName().toString();
                return n.startsWith(prefix) && n.endsWith(".ll");
            }).collect(Collectors.toList());
            for (Path p : dumps) {
                Files.deleteIfExists(p);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        warmup();

        Method hotIf = TestBranchWeights.class.getDeclaredMethod("hotIf", int.class);
        Method hotSwitch = TestBranchWeights.class.getDeclaredMethod("hotSwitch", int.class);
        String dir = System.getProperty("user.dir");
        System.out.println("[setup] user.dir=" + dir);
        // Log any pre-existing .ll files (from warmup auto-compilation) to
        // confirm the dump directory is the one Jeandle writes to.
        try (Stream<Path> s = Files.list(Paths.get(dir))) {
            java.util.List<String> pre = s.map(p -> p.getFileName().toString())
                .filter(n -> n.endsWith(".ll")).collect(Collectors.toList());
            System.out.println("[setup] pre-existing .ll files: " + pre);
        }

        compileAndAwaitDump(hotIf, dir);
        FileCheck ifPre = new FileCheck(dir, hotIf, /*optimized=*/false);
        ifPre.checkPattern("define hotspotcc i32 .*TestBranchWeights_hotIf");
        ifPre.checkPattern("br i1 .*!prof !");
        ifPre.checkPattern("branch_weights\", i32 [0-9]+, i32 [0-9]+");
        // `if (x < 1000)` compiles to `if_icmpge <else>`, so successor-0 (the
        // branch-taken edge) is the x>=1000 path, taken ~1% in warmup. weight[0]
        // must be much smaller than weight[1] -- catches a taken/not_taken swap.
        assertBranchPolarityTakenLess(dir, hotIf);
        FileCheck ifOpt = new FileCheck(dir, hotIf, /*optimized=*/true);
        ifOpt.checkPattern("branch_weights\", i32 [0-9]+, i32 [0-9]+");

        compileAndAwaitDump(hotSwitch, dir);
        FileCheck swPre = new FileCheck(dir, hotSwitch, /*optimized=*/false);
        swPre.checkPattern("define hotspotcc i32 .*TestBranchWeights_hotSwitch");
        swPre.checkPattern("switch i32 ");
        // >= 4 integer weights distinguishes a switch's branch_weights from a
        // 2-weight conditional branch.
        swPre.checkPattern("branch_weights\", i32 [0-9]+, i32 [0-9]+, i32 [0-9]+, i32 [0-9]+");

        System.out.println("TestBranchWeights PASSED");
    }
}
