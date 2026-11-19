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
 * @summary Initialized Java field values enable guarded affine checks
 * @library /test/lib /
 * @build compiler.jeandle.fileCheck.FileCheck compiler.jeandle.checks.CheckLoopShape
 * @run main/othervm -Xcomp -Xbatch -XX:-TieredCompilation -XX:+UseJeandleCompiler
 *      -XX:+JeandleDumpIR -XX:-JeandleDoPEA
 *      -XX:CompileCommand=compileonly,compiler.jeandle.checks.TestLoadedCheckFacts::from*
 *      compiler.jeandle.checks.TestLoadedCheckFacts
 * @run main/othervm -Xcomp -Xbatch -XX:-TieredCompilation -XX:+UseJeandleCompiler
 *      -XX:+JeandleDumpIR -XX:+JeandleDoPEA
 *      -XX:CompileCommand=compileonly,compiler.jeandle.checks.TestLoadedCheckFacts::from*
 *      compiler.jeandle.checks.TestLoadedCheckFacts
 */
package compiler.jeandle.checks;

import compiler.jeandle.fileCheck.FileCheck;
import java.lang.reflect.Method;
import java.util.Arrays;
import jdk.test.lib.Asserts;

public class TestLoadedCheckFacts {
    private static int bound = 30;
    private static int offset = 1;
    private static int side;
    private int instanceBound = 30;
    private int[] nullable;
    private byte smallByte = -2;
    private short smallShort = -3;
    private char character = 65535;
    private boolean flag;

    public static int fromFieldBound(int[] a, int off) {
        int n = bound;
        int sum = a.length;
        for (int i = 0; i < n; i++) {
            side++;
            sum += a[3 * i + off];
        }
        return sum;
    }

    public static int fromFieldOffset(int[] a, int n) {
        int off = offset;
        int sum = a.length;
        for (int i = 0; i < n; i++) {
            side++;
            sum += a[3 * i + off];
        }
        return sum;
    }

    public int fromInstance(int[] a, int off) {
        int n = instanceBound;
        int sum = a.length;
        for (int i = 0; i < n; i++) {
            side++;
            sum += a[3 * i + off];
        }
        return sum;
    }

    public int fromNullable() {
        return nullable == null ? -1 : nullable.length;
    }

    public int fromSubwords() {
        return smallByte + smallShort + character + (flag ? 1 : 0);
    }

    public static void main(String[] args) throws Exception {
        TestLoadedCheckFacts object = new TestLoadedCheckFacts();
        int[] a = new int[100];
        Arrays.fill(a, 1);
        Asserts.assertEquals(fromFieldBound(a, 1), 130);
        Asserts.assertEquals(fromFieldOffset(a, 30), 130);
        Asserts.assertEquals(object.fromInstance(a, 1), 130);
        Asserts.assertEquals(side, 90);
        for (String name : new String[] {"fromFieldBound", "fromFieldOffset", "fromInstance"}) {
            Method target = TestLoadedCheckFacts.class.getDeclaredMethod(name, int[].class, int.class);
            new FileCheck(System.getProperty("user.dir"), target, false)
                    .checkPattern("load atomic i32.*!noundef");
            CheckLoopShape.assertVersioned(target);
        }
        Asserts.assertEquals(object.fromSubwords(), 65530);
        Method subwords = TestLoadedCheckFacts.class.getDeclaredMethod("fromSubwords");
        FileCheck smallLoads = new FileCheck(System.getProperty("user.dir"), subwords, false);
        smallLoads.selectFunction("define.*TestLoadedCheckFacts_fromSubwords.*root");
        smallLoads.checkPattern("load atomic i8.*!noundef");
        smallLoads.checkPattern("load atomic i16.*!noundef");
        smallLoads.checkPattern("load atomic i16.*!noundef");
        smallLoads.checkPattern("load atomic i8.*!noundef");
        Asserts.assertEquals(object.fromNullable(), -1);
        Method nullable = TestLoadedCheckFacts.class.getDeclaredMethod("fromNullable");
        FileCheck referenceLoad = new FileCheck(System.getProperty("user.dir"), nullable, false);
        referenceLoad.selectFunction("define.*TestLoadedCheckFacts_fromNullable.*root");
        referenceLoad.checkPattern("load atomic ptr addrspace\\([13]\\).*!noundef");
        referenceLoad.checkNotPattern("load atomic ptr addrspace\\([13]\\).*!nonnull");
        object.nullable = a;
        Asserts.assertEquals(object.fromNullable(), 100);
        bound = 0;
        Asserts.assertEquals(fromFieldBound(a, Integer.MAX_VALUE), 100);
        bound = 3;
        offset = Integer.MAX_VALUE;
        int before = side;
        try {
            fromFieldOffset(a, 3);
            throw new AssertionError("missing range check");
        } catch (ArrayIndexOutOfBoundsException expected) {
            Asserts.assertEquals(side, before + 1);
        }
        try {
            fromFieldBound(null, 0);
            throw new AssertionError("missing null check");
        } catch (NullPointerException expected) {
            Asserts.assertEquals(side, before + 1);
        }
        System.out.println("LOADED_CHECK_FACTS_EXECUTED " + side);
    }
}
