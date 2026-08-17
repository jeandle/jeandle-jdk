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

/*
 * @test
 * @summary Test that checkcast and instanceof on null do not trigger an uncommon trap
 * @library /test/lib
 * @build jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *      -Xbatch -XX:-TieredCompilation -XX:+UseJeandleCompiler
 *      -XX:CompileCommand=compileonly,TestNullAssert::checkcastNull
 *      -XX:CompileCommand=compileonly,TestNullAssert::instanceofNull
 *      TestNullAssert
 */

import java.lang.reflect.Method;

import jdk.test.lib.Asserts;
import jdk.test.whitebox.WhiteBox;

public class TestNullAssert {
    private static final WhiteBox WB = WhiteBox.getWhiteBox();
    private static final int COMP_LEVEL_FULL_OPTIMIZATION = 4;

    public static void main(String[] args) throws Exception {
        Asserts.assertFalse(WB.isClassAlive("UnloadedCheckcastTarget"));
        Method checkcast = compile("checkcastNull");
        Asserts.assertFalse(WB.isClassAlive("UnloadedCheckcastTarget"));

        Asserts.assertTrue(checkcastNull(null) == null);
        Asserts.assertTrue(WB.isMethodCompiled(checkcast),
                "checkcast on null triggered an uncommon trap");

        Asserts.assertFalse(WB.isClassAlive("UnloadedInstanceofTarget"));
        Method instanceOf = compile("instanceofNull");
        Asserts.assertFalse(WB.isClassAlive("UnloadedInstanceofTarget"));

        Asserts.assertFalse(instanceofNull(null));
        Asserts.assertTrue(WB.isMethodCompiled(instanceOf),
                "instanceof on null triggered an uncommon trap");
    }

    private static Method compile(String name) throws Exception {
        Method method = TestNullAssert.class.getDeclaredMethod(name, Object.class);
        Asserts.assertTrue(WB.enqueueMethodForCompilation(method, COMP_LEVEL_FULL_OPTIMIZATION),
                "failed to enqueue " + name + " for compilation");
        while (!WB.isMethodCompiled(method)) {
            Thread.onSpinWait();
        }
        return method;
    }

    private static Object checkcastNull(Object obj) {
        return (UnloadedCheckcastTarget) obj;
    }

    private static boolean instanceofNull(Object obj) {
        return obj instanceof UnloadedInstanceofTarget;
    }
}

class UnloadedCheckcastTarget {
}

class UnloadedInstanceofTarget {
}
