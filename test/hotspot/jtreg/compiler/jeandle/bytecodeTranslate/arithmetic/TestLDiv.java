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

/*
 * @test
 * @library /test/lib
 * @build jdk.test.lib.Asserts
 * @run main/othervm -XX:-TieredCompilation -Xcomp
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.TestLDiv::ldiv
 *      -XX:+UseJeandleCompiler compiler.jeandle.bytecodeTranslate.arithmetic.TestLDiv
 */

package compiler.jeandle.bytecodeTranslate.arithmetic;

import jdk.test.lib.Asserts;

public class TestLDiv {

    public static void main(String[] args) {
        // Normal division
        Asserts.assertEquals(5L, ldiv(10L, 2L));
        Asserts.assertEquals(-5L, ldiv(-10L, 2L));
        Asserts.assertEquals(-5L, ldiv(10L, -2L));
        Asserts.assertEquals(5L, ldiv(-10L, -2L));

        // Truncation toward zero
        Asserts.assertEquals(3L, ldiv(7L, 2L));
        Asserts.assertEquals(-3L, ldiv(-7L, 2L));
        Asserts.assertEquals(-3L, ldiv(7L, -2L));
        Asserts.assertEquals(3L, ldiv(-7L, -2L));

        // MIN_VALUE / -1 → overflow, result is MIN_VALUE
        Asserts.assertEquals(Long.MIN_VALUE, ldiv(Long.MIN_VALUE, -1L));

        // Division by 1 and -1
        Asserts.assertEquals(42L, ldiv(42L, 1L));
        Asserts.assertEquals(-42L, ldiv(42L, -1L));

        // Division by zero → ArithmeticException
        Asserts.assertThrows(ArithmeticException.class, () -> ldiv(1L, 0L));
        Asserts.assertThrows(ArithmeticException.class, () -> ldiv(-1L, 0L));
        Asserts.assertThrows(ArithmeticException.class, () -> ldiv(0L, 0L));
        Asserts.assertThrows(ArithmeticException.class, () -> ldiv(Long.MIN_VALUE, 0L));
    }

    public static long ldiv(long x, long y) {
        return x / y;
    }
}
