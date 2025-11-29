/*
 * Copyright (c) 2025, the Jeandle-JDK Authors. All Rights Reserved.
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

package compiler.jeandle.exception;

import jdk.test.lib.Asserts;

/*
 * @test
 * @requires os.arch=="amd64" | os.arch=="x86_64" | os.arch=="aarch64"
 * @library /test/lib
 * @run main/othervm -Xcomp -XX:-TieredCompilation -XX:+UseJeandleCompiler
 *      -XX:CompileCommand=compileonly,compiler.jeandle.exception.TestArrayIndexOutOfBoundsException::test*
 *      compiler.jeandle.exception.TestArrayIndexOutOfBoundsException
 */

public class TestArrayIndexOutOfBoundsException {
    public static final boolean[] boolArr   = new boolean[3];
    public static final byte[]    byteArr   = new byte[3];
    public static final short[]   shortArr  = new short[3];
    public static final int[]     intArr    = new int[3];
    public static final long[]    longArr   = new long[3];
    public static final char[]    charArr   = new char[3];
    public static final float[]   floatArr  = new float[3];
    public static final double[]  doubleArr = new double[3];
    public static final Object[]  objArr    = new Object[3];

    public static void main(String[] args) {
        boolean throwExpectedException = false;

        // ---------- boolean ----------
        testBoolStore(boolArr, 2, true);
        Asserts.assertEQ(testBoolLoad(boolArr, 2), true);

        throwExpectedException = false;
        try {
            testBoolLoad(boolArr, 3);
        } catch (ArrayIndexOutOfBoundsException e) {
            throwExpectedException = true;
        }
        Asserts.assertTrue(throwExpectedException);

        throwExpectedException = false;
        try {
            testBoolStore(boolArr, 3, false);
        } catch (ArrayIndexOutOfBoundsException e) {
            throwExpectedException = true;
        }
        Asserts.assertTrue(throwExpectedException);

        throwExpectedException = false;
        try {
            testBoolLoad(boolArr, -1);
        } catch (ArrayIndexOutOfBoundsException e) {
            throwExpectedException = true;
        }
        Asserts.assertTrue(throwExpectedException);

        throwExpectedException = false;
        try {
            testBoolStore(boolArr, -1, false);
        } catch (ArrayIndexOutOfBoundsException e) {
            throwExpectedException = true;
        }
        Asserts.assertTrue(throwExpectedException);


        // ---------- byte ----------
        testByteStore(byteArr, 2, (byte) 1);
        Asserts.assertEQ(testByteLoad(byteArr, 2), (byte) 1);

        throwExpectedException = false;
        try {
            testByteLoad(byteArr, 3);
        } catch (ArrayIndexOutOfBoundsException e) {
            throwExpectedException = true;
        }
        Asserts.assertTrue(throwExpectedException);

        throwExpectedException = false;
        try {
            testByteStore(byteArr, 3, (byte) 0);
        } catch (ArrayIndexOutOfBoundsException e) {
            throwExpectedException = true;
        }
        Asserts.assertTrue(throwExpectedException);

        throwExpectedException = false;
        try {
            testByteLoad(byteArr, -1);
        } catch (ArrayIndexOutOfBoundsException e) {
            throwExpectedException = true;
        }
        Asserts.assertTrue(throwExpectedException);

        throwExpectedException = false;
        try {
            testByteStore(byteArr, -1, (byte) 0);
        } catch (ArrayIndexOutOfBoundsException e) {
            throwExpectedException = true;
        }
        Asserts.assertTrue(throwExpectedException);


        // ---------- short ----------
        testShortStore(shortArr, 2, (short) 1);
        Asserts.assertEQ(testShortLoad(shortArr, 2), (short) 1);

        throwExpectedException = false;
        try {
            testShortLoad(shortArr, 3);
        } catch (ArrayIndexOutOfBoundsException e) {
            throwExpectedException = true;
        }
        Asserts.assertTrue(throwExpectedException);

        throwExpectedException = false;
        try {
            testShortStore(shortArr, 3, (short) 0);
        } catch (ArrayIndexOutOfBoundsException e) {
            throwExpectedException = true;
        }
        Asserts.assertTrue(throwExpectedException);

        throwExpectedException = false;
        try {
            testShortLoad(shortArr, -1);
        } catch (ArrayIndexOutOfBoundsException e) {
            throwExpectedException = true;
        }
        Asserts.assertTrue(throwExpectedException);

        throwExpectedException = false;
        try {
            testShortStore(shortArr, -1, (short) 0);
        } catch (ArrayIndexOutOfBoundsException e) {
            throwExpectedException = true;
        }
        Asserts.assertTrue(throwExpectedException);


        // ---------- int ----------
        testIntStore(intArr, 2, 1);
        Asserts.assertEQ(testIntLoad(intArr, 2), 1);

        throwExpectedException = false;
        try {
            testIntLoad(intArr, 3);
        } catch (ArrayIndexOutOfBoundsException e) {
            throwExpectedException = true;
        }
        Asserts.assertTrue(throwExpectedException);

        throwExpectedException = false;
        try {
            testIntStore(intArr, 3, 0);
        } catch (ArrayIndexOutOfBoundsException e) {
            throwExpectedException = true;
        }
        Asserts.assertTrue(throwExpectedException);

        throwExpectedException = false;
        try {
            testIntLoad(intArr, -1);
        } catch (ArrayIndexOutOfBoundsException e) {
            throwExpectedException = true;
        }
        Asserts.assertTrue(throwExpectedException);

        throwExpectedException = false;
        try {
            testIntStore(intArr, -1, 0);
        } catch (ArrayIndexOutOfBoundsException e) {
            throwExpectedException = true;
        }
        Asserts.assertTrue(throwExpectedException);


        // ---------- long ----------
        testLongStore(longArr, 2, 1L);
        Asserts.assertEQ(testLongLoad(longArr, 2), 1L);

        throwExpectedException = false;
        try {
            testLongLoad(longArr, 3);
        } catch (ArrayIndexOutOfBoundsException e) {
            throwExpectedException = true;
        }
        Asserts.assertTrue(throwExpectedException);

        throwExpectedException = false;
        try {
            testLongStore(longArr, 3, 0L);
        } catch (ArrayIndexOutOfBoundsException e) {
            throwExpectedException = true;
        }
        Asserts.assertTrue(throwExpectedException);

        throwExpectedException = false;
        try {
            testLongLoad(longArr, -1);
        } catch (ArrayIndexOutOfBoundsException e) {
            throwExpectedException = true;
        }
        Asserts.assertTrue(throwExpectedException);

        throwExpectedException = false;
        try {
            testLongStore(longArr, -1, 0L);
        } catch (ArrayIndexOutOfBoundsException e) {
            throwExpectedException = true;
        }
        Asserts.assertTrue(throwExpectedException);


        // ---------- char ----------
        testCharStore(charArr, 2, '1');
        Asserts.assertEQ(testCharLoad(charArr, 2), '1');

        throwExpectedException = false;
        try {
            testCharLoad(charArr, 3);
        } catch (ArrayIndexOutOfBoundsException e) {
            throwExpectedException = true;
        }
        Asserts.assertTrue(throwExpectedException);

        throwExpectedException = false;
        try {
            testCharStore(charArr, 3, '\0');
        } catch (ArrayIndexOutOfBoundsException e) {
            throwExpectedException = true;
        }
        Asserts.assertTrue(throwExpectedException);

        throwExpectedException = false;
        try {
            testCharLoad(charArr, -1);
        } catch (ArrayIndexOutOfBoundsException e) {
            throwExpectedException = true;
        }
        Asserts.assertTrue(throwExpectedException);

        throwExpectedException = false;
        try {
            testCharStore(charArr, -1, '\0');
        } catch (ArrayIndexOutOfBoundsException e) {
            throwExpectedException = true;
        }
        Asserts.assertTrue(throwExpectedException);


        // ---------- float ----------
        testFloatStore(floatArr, 2, 1.0f);
        Asserts.assertEQ(testFloatLoad(floatArr, 2), 1.0f);

        throwExpectedException = false;
        try {
            testFloatLoad(floatArr, 3);
        } catch (ArrayIndexOutOfBoundsException e) {
            throwExpectedException = true;
        }
        Asserts.assertTrue(throwExpectedException);

        throwExpectedException = false;
        try {
            testFloatStore(floatArr, 3, 0.0f);
        } catch (ArrayIndexOutOfBoundsException e) {
            throwExpectedException = true;
        }
        Asserts.assertTrue(throwExpectedException);

        throwExpectedException = false;
        try {
            testFloatLoad(floatArr, -1);
        } catch (ArrayIndexOutOfBoundsException e) {
            throwExpectedException = true;
        }
        Asserts.assertTrue(throwExpectedException);

        throwExpectedException = false;
        try {
            testFloatStore(floatArr, -1, 0.0f);
        } catch (ArrayIndexOutOfBoundsException e) {
            throwExpectedException = true;
        }
        Asserts.assertTrue(throwExpectedException);


        // ---------- double ----------
        testDoubleStore(doubleArr, 2, 1.0);
        Asserts.assertEQ(testDoubleLoad(doubleArr, 2), 1.0);

        throwExpectedException = false;
        try {
            testDoubleLoad(doubleArr, 3);
        } catch (ArrayIndexOutOfBoundsException e) {
            throwExpectedException = true;
        }
        Asserts.assertTrue(throwExpectedException);

        throwExpectedException = false;
        try {
            testDoubleStore(doubleArr, 3, 0.0);
        } catch (ArrayIndexOutOfBoundsException e) {
            throwExpectedException = true;
        }
        Asserts.assertTrue(throwExpectedException);

        throwExpectedException = false;
        try {
            testDoubleLoad(doubleArr, -1);
        } catch (ArrayIndexOutOfBoundsException e) {
            throwExpectedException = true;
        }
        Asserts.assertTrue(throwExpectedException);

        throwExpectedException = false;
        try {
            testDoubleStore(doubleArr, -1, 0.0);
        } catch (ArrayIndexOutOfBoundsException e) {
            throwExpectedException = true;
        }
        Asserts.assertTrue(throwExpectedException);


        // ---------- Object ----------
        Object obj = new Object();
        testObjectStore(objArr, 2, obj);
        Asserts.assertEQ(testObjectLoad(objArr, 2), obj);

        throwExpectedException = false;
        try {
            testObjectLoad(objArr, 3);
        } catch (ArrayIndexOutOfBoundsException e) {
            throwExpectedException = true;
        }
        Asserts.assertTrue(throwExpectedException);

        throwExpectedException = false;
        try {
            testObjectStore(objArr, 3, null);
        } catch (ArrayIndexOutOfBoundsException e) {
            throwExpectedException = true;
        }
        Asserts.assertTrue(throwExpectedException);

        throwExpectedException = false;
        try {
            testObjectLoad(objArr, -1);
        } catch (ArrayIndexOutOfBoundsException e) {
            throwExpectedException = true;
        }
        Asserts.assertTrue(throwExpectedException);

        throwExpectedException = false;
        try {
            testObjectStore(objArr, -1, null);
        } catch (ArrayIndexOutOfBoundsException e) {
            throwExpectedException = true;
        }
        Asserts.assertTrue(throwExpectedException);
    }

    // ---------- boolean ----------
    public static boolean testBoolLoad(boolean[] arr, int index) {
        return arr[index];
    }

    public static void testBoolStore(boolean[] arr, int index, boolean val) {
        arr[index] = val;
    }

    // ---------- byte ----------
    public static byte testByteLoad(byte[] arr, int index) {
        return arr[index];
    }

    public static void testByteStore(byte[] arr, int index, byte val) {
        arr[index] = val;
    }

    // ---------- short ----------
    public static short testShortLoad(short[] arr, int index) {
        return arr[index];
    }

    public static void testShortStore(short[] arr, int index, short val) {
        arr[index] = val;
    }

    // ---------- int ----------
    public static int testIntLoad(int[] arr, int index) {
        return arr[index];
    }

    public static void testIntStore(int[] arr, int index, int val) {
        arr[index] = val;
    }

    // ---------- long ----------
    public static long testLongLoad(long[] arr, int index) {
        return arr[index];
    }

    public static void testLongStore(long[] arr, int index, long val) {
        arr[index] = val;
    }

    // ---------- char ----------
    public static char testCharLoad(char[] arr, int index) {
        return arr[index];
    }

    public static void testCharStore(char[] arr, int index, char val) {
        arr[index] = val;
    }

    // ---------- float ----------
    public static float testFloatLoad(float[] arr, int index) {
        return arr[index];
    }

    public static void testFloatStore(float[] arr, int index, float val) {
        arr[index] = val;
    }

    // ---------- double ----------
    public static double testDoubleLoad(double[] arr, int index) {
        return arr[index];
    }

    public static void testDoubleStore(double[] arr, int index, double val) {
        arr[index] = val;
    }

    // ---------- Object ----------
    public static Object testObjectLoad(Object[] arr, int index) {
        return arr[index];
    }

    public static void testObjectStore(Object[] arr, int index, Object val) {
        arr[index] = val;
    }
}
