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
 */

/*
 * @test
 * @summary M2 unstable-if prune inside a method with a typed catch handler
 *          that reads the same local slot via a phi. Switching the trap
 *          emission to `llvm.experimental.deoptimize` makes LLVM treat the
 *          trap as an opaque barrier, so backward fact propagation from the
 *          trap edge cannot rewrite the local-slot phi feeding the catch
 *          handler. Without that barrier the optimizer would conclude the
 *          loaded-in-trap-arm value is dead and miscompile the handler.
 * @library /test/lib /
 * @build jdk.test.whitebox.WhiteBox compiler.jeandle.fileCheck.FileCheck
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *      -XX:+UseJeandleCompiler -XX:+JeandleUseProfile -XX:+JeandleDumpIR
 *      -XX:CompileCommand=compileonly,compiler.jeandle.pgo.TestUnstableIfTypedCatch::hotNeverTakenWithCatch
 *      -Xlog:compilation*=info
 *      compiler.jeandle.pgo.TestUnstableIfTypedCatch
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

public class TestUnstableIfTypedCatch {
    private static final WhiteBox WB = WhiteBox.getWhiteBox();
    private static final int TIER4 = 4;

    static volatile int sink;

    // Two local slots ('local' and 'fallback') are written under control flow
    // including the M2-prune-candidate `local < 0` arm, then BOTH are read by
    // the typed `catch (ArithmeticException)` handler. The trap site's deopt
    // bundle and the catch handler's local-slot phi reference the same SSA
    // values. With the prior `call @uncommon_trap; unreachable` lowering, an
    // LLVM optimizer (JumpThreading / CVP / SCCP) could reverse-propagate
    // facts from the trap arm back through the join phi feeding the catch and
    // miscompile the handler. With `llvm.experimental.deoptimize` the trap is
    // an opaque barrier and the phi survives.
    static int hotNeverTakenWithCatch(int x, int divisor) {
        int local = x;
        int fallback = x + 7;
        try {
            if (local < 0) {            // never taken in warmup -> M2 prune
                local = ~local;
                fallback = ~fallback;
            }
            return 100 / divisor + local;   // throws ArithmeticException when divisor == 0
        } catch (ArithmeticException ae) {
            return local + fallback;        // both slots flow through a phi
        }
    }

    private static void warmup() {
        long acc = 0;
        // x always >= 0, divisor always != 0 -- the M2 prune arm and the
        // catch handler are both never taken during warmup.
        for (int i = 0; i < 50_000; i++) {
            acc += hotNeverTakenWithCatch(i & 0x7fffffff, (i % 7) + 1);
        }
        sink = (int) acc;
    }

    private static void compileAndAwaitDump(Method m, String dir) throws Exception {
        String prefix = m.getDeclaringClass().getName().replace('.', '_') + "_" + m.getName();
        clearDumps(dir, prefix);
        WB.deoptimizeMethod(m);
        WB.enqueueMethodForCompilation(m, TIER4);
        long start = System.currentTimeMillis();
        long deadline = start + 300_000;
        long nextLog = start + 1_000;
        while (!dumpPresent(dir, prefix)) {
            long now = System.currentTimeMillis();
            if (now > deadline) {
                int lvl = WB.getMethodCompilationLevel(m);
                throw new RuntimeException("Timeout: no Jeandle IR dump for " + m.getName()
                        + " (final level=" + lvl + ", elapsed=" + (now - start) + "ms)");
            }
            if (now >= nextLog) {
                int lvl = WB.getMethodCompilationLevel(m);
                boolean queued = WB.isMethodQueuedForCompilation(m);
                System.out.println("[await-dump] t=" + (now - start) + "ms level=" + lvl
                        + " queued=" + queued + " dumpPresent=" + dumpPresent(dir, prefix));
                nextLog = now + 1_000;
            }
            if (WB.getMethodCompilationLevel(m) != TIER4) {
                WB.enqueueMethodForCompilation(m, TIER4);
            }
            Thread.sleep(20);
        }
        System.out.println("[await-dump] done in " + (System.currentTimeMillis() - start) + "ms");
    }

    private static boolean dumpPresent(String dir, String prefix) throws Exception {
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

        Method m = TestUnstableIfTypedCatch.class.getDeclaredMethod(
                "hotNeverTakenWithCatch", int.class, int.class);
        String dir = System.getProperty("user.dir");
        compileAndAwaitDump(m, dir);

        // The trap must be emitted as `llvm.experimental.deoptimize`, not as a
        // plain noreturn runtime call. The intrinsic is the opaque barrier.
        FileCheck fc = new FileCheck(dir, m, /*optimized=*/false);
        fc.checkPattern("define hotspotcc i32 .*hotNeverTakenWithCatch");
        fc.checkPattern("br i1 .*unstable_if");
        fc.checkPattern("@llvm.experimental.deoptimize");

        // Post-optimization the intrinsic must still be present -- if any
        // optimizer (CVP / JumpThreading / SimplifyCFG) folded through it,
        // the catch-handler local-slot phi would no longer be conservative.
        FileCheck fcOpt = new FileCheck(dir, m, /*optimized=*/true);
        fcOpt.checkPattern("@llvm.experimental.deoptimize");

        // Behavioral verification across the four (cold-side, exception-side)
        // combinations. The hot/no-throw combo is exercised by warmup; the
        // other three force a deopt and/or a catch and must all match the
        // interpreter's semantics.
        //   x>=0, div!=0 : normal return        -> 100/div + x
        //   x>=0, div==0 : catch fires          -> x + (x+7)
        //   x< 0, div!=0 : M2 trap, then normal -> 100/div + (~x)
        //   x< 0, div==0 : M2 trap, then catch  -> (~x) + (~(x+7))
        if (hotNeverTakenWithCatch(5, 4) != 100 / 4 + 5) {
            throw new RuntimeException("hot/no-throw wrong: " + hotNeverTakenWithCatch(5, 4));
        }
        if (hotNeverTakenWithCatch(5, 0) != 5 + 12) {
            throw new RuntimeException("hot/throw wrong: " + hotNeverTakenWithCatch(5, 0));
        }

        // Drive the cold (M2-pruned) arm past the per-method trap limit so
        // de-speculation kicks in; the recompile must keep semantics.
        for (int i = 1; i <= 400; i++) {
            int x = -i;
            int got = hotNeverTakenWithCatch(x, 3);
            int want = 100 / 3 + (~x);
            if (got != want) {
                throw new RuntimeException("cold/no-throw wrong at x=" + x
                        + " got=" + got + " want=" + want);
            }
        }
        // After de-speculation the cold+catch combo must still return the
        // catch-handler phi using the trap-arm-rewritten `local` and
        // `fallback` values. This is the case that would miscompile under
        // backward fact propagation across a non-opaque trap.
        for (int i = 1; i <= 50; i++) {
            int x = -i;
            int got = hotNeverTakenWithCatch(x, 0);
            int want = (~x) + (~(x + 7));
            if (got != want) {
                throw new RuntimeException("cold/throw wrong at x=" + x
                        + " got=" + got + " want=" + want);
            }
        }

        System.out.println("TestUnstableIfTypedCatch PASSED");
    }
}
