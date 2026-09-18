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
 * @summary Verify Jeandle IRCE versions counted loops without widenable
 *          conditions and preserves Java exception and zero-trip semantics.
 * @library /test/lib /
 * @build compiler.jeandle.fileCheck.FileCheck
 * @run main/othervm -Xcomp -Xbatch -XX:-TieredCompilation
 *      -XX:+UseJeandleCompiler
 *      -XX:+JeandleDumpIR
 *      -XX:CompileCommand=compileonly,compiler.jeandle.loopopts.TestLoopCheckHoisting::sum*
 *      compiler.jeandle.loopopts.TestLoopCheckHoisting
 */

package compiler.jeandle.loopopts;

import java.lang.reflect.Method;

import compiler.jeandle.fileCheck.FileCheck;
import jdk.test.lib.Asserts;

public class TestLoopCheckHoisting {
    private static int sideEffects;

    public static void main(String[] args) throws Exception {
        int[] array = { 1, 2, 3, 4, 5, 6, 7, 8 };

        Asserts.assertEquals(sum(array, 0, array.length), 36L);
        Asserts.assertEquals(sum(array, 3, 3), 0L);
        Asserts.assertEquals(sum(null, 0, 0), 0L);

        expectThrows(NullPointerException.class, () -> sum(null, 0, 1));
        expectThrows(ArrayIndexOutOfBoundsException.class,
                     () -> sum(array, -1, 1));
        expectThrows(ArrayIndexOutOfBoundsException.class,
                     () -> sum(array, 0, array.length + 1));

        sideEffects = 0;
        expectThrows(ArrayIndexOutOfBoundsException.class,
                     () -> sumWithSideEffect(array, -1, 1));
        Asserts.assertEquals(sideEffects, 1,
                            "A negative pre-loop index must fail on its current iteration");

        sideEffects = 0;
        expectThrows(ArrayIndexOutOfBoundsException.class,
                     () -> sumWithSideEffect(array, 0, array.length + 1));
        Asserts.assertEquals(sideEffects, array.length + 1,
                            "The checked post-loop must keep the current iteration state");

        int[] wrapArray = { 11, 22, 33 };
        Asserts.assertEquals(sumAcrossIntWrap(wrapArray), 66L,
                            "Java int IV wraparound must remain defined");
        sideEffects = 0;
        expectThrows(ArrayIndexOutOfBoundsException.class,
                     () -> sumAcrossIntWrapWithFailure(wrapArray));
        Asserts.assertEquals(sideEffects, wrapArray.length + 1,
                            "The failing wrapped iteration must not be skipped or replayed");

        Method sumMethod = TestLoopCheckHoisting.class.getDeclaredMethod(
                "sum", int[].class, int.class, int.class);
        checkInitialIR(sumMethod);
        checkOptimizedIR(sumMethod);

        Method sideEffectMethod = TestLoopCheckHoisting.class.getDeclaredMethod(
                "sumWithSideEffect", int[].class, int.class, int.class);
        checkInitialIR(sideEffectMethod);
        checkOptimizedIR(sideEffectMethod);
    }

    private static long sum(int[] array, int from, int to) {
        long result = 0;
        for (int i = from; i < to; i++) {
            result += array[i];
        }
        return result;
    }

    private static long sumWithSideEffect(int[] array, int from, int to) {
        long result = 0;
        for (int i = from; i < to; i++) {
            sideEffects++;
            result += array[i];
        }
        return result;
    }

    private static long sumAcrossIntWrap(int[] array) {
        long result = 0;
        int start = Integer.MAX_VALUE - 2;
        for (int i = start; i > Integer.MIN_VALUE; i++) {
            result += array[i - start];
        }
        return result;
    }

    private static long sumAcrossIntWrapWithFailure(int[] array) {
        long result = 0;
        int start = Integer.MAX_VALUE - 2;
        for (int i = start; i >= Integer.MIN_VALUE; i++) {
            sideEffects++;
            result += array[i - start];
        }
        return result;
    }

    private static void checkInitialIR(Method method) throws Exception {
        FileCheck fileCheck = new FileCheck(System.getProperty("user.dir"), method, false);
        fileCheck.checkPattern("%[0-9]+ = icmp ult i32 %.*, %.*");
        fileCheck.checkPattern("br i1 %[0-9]+, label "
                + "%bci_[0-9]+_boundary_check_pass, label %bci_[0-9]+_boundary_check_fail");
        fileCheck.checkPattern("bci_[0-9]+_boundary_check_fail:");
        // The exact guard retains the ordinary Java range-check trap reason.
        fileCheck.checkPattern("@llvm\\.experimental\\.deoptimize.*i32 -26.*\\[ \\\"deopt\\\"");
    }

    private static void checkOptimizedIR(Method method) throws Exception {
        // Predication checks the full iteration range before executing the
        // loop, so a failed guard can safely deopt from the method-entry state.
        FileCheck deoptCheck = new FileCheck(System.getProperty("user.dir"), method, true);
        deoptCheck.checkPattern("@llvm\\.experimental\\.gc\\.statepoint.*i32 -26.*"
                + "\\[ \\\"deopt\\\"");

        // Strip mining keeps one poll on the outer loop.
        FileCheck pollCheck = new FileCheck(System.getProperty("user.dir"), method, true);
        pollCheck.check("do_safepoint.i:");
        pollCheck.checkPattern("@llvm\\.experimental\\.gc\\.statepoint.*@safepoint_handler");

        FileCheck fileCheck = new FileCheck(System.getProperty("user.dir"), method, true);
        fileCheck.checkNotPattern("br i1 %[0-9]+, label "
                + "%bci_[0-9]+_boundary_check_pass, label %bci_[0-9]+_boundary_check_fail");
        fileCheck.checkNot("postloop:");
    }

    private static <T extends Throwable> void expectThrows(
            Class<T> expected, ThrowingRunnable action) throws Exception {
        try {
            action.run();
        } catch (Throwable actual) {
            if (expected.isInstance(actual)) {
                return;
            }
            throw new AssertionError("Expected " + expected.getName()
                                     + ", got " + actual, actual);
        }
        throw new AssertionError("Expected " + expected.getName());
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
