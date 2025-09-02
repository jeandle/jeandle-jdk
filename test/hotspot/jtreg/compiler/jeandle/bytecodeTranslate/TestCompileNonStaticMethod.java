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
 * @test TestCompileNonStaticMethod.java
 * @run main/othervm -XX:-TieredCompilation -Xcomp
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.TestCall::callSuccessfully
 *      -XX:+UseJeandleCompiler compiler.jeandle.bytecodeTranslate.TestCompileNonStaticMethod
 * @run main/othervm -XX:-UseCompressedOops -XX:-TieredCompilation -Xcomp
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.TestCall::callSuccessfully
 *      -XX:+UseJeandleCompiler compiler.jeandle.bytecodeTranslate.TestCompileNonStaticMethod
 */

package compiler.jeandle.bytecodeTranslate;

public class TestCompileNonStaticMethod {
    public static void main(String[] args) throws Exception {
        if (!new TestCall().callSuccessfully(1, 2, 3)) {
            throw new RuntimeException("Exeption during test");
        }
    }
}

class TestCall {
    boolean callSuccessfully(int a, int b, int c) {
        return a == 1 && b == 2 && c == 3;
    }
}
