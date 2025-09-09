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
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.TestDSub::dsub
 *      -XX:+UseJeandleCompiler compiler.jeandle.bytecodeTranslate.arithmetic.TestDSub
 */

package compiler.jeandle.bytecodeTranslate.arithmetic;

import jdk.test.lib.Asserts;

public class TestDSub {

    public static void main(String[] args) throws Exception {
        Asserts.assertEquals(4.0d, dsub(5.0d, 1.0d));
        Asserts.assertEquals(-4.0d, dsub(-5.0d, -1.0d));
        Asserts.assertEquals(6.0d, dsub(5.0d, -1.0d));

        Asserts.assertEquals(-5.0d, dsub(0.0d, 5.0d));
        Asserts.assertEquals(5.0d, dsub(0.0d, -5.0d));
        Asserts.assertEquals(-5.0d, dsub(-0.0d, 5.0d));
        Asserts.assertEquals(5.0d, dsub(-0.0d, -5.0d));
        Asserts.assertEquals(5.0d, dsub(5.0d, 0.0d));
        Asserts.assertEquals(5.0d, dsub(5.0d, -0.0d));
        Asserts.assertEquals(-5.0d, dsub(-5.0d, 0.0d));
        Asserts.assertEquals(-5.0d, dsub(-5.0d, -0.0d));

        Asserts.assertEquals(0.0d, dsub(0.0d, 0.0d));
        Asserts.assertEquals(0.0d, dsub(0.0d, -0.0d));
        Asserts.assertEquals(-0.0d, dsub(-0.0d, 0.0d));
        Asserts.assertEquals(0.0d, dsub(-0.0d, -0.0d));

        Asserts.assertEquals(Double.NaN, dsub(Double.NaN, 5.0d));
        Asserts.assertEquals(Double.NaN, dsub(5.0d, Double.NaN));

        Asserts.assertEquals(Double.POSITIVE_INFINITY, dsub(Double.POSITIVE_INFINITY, 5.0d));
        Asserts.assertEquals(Double.NEGATIVE_INFINITY, dsub(5.0d, Double.POSITIVE_INFINITY));
        Asserts.assertEquals(Double.NEGATIVE_INFINITY, dsub(Double.NEGATIVE_INFINITY, 5.0d));
        Asserts.assertEquals(Double.POSITIVE_INFINITY, dsub(5.0d, Double.NEGATIVE_INFINITY));
        Asserts.assertEquals(Double.NaN, dsub(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY));
        Asserts.assertEquals(Double.POSITIVE_INFINITY, dsub(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY));
        Asserts.assertEquals(Double.NEGATIVE_INFINITY, dsub(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY));
        Asserts.assertEquals(Double.NaN, dsub(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY));

        Asserts.assertEquals(0.0d, dsub(Double.MIN_VALUE, Double.MIN_VALUE));
        Asserts.assertEquals(Double.MIN_VALUE, dsub(Double.MIN_VALUE * 2, Double.MIN_VALUE));
        Asserts.assertEquals(0.0d, dsub(Double.MAX_VALUE, Double.MAX_VALUE));
        Asserts.assertEquals(Double.MAX_VALUE, dsub(Double.MAX_VALUE, 1.0d));
    }

    public static double dsub(double x, double y) {
        return x - y;
    }
}
