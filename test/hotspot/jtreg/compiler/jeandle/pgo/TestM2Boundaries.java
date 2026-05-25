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
 * @summary M2 unstable-if prune negative cases: heavily-biased (but
 *          two-sided) branch, switch with cold case, immature profile.
 *          None should be pruned.
 * @library /test/lib /
 * @build jdk.test.whitebox.WhiteBox compiler.jeandle.fileCheck.FileCheck
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *      -XX:+UseJeandleCompiler -XX:+JeandleUseProfile -XX:+JeandleDumpIR
 *      -XX:CompileCommand=compileonly,compiler.jeandle.pgo.TestM2Boundaries::biasedNotPruned
 *      -XX:CompileCommand=compileonly,compiler.jeandle.pgo.TestM2Boundaries::switchOneColdCase
 *      -XX:CompileCommand=compileonly,compiler.jeandle.pgo.TestM2Boundaries::immature
 *      compiler.jeandle.pgo.TestM2Boundaries
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

public class TestM2Boundaries {
    private static final WhiteBox WB = WhiteBox.getWhiteBox();
    private static final int TIER4 = 4;

    static volatile int sink;

    // ~99% taken / ~1% not -- never strict-zero, so M2 must keep both edges.
    static int biasedNotPruned(int x) {
        if (x < 1000) {
            return x + 1;
        }
        return x - 1;
    }

    // case 3 is strict-zero in the MDO; M2 only prunes if_* (do_if_branch),
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

    private static void compileAndAwaitDump(Method m, String dir) throws Exception {
        String prefix = m.getDeclaringClass().getName().replace('.', '_') + "_" + m.getName();
        clearDumps(dir, prefix);
        WB.deoptimizeMethod(m);
        WB.enqueueMethodForCompilation(m, TIER4);
        long deadline = System.currentTimeMillis() + 300_000;
        while (!dumpPresent(dir, prefix)) {
            if (System.currentTimeMillis() > deadline) {
                throw new RuntimeException("Timeout: no Jeandle IR dump for " + m.getName());
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
        // `immature` is intentionally not warmed.
        warmupBiasedAndSwitch();

        Method biased = TestM2Boundaries.class.getDeclaredMethod("biasedNotPruned", int.class);
        Method sw = TestM2Boundaries.class.getDeclaredMethod("switchOneColdCase", int.class);
        Method imm = TestM2Boundaries.class.getDeclaredMethod("immature", int.class);
        String dir = System.getProperty("user.dir");

        compileAndAwaitDump(biased, dir);
        FileCheck biasedFc = new FileCheck(dir, biased, /*optimized=*/false);
        biasedFc.checkPattern("define hotspotcc i32 .*TestM2Boundaries_biasedNotPruned");
        biasedFc.checkPattern("branch_weights\", i32 [0-9]+, i32 [0-9]+");
        FileCheck biasedNoTrap = new FileCheck(dir, biased, /*optimized=*/false);
        biasedNoTrap.checkNotPattern("unstable_if");
        FileCheck biasedNoUct = new FileCheck(dir, biased, /*optimized=*/false);
        biasedNoUct.checkNotPattern("@llvm.experimental.deoptimize");

        compileAndAwaitDump(sw, dir);
        FileCheck swFc = new FileCheck(dir, sw, /*optimized=*/false);
        swFc.checkPattern("define hotspotcc i32 .*TestM2Boundaries_switchOneColdCase");
        swFc.checkPattern("switch i32 ");
        FileCheck swNoUnstable = new FileCheck(dir, sw, /*optimized=*/false);
        swNoUnstable.checkNotPattern("unstable_if");

        compileAndAwaitDump(imm, dir);
        FileCheck immFc = new FileCheck(dir, imm, /*optimized=*/false);
        immFc.checkPattern("define hotspotcc i32 .*TestM2Boundaries_immature");
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

        System.out.println("TestM2Boundaries PASSED");
    }
}
