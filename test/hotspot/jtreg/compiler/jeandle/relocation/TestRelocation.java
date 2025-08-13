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

/*
 * @test
 * @library /test/lib
 * @build jdk.test.lib.Asserts
 * @run main/othervm -XX:-TieredCompilation -Xcomp
 *      -XX:CompileCommand=compileonly,compiler.jeandle.relocation.TestRelocation::test*
 *      -XX:+UseJeandleCompiler compiler.jeandle.relocation.TestRelocation
 */

package compiler.jeandle.relocation;

import jdk.test.lib.Asserts;

public class TestRelocation {
    public static void main(String[] args) {
        Asserts.assertEquals(20.0f, testFloat1(1.0f));
        Asserts.assertEquals(24.0f, testFloat2(1.0f));
        Asserts.assertEquals(29.0f, testFloat3(1.0f));
        Asserts.assertEquals(32.0f, testFloat4(1.0f));
        Asserts.assertEquals(37.0f, testFloat5(1.0f));
        Asserts.assertEquals(47.0f, testFloat6(1.0f));

        Asserts.assertEquals(20.0, testDouble1(1.0));
        Asserts.assertEquals(24.0, testDouble2(1.0));
        Asserts.assertEquals(29.0, testDouble3(1.0));
        Asserts.assertEquals(32.0, testDouble4(1.0));
        Asserts.assertEquals(37.0, testDouble5(1.0));
        Asserts.assertEquals(47.0, testDouble6(1.0));

        Asserts.assertEquals(20L, testLong1(1L));
        Asserts.assertEquals(24L, testLong2(1L));
        Asserts.assertEquals(29L, testLong3(1L));
        Asserts.assertEquals(32L, testLong4(1L));
        Asserts.assertEquals(37L, testLong5(1L));
        Asserts.assertEquals(47L, testLong6(1L));
    }

    private static float calleeFloat1(float a) {
        return a += 2.0f;
    }

    private static float calleeFloat2(float a) {
        return a += 3.0f;
    }

    private static double calleeDouble1(double a) {
        return a += 2.0;
    }

    private static double calleeDouble2(double a) {
        return a += 3.0;
    }

    private static long calleeLong1(long a) {
        return a += 2;
    }

    private static long calleeLong2(long a) {
        return a += 3;
    }

    private static float testFloat1(float n) {
        n += calleeFloat1(n);
        n += calleeFloat2(n);
        n += 4.0f;
        n += 5.0f;
        return n;
    }

    private static float testFloat2(float n) {
        n += calleeFloat1(n);
        n += 4.0f;
        n += calleeFloat2(n);
        n += 5.0f;
        return n;
    }

    private static float testFloat3(float n) {
        n += calleeFloat1(n);
        n += 4.0f;
        n += 5.0f;
        n += calleeFloat2(n);
        return n;
    }

    private static float testFloat4(float n) {
        n += 4.0f;
        n += calleeFloat1(n);
        n += calleeFloat2(n);
        n += 5.0f;
        return n;
    }

    private static float testFloat5(float n) {
        n += 4.0f;
        n += calleeFloat1(n);
        n += 5.0f;
        n += calleeFloat2(n);
        return n;
    }

    private static float testFloat6(float n) {
        n += 4.0f;
        n += 5.0f;
        n += calleeFloat1(n);
        n += calleeFloat2(n);
        return n;
    }

    private static double testDouble1(double n) {
        n += calleeDouble1(n);
        n += calleeDouble2(n);
        n += 4.0;
        n += 5.0;
        return n;
    }

    private static double testDouble2(double n) {
        n += calleeDouble1(n);
        n += 4.0;
        n += calleeDouble2(n);
        n += 5.0;
        return n;
    }

    private static double testDouble3(double n) {
        n += calleeDouble1(n);
        n += 4.0;
        n += 5.0;
        n += calleeDouble2(n);
        return n;
    }

    private static double testDouble4(double n) {
        n += 4.0;
        n += calleeDouble1(n);
        n += calleeDouble2(n);
        n += 5.0;
        return n;
    }

    private static double testDouble5(double n) {
        n += 4.0;
        n += calleeDouble1(n);
        n += 5.0;
        n += calleeDouble2(n);
        return n;
    }

    private static double testDouble6(double n) {
        n += 4.0;
        n += 5.0;
        n += calleeDouble1(n);
        n += calleeDouble2(n);
        return n;
    }

    private static long testLong1(long n) {
        n += calleeLong1(n);
        n += calleeLong2(n);
        n += 4;
        n += 5;
        return n;
    }

    private static long testLong2(long n) {
        n += calleeLong1(n);
        n += 4;
        n += calleeLong2(n);
        n += 5;
        return n;
    }

    private static long testLong3(long n) {
        n += calleeLong1(n);
        n += 4;
        n += 5;
        n += calleeLong2(n);
        return n;
    }

    private static long testLong4(long n) {
        n += 4;
        n += calleeLong1(n);
        n += calleeLong2(n);
        n += 5;
        return n;
    }

    private static long testLong5(long n) {
        n += 4;
        n += calleeLong1(n);
        n += 5;
        n += calleeLong2(n);
        return n;
    }
    
    private static long testLong6(long n) {
        n += 4;
        n += 5;
        n += calleeLong1(n);
        n += calleeLong2(n);
        return n;
    }
}