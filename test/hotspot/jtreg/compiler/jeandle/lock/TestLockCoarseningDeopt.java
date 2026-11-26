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
 * @summary Deoptimization inside a coarsened region must restore and release
 *          the retained BasicLock
 * @library /test/lib
 * @build compiler.jeandle.lock.LockCoarseningTestUtils jdk.test.lib.Asserts
 * @run driver compiler.jeandle.lock.TestLockCoarseningDeopt
 */

package compiler.jeandle.lock;

import java.nio.file.Path;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;

public class TestLockCoarseningDeopt {
    private static volatile int sink;

    private final int value;

    private TestLockCoarseningDeopt(int value) {
        this.value = value;
    }

    private synchronized int sum(int input) {
        return value + input;
    }

    private synchronized int stringLength(Object object) {
        return ((String) object).length() + value;
    }

    // Warm calls pass a String.  A later call with an Integer reaches the
    // inlined cast while the synchronized region is active and forces the
    // compiled method to deoptimize.  The deopt metadata must name the retained
    // BasicLock if an earlier region was coarsened into this one.
    private int deoptChain(Object object) {
        int result = sum(value);
        result = sum(result);
        return result + stringLength(object);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 0 && args[0].equals("child")) {
            runChild();
            return;
        }

        Path dumpDirectory = LockCoarseningTestUtils.createDumpDirectory();
        OutputAnalyzer output = LockCoarseningTestUtils.runChildWithDump(
                TestLockCoarseningDeopt.class, "deoptChain", 1,
                dumpDirectory, "sum", "stringLength");
        output.shouldHaveExitValue(0);
        // The deoptimizing cast ends the mergeable chain.  The two arithmetic
        // regions must still be folded into one slot, while the deopt-bearing
        // region may retain its own slot; the runtime phase below verifies the
        // rewritten deopt bundle and monitor release.
        LockCoarseningTestUtils.assertCoarsenedSlotCount(
                dumpDirectory, TestLockCoarseningDeopt.class, "deoptChain",
                1L, 2L);
    }

    private static void runChild() {
        TestLockCoarseningDeopt test = new TestLockCoarseningDeopt(41);
        String warm = "0123456789";
        int result = 0;
        for (int i = 0; i < LockCoarseningTestUtils.ITERATIONS; i++) {
            result = test.deoptChain(warm);
        }
        sink = result;
        Asserts.assertEquals(result, 174,
                "incorrect result after warmed deopt chain");

        boolean deoptimized = false;
        try {
            test.deoptChain(Integer.valueOf(7));
        } catch (ClassCastException expected) {
            deoptimized = true;
        }
        Asserts.assertTrue(deoptimized,
                "expected the inlined cast to deoptimize and throw");
        Asserts.assertFalse(Thread.holdsLock(test),
                "retained monitor leaked after deoptimization");

        Asserts.assertEquals(test.deoptChain(warm), 174,
                "lock state was corrupted after deoptimization");
    }
}
