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
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/*
 * @test
 * @requires os.arch=="aarch64"
 * @summary Correcting the gap in relocation target with special addend.
 *  issue: https://github.com/jeandle/jeandle-jdk/issues/326
 *
 * @library /test/lib
 * @run main/othervm -Xcomp -XX:-TieredCompilation
 *      -XX:CompileCommand=compileonly,compiler.jeandle.relocation.TestMultiJumpTable::returnNumberWithTwoJumpTable
 *      -XX:+UseJeandleCompiler compiler.jeandle.relocation.TestMultiJumpTable
 */

package compiler.jeandle.relocation;

import jdk.test.lib.Asserts;

public class TestMultiJumpTable {
    private static int i0 = 0;
    private static int i9 = 9;
    private static int i1 = 1;
    private static int i3 = 3;
    private static int i2 = 2;
    private static int i4 = 4;
    private static int i7 = 7;
    private static int i6 = 6;
    private static int i5 = 5;
    private static int i8 = 8;

    public static int returnNumberWithTwoJumpTable(int s1, int s2) {
        switch (s1) {
            case 0:
                return s1 + i0;
            case 1:
                return s1 + i1;
            case 2:
                return s1 + i2;
            case 3:
                return s1 + i3;
            case 4:
                return s1 + i4;
            case 5:
                return s1 + i5;
            case 6:
                return s1 + i6;
            case 7:
                return s1 + i7;
            case 8:
                return s1 + i8;
            case 9:
                return s1 + i9;
            default:
        }
        switch (s2) {
            case 0:
                return s2 + i0 + 1;
            case 1:
                return s2 + i1 + 2;
            case 2:
                return s2 + i2 + 3;
            case 3:
                return s2 + i3 + 4;
            case 4:
                return s2 + i4 + 5;
            case 5:
                return s2 + i5 + 6;
            case 6:
                return s2 + i6 + 7;
            case 7:
                return s2 + i7 + 8;
            case 8:
                return s2 + i8 + 9;
            case 9:
                return s2 + i9 + 10;
            default:
                return 0;
        }
    }

    public static void main(String[] args) throws Exception {
        Asserts.assertEquals(returnNumberWithTwoJumpTable(10, 0), 1);
        Asserts.assertEquals(returnNumberWithTwoJumpTable(10, 1), 4);
        Asserts.assertEquals(returnNumberWithTwoJumpTable(10, 2), 7);
        Asserts.assertEquals(returnNumberWithTwoJumpTable(10, 3), 10);
        Asserts.assertEquals(returnNumberWithTwoJumpTable(10, 4), 13);
        Asserts.assertEquals(returnNumberWithTwoJumpTable(10, 5), 16);
        Asserts.assertEquals(returnNumberWithTwoJumpTable(10, 6), 19);
        Asserts.assertEquals(returnNumberWithTwoJumpTable(10, 7), 22);
        Asserts.assertEquals(returnNumberWithTwoJumpTable(10, 8), 25);
        Asserts.assertEquals(returnNumberWithTwoJumpTable(10, 9), 28);
    }
}
