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
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.TestDRem::drem
 *      -XX:+UseJeandleCompiler compiler.jeandle.bytecodeTranslate.arithmetic.TestDRem
 */

package compiler.jeandle.bytecodeTranslate.arithmetic;

import jdk.test.lib.Asserts;

public class TestDRem {

    private static final double DOUBLE_EPSILON = 1e-6d;

    public static void main(String[] args) throws Exception {
        Asserts.assertTrue(equalsWithEpsilon(0.9d, drem(10.5d, 3.2d)));
        Asserts.assertTrue(equalsWithEpsilon(-0.9d, drem(-10.5d, 3.2d)));
        Asserts.assertTrue(equalsWithEpsilon(0.9d, drem(10.5d, -3.2d)));
        Asserts.assertTrue(equalsWithEpsilon(-0.9d, drem(-10.5d, -3.2d)));
        Asserts.assertTrue(equalsWithEpsilon(0.0d, drem(9.0d, 3.0d)));
        Asserts.assertTrue(equalsWithEpsilon(0.0000001d, drem(9.0000001d, 3.0d)));

        Asserts.assertEquals(Double.NaN, drem(Double.POSITIVE_INFINITY, 3.0d));
        Asserts.assertEquals(Double.NaN, drem(10.0d, 0.0d));
        Asserts.assertEquals(Double.NaN, drem(Double.POSITIVE_INFINITY, 0.0d));
        Asserts.assertEquals(Double.NaN, drem(Double.NaN, 3.2d));
        Asserts.assertEquals(Double.NaN, drem(10.5d, Double.NaN));
        Asserts.assertEquals(Double.NaN, drem(Double.NaN, Double.NaN));
        Asserts.assertEquals(Double.NaN, drem(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY));
        Asserts.assertEquals(Double.NaN, drem(0.0d, 0.0d));

        Asserts.assertEquals(0.0d, drem(Double.MAX_VALUE, Double.MAX_VALUE / 2.0d));
        Asserts.assertEquals(Double.MIN_VALUE, drem(Double.MIN_VALUE, Double.MIN_VALUE * 2.0d));

        Asserts.assertEquals(10.0d, drem(10.0d, Double.POSITIVE_INFINITY));
        Asserts.assertEquals(-10.0d, drem(-10.0d, Double.POSITIVE_INFINITY));
        Asserts.assertEquals(0.0d, drem(0.0d, 3.0d));
        Asserts.assertEquals(-0.0d, drem(-0.0d, 3.0d));
    }

    public static double drem(double x, double y) {
        return x % y;
    }

    public static boolean equalsWithEpsilon(double x, double y) {
        return Math.abs(x - y) <= DOUBLE_EPSILON;
    }
}
