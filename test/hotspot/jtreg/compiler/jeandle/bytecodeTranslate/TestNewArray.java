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

/**
 * @test
 * @summary Support newarray
 * issue: https://github.com/jeandle/jeandle-jdk/issues/11
 * @library /test/lib
 * @run main/othervm -Xcomp -XX:-TieredCompilation -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.TestNewArray::newArray
 * -XX:+UseJeandleCompiler compiler.jeandle.bytecodeTranslate.TestNewArray
 */

package compiler.jeandle.bytecodeTranslate;

import jdk.test.lib.Asserts;

public class TestNewArray {
    public static void main(String[] args) {
        newArray();
    }

    public static void newArray() {
        Asserts.assertEQ(new int[10].length, 10);
        Asserts.assertEQ(new double[10].length, 10);
        Asserts.assertEQ(new float[10].length, 10);
        Asserts.assertEQ(new long[10].length, 10);
        Asserts.assertEQ(new short[10].length, 10);
        Asserts.assertEQ(new byte[10].length, 10);
        Asserts.assertEQ(new char[10].length, 10);
        Asserts.assertEQ(new boolean[10].length, 10);
    }
}
