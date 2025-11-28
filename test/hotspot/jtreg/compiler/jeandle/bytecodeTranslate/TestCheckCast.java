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
 * @library /test/lib
 * @build jdk.test.lib.Asserts
 * @run main/othervm -XX:-TieredCompilation -Xcomp -Xbatch
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.TestCheckCast::checkCastToDouble
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.TestCheckCast::checkCastToInteger
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.TestCheckCast::checkCastToParentClass
 *      -XX:+UseJeandleCompiler compiler.jeandle.bytecodeTranslate.TestCheckCast
 */

package compiler.jeandle.bytecodeTranslate;

import jdk.test.lib.Asserts;

public class TestCheckCast {

    public static Double checkCastToDouble(Object obj, boolean shouldFail) {
       Double d = null;
       boolean exceptionThrowed = false;
       try {
           d = (Double)obj;
       } catch (ClassCastException e) {
           exceptionThrowed = true;
       }
       Asserts.assertEquals(exceptionThrowed, shouldFail);
       return d;
    }

    public static Integer checkCastToInteger(Object obj, boolean shouldFail) {
        Integer i = null;
        boolean exceptionThrowed = false;
        try {
            i = (Integer)obj;
        } catch (ClassCastException e) {
            exceptionThrowed = true;
        }
        Asserts.assertEquals(exceptionThrowed, shouldFail);
        return i;
    }

    public static TestCheckCast checkCastToParentClass(Object obj, boolean shouldFail) {
        TestCheckCast t = null;
        boolean exceptionThrowed = false;
        try {
            t = (TestCheckCast)obj;
        } catch (ClassCastException e) {
            exceptionThrowed = true;
        }
        Asserts.assertEquals(exceptionThrowed, shouldFail);
        return t;
    }

    public static class SubClass extends TestCheckCast {}

    private static void testBasicCastScenarios() {
        Double validDouble = Double.valueOf(3.6);
        Asserts.assertEquals(checkCastToDouble(validDouble, false), 3.6);

        Integer validInteger = Integer.valueOf(2);
        Asserts.assertEquals(checkCastToInteger(validInteger, false), 2);

        SubClass subClass = new SubClass();
        Asserts.assertEquals(checkCastToParentClass(subClass, false), subClass);

        SubClass nullObj = null;
        Asserts.assertEquals(checkCastToParentClass(nullObj, false), null);
    }

    private static void testFailingCastScenarios() {
        Object invalidObj = "not a Double";
        checkCastToDouble(invalidObj, true);

        checkCastToInteger(invalidObj, true);

        checkCastToParentClass(invalidObj, true);
    }

    public static void main(String[] args) {
        testBasicCastScenarios();
        testFailingCastScenarios();
    }
}
