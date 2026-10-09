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
 * @summary RISC-V static call site patch address stays 4-byte aligned.
 *          https://github.com/jeandle/jeandle-jdk/issues/634
 * @requires vm.debug == true & os.arch == "riscv64"
 * @modules java.base/jdk.internal.vm.annotation
 * @run main/bootclasspath/othervm -Xcomp -XX:-TieredCompilation -XX:+UseJeandleCompiler
 *      -XX:CompileCommand=compileonly,TestStaticCallSiteAlignment::* TestStaticCallSiteAlignment
 * @run main/bootclasspath/othervm -Xcomp -XX:-TieredCompilation -XX:+UseJeandleCompiler -XX:-UseRVC
 *      -XX:CompileCommand=compileonly,TestStaticCallSiteAlignment::* TestStaticCallSiteAlignment
 */

import jdk.internal.vm.annotation.DontInline;

// Jeandle methods are always compiled without compressed instructions, so
// -XX:-UseRVC does not change the code LLVM emits. The two @run legs differ
// only in HotSpot's own prolog and stub compression, which is what moves the
// copied blob start off a 4-byte boundary. They do not cover compressed code
// inside Jeandle methods. The class is loaded from the boot classpath so
// @DontInline is honored and the static call stays out of line.
public class TestStaticCallSiteAlignment {
    static class Base {
        int mix(int a, int b) {
            int sum = a + b + 1;
            sum = sum ^ (b + 3);
            return sum + 4;
        }
    }

    static class Sub extends Base {
        @Override
        int mix(int a, int b) {
            int sum = a + b + 2;
            sum = sum ^ (b + 7);
            return sum + 8;
        }
    }

    @DontInline
    static int staticCallee(int a, int b) {
        int sum = a + b;
        sum = sum ^ (b + 3);
        sum = sum + (a ^ 5);
        return sum;
    }

    static int exercise(Base base, Base sub) {
        int acc = 0;
        for (int i = 0; i < 10000; i++) {
            acc = staticCallee(acc, i);
            Base recv = ((i & 1) == 0) ? base : sub;
            acc = recv.mix(acc, i);
        }
        return acc;
    }

    public static void main(String[] args) {
        // Initialize both receivers before exercise() is compiled, so the
        // virtual call cannot be statically bound.
        Base base = new Base();
        Base sub = new Sub();
        int acc = exercise(base, sub);
        System.out.println(acc);
        if (acc == 0) {
            throw new RuntimeException("accumulated call result was 0");
        }
    }
}
