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
 */

/*
 * @test
 * @summary Exercise range checks in a runtime-versionable inclusive
 *          non-unit-stride array-add loop compiled by Jeandle.
 * @library /test/lib /
 * @build compiler.jeandle.fileCheck.FileCheck
 * @run main/othervm -Xcomp -Xbatch -XX:-TieredCompilation
 *      -XX:-UseOnStackReplacement -XX:+UseJeandleCompiler
 *      -XX:+JeandleDumpIR
 *      -XX:CompileCommand=compileonly,compiler.jeandle.loopopts.TestInclusiveArrayAddRangeCheck::hotLoop
 *      compiler.jeandle.loopopts.TestInclusiveArrayAddRangeCheck
 */

package compiler.jeandle.loopopts;

import java.lang.reflect.Method;

import compiler.jeandle.fileCheck.FileCheck;

public class TestInclusiveArrayAddRangeCheck {
    private static final int LENGTH = 4096;
    private static final int LAST_INDEX = 2047;

    static void hotLoop(int[] a, int[] b, int[] c, int start, int limit) {
        for (int i = start; i <= limit; i += 2) {
            a[i] = b[i] + c[i];
        }
    }

    public static void main(String[] args) throws Exception {
        int[] a = new int[LENGTH];
        int[] b = new int[LENGTH];
        int[] c = new int[LENGTH];
        for (int i = 0; i < LENGTH; i++) {
            b[i] = i * 3 + 1;
            c[i] = i * 5 - 2;
        }

        // Exercise both parities while keeping start and limit as parameters
        // of the separately compiled root method.
        for (int iteration = 0; iteration < 20_000; iteration++) {
            hotLoop(a, b, c, 0, LAST_INDEX);
            hotLoop(a, b, c, 1, LAST_INDEX);
        }

        for (int i = 0; i <= LAST_INDEX; i++) {
            int expected = b[i] + c[i];
            if (a[i] != expected) {
                throw new AssertionError("wrong result at " + i
                        + ": expected " + expected + ", got " + a[i]);
            }
        }
        for (int i = LAST_INDEX + 1; i < LENGTH; i++) {
            if (a[i] != 0) {
                throw new AssertionError("unexpected write at " + i);
            }
        }

        // A zero-trip invocation must not evaluate any array access, even when
        // loop versioning introduces guards before the fast loop.
        hotLoop(null, null, null, 1, 0);

        // Preserve precise Java exception and partial-side-effect semantics:
        // iteration zero writes a[0], then c[2] fails on the next iteration.
        int[] partialA = new int[4];
        int[] partialB = { 10, 20, 30, 40 };
        int[] shortC = { 1, 2 };
        boolean failed = false;
        try {
            hotLoop(partialA, partialB, shortC, 0, 2);
        } catch (ArrayIndexOutOfBoundsException expected) {
            failed = true;
        }
        if (!failed) {
            throw new AssertionError("expected ArrayIndexOutOfBoundsException");
        }
        if (partialA[0] != 11 || partialA[2] != 0) {
            throw new AssertionError("incorrect partial side effects");
        }

        Method method = TestInclusiveArrayAddRangeCheck.class.getDeclaredMethod(
                "hotLoop", int[].class, int[].class, int[].class,
                int.class, int.class);
        FileCheck fileCheck = new FileCheck(
                System.getProperty("user.dir"), method, true);
        fileCheck.check("vector.ph:");
        fileCheck.check("vector.body:");
        fileCheck.checkPattern("%.* = load <[1-9][0-9]* x i32>, "
                + "ptr addrspace\\(1\\) %.*");
        fileCheck.checkPattern("%.* = load <[1-9][0-9]* x i32>, "
                + "ptr addrspace\\(1\\) %.*");
        fileCheck.checkPattern("%.* = add <[1-9][0-9]* x i32> %.*, %.*");
        fileCheck.checkPattern(
                "!.* = !\\{!\\\"llvm\\.loop\\.isvectorized\\\", i32 1\\}");
    }
}
