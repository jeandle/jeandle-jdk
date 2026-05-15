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

package compiler.jeandle.bytecodeTranslate.alloc;

import jdk.test.lib.Asserts;

/**
 * @test
 * @summary Verify that newly TLAB-fast-path-allocated arrays are zero-initialized for
 *          every element type, including when ZeroTLAB is enabled and the compiled
 *          memset is skipped.
 * @library /test/lib
 * @run main/othervm -Xcomp -XX:-TieredCompilation
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.alloc.TestArrayAllocZeroing::test*
 *      -XX:+UseJeandleCompiler compiler.jeandle.bytecodeTranslate.alloc.TestArrayAllocZeroing
 * @run main/othervm -Xcomp -XX:-TieredCompilation -XX:+ZeroTLAB
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.alloc.TestArrayAllocZeroing::test*
 *      -XX:+UseJeandleCompiler compiler.jeandle.bytecodeTranslate.alloc.TestArrayAllocZeroing
 */
public class TestArrayAllocZeroing {

    // Each test_* method allocates and returns the array. The driver verifies each
    // element is the default zero value for the element type. Using 1024 elements
    // forces the memset (or the TLAB pre-zeroing under ZeroTLAB) to span several
    // cache lines, exercising the compiled zeroing path end-to-end.
    static final int N = 1024;

    public static boolean[] test_boolean_array() { return new boolean[N]; }
    public static byte[]    test_byte_array()    { return new byte[N];    }
    public static char[]    test_char_array()    { return new char[N];    }
    public static short[]   test_short_array()   { return new short[N];   }
    public static int[]     test_int_array()     { return new int[N];     }
    public static long[]    test_long_array()    { return new long[N];    }
    public static float[]   test_float_array()   { return new float[N];   }
    public static double[]  test_double_array()  { return new double[N];  }
    public static Object[]  test_object_array()  { return new Object[N];  }
    public static String[]  test_string_array()  { return new String[N];  }

    public static void main(String[] args) {
        boolean[] booleans = test_boolean_array();
        for (int i = 0; i < N; i++) Asserts.assertFalse(booleans[i], "boolean at " + i);

        byte[] bytes = test_byte_array();
        for (int i = 0; i < N; i++) Asserts.assertEquals(bytes[i], (byte) 0, "byte at " + i);

        char[] chars = test_char_array();
        for (int i = 0; i < N; i++) Asserts.assertEquals(chars[i], (char) 0, "char at " + i);

        short[] shorts = test_short_array();
        for (int i = 0; i < N; i++) Asserts.assertEquals(shorts[i], (short) 0, "short at " + i);

        int[] ints = test_int_array();
        for (int i = 0; i < N; i++) Asserts.assertEquals(ints[i], 0, "int at " + i);

        long[] longs = test_long_array();
        for (int i = 0; i < N; i++) Asserts.assertEquals(longs[i], 0L, "long at " + i);

        float[] floats = test_float_array();
        for (int i = 0; i < N; i++) Asserts.assertEquals(floats[i], 0.0f, "float at " + i);

        double[] doubles = test_double_array();
        for (int i = 0; i < N; i++) Asserts.assertEquals(doubles[i], 0.0d, "double at " + i);

        Object[] objects = test_object_array();
        for (int i = 0; i < N; i++) Asserts.assertNull(objects[i], "Object[] at " + i);

        String[] strings = test_string_array();
        for (int i = 0; i < N; i++) Asserts.assertNull(strings[i], "String[] at " + i);
    }
}
