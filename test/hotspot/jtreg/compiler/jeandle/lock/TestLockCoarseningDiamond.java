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
 * @summary A fully-redundant diamond acquires the same lock on every path and
 *          must be reduced to one monitor region
 * @library /test/lib
 * @build compiler.jeandle.lock.LockCoarseningTestUtils jdk.test.lib.Asserts
 * @run driver compiler.jeandle.lock.TestLockCoarseningDiamond
 */

package compiler.jeandle.lock;

import java.nio.file.Path;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;

public class TestLockCoarseningDiamond {
    private static volatile int sink;

    private final int value;

    private TestLockCoarseningDiamond(int value) {
        this.value = value;
    }

    private synchronized int sum(int input) {
        return value + input;
    }

    private int diamond(boolean p) {
        int result = sum(value);
        if (p) {
            result = sum(result);
        } else {
            result = sum(result);
        }
        return sum(result);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 0 && args[0].equals("child")) {
            runChild();
            return;
        }

        Path dumpDirectory = LockCoarseningTestUtils.createDumpDirectory();
        OutputAnalyzer output = LockCoarseningTestUtils.runChildWithDump(
                TestLockCoarseningDiamond.class, "diamond", 1, dumpDirectory,
                "sum");
        output.shouldHaveExitValue(0);
        LockCoarseningTestUtils.assertOneCoarsenedRegion(
                dumpDirectory, TestLockCoarseningDiamond.class, "diamond");
    }

    private static void runChild() {
        TestLockCoarseningDiamond test = new TestLockCoarseningDiamond(41);
        int result = 0;
        for (int i = 0; i < LockCoarseningTestUtils.ITERATIONS; i++) {
            result = test.diamond((i & 1) == 0);
        }
        sink = result;
        Asserts.assertEquals(result, 164,
                "incorrect result after fully-redundant diamond");
    }
}
