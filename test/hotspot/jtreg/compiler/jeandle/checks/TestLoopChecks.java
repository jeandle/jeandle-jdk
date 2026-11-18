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
 * @summary Loop check optimization preserves zero trips, partial effects, and predicate feedback
 * @library /test/lib /
 * @build compiler.jeandle.fileCheck.FileCheck jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *      -Xbatch -XX:-TieredCompilation -XX:+UseJeandleCompiler -XX:CompileThreshold=100
 *      -XX:+JeandleDumpIR -XX:CompileCommand=compileonly,compiler.jeandle.checks.TestLoopChecks::test*
 *      compiler.jeandle.checks.TestLoopChecks
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *      -Xbatch -XX:-TieredCompilation -XX:+UseJeandleCompiler -XX:CompileThreshold=100
 *      -XX:+JeandleDumpIR -XX:CompileCommand=compileonly,compiler.jeandle.checks.TestLoopChecks::test*
 *      -XX:-JeandleDoPEA
 *      compiler.jeandle.checks.TestLoopChecks
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *      -Xbatch -XX:-TieredCompilation -XX:+UseJeandleCompiler -XX:CompileThreshold=100
 *      -XX:+JeandleDumpIR -XX:CompileCommand=compileonly,compiler.jeandle.checks.TestLoopChecks::test*
 *      -XX:JeandleLLVMOptions=-jeandle-loop-strip-mining-iter=0
 *      compiler.jeandle.checks.TestLoopChecks
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *      -Xbatch -XX:-TieredCompilation -XX:+UseJeandleCompiler -XX:CompileThreshold=100
 *      -XX:+JeandleDumpIR -XX:CompileCommand=compileonly,compiler.jeandle.checks.TestLoopChecks::test*
 *      -XX:-JeandleDoPEA -XX:JeandleLLVMOptions=-jeandle-loop-strip-mining-iter=0
 *      compiler.jeandle.checks.TestLoopChecks
 */
package compiler.jeandle.checks;

import compiler.jeandle.fileCheck.FileCheck;
import java.lang.reflect.Method;
import jdk.test.lib.Asserts;
import jdk.test.whitebox.WhiteBox;

public class TestLoopChecks {
    private static final WhiteBox WB = WhiteBox.getWhiteBox();
    private static int effects;

    public static int testSum(int[] a, int end) {
        int sum = a.length;
        for (int i = 0; i < end; i++) {
            effects++;
            sum += a[i];
        }
        return sum;
    }

    public static int testReverse(int[] a, int from) {
        int sum = a.length;
        for (int i = from; i >= 0; i--) {
            sum += a[i];
        }
        return sum;
    }

    public static int testZero(int[] a, int end) {
        int sum = 0;
        for (int i = 0; i < end; i++) sum += a[i];
        return sum;
    }

    public static int testPairs(int[] a, int[] b, int end) {
        int sum = 0;
        for (int i = 0; i < end; i++) { a[i]++; sum += b[i]; }
        return sum;
    }

    public static int testConditional(int[] a, int end, boolean access) {
        int sum = 0;
        for (int i = 0; i < end; i++) { if (access) sum += a[i]; }
        return sum;
    }

    public static void main(String[] args) throws Exception {
        int[] a = new int[32];
        java.util.Arrays.fill(a, 3);
        for (int n = 0; n < 20000; n++) {
            Asserts.assertEquals(testSum(a, 32), 128);
            Asserts.assertEquals(testReverse(a, 31), 128);
            Asserts.assertEquals(testZero(a, 32), 96);
            Asserts.assertEquals(testConditional(a, 32, true), 96);
        }
        Method method = TestLoopChecks.class.getDeclaredMethod("testSum", int[].class, int.class);
        Asserts.assertTrue(WB.isMethodCompiled(method), "target compiled before failure");
        FileCheck before = new FileCheck(System.getProperty("user.dir"), method, false);
        before.checkPattern("call i1 @llvm.experimental.widenable.condition");
        FileCheck after = new FileCheck(System.getProperty("user.dir"), method, true);
        after.checkNotPattern("call i1 @llvm.experimental.widenable.condition");
        int predicates = WB.getMethodTrapCount(method, "predicate");
        effects = 0;
        try {
            testSum(a, 33);
            throw new AssertionError("missing bounds exception");
        } catch (ArrayIndexOutOfBoundsException expected) {
            Asserts.assertEquals(effects, 33, "effects before throwing access");
        }
        Asserts.assertTrue(WB.getMethodTrapCount(method, "predicate") > predicates,
                "widened check failure records predicate feedback");
        Asserts.assertEquals(testZero(null, 0), 0);
        Asserts.assertEquals(testZero(null, -1), 0);
        Asserts.assertEquals(testConditional(null, 5, false), 0);
        int[] first = new int[4];
        try {
            testPairs(first, new int[2], 4);
            throw new AssertionError("missing second-array exception");
        } catch (ArrayIndexOutOfBoundsException expected) {
            Asserts.assertEquals(first[0], 1);
            Asserts.assertEquals(first[1], 1);
            Asserts.assertEquals(first[2], 1);
            Asserts.assertEquals(first[3], 0);
        }
        // Recompile with recorded feedback, then verify no repeated speculation.
        WB.enqueueMethodForCompilation(method, 4);
        Asserts.assertTrue(WB.isMethodCompiled(method));
        int recorded = WB.getMethodTrapCount(method, "predicate");
        new FileCheck(System.getProperty("user.dir"), method, false)
                .checkNotPattern("call i1 @llvm.experimental.widenable.condition");
        effects = 0;
        try {
            testSum(a, 34);
            throw new AssertionError("missing second bounds exception");
        } catch (ArrayIndexOutOfBoundsException expected) {
            Asserts.assertEquals(effects, 33);
        }
        Asserts.assertEquals(WB.getMethodTrapCount(method, "predicate"), recorded);
        System.out.println("LOOP_CHECKS_EXECUTED predicates=" + recorded);
    }
}
