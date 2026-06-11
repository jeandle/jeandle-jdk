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
 * @summary Test the intrinsic implementation of Array.newInstance (jeandle.new_array)
 *          and bytecode array allocation paths
 * @library /test/lib
 * @run main/othervm TestNewArray
 */

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;

import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestNewArray {

    public static void main(String[] args) throws Exception {
        ArrayList<String> commandArgs = new ArrayList<>(List.of(
                "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
                "-Xlog:jeandle=debug",
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::create*",
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::new*",
                // The intrinsic is bound to the native Array.newArray, and Jeandle has no
                // inlining yet, so newInstance itself must be Jeandle-compiled for the
                // jeandle.new_array lowering to be exercised at its call site.
                "-XX:CompileCommand=compileonly,java.lang.reflect.Array::newInstance",
                TestWrapper.class.getName()));

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(commandArgs);
        OutputAnalyzer output = ProcessTools.executeCommand(pb);

        output.shouldHaveExitValue(0)
              .shouldContain("is parsed as intrinsic");
    }

    static class TestWrapper {
        static Object vForceLoad = Array.newInstance(int.class, 0); // Force load java.lang.reflect.Array

        // === Intrinsic path (Array.newInstance) — exercises lower_new_array() ===

        static Object createIntArray(int len) {
            return Array.newInstance(int.class, len);
        }

        static Object createByteArray(int len) {
            return Array.newInstance(byte.class, len);
        }

        static Object createShortArray(int len) {
            return Array.newInstance(short.class, len);
        }

        static Object createCharArray(int len) {
            return Array.newInstance(char.class, len);
        }

        static Object createLongArray(int len) {
            return Array.newInstance(long.class, len);
        }

        static Object createFloatArray(int len) {
            return Array.newInstance(float.class, len);
        }

        static Object createDoubleArray(int len) {
            return Array.newInstance(double.class, len);
        }

        static Object createBooleanArray(int len) {
            return Array.newInstance(boolean.class, len);
        }

        static Object createObjectArray(int len) {
            return Array.newInstance(Object.class, len);
        }

        static Object createStringArray(int len) {
            return Array.newInstance(String.class, len);
        }

        // === Bytecode path (newarray / anewarray / multianewarray) ===

        static int[] newIntArray(int len) {
            return new int[len];
        }

        static byte[] newByteArray(int len) {
            return new byte[len];
        }

        static String[] newStringArray(int len) {
            return new String[len];
        }

        static Object[] newObjectArray(int len) {
            return new Object[len];
        }

        static int[][] newInt2D(int a, int b) {
            return new int[a][b];
        }

        static String[][][] newString3D(int a, int b) {
            return new String[a][b][];
        }

        // === Helper ===

        static void checkTypeAndLength(Object arr, Class<?> expectedType, int expectedLen) {
            if (!expectedType.isInstance(arr))
                throw new RuntimeException("Expected " + expectedType + ", got: " + arr.getClass());
            int len = java.lang.reflect.Array.getLength(arr);
            if (len != expectedLen)
                throw new RuntimeException("Expected length " + expectedLen + ", got: " + len);
        }

        public static void main(String[] args) throws Exception {

            // --- Intrinsic path: all primitive types (fast path: klass cached in mirror) ---
            for (int i = 0; i < 3; i++) {
                checkTypeAndLength(createIntArray(10),    int[].class,     10);
                checkTypeAndLength(createByteArray(5),    byte[].class,    5);
                checkTypeAndLength(createShortArray(7),   short[].class,   7);
                checkTypeAndLength(createCharArray(3),    char[].class,    3);
                checkTypeAndLength(createLongArray(4),    long[].class,    4);
                checkTypeAndLength(createFloatArray(6),   float[].class,   6);
                checkTypeAndLength(createDoubleArray(8),  double[].class,  8);
                checkTypeAndLength(createBooleanArray(2), boolean[].class, 2);

                // Reference arrays (fast path)
                checkTypeAndLength(createObjectArray(5),  Object[].class,  5);
                checkTypeAndLength(createStringArray(3),  String[].class,  3);
            }

            // --- Intrinsic path: zero-length ---
            checkTypeAndLength(createIntArray(0), int[].class, 0);

            // --- Intrinsic path: NegativeArraySizeException (exception edge) ---
            try {
                createIntArray(-1);
                throw new RuntimeException("Expected NegativeArraySizeException");
            } catch (NegativeArraySizeException e) {
                // expected
            }

            // --- Intrinsic path: null component type -> slow path -> NPE ---
            try {
                Array.newInstance(null, 5);
                throw new RuntimeException("Expected NullPointerException");
            } catch (NullPointerException e) {
                // expected
            }

            // --- Bytecode path: newarray (primitive) ---
            for (int i = 0; i < 3; i++) {
                int[] ia = newIntArray(10);
                if (ia.length != 10) throw new RuntimeException("int[] length mismatch");
                byte[] ba = newByteArray(5);
                if (ba.length != 5) throw new RuntimeException("byte[] length mismatch");
            }

            // --- Bytecode path: anewarray (reference) ---
            for (int i = 0; i < 3; i++) {
                String[] sa = newStringArray(5);
                if (sa.length != 5) throw new RuntimeException("String[] length mismatch");
                Object[] oa = newObjectArray(7);
                if (oa.length != 7) throw new RuntimeException("Object[] length mismatch");
            }

            // --- Bytecode path: multianewarray (2D) ---
            int[][] arr2d = newInt2D(3, 4);
            if (arr2d.length != 3) throw new RuntimeException("2D outer length mismatch");
            for (int i = 0; i < 3; i++) {
                if (arr2d[i].length != 4) throw new RuntimeException("2D inner length mismatch at " + i);
            }

            // --- Bytecode path: multianewarray (3D, last dimension unspecified -> null) ---
            String[][][] arr3d = newString3D(2, 3);
            if (arr3d.length != 2) throw new RuntimeException("3D outer length mismatch");
            for (int i = 0; i < 2; i++) {
                if (arr3d[i].length != 3) throw new RuntimeException("3D mid length mismatch at " + i);
                for (int j = 0; j < 3; j++) {
                    if (arr3d[i][j] != null)
                        throw new RuntimeException("3D inner should be null (unspecified)");
                }
            }

            // --- Bytecode path: NegativeArraySizeException ---
            try {
                newIntArray(-1);
                throw new RuntimeException("Expected NegativeArraySizeException (bytecode)");
            } catch (NegativeArraySizeException e) {
                // expected
            }

            // --- Bytecode path: zero-length ---
            int[] zeroLen = newIntArray(0);
            if (zeroLen.length != 0) throw new RuntimeException("Zero-length bytecode failed");

            System.out.println("TestNewArray PASSED");
        }
    }
}
