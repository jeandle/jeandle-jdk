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
 * @summary Test the intrinsic implementation of Object.hashCode with vtable guard
 * @requires os.arch=="amd64" | os.arch=="x86_64" | os.arch=="aarch64"
 * @library /test/lib /
 * @run main/othervm compiler.jeandle.intrinsic.TestHashCode
 */

package compiler.jeandle.intrinsic;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import compiler.jeandle.fileCheck.FileCheck;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestHashCode {

    // Per-call-site log line emitted by Jeandle when Object.hashCode is recognized.
    private static final String INTRINSIC_LOG_LINE =
        "Method `virtual jint java.lang.Object.hashCode()` is parsed as intrinsic";

    public static void main(String[] args) throws Exception {
        String dumpPath = Files.createTempDirectory("jeandle_hashcode").toString();

        ArrayList<String> commandArgs = new ArrayList<>(List.of(
                "-Xbatch",
                "-XX:-TieredCompilation",
                "-XX:+UseJeandleCompiler",
                "-Xcomp",
                "-Xlog:jeandle=debug",
                "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dumpPath,
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::hashOf",
                TestWrapper.class.getName()));

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(commandArgs);
        OutputAnalyzer output = ProcessTools.executeCommand(pb);

        output.shouldHaveExitValue(0);

        // Expecting count = 1: hashOf is intrinsified on first compilation when
        // only Object is loaded. Later, when Overridden is loaded and hashOf(ov)
        // triggers the vtable guard, the deopt may cause recompilation — but CHA
        // then sees the override, so the intrinsic candidate is no longer set
        // and no additional intrinsic-log line is emitted.
        Matcher m = Pattern.compile(Pattern.quote(INTRINSIC_LOG_LINE)).matcher(output.getOutput());
        int intrinsicCount = 0;
        while (m.find()) intrinsicCount++;
        Asserts.assertEQ(intrinsicCount, 1,
            "Expected exactly 1 intrinsic-log entry for hashOf, got " + intrinsicCount);

        // Verify the intrinsified IR (first compilation, before CHA deopt) contains
        // the vtable guard + fast/slow/merge structure. fileIndex=0 picks the first
        // compilation; the second (post-deopt) has no intrinsic and is skipped.
        FileCheck fc = new FileCheck(dumpPath,
                TestWrapper.class.getDeclaredMethod("hashOf", Object.class),
                false,  // raw IR (.ll), not optimized
                0);     // first compilation — has the intrinsic
        // Vtable guard: load receiver's vtable slot, compare to Object.hashCode's Method*.
        // Patterns are ordered to match IR line order (FileCheck walks forward only).
        fc.checkPattern("hashCode.methods_match");
        fc.checkPattern("hashCode_guard_fail");
        // Fast path block + jeandle.hashcode_fast JavaOp call.
        fc.checkPattern("hashCode_fast");
        fc.checkPattern("call hotspotcc i32 @jeandle\\.hashcode_fast");
        // Slow path block + hashcode_slow runtime routine call.
        fc.checkPattern("hashCode_slow");
        fc.checkPattern("@hashcode_slow");
        // Merge block + result PHI.
        fc.checkPattern("hashCode_merge");
        fc.checkPattern("hashCode.result");
    }

    // TestWrapper exercises all four combinations of:
    //   (a) receiver class overrides hashCode?  → drives vtable guard
    //   (b) fast path succeeds?                 → drives fast/slow selection
    static class TestWrapper {
        public static void main(String[] args) {
            // Prime CP resolution for Object.hashCode so the first compilation
            // of hashOf avoids Jeandle's Reason_unloaded deopt at the invokevirtual.
            int preload = new Object().hashCode();
            if (preload == 0) {
                throw new RuntimeException("prime failed");
            }

            // === Scenario 1: not overridden + fast path fails (no_hash) ===
            // Fresh objects have no hash installed → slow path installs it via FastHashCode.
            for (int i = 0; i < 50; i++) {
                Object fresh = new Object();
                int h = hashOf(fresh);
                int id = System.identityHashCode(fresh);
                if (h != id) {
                    throw new RuntimeException("scenario 1 (no_hash) mismatch at i=" + i
                        + ": " + h + " != " + id);
                }
            }

            // === Scenario 2: not overridden + fast path fails (locked) ===
            // Pre-install hash; synchronized → stack-locked mark (LM_LEGACY) → slow path.
            Object locked = new Object();
            int lockedExpected = System.identityHashCode(locked);
            synchronized (locked) {
                int h = hashOf(locked);
                if (h != lockedExpected) {
                    throw new RuntimeException("scenario 2 (locked) mismatch: "
                        + h + " != " + lockedExpected);
                }
            }

            // === Scenario 3: not overridden + fast path succeeds ===
            // Hash pre-installed, mark unlocked → jeandle.hashcode_fast returns hash inline.
            Object o = new Object();
            int expected = System.identityHashCode(o);
            for (int i = 0; i < 20_000; i++) {
                int h = hashOf(o);
                if (h != expected) {
                    throw new RuntimeException("scenario 3 (fast path) mismatch at i=" + i
                        + ": " + h + " != " + expected);
                }
            }

            // === Scenario 4: overridden + vtable guard fails (must run last: CHA deopt) ===
            // Overridden trips the guard → uncommon_trap → interpreter returns 42.
            Overridden ov = new Overridden();
            int overrideResult = hashOf(ov);
            if (overrideResult != 42) {
                throw new RuntimeException("scenario 4 (override) mismatch: "
                    + overrideResult + " != 42");
            }

            System.out.println("TestHashCode PASSED");
        }

        // Compiled by Jeandle — invokevirtual Object.hashCode with vtable guard.
        static int hashOf(Object o) {
            return o.hashCode();
        }

        // Overrides hashCode — loaded after hashOf is compiled to exercise the guard.
        static class Overridden {
            @Override
            public int hashCode() {
                return 42;
            }
        }
    }
}
