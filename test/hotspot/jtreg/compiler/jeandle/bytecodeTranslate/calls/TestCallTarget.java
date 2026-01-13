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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;

/**
 * @test
 * @summary https://github.com/jeandle/jeandle-jdk/issues/290 https://github.com/jeandle/jeandle-jdk/issues/285
 * @run main/othervm -Xcomp -XX:-TieredCompilation -Xbatch
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.calls.TestCallTarget::getString
 *      -XX:CompileCommand=compileonly,java.math.BigDecimal::add
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
        // Test code from https://github.com/jeandle/jeandle-jdk/issues/290
        Object o = new String("hello");
        if (!getString(o).equals("hello")) {
            throw new RuntimeException("test failed");
        }

        // Test code from https://github.com/jeandle/jeandle-jdk/issues/285
        // Load classes first
        MathContext mc = new MathContext(10);
        BigDecimal b = new BigDecimal(new BigInteger("7812404666936930160"), 11);
        BigDecimal b2 = new BigDecimal(new BigInteger("7812404666936930160"), 11);
        b.add(b2, mc);
        test();
    }

    static void test() {
        MathContext mc = new MathContext(10);
        BigDecimal b = new BigDecimal(new BigInteger("7812404666936930160"), 11);
        BigDecimal b2 = new BigDecimal(new BigInteger("7812404666936930160"), 11);
        b.add(b2, mc);
    }
}
