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
 * @summary Fix incorrect inst offset for duplicated statepoints.
 *  issue: https://github.com/jeandle/jeandle-jdk/issues/64
 * @library /test/lib
 * @run main/othervm -Xbatch -Xcomp -XX:-TieredCompilation -XX:+UseJeandleCompiler -XX:CompileCommand=compileonly,TestDAbs::main TestDAbs
 */

import java.lang.Math;

import jdk.test.lib.Asserts;

public class TestDAbs {
    public static void main(String[] args) {
        double r=0.0;

        for (int i=0;i<10;i++ ) {
            double v = (double)i;
            r += Math.abs(v);
        }

        Asserts.assertEquals(r, 45.0);
    }
}
