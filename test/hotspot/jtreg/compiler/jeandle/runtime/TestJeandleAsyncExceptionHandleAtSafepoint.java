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
 *      -Xbatch -Xcomp -XX:-TieredCompilation -XX:+UseJeandleCompiler -XX:-Inline
 *      -XX:+ExplicitGCInvokesConcurrent -XX:GuaranteedSafepointInterval=1
 *      -XX:CompileCommand=compileonly,compiler.jeandle.safepoint.TestJeandleAsyncExceptionHandleAtSafepoint::returnPoll
 *      -XX:CompileCommand=dontinline,compiler.jeandle.safepoint.TestJeandleAsyncExceptionHandleAtSafepoint::returnPoll
 *      -XX:CompileCommand=compileonly,compiler.jeandle.safepoint.TestJeandleAsyncExceptionHandleAtSafepoint::runReturnPoll
 *      -XX:CompileCommand=dontinline,compiler.jeandle.safepoint.TestJeandleAsyncExceptionHandleAtSafepoint::runReturnPoll
 *      -Xlog:exceptions=info:file=exceptions.log
 *      compiler.jeandle.safepoint.TestJeandleAsyncExceptionHandleAtSafepoint
 */

package compiler.jeandle.safepoint;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public class TestJeandleAsyncExceptionHandleAtSafepoint {

    private static final int JVMTI_ERROR_NONE = 0;

    private static final String EXCEPTIONS_LOG_FILE = "exceptions.log";

    private static final Pattern THREAD_DEATH_IN_INTERPRETER_PATTERN =
            Pattern.compile(
                    "Exception <a 'java/lang/ThreadDeath'.*?"
                  + "thrown in interpreter method .*?"
                  + "'(?:returnPoll|runReturnPoll)' "
                  + "'\\((?:J\\)J|\\)V)'",
                    Pattern.DOTALL);

    private static volatile boolean entered;
    private static volatile boolean stopped;
    private static volatile long sink;

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
                runReturnPoll();
            } catch (ThreadDeath td) {
                stopped = true;
                System.out.println("caught ThreadDeath");
            }
        }, "async-exception-victim");

        victim.setDaemon(true);
        victim.start();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

        while (!entered && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }

        if (!entered) {
            throw new RuntimeException("Victim thread did not enter loop");
        }

        Thread.sleep(500);

        int err = stopThread(victim);

        if (err != JVMTI_ERROR_NONE) {
            throw new RuntimeException(
                    "StopThread failed with JVMTI error: " + err);
        }

        victim.join(10_000);

        if (victim.isAlive()) {
            throw new RuntimeException("Victim thread is still alive");
        }

        if (!stopped) {
            throw new RuntimeException("ThreadDeath was not caught");
        }

        checkExceptionsLog();

        System.out.println("SUCCESS!");
    }

    private static void runReturnPoll() {
        long value = 0;

        entered = true;

        while (!stopped) {
            value = returnPoll(value);
            value = returnPoll(value);
            value = returnPoll(value);
            value = returnPoll(value);
            value = returnPoll(value);
            value = returnPoll(value);
            value = returnPoll(value);
            value = returnPoll(value);

            sink = value;
        }
    }

    private static long returnPoll(long value) {
        return value + 42;
    }

    /**
     * Verify that ThreadDeath was not handled in the interpreter.
     */
    private static void checkExceptionsLog() throws Exception {
        Path log = Path.of(EXCEPTIONS_LOG_FILE);

        if (!Files.exists(log)) {
            throw new RuntimeException(
                    "Exception log file does not exist: "
                    + EXCEPTIONS_LOG_FILE);
        }

        String content = Files.readString(log);

        if (THREAD_DEATH_IN_INTERPRETER_PATTERN.matcher(content).find()) {
            throw new RuntimeException(
                    "Found ThreadDeath handled in interpreter execution");
        }
    }
}
