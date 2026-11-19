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
 * @summary Preserve implicit null checks with a profiled throwing handler
 * @library /test/lib /
 * @build compiler.jeandle.fileCheck.FileCheck jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *      -Xbatch -XX:-TieredCompilation -XX:+UseJeandleCompiler -XX:CompileThreshold=1000000
 *      -XX:+JeandleDumpIR -XX:+JeandleDumpObjects
 *      -XX:CompileCommand=compileonly,compiler.jeandle.checks.TestImplicitNullChecks::testHotLength
 *      compiler.jeandle.checks.TestImplicitNullChecks
 */
package compiler.jeandle.checks;

import compiler.jeandle.fileCheck.FileCheck;
import java.lang.reflect.Method;
import jdk.test.lib.Asserts;
import jdk.test.whitebox.WhiteBox;

public class TestImplicitNullChecks {
    public static int testHotLength(int[] a) {
        try {
            return a.length;
        } catch (NullPointerException expected) {
            return -1;
        }
    }

    public static void main(String[] args) throws Exception {
        WhiteBox wb = WhiteBox.getWhiteBox();
        int[] a = new int[32];
        for (int i = 0; i < 20000; i++) {
            Asserts.assertEquals(testHotLength(null), -1);
            Asserts.assertEquals(testHotLength(a), 32);
        }
        Method target = TestImplicitNullChecks.class.getDeclaredMethod("testHotLength", int[].class);
        wb.enqueueMethodForCompilation(target, 4);
        Asserts.assertTrue(wb.isMethodCompiled(target), "ordinary entry compiled with hot exception profile");
        new FileCheck(System.getProperty("user.dir"), target, false).checkPattern("!make.implicit");
        new FileCheck(System.getProperty("user.dir"), target, true).checkPattern("!make.implicit");
        for (int i = 0; i < 1000; i++) {
            Asserts.assertEquals(testHotLength(a), 32);
            Asserts.assertEquals(testHotLength(null), -1);
        }
        Asserts.assertTrue(wb.isMethodCompiled(target), "hot null handler remains compiled");
        System.out.println("HOT_NULL_CHECK_EXECUTED");
    }
}
