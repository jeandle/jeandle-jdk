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

package compiler.jeandle.bytecodeTranslate.alloc;

import jdk.test.lib.Asserts;

/**
 * @test
 * @summary Test allocation correctness with class inheritance hierarchies
 * @library /test/lib
 * @run main/othervm -Xcomp -XX:-TieredCompilation
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.alloc.TestAllocInheritance::test*
 *      -XX:+UseJeandleCompiler compiler.jeandle.bytecodeTranslate.alloc.TestAllocInheritance
 */
public class TestAllocInheritance {

    static class Base {
        int baseVal = 10;
        long baseLong = 20L;
    }

    static class Child extends Base {
        int childVal = 30;
        double childDouble = 4.0;
    }

    static class GrandChild extends Child {
        String name = "gc";
        int gcVal = 50;
    }

    // Deep inheritance chain (5 levels)
    static class Level1 { int v1 = 1; }
    static class Level2 extends Level1 { int v2 = 2; }
    static class Level3 extends Level2 { int v3 = 3; }
    static class Level4 extends Level3 { int v4 = 4; }
    static class Level5 extends Level4 { int v5 = 5; }

    // Constructor chaining with super()
    static class Parent {
        int x;
        Parent(int x) { this.x = x; }
    }

    static class Sub extends Parent {
        int y;
        Sub(int x, int y) {
            super(x);
            this.y = y;
        }
    }

    public static void main(String[] args) {
        for (int i = 0; i < 1000; i++) {
            testSimpleInheritance();
            testThreeLevelInheritance();
            testDeepInheritance();
            testConstructorChaining();
        }
        System.out.println("TestAllocInheritance passed.");
    }

    static void testSimpleInheritance() {
        Child obj = new Child();
        Asserts.assertEquals(obj.baseVal, 10, "base field");
        Asserts.assertEquals(obj.baseLong, 20L, "base long field");
        Asserts.assertEquals(obj.childVal, 30, "child field");
        Asserts.assertEquals(obj.childDouble, 4.0, "child double field");
        Asserts.assertTrue(obj instanceof Base, "instanceof Base");
    }

    static void testThreeLevelInheritance() {
        GrandChild obj = new GrandChild();
        Asserts.assertEquals(obj.baseVal, 10, "base field");
        Asserts.assertEquals(obj.baseLong, 20L, "base long field");
        Asserts.assertEquals(obj.childVal, 30, "child field");
        Asserts.assertEquals(obj.childDouble, 4.0, "child double field");
        Asserts.assertEquals(obj.name, "gc", "grandchild string");
        Asserts.assertEquals(obj.gcVal, 50, "grandchild field");
    }

    static void testDeepInheritance() {
        Level5 obj = new Level5();
        Asserts.assertEquals(obj.v1, 1, "level1 field");
        Asserts.assertEquals(obj.v2, 2, "level2 field");
        Asserts.assertEquals(obj.v3, 3, "level3 field");
        Asserts.assertEquals(obj.v4, 4, "level4 field");
        Asserts.assertEquals(obj.v5, 5, "level5 field");
    }

    static void testConstructorChaining() {
        Sub obj = new Sub(100, 200);
        Asserts.assertEquals(obj.x, 100, "parent field via super()");
        Asserts.assertEquals(obj.y, 200, "child field");
    }
}
