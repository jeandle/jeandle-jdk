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
 * @summary Verify Jeandle folds loops over newly allocated constant-size
 *          arrays while preserving the escaping array allocation, and record
 *          whether a ten-element loop reaches the vectorizer.
 * @library /test/lib /
 * @build compiler.jeandle.fileCheck.FileCheck
 * @run main/othervm -Xcomp -Xbatch -XX:-TieredCompilation
 *      -XX:-UseOnStackReplacement -XX:+UseJeandleCompiler
 *      -XX:+JeandleDumpIR
 *      -XX:CompileCommand=compileonly,compiler.jeandle.loopopts.TestSingleElementArrayLoop::*
 *      compiler.jeandle.loopopts.TestSingleElementArrayLoop
 */

package compiler.jeandle.loopopts;

import java.lang.reflect.Method;

import compiler.jeandle.fileCheck.FileCheck;

public class TestSingleElementArrayLoop {
    public static void main(String[] args) throws Exception {
        int[] result = initialize();
        if (result.length != 1 || result[0] != 0) {
            throw new AssertionError("unexpected initialized array");
        }

        int[] tenResult = initializeTen();
        if (tenResult.length != 10) {
            throw new AssertionError("unexpected ten-element array length");
        }
        for (int i = 0; i < tenResult.length; i++) {
            if (tenResult[i] != i) {
                throw new AssertionError("unexpected ten-element value at " + i);
            }
        }

        Method method = TestSingleElementArrayLoop.class.getDeclaredMethod(
                "initialize");
        FileCheck initial = new FileCheck(System.getProperty("user.dir"),
                                          method, false);
        initial.checkPattern("%[0-9]+ = icmp ult i32 %.*, %.*");

        FileCheck optimized = new FileCheck(System.getProperty("user.dir"),
                                            method, true);
        optimized.checkPattern("define hotspotcc .*ptr addrspace\\(1\\).*"
                + "TestSingleElementArrayLoop_initialize");
        optimized.checkPattern("@new_array.*i32 1");
        optimized.checkNotPattern("%[0-9]+ = icmp ult i32 %.*, %.*");
        optimized.checkNot("llvm.loop");
        optimized.checkNot("vector.body:");

        Method tenMethod = TestSingleElementArrayLoop.class.getDeclaredMethod(
                "initializeTen");
        FileCheck tenInitial = new FileCheck(System.getProperty("user.dir"),
                                             tenMethod, false);
        tenInitial.checkPattern("%[0-9]+ = icmp ult i32 %.*, %.*");

        FileCheck tenOptimized = new FileCheck(
                System.getProperty("user.dir"), tenMethod, true);
        tenOptimized.checkPattern("define hotspotcc .*ptr addrspace\\(1\\).*"
                + "TestSingleElementArrayLoop_initializeTen");
        tenOptimized.checkPattern("@new_array.*i32 10");
        // The pre-PEA unrolled stores are now exposed to LLVM's native SLP
        // vectorizer after the post-transform poll cleanup.
        tenOptimized.checkPattern("store <4 x i32>");
        tenOptimized.checkPattern("store <4 x i32>");
        tenOptimized.checkNotPattern("%[0-9]+ = icmp ult i32 %.*, %.*");
    }

    private static int[] initialize() {
        int[] arr = new int[1];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = i;
        }
        return arr;
    }

    private static int[] initializeTen() {
        int[] arr = new int[10];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = i;
        }
        return arr;
    }
}
