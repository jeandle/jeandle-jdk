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
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.TestFRem::frem
 *      -XX:+UseJeandleCompiler compiler.jeandle.bytecodeTranslate.arithmetic.TestFRem
 */

package compiler.jeandle.bytecodeTranslate.arithmetic;

import jdk.test.lib.Asserts;

public class TestFRem {

    private static final float FLOAT_EPSILON = 1e-6f;

    public static void main(String[] args) throws Exception {
        Asserts.assertTrue(equalsWithEpsilon(0.9f, frem(10.5f, 3.2f)));
        Asserts.assertTrue(equalsWithEpsilon(-0.9f, frem(-10.5f, 3.2f)));
        Asserts.assertTrue(equalsWithEpsilon(0.9f, frem(10.5f, -3.2f)));
        Asserts.assertTrue(equalsWithEpsilon(-0.9f, frem(-10.5f, -3.2f)));
        Asserts.assertTrue(equalsWithEpsilon(0.0f, frem(9.0f, 3.0f)));
        Asserts.assertTrue(equalsWithEpsilon(0.0000001f, frem(9.0000001f, 3.0f)));

        Asserts.assertEquals(Float.NaN, frem(Float.POSITIVE_INFINITY, 3.0f));
        Asserts.assertEquals(Float.NaN, frem(10.0f, 0.0f));
        Asserts.assertEquals(Float.NaN, frem(Float.POSITIVE_INFINITY, 0.0f));
        Asserts.assertEquals(Float.NaN, frem(Float.NaN, 3.2f));
        Asserts.assertEquals(Float.NaN, frem(10.5f, Float.NaN));
        Asserts.assertEquals(Float.NaN, frem(Float.NaN, Float.NaN));
        Asserts.assertEquals(Float.NaN, frem(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY));
        Asserts.assertEquals(Float.NaN, frem(0.0f, 0.0f));

        Asserts.assertEquals(0.0f, frem(Float.MAX_VALUE, Float.MAX_VALUE / 2.0f));
        Asserts.assertEquals(Float.MIN_VALUE, frem(Float.MIN_VALUE, Float.MIN_VALUE * 2.0f));

        Asserts.assertEquals(10.0f, frem(10.0f, Float.POSITIVE_INFINITY));
        Asserts.assertEquals(-10.0f, frem(-10.0f, Float.POSITIVE_INFINITY));
        Asserts.assertEquals(0.0f, frem(0.0f, 3.0f));
        Asserts.assertEquals(-0.0f, frem(-0.0f, 3.0f));
    }

    public static float frem(float x, float y) {
        return x % y;
    }

    public static boolean equalsWithEpsilon(float x, float y) {
        return Math.abs(x - y) <= FLOAT_EPSILON;
    }
}
