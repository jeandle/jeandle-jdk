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
 * @compile MultiANewArray1.jasm
 * @run main/othervm -Xcomp -XX:-TieredCompilation -XX:+UseSerialGC
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.array.TestMultiANewArray::multiANewArray*
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.array.MultiANewArray1::get
 *      -XX:+UseJeandleCompiler compiler.jeandle.bytecodeTranslate.array.TestMultiANewArray
 */

package compiler.jeandle.bytecodeTranslate.array;

import jdk.test.lib.Asserts;

public class TestMultiANewArray {
  public static void main(String[] args) {
    Object obj = new Object();

    try {
      Object[] zeroArray = (Object[]) multiANewArray1(0);
      Asserts.assertTrue(zeroArray.length == 0);
      Object[] array1 = (Object[]) multiANewArray1(8);
      Asserts.assertTrue(array1.length == 8);
      array1[7] = obj;
      Asserts.assertTrue(array1[7] == obj);
    } catch (Throwable t) {
      Asserts.fail("multiANewArray1 failed", t);
    }

    try {
      Object[][] array2 = (Object[][]) multiANewArray2(3, 4);
      Asserts.assertTrue(array2.length == 3);
      Asserts.assertTrue(array2[0].length == 4);
      array2[1][2] = obj;
      Asserts.assertTrue(array2[1][2] == obj);
    } catch (Throwable t) {
      Asserts.fail("multiANewArray2 failed", t);
    }

    try {
      Object[][][] array3 = (Object[][][]) multiANewArray3(3, 4, 5);
      Asserts.assertTrue(array3.length == 3);
      Asserts.assertTrue(array3[0].length == 4);
      Asserts.assertTrue(array3[0][0].length == 5);
      array3[1][2][3] = obj;
      Asserts.assertTrue(array3[1][2][3] == obj);
    } catch (Throwable t) {
      Asserts.fail("multiANewArray3 failed", t);
    }

    try {
      Object[][][][] array4 = (Object[][][][]) multiANewArray4(3, 4, 5, 6);
      Asserts.assertTrue(array4.length == 3);
      Asserts.assertTrue(array4[0].length == 4);
      Asserts.assertTrue(array4[0][0].length == 5);
      Asserts.assertTrue(array4[0][0][0].length == 6);
      array4[1][2][3][4] = obj;
      Asserts.assertTrue(array4[1][2][3][4] == obj);
    } catch (Throwable t) {
      Asserts.fail("multiANewArray4 failed", t);
    }

    try {
      Object[][][][][] array5 = (Object[][][][][]) multiANewArray5(3, 4, 5, 6, 7);
      Asserts.assertTrue(array5.length == 3);
      Asserts.assertTrue(array5[0].length == 4);
      Asserts.assertTrue(array5[0][0].length == 5);
      Asserts.assertTrue(array5[0][0][0].length == 6);
      Asserts.assertTrue(array5[0][0][0][0].length == 7);
      array5[1][2][3][4][5] = obj;
      Asserts.assertTrue(array5[1][2][3][4][5] == obj);
    } catch (Throwable t) {
      Asserts.fail("multiANewArray5 failed", t);
    }

    try {
      Object[][][][][][] array6 = (Object[][][][][][]) multiANewArray6(3, 4, 5, 6, 7, 8);
      Asserts.assertTrue(array6.length == 3);
      Asserts.assertTrue(array6[0].length == 4);
      Asserts.assertTrue(array6[0][0].length == 5);
      Asserts.assertTrue(array6[0][0][0].length == 6);
      Asserts.assertTrue(array6[0][0][0][0].length == 7);
      Asserts.assertTrue(array6[0][0][0][0][0].length == 8);
      array6[1][2][3][4][5][6] = obj;
      Asserts.assertTrue(array6[1][2][3][4][5][6] == obj);
    } catch (Throwable t) {
      Asserts.fail("multiANewArray6 failed", t);
    }

    try {
      Object[][][][][][][] array7 = (Object[][][][][][][]) multiANewArray7(3, 4, 5, 6, 7, 8, 9);
      Asserts.assertTrue(array7.length == 3);
      Asserts.assertTrue(array7[0].length == 4);
      Asserts.assertTrue(array7[0][0].length == 5);
      Asserts.assertTrue(array7[0][0][0].length == 6);
      Asserts.assertTrue(array7[0][0][0][0].length == 7);
      Asserts.assertTrue(array7[0][0][0][0][0].length == 8);
      Asserts.assertTrue(array7[0][0][0][0][0][0].length == 9);
      array7[1][2][3][4][5][6][7] = obj;
      Asserts.assertTrue(array7[1][2][3][4][5][6][7] == obj);
    } catch (Throwable t) {
      Asserts.fail("multiANewArray7 failed", t);
    }
  }

  static Object multiANewArray1(int dim1) {
    return MultiANewArray1.get(dim1);
  }

  static Object multiANewArray2(int dim1, int dim2) {
    return new Object[dim1][dim2];
  }

  static Object multiANewArray3(int dim1, int dim2, int dim3) {
    return new Object[dim1][dim2][dim3];
  }

  static Object multiANewArray4(int dim1, int dim2, int dim3, int dim4) {
    return new Object[dim1][dim2][dim3][dim4];
  }

  static Object multiANewArray5(int dim1, int dim2, int dim3, int dim4, int dim5) {
    return new Object[dim1][dim2][dim3][dim4][dim5];
  }

  static Object multiANewArray6(int dim1, int dim2, int dim3, int dim4, int dim5, int dim6) {
    return new Object[dim1][dim2][dim3][dim4][dim5][dim6];
  }

  static Object multiANewArray7(int dim1, int dim2, int dim3, int dim4, int dim5, int dim6, int dim7) {
    return new Object[dim1][dim2][dim3][dim4][dim5][dim6][dim7];
  }
}
