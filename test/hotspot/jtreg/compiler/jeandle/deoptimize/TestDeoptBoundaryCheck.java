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
 * @summary test deoptimization in boundary check and check deoptimization log
 * @requires vm.debug
 * @library /test/lib
 * @run driver compiler.jeandle.deoptimize.TestDeoptBoundaryCheck
 */

package compiler.jeandle.deoptimize;

import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;
import java.util.ArrayList;
import java.util.List;

public class TestDeoptBoundaryCheck {

    private static boolean[] boolArr = new boolean[]{true};
    private static byte[] byteArr = new byte[]{1, 2};
    private static char[] charArr = new char[]{'a', 'b', 'c'};
    private static short[] shortArr = new short[]{1, 2, 3, 4};
    private static int[] intArr = new int[]{1, 2, 3, 4, 5};
    private static long[] longArr = new long[]{1, 2, 3, 4, 5, 6};
    private static float[] floatArr = new float[]{1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f, 7.0f};
    private static double[] doubleArr = new double[]{1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0};

    private static enum ArrayKind {BOOLEAN, BYTE, CHAR, SHORT, INT, LONG, FLOAT, DOUBLE}

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            runTests();
            return;
        }

        if (args.length != 2) {
            throw new RuntimeException("Incorrect test arguments");
        }

        boolean isLoad = args[0].equals("load");
        String type = args[1];

        if (isLoad) {
            testLoadArray(type);
        } else {
            testStoreArray(type);
        }
    }

    public static void runTests() throws Exception {
        ArrayList<String> commandPrefix = new ArrayList<>(List.of(
            "-Xcomp",
            "-Xbatch",
            "-XX:-TieredCompilation",
            "-XX:+UseJeandleCompiler",
            "-Xlog:deoptimization=debug",
            "-XX:CompileCommand=compileonly,compiler.jeandle.deoptimize.TestDeoptBoundaryCheck::test*",
            TestDeoptBoundaryCheck.class.getName()
        ));

        runTestHelper(commandPrefix, "load", "BOOLEAN");
        runTestHelper(commandPrefix, "load", "BYTE");
        runTestHelper(commandPrefix, "load", "CHAR");
        runTestHelper(commandPrefix, "load", "SHORT");
        runTestHelper(commandPrefix, "load", "INT");
        runTestHelper(commandPrefix, "load", "LONG");
        runTestHelper(commandPrefix, "load", "FLOAT");
        runTestHelper(commandPrefix, "load", "DOUBLE");
        runTestHelper(commandPrefix, "store", "BOOLEAN");
        runTestHelper(commandPrefix, "store", "BYTE");
        runTestHelper(commandPrefix, "store", "CHAR");
        runTestHelper(commandPrefix, "store", "SHORT");
        runTestHelper(commandPrefix, "store", "INT");
        runTestHelper(commandPrefix, "store", "LONG");
        runTestHelper(commandPrefix, "store", "FLOAT");
        runTestHelper(commandPrefix, "store", "DOUBLE");
    }

    public static void runTestHelper(ArrayList<String> commandPrefix, String loadStore, String type) throws Exception {
        ArrayList<String> commandArgs = new ArrayList<>(commandPrefix);
        commandArgs.add(loadStore);
        commandArgs.add(type);

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(commandArgs);
        OutputAnalyzer output = ProcessTools.executeCommand(pb);

        output.shouldHaveExitValue(1);
        output.shouldMatch("java.lang.ArrayIndexOutOfBoundsException: Index [0-9]+ out of bounds for length " + getArrayLength(type));
        output.shouldMatch("\\[debug\\]\\[deoptimization\\].*range_check maybe_recompile");
    }

    public static void testLoadArray(String kind) {
        switch (kind) {
            case "BOOLEAN":
                var booleanValue = boolArr[boolArr.length];
                break;
            case "BYTE":
                var byteValue = byteArr[byteArr.length];
                break;
            case "CHAR":
                var charValue = charArr[charArr.length];
                break;
            case "SHORT":
                var shortValue = shortArr[shortArr.length];
                break;
            case "INT":
                var intValue = intArr[intArr.length];
                break;
            case "LONG":
                var longValue = longArr[longArr.length];
                break;
            case "FLOAT":
                var floatValue = floatArr[floatArr.length];
                break;
            case "DOUBLE":
                var doubleValue = doubleArr[doubleArr.length];
                break;
            default:
                throw new IllegalArgumentException("Invalid array kind" + kind);
        }
    }

    public static void testStoreArray(String kind) {
        switch (kind) {
            case "BOOLEAN":
                boolArr[boolArr.length] = true;
                break;
            case "BYTE":
                byteArr[byteArr.length] = 1;
                break;
            case "CHAR":
                charArr[charArr.length] = 'a';
                break;
            case "SHORT":
                shortArr[shortArr.length] = 1;
                break;
            case "INT":
                intArr[intArr.length] = 1;
                break;
            case "LONG":
                longArr[longArr.length] = 1;
                break;
            case "FLOAT":
                floatArr[floatArr.length] = 1.0f;
                break;
            case "DOUBLE":
                doubleArr[doubleArr.length] = 1.0;
                break;
            default:
                throw new IllegalArgumentException("Invalid array kind" + kind);
        }
    }

    public static int getArrayLength(String kind) {
        switch (kind) {
            case "BOOLEAN":
                 return boolArr.length;
            case "BYTE":
                 return byteArr.length;
            case "CHAR":
                 return charArr.length;
            case "SHORT":
                 return shortArr.length;
            case "INT":
                 return intArr.length;
            case "LONG":
                 return longArr.length;
            case "FLOAT":
                 return floatArr.length;
            case "DOUBLE":
                 return doubleArr.length;
            default:
                throw new IllegalArgumentException("Invalid array kind" + kind);
        }
    }
}
