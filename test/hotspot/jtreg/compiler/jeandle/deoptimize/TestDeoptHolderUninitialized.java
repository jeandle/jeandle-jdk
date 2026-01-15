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
 * @test uninitialized deoptimization of holder klass
 * @requires vm.debug
 * @library /test/lib
 * @run driver compiler.jeandle.deoptimize.TestDeoptHolderUninitialized
 */

package compiler.jeandle.deoptimize;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.stream.IntStream;

import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestDeoptHolderUninitialized {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            runTest();
            return;
        }

        test();
    }

    public static void runTest() throws Exception {
        ArrayList<String> commandArgs = new ArrayList<>(List.of(
            "-Xcomp",
            "-Xbatch",
            "-XX:-TieredCompilation",
            "-XX:+UseJeandleCompiler",
            "-Xlog:deoptimization=debug",
            "-XX:CompileCommand=compileonly,java.util.HashMap::replacementTreeNode",
            TestDeoptHolderUninitialized.class.getName(),
            "test"
        ));

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(commandArgs);
        OutputAnalyzer output = ProcessTools.executeCommand(pb);

        output.shouldHaveExitValue(0);
        output.shouldMatch("\\[debug\\]\\[deoptimization\\].*replacementTreeNode.*uninitialized reinterpret");
    }

    public static void test() {
        // This will invoke replacementTreeNode and the trigger uninitialized deoptimization of holder klass.
        IntStream.range(0, 10).forEach(i ->
            new HashMap<Object, Object>(64) {{
                IntStream.range(0, 10).forEach(j ->
                    put(new Object() {
                        public int hashCode() { return 0; }
                        public boolean equals(Object o) { return false; }
                    }, j)
                );
            }}
        );
    }
}
