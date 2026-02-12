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
 * @test
 * @library /test/lib
 * @run main/othervm -Xcomp -XX:-TieredCompilation -Xbatch -XX:+TraceCallFixup
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.calls.TestStaticBoundedCall::testFinalCall
 *      -XX:+UseJeandleCompiler compiler.jeandle.bytecodeTranslate.calls.TestStaticBoundedCall true
 */

package compiler.jeandle.bytecodeTranslate.calls;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestStaticBoundedCall {
    public static int x = 0;

    public static class SubClassA extends TestStaticBoundedCall { }

    public static class SubClassB extends TestStaticBoundedCall { }

    public final void finalCall() {
        x = 1;
    }

    public static void testFinalCall(TestStaticBoundedCall t){
        t.finalCall();
    }

    public static void doTest() {
        SubClassA a = new SubClassA();
        SubClassB b = new SubClassB();
        for (int i = 0; i < 100000; i++) {
            testFinalCall(a);
            testFinalCall(b);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("true")) {
            List<String> jvmArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();
            List<String> cmdLine = new ArrayList<>();
            cmdLine.addAll(jvmArgs);
            cmdLine.add("compiler.jeandle.bytecodeTranslate.calls.TestStaticBoundedCall");
            ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(cmdLine);
            OutputAnalyzer output = new OutputAnalyzer(pb.start());
            output.shouldHaveExitValue(0);

            output.stdoutShouldNotContain("handle_wrong_method");
            return;
        }

        doTest();
    }
}
