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
 * @summary Keep hot and feedback-disabled null checks explicit
 * @library /test/lib /
 * @build compiler.jeandle.fileCheck.FileCheck jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *      -Xbatch -XX:-TieredCompilation -XX:+UseJeandleCompiler -XX:CompileThreshold=1000000
 *      -XX:+JeandleDumpIR -XX:+JeandleDumpObjects
 *      -XX:CompileCommand=compileonly,compiler.jeandle.checks.TestImplicitNullChecks::testLength
 *      compiler.jeandle.checks.TestImplicitNullChecks hot
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *      -Xbatch -XX:-TieredCompilation -XX:+UseJeandleCompiler -XX:CompileThreshold=1000000
 *      -XX:-OmitStackTraceInFastThrow -XX:+JeandleDumpIR -XX:+JeandleDumpObjects
 *      -XX:CompileCommand=compileonly,compiler.jeandle.checks.TestImplicitNullChecks::testLength
 *      compiler.jeandle.checks.TestImplicitNullChecks hot-stacktrace
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *      -Xbatch -XX:-TieredCompilation -XX:+UseJeandleCompiler -XX:CompileThreshold=1000000
 *      -XX:+JeandleDumpIR -XX:+JeandleDumpObjects
 *      -XX:CompileCommand=compileonly,compiler.jeandle.checks.TestImplicitNullChecks::testLength
 *      compiler.jeandle.checks.TestImplicitNullChecks cold
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *      -Xbatch -XX:-TieredCompilation -XX:+UseJeandleCompiler -XX:CompileThreshold=1000000
 *      -XX:-ProfileTraps -XX:+JeandleDumpIR -XX:+JeandleDumpObjects
 *      -XX:CompileCommand=compileonly,compiler.jeandle.checks.TestImplicitNullChecks::testLength
 *      compiler.jeandle.checks.TestImplicitNullChecks no-profile
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *      -Xcomp -Xbatch -XX:-TieredCompilation -XX:+UseJeandleCompiler
 *      -XX:+JeandleDumpIR -XX:+JeandleDumpObjects
 *      -XX:CompileCommand=compileonly,compiler.jeandle.checks.TestImplicitNullChecks::testLength
 *      compiler.jeandle.checks.TestImplicitNullChecks no-interpreter
 */
package compiler.jeandle.checks;

import compiler.jeandle.fileCheck.FileCheck;
import java.lang.reflect.Method;
import jdk.test.lib.Asserts;
import jdk.test.whitebox.WhiteBox;

public class TestImplicitNullChecks {
    public static int testLength(int[] a) {
        try {
            return a.length;
        } catch (NullPointerException expected) {
            return expected == null ? -2 : -1;
        }
    }

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        boolean hot = mode.startsWith("hot");
        WhiteBox wb = WhiteBox.getWhiteBox();
        int[] a = new int[32];
        for (int i = 0; i < 20000; i++) {
            if (hot) {
                Asserts.assertEquals(testLength(null), -1);
            }
            Asserts.assertEquals(testLength(a), 32);
        }
        Method target = TestImplicitNullChecks.class.getDeclaredMethod("testLength", int[].class);
        wb.enqueueMethodForCompilation(target, 4);
        Asserts.assertTrue(wb.isMethodCompiled(target), "ordinary entry compiled for " + mode);
        FileCheck input = new FileCheck(System.getProperty("user.dir"), target, false);
        FileCheck optimized = new FileCheck(System.getProperty("user.dir"), target, true);
        if (mode.equals("cold")) {
            input.checkPattern("jeandle-implicit-null-check");
            optimized.checkPattern("!make.implicit");
        } else {
            input.checkNotPattern("jeandle-implicit-null-check|!make.implicit");
            optimized.checkNotPattern("!make.implicit");
        }
        if (mode.equals("hot")) {
            new FileCheck(System.getProperty("user.dir"), target, false)
                    .checkPattern("load ptr addrspace\\(1\\).*?(?=.*!nonnull)(?=.*!noundef)");
        }
        for (int i = 0; i < 100; i++) {
            Asserts.assertEquals(testLength(a), 32);
            Asserts.assertEquals(testLength(null), -1);
        }
        if (mode.equals("hot")) {
            Asserts.assertTrue(wb.isMethodCompiled(target), "hot null handler remains compiled");
        }
        System.out.println("NULL_CHECK_EXECUTED " + mode);
    }
}
