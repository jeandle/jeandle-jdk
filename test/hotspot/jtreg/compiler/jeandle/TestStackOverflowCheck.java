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
 */

/*
 * @test
 * @summary Verify Jeandle compiled Java methods perform stack overflow checks and raise StackOverflowError.
 * @run main/othervm -Xss256k -XX:+UseJeandleCompiler -XX:-TieredCompilation -Xcomp -XX:-BackgroundCompilation
 *     -XX:CompileCommand=compileonly,compiler.jeandle.TestStackOverflowCheck::recurse compiler.jeandle.TestStackOverflowCheck
 */

package compiler.jeandle;

public class TestStackOverflowCheck {
  // Volatile sink to keep the recursion side-effectful and prevent dead-code elimination.
  private static volatile int sink;

  private static void recurse(int depth) {
    sink = depth;
    recurse(depth + 1);
  }

  public static void main(String[] args) {
    boolean caught = false;
    try {
      recurse(0);
      throw new RuntimeException("Expected StackOverflowError was not thrown");
    } catch (StackOverflowError expected) {
      // Expected: reaching the guard should throw and not crash the VM.
      caught = true;
    }
    if (!caught) {
      throw new RuntimeException("StackOverflowError branch was not executed");
    }
  }
}
