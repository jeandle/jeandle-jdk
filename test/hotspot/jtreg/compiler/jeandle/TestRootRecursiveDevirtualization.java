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
 */

/*
 * @test
 * @summary Verify a virtual root-recursive call exposed by inlining can be devirtualized
 * @run main/othervm
 *      -Xbatch -Xcomp -XX:-TieredCompilation -XX:+UseJeandleCompiler
 *      -XX:CompileCommand=quiet
 *      -XX:CompileCommand=compileonly,compiler.jeandle.TestRootRecursiveDevirtualization::root
 *      -XX:CompileCommand=inline,compiler.jeandle.TestRootRecursiveDevirtualization::helper
 *      compiler.jeandle.TestRootRecursiveDevirtualization
 */

package compiler.jeandle;

public class TestRootRecursiveDevirtualization {
    private static volatile int sink;

    // This is the compilation root. Keep it virtual and the class non-final so
    // the call exposed from helper starts as invokevirtual rather than as an
    // already-monomorphic direct call.
    public int root(int depth) {
        if (depth == 0) {
            return 1;
        }
        return helper(this, depth);
    }

    // The compile command forces this method into root. Its invokevirtual of
    // root is then a newly exposed call site in JeandleInlineDriver.
    private static int helper(TestRootRecursiveDevirtualization receiver,
                              int depth) {
        return 1 + receiver.root(depth - 1);
    }

    public static void main(String[] args) {
        TestRootRecursiveDevirtualization test =
                new TestRootRecursiveDevirtualization();
        for (int i = 0; i < 20_000; i++) {
            sink = test.root(3);
        }
        if (sink != 4) {
            throw new RuntimeException("unexpected result: " + sink);
        }
    }
}
