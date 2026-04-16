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

import java.util.ArrayList;
import java.util.HashMap;

import jdk.test.lib.Asserts;

/**
 * @test
 * @summary Test allocation of diverse object types: empty, single-field, JDK classes, nested
 * @library /test/lib
 * @run main/othervm -Xcomp -XX:-TieredCompilation
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.alloc.TestAllocObjectTypes::test*
 *      -XX:+UseJeandleCompiler compiler.jeandle.bytecodeTranslate.alloc.TestAllocObjectTypes
 */
public class TestAllocObjectTypes {

    // Minimal object (just header, no user fields)
    static class EmptyClass {}

    // Single-field objects of various sizes
    static class SingleByte { byte val = 1; }
    static class SingleInt { int val = 42; }
    static class SingleLong { long val = 123456789L; }
    static class SingleRef { Object val = null; }

    // Object containing another object
    static class Outer {
        Inner inner;
        int outerVal = 10;

        Outer() {
            this.inner = new Inner(20);
        }
    }

    static class Inner {
        int innerVal;
        Inner(int v) { this.innerVal = v; }
    }

    // Object with self-reference
    static class LinkedNode {
        int value;
        LinkedNode next;
        LinkedNode(int v) { this.value = v; }
    }

    public static void main(String[] args) {
        for (int i = 0; i < 1000; i++) {
            testEmptyObject();
            testPlainJavaObject();
            testSingleFieldObjects();
            testNestedObject();
            testLinkedList();
            testJDKCollections();
            testStringCreation();
        }
        System.out.println("TestAllocObjectTypes passed.");
    }

    static void testEmptyObject() {
        EmptyClass obj = new EmptyClass();
        Asserts.assertNotNull(obj);
        Asserts.assertTrue(obj instanceof EmptyClass);
    }

    static void testPlainJavaObject() {
        Object obj = new Object();
        Asserts.assertNotNull(obj);
        Asserts.assertEquals(obj.getClass(), Object.class);
    }

    static void testSingleFieldObjects() {
        Asserts.assertEquals(new SingleByte().val, (byte) 1);
        Asserts.assertEquals(new SingleInt().val, 42);
        Asserts.assertEquals(new SingleLong().val, 123456789L);
        Asserts.assertNull(new SingleRef().val);
    }

    static void testNestedObject() {
        Outer obj = new Outer();
        Asserts.assertEquals(obj.outerVal, 10);
        Asserts.assertNotNull(obj.inner);
        Asserts.assertEquals(obj.inner.innerVal, 20);
    }

    static void testLinkedList() {
        // Build a short linked list
        LinkedNode head = new LinkedNode(1);
        head.next = new LinkedNode(2);
        head.next.next = new LinkedNode(3);
        head.next.next.next = new LinkedNode(4);

        // Verify traversal
        int sum = 0;
        LinkedNode cur = head;
        int count = 0;
        while (cur != null) {
            sum += cur.value;
            cur = cur.next;
            count++;
        }
        Asserts.assertEquals(count, 4, "linked list length");
        Asserts.assertEquals(sum, 10, "linked list sum");
    }

    static void testJDKCollections() {
        ArrayList<Integer> list = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            list.add(i);
        }
        Asserts.assertEquals(list.size(), 100);
        Asserts.assertEquals((int) list.get(50), 50);

        HashMap<String, Integer> map = new HashMap<>();
        map.put("key", 42);
        Asserts.assertEquals((int) map.get("key"), 42);
    }

    static void testStringCreation() {
        // String concatenation creates new String objects
        String s = "";
        for (int i = 0; i < 50; i++) {
            s = new String("iter" + i);
        }
        Asserts.assertEquals(s, "iter49");
    }
}
