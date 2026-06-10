/**
 * @test
 * @summary Tests whether the Jeandle compiler correctly handles asynchronous exceptions at a return poll.
 * @run main/othervm/native -agentlib:TestJeandleAsyncExceptionHandleAtSafepoint
 *      -Xbatch -Xcomp -XX:+UnlockDiagnosticVMOptions
 *      -XX:-TieredCompilation -XX:+UseJeandleCompiler -XX:-Inline
 *      -XX:+ExplicitGCInvokesConcurrent -XX:GuaranteedSafepointInterval=1
 *      -XX:CompileCommand=compileonly,compiler.jeandle.safepoint.TestJeandleAsyncExceptionHandleAtSafepoint::returnPoll
 *      -XX:CompileCommand=dontinline,compiler.jeandle.safepoint.TestJeandleAsyncExceptionHandleAtSafepoint::returnPoll
 *      -XX:CompileCommand=exclude,compiler.jeandle.safepoint.TestJeandleAsyncExceptionHandleAtSafepoint::runReturnPollLoop
 *      -XX:+LogCompilation -XX:LogFile=compilation.log
 *      compiler.jeandle.safepoint.TestJeandleAsyncExceptionHandleAtSafepoint
 */

package compiler.jeandle.safepoint;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public class TestJeandleAsyncExceptionHandleAtSafepoint {
    private static final int JVMTI_ERROR_NONE = 0;
    private static final String COMPILATION_LOG_FILE = "compilation.log";
    private static final Pattern UNEXPECTED_DEOPT_PATTERN = Pattern.compile("deoptimized");    

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

	// Continuously executes a method that contains a return poll.
        Thread victim = new Thread(() -> {
            try {
                runReturnPollLoop();
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
            throw new RuntimeException("victim thread did not enter loop");
        }

	Thread.sleep(500);

	// Force frequent safepoint activity while the victim
        // is executing compiled code.
        Thread buster = new Thread(() -> {
            while (!stopped) {
                System.gc();
                Thread.onSpinWait();
            }
        });

        buster.setDaemon(true);
        buster.start();

        Thread.sleep(50);

	// Inject ThreadDeath asynchronously into the victim thread.
        int err = stopThread(victim);
        if (err != JVMTI_ERROR_NONE) {
            throw new RuntimeException("StopThread failed with JVMTI error: " + err);
        }

        victim.join(10_000);

        if (victim.isAlive()) {
            throw new RuntimeException("victim thread is still alive");
        }
        if (!stopped) {
            throw new RuntimeException("ThreadDeath was not caught");
        }

	// Verify that handling the async exception did not trigger
        // an unexpected deoptimization.
        checkCompilationLogContainsExpectedDeopt();
        System.out.println("SUCCESS!");
    }

    private static void runReturnPollLoop() {
        long value = 0;

	for (int i = 0; i < 2000; i++) {
            value = returnPoll(value);
         }
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

    private static void checkCompilationLogContainsExpectedDeopt() throws Exception {
    Path log = Path.of(COMPILATION_LOG_FILE);

    if (!Files.exists(log)) {
        throw new RuntimeException(
                "Compilation log file does not exist: " + COMPILATION_LOG_FILE);
    }

    String content = Files.readString(log);

    if (UNEXPECTED_DEOPT_PATTERN.matcher(content).find()) {
        throw new RuntimeException(
                "Unexpected deopt pattern found in compilation log.");
    }
}
}
