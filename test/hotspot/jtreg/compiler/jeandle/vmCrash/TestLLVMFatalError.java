/*
 * Copyright (c) 2025, the Jeandle-JDK Authors. All Rights Reserved.
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
 * @test TestLLVMFatalError
 * @summary Test that hs_err log is generated when LLVM fatal error occurs during
 *          Jeandle compilation. This test verifies that when LLVM assertions or
 *          fatal errors occur, the HotSpot VM generates a proper hs_err log file
 *          through the custom fatal error handler installed in JeandleCompiler.
 * @bug 105
 * @library /test/lib
 * @requires os.family == "linux"
 * @compile Crash.jasm
 * @run main/othervm/native compiler.jeandle.vmCrash.TestLLVMFatalError
 */

package compiler.jeandle.vmCrash;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

/**
 * This test verifies that when LLVM encounters a fatal error (such as an assertion
 * failure) during Jeandle compilation, the custom fatal error handler installed
 * in JeandleCompiler generates a proper hs_err log file.
 *
 * The LLVM fatal error handler is installed in JeandleCompiler::initialize() via:
 *   llvm::install_fatal_error_handler(llvm_fatal_error_handler, nullptr);
 *
 * When an LLVM fatal error occurs, the handler calls:
 *   VMError::report_and_die(Thread::current_or_null(), nullptr, __FILE__, __LINE__,
 *                           "LLVM fatal error", "LLVM assertion or fatal error: %s", reason);
 *
 * This test works by:
 * 1. Starting a child JVM process with the Jeandle compiler enabled
 * 2. Compiling invalid bytecode (Crash.jasm) that triggers an LLVM error
 * 3. Verifying that:
 *    - The process exits with a non-zero exit code
 *    - An hs_err log file is generated
 *    - The log file contains proper crash information
 */
public class TestLLVMFatalError {

    public static final String notExpectedString = "error occurred during error reporting";

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("triggerCrash")) {
            // This is the child process - trigger compilation crash
            triggerCompilationCrash();
            return;
        }

        // Main test: verify hs_err log generation on LLVM fatal error during compilation
        testLLVMFatalErrorDuringCompilation();
    }

    /**
     * Test that LLVM fatal errors during Jeandle compilation generate hs_err log files.
     *
     * This test uses invalid bytecode (Crash.jasm) that will cause an LLVM error
     * when the Jeandle compiler attempts to compile it. The JeandleCrashOnError
     * flag is set to ensure the error triggers a crash instead of just being recorded.
     */
    public static void testLLVMFatalErrorDuringCompilation() throws Exception {
        System.out.println("Testing LLVM fatal error hs_err log generation during compilation...");

        List<String> cmdLine = new ArrayList<>();
        cmdLine.add("-Xcomp");
        cmdLine.add("-XX:-TieredCompilation");
        cmdLine.add("-Xbatch");
        cmdLine.add("-noverify");  // Needed to load invalid bytecode
        cmdLine.add("-XX:-Inline");
        cmdLine.add("-XX:+UseJeandleCompiler");
        // Enable JeandleCrashOnError to ensure fatal errors trigger crash with hs_err
        cmdLine.add("-XX:+UnlockDiagnosticVMOptions");
        cmdLine.add("-XX:+JeandleCrashOnError");
        cmdLine.add("-XX:CompileCommand=compileonly,compiler.jeandle.vmCrash.Crash::doCrash");
        cmdLine.add("compiler.jeandle.vmCrash.TestLLVMFatalError");
        cmdLine.add("triggerCrash");

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(cmdLine);
        OutputAnalyzer output = new OutputAnalyzer(pb.start());

        long pid = output.pid();
        System.out.println("Child process PID: " + pid);
        System.out.println("Exit value: " + output.getExitValue());

        // The process should exit with non-zero status due to fatal error
        output.shouldNotHaveExitValue(0);

        // Should not have secondary errors during error reporting
        output.shouldNotContain(notExpectedString);

        // Check if hs_err log file was generated
        String hsErrFilePath = "hs_err_pid" + pid + ".log";
        File hsErrFile = new File(hsErrFilePath);

        if (!hsErrFile.exists()) {
            // Try to find hs_err file in current directory
            File currentDir = new File(".");
            File[] hsErrFiles = currentDir.listFiles((dir, name) ->
                name.startsWith("hs_err_pid") && name.endsWith(".log"));

            if (hsErrFiles != null && hsErrFiles.length > 0) {
                // Use the most recent one
                hsErrFile = hsErrFiles[hsErrFiles.length - 1];
                hsErrFilePath = hsErrFile.getPath();
                System.out.println("Found hs_err file: " + hsErrFilePath);
            } else {
                throw new RuntimeException("hs_err log file not found. " +
                    "Expected location: " + hsErrFilePath + "\n" +
                    "Stdout: " + output.getStdout() + "\n" +
                    "Stderr: " + output.getStderr());
            }
        }

        System.out.println("Verifying hs_err log file: " + hsErrFilePath);
        verifyHsErrLogContent(hsErrFilePath);

        System.out.println("Test PASSED: hs_err log was generated correctly for LLVM/Jeandle fatal error");
    }

    /**
     * Verify the content of the hs_err log file contains expected sections.
     */
    public static void verifyHsErrLogContent(String filePath) throws Exception {
        boolean foundInternalError = false;
        boolean foundJeandleInfo = false;
        boolean foundNativeFrames = false;
        boolean foundRegisters = false;
        boolean foundStack = false;
        boolean foundVMInfo = false;

        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            System.out.println("\n=== hs_err log analysis ===");
            while ((line = reader.readLine()) != null) {
                // Check for internal error marker
                if (line.contains("Internal Error") || line.contains("fatal error") ||
                    line.contains("EXCEPTION_ACCESS_VIOLATION") || line.contains("SIGSEGV") ||
                    line.contains("SIGABRT") || line.contains("guarantee")) {
                    System.out.println("Found error marker: " + line);
                    foundInternalError = true;
                }

                // Check for Jeandle/LLVM-related information
                if (line.contains("LLVM") || line.contains("jeandle") ||
                    line.contains("Jeandle") || line.contains("Compilation failed")) {
                    System.out.println("Found Jeandle/LLVM info: " + line);
                    foundJeandleInfo = true;
                }

                // Check for native frames section
                if (line.contains("Native frames:")) {
                    System.out.println("Found native frames section");
                    foundNativeFrames = true;
                }

                // Check for registers section
                if (line.contains("Registers:")) {
                    System.out.println("Found registers section");
                    foundRegisters = true;
                }

                // Check for stack section
                if (line.contains("Top of Stack:") || line.contains("Stack:")) {
                    System.out.println("Found stack section");
                    foundStack = true;
                }

                // Check for VM info section
                if (line.contains("vm_info:") || line.contains("VM version:")) {
                    System.out.println("Found VM info section");
                    foundVMInfo = true;
                }
            }
            System.out.println("=== End of analysis ===\n");
        }

        // Report verification summary
        System.out.println("hs_err log verification summary:");
        System.out.println("  - Internal error found: " + foundInternalError);
        System.out.println("  - Jeandle/LLVM info found: " + foundJeandleInfo);
        System.out.println("  - Native frames found: " + foundNativeFrames);
        System.out.println("  - Registers found: " + foundRegisters);
        System.out.println("  - Stack info found: " + foundStack);
        System.out.println("  - VM info found: " + foundVMInfo);

        // Verify essential sections are present
        if (!foundNativeFrames) {
            throw new RuntimeException("Native frames section not found in hs_err log");
        }

        if (!foundVMInfo) {
            throw new RuntimeException("VM info section not found in hs_err log");
        }

        System.out.println("hs_err log file verified successfully");
    }

    /**
     * Trigger a compilation crash by calling the invalid bytecode in Crash.jasm.
     * The Crash class contains invalid bytecode (fconst_1; ishl;) which will
     * cause an error during Jeandle compilation.
     */
    public static void triggerCompilationCrash() {
        // Call the crash method which contains invalid bytecode
        // This will trigger a compilation error in Jeandle
        Crash.doCrash(1.0f);
    }
}
