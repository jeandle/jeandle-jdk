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
 * @run driver compiler.jeandle.bytecodeTranslate.TestFallthroughSwitch
 */

package compiler.jeandle.bytecodeTranslate;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestFallthroughSwitch {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            runTests();
            return;
        }

        String testMethod = args[0];
        Method method = TestFallthroughSwitch.class.getDeclaredMethod(testMethod);
        method.invoke(null);
    }

    public static void runTests() throws Exception {
        ArrayList<String> commandArgs = new ArrayList<>(List.of(
            "-Xcomp",
            "-Xbatch",
            "-XX:-TieredCompilation",
            "-XX:+UseJeandleCompiler",
            "-XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.TestFallthroughSwitch::fallthroughSwitch",
            TestFallthroughSwitch.class.getName(),
            "testfallthroughSwitch"
        ));

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(commandArgs);
        OutputAnalyzer output = ProcessTools.executeCommand(pb);

        output.shouldHaveExitValue(0);
        output.stderrShouldBeEmpty();
    }

    public static int fallthroughSwitch(int num) {
        int a = 0;
        switch(num) {
            case 1: a += returnOne();
            case 2: a += returnTwo();
            case 3: a += returnThree();
            case 4:
            case 5:
            case 6:
            // Fallthrough
            case 7: a += returnSeven();
        }

        return a;
    }

    private static void testfallthroughSwitch() {
        Asserts.assertEquals(fallthroughSwitch(1), 1 + 2 + 3 + 7);
        Asserts.assertEquals(fallthroughSwitch(2), 2 + 3 + 7);
        Asserts.assertEquals(fallthroughSwitch(3), 3 + 7);
        Asserts.assertEquals(fallthroughSwitch(4), 7);
        Asserts.assertEquals(fallthroughSwitch(5), 7);
        Asserts.assertEquals(fallthroughSwitch(6), 7);
        Asserts.assertEquals(fallthroughSwitch(7), 7);
    }

    public static int returnOne()   { return 1; }
    public static int returnTwo()   { return 2; }
    public static int returnThree() { return 3; }
    public static int returnSeven() { return 7; }
}
