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

/**
 * @test
 * @requires vm.debug
 * @library /test/lib
 * @build jdk.test.lib.Asserts
 * @run driver compiler.jeandle.deoptimize.TestDeoptUnload
 */

package compiler.jeandle.deoptimize;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;
import java.util.ArrayList;
import java.util.List;

public class TestDeoptUnload {
    static class MyClass {
        public int fieldA;
        public static int staticB = 2;

        public MyClass(int fieldA) {
            this.fieldA = fieldA;
        }

        public static int getStaticB() {
            return staticB;
        }
    }

    static class MyLoadedClass {        
        public int fieldA;
    }

    static Object obj = new Object();

    static MyLoadedClass loadedObj = new MyLoadedClass();

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            runTests();
            return;
        }

        String testMethod = args[0];
        Method method = TestDeoptUnload.class.getDeclaredMethod(testMethod);
        method.invoke(null);
    }

    public static void runTests() throws Exception {
        ArrayList<String> commandPrefix = new ArrayList<>(List.of(
            "-Xcomp",
            "-Xbatch",
            "-XX:-TieredCompilation",
            "-XX:+UseJeandleCompiler",
            "-Xlog:deoptimization=debug",
            "-XX:CompileCommand=compileonly,compiler.jeandle.deoptimize.TestDeoptUnload::test*",
            TestDeoptUnload.class.getName()
        ));

        runTestHelper(commandPrefix, "testInvoke");
        runTestHelper(commandPrefix, "testCheckCast");
        runTestHelper(commandPrefix, "testInstanceof");
        runTestHelper(commandPrefix, "testLoadField");
        runTestHelper(commandPrefix, "testStoreField");
        runTestHelper(commandPrefix, "testNewInstance");
        runTestHelper(commandPrefix, "testANewArray");
        runTestHelper(commandPrefix, "testMultiANewArray");
    }

    public static void runTestHelper(ArrayList<String> commandPrefix, String testMethod) throws Exception {
        ArrayList<String> commandArgs = new ArrayList<>(commandPrefix);
        commandArgs.add(testMethod);

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(commandArgs);
        OutputAnalyzer output = ProcessTools.executeCommand(pb);

        output.shouldHaveExitValue(0);
        output.shouldMatch("\\[debug\\]\\[deoptimization\\].*" + testMethod + ".*unloaded reinterpret");
    }

    private static void testInvoke() {
        int b = MyClass.getStaticB();
    }

    private static void testCheckCast() {
        boolean passed = false;
        try {
            MyClass myObj = (MyClass)obj;
        } catch (ClassCastException e) {
            passed = true;
        }
        Asserts.assertTrue(passed);
    }

    private static void testInstanceof() {
        // MyClass is unloaded, do nothing.
        // We only check whether the deoptimization happens.
        if (obj instanceof MyClass);
    }

    private static void testLoadField() {
        int a = MyClass.staticB;
    }

    private static void testStoreField() {
        MyClass.staticB = 3;
    }

    private static void testNewInstance() {
        MyClass myObj = new MyClass(1);
    }

    private static void testANewArray() {
        MyClass[] array = new MyClass[3];
    }

    private static void testMultiANewArray() {
        MyClass[][][][][][] array = new MyClass[3][4][5][6][7][8];
    }
}
