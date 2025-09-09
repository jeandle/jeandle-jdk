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
 * @requires os.arch=="amd64" | os.arch=="x86_64"
 * @run main/othervm -XX:CompileCommand=compileonly,compiler.jeandle.exception.TestThrow::testThrow
 *      -Xcomp -XX:-TieredCompilation -XX:+UseJeandleCompiler compiler.jeandle.exception.TestThrow
 */

package compiler.jeandle.exception;

 public class TestThrow {
    public static void main(String[] args) throws RuntimeException {
        try {
            testThrow(true, new RuntimeException("Expected"));
        } catch (RuntimeException e) {
            System.out.println("Expected Error Occorred");
        }

        testThrow(false, new RuntimeException("Not Expected"));
    }

    static void testThrow(boolean to_throw, RuntimeException e) throws RuntimeException {
        if (to_throw)
            throw e;
    }
}
