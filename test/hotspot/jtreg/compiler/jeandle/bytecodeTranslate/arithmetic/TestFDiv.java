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
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.TestFDiv::fdiv
 *      -XX:+UseJeandleCompiler compiler.jeandle.bytecodeTranslate.arithmetic.TestFDiv
 */

package compiler.jeandle.bytecodeTranslate.arithmetic;

import jdk.test.lib.Asserts;

public class TestFDiv {

    public static void main(String[] args) throws Exception {
        Asserts.assertEquals(5.0f, fdiv(10.0f, 2.0f));
        Asserts.assertEquals(-5.0f, fdiv(-10.0f, 2.0f));
        Asserts.assertEquals(-5.0f, fdiv(10.0f, -2.0f));
        Asserts.assertEquals(5.0f, fdiv(-10.0f, -2.0f));
        Asserts.assertEquals(1.57f, fdiv(3.14f, 2.0f));

        Asserts.assertEquals(Float.NaN, fdiv(Float.NaN, 1.0f));
        Asserts.assertEquals(Float.NaN, fdiv(1.0f, Float.NaN));
        Asserts.assertEquals(Float.NaN, fdiv(Float.NaN, Float.NaN));
        Asserts.assertEquals(Float.NaN, fdiv(Float.NaN, Float.POSITIVE_INFINITY));
        Asserts.assertEquals(Float.NaN, fdiv(Float.POSITIVE_INFINITY, Float.NaN));
        Asserts.assertEquals(Float.NaN, fdiv(Float.NaN, Float.NEGATIVE_INFINITY));
        Asserts.assertEquals(Float.NaN, fdiv(Float.NEGATIVE_INFINITY, Float.NaN));

        Asserts.assertEquals(Float.NaN, fdiv(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY));
        Asserts.assertEquals(Float.NaN, fdiv(Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY));
        Asserts.assertEquals(Float.NaN, fdiv(Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY));
        Asserts.assertEquals(Float.NaN, fdiv(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY));
        Asserts.assertEquals(Float.POSITIVE_INFINITY, fdiv(Float.POSITIVE_INFINITY, 5.0f));
        Asserts.assertEquals(Float.NEGATIVE_INFINITY, fdiv(Float.POSITIVE_INFINITY, -5.0f));
        Asserts.assertEquals(Float.NEGATIVE_INFINITY, fdiv(Float.NEGATIVE_INFINITY, 5.0f));
        Asserts.assertEquals(Float.POSITIVE_INFINITY, fdiv(Float.NEGATIVE_INFINITY, -5.0f));
        Asserts.assertEquals(+0.0f, fdiv(5.0f, Float.POSITIVE_INFINITY));
        Asserts.assertEquals(-0.0f, fdiv(5.0f, Float.NEGATIVE_INFINITY));
        Asserts.assertEquals(-0.0f, fdiv(-5.0f, Float.POSITIVE_INFINITY));
        Asserts.assertEquals(+0.0f, fdiv(-5.0f, Float.NEGATIVE_INFINITY));

        Asserts.assertEquals(Float.NaN, fdiv(0.0f, 0.0f));
        Asserts.assertEquals(Float.NaN, fdiv(0.0f, -0.0f));
        Asserts.assertEquals(Float.NaN, fdiv(-0.0f, 0.0f));
        Asserts.assertEquals(Float.NaN, fdiv(-0.0f, -0.0f));
        Asserts.assertEquals(+0.0f, fdiv(0.0f, 10.0f));
        Asserts.assertEquals(-0.0f, fdiv(0.0f, -10.0f));
        Asserts.assertEquals(-0.0f, fdiv(-0.0f, 10.0f));
        Asserts.assertEquals(+0.0f, fdiv(-0.0f, -10.0f));
        Asserts.assertEquals(Float.POSITIVE_INFINITY, fdiv(10.0f, 0.0f));
        Asserts.assertEquals(Float.NEGATIVE_INFINITY, fdiv(10.0f, -0.0f));
        Asserts.assertEquals(Float.NEGATIVE_INFINITY, fdiv(-10.0f, 0.0f));
        Asserts.assertEquals(Float.POSITIVE_INFINITY, fdiv(-10.0f, -0.0f));

        Asserts.assertEquals(Float.POSITIVE_INFINITY, fdiv(Float.MAX_VALUE, Float.MIN_VALUE));
        Asserts.assertEquals(Float.NEGATIVE_INFINITY, fdiv(-Float.MAX_VALUE, Float.MIN_VALUE));
        Asserts.assertEquals(0.0f, fdiv(Float.MIN_VALUE, Float.MAX_VALUE));
        Asserts.assertEquals(-0.0f, fdiv(-Float.MIN_VALUE, Float.MAX_VALUE));
    }

    public static float fdiv(float x, float y) {
        return x / y;
    }
}
