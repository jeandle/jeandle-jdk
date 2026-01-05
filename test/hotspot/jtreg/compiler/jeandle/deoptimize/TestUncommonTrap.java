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
 * @test
 * @summary Test uncommon_trap for uninitialzed class and null pointer check
 * @requires os.arch=="amd64" | os.arch=="x86_64" | os.arch=="aarch64"
 * @library /test/lib
 * @run main/othervm -Xbatch -Xcomp -XX:-TieredCompilation -XX:+UseJeandleCompiler
 *      -XX:CompileCommand=compileonly,TestUncommonTrap::test_uncommon -XX:CompileCommand=compileonly,TestUncommonTrap::test_null_check_with_trap
 *      TestUncommonTrap
 */

import jdk.test.lib.Asserts;

public class TestUncommonTrap {
  public int val() { return 30; }
  public int f = 40;

  public static void main(String[] args) {
    Asserts.assertEquals(test_uncommon(5) , 15);
    Asserts.assertThrows(NullPointerException.class, () -> test_null_check_with_trap(null));
  }

  private static int test_null_check_with_trap(TestUncommonTrap obj) {
    return obj.f;
  }

  private static int test_uncommon(int i) {
    /* trigger uncommon_trap for uninitialzed class */
    return new UninitClass().val() + i;
  }

  static class UninitClass extends TestUncommonTrap {
    public int val() { return 10; }
  }
}
