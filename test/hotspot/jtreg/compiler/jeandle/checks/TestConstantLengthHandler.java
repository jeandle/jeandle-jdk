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
 * @summary Version constant-length checks with an ordinary exception handler
 * @library /test/lib /
 * @build compiler.jeandle.checks.CheckLoopShape jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *      -XX:+OmitStackTraceInFastThrow -Xbatch -XX:-TieredCompilation -XX:+UseJeandleCompiler
 *      -XX:CompileThreshold=100 -XX:+JeandleDumpIR
 *      -XX:CompileCommand=compileonly,compiler.jeandle.checks.TestConstantLengthHandler::test
 *      compiler.jeandle.checks.TestConstantLengthHandler
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *      -XX:+OmitStackTraceInFastThrow -Xbatch -XX:-TieredCompilation -XX:+UseJeandleCompiler
 *      -XX:CompileThreshold=100 -XX:+JeandleDumpIR -XX:-JeandleDoPEA
 *      -XX:CompileCommand=compileonly,compiler.jeandle.checks.TestConstantLengthHandler::test
 *      compiler.jeandle.checks.TestConstantLengthHandler
 */
package compiler.jeandle.checks;

import java.lang.reflect.Method;
import jdk.test.lib.Asserts;
import jdk.test.whitebox.WhiteBox;

public class TestConstantLengthHandler {
    private static final WhiteBox WB = WhiteBox.getWhiteBox();
    private static volatile int[] escaped;
    private static int effects;
    private static int handlers;

    private static void touch(int[] a) {
        a[0] = 7;
    }

    public static int test(int end, int offset, int scale) {
        int[] a = new int[1024];
        escaped = a;
        touch(a);
        int sum = 0;
        try {
            for (int i = 0; i < end; i++) {
                effects++;
                sum += a[offset + i * scale];
            }
        } catch (ArrayIndexOutOfBoundsException expected) {
            handlers++;
            sum ^= 999;
        }
        return sum;
    }

    private static int reference(int end, int offset, int scale) {
        int[] a = new int[1024];
        touch(a);
        int sum = 0;
        try {
            for (int i = 0; i < end; i++) {
                effects++;
                sum += a[offset + i * scale];
            }
        } catch (ArrayIndexOutOfBoundsException expected) {
            handlers++;
            sum ^= 999;
        }
        return sum;
    }

    private static void check(Method method, int end, int offset, int scale) {
        effects = 0;
        handlers = 0;
        int expected = reference(end, offset, scale);
        int expectedEffects = effects;
        int expectedHandlers = handlers;
        Asserts.assertTrue(WB.isMethodCompiled(method), "compiled before semantic probe");
        effects = 0;
        handlers = 0;
        Asserts.assertEquals(test(end, offset, scale), expected, "partial sum");
        Asserts.assertEquals(effects, expectedEffects, "effects before failure");
        Asserts.assertEquals(handlers, expectedHandlers, "ordinary handler count");
        Asserts.assertTrue(WB.isMethodCompiled(method), "compiled after handler fallback");
    }

    public static void main(String[] args) throws Exception {
        Method method = TestConstantLengthHandler.class.getDeclaredMethod("test",
                int.class, int.class, int.class);
        for (int i = 0; i < 20000; i++) {
            test(64, (i & 3) == 0 ? 1024 : 0, 3);
        }
        WB.deoptimizeMethod(method);
        WB.enqueueMethodForCompilation(method, 4);
        Asserts.assertTrue(WB.isMethodCompiled(method), "ordinary entry compiled with throwing profile");
        CheckLoopShape.assertVersioned(method);
        for (int end : new int[] {-1, 0, 1, 2, 64, 1025}) {
            for (int scale : new int[] {Integer.MIN_VALUE, -3, -1, 0, 1, 3, Integer.MAX_VALUE}) {
                check(method, end, 0, scale);
                check(method, end, 1023, scale);
                check(method, end, 1024, scale);
            }
        }
        System.out.println("CONSTANT_LENGTH_HANDLER_EXECUTED");
    }
}
