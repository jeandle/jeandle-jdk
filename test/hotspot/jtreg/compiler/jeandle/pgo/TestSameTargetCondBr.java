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
 * @summary A degenerate `if (cond) {}` (taken target == fallthrough) must
 *          compile without crashing the LLVM verifier. Regression test for
 *          dotty Types$UncachedGroundType::<init> on LLVM 22 aarch64.
 * @library /test/lib /
 * @build jdk.test.whitebox.WhiteBox compiler.jeandle.fileCheck.FileCheck
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *      -XX:+UseJeandleCompiler -XX:+JeandleUseProfile -XX:+JeandleDumpIR
 *      -XX:CompileCommand=compileonly,compiler.jeandle.pgo.TestSameTargetCondBr::emptyBranch
 *      -Xlog:compilation*=info
 *      compiler.jeandle.pgo.TestSameTargetCondBr
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

public class TestSameTargetCondBr {
    private static final WhiteBox WB = WhiteBox.getWhiteBox();
    private static final int TIER4 = 4;  // CompLevel_full_optimization (Jeandle)

    static volatile int sink;

    // Empty `if` body: javac emits `iload; ifle TARGET; TARGET:` where TARGET
    // equals the fallthrough bci, so both arms of the ifle point at the same
    // JeandleBasicBlock. do_if_branch must emit a plain CondBr without
    // branch_weights here, otherwise LLVM 22's verifyModule null-derefs.
    static int emptyBranch(int x) {
        if (x > 0) {}
        return x;
    }

    private static void warmup() {
        long acc = 0;
        // Mix signed-positive and non-positive so the MDO records both sides
        // as non-zero; if one side were strict-zero, M2 would prune the
        // degenerate CondBr away and side-step this regression.
        for (int i = 0; i < 50_000; i++) {
            acc += emptyBranch((i & 0xff) - 64);
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
        // Build a mature profile so do_if_branch goes through the same code
        // path it would take for a real Java method (mature + has counts).
        warmup();

        Method m = TestSameTargetCondBr.class.getDeclaredMethod("emptyBranch", int.class);
        String dir = System.getProperty("user.dir");

        // The primary assertion is just that the compile didn't crash --
        // verifyModule would SIGSEGV before producing _optimized.ll if the
        // same-target-CondBr guard regressed.
        compileAndAwaitDump(m, dir);

        FileCheck opt = new FileCheck(dir, m, /*optimized=*/true);
        opt.checkPattern("define hotspotcc i32 .*TestSameTargetCondBr_emptyBranch");
        // SimplifyCFG folds `br i1, %B, %B` to `br %B`, so no same-target
        // CondBr should survive into the optimized IR.
        FileCheck optNoSameTarget = new FileCheck(dir, m, /*optimized=*/true);
        optNoSameTarget.checkNotPattern("br i1 [^,]+, label %([\\w.]+), label %\\1");

        if (emptyBranch(5) != 5 || emptyBranch(-3) != -3 || emptyBranch(0) != 0) {
            throw new RuntimeException("emptyBranch returned wrong value");
        }

        System.out.println("TestSameTargetCondBr PASSED");
    }
}
