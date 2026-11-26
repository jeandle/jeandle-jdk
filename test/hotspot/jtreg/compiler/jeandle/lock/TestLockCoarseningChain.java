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
 * @summary Benchmark-shaped chain of eight synchronized inlinees must retain
 *          one BasicLock and one monitor region
 * @library /test/lib
 * @build compiler.jeandle.lock.LockCoarseningTestUtils jdk.test.lib.Asserts
 * @run driver compiler.jeandle.lock.TestLockCoarseningChain
 */

package compiler.jeandle.lock;

import java.nio.file.Path;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;

public class TestLockCoarseningChain {
    private static volatile int sink;

    private final int value;

    private TestLockCoarseningChain(int value) {
        this.value = value;
    }

    private synchronized int sum(int input) {
        return value + input;
    }

    private int chain() {
        int result = value * 2;
        result = sum(result);
        result = sum(result);
        result = sum(result);
        result = sum(result);
        result = sum(result);
        result = sum(result);
        result = sum(result);
        return sum(result);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 0 && args[0].equals("child")) {
            runChild();
            return;
        }

        Path dumpDirectory = LockCoarseningTestUtils.createDumpDirectory();
        OutputAnalyzer output = LockCoarseningTestUtils.runChildWithDump(
                TestLockCoarseningChain.class, "chain", 1, dumpDirectory, "sum");
        output.shouldHaveExitValue(0);
        LockCoarseningTestUtils.assertOneCoarsenedRegion(
                dumpDirectory, TestLockCoarseningChain.class, "chain");
    }

    private static void runChild() {
        TestLockCoarseningChain test = new TestLockCoarseningChain(41);
        int result = 0;
        for (int i = 0; i < LockCoarseningTestUtils.ITERATIONS; i++) {
            result = test.chain();
        }
        sink = result;
        Asserts.assertEquals(result, 410,
                "incorrect result after chain lock coarsening");
    }
}
