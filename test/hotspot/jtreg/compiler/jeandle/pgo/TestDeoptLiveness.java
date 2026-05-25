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
 * @summary Locals that are dead at a deopt-point bci are encoded as
 *          T_ILLEGAL placeholders in the deopt bundle, not pinned live as
 *          their SSA values.
 * @library /test/lib /
 * @build jdk.test.whitebox.WhiteBox compiler.jeandle.fileCheck.FileCheck
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *      -XX:+UseJeandleCompiler -XX:+JeandleUseProfile -XX:+JeandleDumpIR
 *      -XX:CompileCommand=compileonly,compiler.jeandle.pgo.TestDeoptLiveness::deadLocalsAtTrap
 *      compiler.jeandle.pgo.TestDeoptLiveness
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

public class TestDeoptLiveness {
    private static final WhiteBox WB = WhiteBox.getWhiteBox();
    private static final int TIER4 = 4;

    static volatile int sink;

    // x and y are dead from bci AFTER `temp = x * y + (y >>> 1)`. The
    // implicit null-check on arr.length is a deopt point at that bci; its
    // deopt bundle must encode x (%1) and y (%2) as T_ILLEGAL placeholders.
    static int deadLocalsAtTrap(int[] arr, int x, int y) {
        int temp = x * y + (y >>> 1);
        int len = arr.length;
        return temp + len;
    }

    private static void warmup() {
        long acc = 0;
        int[] data = new int[1];
        for (int i = 0; i < 50_000; i++) {
            acc += deadLocalsAtTrap(data, i, i + 1);
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

    // The method's only uncommon_trap is the implicit null check on
    // arr.length, which sits at a bci where x (%1) and y (%2) are already
    // dead. Assert neither appears as a deopt argument anywhere in the IR.
    private static void assertDeadParamsAbsentFromTrapDeopt(String dir, Method m) throws Exception {
        String prefix = m.getDeclaringClass().getName().replace('.', '_') + "_" + m.getName();
        Path ll;
        try (Stream<Path> s = Files.list(Paths.get(dir))) {
            ll = s.filter(p -> {
                    String n = p.getFileName().toString();
                    return n.startsWith(prefix) && n.endsWith(".ll") && !n.endsWith("_optimized.ll");
                }).findFirst().orElseThrow(() -> new RuntimeException("no pre-opt dump"));
        }

        Pattern trapDeoptStart = Pattern.compile("call.*@llvm\\.experimental\\.deoptimize.*\\[\\s*\"deopt\"\\((.*?)\\)\\s*\\]");
        Pattern param1 = Pattern.compile("\\bi32 %1\\b");
        Pattern param2 = Pattern.compile("\\bi32 %2\\b");
        int trapsScanned = 0;
        for (String line : Files.readAllLines(ll)) {
            Matcher mm = trapDeoptStart.matcher(line);
            if (!mm.find()) continue;
            trapsScanned++;
            String args = mm.group(1);
            if (param1.matcher(args).find()) {
                throw new RuntimeException("dead %1 (x) pinned live in deopt bundle: " + line);
            }
            if (param2.matcher(args).find()) {
                throw new RuntimeException("dead %2 (y) pinned live in deopt bundle: " + line);
            }
        }
        if (trapsScanned == 0) {
            throw new RuntimeException("no uncommon_trap deopt bundles found");
        }
    }

    public static void main(String[] args) throws Exception {
        warmup();

        Method m = TestDeoptLiveness.class.getDeclaredMethod(
            "deadLocalsAtTrap", int[].class, int.class, int.class);
        String dir = System.getProperty("user.dir");
        compileAndAwaitDump(m, dir);

        FileCheck pre = new FileCheck(dir, m, /*optimized=*/false);
        pre.checkPattern("define hotspotcc i32 .*TestDeoptLiveness_deadLocalsAtTrap");
        pre.checkPattern("@llvm.experimental.deoptimize");

        assertDeadParamsAbsentFromTrapDeopt(dir, m);

        try {
            deadLocalsAtTrap(null, 1, 2);
            throw new RuntimeException("Expected NullPointerException");
        } catch (NullPointerException expected) {
        }
        if (deadLocalsAtTrap(new int[]{0,1,2}, 4, 5) != (4*5 + (5>>>1) + 3)) {
            throw new RuntimeException("Hot path returned wrong value");
        }

        System.out.println("TestDeoptLiveness PASSED");
    }
}
