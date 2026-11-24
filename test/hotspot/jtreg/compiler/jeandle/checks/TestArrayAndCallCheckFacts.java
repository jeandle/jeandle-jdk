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
 */

/*
 * @test
 * @summary Java array elements and normal call results expose defined values without implying non-null
 * @library /test/lib /
 * @build compiler.jeandle.fileCheck.FileCheck compiler.jeandle.checks.CheckLoopShape
 * @run main/othervm -Xcomp -Xbatch -XX:-TieredCompilation -XX:+UseJeandleCompiler
 *      -XX:+JeandleDumpIR -XX:-JeandleDoPEA
 *      -XX:CompileCommand=compileonly,compiler.jeandle.checks.TestArrayAndCallCheckFacts::from*
 *      -XX:CompileCommand=dontinline,compiler.jeandle.checks.TestArrayAndCallCheckFacts::get*
 *      compiler.jeandle.checks.TestArrayAndCallCheckFacts
 * @run main/othervm -Xcomp -Xbatch -XX:-TieredCompilation -XX:+UseJeandleCompiler
 *      -XX:+JeandleDumpIR -XX:+JeandleDoPEA
 *      -XX:CompileCommand=compileonly,compiler.jeandle.checks.TestArrayAndCallCheckFacts::from*
 *      -XX:CompileCommand=dontinline,compiler.jeandle.checks.TestArrayAndCallCheckFacts::get*
 *      compiler.jeandle.checks.TestArrayAndCallCheckFacts
 */
package compiler.jeandle.checks;

import compiler.jeandle.fileCheck.FileCheck;
import java.lang.reflect.Method;
import java.util.Arrays;
import jdk.test.lib.Asserts;

public class TestArrayAndCallCheckFacts {
    private static int bound = 30;
    private static int side;
    private static Object nullable;

    private static int getBound() { return bound; }
    private static Object getNullable() { return nullable; }

    public static int fromArrayBound(int[] a, int[] bounds, int offset) {
        int n = bounds[0];
        int sum = a.length;
        for (int i = 0; i < n; i++) {
            side++;
            sum += a[3 * i + offset];
        }
        return sum;
    }

    public static int fromCallBound(int[] a, int offset) {
        int n = getBound();
        int sum = a.length;
        for (int i = 0; i < n; i++) {
            side++;
            sum += a[3 * i + offset];
        }
        return sum;
    }

    public static Object fromReferenceArray(Object[] a) { return a[0]; }
    public static Object fromReferenceCall() { return getNullable(); }

    public static double fromElements(byte[] b, boolean[] z, short[] s, char[] c,
                                      int[] i, long[] l, float[] f, double[] d) {
        return b[0] + (z[0] ? 1 : 0) + s[0] + c[0] + i[0] + l[0] + f[0] + d[0];
    }

    private static FileCheck input(String name, Class<?>... params) throws Exception {
        Method method = TestArrayAndCallCheckFacts.class.getDeclaredMethod(name, params);
        FileCheck check = new FileCheck(System.getProperty("user.dir"), method, false);
        check.selectFunction("define.*TestArrayAndCallCheckFacts_" + name + ".*root");
        return check;
    }

    public static void main(String[] args) throws Exception {
        int[] a = new int[100];
        Arrays.fill(a, 1);
        Asserts.assertEquals(fromArrayBound(a, new int[] {30}, 1), 130);
        Asserts.assertEquals(fromCallBound(a, 1), 130);
        input("fromArrayBound", int[].class, int[].class, int.class)
                .checkPattern("load atomic i32, ptr addrspace\\(1\\) %array_element_address.*!noundef");
        input("fromCallBound", int[].class, int.class)
                .checkPattern("invoke hotspotcc noundef i32");
        CheckLoopShape.assertVersioned(TestArrayAndCallCheckFacts.class.getDeclaredMethod(
                "fromArrayBound", int[].class, int[].class, int.class));
        CheckLoopShape.assertVersioned(TestArrayAndCallCheckFacts.class.getDeclaredMethod(
                "fromCallBound", int[].class, int.class));

        Asserts.assertNull(fromReferenceArray(new Object[] {null}));
        Asserts.assertNull(fromReferenceCall());
        input("fromReferenceArray", Object[].class)
                .checkPattern("load atomic ptr addrspace\\([13]\\).*!noundef");
        input("fromReferenceArray", Object[].class)
                .checkNotPattern("load atomic ptr addrspace\\([13]\\).*!nonnull");
        input("fromReferenceCall").checkPattern("invoke hotspotcc noundef.*ptr addrspace\\(1\\)");
        input("fromReferenceCall").checkNotPattern("invoke hotspotcc.*nonnull");
        nullable = a;
        Asserts.assertEquals(fromReferenceCall(), a);
        Asserts.assertEquals(fromReferenceArray(new Object[] {a}), a);

        Asserts.assertEquals(fromElements(new byte[] {-2}, new boolean[] {true},
                new short[] {-3}, new char[] {65535}, new int[] {7}, new long[] {11},
                new float[] {13}, new double[] {17}), 65579.0);
        FileCheck elements = input("fromElements", byte[].class, boolean[].class, short[].class,
                char[].class, int[].class, long[].class, float[].class, double[].class);
        for (String type : new String[] {"i8", "i8", "i16", "i16", "i32", "i64", "float", "double"}) {
            elements.checkPattern("load atomic " + type + ".*!noundef");
        }
        bound = 0;
        Asserts.assertEquals(fromCallBound(a, Integer.MAX_VALUE), 100);
        bound = 30;
        int before = side;
        try {
            fromArrayBound(a, new int[] {30}, Integer.MAX_VALUE);
            throw new AssertionError("missing bounds exception");
        } catch (ArrayIndexOutOfBoundsException expected) {
            Asserts.assertEquals(side, before + 1);
        }
        try {
            fromCallBound(null, 1);
            throw new AssertionError("missing null exception");
        } catch (NullPointerException expected) {
            Asserts.assertEquals(side, before + 1);
        }
        System.out.println("ARRAY_AND_CALL_CHECK_FACTS_EXECUTED " + side);
    }
}
