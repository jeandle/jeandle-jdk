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
 * @test fast path and slow path of object allocation
 * @summary
 * @library /test/lib /
 * @run driver compiler.jeandle.bytecodeTranslate.alloc.TestFastSlowAllocation
 */

package compiler.jeandle.bytecodeTranslate.alloc;

import java.util.ArrayList;
import java.util.List;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestFastSlowAllocation {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            runTest(true);  // fast path: allocate a small object
            runTest(false); // slow path: allocate many big objects
            return;
        }
    }

    public static void runTest(boolean isFast) throws Exception {
        ArrayList<String> commandArgs = new ArrayList<>(List.of(
            "-Xcomp",
            "-Xbatch",
            "-Xmx5m",
            "-Xmn2m" ,
            "-XX:-TieredCompilation",
            "-XX:+UseJeandleCompiler",
            "-Xlog:jeandle+alloc=debug",
            "-XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.alloc.TestNewObject::*",
            TestNewObject.class.getName(),
            isFast ? "" : "stress"
        ));

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(commandArgs);
        OutputAnalyzer output = ProcessTools.executeCommand(pb);

        output.shouldHaveExitValue(0);

        if (isFast) {
            output.shouldNotContain("Slow path allocation for");
        } else {
            output.shouldContain("Slow path allocation for compiler.jeandle.bytecodeTranslate.alloc.TestNewObject$BigClass");
        }
    }
}
