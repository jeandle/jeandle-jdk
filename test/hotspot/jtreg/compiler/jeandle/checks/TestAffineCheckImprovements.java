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
 * @summary Version merged and dynamic affine checks while preserving hot handler state
 * @library /test/lib /
 * @build compiler.jeandle.checks.CheckLoopShape jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *      -XX:-OmitStackTraceInFastThrow -Xbatch -XX:-TieredCompilation -XX:+UseJeandleCompiler
 *      -XX:CompileThreshold=100 -XX:+JeandleDumpIR
 *      -XX:CompileCommand=compileonly,compiler.jeandle.checks.TestAffineCheckImprovements::test*
 *      compiler.jeandle.checks.TestAffineCheckImprovements
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *      -XX:-OmitStackTraceInFastThrow -Xbatch -XX:-TieredCompilation -XX:+UseJeandleCompiler
 *      -XX:CompileThreshold=100 -XX:+JeandleDumpIR -XX:-JeandleDoPEA
 *      -XX:CompileCommand=compileonly,compiler.jeandle.checks.TestAffineCheckImprovements::test*
 *      compiler.jeandle.checks.TestAffineCheckImprovements
 */
package compiler.jeandle.checks;

import java.lang.reflect.Method;
import jdk.test.lib.Asserts;
import jdk.test.whitebox.WhiteBox;

public class TestAffineCheckImprovements {
    private static final WhiteBox WB = WhiteBox.getWhiteBox();
    private static int effects;
    private static int handlers;

    public static int testCombined(int[] a, int end, int first, int second) {
        int sum = 0;
        for (int i = 0; i < end; i++) {
            sum += a[3 * i + first] + a[5 * i + second];
        }
        return sum;
    }

    public static int testDynamic(int[] a, int end, int offset, int scale) {
        int sum = 0;
        for (int i = 0; i < end; i++) {
            effects++;
            sum += a[scale * i + offset];
        }
        return sum;
    }

    public static int testHotHandler(int[] a, int end, int first, int second, int scale) {
        int sum = 0;
        try {
            for (int i = 0; i < end; i++) {
                effects++;
                sum += a[scale * i + first];
                effects += 10;
                sum += a[5 * i + second];
            }
        } catch (ArrayIndexOutOfBoundsException expected) {
            handlers++;
        }
        return sum;
    }

    private static int referenceHot(int[] a, int end, int first, int second, int scale) {
        int sum = 0;
        try {
            for (int i = 0; i < end; i++) {
                effects++;
                sum += a[scale * i + first];
                effects += 10;
                sum += a[5 * i + second];
            }
        } catch (ArrayIndexOutOfBoundsException expected) {
            handlers++;
        }
        return sum;
    }

    private static void assertCompiled(Method method) {
        WB.enqueueMethodForCompilation(method, 4);
        Asserts.assertTrue(WB.isMethodCompiled(method), "ordinary target compiled: " + method.getName());
    }

    private static void dynamicCase(Method method, int[] a, int end, int offset, int scale) {
        int expected = 0;
        int expectedEffects = 0;
        boolean failure = false;
        try {
            for (int i = 0; i < end; i++) {
                expectedEffects++;
                expected += a[scale * i + offset];
            }
        } catch (ArrayIndexOutOfBoundsException exception) {
            failure = true;
        }
        assertCompiled(method);
        effects = 0;
        try {
            int actual = testDynamic(a, end, offset, scale);
            Asserts.assertFalse(failure, "expected dynamic failure");
            Asserts.assertEquals(actual, expected, "dynamic result");
        } catch (ArrayIndexOutOfBoundsException exception) {
            Asserts.assertTrue(failure, "unexpected dynamic failure");
            Asserts.assertEquals(exception.getStackTrace()[0].getMethodName(), "testDynamic");
        }
        Asserts.assertEquals(effects, expectedEffects, "effects before first dynamic failure");
    }

    private static void hotCase(Method method, int[] a, int end, int first, int second, int scale) {
        effects = 0;
        handlers = 0;
        int expected = referenceHot(a, end, first, second, scale);
        int expectedEffects = effects;
        int expectedHandlers = handlers;
        assertCompiled(method);
        effects = 0;
        handlers = 0;
        Asserts.assertEquals(testHotHandler(a, end, first, second, scale), expected, "partial sum in handler");
        Asserts.assertEquals(effects, expectedEffects, "partial effects in handler");
        Asserts.assertEquals(handlers, expectedHandlers, "ordinary handler executed");
        Asserts.assertTrue(WB.isMethodCompiled(method), "hot handler target remains compiled after fallback");
    }

    public static void main(String[] args) throws Exception {
        int[] a = new int[64];
        for (int i = 0; i < a.length; i++) {
            a[i] = i * 7 + 3;
        }
        Method combined = TestAffineCheckImprovements.class.getDeclaredMethod("testCombined",
                int[].class, int.class, int.class, int.class);
        Method dynamic = TestAffineCheckImprovements.class.getDeclaredMethod("testDynamic",
                int[].class, int.class, int.class, int.class);
        Method hot = TestAffineCheckImprovements.class.getDeclaredMethod("testHotHandler",
                int[].class, int.class, int.class, int.class, int.class);
        for (int iteration = 0; iteration < 20000; iteration++) {
            int first = iteration & 1;
            int second = (iteration >>> 1) & 1;
            int expected = 0;
            for (int i = 0; i < 10; i++) expected += a[3 * i + first] + a[5 * i + second];
            Asserts.assertEquals(testCombined(a, 10, first, second), expected);
            int scale = iteration % 3 == 0 ? -3 : iteration % 3 == 1 ? 0 : 3;
            int offset = scale < 0 ? 63 : first;
            testDynamic(a, 10, offset, scale);
            testHotHandler(a, iteration % 16 == 0 ? 15 : 10, first, second, 3);
        }
        assertCompiled(combined);
        assertCompiled(dynamic);
        // Compile with the recorded throwing profile so the ordinary handler is present.
        WB.deoptimizeMethod(hot);
        assertCompiled(hot);
        CheckLoopShape.assertVersioned(combined, false);
        CheckLoopShape.assertVersioned(dynamic, false);
        CheckLoopShape.assertVersioned(hot, false);
        Asserts.assertEquals(testCombined(a, 1, 0, 0), a[0] + a[0]);
        Asserts.assertEquals(testCombined(null, 0, 0, 0), 0);
        for (int scale : new int[] {Integer.MIN_VALUE, Integer.MIN_VALUE + 1, -3, -1, 0,
                                   1, 3, Integer.MAX_VALUE - 1, Integer.MAX_VALUE}) {
            dynamicCase(dynamic, a, 25, scale < 0 ? 63 : 0, scale);
            dynamicCase(dynamic, a, 1, 0, scale);
            hotCase(hot, a, 25, 0, 0, scale);
            hotCase(hot, a, 25, 63, 64, scale);
        }
        dynamicCase(dynamic, null, 0, 0, Integer.MIN_VALUE);
        hotCase(hot, null, 0, 0, 0, Integer.MAX_VALUE);
        System.out.println("AFFINE_CHECK_IMPROVEMENTS_EXECUTED");
    }
}
