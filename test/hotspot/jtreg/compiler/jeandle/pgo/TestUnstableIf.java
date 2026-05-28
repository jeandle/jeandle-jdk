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
 * @summary A branch never taken in the MDO is pruned to
 *          uncommon_trap(Reason_unstable_if); the speculation must deopt
 *          correctly when the pruned path is taken at runtime.
 * @library /test/lib /
 * @build jdk.test.whitebox.WhiteBox compiler.jeandle.fileCheck.FileCheck
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *      -XX:+UseJeandleCompiler -XX:+JeandleUseProfile -XX:+JeandleDumpIR
 *      -XX:CompileCommand=compileonly,compiler.jeandle.pgo.TestUnstableIf::hotNeverTaken
 *      -Xlog:compilation*=info
 *      compiler.jeandle.pgo.TestUnstableIf
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

public class TestUnstableIf {
    private static final WhiteBox WB = WhiteBox.getWhiteBox();
    private static final int TIER4 = 4;  // CompLevel_full_optimization (Jeandle)

    static volatile int sink;

    // During warmup x is always >= 0, so the `x < 0` branch is never taken. With a
    // mature profile, the cold edge should be pruned to an uncommon_trap.
    static int hotNeverTaken(int x) {
        if (x < 0) {
            return -1;
        }
        return x + 1;
    }

    private static void warmup() {
        long acc = 0;
        for (int i = 0; i < 50_000; i++) {
            acc += hotNeverTaken(i & 0x7fffffff);  // always >= 0
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
        warmup();

        Method m = TestUnstableIf.class.getDeclaredMethod("hotNeverTaken", int.class);
        String dir = System.getProperty("user.dir");
        compileAndAwaitDump(m, dir);

        FileCheck fc = new FileCheck(dir, m, /*optimized=*/false);
        fc.checkPattern("define hotspotcc i32 .*hotNeverTaken");
        fc.checkPattern("br i1 .*unstable_if");
        fc.checkPattern("@llvm.experimental.deoptimize");

        // The compiled method prunes the x<0 path, so x<0 takes the trap, deopts,
        // re-executes in the interpreter, and must still return -1. Driving the
        // trap past the per-method trap limit (default ~100) also exercises
        // de-speculation -- the recompile must stop pruning and emit both sides,
        // otherwise the cold path loops in deopt forever.
        for (int i = 1; i <= 400; i++) {
            int r = hotNeverTaken(-i);
            if (r != -1) {
                throw new RuntimeException("Pruned-path deopt returned " + r + " for x=" + (-i));
            }
        }
        if (hotNeverTaken(41) != 42) {
            throw new RuntimeException("Hot path returned wrong value");
        }

        System.out.println("TestUnstableIf PASSED");
    }
}
