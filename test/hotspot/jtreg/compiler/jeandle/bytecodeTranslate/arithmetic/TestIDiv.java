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
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.TestIDiv::idiv
 *      -XX:+UseJeandleCompiler compiler.jeandle.bytecodeTranslate.arithmetic.TestIDiv
 */

package compiler.jeandle.bytecodeTranslate.arithmetic;

import jdk.test.lib.Asserts;

public class TestIDiv {

    public static void main(String[] args) {
        // Normal division
        Asserts.assertEquals(5, idiv(10, 2));
        Asserts.assertEquals(-5, idiv(-10, 2));
        Asserts.assertEquals(-5, idiv(10, -2));
        Asserts.assertEquals(5, idiv(-10, -2));

        // Truncation toward zero
        Asserts.assertEquals(3, idiv(7, 2));
        Asserts.assertEquals(-3, idiv(-7, 2));
        Asserts.assertEquals(-3, idiv(7, -2));
        Asserts.assertEquals(3, idiv(-7, -2));

        // MIN_VALUE / -1 → overflow, result is MIN_VALUE
        Asserts.assertEquals(Integer.MIN_VALUE, idiv(Integer.MIN_VALUE, -1));

        // Division by 1 and -1
        Asserts.assertEquals(42, idiv(42, 1));
        Asserts.assertEquals(-42, idiv(42, -1));

        // Division by zero → ArithmeticException
        Asserts.assertThrows(ArithmeticException.class, () -> idiv(1, 0));
        Asserts.assertThrows(ArithmeticException.class, () -> idiv(-1, 0));
        Asserts.assertThrows(ArithmeticException.class, () -> idiv(0, 0));
        Asserts.assertThrows(ArithmeticException.class, () -> idiv(Integer.MIN_VALUE, 0));
    }

    public static int idiv(int x, int y) {
        return x / y;
    }
}
