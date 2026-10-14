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
 */

/*
 * @test
 * @summary IRCE versions a positive non-unit-stride array loop with exact
 *          range checks while preserving the precise deopt path.
 * @library /test/lib /
 * @build compiler.jeandle.fileCheck.FileCheck
 * @run main/othervm -Xcomp -Xbatch -XX:-TieredCompilation
 *      -XX:-UseOnStackReplacement -XX:+UseJeandleCompiler
 *      -XX:+JeandleDumpIR
 *      -XX:CompileCommand=compileonly,compiler.jeandle.loopopts.TestRangeCheckDeoptPollElimination::storeStrideTwo
 *      compiler.jeandle.loopopts.TestRangeCheckDeoptPollElimination
 */

package compiler.jeandle.loopopts;

import java.lang.reflect.Method;

import compiler.jeandle.fileCheck.FileCheck;

public class TestRangeCheckDeoptPollElimination {
    static void storeStrideTwo(int[] array, int start, int end) {
        for (int i = start; i < end; i += 2) {
            array[i] = i;
        }
    }

    public static void main(String[] args) throws Exception {
        int[] array = new int[4096];
        storeStrideTwo(array, 0, array.length);
        for (int i = 0; i < array.length; i++) {
            int expected = (i & 1) == 0 ? i : 0;
            if (array[i] != expected) {
                throw new AssertionError("wrong value at " + i);
            }
        }

        // The dynamic end does not by itself prove that i += 2 avoids int wrap.
        // Predication must either guard against that wrap or deopt, preserving
        // the precise Java exception.
        boolean failed = false;
        try {
            storeStrideTwo(new int[4], Integer.MAX_VALUE - 2,
                           Integer.MAX_VALUE);
        } catch (ArrayIndexOutOfBoundsException expected) {
            failed = true;
        }
        if (!failed) {
            throw new AssertionError("expected ArrayIndexOutOfBoundsException");
        }

        Method method = TestRangeCheckDeoptPollElimination.class
                .getDeclaredMethod("storeStrideTwo", int[].class,
                                   int.class, int.class);
        FileCheck initial = new FileCheck(System.getProperty("user.dir"),
                                          method, false);
        initial.checkPattern("br i1 %[0-9]+, label "
                + "%bci_[0-9]+_boundary_check_pass, label "
                + "%bci_[0-9]+_boundary_check_fail");
        initial.checkPattern("@llvm\\.experimental\\.deoptimize.*i32 -26");

        // The stock loop pipeline widens the Java IV to i64 while building a
        // check-free main loop. The exact range check remains on the scalar
        // fallback so an out-of-bounds iteration still deoptimizes precisely.
        // Use independent checks because CFG layout does not guarantee whether
        // the deopt block is printed before or after its guarding branch.
        FileCheck deoptCheck = new FileCheck(System.getProperty("user.dir"),
                                             method, true);
        deoptCheck.checkPattern("@llvm.experimental.gc.statepoint.*i32 -26");

        FileCheck branchCheck = new FileCheck(System.getProperty("user.dir"),
                                              method, true);
        branchCheck.checkPattern("br i1 %[0-9]+, label "
                + "%bci_[0-9]+_boundary_check_pass, label "
                + "%bci_[0-9]+_boundary_check_fail");
    }
}
