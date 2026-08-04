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
 * @summary Test goto_w bytecode translation
 * @library /test/lib
 * @build jdk.test.lib.Asserts
 * @build jdk.test.whitebox.WhiteBox
 * @compile GotoWTarget.jasm TestGotoW.java
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-TieredCompilation -Xbatch
 *      -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.GotoWTarget::gotoW
 *      -XX:+UseJeandleCompiler compiler.jeandle.bytecodeTranslate.TestGotoW
 */

package compiler.jeandle.bytecodeTranslate;

import java.lang.reflect.Method;

import jdk.test.lib.Asserts;
import jdk.test.whitebox.WhiteBox;

public class TestGotoW {
    private static final WhiteBox WB = WhiteBox.getWhiteBox();

    public static void main(String[] args) throws Exception {
        Method method = GotoWTarget.class.getDeclaredMethod("gotoW");

        Asserts.assertEquals(GotoWTarget.gotoW(), 1);
        compile(method);
        Asserts.assertEquals(GotoWTarget.gotoW(), 1);
    }

    private static void compile(Method method) {
        if (!WB.enqueueMethodForCompilation(method, 4)) {
            throw new RuntimeException("Enqueue " + method + " failed");
        }
        while (!WB.isMethodCompiled(method)) {
            Thread.yield();
        }
    }
}
