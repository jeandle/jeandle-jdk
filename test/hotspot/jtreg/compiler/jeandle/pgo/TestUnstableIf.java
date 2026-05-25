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
    // Waiting on the dump file is race-free: a transient C1 (tier-3) compile, or a stale
    // tier-4 level reading after deoptimizeMethod, would otherwise let a level/isCompiled
    // wait fall through before the Jeandle dump exists.
    private static void compileAndAwaitDump(Method m, String dir) throws Exception {
        String prefix = m.getDeclaringClass().getName().replace('.', '_') + "_" + m.getName();
        clearDumps(dir);
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
