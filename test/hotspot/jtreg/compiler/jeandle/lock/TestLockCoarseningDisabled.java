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
 * @summary Disabling lock coarsening must preserve behavior and leave the
 *          synchronized inlinees with distinct BasicLock slots
 * @library /test/lib
 * @build compiler.jeandle.lock.LockCoarseningTestUtils jdk.test.lib.Asserts
 * @run driver compiler.jeandle.lock.TestLockCoarseningDisabled
 */

package compiler.jeandle.lock;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestLockCoarseningDisabled {
    private static volatile int sink;

    private final int value;

    private TestLockCoarseningDisabled(int value) {
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
        List<String> command = new ArrayList<>(List.of(
                "-Xbatch", "-XX:-TieredCompilation",
                "-XX:-BackgroundCompilation", "-XX:-UseOnStackReplacement",
                "-XX:+UseJeandleCompiler", "-XX:-JeandleLockCoarsening",
                "-XX:+UnlockExperimentalVMOptions", "-XX:LockingMode=1",
                "-XX:CompileThreshold=100",
                "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dumpDirectory,
                "-XX:CompileCommand=compileonly," + TestLockCoarseningDisabled.class.getName() + "::chain",
                "-XX:CompileCommand=dontinline," + TestLockCoarseningDisabled.class.getName() + "::chain",
                "-XX:CompileCommand=inline," + TestLockCoarseningDisabled.class.getName() + "::sum",
                TestLockCoarseningDisabled.class.getName(), "child"));
        OutputAnalyzer output = ProcessTools.executeTestJava(
                command.toArray(String[]::new));
        output.shouldHaveExitValue(0);

        Path dump = LockCoarseningTestUtils.findOptimizedDump(
                dumpDirectory, TestLockCoarseningDisabled.class, "chain");
        String ir = LockCoarseningTestUtils.read(dump);
        Asserts.assertGreaterThan(
                LockCoarseningTestUtils.countBasicLockSlots(ir), 1L,
                "disabled coarsening unexpectedly replaced the inlinee locks");
    }

    private static void runChild() {
        TestLockCoarseningDisabled test = new TestLockCoarseningDisabled(41);
        int result = 0;
        for (int i = 0; i < LockCoarseningTestUtils.ITERATIONS; i++) {
            result = test.chain();
        }
        sink = result;
        Asserts.assertEquals(result, 410,
                "disabled coarsening changed chain behavior");
    }
}
