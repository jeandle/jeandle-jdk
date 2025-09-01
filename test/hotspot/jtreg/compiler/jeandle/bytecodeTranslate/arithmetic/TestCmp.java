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
 * @compile Cmp.jasm TestCmp.java
 * @run main/othervm -XX:-TieredCompilation -Xcomp -Xbatch
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.Cmp::testIf*
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.Cmp::testLcmp
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.Cmp::testFcmp*
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.Cmp::testDcmp*
 *      -XX:+UseJeandleCompiler compiler.jeandle.bytecodeTranslate.arithmetic.TestCmp
 */

package compiler.jeandle.bytecodeTranslate.arithmetic;

import jdk.test.lib.Asserts;

public class TestCmp {
    public static void main(String[] args) throws Exception {
        testPrimitiveComparison();
        testReferenceComparison();
    }

    private static void testPrimitiveComparison() {
        // if_icmpeq
        Asserts.assertTrue(Cmp.testIfIcmpeq(1, 1));
        Asserts.assertFalse(Cmp.testIfIcmpeq(1, 0));

        // if_icmpne
        Asserts.assertTrue(Cmp.testIfIcmpne(1, 0));
        Asserts.assertFalse(Cmp.testIfIcmpne(1, 1));

        // if_icmplt
        Asserts.assertTrue(Cmp.testIfIcmplt(0, 1));
        Asserts.assertFalse(Cmp.testIfIcmplt(1, 0));

        // if_icmple
        Asserts.assertTrue(Cmp.testIfIcmple(0, 1));
        Asserts.assertTrue(Cmp.testIfIcmple(1, 1));
        Asserts.assertFalse(Cmp.testIfIcmple(1, 0));

        // if_icmpgt
        Asserts.assertTrue(Cmp.testIfIcmpgt(1, 0));
        Asserts.assertFalse(Cmp.testIfIcmpgt(0, 1));

        //if_icmpge
        Asserts.assertTrue(Cmp.testIfIcmpge(1, 0));
        Asserts.assertTrue(Cmp.testIfIcmpge(1, 1));
        Asserts.assertFalse(Cmp.testIfIcmpge(0, 1));

        // lcmp
        Asserts.assertEquals(Cmp.testLcmp(1L, 0L), 1);
        Asserts.assertEquals(Cmp.testLcmp(0L, 1L), -1);
        Asserts.assertEquals(Cmp.testLcmp(1L, 1L), 0);

        // fcmpl
        Asserts.assertEquals(Cmp.testFcmpl(1.0f, 0.0f), 1);
        Asserts.assertEquals(Cmp.testFcmpl(0.0f, 1.0f), -1);
        Asserts.assertEquals(Cmp.testFcmpl(1.0f, 1.0f), 0);
        Asserts.assertEquals(Cmp.testFcmpl(Float.NaN, 1.0f), -1);
        Asserts.assertEquals(Cmp.testFcmpl(1.0f, Float.NaN), -1);
        Asserts.assertEquals(Cmp.testFcmpl(Float.NaN, Float.NaN), -1);

        // fcmpg
        Asserts.assertEquals(Cmp.testFcmpg(1.0f, 0.0f), 1);
        Asserts.assertEquals(Cmp.testFcmpg(0.0f, 1.0f), -1);
        Asserts.assertEquals(Cmp.testFcmpg(1.0f, 1.0f), 0);
        Asserts.assertEquals(Cmp.testFcmpg(Float.NaN, 1.0f), 1);
        Asserts.assertEquals(Cmp.testFcmpg(1.0f, Float.NaN), 1);
        Asserts.assertEquals(Cmp.testFcmpg(Float.NaN, Float.NaN), 1);

        // dcmpl
        Asserts.assertEquals(Cmp.testDcmpl(1.0, 0.0), 1);
        Asserts.assertEquals(Cmp.testDcmpl(0.0, 1.0), -1);
        Asserts.assertEquals(Cmp.testDcmpl(1.0, 1.0), 0);
        Asserts.assertEquals(Cmp.testDcmpl(Double.NaN, 1.0), -1);
        Asserts.assertEquals(Cmp.testDcmpl(1.0, Double.NaN), -1);
        Asserts.assertEquals(Cmp.testDcmpl(Double.NaN, Double.NaN), -1);

        // dcmpg
        Asserts.assertEquals(Cmp.testDcmpg(1.0, 0.0), 1);
        Asserts.assertEquals(Cmp.testDcmpg(0.0, 1.0), -1);
        Asserts.assertEquals(Cmp.testDcmpg(1.0, 1.0), 0);
        Asserts.assertEquals(Cmp.testDcmpg(Double.NaN, 1.0), 1);
        Asserts.assertEquals(Cmp.testDcmpg(1.0, Double.NaN), 1);
        Asserts.assertEquals(Cmp.testDcmpg(Double.NaN, Double.NaN), 1);
    }

    private static void testReferenceComparison() {
        Object a = new Object();
        Object b = new Object();

        // if_acmpeq
        Asserts.assertTrue(Cmp.testIfAcmpeq(a, a));
        Asserts.assertFalse(Cmp.testIfAcmpeq(a, b));

        // if_acmpne
        Asserts.assertTrue(Cmp.testIfAcmpne(a, b));
        Asserts.assertFalse(Cmp.testIfAcmpne(a, a));

        // ifnull
        Asserts.assertTrue(Cmp.testIfNull(null));
        Asserts.assertFalse(Cmp.testIfNull(a));

        // ifnonnull
        Asserts.assertTrue(Cmp.testIfNonNull(a));
        Asserts.assertFalse(Cmp.testIfNonNull(null));
    }
}
