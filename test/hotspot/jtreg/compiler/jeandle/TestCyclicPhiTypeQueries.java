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
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License
 * version 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 */

/*
 * @test
 * @summary Compilation completes for shared inputs depending on cyclic Java type queries
 * @run main/othervm/timeout=120 -Xcomp -Xbatch -XX:-TieredCompilation -XX:+UseJeandleCompiler
 *      -XX:CompileCommand=compileonly,compiler.jeandle.TestCyclicPhiTypeQueries::test
 *      compiler.jeandle.TestCyclicPhiTypeQueries
 */

package compiler.jeandle;

public class TestCyclicPhiTypeQueries {
    static volatile int marker;
    static class Node {
        final int value;
        Node(int value) { this.value = value; }
        int get() { return value; }
    }
    static class Left extends Node { Left() { super(11); } }
    static class Right extends Node { Right() { super(17); } }
    static int test(Left left, Right right, int mask, int count) {
        Object a = left;
        Object b = right;
        int sum = 0;
        for (int i = 0; i < count; i++) {
            if ((i & 1) == 0) {
                // Keep this reset branch from becoming a select in the type-query cycle.
                marker = i;
                a = left;
                b = right;
            } else {
                Object tmp = a;
                a = b;
                b = tmp;
            }
            Object c = a;
            Object d = b;
            // Spell out the swaps to create a shared dependency chain in the IR.
            if ((mask & (1 << 0)) != 0) {
                Object tmp = c;
                c = d;
                d = tmp;
            }
            if ((mask & (1 << 1)) != 0) {
                Object tmp = c;
                c = d;
                d = tmp;
            }
            if ((mask & (1 << 2)) != 0) {
                Object tmp = c;
                c = d;
                d = tmp;
            }
            if ((mask & (1 << 3)) != 0) {
                Object tmp = c;
                c = d;
                d = tmp;
            }
            if ((mask & (1 << 4)) != 0) {
                Object tmp = c;
                c = d;
                d = tmp;
            }
            if ((mask & (1 << 5)) != 0) {
                Object tmp = c;
                c = d;
                d = tmp;
            }
            if ((mask & (1 << 6)) != 0) {
                Object tmp = c;
                c = d;
                d = tmp;
            }
            if ((mask & (1 << 7)) != 0) {
                Object tmp = c;
                c = d;
                d = tmp;
            }
            if ((mask & (1 << 8)) != 0) {
                Object tmp = c;
                c = d;
                d = tmp;
            }
            if ((mask & (1 << 9)) != 0) {
                Object tmp = c;
                c = d;
                d = tmp;
            }
            if ((mask & (1 << 10)) != 0) {
                Object tmp = c;
                c = d;
                d = tmp;
            }
            if ((mask & (1 << 11)) != 0) {
                Object tmp = c;
                c = d;
                d = tmp;
            }
            if ((mask & (1 << 12)) != 0) {
                Object tmp = c;
                c = d;
                d = tmp;
            }
            if ((mask & (1 << 13)) != 0) {
                Object tmp = c;
                c = d;
                d = tmp;
            }
            if ((mask & (1 << 14)) != 0) {
                Object tmp = c;
                c = d;
                d = tmp;
            }
            if ((mask & (1 << 15)) != 0) {
                Object tmp = c;
                c = d;
                d = tmp;
            }
            if ((mask & (1 << 16)) != 0) {
                Object tmp = c;
                c = d;
                d = tmp;
            }
            if ((mask & (1 << 17)) != 0) {
                Object tmp = c;
                c = d;
                d = tmp;
            }
            if ((mask & (1 << 18)) != 0) {
                Object tmp = c;
                c = d;
                d = tmp;
            }
            if ((mask & (1 << 19)) != 0) {
                Object tmp = c;
                c = d;
                d = tmp;
            }
            if ((mask & (1 << 20)) != 0) {
                Object tmp = c;
                c = d;
                d = tmp;
            }
            if ((mask & (1 << 21)) != 0) {
                Object tmp = c;
                c = d;
                d = tmp;
            }
            if ((mask & (1 << 22)) != 0) {
                Object tmp = c;
                c = d;
                d = tmp;
            }
            if ((mask & (1 << 23)) != 0) {
                Object tmp = c;
                c = d;
                d = tmp;
            }
            if ((mask & (1 << 24)) != 0) {
                Object tmp = c;
                c = d;
                d = tmp;
            }
            if ((mask & (1 << 25)) != 0) {
                Object tmp = c;
                c = d;
                d = tmp;
            }
            if ((mask & (1 << 26)) != 0) {
                Object tmp = c;
                c = d;
                d = tmp;
            }
            if ((mask & (1 << 27)) != 0) {
                Object tmp = c;
                c = d;
                d = tmp;
            }
            if ((mask & (1 << 28)) != 0) {
                Object tmp = c;
                c = d;
                d = tmp;
            }
            if ((mask & (1 << 29)) != 0) {
                Object tmp = c;
                c = d;
                d = tmp;
            }
            if ((mask & (1 << 30)) != 0) {
                Object tmp = c;
                c = d;
                d = tmp;
            }
            if ((mask & (1 << 31)) != 0) {
                Object tmp = c;
                c = d;
                d = tmp;
            }
            sum += ((Node) c).value + ((Node) c).get();
        }
        return sum;
    }
    public static void main(String[] args) {
        Left left = new Left();
        Right right = new Right();
        int[] masks = {0, 1, 3, -1, 0x55555555, 0x12345678, Integer.MIN_VALUE};
        for (int mask : masks) {
            for (int count = 0; count < 5; count++) {
                int expected = 0;
                for (int i = 0; i < count; i++) {
                    boolean swapped = ((Integer.bitCount(mask) ^ i) & 1) != 0;
                    expected += swapped ? 34 : 22;
                }
                int actual = test(left, right, mask, count);
                if (actual != expected) {
                    throw new AssertionError(actual + " != " + expected);
                }
            }
        }
        System.out.println("Passed");
    }
}
