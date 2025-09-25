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
 * @library /test/lib
 * @build jdk.test.lib.Asserts
 * @run main/othervm -XX:-TieredCompilation -Xcomp -Xbatch
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.TestTableSwitch::minPositiveSwitch
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.TestTableSwitch::zeroCrossSwitch
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.TestTableSwitch::largeRangeSwitch
 *      -XX:+UseJeandleCompiler compiler.jeandle.bytecodeTranslate.TestTableSwitch
 */

package compiler.jeandle.bytecodeTranslate;

import jdk.test.lib.Asserts;

public class TestTableSwitch {
    public static void main(String[] args) throws Exception {
        testBasicBoundaryScenarios();
        testLargeRangeSwitch();
    }

    public static int minPositiveSwitch(int num) {
        switch(num) {
            case 1: return 1;
            case 2: return 2;
            case 3: return 3;
            default: return -100;
        }
    }
    
    public static int zeroCrossSwitch(int num) {
        switch(num) {
            case -1: return -1;
            case 0: return 0;
            case 1: return 1;
            default: return -100;
        }
    }

    private static void testBasicBoundaryScenarios() {
        Asserts.assertEquals(minPositiveSwitch(0), -100);
        Asserts.assertEquals(minPositiveSwitch(1), 1);
        Asserts.assertEquals(minPositiveSwitch(2), 2);
        Asserts.assertEquals(minPositiveSwitch(3), 3);

        Asserts.assertEquals(zeroCrossSwitch(-2), -100);
        Asserts.assertEquals(zeroCrossSwitch(-1), -1);
        Asserts.assertEquals(zeroCrossSwitch(0), 0);
        Asserts.assertEquals(zeroCrossSwitch(1), 1);
        Asserts.assertEquals(zeroCrossSwitch(2), -100);
    }

    public static int largeRangeSwitch(int num) {
        switch(num) {
            case 1000: return 1000;
            case 1001: return 1001;
            case 1002: return 1002;
            default: return -100;
        }
    }

    private static void testLargeRangeSwitch() {
        Asserts.assertEquals(largeRangeSwitch(999), -100);
        Asserts.assertEquals(largeRangeSwitch(1000), 1000);
        Asserts.assertEquals(largeRangeSwitch(1001), 1001);
        Asserts.assertEquals(largeRangeSwitch(1002), 1002);
        Asserts.assertEquals(largeRangeSwitch(2001), -100);
    }
    
}
