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
 * @run main/othervm -XX:-TieredCompilation -Xcomp -Xbatch
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.TestTypeConversion::testi2*
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.TestTypeConversion::testl2*
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.TestTypeConversion::testf2*
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.TestTypeConversion::testd2*
 *      -XX:+UseJeandleCompiler compiler.jeandle.bytecodeTranslate.TestTypeConversion
 */

package compiler.jeandle.bytecodeTranslate;

import jdk.test.lib.Asserts;

public class TestTypeConversion {
    public static void main(String[] args) throws Exception {
        preInit();
        testWideningConversion();
        testNarrowingConversion();
    }

    private static void preInit() throws Exception {
        Class.forName("java.lang.Byte");
        Class.forName("java.lang.Character");
        Class.forName("java.lang.Short");
        Class.forName("java.lang.Integer");
        Class.forName("java.lang.Long");
        Class.forName("java.lang.Float");
        Class.forName("java.lang.Double");
        Class.forName("jdk.test.lib.Asserts");
    }

    private static void testWideningConversion() {
        // testi2l
        testi2l(0, 0L);
        testi2l(1, 1L);
        testi2l(-1, -1L);
        testi2l(0x7FFF_FFFF, 0x7FFF_FFFFL);
        testi2l(0x8000_0000, 0xFFFF_FFFF_8000_0000L);

        // testi2f
        testi2f(0, 0.0f);
        testi2f(1, 1.0f);
        testi2f(-1, -1.0f);
        testi2f(0x100_0000, 0x1.0p+24f);                 // No loss of precision
        testi2f(0x100_0001, 0x1.0p+24f);                 // Loss of precision
        testi2f(0x7FFF_FFFF, 0x1.0p+31f);                // Loss of precision
        testi2f(0x8000_0000, -0x1.0p+31f);

        // testi2d
        testi2d(0, 0.0d);
        testi2d(1, 1.0d);
        testi2d(-1, -1.0d);
        testi2d(0x100_0000, 0x1.0p+24d);                 // No loss of precision
        testi2d(0x100_0001, 0x1.000001p+24d);            // No loss of precision
        testi2d(0x7FFF_FFFF, 0x1.FFFF_FFFCp+30d);        // No loss of precision
        testi2d(0x8000_0000, -0x1.0p+31d);

        // testl2f
        testl2f(0L, 0.0f);
        testl2f(1L, 1.0f);
        testl2f(-1L, -1.0f);
        testl2f(0x100_0000L, 0x1.0p+24f);                // No loss of precision
        testl2f(0x100_0001L, 0x1.0p+24f);                // Loss of precision
        testl2f(0x7FFF_FFFF_FFFF_FFFFL, 0x1.0p+63f);     // Loss of precision
        testl2f(0x8000_0000_0000_0000L, -0x1.0p+63f);

        // testl2d
        testl2d(0L, 0.0d);
        testl2d(1L, 1.0d);
        testl2d(-1L, -1.0d);
        testl2d(0x100_0000L, 0x1.0p+24d);                // No loss of precision
        testl2d(0x100_0001L, 0x1.000001p+24d);           // No loss of precision
        testl2d(0x40_0000_0000_0000L, 0x1.0p+54d);       // No loss of precision
        testl2d(0x40_0000_0000_0001L, 0x1.0p+54d);       // Loss of precision
        testl2d(0x7FFF_FFFF_FFFF_FFFFL, 0x1.0p+63d);     // Loss of precision
        testl2d(0x8000_0000_0000_0000L, -0x1.0p+63d);

        // testf2d
        testf2d(0.0f, 0.0d);
        testf2d(1.0f, 1.0d);
        testf2d(-1.0f, -1.0d);
        testf2d(0x1.0p-126f, 0x1.0p-126d);
        testf2d(0x1.0p-149f, 0x1.0p-149d);
        testf2d(0x1.FFFF_FEp+127f, 0x1.FFFF_FEp+127d);
        testf2d(-0x1.FFFF_FEp+127f, -0x1.FFFF_FEp+127d);
    }

    private static void testNarrowingConversion() {
        // testi2b
        testi2b(0, (byte)0);
        testi2b(1, (byte)1);
        testi2b(-1, (byte)-1);
        testi2b(127, (byte)127);
        testi2b(128, (byte)-128);
        testi2b(-128, (byte)-128);
        testi2b(-129, (byte)127);
        testi2b(Integer.MAX_VALUE, (byte)-1);
        testi2b(Integer.MIN_VALUE, (byte)0);

        // testi2c
        testi2c(0, (char)0);
        testi2c(1, (char)1);
        testi2c(-1, (char)-1);
        testi2c(32767, (char)32767);
        testi2c(32768, (char)-32768);
        testi2c(-32768, (char)-32768);
        testi2c(-32769, (char)32767);
        testi2c(Integer.MAX_VALUE, (char)-1);
        testi2c(Integer.MIN_VALUE, (char)0);

        // testi2s
        testi2s(0, (short)0);
        testi2s(1, (short)1);
        testi2s(-1, (short)-1);
        testi2s(32767, (short)32767);
        testi2s(32768, (short)-32768);
        testi2s(-32768, (short)-32768);
        testi2s(-32769, (short)32767);
        testi2s(Integer.MAX_VALUE, (short)-1);
        testi2s(Integer.MIN_VALUE, (short)0);

        // testl2i
        testl2i(0L, 0);
        testl2i(1L, 1);
        testl2i(-1L, -1);
        testl2i(0x7FFF_FFFFL, 0x7FFF_FFFF); //  2147483647
        testl2i(0x8000_0000L, 0x8000_0000); // -2147483648
        testl2i(0xFFFF_FFFF_8000_0000L, 0x8000_0000);
        testl2i(0xFFFF_FFFF_7FFF_FFFFL, 0x7FFF_FFFF);
        testl2i(Long.MAX_VALUE, -1);
        testl2i(Long.MIN_VALUE, 0);

        // testf2i
        testf2i(0.0f, 0);
        testf2i(1.0f, 1);
        testf2i(-1.0f, -1);
        testf2i(0.5f, 0);
        testf2i(-0.5f, 0);
        testf2i(2.4f, 2);
        testf2i(2.6f, 2);
        testf2i(-2.4f, -2);
        testf2i(-2.6f, -2);
        testf2i(2147483648.0f, 2147483647);
        testf2i(-2147483648.0f, -2147483648);
        testf2i(-2147483904.0f, -2147483648);
        testf2i(Float.NaN, 0);
        testf2i(Float.MAX_VALUE, Integer.MAX_VALUE);
        testf2i(Float.MIN_VALUE, 0);
        testf2i(Float.MIN_NORMAL, 0);
        testf2i(Float.POSITIVE_INFINITY, Integer.MAX_VALUE);
        testf2i(Float.NEGATIVE_INFINITY, Integer.MIN_VALUE);

        // testf2l
        testf2l(0.0f, 0L);
        testf2l(1.0f, 1L);
        testf2l(-1.0f, -1L);
        testf2l(0.5f, 0L);
        testf2l(-0.5f, 0L);
        testf2l(2.4f, 2L);
        testf2l(2.6f, 2L);
        testf2l(-2.4f, -2L);
        testf2l(-2.6f, -2L);
        testf2l( 0x1.0p63f, 0x7FFF_FFFF_FFFF_FFFFL);
        testf2l(-0x1.0p63f, 0x8000_0000_0000_0000L);
        testf2l(-0x1.1p63f, 0x8000_0000_0000_0000L);
        testf2l(Float.NaN, 0L);
        testf2l(Float.MAX_VALUE, Long.MAX_VALUE);
        testf2l(Float.MIN_VALUE, 0L);
        testf2l(Float.MIN_NORMAL, 0L);
        testf2l(Float.POSITIVE_INFINITY, Long.MAX_VALUE);
        testf2l(Float.NEGATIVE_INFINITY, Long.MIN_VALUE);

        // testd2i
        testd2i(0.0d, 0);
        testd2i(1.0d, 1);
        testd2i(-1.0d, -1);
        testd2i(0.5d, 0);
        testd2i(-0.5d, 0);
        testd2i(2.4d, 2);
        testd2i(2.6d, 2);
        testd2i(-2.4d, -2);
        testd2i(-2.6d, -2);
        testd2i(2147483648.0d, 2147483647);
        testd2i(-2147483648.0d, -2147483648);
        testd2i(-2147483904.0d, -2147483648);
        testd2i(Double.NaN, 0);
        testd2i(Double.MAX_VALUE, Integer.MAX_VALUE);
        testd2i(Double.MIN_VALUE, 0);
        testd2i(Double.MIN_NORMAL, 0);
        testd2i(Double.POSITIVE_INFINITY, Integer.MAX_VALUE);
        testd2i(Double.NEGATIVE_INFINITY, Integer.MIN_VALUE);

        // testd2l
        testd2l(0.0d, 0L);
        testd2l(1.0d, 1L);
        testd2l(-1.0d, -1L);
        testd2l(0.5d, 0L);
        testd2l(-0.5d, 0L);
        testd2l(2.4d, 2L);
        testd2l(2.6d, 2L);
        testd2l(-2.4d, -2L);
        testd2l(-2.6d, -2L);
        testd2l( 0x1.0p63d, 0x7FFF_FFFF_FFFF_FFFFL);
        testd2l(-0x1.0p63d, 0x8000_0000_0000_0000L);
        testd2l(-0x1.1p63d, 0x8000_0000_0000_0000L);
        testd2l(Double.NaN, 0L);
        testd2l(Double.MAX_VALUE, Long.MAX_VALUE);
        testd2l(Double.MIN_VALUE, 0L);
        testd2l(Double.MIN_NORMAL, 0L);
        testd2l(Double.POSITIVE_INFINITY, Long.MAX_VALUE);
        testd2l(Double.NEGATIVE_INFINITY, Long.MIN_VALUE);

        // testd2f
        testd2f(0.0d, 0.0f);
        testd2f(1.0d, 1.0f);
        testd2f(-1.0d, -1.0f);
        testd2f(0.5d, 0.5f);
        testd2f(-0.5d, -0.5f);
        testd2f(2.4d, 2.4f);
        testd2f(2.6d, 2.6f);
        testd2f(-2.4d, -2.4f);
        testd2f(-2.6d, -2.6f);
        testd2f(0x1.000000p+24d,  0x1.000000p+24f);
        testd2f(0x1.000001p+24d,  0x1.000000p+24f);
        testd2f(0x1.0000001p+24d, 0x1.000000p+24f);
        testd2f(0x1.0000011p+24d, 0x1.000002p+24f);
        testd2f(Double.NaN, Float.NaN);
        testd2f(Double.MAX_VALUE, Float.POSITIVE_INFINITY);
        testd2f(Double.MIN_VALUE, 0.0f);
        testd2f(Double.MIN_NORMAL, 0.0f);
        testd2f(Double.POSITIVE_INFINITY, Float.POSITIVE_INFINITY);
        testd2f(Double.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY);
    }

    private static void testi2l(int input, long expected) {
        Asserts.assertEquals((long)input, expected);
    }

    private static void testi2f(int input, float expected) {
        Asserts.assertEquals((float)input, expected);
    }

    private static void testi2d(int input, double expected) {
        Asserts.assertEquals((double)input, expected);
    }

    private static void testl2f(long input, float expected) {
        Asserts.assertEquals((float)input, expected);
    }

    private static void testl2d(long input, double expected) {
        Asserts.assertEquals((double)input, expected);
    }

    private static void testf2d(float input, double expected) {
        Asserts.assertEquals((double)input, expected);
    }

    private static void testi2b(int input, byte expected) {
        Asserts.assertEquals((byte)input, expected);
    }

    private static void testi2c(int input, char expected) {
        Asserts.assertEquals((char)input, expected);
    }

    private static void testi2s(int input, short expected) {
        Asserts.assertEquals((short)input, expected);
    }

    private static void testl2i(long input, int expected) {
        Asserts.assertEquals((int)input, expected);
    }

    private static void testf2i(float input, int expected) {
        Asserts.assertEquals((int)input, expected);
    }

    private static void testf2l(float input, long expected) {
        Asserts.assertEquals((long)input, expected);
    }

    private static void testd2i(double input, int expected) {
        Asserts.assertEquals((int)input, expected);
    }

    private static void testd2l(double input, long expected) {
        Asserts.assertEquals((long)input, expected);
    }

    private static void testd2f(double input, float expected) {
        Asserts.assertEquals((float)input, expected);
    }
}
