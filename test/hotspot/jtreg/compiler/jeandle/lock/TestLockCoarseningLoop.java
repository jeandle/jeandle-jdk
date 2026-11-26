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
 */

/*
 * @test
 * @summary Loop-local coarsening must not hold a monitor across a backedge
 * @library /test/lib
 * @build compiler.jeandle.lock.LockCoarseningTestUtils jdk.test.lib.Asserts
 * @run driver compiler.jeandle.lock.TestLockCoarseningLoop
 */

package compiler.jeandle.lock;

import java.nio.file.Path;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;

public class TestLockCoarseningLoop {
    private static volatile int sink;

    private final int value;

    private TestLockCoarseningLoop(int value) {
        this.value = value;
    }

    private synchronized int sum(int input) {
        return value + input;
    }

    private int loop(int iterations) {
        int result = value;
        for (int i = 0; i < iterations; i++) {
            result = sum(result);
            result = sum(result);
        }
        return result;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 0 && args[0].equals("child")) {
            runChild();
            return;
        }

        Path dumpDirectory = LockCoarseningTestUtils.createDumpDirectory();
        OutputAnalyzer output = LockCoarseningTestUtils.runChildWithDump(
                TestLockCoarseningLoop.class, "loop", 1, dumpDirectory, "sum");
        output.shouldHaveExitValue(0);
    }

    private static void runChild() {
        TestLockCoarseningLoop test = new TestLockCoarseningLoop(41);
        int result = 0;
        for (int i = 0; i < 10_000; i++) {
            result = test.loop(8);
        }
        sink = result;
        // loop(8) starts at value and performs sixteen sum calls, each adding
        // the same receiver value: value + 16 * value.
        int expected = test.value + 16 * test.value;
        Asserts.assertEquals(result, expected,
                "incorrect result after loop-local coarsening");
        Asserts.assertFalse(Thread.holdsLock(test),
                "loop-local monitor leaked after return");
    }
}
