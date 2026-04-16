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
 * @summary Verify that all field types are zero-initialized after TLAB fast path allocation
 * @library /test/lib
 * @run main/othervm -Xcomp -XX:-TieredCompilation
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.alloc.TestAllocFieldZeroing::test*
 *      -XX:+UseJeandleCompiler compiler.jeandle.bytecodeTranslate.alloc.TestAllocFieldZeroing
 * @run main/othervm -Xcomp -XX:-TieredCompilation -XX:+ZeroTLAB
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.alloc.TestAllocFieldZeroing::test*
 *      -XX:+UseJeandleCompiler compiler.jeandle.bytecodeTranslate.alloc.TestAllocFieldZeroing
 */
public class TestAllocFieldZeroing {

    // Object with all primitive types - no explicit initialization
    static class AllPrimitives {
        boolean z;
        byte b;
        char c;
        short s;
        int i;
        long l;
        float f;
        double d;
    }

    // Object with reference types - no explicit initialization
    static class AllReferences {
        Object obj;
        String str;
        int[] intArray;
        Object[] objArray;
        AllPrimitives nested;
    }

    // Object with mixed fields - some initialized, some not
    static class MixedFields {
        int uninitInt;
        int initInt = 99;
        long uninitLong;
        long initLong = 123456789L;
        double uninitDouble;
        double initDouble = 3.14;
        Object uninitRef;
        String initStr = "hello";
    }

    // Large object with many fields to test memset across cache lines
    static class LargeObject {
        long f0, f1, f2, f3, f4, f5, f6, f7;
        long f8, f9, f10, f11, f12, f13, f14, f15;
        double d0, d1, d2, d3, d4, d5, d6, d7;
        int i0, i1, i2, i3, i4, i5, i6, i7;
        Object r0, r1, r2, r3;
    }

    public static void main(String[] args) {
        for (int iter = 0; iter < 1000; iter++) {
            testPrimitiveZeroing();
            testReferenceZeroing();
            testMixedFields();
            testLargeObjectZeroing();
        }
        System.out.println("TestAllocFieldZeroing passed.");
    }

    static void testPrimitiveZeroing() {
        AllPrimitives obj = new AllPrimitives();
        Asserts.assertFalse(obj.z, "boolean should be false");
        Asserts.assertEquals(obj.b, (byte) 0, "byte should be 0");
        Asserts.assertEquals(obj.c, (char) 0, "char should be 0");
        Asserts.assertEquals(obj.s, (short) 0, "short should be 0");
        Asserts.assertEquals(obj.i, 0, "int should be 0");
        Asserts.assertEquals(obj.l, 0L, "long should be 0");
        Asserts.assertEquals(obj.f, 0.0f, "float should be 0.0f");
        Asserts.assertEquals(obj.d, 0.0, "double should be 0.0");
    }

    static void testReferenceZeroing() {
        AllReferences obj = new AllReferences();
        Asserts.assertNull(obj.obj, "Object should be null");
        Asserts.assertNull(obj.str, "String should be null");
        Asserts.assertNull(obj.intArray, "int[] should be null");
        Asserts.assertNull(obj.objArray, "Object[] should be null");
        Asserts.assertNull(obj.nested, "nested ref should be null");
    }

    static void testMixedFields() {
        MixedFields obj = new MixedFields();
        // Uninitialized fields should be zero
        Asserts.assertEquals(obj.uninitInt, 0, "uninit int should be 0");
        Asserts.assertEquals(obj.uninitLong, 0L, "uninit long should be 0");
        Asserts.assertEquals(obj.uninitDouble, 0.0, "uninit double should be 0.0");
        Asserts.assertNull(obj.uninitRef, "uninit ref should be null");
        // Initialized fields should have their values
        Asserts.assertEquals(obj.initInt, 99, "init int should be 99");
        Asserts.assertEquals(obj.initLong, 123456789L, "init long");
        Asserts.assertEquals(obj.initDouble, 3.14, "init double");
        Asserts.assertEquals(obj.initStr, "hello", "init string");
    }

    static void testLargeObjectZeroing() {
        LargeObject obj = new LargeObject();
        Asserts.assertEquals(obj.f0, 0L);  Asserts.assertEquals(obj.f1, 0L);
        Asserts.assertEquals(obj.f2, 0L);  Asserts.assertEquals(obj.f3, 0L);
        Asserts.assertEquals(obj.f4, 0L);  Asserts.assertEquals(obj.f5, 0L);
        Asserts.assertEquals(obj.f6, 0L);  Asserts.assertEquals(obj.f7, 0L);
        Asserts.assertEquals(obj.f8, 0L);  Asserts.assertEquals(obj.f9, 0L);
        Asserts.assertEquals(obj.f10, 0L); Asserts.assertEquals(obj.f11, 0L);
        Asserts.assertEquals(obj.f12, 0L); Asserts.assertEquals(obj.f13, 0L);
        Asserts.assertEquals(obj.f14, 0L); Asserts.assertEquals(obj.f15, 0L);
        Asserts.assertEquals(obj.d0, 0.0); Asserts.assertEquals(obj.d1, 0.0);
        Asserts.assertEquals(obj.d2, 0.0); Asserts.assertEquals(obj.d3, 0.0);
        Asserts.assertEquals(obj.d4, 0.0); Asserts.assertEquals(obj.d5, 0.0);
        Asserts.assertEquals(obj.d6, 0.0); Asserts.assertEquals(obj.d7, 0.0);
        Asserts.assertEquals(obj.i0, 0);   Asserts.assertEquals(obj.i1, 0);
        Asserts.assertEquals(obj.i2, 0);   Asserts.assertEquals(obj.i3, 0);
        Asserts.assertEquals(obj.i4, 0);   Asserts.assertEquals(obj.i5, 0);
        Asserts.assertEquals(obj.i6, 0);   Asserts.assertEquals(obj.i7, 0);
        Asserts.assertNull(obj.r0); Asserts.assertNull(obj.r1);
        Asserts.assertNull(obj.r2); Asserts.assertNull(obj.r3);
    }
}
