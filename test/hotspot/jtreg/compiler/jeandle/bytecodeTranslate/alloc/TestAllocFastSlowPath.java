/*
 * Copyright (c) 2025, 2026, the Jeandle-JDK Authors. All Rights Reserved.
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

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestAllocFastSlowPath {

    private int _val = 42;

    public static void main(String[] args) throws Exception {
        if (args.length == 1) {
            // Child process mode
            if (args[0].equals("normal")) {
                Asserts.assertEquals(allocate_java_instance(), 42);
            } else if (args[0].equals("stress")) {
                // Pre-load BigClass before Jeandle compiles the method,
                // to avoid deoptimization.
                new BigClass();
                Asserts.assertEquals(stress_allocate_java_instance(100_000), 42L * 100_000);
            }
            return;
        }

        // Driver mode: run sub-tests
        testFastPathSmallObject();
        testSlowPathTLABExhaustion();
        testSlowPathUseTLABDisabled();
    }

    public static int allocate_java_instance() {
        TestAllocFastSlowPath obj = new TestAllocFastSlowPath();
        return obj._val;
    }

    public static long stress_allocate_java_instance(int loop) {
        long sum = 0;
        for (int i = 0; i < loop; i++) {
            BigClass obj = new BigClass();
            sum += obj.x;
        }
        return sum;
    }

    // Small object with sufficient TLAB space should use fast path (no slow path log)
    static void testFastPathSmallObject() throws Exception {
        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(List.of(
            "-Xcomp",
            "-Xbatch",
            "-XX:-TieredCompilation",
            "-XX:+UseJeandleCompiler",
            "-Xlog:jeandle+alloc=debug",
            "-XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.alloc.TestAllocFastSlowPath::allocate_java_instance",
            TestAllocFastSlowPath.class.getName(),
            "normal"
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
            "-XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.alloc.TestAllocFastSlowPath::*",
            TestAllocFastSlowPath.class.getName(),
            "stress"
        ));
        OutputAnalyzer output = ProcessTools.executeCommand(pb);
        output.shouldHaveExitValue(0);
        output.shouldContain("Slow path allocation for compiler.jeandle.bytecodeTranslate.alloc.TestAllocFastSlowPath$BigClass");
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
            "-XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.alloc.TestAllocFastSlowPath::allocate_java_instance",
            TestAllocFastSlowPath.class.getName(),
            "normal"
        ));
        OutputAnalyzer output = ProcessTools.executeCommand(pb);
        output.shouldHaveExitValue(0);
        output.shouldContain("Slow path allocation for compiler.jeandle.bytecodeTranslate.alloc.TestAllocFastSlowPath");
    }

    // A big class to make TLAB exhaustion likely under small heap
    static class BigClass {
        long a;
        long b;
        long c;
        long d;
        long e;
        long f;
        long g;
        long h;
        long i;
        long j;
        long k;
        long l;
        long m;
        long n;
        long o;
        long p;
        long q;
        long r;
        long s;
        long t;
        long u;
        long v;
        long w;
        long x = 42L;
        long y;
        long z;

        double d_a;
        double d_b;
        double d_c;
        double d_d;
        double d_e;
        double d_f;
        double d_g;
        double d_h;
        double d_i;
        double d_j;
        double d_k;
        double d_l;
        double d_m;
        double d_n;
        double d_o;
        double d_p;
        double d_q;
        double d_r;
        double d_s;
        double d_t;
        double d_u;
        double d_v;
        double d_w;
        double d_x;
        double d_y;
        double d_z;
    }
}
