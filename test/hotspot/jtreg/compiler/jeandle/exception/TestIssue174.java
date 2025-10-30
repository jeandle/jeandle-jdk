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
 * @summary https://github.com/jeandle/jeandle-jdk/issues/174
 * @library /test/lib /
 * @run main/othervm -XX:CompileCommand=compileonly,compiler.jeandle.exception.TestIssue174::addCounter
 *      -Xcomp -XX:-TieredCompilation -XX:+UseJeandleCompiler compiler.jeandle.exception.TestIssue174
 */

package compiler.jeandle.exception;

public class TestIssue174 {
    static int x;

    public static void main(String[] args) throws Exception {
        Class.forName("java.lang.System");
        addCounter(0);
    }

    static void addOne(int threadID) {
        x++;
    }

    static void addCounter(int ID) {
        for (int j = 0; j < 100000; j++) {
            if (ID >= 3 || ID < 0) {
                System.out.println(ID);
            }
            addOne(ID);
        }
    }
}
