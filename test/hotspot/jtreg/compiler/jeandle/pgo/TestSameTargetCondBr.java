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

    private static void compileAndAwaitDump(Method m, String dir) throws Exception {
        String prefix = m.getDeclaringClass().getName().replace('.', '_') + "_" + m.getName();
        clearDumps(dir);
        WB.deoptimizeMethod(m);
        WB.enqueueMethodForCompilation(m, TIER4);
        long deadline = System.currentTimeMillis() + 300_000;
        while (!dumpPresent(dir, prefix)) {
            if (System.currentTimeMillis() > deadline) {
                throw new RuntimeException("Timeout: no Jeandle IR dump for " + m.getName()
                    + " -- compilation likely crashed (regression of the dotty "
                    + "UncachedGroundType.<init> SIGSEGV?)");
            }
            if (WB.getMethodCompilationLevel(m) != TIER4) {
                WB.enqueueMethodForCompilation(m, TIER4);
            }
            Thread.sleep(20);
        }
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

    private static void clearDumps(String dir) throws Exception {
        try (Stream<Path> s = Files.list(Paths.get(dir))) {
            List<Path> dumps = s.filter(p -> p.getFileName().toString().endsWith(".ll"))
                                .collect(Collectors.toList());
            for (Path p : dumps) {
                Files.deleteIfExists(p);
            }
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
