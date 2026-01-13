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

package compiler.jeandle.bytecodeTranslate.calls;

/**
 * @test
 * @summary https://github.com/jeandle/jeandle-jdk/issues/290
 * @run main/othervm -Xcomp -XX:-TieredCompilation -Xbatch
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.calls.TestCallTarget::getString
 *      -XX:+UseJeandleCompiler compiler.jeandle.bytecodeTranslate.calls.TestCallTarget
 */

public class TestCallTarget {
    public static String getString(Object obj) {
        return getString((String)obj);
    }
    public static String getString(String s) {
        return s;
    }
    public static void main(String[] args) throws Exception {
        Object o = new String("hello");
        if (!getString(o).equals("hello")) {
            throw new RuntimeException("test failed");
        }
    }
}
