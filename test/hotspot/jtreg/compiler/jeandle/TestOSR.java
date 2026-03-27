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
 * @library /test/lib /
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *                   -XX:CompileCommand=compileonly,compiler.jeandle.TestOSR::testAllTypes
 *                   -XX:CompileCommand=compileonly,compiler.jeandle.TestOSR::testNestedLoop
 *                   -XX:CompileCommand=compileonly,compiler.jeandle.TestOSR::testTryCatch
 *                   -XX:CompileCommand=dontinline,compiler.jeandle.TestOSR::throwRuntimeException
 *                   -XX:-TieredCompilation -XX:+UseOnStackReplacement -Xbatch -XX:+JeandleDumpIR
 *                   -XX:+UseJeandleCompiler compiler.jeandle.TestOSR
 */

package compiler.jeandle;

import java.lang.reflect.Method;

import jdk.test.lib.Asserts;
import jdk.test.whitebox.WhiteBox;

public class TestOSR {
    private static WhiteBox wb = WhiteBox.getWhiteBox();
    private static int intValue = 123;
    private static short shortValue = 456;
    private static long longValue = 789L;
    private static float floatValue = 3.5f;
    private static double doubleValue = 2.125;
    private static String str = "Verified_Reference_Object";

    private static int iterations = 30_000;
    private static int outerIterations = 20;

    public static void main(String[] args) throws Exception {

        testAllTypes();
        Method testAllTypesMethod = TestOSR.class.getDeclaredMethod("testAllTypes");
        Asserts.assertTrue(wb.isMethodCompiled(testAllTypesMethod, true /* isOsr */));

        testNestedLoop();
        Method testNestedLoopMethod = TestOSR.class.getDeclaredMethod("testNestedLoop");
        Asserts.assertTrue(wb.isMethodCompiled(testNestedLoopMethod, true /* isOsr */));

        testTryCatch();
        Method testTryCatchMethod = TestOSR.class.getDeclaredMethod("testTryCatch");
        Asserts.assertTrue(wb.isMethodCompiled(testTryCatchMethod, true /* isOsr */));
    }

    public static void testAllTypes() {
        // 32-bit Integer
        int intVal = intValue;
        // Short/Byte/Char (Stored as int in locals)
        short shortVal = shortValue;
        // 64-bit Long (Uses 2 slots in Local Variable Table)
        long longVal = longValue;
        // 32-bit Float
        float floatVal = floatValue;
        // 64-bit Double (Uses 2 slots)
        double doubleVal = doubleValue;
        // Object Reference (OOP)
        Object referenceVal = str;

        // Loop Header: OSR will trigger here
        for (int i = 0; i < iterations; i++) {
            // Modify values to ensure they are "Live" and stored in the OSR Buffer
            intVal += 1;
            shortVal += 1;
            longVal += 1;
            floatVal += 0.125f;
            doubleVal += 0.0625f;

            // Blackhole-like check to prevent dead-code elimination
            if (i == iterations - 1) {
                checkResult(intVal, shortVal, longVal, floatVal, doubleVal, referenceVal);
            }
        }
    }

    private static void checkResult(int i, short s, long l, float f, double d, Object r) {
        Asserts.assertEquals(i, intValue + iterations);
        Asserts.assertEquals(s, (short)(shortValue + iterations));
        Asserts.assertEquals(l, longValue + iterations);
        Asserts.assertEquals(f, floatValue + iterations * 0.125f);
        Asserts.assertEquals(d, doubleValue + iterations * 0.0625f);
        Asserts.assertEquals(r, str);
    }

    public static void testNestedLoop() {
        long longVal = longValue;

        for (int i = 0; i < outerIterations; i++) {
            // Loop Header: OSR will trigger here
            for (int j = 0; j < iterations; j++) {
                longVal += 1;
            }
        }

        Asserts.assertEquals(longVal, longValue + iterations * outerIterations);
    }

    public static void testTryCatch() {
        boolean exceptionCatched = false;
        long longVal = longValue;

        try {
            // Loop Header: OSR will trigger here
            for (int i = 0; i < iterations; i++) {
                longVal += 1;

                if (i == iterations - 1) {
                    throwRuntimeException();
                }
            }
        } catch (RuntimeException e) {
            exceptionCatched = true;
        }

        Asserts.assertTrue(exceptionCatched);
        Asserts.assertEquals(longVal, longValue + iterations);
    }

    public static void throwRuntimeException() {
        throw new RuntimeException();
    }
}
