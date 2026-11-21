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
 */

/*
 * @test
 * @summary Cold Java checks expose widenable branches with distinct predicate feedback
 * @library /test/lib /
 * @build compiler.jeandle.fileCheck.FileCheck
 * @run main/othervm -Xbatch -XX:CompileThreshold=100 -XX:-TieredCompilation -XX:+UseJeandleCompiler
 *      -XX:+JeandleDumpIR -XX:CompileCommand=compileonly,compiler.jeandle.checks.TestCheckGuards::access
 *      compiler.jeandle.checks.TestCheckGuards widen
 * @run main/othervm -Xbatch -XX:CompileThreshold=100 -XX:-TieredCompilation -XX:+UseJeandleCompiler
 *      -XX:-ProfileTraps -XX:+JeandleDumpIR
 *      -XX:CompileCommand=compileonly,compiler.jeandle.checks.TestCheckGuards::access
 *      compiler.jeandle.checks.TestCheckGuards plain
 */
package compiler.jeandle.checks;

import compiler.jeandle.fileCheck.FileCheck;
import jdk.test.lib.Asserts;

public class TestCheckGuards {
    private static int effects;

    public static int access(int[] array, int index) {
        effects++;
        return array[index];
    }

    public static void main(String[] args) throws Exception {
        int[] array = { 7, 11 };
        for (int i = 0; i < 20000; i++) {
            Asserts.assertEquals(access(array, 1), 11);
        }
        effects = 0;
        Asserts.assertEquals(access(array, 1), 11);
        FileCheck input = new FileCheck(System.getProperty("user.dir"),
                TestCheckGuards.class.getDeclaredMethod("access", int[].class, int.class), false);
        if (args[0].equals("widen")) {
            input.checkPattern("call i1 @llvm.experimental.widenable.condition");
            input.checkPattern("check.trap_request.*select i1");
        } else {
            input.checkNotPattern("call i1 @llvm.experimental.widenable.condition");
        }
        try {
            access(null, -1);
            throw new AssertionError("missing NPE");
        } catch (NullPointerException expected) {
            Asserts.assertEquals(effects, 2);
        }
        try {
            access(new int[1], -1);
            throw new AssertionError("missing bounds exception");
        } catch (ArrayIndexOutOfBoundsException expected) {
            Asserts.assertEquals(effects, 3);
        }
        System.out.println("CHECK_GUARDS_EXECUTED " + args[0]);
    }
}
