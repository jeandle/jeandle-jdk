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
 * @summary Support creating new array of reference
 * issue: https://github.com/jeandle/jeandle-jdk/issues/21
 * @library /test/lib
 * @run main/othervm -Xcomp -XX:-TieredCompilation -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.array.TestANewArray::test
 * -XX:+UseJeandleCompiler -XX:+UseSerialGC compiler.jeandle.bytecodeTranslate.array.TestANewArray
 */

package compiler.jeandle.bytecodeTranslate.array;

import jdk.test.lib.Asserts;

public class TestANewArray {
  public static void main(String[] args) {
    System.out.println(Integer.parseInt("1")); // Make sure Integer is loaded.
    System.out.println(Foo.class.getName()); // Make sure Foo is loaded.
    test();
  }

  static class Foo {
  }

  static void test() {
    String[] stringArray = new String[10];
    stringArray[0] = "123";
    Asserts.assertEquals(stringArray.length, 10);
    Asserts.assertEquals(stringArray[0], "123");

    Integer[] integerArray = new Integer[3];
    integerArray[1] = 1;
    Asserts.assertEquals(integerArray.length, 3);
    Asserts.assertEquals(integerArray[1], 1);

    Asserts.assertEquals(new Foo[10].length, 10);

    Asserts.assertEquals(new Object[5].length, 5);
    Asserts.assertEquals(new Object[0].length, 0);
  }

}
