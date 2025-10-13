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

package compiler.jeandle.bytecodeTranslate.calls.common;

import compiler.testlibrary.CompilerUtils;
import jdk.test.lib.Asserts;
import jdk.test.whitebox.WhiteBox;

import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * A common class for Invoke* classes
 */
public abstract class CallsBase {
    public static final String CALL_ERR_MSG = "Call insuccessfull";
    protected final Method calleeMethod;
    protected final Method callerCallNormalMethod;
    protected final Method callerCallNativeMethod;
    protected final WhiteBox wb = WhiteBox.getWhiteBox();
    protected int compileCallee = -1;
    protected int compileCaller = -1;
    protected boolean nativeCallee = false;
    protected boolean nativeCaller = false;
    protected boolean checkCallerCompilationLevel;
    protected boolean checkCalleeCompilationLevel;
    protected int expectedCallerCompilationLevel;
    protected int expectedCalleeCompilationLevel;

    protected CallsBase() {
        try {
            callerCallNormalMethod = getClass().getDeclaredMethod("callerCallNormal");
            callerCallNativeMethod = getClass().getDeclaredMethod("callerCallNative");
            calleeMethod = getClass().getDeclaredMethod("callee",
                    getCalleeParametersTypes());
            wb.testSetDontInlineMethod(callerCallNormalMethod, /* dontinline= */ true);
            wb.testSetDontInlineMethod(callerCallNativeMethod, /* dontinline= */ true);
            wb.testSetDontInlineMethod(calleeMethod, /* dontinline= */ true);
        } catch (NoSuchMethodException e) {
            throw new Error("TEST BUG: can't find test method", e);
        }
    }

    /**
     * Provides callee parameters types to search method
     * @return array of types
     */
    protected Class[] getCalleeParametersTypes() {
        return new Class[] {int.class, long.class, int.class,
            int.class, int.class};
    }

    /**
     * Loads native library(libJeandleCallsNative.so)
     */
    protected static void loadNativeLibrary() {
        System.loadLibrary("JeandleCallsNative");
    }

    /**
     * Checks if requested compilation levels are inside of current vm capabilities
     * @return true if vm is capable of requested compilation levels
     */
    protected final boolean compilationLevelsSupported() {
        int[] compLevels = CompilerUtils.getAvailableCompilationLevels();
        boolean callerCompLevelSupported = compileCaller <= 0 || (compileCaller > 0
                && Arrays.stream(compLevels)
                        .filter(elem -> elem == compileCaller)
                        .findAny()
                        .isPresent());
        boolean calleeCompLevelSupported = compileCallee <= 0 || (compileCallee > 0
                && Arrays.stream(compLevels)
                        .filter(elem -> elem == compileCallee)
                        .findAny()
                        .isPresent());
        return callerCompLevelSupported && calleeCompLevelSupported;
    }

    /**
     * Parse test arguments
     * @param args test arguments
     */
    protected final void parseArgs(String args[]) {
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-nativeCallee":
                    nativeCallee = true;
                    break;
                case "-nativeCaller":
                    nativeCaller = true;
                    break;
                case "-compileCallee":
                    compileCallee = Integer.parseInt(args[++i]);
                    break;
                case "-compileCaller":
                    compileCaller = Integer.parseInt(args[++i]);
                    break;
                case "-checkCallerCompileLevel":
                    checkCallerCompilationLevel = true;
                    expectedCallerCompilationLevel = Integer.parseInt(args[++i]);
                    break;
                case "-checkCalleeCompileLevel":
                    checkCalleeCompilationLevel = true;
                    expectedCalleeCompilationLevel = Integer.parseInt(args[++i]);
                    break;
                default:
                    throw new Error("Can't parse test parameter:" + args[i]);
            }
        }
    }

    /**
     * Run basic logic of a test by doing compile
     * action(if needed). An arguments can be -compileCallee
     * $calleeCompilationLevel and/or -compileCaller $callerCompilationLevel
     * and/or -nativeCaller and/or -nativeCallee to indicate that native methods
     * for caller/callee should be used
     * @param args test args
     */
    protected final void runTest(String args[]) {
        parseArgs(args);
        if (compilationLevelsSupported()) {
            if (nativeCaller || nativeCallee) {
                CallsBase.loadNativeLibrary();
            }
            Object lock = getLockObject();
            Asserts.assertNotNull(lock, "Lock object is null");
            /* a following lock is needed in case several instances of this
               test are launched in same vm */
            synchronized (lock) {
                if (compileCaller > 0 || compileCallee > 0) {
                    // call once to have everything loaded
                    callerCallNormal();
                    if (nativeCallee) {
                        callerCallNative();
                    }
                }
                // compile with requested level if needed
                if (compileCallee > 0 && !compileMethod(calleeMethod, compileCallee)) {
                    System.out.println("WARNING: Blocking compilation failed for calleeMethod (timeout?). Skipping.");
                    return;
                }
                if (checkCalleeCompilationLevel) {
                    Asserts.assertEQ(expectedCalleeCompilationLevel,
                            wb.getMethodCompilationLevel(calleeMethod),
                            "Unexpected callee compilation level");
                }
                if (compileCaller > 0 && !compileMethod(callerCallNormalMethod, compileCaller)) {
                    System.out.println("WARNING: Blocking compilation failed for callerCallNormalMethod (timeout?). Skipping.");
                    return;
                }
                if (compileCaller > 0 && !compileMethod(callerCallNativeMethod, compileCaller)) {
                    System.out.println("WARNING: Blocking compilation failed for callerCallNativeMethod (timeout?). Skipping.");
                    return;
                }
                if (checkCallerCompilationLevel) {
                    Asserts.assertEQ(expectedCallerCompilationLevel,
                            wb.getMethodCompilationLevel(callerCallNormalMethod),
                            "Unexpected caller compilation level");
                            Asserts.assertEQ(expectedCallerCompilationLevel,
                            wb.getMethodCompilationLevel(callerCallNativeMethod),
                            "Unexpected caller compilation level");
                }
                // do calling work
                if (nativeCaller) {
                    callerNative();
                } else if (nativeCallee) {
                    callerCallNative();
                } else {
                    callerCallNormal();
                }
            }
        } else {
            System.out.println("WARNING: Requested compilation levels are "
                    + "out of current vm capabilities. Skipping.");
        }
    }

    /**
     * A method to compile another method, searching it by name in current class
     * @param method a method to compile
     * @param compLevel a compilation level
     * @return true if method was enqueued for compilation
     */
    protected final boolean compileMethod(Method method, int compLevel) {
        wb.deoptimizeMethod(method);
        Asserts.assertTrue(wb.isMethodCompilable(method, compLevel));
        return wb.enqueueMethodForCompilation(method, compLevel);
    }

    /*
     * @return Object to lock on during execution
     */

    protected abstract Object getLockObject();

    protected abstract void callerCallNormal();
    protected abstract void callerCallNative();

    protected abstract void callerNative();

    /**
     * A method checking values. Should be used to verify if all parameters are
     * passed as expected. Parameter N should have a value indicating number "N"
     * in respective type representation.
     */
    public static void checkValues(int param1, long param2, int param3,
            int param4, int param5) {
        Asserts.assertEQ(param1, 1);
        Asserts.assertEQ(param2, 2L);
        Asserts.assertEQ(param3, 3);
        Asserts.assertEQ(param4, 4);
        Asserts.assertEQ(param5, 5);
    }
}
