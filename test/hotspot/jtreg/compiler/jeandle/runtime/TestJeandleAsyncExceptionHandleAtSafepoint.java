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

/**
 * @test
 * @summary Tests whether the Jeandle compiler correctly handles asynchronous exceptions at a return poll.
 * @run main/othervm/native -agentlib:TestJeandleAsyncExceptionHandleAtSafepoint
 *      -Xbatch -Xcomp -XX:-TieredCompilation -XX:+UseJeandleCompiler -XX:-Inline -Xss512m
 *      -XX:+ExplicitGCInvokesConcurrent -XX:GuaranteedSafepointInterval=1
 *      -XX:CompileCommand=compileonly,compiler.jeandle.safepoint.TestJeandleAsyncExceptionHandleAtSafepoint::returnPoll
 *      -XX:CompileCommand=dontinline,compiler.jeandle.safepoint.TestJeandleAsyncExceptionHandleAtSafepoint::returnPoll
 *      -XX:+LogCompilation -XX:LogFile=compilation.log
 *      compiler.jeandle.safepoint.TestJeandleAsyncExceptionHandleAtSafepoint
 */

package compiler.jeandle.safepoint;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public class TestJeandleAsyncExceptionHandleAtSafepoint {

    private static class ReturnObject {
        private final int value;

        ReturnObject(int value) {
            this.value = value;
        }
    }

    private static final int JVMTI_ERROR_NONE = 0;
    private static final String COMPILATION_LOG_FILE = "compilation.log";
    private static final Pattern UNEXPECTED_DEOPT_PATTERN = Pattern.compile("deoptimized");

    private static volatile boolean entered;
    private static volatile boolean stopped = false;

    /**
     * Initializes the JVMTI test agent and configures the exception
     * that will later be injected into the target thread.
     */
    private static native void prepareAgent(Throwable exception);

    /**
     * Uses JVMTI StopThread to asynchronously throw the configured
     * exception into the specified thread.
     */
    private static native int stopThread(Thread thread);

    public static void main(String[] args) throws Exception {
        prepareAgent(new ThreadDeath());

        Thread victim = new Thread(() -> {
            try {
                ReturnObject returnObj = new ReturnObject(42);
                entered = true;
                returnPoll(true, returnObj);
            } catch (ThreadDeath td) {
                System.out.println("caught ThreadDeath");
            }
        }, "async-exception-victim");

        victim.setDaemon(true);

        // The first object creation is used to load the class.
        ReturnObject returnObj = new ReturnObject(42);
        // The first invocation is used to trigger compilation.
        returnPoll(false, returnObj);

        victim.start();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!entered && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }

        if (!entered) {
            throw new RuntimeException("Victim thread did not enter loop");
        }

        Thread.sleep(50);

        stopped = true;
        int err = stopThread(victim);
        if (err != JVMTI_ERROR_NONE) {
            throw new RuntimeException("StopThread failed with JVMTI error: " + err);
        }



        victim.join(100_000);
        if (victim.isAlive()) {
            throw new RuntimeException("Victim thread is still alive");
        }

        checkCompilationLogContainsExpectedDeopt();
        System.out.println("SUCCESS!");
    }

    private static ReturnObject returnPoll(boolean recurse, ReturnObject returnObj) {
        if (stopped) {
            return returnObj;
        }
        if (recurse) {
            returnObj = returnPoll(true, returnObj);
        }

        return returnObj;
    }

    private static void checkCompilationLogContainsExpectedDeopt() throws Exception {
        Path log = Path.of(COMPILATION_LOG_FILE);

        if (!Files.exists(log)) {
            throw new RuntimeException("Compilation log file does not exist: " + COMPILATION_LOG_FILE);
        }

        String content = Files.readString(log);

        if (UNEXPECTED_DEOPT_PATTERN.matcher(content).find()) {
            throw new RuntimeException("Unexpected deopt pattern found in compilation log.");
        }
    }
}
