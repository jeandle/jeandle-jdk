/*
 * Copyright (c) 2025, the Jeandle-JDK Authors. All Rights Reserved.
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
 * @test TestNullCheck.java
 * @summary Support null check, which can be optimized into implicit checking.
 *  issue: https://github.com/jeandle/jeandle-jdk/issues/14
 * @requires os.arch=="amd64" | os.arch=="x86_64" | os.arch=="aarch64"
 * @library /test/lib /
 * @build compiler.jeandle.fileCheck.FileCheck
 * @run driver TestNullCheck
 */

import compiler.jeandle.fileCheck.FileCheck;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestNullCheck {
    private static final String NPE_STRING = "java.lang.NullPointerException";

    private static final String[] procArgs = new String[] {
        "-Xcomp",
        "-XX:-TieredCompilation",
        "-XX:CompileCommand=compileonly,TestNullCheck::test*",
        "-XX:CompileCommand=compileonly,TestNullCheck::justThrowNull",
        "-XX:+JeandleDumpIR",
        "-XX:+UseJeandleCompiler",
        "TestNullCheck"
    };

    public static void main(String[] args) throws Exception {
        Class.forName("MyClass");

        if (args.length == 0) {
            runVM();
        } else if (args[0].equals("testBase")) {
            MyClass myClass = new MyClass();
            Asserts.assertEquals(testInvoke(myClass), 1);

            Asserts.assertEquals(testAccess(myClass), 1);
            checkFileNull(TestNullCheck.class.getDeclaredMethod("testAccess", MyClass.class));

            Asserts.assertEquals(testMulti(myClass, myClass), 9);
            checkFileNull(TestNullCheck.class.getDeclaredMethod("testMulti", MyClass.class, MyClass.class));
        } else if (args[0].equals("testInvoke")) {
            testInvoke(null);
        } else if (args[0].equals("testAccess")) {
            testAccess(null);
        } else if (args[0].equals("testMulti")) {
            testMulti(null, null);
        } else if (args[0].equals("testThrowNull")) {
            testThrowNull();
        } else {
            throw new IllegalArgumentException("Unsupported argument: " + args[0]);
        }
    }

    private static void runVM() throws Exception {
        OutputAnalyzer output = runTestProcess("testBase");
        output.shouldHaveExitValue(0);

        output = runTestProcess("testInvoke");
        output.shouldHaveExitValue(1);
        output.shouldContain(NPE_STRING);

        output = runTestProcess("testAccess");
        output.shouldHaveExitValue(1);
        output.shouldContain(NPE_STRING);

        output = runTestProcess("testMulti");
        output.shouldHaveExitValue(1);
        output.shouldContain(NPE_STRING);

        output = runTestProcess("testThrowNull");
        output.shouldHaveExitValue(0);
    }

    private static OutputAnalyzer runTestProcess(String testType) throws Exception {
        List<String> loadCmd = new ArrayList();
        loadCmd.addAll(Arrays.asList(procArgs));
        loadCmd.add(testType);
        String testClassPath = System.getProperty("test.classes", ".");
        String classPathProp = "-Dtest.classes=" + testClassPath;
        loadCmd.add(0, classPathProp);

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(loadCmd);
        return ProcessTools.executeProcess(pb);
    }

    private static void checkFileNull(Method method) throws Exception {
        String currentDir = System.getProperty("user.dir");

        FileCheck fileCheck = new FileCheck(currentDir, method, false);
        fileCheck.checkPattern("br i1 %[0-9]+, label %bci_[0-9]+_null_check_fail, label %bci_[0-9]+_null_check_pass, !make.implicit");
    }

    private static int testInvoke(MyClass myClass) {
        return myClass.getField();
    }

    private static int testAccess(MyClass myClass) {
        return myClass.field;
    }

    private static int testMulti(MyClass a, MyClass b) {
        int x = a.field + 3;
        if (x < 0) {
            return 0;
        }
        int y = b.field + 4;
        return x + y;
    }

    private static void testThrowNull() {
        try {
            justThrowNull();
        } catch (Exception e) {}
    }

    private static void justThrowNull() throws Exception {
        throw null;
    }
}

class MyClass {
    public int field = 1;

    public int getField() {
        return field;
    }
}
