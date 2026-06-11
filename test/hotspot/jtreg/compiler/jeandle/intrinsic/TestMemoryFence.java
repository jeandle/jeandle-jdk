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
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if a Free Software Foundation, write to the
 * Free Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA.
 *
 */

/*
 * @test
 * @summary Test the intrinsic implementation of Unsafe.loadFence, storeFence, and fullFence
 * @modules java.base/jdk.internal.misc
 * @library /test/lib /
 * @build jdk.test.lib.Asserts
 * @run main/othervm compiler.jeandle.intrinsic.TestMemoryFence
 */

package compiler.jeandle.intrinsic;

import compiler.jeandle.fileCheck.FileCheck;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import jdk.internal.misc.Unsafe;
import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestMemoryFence {
    public static void main(String[] args) throws Exception {
        String dumpPath = Files.createTempDirectory("jeandle_test_fence").toString();

        ArrayList<String> commandArgs = new ArrayList<>(List.of(
                "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
                "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
                "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dumpPath,
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::testLoadFence",
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::testStoreFence",
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::testFullFence",
                TestWrapper.class.getName()));

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(commandArgs);
        OutputAnalyzer output = ProcessTools.executeCommand(pb);

        output.shouldHaveExitValue(0)
              .shouldContain("is parsed as intrinsic");

        // Verify fence IR — only check the semantic ordering, not exact syntax
        FileCheck loadFenceChecker = new FileCheck(dumpPath,
                TestWrapper.class.getMethod("testLoadFence"), false);
        loadFenceChecker.checkPattern("fence acquire");

        FileCheck storeFenceChecker = new FileCheck(dumpPath,
                TestWrapper.class.getMethod("testStoreFence"), false);
        storeFenceChecker.checkPattern("fence release");

        FileCheck fullFenceChecker = new FileCheck(dumpPath,
                TestWrapper.class.getMethod("testFullFence"), false);
        fullFenceChecker.checkPattern("fence seq_cst");
    }

    static class TestWrapper {
        static final Unsafe U = Unsafe.getUnsafe();

        public static void main(String[] args) {
            // Smoke test: verify the fences don't crash and produce correct results
            // Each fence is a no-arg void method on Unsafe, so we just call them
            testLoadFence();
            testStoreFence();
            testFullFence();

            // Functional test: simple store-load ordering with fences
            // This is a basic sanity test, not a formal memory model proof
            testFenceOrdering();

            System.out.println("TestMemoryFence PASSED");
        }

        public static void testLoadFence() {
            U.loadFence();
        }

        public static void testStoreFence() {
            U.storeFence();
        }

        public static void testFullFence() {
            U.fullFence();
        }

        // Simple multi-threaded test: writer stores a value then issues storeFence,
        // reader issues loadFence then reads the value. If fences work correctly,
        // the reader should see the written value.
        static volatile int flag = 0;
        static int data = 0;

        static void testFenceOrdering() {
            // Run multiple iterations to increase chance of catching reordering
            for (int iter = 0; iter < 1000; iter++) {
                flag = 0;
                data = 0;

                Thread writer = new Thread(() -> {
                    data = 42;        // store data
                    U.storeFence();   // ensure data store is visible before flag store
                    flag = 1;         // store flag
                });

                Thread reader = new Thread(() -> {
                    int f;
                    // Spin until flag is visible
                    while ((f = flag) == 0) {
                        Thread.yield();
                    }
                    U.loadFence(); // ensure we see data after seeing flag
                    if (f == 1 && data != 42) {
                        throw new RuntimeException("Fence ordering violated: saw flag=1 but data=" + data);
                    }
                });

                writer.start();
                reader.start();
                try {
                    writer.join();
                    reader.join();
                } catch (InterruptedException e) {
                    throw new RuntimeException("Thread interrupted", e);
                }
            }
        }
    }
}
