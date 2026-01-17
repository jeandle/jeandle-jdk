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
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.TestLRem::lrem
 *      -XX:+UseJeandleCompiler compiler.jeandle.bytecodeTranslate.arithmetic.TestLRem
 */

package compiler.jeandle.bytecodeTranslate.arithmetic;

import jdk.test.lib.Asserts;

public class TestLRem {

    public static void main(String[] args) {
        // Normal remainder
        Asserts.assertEquals(0L, lrem(10L, 2L));
        Asserts.assertEquals(0L, lrem(-10L, 2L));
        Asserts.assertEquals(0L, lrem(10L, -2L));
        Asserts.assertEquals(0L, lrem(-10L, -2L));

        // Remainder sign matches dividend
        Asserts.assertEquals(1L, lrem(7L, 2L));
        Asserts.assertEquals(-1L, lrem(-7L, 2L));
        Asserts.assertEquals(1L, lrem(7L, -2L));
        Asserts.assertEquals(-1L, lrem(-7L, -2L));

        // MIN_VALUE % -1 → 0
        Asserts.assertEquals(0L, lrem(Long.MIN_VALUE, -1L));

        // Remainder with 1 and -1
        Asserts.assertEquals(0L, lrem(42L, 1L));
        Asserts.assertEquals(0L, lrem(42L, -1L));

        // Division by zero → ArithmeticException
        Asserts.assertThrows(ArithmeticException.class, () -> lrem(1L, 0L));
        Asserts.assertThrows(ArithmeticException.class, () -> lrem(-1L, 0L));
        Asserts.assertThrows(ArithmeticException.class, () -> lrem(0L, 0L));
        Asserts.assertThrows(ArithmeticException.class, () -> lrem(Long.MIN_VALUE, 0L));
    }

    public static long lrem(long x, long y) {
        return x % y;
    }
}
