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
 * @summary On a tight recurrence `s += v*7 + (v>>>2)` in an innermost loop,
 *          Reassociate must NOT sink the loop-carried phi `s` into the inner
 *          add of the chain. The patched LLVM Reassociate (OpCategory model)
 *          classifies `s` as LoopCarriedRecurrence and places it at the
 *          outermost RHS of the rebuilt tree, leaving the inner add to
 *          contain only the two body computations `v*7` and `v>>>2`. The
 *          Jeandle-side `llvm.fake.use` workaround is no longer emitted.
 *
 *          This test guards against (a) regression of the LLVM-side fix and
 *          (b) accidental reintroduction of the workaround.
 * @library /test/lib /
 * @build jdk.test.whitebox.WhiteBox compiler.jeandle.fileCheck.FileCheck
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *      -XX:+UseJeandleCompiler -XX:+JeandleUseProfile -XX:+JeandleDumpIR
 *      -XX:CompileCommand=compileonly,compiler.jeandle.pgo.TestReassociateBarrier::recurrence
 *      -Xlog:compilation*=info
 *      compiler.jeandle.pgo.TestReassociateBarrier
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

public class TestReassociateBarrier {
    private static final WhiteBox WB = WhiteBox.getWhiteBox();
    private static final int TIER4 = 4;

    static volatile int sink;
    static int[] data;

    static int recurrence(int[] a) {
        int s = 0;
        for (int i = 0; i < a.length; i++) {
            int v = a[i];
            s += v * 7 + (v >>> 2);
        }
        return s;
    }

    private static void warmup() {
        long acc = 0;
        for (int i = 0; i < 200; i++) {
            acc += recurrence(data);
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

    // Locate either the pre-opt (`prefix_NNN.ll`) or the post-opt
    // (`prefix_NNN_optimized.ll`) IR dump for `m` and return its contents.
    // Takes the file with the LATEST mtime; freshness is already guaranteed
    // by compileAndAwaitDump() having returned, so we don't need to apply a
    // separate cutoff here. (An earlier version of this helper tried to use
    // an outer `since` from main() and tripped over a 1-2ms race where a
    // warmup-era dump's mtime ended up just before that cutoff -- safer to
    // let compileAndAwaitDump be the sole authority on what "fresh" means.)
    //
    // The two dumps are needed for DIFFERENT assertions:
    //   * The pre-opt dump shows what Jeandle's AbstractInterpreter emits
    //     BEFORE LLVM's pipeline runs. The absence of `llvm.fake.use` calls
    //     here confirms the JIT-side workaround is gone.
    //   * The post-opt dump shows what Jeandle ships to codegen AFTER
    //     LLVM optimization. The recurrence-shape assertion belongs here:
    //     pre-opt IR always has the shallow `add (mul, lshr)` shape (it is
    //     the construction order), regardless of whether Reassociate would
    //     later mangle it. The mangling -- and our LLVM-side fix's
    //     prevention of it -- only manifests after `jeandle::optimize()`.
    private static String readDump(String dir, Method m,
                                   boolean optimized) throws Exception {
        String prefix = m.getDeclaringClass().getName().replace('.', '_') + "_" + m.getName();
        Path latest = null;
        long latestMtime = 0;
        try (Stream<Path> s = Files.list(Paths.get(dir))) {
            for (Path p : (Iterable<Path>) s::iterator) {
                String n = p.getFileName().toString();
                if (!n.startsWith(prefix)) continue;
                if (!n.endsWith(".ll")) continue;
                boolean isOpt = n.endsWith("_optimized.ll");
                if (isOpt != optimized) continue;
                long mt = Files.getLastModifiedTime(p).toMillis();
                if (mt > latestMtime) { latestMtime = mt; latest = p; }
            }
        }
        if (latest == null) {
            throw new RuntimeException(
                "no " + (optimized ? "post-opt" : "pre-opt")
                + " dump found for " + m.getName());
        }
        return new String(Files.readAllBytes(latest));
    }

    // Assert the OPTIMIZED IR has the SHALLOW recurrence shape:
    //   mul %v, 7
    //   lshr %v, 2
    //   add (mul_res, shr_res)       <- inner add: pure body
    //   add (inner_res, phi)         <- outer add: phi at outermost RHS
    //
    // The deep-shape (regression we guard against) has the phi mixed in:
    //   mul %v, 7
    //   lshr %v, 2
    //   add (lshr_res, phi)          <- BAD: phi in inner add
    //   add (inner_res, mul_res)
    // or any other ordering that puts the phi at a non-outer position.
    //
    // Pre-opt IR is NOT acceptable here because Jeandle's AbstractInterpreter
    // emits the shallow form by construction -- the absence of a regression
    // in pre-opt is vacuous, the property only manifests post-optimization.
    private static void assertRecurrenceShallowShape(String ir) {
        // Find `%M = mul ... i32 %V, 7`. The flag clause `[a-z ]*?` is permissive
        // to accommodate any future canonicalization (`nsw`, `nuw nsw`, etc.).
        Pattern mulPat = Pattern.compile(
                "^\\s*(%[\\w.]+)\\s*=\\s*mul\\s+[a-z ]*?i32\\s+(%[\\w.]+),\\s*7\\b",
                Pattern.MULTILINE);
        Pattern shrPat = Pattern.compile(
                "^\\s*(%[\\w.]+)\\s*=\\s*lshr\\s+[a-z ]*?i32\\s+(%[\\w.]+),\\s*2\\b",
                Pattern.MULTILINE);
        Matcher mm = mulPat.matcher(ir);
        if (!mm.find()) {
            throw new RuntimeException("could not locate `mul i32 %v, 7` in optimized IR");
        }
        String mulRes = mm.group(1);
        String mulIn = mm.group(2);
        Matcher sm = shrPat.matcher(ir);
        if (!sm.find()) {
            throw new RuntimeException("could not locate `lshr i32 %v, 2` in optimized IR");
        }
        String shrRes = sm.group(1);
        String shrIn = sm.group(2);
        // Require both ops to operate on the SAME source value -- otherwise an
        // unrelated `mul (something), 7` and `lshr (something_else), 2`
        // appearing in the dump could falsely satisfy the search.
        if (!mulIn.equals(shrIn)) {
            throw new RuntimeException(
                "found `mul i32 " + mulIn + ", 7` and `lshr i32 " + shrIn
                + ", 2` but the input values differ; expected both to derive from "
                + "the same array-load %v");
        }
        // Look for the inner add consuming both `mulRes` and `shrRes` -- either
        // operand order is acceptable (commutative add).
        Pattern innerAdd = Pattern.compile(
                "^\\s*%[\\w.]+\\s*=\\s*add\\s+[a-z ]*?i32\\s+("
                        + Pattern.quote(mulRes) + "\\s*,\\s*" + Pattern.quote(shrRes)
                        + "|" + Pattern.quote(shrRes) + "\\s*,\\s*" + Pattern.quote(mulRes)
                        + ")",
                Pattern.MULTILINE);
        if (!innerAdd.matcher(ir).find()) {
            throw new RuntimeException(
                "expected an inner add combining mul-by-7 (" + mulRes + ") and lshr-by-2 ("
                + shrRes + ") with both operating on the same %v -- but didn't find one. "
                + "Reassociate may have sunk the loop-carried phi into the inner add "
                + "(the regression we are guarding against). Either the LLVM-side fix has "
                + "been reverted, or the JIT-side workaround is missing.");
        }
    }

    public static void main(String[] args) throws Exception {
        data = new int[4096];
        for (int i = 0; i < data.length; i++) {
            data[i] = (i * 1234567) & 0x7fffffff;
        }
        warmup();

        Method m = TestReassociateBarrier.class.getDeclaredMethod("recurrence", int[].class);
        String dir = System.getProperty("user.dir");
        compileAndAwaitDump(m, dir);

        FileCheck pre = new FileCheck(dir, m, /*optimized=*/false);
        pre.checkPattern("define hotspotcc i32 .*TestReassociateBarrier_recurrence");

        // Read both dumps -- they verify different properties of the fix.
        String preIR  = readDump(dir, m, /*optimized=*/false);
        String postIR = readDump(dir, m, /*optimized=*/true);

        // (a) The Jeandle-side llvm.fake.use workaround inserts its calls
        // BEFORE LLVM optimization runs, so the absence-of-fake-use check must
        // look at the pre-opt dump. If the JIT-side workaround is reintroduced
        // it will be visible here.
        if (preIR.contains("llvm.fake.use")) {
            throw new RuntimeException(
                "@llvm.fake.use found in pre-opt IR: the JIT-side workaround appears to have "
                + "been reintroduced. The LLVM-side Reassociate fix (OpCategory) should be the "
                + "sole mechanism preventing the loop-carried phi sink.");
        }

        // (b) The recurrence-shape property only manifests AFTER optimization
        // (LLVM's Reassociate is what either preserves or mangles the chain).
        // Pre-opt IR always has the shallow shape -- it's the construction
        // form emitted by Jeandle's AbstractInterpreter. The assertion must
        // run on the post-opt dump to actually verify the LLVM-side fix.
        assertRecurrenceShallowShape(postIR);

        // (c) Semantic check: the recurrence still computes the right value.
        int expected = 0;
        for (int v : data) {
            expected += v * 7 + (v >>> 2);
        }
        if (recurrence(data) != expected) {
            throw new RuntimeException("recurrence returned wrong value");
        }

        System.out.println("TestReassociateBarrier PASSED");
    }
}
