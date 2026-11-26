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
 * @summary Benchmark-shaped conditional synchronized chains must preserve
 *          Java synchronization semantics; CFG shape is covered separately by
 *          TestLockCoarseningDiamond
 * @library /test/lib
 * @build compiler.jeandle.lock.LockCoarseningTestUtils jdk.test.lib.Asserts
 * @run driver compiler.jeandle.lock.TestLockCoarseningConditional
 */

package compiler.jeandle.lock;

import java.nio.file.Path;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;

public class TestLockCoarseningConditional {
    private static volatile int sink;

    private final int value;

    private TestLockCoarseningConditional(int value) {
        this.value = value;
    }

    private synchronized int sum(int input) {
        return value + input;
    }

    // Mirrors LockCoarseningBenchmark.conditional_chain_method_calls.  The
    // runtime calls use p == true, matching the benchmark's hot path.
    private int conditionalChain(boolean p) {
        int result = value * 2;
        if (p) {
            result = sum(result);
        }
        if (p) {
            result = sum(result);
        }
        if (p) {
            result = sum(result);
        }
        if (p) {
            result = sum(result);
        }
        if (p) {
            result = sum(result);
        }
        if (p) {
            result = sum(result);
        }
        if (p) {
            result = sum(result);
        }
        if (p) {
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
                TestLockCoarseningConditional.class, "conditionalChain", 1,
                dumpDirectory, "sum");
        output.shouldHaveExitValue(0);
    }

    private static void runChild() {
        TestLockCoarseningConditional test = new TestLockCoarseningConditional(41);
        int result = 0;
        for (int i = 0; i < LockCoarseningTestUtils.ITERATIONS; i++) {
            result = test.conditionalChain(true);
        }
        sink = result;
        Asserts.assertEquals(result, 410,
                "incorrect result after conditional chain");
        Asserts.assertEquals(test.conditionalChain(false), 82,
                "false conditional path must not acquire a monitor");
    }
}
