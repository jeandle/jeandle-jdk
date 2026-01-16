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
 * @library /test/lib
 * @build jdk.test.lib.Asserts
 * @run main/othervm -XX:-TieredCompilation -Xcomp -Xbatch
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.TestInvokeMethodHandle::test*
 *      -XX:+UseJeandleCompiler compiler.jeandle.bytecodeTranslate.TestInvokeMethodHandle
 */

package compiler.jeandle.bytecodeTranslate;

import java.lang.invoke.*;
import jdk.test.lib.Asserts;

import java.lang.invoke.*;

public class TestInvokeMethodHandle {

    public int add(int a, int b) {
        return a + b;
    }

    public static long mul(long x, long y) {
        return x * y;
    }

    // --- invokeExact tests ---
    public static int testInvokeExact(MethodHandle mh, TestInvokeMethodHandle obj) throws Throwable {
        return (int) mh.invokeExact(obj, 10, 20);
    }

    public static long testStaticInvokeExact(MethodHandle mh) throws Throwable {
        return (long) mh.invokeExact(5L, 6L);
    }

    // --- invoke() tests (with type adaptation) ---
    public static Object testInvokeWithBoxing(MethodHandle mh, TestInvokeMethodHandle obj) throws Throwable {
        // Pass Integer args; invoke() auto-unboxes to int
        return mh.invoke(obj, Integer.valueOf(10), Integer.valueOf(20));
    }

    public static Object testInvokeWithSubtype(MethodHandle mh, TestInvokeMethodHandle obj) throws Throwable {
        // Same args as invokeExact, but uses flexible invoke()
        return mh.invoke(obj, 10, 20);
    }

    public static Object testStaticInvoke(MethodHandle mh) throws Throwable {
        // Static method via invoke()
        return mh.invoke(5L, 6L);
    }

    public static void main(String[] args) throws Throwable {
        TestInvokeMethodHandle t = new TestInvokeMethodHandle();

        MethodType mtAdd = MethodType.methodType(int.class, int.class, int.class);
        MethodHandle mhAdd = MethodHandles.lookup().findVirtual(TestInvokeMethodHandle.class, "add", mtAdd);

        MethodType mtMul = MethodType.methodType(long.class, long.class, long.class);
        MethodHandle mhMul = MethodHandles.lookup().findStatic(TestInvokeMethodHandle.class, "mul", mtMul);

        // Pre-resolve MethodHandle adapters to avoid deoptimization in compiled test methods
        int intCapture;
        long longCapture;
        Object objectCapture;
        intCapture = (int)mhAdd.invokeExact(t, 1, 2);
        longCapture = (long)mhMul.invokeExact(3L, 4L);
        objectCapture = (Object)mhAdd.invoke(t, 5, 6);
        objectCapture = (Object)mhMul.invoke(7L, 8L);
        objectCapture = (Object)mhAdd.invoke(t, Integer.valueOf(9), Integer.valueOf(10));

        // === invokeExact ===
        Asserts.assertEquals(testInvokeExact(mhAdd, t), 30);
        Asserts.assertEquals(testStaticInvokeExact(mhMul), 30L);

        // === invoke() ===
        Asserts.assertEquals(testInvokeWithBoxing(mhAdd, t), 30);
        Asserts.assertEquals(testInvokeWithSubtype(mhAdd, t), 30);
        Asserts.assertEquals(testStaticInvoke(mhMul), 30L);
    }
}
