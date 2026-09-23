/*
 * Copyright (c) 2026, the Jeandle-JDK Authors. All rights reserved.
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
 * accompanied this work).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 */

/*
 * @test
 * @summary PEA support for the System.arraycopy intrinsic: a validated copy
 *          between two virtual arrays folds into field state (both arrays
 *          and the pseudo call are eliminated), overlapping self-copies
 *          keep memmove semantics, and a deopt after the fold reconstructs
 *          the copied values in the interpreter.
 * @library /test/lib /
 * @build jdk.test.lib.Asserts compiler.jeandle.fileCheck.FileCheck
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.pea.TestPEAArrayCopyFold
 */

package compiler.jeandle.pea;

import compiler.jeandle.fileCheck.FileCheck;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestPEAArrayCopyFold {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestPEAArrayCopyFold$TestWrapper";
    private static final String DEOPT_WRAPPER =
            "compiler.jeandle.pea.TestPEAArrayCopyFold$DeoptWrapper";

    public static void main(String[] args) throws Exception {
        checkFoldIR();
        checkDeoptAfterFold();
    }

    // Child A compiles the fold shapes with the Jeandle compiler and dumps
    // the optimized IR: both array allocations, the element stores, and the
    // arraycopy pseudo call must be gone (fully scalar-replaced).
    private static void checkFoldIR() throws Exception {
        String dump_path = System.getProperty("user.dir");
        ArrayList<String> command_args = new ArrayList<String>(List.of(
                "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
                "-XX:+JeandleDoPEA",
                "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dump_path,
                "-XX:CompileCommand=compileonly," + WRAPPER + "::fold",
                "-XX:CompileCommand=compileonly," + WRAPPER + "::overlap",
                WRAPPER));

        OutputAnalyzer output = ProcessTools.executeCommand(
                ProcessTools.createLimitedTestJavaProcessBuilder(command_args));
        output.shouldHaveExitValue(0);
        output.shouldContain("fold result: 18");
        output.shouldContain("overlap result: 3");

        for (String name : List.of("fold", "overlap")) {
            Method method = name.equals("fold")
                    ? TestWrapper.class.getMethod("fold", int.class)
                    : TestWrapper.class.getMethod("overlap");
            FileCheck checker = new FileCheck(dump_path, method,
                    /*optimized=*/true);
            checker.checkPattern("define .*" + name + ".*");
            checker.checkNot("jeandle.new_array");
            checker.checkNot("jeandle.arraycopy");
            checker.checkNot("store atomic");
        }
    }

    // Child B warms up a method whose cold branch is never taken, so the
    // level-4 compilation folds the arraycopy and profiles the branch as an
    // uncommon trap. Taking the cold branch deoptimizes mid-method: the
    // interpreter must reconstruct the scalar-replaced destination array
    // with the values the fold propagated (not the Java defaults).
    private static void checkDeoptAfterFold() throws Exception {
        ArrayList<String> command_args = new ArrayList<String>(List.of(
                "-Xbatch", "-XX:+UseJeandleCompiler", "-XX:+JeandleDoPEA",
                "-Xlog:deoptimization=debug",
                "-XX:CompileCommand=compileonly," + DEOPT_WRAPPER + "::foldDeopt",
                DEOPT_WRAPPER));

        OutputAnalyzer output = ProcessTools.executeCommand(
                ProcessTools.createLimitedTestJavaProcessBuilder(command_args));
        output.shouldHaveExitValue(0);
        output.shouldContain("warm result: 18");
        output.shouldContain("cold result: 567");
        // The cold branch deoptimizes (unstable-if) and is reinterpreted.
        output.shouldMatch("foldDeopt.*unstable_if");
    }

    public static class TestWrapper {
        public static void main(String[] args) {
            System.out.println("fold result: " + fold(5));
            System.out.println("overlap result: " + overlap());
        }

        // Compiled by Jeandle. Both arrays are virtual; the validated
        // arraycopy(src, 0, dest, 0, 3) folds, and the element loads fold to
        // the copied values 5 + 6 + 7.
        public static int fold(int x) {
            int[] src = new int[]{x, x + 1, x + 2, 0};
            int[] dest = new int[4];
            System.arraycopy(src, 0, dest, 0, 3);
            return dest[0] + dest[1] + dest[2];
        }

        // Overlapping self-copy (arraycopy(a, 0, a, 1, 2)): a becomes
        // [1, 1, 2, 0] — the reads must observe the pre-copy source values.
        public static int overlap() {
            int[] a = new int[]{1, 2, 3, 0};
            System.arraycopy(a, 0, a, 1, 2);
            return a[1] + a[2];
        }
    }

    public static class DeoptWrapper {
        public static void main(String[] args) {
            long acc = 0;
            for (int i = 0; i < 20_000; i++) {
                acc += foldDeopt(5, false);
            }
            Asserts.assertEquals(acc, 20_000L * 18, "warm accumulation");
            System.out.println("warm result: " + foldDeopt(5, false));
            System.out.println("cold result: " + foldDeopt(5, true));
        }

        // The cold branch is an unstable-if uncommon trap after warmup. The
        // deopt bundle describes the scalar-replaced arrays; the interpreter
        // reconstruction must carry the COPIED values (5, 6, 7 — not the
        // defaults 0, 0, 0) into the cold path.
        public static int foldDeopt(int x, boolean cold) {
            int[] src = new int[]{x, x + 1, x + 2, 0};
            int[] dest = new int[4];
            System.arraycopy(src, 0, dest, 0, 3);
            if (cold) {
                return dest[0] * 100 + dest[1] * 10 + dest[2];
            }
            return dest[0] + dest[1] + dest[2];
        }
    }
}
