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
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.TestDDiv::ddiv
 *      -XX:+UseJeandleCompiler compiler.jeandle.bytecodeTranslate.arithmetic.TestDDiv
 */

package compiler.jeandle.bytecodeTranslate.arithmetic;

import jdk.test.lib.Asserts;

public class TestDDiv {

    public static void main(String[] args) throws Exception {
        Asserts.assertEquals(5.0d, ddiv(10.0d, 2.0d));
        Asserts.assertEquals(-5.0d, ddiv(-10.0d, 2.0d));
        Asserts.assertEquals(-5.0d, ddiv(10.0d, -2.0d));
        Asserts.assertEquals(5.0d, ddiv(-10.0d, -2.0d));
        Asserts.assertEquals(1.57d, ddiv(3.14d, 2.0d));

        Asserts.assertEquals(Double.NaN, ddiv(Double.NaN, 1.0d));
        Asserts.assertEquals(Double.NaN, ddiv(1.0d, Double.NaN));
        Asserts.assertEquals(Double.NaN, ddiv(Double.NaN, Double.NaN));
        Asserts.assertEquals(Double.NaN, ddiv(Double.NaN, Double.POSITIVE_INFINITY));
        Asserts.assertEquals(Double.NaN, ddiv(Double.POSITIVE_INFINITY, Double.NaN));
        Asserts.assertEquals(Double.NaN, ddiv(Double.NaN, Double.NEGATIVE_INFINITY));
        Asserts.assertEquals(Double.NaN, ddiv(Double.NEGATIVE_INFINITY, Double.NaN));

        Asserts.assertEquals(Double.NaN, ddiv(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY));
        Asserts.assertEquals(Double.NaN, ddiv(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY));
        Asserts.assertEquals(Double.NaN, ddiv(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY));
        Asserts.assertEquals(Double.NaN, ddiv(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY));
        Asserts.assertEquals(Double.POSITIVE_INFINITY, ddiv(Double.POSITIVE_INFINITY, 5.0d));
        Asserts.assertEquals(Double.NEGATIVE_INFINITY, ddiv(Double.POSITIVE_INFINITY, -5.0d));
        Asserts.assertEquals(Double.NEGATIVE_INFINITY, ddiv(Double.NEGATIVE_INFINITY, 5.0d));
        Asserts.assertEquals(Double.POSITIVE_INFINITY, ddiv(Double.NEGATIVE_INFINITY, -5.0d));
        Asserts.assertEquals(+0.0d, ddiv(5.0d, Double.POSITIVE_INFINITY));
        Asserts.assertEquals(-0.0d, ddiv(5.0d, Double.NEGATIVE_INFINITY));
        Asserts.assertEquals(-0.0d, ddiv(-5.0d, Double.POSITIVE_INFINITY));
        Asserts.assertEquals(+0.0d, ddiv(-5.0d, Double.NEGATIVE_INFINITY));

        Asserts.assertEquals(Double.NaN, ddiv(0.0d, 0.0d));
        Asserts.assertEquals(Double.NaN, ddiv(0.0d, -0.0d));
        Asserts.assertEquals(Double.NaN, ddiv(-0.0d, 0.0d));
        Asserts.assertEquals(Double.NaN, ddiv(-0.0d, -0.0d));
        Asserts.assertEquals(+0.0d, ddiv(0.0d, 10.0d));
        Asserts.assertEquals(-0.0d, ddiv(0.0d, -10.0d));
        Asserts.assertEquals(-0.0d, ddiv(-0.0d, 10.0d));
        Asserts.assertEquals(+0.0d, ddiv(-0.0d, -10.0d));
        Asserts.assertEquals(Double.POSITIVE_INFINITY, ddiv(10.0d, 0.0d));
        Asserts.assertEquals(Double.NEGATIVE_INFINITY, ddiv(10.0d, -0.0d));
        Asserts.assertEquals(Double.NEGATIVE_INFINITY, ddiv(-10.0d, 0.0d));
        Asserts.assertEquals(Double.POSITIVE_INFINITY, ddiv(-10.0d, -0.0d));

        Asserts.assertEquals(Double.POSITIVE_INFINITY, ddiv(Double.MAX_VALUE, Double.MIN_VALUE));
        Asserts.assertEquals(Double.NEGATIVE_INFINITY, ddiv(-Double.MAX_VALUE, Double.MIN_VALUE));
        Asserts.assertEquals(0.0d, ddiv(Double.MIN_VALUE, Double.MAX_VALUE));
        Asserts.assertEquals(-0.0d, ddiv(-Double.MIN_VALUE, Double.MAX_VALUE));
    }

    public static double ddiv(double x, double y) {
        return x / y;
    }
}
