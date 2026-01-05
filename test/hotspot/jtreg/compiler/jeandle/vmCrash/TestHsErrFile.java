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
 * @test
 * @library /test/lib
 * @modules java.base/jdk.internal.misc
 * @requires os.family == "linux"
 * @compile NativeThreadHolder.java Crash.jasm
 * @run main/othervm/native compiler.jeandle.vmCrash.TestHsErrFile
 */

package compiler.jeandle.vmCrash;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import jdk.internal.misc.Unsafe;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestHsErrFile {
    public static int n = 0;
    public static final String notExpectedString = "error occurred during error reporting";

    public static void main(String[] args) throws Exception {
        if (args.length == 1) {
            makeProcess(args[0]);
            return;
        }

        long pid;
        String hs_err_log;
        String expected;
        String anchor = "Native frames: (J=compiled Java code, j=interpreted, Vv=VM code, C=native code)";

        pid = crashInJeandleCompiledCode();
        expected = 
            """
            J  jeandle compiler.jeandle.vmCrash.TestHsErrFile.enterLoop()V (7 bytes)
            j  compiler.jeandle.vmCrash.TestHsErrFile.javaMethodInnerWrapper()V
            J  jeandle compiler.jeandle.vmCrash.TestHsErrFile.javaMethodOuterWrapper()V (4 bytes)
            j  compiler.jeandle.vmCrash.TestHsErrFile.lambda$makeProcess
            j  compiler.jeandle.vmCrash.TestHsErrFile$$Lambda
            j  java.lang.Thread.runWith(Ljava/lang/Object;Ljava/lang/Runnable;)V
            j  java.lang.Thread.run()V
            """;
        checkHsErrFile("hs_err_pid" + pid + ".log", anchor, expected);

        pid = crashInJeandleCompilation();
        checkHsErrFile("hs_err_pid" + pid + ".log");

        pid = crashInNativeCode();
        checkHsErrFile("hs_err_pid" + pid + ".log");
    }

    public static long crashInJeandleCompiledCode() throws Exception {
        List<String> cmdLine = new ArrayList<>();
        cmdLine.add("-Xcomp");
        cmdLine.add("-XX:-TieredCompilation");
        cmdLine.add("-Xbatch");
        cmdLine.add("-XX:-Inline");
        cmdLine.add("-XX:CompileCommand=compileonly,compiler.jeandle.vmCrash.TestHsErrFile::javaMethodOuterWrapper");
        cmdLine.add("-XX:CompileCommand=compileonly,compiler.jeandle.vmCrash.TestHsErrFile::enterLoop");
        cmdLine.add("-XX:+UseJeandleCompiler");
        cmdLine.add("compiler.jeandle.vmCrash.TestHsErrFile");
        cmdLine.add("crashInJeandleCompiledCode");
        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(cmdLine);
        OutputAnalyzer output = new OutputAnalyzer(pb.start());
        output.shouldNotHaveExitValue(0);
        output.shouldNotContain(notExpectedString);
        return output.pid();
    }

    public static long crashInJeandleCompilation() throws Exception {
        List<String> cmdLine = new ArrayList<>();
        cmdLine.add("-Xcomp");
        cmdLine.add("-XX:-TieredCompilation");
        cmdLine.add("-Xbatch");
        cmdLine.add("-noverify");
        cmdLine.add("-XX:-Inline");
        cmdLine.add("-XX:CompileCommand=compileonly,compiler.jeandle.vmCrash.Crash::doCrash");
        cmdLine.add("-XX:+UseJeandleCompiler");
        cmdLine.add("compiler.jeandle.vmCrash.TestHsErrFile");
        cmdLine.add("crashInJeandleCompilation");
        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(cmdLine);
        OutputAnalyzer output = new OutputAnalyzer(pb.start());
        output.shouldNotHaveExitValue(0);
        output.shouldNotContain(notExpectedString);
        return output.pid();
    }

    public static long crashInNativeCode() throws Exception {
        List<String> cmdLine = new ArrayList<>();
        cmdLine.add("-Xcomp");
        cmdLine.add("-XX:-TieredCompilation");
        cmdLine.add("-Xbatch");
        cmdLine.add("-XX:-Inline");
        cmdLine.add("-XX:CompileCommand=compileonly,compiler.jeandle.vmCrash.TestHsErrFile::nativeMethodWrapper");
        cmdLine.add("-XX:+UseJeandleCompiler");
        cmdLine.add("compiler.jeandle.vmCrash.TestHsErrFile");
        cmdLine.add("crashInNativeCode");
        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(cmdLine);
        OutputAnalyzer output = new OutputAnalyzer(pb.start());
        output.shouldNotHaveExitValue(0);
        output.shouldNotContain(notExpectedString);
        return output.pid();
    }

    public static void makeProcess(String type) throws Exception {
        if ("crashInJeandleCompiledCode".equals(type)) {
            System.loadLibrary("NativeThreadHolder");

            NativeThreadHolder nth = new NativeThreadHolder();
            Thread t = new Thread(() -> {
                nth.setpThreadId(NativeThreadHolder.getID());
                javaMethodOuterWrapper();
            });
            nth.setThread(t);
            nth.start();
            while (n == 0) {
                Thread.sleep(500);
            }
            NativeThreadHolder.signal(nth.pThreadId(), NativeThreadHolder.Signal.SIGSEGV.getNumber());
        } else if ("crashInJeandleCompilation".equals(type)) {
            Thread t = new Thread(() -> {
                crash();
            });
            t.start();
        } else if ("crashInNativeCode".equals(type)) {
            System.loadLibrary("TestHsErrFile");
            Thread t = new Thread(() -> {
                nativeMethodWrapper();
            });
            t.start();
        } else {
            throw new RuntimeException("Unexpected type in makeProcess");
        }
    }

    public static void checkHsErrFile(String filePath) throws Exception {
        if (!new File(filePath).exists()) {
            throw new RuntimeException("File " + filePath + " not found");
        }
    }

    public static void checkHsErrFile(String filePath, String anchor, String expected) throws Exception {
        checkHsErrFile(filePath);

        String registersInfoTitle = "Registers:";
        String stackInfoTitle = "Top of Stack:";
        String[] expectedArray = expected.split("\n");
        int index = 0;

        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            boolean isAnchorFound = false;
            boolean isFrameMatched = false;
            boolean isRegistersInfoFound = false;
            boolean isStackInfoFound = false;
            System.out.println("Check " + filePath + ":");
            while ((line = reader.readLine()) != null) {
                if (isAnchorFound && !isFrameMatched) {
                    // check frame type: J, j, V, v, C
                    char expected_type = expectedArray[index].charAt(0);
                    char frame_type = line.charAt(0);
                    if (expected_type != frame_type) {
                        throw new RuntimeException(filePath + " match failed. Expected frame type is: \"" + expected_type + "\", but got: \"" + line + "\"");
                    }

                    // check method
                    String expected_frame = expectedArray[index].substring(3);
                    if (!line.contains(expected_frame)) {
                        throw new RuntimeException(filePath + " match failed. Expected: \"" + expected_frame + "\", but got: \"" + line + "\"");
                    }

                    System.out.println("    " + line);
                    if (++index == expectedArray.length) {
                        isFrameMatched = true;
                    }
                } else if (line.contains(registersInfoTitle)) {
                    System.out.println("RegistersInfo found.");
                    isRegistersInfoFound = true;
                } else if (line.contains(stackInfoTitle)) {
                    System.out.println("StackInfo found.");
                    isStackInfoFound = true;
                    break;
                } else if (line.equals(anchor)) {
                    isAnchorFound = true;
                }
            }
            if (!isFrameMatched) {
                throw new RuntimeException(filePath + " frame info match failed");
            }
            if (!isRegistersInfoFound) {
                throw new RuntimeException(filePath + " registers info not found");
            }
            if (!isStackInfoFound) {
                throw new RuntimeException(filePath + " stack info not found");
            }
            System.out.println("File " + filePath + " matched successfully\n");
        }
    }

    public static void javaMethodOuterWrapper() {
        javaMethodInnerWrapper();
    }

    public static void javaMethodInnerWrapper() {
        enterLoop();
    }

    public static void enterLoop() {
        while (true) {
            n = 1;
        }
    }

    public static void crash() {
        Crash.doCrash(1.0f);
    }

    public static void nativeMethodWrapper() {
        crashInNative();
    }

    public static native void crashInNative();
}
