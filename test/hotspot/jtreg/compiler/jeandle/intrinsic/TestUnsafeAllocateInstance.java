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
 * @summary Test the intrinsic implementation of Unsafe.allocateInstance()
 * @library /test/lib /
 * @modules java.base/jdk.internal.misc
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -Xbootclasspath/a:. -Xbatch -XX:+UnlockDiagnosticVMOptions
 *      -XX:+WhiteBoxAPI -XX:-TieredCompilation
 *      -XX:+UseJeandleCompiler
 *      -XX:CompileCommand=compileonly,compiler.jeandle.intrinsic.TestUnsafeAllocateInstance::allocate*
 *      compiler.jeandle.intrinsic.TestUnsafeAllocateInstance
 */

package compiler.jeandle.intrinsic;

import java.lang.reflect.Method;

import jdk.internal.misc.Unsafe;
import jdk.test.lib.Asserts;
import jdk.test.whitebox.WhiteBox;

public class TestUnsafeAllocateInstance {
    private static final Unsafe UNSAFE = Unsafe.getUnsafe();
    private static final WhiteBox WHITE_BOX = WhiteBox.getWhiteBox();
    private static boolean lazyClassInitialized;

    static class PlainClass {
        static int constructorCalls;

        int intValue;
        long longValue;
        Object referenceValue;

        PlainClass() {
            constructorCalls++;
            intValue = 42;
            longValue = 43;
            referenceValue = new Object();
        }
    }

    static class LazyClass {
        static {
            lazyClassInitialized = true;
        }

        int value = 42;
    }

    abstract static class AbstractClass {
    }

    interface TestInterface {
    }

    static Object allocateVariable(Class<?> klass) throws InstantiationException {
        return UNSAFE.allocateInstance(klass);
    }

    static Object allocateConstant() throws InstantiationException {
        return UNSAFE.allocateInstance(PlainClass.class);
    }

    private static void assertZeroed(PlainClass value) {
        Asserts.assertEquals(value.intValue, 0,
                "Unsafe allocation must leave int fields zeroed");
        Asserts.assertEquals(value.longValue, 0L,
                "Unsafe allocation must leave long fields zeroed");
        Asserts.assertTrue(value.referenceValue == null,
                "Unsafe allocation must leave reference fields null");
    }

    private static void warmup() throws InstantiationException {
        for (int i = 0; i < 20_000; i++) {
            allocateVariable(PlainClass.class);
            allocateConstant();
        }
    }

    private static void assertCompiled() throws Exception {
        Method variable = TestUnsafeAllocateInstance.class.getDeclaredMethod(
                "allocateVariable", Class.class);
        Method constant = TestUnsafeAllocateInstance.class.getDeclaredMethod("allocateConstant");
        Asserts.assertTrue(WHITE_BOX.isMethodCompiled(variable),
                "allocateVariable should be compiled after warmup");
        Asserts.assertTrue(WHITE_BOX.isMethodCompiled(constant),
                "allocateConstant should be compiled after warmup");
    }

    private static void testFastPath() throws InstantiationException {
        assertZeroed((PlainClass) allocateVariable(PlainClass.class));
        assertZeroed((PlainClass) allocateConstant());

        Asserts.assertEquals(PlainClass.constructorCalls, 0,
                "Unsafe.allocateInstance must not invoke a constructor");
    }

    private static void testClassInitializationSlowPath() throws InstantiationException {
        Asserts.assertFalse(lazyClassInitialized,
                "Taking a class literal must not initialize LazyClass");

        LazyClass value = (LazyClass) allocateVariable(LazyClass.class);
        Asserts.assertTrue(lazyClassInitialized,
                "Unsafe.allocateInstance must initialize the target class");
        Asserts.assertEquals(value.value, 0,
                "Unsafe allocation must not run instance field initializers");
    }

    private static void expectInstantiationException(Class<?> klass) throws Exception {
        try {
            allocateVariable(klass);
            throw new AssertionError("Expected InstantiationException for " + klass);
        } catch (InstantiationException expected) {
            // Expected.
        }
    }

    private static void testInvalidTypes() throws Exception {
        expectInstantiationException(AbstractClass.class);
        expectInstantiationException(TestInterface.class);
        expectInstantiationException(Object[].class);

        // A primitive mirror has a null Klass pointer and exercises the
        // intrinsic's uncommon-trap path, so keep this check last.
        expectInstantiationException(int.class);
    }

    public static void main(String[] args) throws Exception {
        PlainClass.constructorCalls = 0; // Initialize PlainClass before allocation.
        warmup();
        assertCompiled();
        testFastPath();
        testClassInitializationSlowPath();
        testInvalidTypes();
    }
}
