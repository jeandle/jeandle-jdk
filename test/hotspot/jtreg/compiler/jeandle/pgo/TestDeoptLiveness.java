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
 *          their SSA values. A dead two-word (long/double) local takes two
 *          illegal slots so later locals stay aligned.
 * @library /test/lib /
 * @build jdk.test.whitebox.WhiteBox compiler.jeandle.fileCheck.FileCheck
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *      -XX:+UseJeandleCompiler -XX:+JeandleUseProfile -XX:+JeandleDumpIR
 *      -XX:CompileCommand=compileonly,compiler.jeandle.pgo.TestDeoptLiveness::deadLocalsAtTrap
 *      -XX:CompileCommand=compileonly,compiler.jeandle.pgo.TestDeoptLiveness::deadLongLocalsAtTrap
 *      -Xlog:compilation*=info
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

    // Same shape with two-word locals: x (slots 1-2) and y (slots 3-4) are dead
    // from the bci of arr.length (the implicit null-check deopt point). Each must
    // be encoded as two T_ILLEGAL slots, never pinning the i64 value.
    static int deadLongLocalsAtTrap(int[] arr, long x, long y) {
        long temp = x * y + (y >>> 1);
        int len = arr.length;
        return (int) temp + len;
    }

    private static void warmup() {
        long acc = 0;
        int[] data = new int[1];
        for (int i = 0; i < 50_000; i++) {
            acc += deadLocalsAtTrap(data, i, i + 1);
            acc += deadLongLocalsAtTrap(data, i, i + 1);
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


    // The method's only uncommon_trap is the implicit null check on
    // arr.length, which sits at a bci where x (%1) and y (%2) are already
    // dead. Assert neither appears as a deopt argument anywhere in the IR.
    private static void assertDeadParamsAbsentFromTrapDeopt(String dir, Method m,
                                                            String... deadParams) throws Exception {
        String prefix = m.getDeclaringClass().getName().replace('.', '_') + "_" + m.getName();
        Path ll;
        try (Stream<Path> s = Files.list(Paths.get(dir))) {
            ll = s.filter(p -> {
                    String n = p.getFileName().toString();
                    return n.startsWith(prefix) && n.endsWith(".ll") && !n.endsWith("_optimized.ll");
                }).findFirst().orElseThrow(() -> new RuntimeException("no pre-opt dump"));
        }

        Pattern trapDeoptStart = Pattern.compile("call.*@llvm\\.experimental\\.deoptimize.*\\[\\s*\"deopt\"\\((.*?)\\)\\s*\\]");
        int trapsScanned = 0;
        for (String line : Files.readAllLines(ll)) {
            Matcher mm = trapDeoptStart.matcher(line);
            if (!mm.find()) continue;
            trapsScanned++;
            String args = mm.group(1);
            for (String p : deadParams) {
                if (Pattern.compile("\\b" + Pattern.quote(p) + "\\b").matcher(args).find()) {
                    throw new RuntimeException("dead " + p + " pinned live in deopt bundle: " + line);
                }
            }
        }
        if (trapsScanned == 0) {
            throw new RuntimeException("no uncommon_trap deopt bundles found");
        }
    }

    // Decode the trap's deopt bundle and assert exactly which local slots are
    // encoded as T_ILLEGAL placeholders. The i64 operand of each (encode, value)
    // pair packs |index:32|value_type:16|basic_type:16| (DeoptValueEncoding);
    // LocalType==0, T_ILLEGAL==99. A dead two-word local must yield two illegal
    // slots with DISTINCT consecutive indices (i, i+1) -- the earlier code reused
    // index i for both, which this check would catch.
    private static void assertIllegalLocalSlots(String dir, Method m,
                                                long... expectedIndices) throws Exception {
        String prefix = m.getDeclaringClass().getName().replace('.', '_') + "_" + m.getName();
        Path ll;
        try (Stream<Path> s = Files.list(Paths.get(dir))) {
            ll = s.filter(p -> {
                    String n = p.getFileName().toString();
                    return n.startsWith(prefix) && n.endsWith(".ll") && !n.endsWith("_optimized.ll");
                }).findFirst().orElseThrow(() -> new RuntimeException("no pre-opt dump"));
        }

        Pattern trapDeoptStart = Pattern.compile("call.*@llvm\\.experimental\\.deoptimize.*\\[\\s*\"deopt\"\\((.*?)\\)\\s*\\]");
        Pattern i64Const = Pattern.compile("i64 (-?\\d+)");
        List<Long> illegal = null;
        int trapsScanned = 0;
        for (String line : Files.readAllLines(ll)) {
            Matcher mm = trapDeoptStart.matcher(line);
            if (!mm.find()) continue;
            trapsScanned++;
            List<Long> found = new java.util.ArrayList<>();
            Matcher cm = i64Const.matcher(mm.group(1));
            while (cm.find()) {
                long enc = Long.parseLong(cm.group(1));
                long index     = enc >>> 32;
                long valueType = (enc >>> 16) & 0xffff;
                long basicType = enc & 0xffff;
                if (valueType == 0 /*LocalType*/ && basicType == 99 /*T_ILLEGAL*/) {
                    found.add(index);
                }
            }
            illegal = found;
            break; // exactly one trap (the implicit null check) in these methods
        }
        if (trapsScanned == 0) {
            throw new RuntimeException("no uncommon_trap deopt bundles found");
        }
        java.util.Collections.sort(illegal);
        List<Long> expected = new java.util.ArrayList<>();
        for (long e : expectedIndices) expected.add(e);
        if (!illegal.equals(expected)) {
            throw new RuntimeException("illegal local slots " + illegal + " != expected " + expected);
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

        assertDeadParamsAbsentFromTrapDeopt(dir, m, "i32 %1", "i32 %2");
        // At the arraylength trap bci the dead locals are: arr (slot 0 -- its
        // value is on the operand stack, the local is no longer read), x (slot 1),
        // y (slot 2), and len (slot 4, not yet stored). temp (slot 3) is live.
        assertIllegalLocalSlots(dir, m, 0, 1, 2, 4);

        try {
            deadLocalsAtTrap(null, 1, 2);
            throw new RuntimeException("Expected NullPointerException");
        } catch (NullPointerException expected) {
        }
        if (deadLocalsAtTrap(new int[]{0,1,2}, 4, 5) != (4*5 + (5>>>1) + 3)) {
            throw new RuntimeException("Hot path returned wrong value");
        }

        // Two-word (long) dead locals: the i64 values must not be pinned, and the
        // NPE deopt must reconstruct the frame correctly (two illegal slots each).
        Method mLong = TestDeoptLiveness.class.getDeclaredMethod(
            "deadLongLocalsAtTrap", int[].class, long.class, long.class);
        compileAndAwaitDump(mLong, dir);
        FileCheck preLong = new FileCheck(dir, mLong, /*optimized=*/false);
        preLong.checkPattern("define hotspotcc i32 .*TestDeoptLiveness_deadLongLocalsAtTrap");
        preLong.checkPattern("@llvm.experimental.deoptimize");
        assertDeadParamsAbsentFromTrapDeopt(dir, mLong, "i64 %1", "i64 %2");
        // Dead at the arraylength trap bci: arr (slot 0), the two-word x (slots
        // 1-2), the two-word y (slots 3-4), and len (slot 7, not yet stored). temp
        // (slots 5-6) is live. Each dead two-word local must emit two illegal slots
        // at DISTINCT consecutive indices; the earlier reused-index bug would have
        // produced [0,1,1,3,3,7] here instead of [0,1,2,3,4,7].
        assertIllegalLocalSlots(dir, mLong, 0, 1, 2, 3, 4, 7);

        try {
            deadLongLocalsAtTrap(null, 1L, 2L);
            throw new RuntimeException("Expected NullPointerException (long)");
        } catch (NullPointerException expected) {
        }
        if (deadLongLocalsAtTrap(new int[]{0,1,2}, 4L, 5L) != ((int)(4L*5L + (5L>>>1)) + 3)) {
            throw new RuntimeException("Hot path (long) returned wrong value");
        }

        System.out.println("TestDeoptLiveness PASSED");
    }
}
