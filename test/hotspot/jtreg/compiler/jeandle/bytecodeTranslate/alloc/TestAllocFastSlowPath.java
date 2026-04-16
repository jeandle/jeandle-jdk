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
 * @summary Verify fast path and slow path selection for object allocation via log output
 * @library /test/lib /
 * @run driver compiler.jeandle.bytecodeTranslate.alloc.TestAllocFastSlowPath
 */

package compiler.jeandle.bytecodeTranslate.alloc;

import java.util.ArrayList;
import java.util.List;

import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestAllocFastSlowPath {

    public static void main(String[] args) throws Exception {
        testFastPathSmallObject();
        testSlowPathTLABExhaustion();
        testSlowPathUseTLABDisabled();
    }

    // Small object with sufficient TLAB space should use fast path (no slow path log)
    static void testFastPathSmallObject() throws Exception {
        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(List.of(
            "-Xcomp",
            "-Xbatch",
            "-XX:-TieredCompilation",
            "-XX:+UseJeandleCompiler",
            "-Xlog:jeandle+alloc=debug",
            "-XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.alloc.TestNewObject::allocate_java_instance",
            TestNewObject.class.getName()
        ));
        OutputAnalyzer output = ProcessTools.executeCommand(pb);
        output.shouldHaveExitValue(0);
        output.shouldNotContain("Slow path allocation for");
    }

    // Allocating large objects in tight loop with small heap should trigger slow path
    static void testSlowPathTLABExhaustion() throws Exception {
        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(List.of(
            "-Xcomp",
            "-Xbatch",
            "-Xmx5m",
            "-Xmn2m",
            "-XX:-TieredCompilation",
            "-XX:+UseJeandleCompiler",
            "-Xlog:jeandle+alloc=debug",
            "-XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.alloc.TestNewObject::*",
            TestNewObject.class.getName(),
            "stress"
        ));
        OutputAnalyzer output = ProcessTools.executeCommand(pb);
        output.shouldHaveExitValue(0);
        output.shouldContain("Slow path allocation for compiler.jeandle.bytecodeTranslate.alloc.TestNewObject$BigClass");
    }

    // With -XX:-UseTLAB, all allocations should go through slow path
    static void testSlowPathUseTLABDisabled() throws Exception {
        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(List.of(
            "-Xcomp",
            "-Xbatch",
            "-XX:-TieredCompilation",
            "-XX:-UseTLAB",
            "-XX:+UseJeandleCompiler",
            "-Xlog:jeandle+alloc=debug",
            "-XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.alloc.TestNewObject::allocate_java_instance",
            TestNewObject.class.getName()
        ));
        OutputAnalyzer output = ProcessTools.executeCommand(pb);
        output.shouldHaveExitValue(0);
        output.shouldContain("Slow path allocation for compiler.jeandle.bytecodeTranslate.alloc.TestNewObject");
    }
}
