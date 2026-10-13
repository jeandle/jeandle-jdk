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
 * @summary Class.cast must stop emitting intrinsic traps after a failed cast
 * @requires os.arch=="amd64" | os.arch=="x86_64" | os.arch=="aarch64"
 * @library /test/lib /
 * @build jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions
 *      -XX:+WhiteBoxAPI -XX:-BackgroundCompilation -XX:-TieredCompilation
 *      -XX:CompileThreshold=1000000 -XX:-UseTypeSpeculation
 *      -XX:PerMethodTrapLimit=1 -XX:PerBytecodeTrapLimit=1
 *      -XX:-UseJeandleCompiler -XX:+InlineClassNatives
 *      -XX:CompileCommand=compileonly,compiler.jeandle.intrinsic.TestClassCastTrapLimit::cast
 *      -XX:CompileCommand=dontinline,compiler.jeandle.intrinsic.TestClassCastTrapLimit::cast
 *      compiler.jeandle.intrinsic.TestClassCastTrapLimit
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions
 *      -XX:+WhiteBoxAPI -XX:-BackgroundCompilation -XX:-TieredCompilation
 *      -XX:CompileThreshold=1000000 -XX:-UseTypeSpeculation
 *      -XX:PerMethodTrapLimit=1 -XX:PerBytecodeTrapLimit=1
 *      -XX:+UseJeandleCompiler -XX:+InlineClassNatives
 *      -XX:CompileCommand=compileonly,compiler.jeandle.intrinsic.TestClassCastTrapLimit::cast
 *      -XX:CompileCommand=dontinline,compiler.jeandle.intrinsic.TestClassCastTrapLimit::cast
 *      compiler.jeandle.intrinsic.TestClassCastTrapLimit
 */

package compiler.jeandle.intrinsic;

import compiler.whitebox.CompilerWhiteBoxTest;
import java.lang.reflect.Method;
import jdk.test.lib.Asserts;
import jdk.test.whitebox.WhiteBox;

public class TestClassCastTrapLimit {
    private static final WhiteBox WB = WhiteBox.getWhiteBox();

    public static Object cast(Class<?> mirror, Object value) {
        return mirror.cast(value);
    }

    private static void compile(Method method) {
        WB.deoptimizeMethod(method);
        Asserts.assertTrue(WB.enqueueMethodForCompilation(
                method, CompilerWhiteBoxTest.COMP_LEVEL_FULL_OPTIMIZATION));
        Asserts.assertTrue(WB.isMethodCompiled(method), "cast wrapper must compile");
    }

    private static void expectFailure(Class<?> mirror, Object value) {
        try {
            cast(mirror, value);
            throw new AssertionError("ClassCastException expected for " + mirror);
        } catch (ClassCastException expected) {
        }
    }

    private static int intrinsicTraps() {
        // JVMCI builds use the longer name even when JVMCI is not selected.
        return WB.getDeoptCount("intrinsic", null)
                + WB.getDeoptCount("intrinsic_or_type_checked_inlining", null);
    }

    public static void main(String[] args) throws Exception {
        Method method = TestClassCastTrapLimit.class.getDeclaredMethod(
                "cast", Class.class, Object.class);
        Object object = new Object();
        for (int i = 0; i < 2000; i++) {
            cast(String.class, "value");
            cast(Integer.class, Integer.valueOf(1));
            cast(Object.class, object);
        }

        int before = intrinsicTraps();
        compile(method);
        expectFailure(String.class, new Object());
        int after = intrinsicTraps();
        Asserts.assertGT(after, before, "the initial intrinsic trap must execute");

        // Force recompilation from the recorded trap history. Repeating this
        // used to recreate the same failing Class.cast intrinsic every time.
        for (int round = 0; round < 8; round++) {
            compile(method);
            for (int i = 0; i < 100; i++) {
                expectFailure(String.class, Integer.valueOf(1));
                expectFailure(int.class, Integer.valueOf(1));
            }
            Asserts.assertEQ(intrinsicTraps(), after,
                    "intrinsic traps must converge after recording the failure");
            Asserts.assertEQ(cast(String.class, "value"), "value");
            Asserts.assertNull(cast(String.class, null));
            Asserts.assertNull(cast(int.class, null));
        }
    }
}
