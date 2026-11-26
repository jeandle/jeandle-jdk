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
 * @summary Nested synchronized regions introduced by inlining on the same
 *          receiver must be folded into one outer monitor region
 * @library /test/lib
 * @build compiler.jeandle.lock.LockCoarseningTestUtils jdk.test.lib.Asserts
 * @run driver compiler.jeandle.lock.TestLockCoarseningNested
 */

package compiler.jeandle.lock;

import java.nio.file.Path;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;

public class TestLockCoarseningNested {
    private static volatile int sink;

    private final int defaultValue;

    private TestLockCoarseningNested(int defaultValue) {
        this.defaultValue = defaultValue;
    }

    // The inner synchronized call is inlined into the outer region.  The
    // reentrant acquisition on the same receiver must be folded into the
    // outer monitor.
    private int nested() {
        int result = defaultValue << 1;
        synchronized (this) {
            result += 1;
            result = nestedInner(result);
        }
        return result;
    }

    private synchronized int nestedInner(int value) {
        return value + 1;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 0 && args[0].equals("child")) {
            runChild();
            return;
        }

        Path dumpDirectory = LockCoarseningTestUtils.createDumpDirectory();
        OutputAnalyzer output = LockCoarseningTestUtils.runChildWithDump(
                TestLockCoarseningNested.class, "nested", 1, dumpDirectory,
                "nestedInner");
        output.shouldHaveExitValue(0);
        LockCoarseningTestUtils.assertOneCoarsenedRegion(
                dumpDirectory, TestLockCoarseningNested.class, "nested");
    }

    private static void runChild() {
        TestLockCoarseningNested test = new TestLockCoarseningNested(41);
        int result = 0;
        for (int i = 0; i < LockCoarseningTestUtils.ITERATIONS; i++) {
            result = test.nested();
        }
        sink = result;
        Asserts.assertEquals(result, 84,
                "incorrect result after nested lock coarsening");
        Asserts.assertFalse(Thread.holdsLock(test),
                "nested monitor leaked after coarsening");
    }
}
