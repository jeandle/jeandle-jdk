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
 * @summary Exercise System.arraycopy exception and boundary semantics in Jeandle.
 * @run main/othervm -Xbatch -XX:-TieredCompilation -XX:+UseJeandleCompiler
 *      -Xcomp
 *      -XX:CompileCommand=compileonly,compiler.jeandle.arraycopy.TestArrayCopyExceptions::copy
 *      compiler.jeandle.arraycopy.TestArrayCopyExceptions
 */

package compiler.jeandle.arraycopy;

public class TestArrayCopyExceptions {
    private static int storeExceptions;

    static void copy(Object src, int srcPos, Object dst, int dstPos, int length) {
        System.arraycopy(src, srcPos, dst, dstPos, length);
    }

    private static void expect(Class<? extends Throwable> type,
                               Object src, int srcPos, Object dst,
                               int dstPos, int length) {
        try {
            copy(src, srcPos, dst, dstPos, length);
            throw new AssertionError("missing " + type.getSimpleName());
        } catch (Throwable t) {
            if (!type.isInstance(t)) {
                throw new AssertionError("expected " + type.getName()
                                         + ", got " + t, t);
            }
        }
    }

    public static void main(String[] args) {
        int[] ints = {1, 2, 3, 4};
        long[] longs = {9, 9, 9, 9};

        // Type compatibility is checked even when no elements are copied.
        expect(ArrayStoreException.class, ints, 4, longs, 4, 0);
        expect(ArrayStoreException.class, ints, 0, longs, 0, 1);
        expect(ArrayStoreException.class, longs, 0, ints, 0, 1);

        expect(NullPointerException.class, null, 0, ints, 0, 0);
        expect(NullPointerException.class, ints, 0, null, 0, 0);
        expect(ArrayIndexOutOfBoundsException.class, ints, -1, ints, 0, 1);
        expect(ArrayIndexOutOfBoundsException.class, ints, 0, ints, -1, 1);
        expect(ArrayIndexOutOfBoundsException.class, ints, 0, ints, 0, -1);
        expect(ArrayIndexOutOfBoundsException.class, ints, 4, ints, 0, 1);
        expect(ArrayIndexOutOfBoundsException.class, ints, 0, ints, 4, 1);
        expect(ArrayIndexOutOfBoundsException.class, ints, Integer.MAX_VALUE,
               ints, 0, 1);
        expect(ArrayIndexOutOfBoundsException.class, ints, 0, ints,
               Integer.MAX_VALUE, 1);

        Object nonArray = new Object();
        expect(ArrayStoreException.class, nonArray, 0, ints, 0, 1);
        expect(ArrayStoreException.class, ints, 0, nonArray, 0, 1);

        for (int i = 0; i < 20_000; i++) {
            try {
                copy(ints, 0, longs, 0, 1);
            } catch (ArrayStoreException expected) {
                storeExceptions++;
            }
        }
        if (storeExceptions != 20_000) {
            throw new AssertionError("wrong exception count: " + storeExceptions);
        }
        for (long value : longs) {
            if (value != 9) {
                throw new AssertionError("mismatched primitive copy modified dst");
            }
        }
    }
}
