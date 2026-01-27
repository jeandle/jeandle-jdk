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

/*
 * @test
 * @library /test/lib
 * @run main/othervm -XX:-TieredCompilation -Xcomp
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.Child::isFinal
 *      -XX:+UseJeandleCompiler compiler.jeandle.bytecodeTranslate.TestReceiverConstraint
 */

package compiler.jeandle.bytecodeTranslate;

import jdk.test.lib.Asserts;

public class TestReceiverConstraint {
    public static void main(String[] args) {
        Child child = new Child() {};
        Asserts.assertTrue(child.isFinal());
    }
}

interface Parent {
    default boolean isFinalFlagSet() {
        return true;
    }
}

interface Child extends Parent {
    default boolean isFinal() {
        return Parent.super.isFinalFlagSet();
    }
}
