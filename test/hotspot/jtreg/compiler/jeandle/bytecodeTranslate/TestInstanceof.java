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
 * @test TestInstanceof.java
 * @summary Test instanceof Java op
 *  issue: https://github.com/jeandle/jeandle-jdk/issues/6
 * @library /test/lib /
 * @build compiler.jeandle.fileCheck.FileCheck
 * @run main/othervm -XX:-TieredCompilation -Xcomp
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.TestInstanceof::test*
 *      -XX:+UseJeandleCompiler -XX:+JeandleDumpIR compiler.jeandle.bytecodeTranslate.TestInstanceof
 */

package compiler.jeandle.bytecodeTranslate;

import jdk.test.lib.Asserts;

import compiler.jeandle.fileCheck.FileCheck;

public class TestInstanceof {
    static class Animal {}

    interface Barkable {
        void bark();
    }

    static class Dog extends Animal implements Barkable {
        public void bark() { /* Do nothing */ }
    }

    static class NotSuperClass {}
    interface NotSuperInterface {}

    private static boolean testSubClass(Object myDog) { return (myDog instanceof Animal); }
    private static boolean testSubInterface(Object myDog) { return (myDog instanceof Barkable); }

    private static boolean testNotSubClass(Object myDog) { return (myDog instanceof NotSuperClass); }
    private static boolean testNotSubInterface(Object myDog) { return (myDog instanceof NotSuperInterface); }

    public static void main(String[] args) throws Exception {
        // Pre-load Classes
        Class.forName("compiler.jeandle.bytecodeTranslate.TestInstanceof$NotSuperClass");
        Class.forName("compiler.jeandle.bytecodeTranslate.TestInstanceof$NotSuperInterface");

        Animal myDog = new Dog();

        // test is_instanceof
        Asserts.assertTrue(testSubClass(myDog));
        Asserts.assertTrue(testSubInterface(myDog));

        // test not_instanceof
        Asserts.assertFalse(testNotSubClass(myDog));
        Asserts.assertFalse(testNotSubInterface(myDog));

        Asserts.assertFalse(testSubClass(null));
        Asserts.assertFalse(testSubInterface(null));

        // check dead global variable elimination
        String currentDir = System.getProperty("user.dir");
        {
            // before optimization
            FileCheck fileCheck = new FileCheck(currentDir,
                                                TestInstanceof.class.getDeclaredMethod("testSubClass", Object.class),
                                                false);
            fileCheck.check("@KlassArray.base_offset_in_bytes = private constant");
            fileCheck.check("@KlassArray.length_offset_in_bytes = private constant");
            fileCheck.check("@arrayOopDesc.length_offset_in_bytes = private constant");
            fileCheck.check("@Klass.secondary_super_cache_offset = private constant");
            fileCheck.check("@Klass.secondary_supers_offset = private constant");
            fileCheck.check("@Klass.super_check_offset_offset = private constant");
            fileCheck.check("@oopDesc.klass_offset_in_bytes = private constant");

            // after optimization
            FileCheck fileCheckOpt = new FileCheck(currentDir,
                                                   TestInstanceof.class.getDeclaredMethod("testSubClass", Object.class),
                                                   true);
            fileCheckOpt.checkNot("@KlassArray.base_offset_in_bytes");
            fileCheckOpt.checkNot("@KlassArray.length_offset_in_bytes");
            fileCheckOpt.checkNot("@arrayOopDesc.length_offset_in_bytes");
            fileCheckOpt.checkNot("@Klass.secondary_super_cache_offset");
            fileCheckOpt.checkNot("@Klass.secondary_supers_offset");
            fileCheckOpt.checkNot("@Klass.super_check_offset_offset");
            fileCheckOpt.checkNot("@oopDesc.klass_offset_in_bytes");
        }
    }
}
