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
 * @summary Exercise object-array checkcast and partial-copy semantics in Jeandle.
 * @run main/othervm -Xbatch -XX:-TieredCompilation -XX:+UseJeandleCompiler
 *      -Xcomp
 *      -XX:CompileCommand=compileonly,compiler.jeandle.arraycopy.TestArrayCopyCheckcast::copy
 *      compiler.jeandle.arraycopy.TestArrayCopyCheckcast
 */

package compiler.jeandle.arraycopy;

public class TestArrayCopyCheckcast {
    static void copy(Object[] src, int srcPos, String[] dst,
                     int dstPos, int length) {
        System.arraycopy(src, srcPos, dst, dstPos, length);
    }

    private static void checkPartialFailure(int badIndex) {
        Object[] src = {"a", "b", "c", "d", "e"};
        src[badIndex] = new Object();
        String[] dst = {"x", "x", "x", "x", "x"};
        try {
            copy(src, 0, dst, 0, src.length);
            throw new AssertionError("missing ArrayStoreException");
        } catch (ArrayStoreException expected) {
            // The prefix before the incompatible element must be copied.
        }
        for (int i = 0; i < badIndex; i++) {
            if (!dst[i].equals(src[i])) {
                throw new AssertionError("prefix was not copied at " + i);
            }
        }
        for (int i = badIndex; i < dst.length; i++) {
            if (!dst[i].equals("x")) {
                throw new AssertionError("suffix was copied at " + i);
            }
        }
    }

    public static void main(String[] args) {
        Object[] allStrings = {"a", "b", "c", "d"};
        String[] strings = {"x", "x", "x", "x"};
        copy(allStrings, 0, strings, 0, allStrings.length);
        for (int i = 0; i < strings.length; i++) {
            if (!strings[i].equals(allStrings[i])) {
                throw new AssertionError("compatible copy failed at " + i);
            }
        }

        Object[] withNull = {"a", null, "c"};
        String[] nullDst = {"x", "x", "x"};
        copy(withNull, 0, nullDst, 0, withNull.length);
        if (!"a".equals(nullDst[0]) || nullDst[1] != null
                || !"c".equals(nullDst[2])) {
            throw new AssertionError("null element copy failed");
        }

        for (int badIndex = 0; badIndex < 5; badIndex++) {
            checkPartialFailure(badIndex);
        }

        // Keep the checkcast path hot enough to compile.
        for (int i = 0; i < 20_000; i++) {
            checkPartialFailure(3);
        }
    }
}
