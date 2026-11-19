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
 * @summary Versioned checks preserve inline state, monitors, virtual objects, and full GC
 * @library /test/lib /
 * @build compiler.jeandle.checks.CheckLoopShape jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *      -Xbatch -XX:-TieredCompilation -XX:+UseJeandleCompiler -XX:CompileThreshold=100
 *      -XX:+JeandleDumpIR -XX:CompileCommand=compileonly,compiler.jeandle.checks.TestCheckState::test*
 *      -XX:CompileCommand=dontinline,compiler.jeandle.checks.TestCheckState::gc
 *      -XX:CompileCommand=inline,compiler.jeandle.checks.TestCheckState::inlineSum
 *      compiler.jeandle.checks.TestCheckState
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *      -Xbatch -XX:-TieredCompilation -XX:+UseJeandleCompiler -XX:CompileThreshold=100
 *      -XX:+JeandleDumpIR -XX:CompileCommand=compileonly,compiler.jeandle.checks.TestCheckState::test*
 *      -XX:CompileCommand=dontinline,compiler.jeandle.checks.TestCheckState::gc
 *      -XX:CompileCommand=inline,compiler.jeandle.checks.TestCheckState::inlineSum
 *      -XX:-JeandleDoPEA compiler.jeandle.checks.TestCheckState
 */
package compiler.jeandle.checks;

import java.lang.reflect.Method;
import jdk.test.lib.Asserts;
import jdk.test.whitebox.WhiteBox;

public class TestCheckState {
    static final WhiteBox WB = WhiteBox.getWhiteBox();
    static final Object LOCK = new Object();
    static boolean collect;
    static int collections;
    static int finalizations;

    static class Box {
        int count;
    }

    static int inlineSum(int[] a, int end, Box state, int[] totals) {
        int sum = a.length;
        for (int i = 0; i < end; i++) {
            state.count++;
            totals[0] += a[3 * i + 1];
            sum += totals[0];
        }
        return sum;
    }

    public static int testState(int[] a, int end) {
        Box state = new Box();
        int[] totals = {7, 9};
        synchronized (LOCK) {
            try {
                return inlineSum(a, end, state, totals);
            } catch (ArrayIndexOutOfBoundsException expected) {
                return state.count * 1000 + totals[0] + totals[1];
            } finally {
                finalizations++;
            }
        }
    }

    static void gc() {
        if (collect) {
            WB.fullGC();
            collections++;
        }
    }

    public static int testGC(int[] a, int end) {
        int sum = a.length;
        for (int i = 0; i < end; i++) {
            gc();
            sum += a[3 * i + 1];
        }
        return sum;
    }

    public static void main(String[] args) throws Exception {
        int[] a = new int[32];
        java.util.Arrays.fill(a, 3);
        for (int i = 0; i < 20000; i++) {
            Asserts.assertEquals(testState(a, 10), 267);
            Asserts.assertEquals(testGC(a, 10), 62);
        }
        Method state = TestCheckState.class.getDeclaredMethod("testState", int[].class, int.class);
        Method gc = TestCheckState.class.getDeclaredMethod("testGC", int[].class, int.class);
        Asserts.assertTrue(WB.isMethodCompiled(state));
        Asserts.assertTrue(WB.isMethodCompiled(gc));
        CheckLoopShape.assertVersioned(gc);
        CheckLoopShape.assertVersioned(state);
        int before = finalizations;
        Asserts.assertEquals(testState(a, 12), 12049, "virtual fields and array contents after deopt");
        Asserts.assertEquals(finalizations, before + 1);
        Thread probe = new Thread(() -> { synchronized (LOCK) { finalizations++; } });
        probe.start();
        probe.join(10000);
        Asserts.assertFalse(probe.isAlive(), "monitor released after exception");
        Asserts.assertEquals(finalizations, before + 2);
        collect = true;
        Asserts.assertEquals(testGC(a, 10), 62);
        Asserts.assertEquals(collections, 10, "full GC requests completed in compiled loop");
        Asserts.assertTrue(WB.isMethodCompiled(gc), "GC loop remains compiled after collection");
        System.out.println("CHECK_STATE_EXECUTED gc=" + collections);
    }
}
