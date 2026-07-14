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
 */

/*
 * @test
 * @library /test/lib
 * @build jdk.test.lib.Asserts
 * @compile JsrRet.jasm TestJsrRet.java
 * @run main/othervm -XX:-TieredCompilation -Xcomp
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.jsr.JsrRet::*
 *      -XX:+UseJeandleCompiler compiler.jeandle.bytecodeTranslate.jsr.TestJsrRet
 */

package compiler.jeandle.bytecodeTranslate.jsr;

import jdk.test.lib.Asserts;

public class TestJsrRet {
    public static void main(String[] args) {
        Asserts.assertEquals(JsrRet.simple(5), 15);
        Asserts.assertEquals(JsrRet.twoCallSites(20, true), 31);
        Asserts.assertEquals(JsrRet.twoCallSites(20, false), 32);
        Asserts.assertEquals(JsrRet.nested(1), 111);
        Asserts.assertEquals(JsrRet.loopInSubroutine(0), 0);
        Asserts.assertEquals(JsrRet.loopInSubroutine(7), 7);
        Asserts.assertEquals(JsrRet.loopCallsSubroutine(0), 0);
        Asserts.assertEquals(JsrRet.loopCallsSubroutine(5), 10);
        Asserts.assertEquals(JsrRet.wideJsr(3), 23);
        Asserts.assertEquals(JsrRet.exceptionFinally(false), 11);
        Asserts.assertEquals(JsrRet.exceptionFinally(true), -1);
    }
}
