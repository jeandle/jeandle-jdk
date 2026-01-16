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
 * @library /test/lib /
 * @build jdk.test.lib.Asserts
 * @run main/othervm compiler.jeandle.deoptimize.TestScopeValues
 */

package compiler.jeandle.deoptimize;

import compiler.jeandle.fileCheck.FileCheck;
import java.util.ArrayList;
import java.util.List;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestScopeValues {
    public static void main(String[] args) throws Exception {
        String dump_path = System.getProperty("user.dir");
        ArrayList<String> command_args = new ArrayList<String>(List.of(
                "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
                "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dump_path,
                "-XX:+PrintNMethods",
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::test_invoke",
                TestWrapper.class.getName()));

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(command_args);
        OutputAnalyzer output = ProcessTools.executeCommand(pb);

        output.shouldHaveExitValue(0);

        // Verify llvm IR
        FileCheck checker = new FileCheck(dump_path, TestWrapper.class.getMethod("test_invoke", TestWrapper.class), false);
        // find compiled method
        checker.checkPattern(
            "define hotspotcc i32 .*compiler_jeandle_deoptimize_TestScopeValues\\$TestWrapper_test_invoke.*");
        // check IR
        checker.checkNext("entry:");
        checker.checkNext("br label %bci_0");
        checker.checkNext("bci_0:");
        checker.checkNextPattern("invoke hotspotcc .*compiler_jeandle_deoptimize_TestScopeValues\\$TestWrapper_empty.* \"deopt\"\\(i32 11, i64 12, ptr addrspace\\(1\\) %0, i64 4294967306, i32 10, i64 8589934603, i64 12, i64 17179869190, float 1.300000e\\+01\\)");

        // check DebugInfo in nmethods output
        /* example output of PcDesc
pc-bytecode offsets:
PcDesc(pc=0x00007fcad48b273f offset=ffffffff bits=0):
PcDesc(pc=0x00007fcad48b2758 offset=18 bits=0):
   compiler.jeandle.deoptimize.TestScopeValues$TestWrapper::test_invoke@11 (line 84)
   Locals
    - l0: stack[12],oop
    - l1: 10
    - l2: 0
    - l3: 12
    - l4: 1095761920
        */
        output.shouldMatch(
            "pc-bytecode offsets:\n" +
            "PcDesc.*\n" +
            "PcDesc.*\n" +
            ".*TestWrapper::test_invoke.*\n" +
            ".*Locals\n" +
            ".*l0: stack\\[\\d+\\],oop\n" +
            ".*l1: 10\n" +
            ".*l2: 0\n" +
            ".*l3: 12\n" +
            ".*l4: 1095761920\n"
        );
        /* example output of ScopeDesc
scopes:
ScopeDesc(pc=0x00007fccfc8b2758 offset=18):
   compiler.jeandle.deoptimize.TestScopeValues$TestWrapper::test_invoke@11 (line 101)
   Locals
    - l0: stack[0],oop
    - l1: 10
    - l3: 12
    - l4: 10957619200
         */
        output.shouldMatch(
            "scopes:\n" +
            "ScopeDesc.*\n" +
            ".*TestWrapper::test_invoke.*\n" +
            ".*Locals\n" +
            ".*l0: stack\\[\\d+\\],oop\n" +
            ".*l1: 10\n" +
            ".*l2: 0\n" +
            ".*l3: 12\n" +
            ".*l4: 1095761920\n"
        );
    }

    static public class TestWrapper {
        private int val = 123;
        public static void main(String[] args) {
            TestWrapper obj = new TestWrapper();
            Asserts.assertEquals(test_invoke(obj), 158);
        }

        public static int test_invoke(TestWrapper obj) {
            // these varialbes should be live at 'empty' call site and recorded in ScopeValues
            int   i = 10;
            long  l = 12;
            float f = 13.0f;
            empty();
            return (int)(i+l+f+obj.val);
        }

        public static void empty() { return; }
    }
}
