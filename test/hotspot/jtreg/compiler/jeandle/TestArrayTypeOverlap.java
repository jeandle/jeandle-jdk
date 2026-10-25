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
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License
 * version 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 */

/*
 * @test
 * @summary Covariant arrays can overlap with arrays of interfaces
 * @library /test/lib /
 * @build jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *      -Xcomp -Xbatch -XX:-TieredCompilation -XX:+UseJeandleCompiler
 *      -XX:CompileCommand=compileonly,compiler.jeandle.TestArrayTypeOverlap::test*
 *      compiler.jeandle.TestArrayTypeOverlap
 */

package compiler.jeandle;

import java.lang.reflect.Method;
import jdk.test.lib.Asserts;
import jdk.test.whitebox.WhiteBox;

public class TestArrayTypeOverlap {
    interface I { }
    static class A { }
    static final class D extends A implements I { }

    static boolean testOne(A[] value) {
        return value instanceof I[];
    }

    static boolean testTwo(A[][] value) {
        return value instanceof I[][];
    }

    static boolean testPrimitive(Object value) {
        return value instanceof int[];
    }

    public static void main(String[] args) throws Exception {
        // Resolve both target array klasses before compiling the tested methods.
        Object[] targets = {new I[0], new I[0][0]};
        Asserts.assertEQ(targets.length, 2);
        Asserts.assertTrue(testOne(new D[0]));
        Asserts.assertFalse(testOne(new A[0]));
        Asserts.assertFalse(testOne(null));
        Asserts.assertTrue(testTwo(new D[0][0]));
        Asserts.assertFalse(testTwo(new A[0][0]));
        Asserts.assertFalse(testTwo(null));
        Asserts.assertTrue(testPrimitive(new int[0]));
        Asserts.assertFalse(testPrimitive(new D[0]));
        Asserts.assertFalse(testPrimitive(null));

        WhiteBox wb = WhiteBox.getWhiteBox();
        for (Method method : TestArrayTypeOverlap.class.getDeclaredMethods()) {
            if (method.getName().startsWith("test")) {
                Asserts.assertTrue(wb.isMethodCompiled(method),
                                  method.getName() + " must be compiled by Jeandle");
            }
        }
    }
}
