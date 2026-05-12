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

/**
 * @test
 * @summary Test allocation correctness under various VM option combinations
 * @library /test/lib /
 * @run driver compiler.jeandle.bytecodeTranslate.alloc.TestAllocVMOptions
 */

package compiler.jeandle.bytecodeTranslate.alloc;

import java.util.ArrayList;
import java.util.List;

import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestAllocVMOptions {

    // Inner class used as the child process entry point
    public static class AllocWorker {
        static class Obj {
            int a;
            long b;
            double c;
            String d;

            Obj(int a) {
                this.a = a;
                this.b = a * 2L;
                this.c = a * 3.0;
                this.d = "v" + a;
            }
        }

        public static void main(String[] args) {
            for (int i = 0; i < 10_000; i++) {
                Obj obj = new Obj(i);
                if (obj.a != i || obj.b != i * 2L || obj.c != i * 3.0 || !obj.d.equals("v" + i)) {
                    throw new RuntimeException("Field mismatch at iteration " + i);
                }
            }
            System.out.println("AllocWorker passed.");
        }
    }

    public static void main(String[] args) throws Exception {
        // Test 1: Default options (UseTLAB=true, ZeroTLAB=false)
        runWithOptions("default", List.of());

        // Test 2: UseTLAB disabled - all allocations go through slow path
        runWithOptions("-UseTLAB", List.of("-XX:-UseTLAB"));

        // Test 3: ZeroTLAB enabled - skip memset in fast path (TLAB is pre-zeroed)
        runWithOptions("+ZeroTLAB", List.of("-XX:+ZeroTLAB"));

        // Test 4: Small TLAB - force frequent TLAB refills, stress fast/slow path transitions
        runWithOptions("small TLAB", List.of("-XX:TLABSize=2k", "-XX:-ResizeTLAB"));

        // Test 5: Both UseTLAB disabled and ZeroTLAB (ZeroTLAB should be irrelevant)
        runWithOptions("-UseTLAB +ZeroTLAB", List.of("-XX:-UseTLAB", "-XX:+ZeroTLAB"));

        // Test 6: Large TLAB - fewer refills, objects mostly in fast path
        runWithOptions("large TLAB", List.of("-XX:TLABSize=4m", "-XX:-ResizeTLAB"));

        System.out.println("All TestAllocVMOptions tests passed.");
    }

    static void runWithOptions(String label, List<String> extraOpts) throws Exception {
        ArrayList<String> cmdArgs = new ArrayList<>(List.of(
            "-Xcomp",
            "-XX:-TieredCompilation",
            "-XX:+UseJeandleCompiler",
            "-XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.alloc.TestAllocVMOptions$AllocWorker::*"
        ));
        cmdArgs.addAll(extraOpts);
        cmdArgs.add(AllocWorker.class.getName());

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(cmdArgs);
        OutputAnalyzer output = ProcessTools.executeCommand(pb);
        output.shouldHaveExitValue(0);
        output.shouldContain("AllocWorker passed.");
        System.out.println("  [PASS] " + label);
    }
}
