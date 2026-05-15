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
 * @summary Verify fast path and slow path selection for array allocation via log output
 * @library /test/lib /
 * @run driver compiler.jeandle.bytecodeTranslate.alloc.TestArrayAllocFastSlowPath
 */

package compiler.jeandle.bytecodeTranslate.alloc;

import java.util.List;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestArrayAllocFastSlowPath {

    public static void main(String[] args) throws Exception {
        if (args.length == 1) {
            // Child process mode
            if (args[0].equals("typearray_small")) {
                Asserts.assertEquals(allocate_int_array(), 8);
            } else if (args[0].equals("objarray_small")) {
                Asserts.assertEquals(allocate_object_array(), 4);
            } else if (args[0].equals("typearray_stress")) {
                Asserts.assertEquals(stress_allocate_int_array(50_000), 50_000L * 1024);
            } else if (args[0].equals("objarray_stress")) {
                Asserts.assertEquals(stress_allocate_object_array(50_000), 50_000L * 256);
            }
            return;
        }

        // Driver mode: run sub-tests
        testFastPathTypeArray();
        testFastPathObjectArray();
        testSlowPathTLABExhaustionTypeArray();
        testSlowPathTLABExhaustionObjectArray();
        testSlowPathUseTLABDisabled();
    }

    public static int allocate_int_array() {
        int[] a = new int[8];
        return a.length;
    }

    public static int allocate_object_array() {
        Object[] a = new Object[4];
        return a.length;
    }

    // Repeatedly allocate medium arrays to force TLAB exhaustion under -Xmx5m -Xmn2m.
    public static long stress_allocate_int_array(int loop) {
        long sum = 0;
        for (int i = 0; i < loop; i++) {
            int[] a = new int[1024];
            sum += a.length;
        }
        return sum;
    }

    public static long stress_allocate_object_array(int loop) {
        long sum = 0;
        for (int i = 0; i < loop; i++) {
            Object[] a = new Object[256];
            sum += a.length;
        }
        return sum;
    }

    // Small int[] within TLAB headroom => fast path (no slow-path log)
    static void testFastPathTypeArray() throws Exception {
        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(List.of(
            "-Xcomp",
            "-Xbatch",
            "-XX:-TieredCompilation",
            "-XX:+UseJeandleCompiler",
            "-Xlog:jeandle+alloc=debug",
            "-XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.alloc.TestArrayAllocFastSlowPath::allocate_int_array",
            TestArrayAllocFastSlowPath.class.getName(),
            "typearray_small"
        ));
        OutputAnalyzer output = ProcessTools.executeCommand(pb);
        output.shouldHaveExitValue(0);
        output.shouldNotContain("Slow path allocation for [I");
    }

    // Small Object[] within TLAB headroom => fast path
    static void testFastPathObjectArray() throws Exception {
        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(List.of(
            "-Xcomp",
            "-Xbatch",
            "-XX:-TieredCompilation",
            "-XX:+UseJeandleCompiler",
            "-Xlog:jeandle+alloc=debug",
            "-XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.alloc.TestArrayAllocFastSlowPath::allocate_object_array",
            TestArrayAllocFastSlowPath.class.getName(),
            "objarray_small"
        ));
        OutputAnalyzer output = ProcessTools.executeCommand(pb);
        output.shouldHaveExitValue(0);
        output.shouldNotContain("Slow path allocation for [Ljava.lang.Object;");
    }

    // Repeated medium int[] under small heap => slow path eventually fires
    static void testSlowPathTLABExhaustionTypeArray() throws Exception {
        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(List.of(
            "-Xcomp",
            "-Xbatch",
            "-Xmx5m",
            "-Xmn2m",
            "-XX:-TieredCompilation",
            "-XX:+UseJeandleCompiler",
            "-Xlog:jeandle+alloc=debug",
            "-XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.alloc.TestArrayAllocFastSlowPath::*",
            TestArrayAllocFastSlowPath.class.getName(),
            "typearray_stress"
        ));
        OutputAnalyzer output = ProcessTools.executeCommand(pb);
        output.shouldHaveExitValue(0);
        output.shouldContain("Slow path allocation for [I");
    }

    // Repeated medium Object[] under small heap => slow path eventually fires
    static void testSlowPathTLABExhaustionObjectArray() throws Exception {
        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(List.of(
            "-Xcomp",
            "-Xbatch",
            "-Xmx5m",
            "-Xmn2m",
            "-XX:-TieredCompilation",
            "-XX:+UseJeandleCompiler",
            "-Xlog:jeandle+alloc=debug",
            "-XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.alloc.TestArrayAllocFastSlowPath::*",
            TestArrayAllocFastSlowPath.class.getName(),
            "objarray_stress"
        ));
        OutputAnalyzer output = ProcessTools.executeCommand(pb);
        output.shouldHaveExitValue(0);
        output.shouldContain("Slow path allocation for [Ljava.lang.Object;");
    }

    // With -XX:-UseTLAB, all array allocations should go through slow path
    static void testSlowPathUseTLABDisabled() throws Exception {
        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(List.of(
            "-Xcomp",
            "-Xbatch",
            "-XX:-TieredCompilation",
            "-XX:-UseTLAB",
            "-XX:+UseJeandleCompiler",
            "-Xlog:jeandle+alloc=debug",
            "-XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.alloc.TestArrayAllocFastSlowPath::allocate_int_array",
            TestArrayAllocFastSlowPath.class.getName(),
            "typearray_small"
        ));
        OutputAnalyzer output = ProcessTools.executeCommand(pb);
        output.shouldHaveExitValue(0);
        output.shouldContain("Slow path allocation for [I");
    }
}
