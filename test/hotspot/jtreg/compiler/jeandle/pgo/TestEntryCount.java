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
 * @summary The interpreter invocation count is attached as the LLVM function
 *          entry count and survives the optimization pipeline.
 * @library /test/lib /
 * @build jdk.test.whitebox.WhiteBox compiler.jeandle.fileCheck.FileCheck
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *      -XX:+UseJeandleCompiler -XX:+JeandleUseProfile -XX:+JeandleDumpIR
 *      -XX:CompileCommand=compileonly,compiler.jeandle.pgo.TestEntryCount::hot
 *      -Xlog:compilation*=info
 *      compiler.jeandle.pgo.TestEntryCount
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

public class TestEntryCount {
    private static final WhiteBox WB = WhiteBox.getWhiteBox();
    private static final int TIER4 = 4;

    static volatile int sink;

    static int hot(int x) {
        return x * 3 + 1;
    }

    private static void warmup() {
        long acc = 0;
        for (int i = 0; i < 50_000; i++) {
            acc += hot(i);
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
                WB.enqueueMethodForCompilation(m, TIER4);
            }
            Thread.sleep(20);
        }
        System.out.println("[await-dump] done in " + (System.currentTimeMillis() - start) + "ms");
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

        Method m = TestEntryCount.class.getDeclaredMethod("hot", int.class);
        String dir = System.getProperty("user.dir");
        compileAndAwaitDump(m, dir);

        FileCheck pre = new FileCheck(dir, m, /*optimized=*/false);
        pre.checkPattern("define hotspotcc i32 .*TestEntryCount_hot.*!prof");
        FileCheck preMd = new FileCheck(dir, m, /*optimized=*/false);
        preMd.checkPattern("!\\{!\"function_entry_count\", i64 [0-9]+\\}");

        FileCheck optMd = new FileCheck(dir, m, /*optimized=*/true);
        optMd.checkPattern("!\\{!\"function_entry_count\", i64 [0-9]+\\}");

        if (hot(7) != 22) {
            throw new RuntimeException("hot(7) returned wrong value");
        }

        System.out.println("TestEntryCount PASSED");
    }
}
