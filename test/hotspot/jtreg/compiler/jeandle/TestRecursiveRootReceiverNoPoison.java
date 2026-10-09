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
 * You should have received a copy of the GNU General Public License
 * version 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, 5th Floor, Boston, MA 02110-1301 USA.
 */

/*
 * @test
 * @summary Verify root function name carries .root suffix and a recursive
 *          virtual call with an unused formal receiver does not have its
 *          call-site operand poisoned by DeadArgumentElimination.
 * @library /test/lib /
 * @build compiler.jeandle.fileCheck.FileCheck
 * @run main/othervm
 *      -Xbatch -Xcomp -XX:-TieredCompilation -XX:+UseJeandleCompiler
 *      -XX:+JeandleDumpIR
 *      -XX:CompileCommand=quiet
 *      -XX:CompileCommand=compileonly,compiler.jeandle.TestRecursiveRootReceiverNoPoison::root
 *      compiler.jeandle.TestRecursiveRootReceiverNoPoison
 */

package compiler.jeandle;

import compiler.jeandle.fileCheck.FileCheck;
import java.lang.reflect.Method;

public class TestRecursiveRootReceiverNoPoison {

    private static volatile int sink;

    // A different receiver for the recursive call so that `this` (the formal
    // receiver parameter of root) is never dereferenced in the method body.
    static TestRecursiveRootReceiverNoPoison other =
            new TestRecursiveRootReceiverNoPoison();

    // The class is intentionally non-final so the recursive call starts as
    // invokevirtual. CHA can still prove the unique target is this method
    // itself (no subclasses are loaded), and devirtualize accordingly.
    public int root(int depth) {
        if (depth == 0) {
            return 1;
        }
        // The recursive call passes `other`, not `this`. After CHA devirt
        // retargets this invoke to root itself, the call targets a distinct
        // unsuffixed declaration while the root function definition carries
        // the .root suffix. DAE therefore sees no recursive call and does
        // not poison the receiver operand.
        return other.root(depth - 1) + 1;
    }

    public static void main(String[] args) throws Exception {
        TestRecursiveRootReceiverNoPoison test =
                new TestRecursiveRootReceiverNoPoison();
        int result = 0;
        for (int i = 0; i < 20_000; i++) {
            result = test.root(3);
        }
        sink = result;
        if (sink != 4) {
            throw new RuntimeException("unexpected result: " + sink);
        }

        // Inspect the optimized IR dump.
        String currentDir = System.getProperty("user.dir");
        Method rootMethod =
                TestRecursiveRootReceiverNoPoison.class
                        .getDeclaredMethod("root", int.class);

        FileCheck fileCheck =
                new FileCheck(currentDir, rootMethod, true);

        // The root function definition must carry the .root suffix.
        fileCheck.checkPattern(
                "define.*TestRecursiveRootReceiverNoPoison_root.*\\.root");

        // DAE must not have replaced any call operand with poison.
        fileCheck.checkNot("poison");
    }
}
