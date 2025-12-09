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

package compiler.jeandle.exception;

import jdk.test.lib.Asserts;

/*
 * @test
 * @requires os.arch=="amd64" | os.arch=="x86_64" | os.arch=="aarch64"
 * @library /test/lib
 * @run main/othervm -Xmx1G -Xcomp -XX:-TieredCompilation -XX:+UseJeandleCompiler
 *      -XX:CompileCommand=compileonly,compiler.jeandle.exception.TestCheckExceptionInJeandleRoutine::test*
 *      compiler.jeandle.exception.TestCheckExceptionInJeandleRoutine
 */

public class TestCheckExceptionInJeandleRoutine {
    public static void main(String[] args) {
        // Pre-initialize for class MemoryConsumer
        preInit();

        testFailOnNewInstance();
        testFailOnNewArray(-1);
        testFailOnNew2DArray(1, -1);
    }

    public static class MemoryConsumer {
        public static byte[] holder;
        MemoryConsumer(int size) {
            holder = new byte[size];
        }
    }

    public static void preInit() {
        new MemoryConsumer(10);
    }

    public static Object testFailOnNewInstance() {
        boolean throwExpectedException = false;
        Object obj = null;
        try {
            obj = new MemoryConsumer(Integer.MAX_VALUE);
        } catch (OutOfMemoryError e) {
            throwExpectedException = true;
        }
        Asserts.assertTrue(throwExpectedException);
        return obj; // Capture the return value to prevent optimization
    }

    public static Object testFailOnNewArray(int len) {
        boolean throwExpectedException = false;
        Object obj = null;
        try {
            obj = new int[len];
        } catch (NegativeArraySizeException e) {
            throwExpectedException = true;
        }
        Asserts.assertTrue(throwExpectedException);
        return obj; // Capture the return value to prevent optimization
    }

    public static Object testFailOnNew2DArray(int len1, int len2) {
        boolean throwExpectedException = false;
        Object obj = null;
        try {
            obj = new int[len1][len2];
        } catch (NegativeArraySizeException e) {
            throwExpectedException = true;
        }
        Asserts.assertTrue(throwExpectedException);
        return obj; // Capture the return value to prevent optimization
    }
}
