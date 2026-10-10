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
 * @summary Verify Jeandle OSR loops expose exact range checks to IRCE,
 *          vectorize after OSR migration, and deoptimize with the migrated
 *          current loop state.
 * @library /test/lib /
 * @build compiler.jeandle.fileCheck.FileCheck jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -Xbootclasspath/a:. -Xbatch -XX:-TieredCompilation
 *      -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *      -XX:+UseOnStackReplacement -XX:+UseJeandleCompiler
 *      -XX:+JeandleDumpIR
 *      compiler.jeandle.loopopts.TestOSRRangeCheckHoisting
 */

package compiler.jeandle.loopopts;

import java.lang.reflect.Method;

import compiler.jeandle.fileCheck.FileCheck;
import jdk.test.lib.Asserts;
import jdk.test.whitebox.WhiteBox;

public class TestOSRRangeCheckHoisting {
    private static final WhiteBox WB = WhiteBox.getWhiteBox();
    private static final int ITERATIONS = 200_000;

    private static int sideEffects;
    private static volatile long sink;

    public static void main(String[] args) throws Exception {
        int[] array = new int[ITERATIONS];
        long expected = 0;
        for (int i = 0; i < array.length; i++) {
            array[i] = i;
            expected += i;
        }

        Asserts.assertEquals(osrSum(array, array.length), expected);
        assertOSRCompiled("osrSum");

        int[] vectorDst = new int[ITERATIONS];
        osrVectorStore(vectorDst, vectorDst.length);
        assertOSRCompiled("osrVectorStore", int[].class, int.class);
        for (int i = 0; i < vectorDst.length; i++) {
            Asserts.assertEquals(vectorDst[i], i * 3 + 1,
                                 "wrong vector result at index " + i);
        }

        sideEffects = 0;
        expectThrows(ArrayIndexOutOfBoundsException.class,
                     () -> sink = osrFailAfterMigration(array, array.length + 1));
        // The side effect precedes the failing load, so it is expected once for
        // every valid iteration plus once for the failing iteration.  A deopt
        // reconstructed from method entry would make this value larger.
        Asserts.assertEquals(sideEffects, array.length + 1,
                            "OSR post-loop deopt must not replay migrated iterations");

        int wrapStart = Integer.MAX_VALUE - ITERATIONS + 1;
        Asserts.assertEquals(osrAcrossIntWrap(array, wrapStart), expected,
                            "OSR compilation must preserve Java int IV wraparound");
        assertOSRCompiled("osrAcrossIntWrap");

        Method method = TestOSRRangeCheckHoisting.class.getDeclaredMethod(
                "osrSum", int[].class, int.class);
        checkInitialOSRIR(method);
        checkOptimizedOSRIR(method);

        Method vectorMethod = TestOSRRangeCheckHoisting.class.getDeclaredMethod(
                "osrVectorStore", int[].class, int.class);
        checkVectorizedOSRIR(vectorMethod);

        Method failingMethod = TestOSRRangeCheckHoisting.class.getDeclaredMethod(
                "osrFailAfterMigration", int[].class, int.class);
        checkInitialOSRIR(failingMethod);
        checkOptimizedOSRIR(failingMethod);
    }

    private static long osrSum(int[] array, int limit) {
        long result = 0;
        for (int i = 0; i < limit; i++) {
            result += array[i];
        }
        return result;
    }

    private static void osrVectorStore(int[] dst, int limit) {
        for (int i = 0; i < limit; i++) {
            dst[i] = i * 3 + 1;
        }
    }

    private static long osrFailAfterMigration(int[] array, int limit) {
        long result = 0;
        for (int i = 0; i < limit; i++) {
            sideEffects++;
            result += array[i];
        }
        return result;
    }

    private static long osrAcrossIntWrap(int[] array, int start) {
        long result = 0;
        for (int i = start; i > Integer.MIN_VALUE; i++) {
            result += array[i - start];
        }
        return result;
    }

    private static void assertOSRCompiled(String name, Class<?>... types)
            throws Exception {
        Method method = TestOSRRangeCheckHoisting.class.getDeclaredMethod(
                name, types.length == 0
                        ? new Class<?>[] { int[].class, int.class }
                        : types);
        Asserts.assertTrue(WB.isMethodCompiled(method, true),
                           name + " must have an installed OSR compilation");
    }

    private static void checkVectorizedOSRIR(Method method) throws Exception {
        // One invocation is enough to trigger an OSR compilation but not a
        // normal-entry compilation. Select the first dump explicitly and prove
        // that the vector loop belongs to the OSR root, rather than accepting a
        // later ordinary compilation of the same Java method.
        FileCheck fileCheck = new FileCheck(System.getProperty("user.dir"),
                                            method, true, 0);
        fileCheck.check("define hotspotcc void @\"__jeandle_osr.");
        fileCheck.check("vector.ph:");
        fileCheck.check("vector.body:");
        fileCheck.checkPattern("store <[1-9][0-9]* x i32> %.*, "
                + "ptr addrspace\\(1\\) %.*");
        fileCheck.checkPattern(
                "!.* = !\\{!\\\"llvm\\.loop\\.isvectorized\\\", i32 1\\}");
    }

    private static void checkInitialOSRIR(Method method) throws Exception {
        // The test may subsequently trigger a normal-entry recompilation (for
        // example after a deliberate range-check failure).  Select the first
        // compilation dump, which is the OSR compilation under test.
        FileCheck fileCheck = new FileCheck(System.getProperty("user.dir"), method, false, 0);
        fileCheck.check("define hotspotcc i64 @\"__jeandle_osr.");
        fileCheck.checkPattern("%[0-9]+ = icmp ult i32 %.*, %.*");
        fileCheck.checkPattern("br i1 %[0-9]+, label "
                + "%bci_[0-9]+_boundary_check_pass, label %bci_[0-9]+_boundary_check_fail");
        fileCheck.checkPattern("bci_[0-9]+_boundary_check_fail:.*preds = %bci_[0-9]+_null_check_pass");
        fileCheck.check("@llvm.experimental.deoptimize.i64");
    }

    private static void checkOptimizedOSRIR(Method method) throws Exception {
        // OSR keeps the exact check at the migrated current iteration. Its
        // failure state must contain the current index and accumulator.
        // Use the first optimized dump for the same reason as above: a failed
        // OSR guard can make the VM produce a later ordinary-entry dump.
        FileCheck deoptCheck = new FileCheck(System.getProperty("user.dir"), method, true, 0);
        deoptCheck.checkPattern("@llvm\\.experimental\\.gc\\.statepoint.*i32 -26.*"
                + "\\[ \\\"deopt\\\".*i32 %[^,]+, i64 65547, i64 %[^,]+");

        FileCheck pollCheck = new FileCheck(System.getProperty("user.dir"), method, true, 0);
        pollCheck.check("do_safepoint.i:");
        pollCheck.checkPattern("@llvm\\.experimental\\.gc\\.statepoint.*@safepoint_handler");

        FileCheck fileCheck = new FileCheck(System.getProperty("user.dir"), method, true, 0);
        fileCheck.check("define hotspotcc i64 @\"__jeandle_osr.");
        // IRCE canonicalizes the exact upper-bound check to an equality
        // between the widened current IV and the clamped array bound. The
        // true edge is still the precise current-iteration range-check deopt.
        fileCheck.checkPattern(
                "%.* = icmp eq i64 %indvars\\.iv, %sext");
        fileCheck.checkPattern("br i1 %.*, label "
                + "%bci_[0-9]+_boundary_check_fail, label "
                + "%bci_[0-9]+_boundary_check_pass");
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
