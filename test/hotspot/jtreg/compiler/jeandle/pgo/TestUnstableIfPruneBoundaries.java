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
 * @summary unstable-if prune negative cases: heavily-biased (but
 *          two-sided) branch, switch with cold case, immature profile.
 *          None should be pruned.
 * @library /test/lib /
 * @build jdk.test.whitebox.WhiteBox compiler.jeandle.fileCheck.FileCheck
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *      -XX:+UseJeandleCompiler -XX:+JeandleUseProfile -XX:+JeandleDumpIR
 *      -XX:CompileCommand=compileonly,compiler.jeandle.pgo.TestUnstableIfPruneBoundaries::biasedNotPruned
 *      -XX:CompileCommand=compileonly,compiler.jeandle.pgo.TestUnstableIfPruneBoundaries::switchOneColdCase
 *      -XX:CompileCommand=compileonly,compiler.jeandle.pgo.TestUnstableIfPruneBoundaries::immature
 *      compiler.jeandle.pgo.TestUnstableIfPruneBoundaries
 */

package compiler.jeandle.pgo;

import compiler.jeandle.fileCheck.FileCheck;
import jdk.test.whitebox.WhiteBox;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class TestUnstableIfPruneBoundaries {
    private static final WhiteBox WB = WhiteBox.getWhiteBox();
    private static final int TIER4 = 4;

    static volatile int sink;

    // ~99% taken / ~1% not -- never strict-zero, so the prune must keep both edges.
    static int biasedNotPruned(int x) {
        if (x < 1000) {
            return x + 1;
        }
        return x - 1;
    }

    // case 3 is strict-zero in the MDO; the prune only applies to if_* (do_if_branch),
    // never switch arms.
    static int switchOneColdCase(int k) {
        switch (k) {
            case 0:  return 10;
            case 1:  return 11;
            case 2:  return 12;
            case 3:  return 13;
            default: return 99;
        }
    }

    // Compiled without warmup -> MDO is immature; is_mature() must gate out
    // even though one side is technically unobserved.
    static int immature(int x) {
        if (x < 0) {
            return -1;
        }
        return x;
    }

    private static void warmupBiasedAndSwitch() {
        long acc = 0;
        for (int i = 0; i < 50_000; i++) {
            int x = (i % 100 == 0) ? 5000 : (i % 1000);
            acc += biasedNotPruned(x);
            acc += switchOneColdCase(i % 3);
        }
        sink = (int) acc;
    }

    // Force a fresh tier-4 (Jeandle) compile and wait until its IR dump appears on disk.
    //
    // Design note: we do NOT rely on clearDumps to guarantee freshness because there
    // is a race between the warmup compilation (which writes the pre-opt dump first
    // and the optimized dump second, asynchronously) and our clearDumps call.  In the
    // worst case clearDumps runs between the two writes, deleting only the pre-opt dump
    // while the optimized dump is written afterwards; then dumpPresent can never return
    // true.  Instead we record `since` just before the first deoptimize+enqueue call and
    // only accept dump files whose on-disk mtime >= since.  We also re-deoptimize every
    // 5 s because WB.deoptimizeMethod is not guaranteed to drop the compilation level
    // synchronously for Jeandle-compiled methods; retrying is safe because each new
    // Jeandle compilation writes fresh files with a new timestamp.
    private static void compileAndAwaitDump(Method m, String dir) throws Exception {
        String prefix = m.getDeclaringClass().getName().replace('.', '_') + "_" + m.getName();
        // Give 100 ms of slack so mtime comparisons survive low-resolution fs clocks.
        long since = System.currentTimeMillis() - 100;
        WB.deoptimizeMethod(m);
        WB.enqueueMethodForCompilation(m, TIER4);
        long start = System.currentTimeMillis();
        long deadline = start + 300_000;
        long nextLog = start + 1_000;
        long nextRetry = start + 5_000;
        while (!dumpPresent(dir, prefix, since)) {
            long now = System.currentTimeMillis();
            if (now > deadline) {
                int lvl = WB.getMethodCompilationLevel(m);
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
                        + " queued=" + queued + " dumpPresent=" + dumpPresent(dir, prefix, since));
                nextLog = now + 1_000;
            }
            if (now >= nextRetry) {
                // deoptimize may have been a no-op; force another cycle
                System.out.println("[await-dump] retry deopt+enqueue at t=" + (now - start) + "ms");
                since = System.currentTimeMillis() - 100;
                WB.deoptimizeMethod(m);
                WB.enqueueMethodForCompilation(m, TIER4);
                nextRetry = now + 5_000;
            } else if (WB.getMethodCompilationLevel(m) != TIER4) {
                WB.enqueueMethodForCompilation(m, TIER4);
            }
            Thread.sleep(20);
        }
        System.out.println("[await-dump] done in " + (System.currentTimeMillis() - start) + "ms");
    }

    private static boolean dumpPresent(String dir, String prefix, long since) throws Exception {
        // Accept only dump files written on or after `since` (ms) to avoid
        // treating warmup-time dumps as the result of our deoptimize+enqueue.
        try (Stream<Path> s = Files.list(Paths.get(dir))) {
            boolean[] seen = new boolean[2];
            s.forEach(p -> {
                try {
                    String n = p.getFileName().toString();
                    if (!n.startsWith(prefix)) return;
                    if (Files.getLastModifiedTime(p).toMillis() < since) return;
                    if (n.endsWith("_optimized.ll")) seen[1] = true;
                    else if (n.endsWith(".ll")) seen[0] = true;
                } catch (java.io.IOException ignored) {}
            });
            return seen[0] && seen[1];
        }
    }


    public static void main(String[] args) throws Exception {
        // `immature` is intentionally not warmed.
        warmupBiasedAndSwitch();

        Method biased = TestUnstableIfPruneBoundaries.class.getDeclaredMethod("biasedNotPruned", int.class);
        Method sw = TestUnstableIfPruneBoundaries.class.getDeclaredMethod("switchOneColdCase", int.class);
        Method imm = TestUnstableIfPruneBoundaries.class.getDeclaredMethod("immature", int.class);
        String dir = System.getProperty("user.dir");

        compileAndAwaitDump(biased, dir);
        FileCheck biasedFc = new FileCheck(dir, biased, /*optimized=*/false);
        biasedFc.checkPattern("define hotspotcc i32 .*TestUnstableIfPruneBoundaries_biasedNotPruned");
        biasedFc.checkPattern("branch_weights\", i32 [0-9]+, i32 [0-9]+");
        FileCheck biasedNoTrap = new FileCheck(dir, biased, /*optimized=*/false);
        biasedNoTrap.checkNotPattern("unstable_if");
        FileCheck biasedNoUct = new FileCheck(dir, biased, /*optimized=*/false);
        biasedNoUct.checkNotPattern("@llvm.experimental.deoptimize");

        compileAndAwaitDump(sw, dir);
        FileCheck swFc = new FileCheck(dir, sw, /*optimized=*/false);
        swFc.checkPattern("define hotspotcc i32 .*TestUnstableIfPruneBoundaries_switchOneColdCase");
        swFc.checkPattern("switch i32 ");
        FileCheck swNoUnstable = new FileCheck(dir, sw, /*optimized=*/false);
        swNoUnstable.checkNotPattern("unstable_if");

        compileAndAwaitDump(imm, dir);
        FileCheck immFc = new FileCheck(dir, imm, /*optimized=*/false);
        immFc.checkPattern("define hotspotcc i32 .*TestUnstableIfPruneBoundaries_immature");
        immFc.checkNotPattern("unstable_if");

        if (biasedNotPruned(500) != 501 || biasedNotPruned(5000) != 4999) {
            throw new RuntimeException("biasedNotPruned wrong");
        }
        if (switchOneColdCase(0) != 10 || switchOneColdCase(3) != 13 || switchOneColdCase(99) != 99) {
            throw new RuntimeException("switchOneColdCase wrong");
        }
        if (immature(5) != 5 || immature(-7) != -1) {
            throw new RuntimeException("immature wrong");
        }

        System.out.println("TestUnstableIfPruneBoundaries PASSED");
    }
}
