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
 * @summary Verify Jeandle vectorizes a Java int-array addition loop.
 * @library /test/lib /
 * @build compiler.jeandle.fileCheck.FileCheck
 * @run main/othervm -Xcomp -Xbatch -XX:-TieredCompilation
 *      -XX:-UseOnStackReplacement -XX:+UseJeandleCompiler
 *      -XX:+JeandleDumpIR
 *      -XX:CompileCommand=compileonly,compiler.jeandle.loopopts.TestArrayVectorization::add
 *      compiler.jeandle.loopopts.TestArrayVectorization
 */

package compiler.jeandle.loopopts;

import java.lang.reflect.Method;

import compiler.jeandle.fileCheck.FileCheck;

public class TestArrayVectorization {
    private static final int LENGTH = 16_384;
    private static volatile int sink;

    public static void main(String[] args) throws Exception {
        int[] dst = new int[LENGTH];
        int[] left = new int[LENGTH];
        int[] right = new int[LENGTH];

        for (int i = 0; i < LENGTH; i++) {
            left[i] = i * 3 + 1;
            right[i] = i * 5 - 2;
        }

        add(dst, left, right);

        for (int i = 0; i < LENGTH; i++) {
            int expected = left[i] + right[i];
            if (dst[i] != expected) {
                throw new AssertionError("wrong result at index " + i
                        + ": expected " + expected + ", got " + dst[i]);
            }
        }
        sink = dst[LENGTH - 1];

        Method method = TestArrayVectorization.class.getDeclaredMethod(
                "add", int[].class, int[].class, int[].class);
        checkOptimizedIR(method);
    }

    private static void add(int[] dst, int[] left, int[] right) {
        for (int i = 0; i < dst.length; i++) {
            dst[i] = left[i] + right[i];
        }
    }

    private static void checkOptimizedIR(Method method) throws Exception {
        FileCheck fileCheck = new FileCheck(
                System.getProperty("user.dir"), method, true);

        // A vector loop must contain vector memory operations and arithmetic;
        // merely finding a vector-loop metadata node is not sufficient.
        fileCheck.check("vector.ph:");
        fileCheck.check("vector.body:");
        fileCheck.checkPattern("%.* = load <[1-9][0-9]* x i32>, "
                + "ptr addrspace\\(1\\) %.*");
        fileCheck.checkPattern("%.* = load <[1-9][0-9]* x i32>, "
                + "ptr addrspace\\(1\\) %.*");
        fileCheck.checkPattern("%.* = add <[1-9][0-9]* x i32> %.*, %.*");
        fileCheck.checkPattern("store <[1-9][0-9]* x i32> %.*, "
                + "ptr addrspace\\(1\\) %.*");
        fileCheck.checkPattern("!.* = !\\{!\\\"llvm\\.loop\\.isvectorized\\\", i32 1\\}");
    }
}
