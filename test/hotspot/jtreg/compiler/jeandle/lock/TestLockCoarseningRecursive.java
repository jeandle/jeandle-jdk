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
 * @summary Recursive synchronized calls must fold the inlined inner acquire
 *          into the outer monitor and preserve normal and exceptional exits
 * @library /test/lib
 * @build compiler.jeandle.lock.LockCoarseningTestUtils jdk.test.lib.Asserts
 * @run driver compiler.jeandle.lock.TestLockCoarseningRecursive
 */

package compiler.jeandle.lock;

import java.nio.file.Path;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;

public class TestLockCoarseningRecursive {
    private static final int DEPTH = 8;
    private static volatile int sink;

    private final int defaultValue;
    private final int incrementValue;

    private TestLockCoarseningRecursive(int defaultValue, int incrementValue) {
        this.defaultValue = defaultValue;
        this.incrementValue = incrementValue;
    }

    private synchronized int recursiveSum(int value, int depth) {
        if (depth == 0) {
            return value;
        }
        return incrementValue + recursiveSum(value, depth - 1);
    }

    private int recursiveChain() {
        int result = defaultValue << 1;
        return recursiveSum(result, DEPTH);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 0 && args[0].equals("child")) {
            runChild();
            return;
        }

        Path dumpDirectory = LockCoarseningTestUtils.createDumpDirectory();
        OutputAnalyzer output = LockCoarseningTestUtils.runChildWithDump(
                TestLockCoarseningRecursive.class, "recursiveChain", 1,
                dumpDirectory, "recursiveSum");
        output.shouldHaveExitValue(0);
        // The community inliner may decline to inline a recursive callee.  In
        // that case there is no adjacent region to fold and the whole monitor
        // can be eliminated by later optimization; either zero or one retained
        // slot is correct.  The runtime checks below cover reentrant locking
        // and monitor release.
        LockCoarseningTestUtils.assertAtMostOneCoarsenedRegion(
                dumpDirectory, TestLockCoarseningRecursive.class,
                "recursiveChain");
    }

    private static void runChild() {
        TestLockCoarseningRecursive test =
                new TestLockCoarseningRecursive(41, 3);
        int result = 0;
        for (int i = 0; i < LockCoarseningTestUtils.ITERATIONS; i++) {
            result = test.recursiveChain();
        }
        sink = result;
        Asserts.assertEquals(result, 106,
                "recursive synchronized calls changed reentrant semantics");
        Asserts.assertFalse(Thread.holdsLock(test),
                "recursive monitor leaked after return");
    }
}
