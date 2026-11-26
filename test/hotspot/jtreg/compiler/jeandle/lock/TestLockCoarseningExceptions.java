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
 * @summary Coarsening across a potentially throwing synchronized inlinee must
 *          release the retained lock on unwind
 * @library /test/lib
 * @build compiler.jeandle.lock.LockCoarseningTestUtils jdk.test.lib.Asserts
 * @run driver compiler.jeandle.lock.TestLockCoarseningExceptions
 */

package compiler.jeandle.lock;

import java.nio.file.Path;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;

public class TestLockCoarseningExceptions {
    private static volatile int sink;

    private final int value;

    private TestLockCoarseningExceptions(int value) {
        this.value = value;
    }

    private synchronized int sum(int input) {
        return value + input;
    }

    private synchronized int throwingSum(int input, boolean throwIt) {
        if (throwIt) {
            throw new RuntimeException("expected test exception");
        }
        return value + input;
    }

    // The second region can throw.  If its acquisition is folded into an
    // earlier region, the exceptional edge must still release the retained
    // BasicLock before control reaches the catch block.
    private int exceptionChain(boolean throwIt) {
        int result = sum(value);
        try {
            result = throwingSum(result, throwIt);
            result = sum(result);
            return result;
        } catch (RuntimeException expected) {
            return -1;
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 0 && args[0].equals("child")) {
            runChild();
            return;
        }

        Path dumpDirectory = LockCoarseningTestUtils.createDumpDirectory();
        OutputAnalyzer output = LockCoarseningTestUtils.runChildWithDump(
                TestLockCoarseningExceptions.class, "exceptionChain", 1,
                dumpDirectory, "sum", "throwingSum");
        output.shouldHaveExitValue(0);
        LockCoarseningTestUtils.assertOneCoarsenedRegion(
                dumpDirectory, TestLockCoarseningExceptions.class,
                "exceptionChain");
    }

    private static void runChild() {
        TestLockCoarseningExceptions test = new TestLockCoarseningExceptions(41);
        int result = 0;
        for (int i = 0; i < LockCoarseningTestUtils.ITERATIONS; i++) {
            result = test.exceptionChain(false);
        }
        sink = result;
        Asserts.assertEquals(result, 164,
                "incorrect result on the non-throwing path");

        Asserts.assertEquals(test.exceptionChain(true), -1,
                "exception was not observed after coarsening");
        Asserts.assertFalse(Thread.holdsLock(test),
                "retained monitor leaked after exceptional unwind");

        // A subsequent call rules out a permanently corrupted lock state.
        Asserts.assertEquals(test.exceptionChain(false), 164,
                "lock state was corrupted after exceptional unwind");
    }
}
