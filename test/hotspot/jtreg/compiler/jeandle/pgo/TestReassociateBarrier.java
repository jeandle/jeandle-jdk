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
 * @summary On a tight recurrence in an innermost loop, a llvm.fake.use must
 *          be emitted on the inner add so Reassociate doesn't flatten the
 *          (phi + inner) chain.
 * @library /test/lib /
 * @build jdk.test.whitebox.WhiteBox compiler.jeandle.fileCheck.FileCheck
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *      -XX:+UseJeandleCompiler -XX:+JeandleUseProfile -XX:+JeandleDumpIR
 *      -XX:CompileCommand=compileonly,compiler.jeandle.pgo.TestReassociateBarrier::recurrence
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

        FileCheck preFakeUse = new FileCheck(dir, m, /*optimized=*/false);
        preFakeUse.checkPattern("call void.*@llvm\\.fake\\.use\\(i32 ");

        FileCheck optFakeUse = new FileCheck(dir, m, /*optimized=*/true);
        optFakeUse.checkPattern("call void.*@llvm\\.fake\\.use");

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
