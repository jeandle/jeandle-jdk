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
 * @summary Non-null receivers and immutable array lengths survive calls independently of PEA
 * @library /test/lib /
 * @build compiler.jeandle.fileCheck.FileCheck
 * @run main/othervm -Xcomp -Xbatch -XX:-TieredCompilation -XX:+UseJeandleCompiler
 *      -XX:-JeandleDoPEA -XX:+JeandleDumpIR
 *      -XX:CompileCommand=compileonly,compiler.jeandle.checks.TestCheckFacts::receiver
 *      -XX:CompileCommand=compileonly,compiler.jeandle.checks.TestCheckFacts::twoLengths
 *      -XX:CompileCommand=compileonly,compiler.jeandle.checks.TestCheckFacts::allocation
 *      -XX:CompileCommand=dontinline,compiler.jeandle.checks.TestCheckFacts::touch
 *      compiler.jeandle.checks.TestCheckFacts
 */

package compiler.jeandle.checks;

import compiler.jeandle.fileCheck.FileCheck;
import jdk.test.lib.Asserts;

public class TestCheckFacts {
    private static volatile int calls;
    private int value = 17;

    public int receiver() {
        return this == null ? -1 : value;
    }

    private static void touch(int[] array) {
        array[0]++;
        calls++;
    }

    public static int twoLengths(int[] array) {
        int first = array.length;
        touch(array);
        return first + array.length;
    }

    public static int allocation(int length) {
        int[] array = new int[length];
        touch(array);
        return array == null ? -1 : array.length;
    }

    public static void main(String[] args) throws Exception {
        TestCheckFacts object = new TestCheckFacts();
        int[] array = new int[7];
        for (int i = 0; i < 20; i++) {
            Asserts.assertEquals(object.receiver(), 17);
            Asserts.assertEquals(twoLengths(array), 14);
        }
        Asserts.assertEquals(calls, 20);
        Asserts.assertEquals(array[0], 20);
        new FileCheck(System.getProperty("user.dir"),
                TestCheckFacts.class.getDeclaredMethod("receiver"), true)
                .checkNotPattern("icmp (eq|ne) ptr addrspace\\(1\\).*null");
        FileCheck input = new FileCheck(System.getProperty("user.dir"),
                TestCheckFacts.class.getDeclaredMethod("twoLengths", int[].class), false);
        input.checkPattern("call hotspotcc.*@jeandle.arraylength");
        input.checkPattern("call hotspotcc.*@jeandle.arraylength");
        new FileCheck(System.getProperty("user.dir"),
                TestCheckFacts.class.getDeclaredMethod("twoLengths", int[].class), true)
                .checkPattern("shl .*i32 %length");
        try {
            twoLengths(null);
            throw new AssertionError("missing null check");
        } catch (NullPointerException expected) {
            Asserts.assertEquals(calls, 20);
        }
        Asserts.assertEquals(allocation(9), 9);
        new FileCheck(System.getProperty("user.dir"),
                TestCheckFacts.class.getDeclaredMethod("allocation", int.class), false)
                .checkPattern("invoke hotspotcc noundef nonnull .*ptr addrspace\\(1\\)");
        new FileCheck(System.getProperty("user.dir"),
                TestCheckFacts.class.getDeclaredMethod("allocation", int.class), true)
                .checkNotPattern("%length[^=]*= load atomic i32");
        System.out.println("CHECK_FACTS_EXECUTED");
    }
}
