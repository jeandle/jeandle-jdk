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
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.TestFSub::fsub
 *      -XX:+UseJeandleCompiler compiler.jeandle.bytecodeTranslate.arithmetic.TestFSub
 */

package compiler.jeandle.bytecodeTranslate.arithmetic;

import jdk.test.lib.Asserts;

public class TestFSub {

    public static void main(String[] args) throws Exception {
        Asserts.assertEquals(4.0f, fsub(5.0f, 1.0f));
        Asserts.assertEquals(-4.0f, fsub(-5.0f, -1.0f));
        Asserts.assertEquals(6.0f, fsub(5.0f, -1.0f));

        Asserts.assertEquals(-5.0f, fsub(0.0f, 5.0f));
        Asserts.assertEquals(5.0f, fsub(0.0f, -5.0f));
        Asserts.assertEquals(-5.0f, fsub(-0.0f, 5.0f));
        Asserts.assertEquals(5.0f, fsub(-0.0f, -5.0f));
        Asserts.assertEquals(5.0f, fsub(5.0f, 0.0f));
        Asserts.assertEquals(5.0f, fsub(5.0f, -0.0f));
        Asserts.assertEquals(-5.0f, fsub(-5.0f, 0.0f));
        Asserts.assertEquals(-5.0f, fsub(-5.0f, -0.0f));

        Asserts.assertEquals(0.0f, fsub(0.0f, 0.0f));
        Asserts.assertEquals(0.0f, fsub(0.0f, -0.0f));
        Asserts.assertEquals(-0.0f, fsub(-0.0f, 0.0f));
        Asserts.assertEquals(0.0f, fsub(-0.0f, -0.0f));

        Asserts.assertEquals(Float.NaN, fsub(Float.NaN, 5.0f));
        Asserts.assertEquals(Float.NaN, fsub(5.0f, Float.NaN));

        Asserts.assertEquals(Float.POSITIVE_INFINITY, fsub(Float.POSITIVE_INFINITY, 5.0f));
        Asserts.assertEquals(Float.NEGATIVE_INFINITY, fsub(5.0f, Float.POSITIVE_INFINITY));
        Asserts.assertEquals(Float.NEGATIVE_INFINITY, fsub(Float.NEGATIVE_INFINITY, 5.0f));
        Asserts.assertEquals(Float.POSITIVE_INFINITY, fsub(5.0f, Float.NEGATIVE_INFINITY));
        Asserts.assertEquals(Float.NaN, fsub(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY));
        Asserts.assertEquals(Float.POSITIVE_INFINITY, fsub(Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY));
        Asserts.assertEquals(Float.NEGATIVE_INFINITY, fsub(Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY));
        Asserts.assertEquals(Float.NaN, fsub(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY));

        Asserts.assertEquals(0.0f, fsub(Float.MIN_VALUE, Float.MIN_VALUE));
        Asserts.assertEquals(Float.MIN_VALUE, fsub(Float.MIN_VALUE * 2, Float.MIN_VALUE));
        Asserts.assertEquals(0.0f, fsub(Float.MAX_VALUE, Float.MAX_VALUE));
        Asserts.assertEquals(Float.MAX_VALUE, fsub(Float.MAX_VALUE, 1.0f));

    }

    public static float fsub(float x, float y) {
        return x - y;
    }
}
