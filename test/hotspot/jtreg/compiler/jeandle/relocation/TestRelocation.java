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

/*
 * @test
 * @requires os.arch=="x86_64"
 * @library /test/lib
 * @build jdk.test.lib.Asserts
 * @run main/othervm -XX:-TieredCompilation -Xcomp
 *      -XX:CompileCommand=compileonly,compiler.jeandle.relocation.TestRelocation::test*
 *      -XX:+UseJeandleCompiler compiler.jeandle.relocation.TestRelocation
 */

package compiler.jeandle.relocation;

import jdk.test.lib.Asserts;

public class TestRelocation {
    public static void main(String[] args) {
        Asserts.assertEquals(20.0f, test1(1.0f));
        Asserts.assertEquals(24.0f, test2(1.0f));
        Asserts.assertEquals(29.0f, test3(1.0f));
        Asserts.assertEquals(32.0f, test4(1.0f));
        Asserts.assertEquals(37.0f, test5(1.0f));
        Asserts.assertEquals(47.0f, test6(1.0f));
    }
    private static float callee1(float a) {
      return a += 2.0f;
    }

    private static float callee2(float a) {
      return a += 3.0f;
    }

    private static float test1(float n) {
        n += callee1(n);
        n += callee2(n);
        n += 4.0f;
        n += 5.0f;
        return n;
    }
    private static float test2(float n) {
        n += callee1(n);
        n += 4.0f;
        n += callee2(n);
        n += 5.0f;
        return n;
    }
    private static float test3(float n) {
        n += callee1(n);
        n += 4.0f;
        n += 5.0f;
        n += callee2(n);
        return n;
    }
    private static float test4(float n) {
        n += 4.0f;
        n += callee1(n);
        n += callee2(n);
        n += 5.0f;
        return n;
    }
    private static float test5(float n) {
        n += 4.0f;
        n += callee1(n);
        n += 5.0f;
        n += callee2(n);
        return n;
    }
    private static float test6(float n) {
        n += 4.0f;
        n += 5.0f;
        n += callee1(n);
        n += callee2(n);
        return n;
    }
}
