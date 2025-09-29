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

package compiler.jeandle.bytecodeTranslate.alloc;

import jdk.test.lib.Asserts;

/**
 * @test
 * @summary Test arraylength
 * @library /test/lib
 * @run main/othervm -Xcomp -XX:-TieredCompilation
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.alloc.TestNewObject::allocate_java_instance
 *      -XX:+UseJeandleCompiler compiler.jeandle.bytecodeTranslate.alloc.TestNewObject
 * @run main/othervm -Xmx5m -Xmn2m -XX:+PrintGCDetails -Xcomp -XX:-TieredCompilation
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.alloc.TestNewObject::stress_allocate_java_instance
 *      -XX:+UseJeandleCompiler compiler.jeandle.bytecodeTranslate.alloc.TestNewObject stress
 */
public class TestNewObject {
    private int _val = 42;
    public static void main(String[] args) {
        boolean stress = args.length == 1  && args[0].equals("stress");
        if (stress) {
            BigClass obj = new BigClass();
            // stress allocate big object in loop to trigger a GC
            Asserts.assertEquals(stress_allocate_java_instance(100_000), 42L * 100_000);
        } else {
            Asserts.assertEquals(allocate_java_instance(), 42);
        }
    }

    public static int allocate_java_instance() {
        TestNewObject obj = new TestNewObject();
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

    // define a big class
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
