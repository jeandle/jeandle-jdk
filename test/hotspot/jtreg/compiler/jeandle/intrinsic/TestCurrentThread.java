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
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 */

/*
 * @test
 * @summary Test the intrinsic implementation of Thread.currentThread()
 * @library /test/lib /
 * @build jdk.test.lib.Asserts
 * @run main/othervm compiler.jeandle.intrinsic.TestCurrentThread
 */

package compiler.jeandle.intrinsic;

import compiler.jeandle.fileCheck.FileCheck;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestCurrentThread {
    public static void main(String[] args) throws Exception {
        String dumpPath = Files.createTempDirectory("jeandle_test_currentthread").toString();

        ArrayList<String> commandArgs = new ArrayList<>(List.of(
                "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
                "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dumpPath,
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::currentThread_of",
                TestWrapper.class.getName()));

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(commandArgs);
        OutputAnalyzer output = ProcessTools.executeCommand(pb);

        output.shouldHaveExitValue(0)
              .shouldContain("is parsed as intrinsic");

        // Verify the jeandle.current_thread_obj JavaOp is present in the IR
        FileCheck checker = new FileCheck(dumpPath,
                TestWrapper.class.getMethod("currentThread_of"), false);
        checker.checkPattern("jeandle\\.current_thread_obj");
    }

    static class TestWrapper {
        public static void main(String[] args) throws Exception {
            // Platform thread: the compiled currentThread_of() must return the
            // executing (carrier) thread.
            Thread platformThread = Thread.currentThread();
            Asserts.assertTrue(currentThread_of() == platformThread,
                    "Thread.currentThread() on a platform thread should return that thread");

            // Virtual thread: the compiled currentThread_of() must return the
            // virtual Thread itself, NOT its carrier. This is the assertion that
            // distinguishes reading JavaThread::_vthread (correct) from
            // _threadObj (would return the carrier).
            Thread[] seenOnVirtual = new Thread[1];
            Thread vthread = Thread.startVirtualThread(() -> {
                seenOnVirtual[0] = currentThread_of();
            });
            vthread.join();
            Asserts.assertTrue(seenOnVirtual[0] == vthread,
                    "Thread.currentThread() on a virtual thread should return the virtual thread");
            Asserts.assertFalse(seenOnVirtual[0] == platformThread,
                    "Thread.currentThread() on a virtual thread must not return the carrier thread");

            System.out.println("TestCurrentThread PASSED");
        }

        public static Thread currentThread_of() {
            return Thread.currentThread();
        }
    }
}
