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
 * You should have received a copy of the GNU General Public License
 * version 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, 5th Floor, Boston, MA 02110-1301 USA.
 */

/*
 * @test
 * @summary Array length range removes impossible branches without weakening caller checks
 * @library /test/lib /
 * @build compiler.jeandle.fileCheck.FileCheck
 * @run main/othervm -Xbatch -Xcomp -XX:-TieredCompilation -XX:+UseJeandleCompiler
 *      -XX:+JeandleDumpIR -XX:CompileCommand=quiet
 *      -XX:CompileCommand=compileonly,compiler.jeandle.TestArrayLengthRange::negativeLength
 *      -XX:CompileCommand=compileonly,compiler.jeandle.TestArrayLengthRange::invalidCount
 *      -XX:CompileCommand=compileonly,compiler.jeandle.TestArrayLengthRange::targetTooLong
 *      compiler.jeandle.TestArrayLengthRange
 */

package compiler.jeandle;

import compiler.jeandle.fileCheck.FileCheck;

public class TestArrayLengthRange {
    public static int negativeLength(byte[] array) {
        return array.length < 0 ? 7 : 11;
    }

    public static boolean invalidCount(byte[] array, int count) {
        return count < 0 || count > array.length;
    }

    public static boolean targetTooLong(byte[] source, byte[] target) {
        return target.length > source.length;
    }

    public static void main(String[] args) throws Exception {
        byte[] empty = new byte[0];
        byte[] data = new byte[7];
        for (int i = 0; i < 100; i++) {
            if (negativeLength(empty) != 11 || negativeLength(data) != 11) {
                throw new AssertionError("negative Java array length");
            }
            if (!invalidCount(data, -1) || !invalidCount(data, Integer.MIN_VALUE)
                    || !invalidCount(data, 8) || !invalidCount(data, Integer.MAX_VALUE)
                    || invalidCount(data, 0) || invalidCount(data, 7)
                    || !invalidCount(empty, 1) || invalidCount(empty, 0)) {
                throw new AssertionError("caller count guard changed");
            }
            if (!targetTooLong(empty, data) || targetTooLong(data, empty)
                    || targetTooLong(data, data)) {
                throw new AssertionError("source/target length comparison changed");
            }
        }
        try {
            negativeLength(null);
            throw new AssertionError("arraylength null check disappeared");
        } catch (NullPointerException expected) {
        }

        FileCheck check = new FileCheck(System.getProperty("user.dir"),
                TestArrayLengthRange.class.getDeclaredMethod("negativeLength", byte[].class), true);
        check.checkPattern("ret i32 11");
        check.checkNotPattern("icmp slt i32");
    }
}
