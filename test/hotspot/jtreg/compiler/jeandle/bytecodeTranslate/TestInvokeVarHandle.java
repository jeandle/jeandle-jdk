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
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.TestInvokeVarHandle::test*
 *      -XX:+UseJeandleCompiler compiler.jeandle.bytecodeTranslate.TestInvokeVarHandle
 */

package compiler.jeandle.bytecodeTranslate;

import java.lang.invoke.*;
import jdk.test.lib.Asserts;

public class TestInvokeVarHandle {

    // Instance field
    private volatile int value = 0;
    // Static field
    private static volatile long counter = 0;

    private static final VarHandle VALUE_HANDLE;
    private static final VarHandle COUNTER_HANDLE;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            VALUE_HANDLE = lookup.findVarHandle(TestInvokeVarHandle.class, "value", int.class);
            COUNTER_HANDLE = lookup.findStaticVarHandle(TestInvokeVarHandle.class, "counter", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    // --- Plain access ---
    public static int testGetPlain(VarHandle vh, TestInvokeVarHandle obj) {
        return (int) vh.get(obj);
    }

    public static void testSetPlain(VarHandle vh, TestInvokeVarHandle obj, int val) {
        vh.set(obj, val);
    }

    public static long testGetStaticPlain(VarHandle vh) {
        return (long) vh.get();
    }

    public static void testSetStaticPlain(VarHandle vh, long val) {
        vh.set(val);
    }

    // --- Volatile access ---
    public static int testGetVolatile(VarHandle vh, TestInvokeVarHandle obj) {
        return (int) vh.getVolatile(obj);
    }

    public static void testSetVolatile(VarHandle vh, TestInvokeVarHandle obj, int val) {
        vh.setVolatile(obj, val);
    }

    public static long testGetStaticVolatile(VarHandle vh) {
        return (long) vh.getVolatile();
    }

    public static void testSetStaticVolatile(VarHandle vh, long val) {
        vh.setVolatile(val);
    }

    // --- Atomic CAS ---
    public static boolean testCompareAndSet(VarHandle vh, TestInvokeVarHandle obj, int expected, int newVal) {
        return (boolean) vh.compareAndSet(obj, expected, newVal);
    }

    public static boolean testStaticCompareAndSet(VarHandle vh, long expected, long newVal) {
        return (boolean) vh.compareAndSet(expected, newVal);
    }

    public static void main(String[] args) throws Throwable {
        TestInvokeVarHandle obj = new TestInvokeVarHandle();

        // Pre-resolve VarHandle adapters to avoid deoptimization in compiled test methods
        boolean booleanCapture;
        int intCapture;
        long longCapture;
        VALUE_HANDLE.set(obj, 1);
        intCapture = (int)VALUE_HANDLE.get(obj);
        VALUE_HANDLE.setVolatile(obj, 1);
        intCapture = (int)VALUE_HANDLE.getVolatile(obj);
        booleanCapture = (boolean)VALUE_HANDLE.compareAndSet(obj, 1, -1);
        COUNTER_HANDLE.set(2L);
        longCapture = (long)COUNTER_HANDLE.get();
        COUNTER_HANDLE.setVolatile(2L);
        longCapture = (long)COUNTER_HANDLE.getVolatile();
        booleanCapture = (boolean)COUNTER_HANDLE.compareAndSet(2L, -2L);

        // --- Plain access ---
        testSetPlain(VALUE_HANDLE, obj, 100);
        Asserts.assertEquals(testGetPlain(VALUE_HANDLE, obj), 100);

        testSetStaticPlain(COUNTER_HANDLE, 200L);
        Asserts.assertEquals(testGetStaticPlain(COUNTER_HANDLE), 200L);

        // --- Volatile access ---
        testSetVolatile(VALUE_HANDLE, obj, 300);
        Asserts.assertEquals(testGetVolatile(VALUE_HANDLE, obj), 300);

        testSetStaticVolatile(COUNTER_HANDLE, 400L);
        Asserts.assertEquals(testGetStaticVolatile(COUNTER_HANDLE), 400L);

        // --- CAS ---
        boolean cas1 = testCompareAndSet(VALUE_HANDLE, obj, 300, 500);
        Asserts.assertTrue(cas1);
        Asserts.assertEquals(testGetVolatile(VALUE_HANDLE, obj), 500);

        boolean cas2 = testStaticCompareAndSet(COUNTER_HANDLE, 400L, 600L);
        Asserts.assertTrue(cas2);
        Asserts.assertEquals(testGetStaticVolatile(COUNTER_HANDLE), 600L);
    }
}
