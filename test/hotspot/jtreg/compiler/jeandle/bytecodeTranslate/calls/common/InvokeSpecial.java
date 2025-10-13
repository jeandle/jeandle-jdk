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

import jdk.test.lib.Asserts;

/**
 * A test class checking InvokeSpecial instruction
 */
public class InvokeSpecial extends CallsBase {
    private static final Object LOCK = new Object();

    public static void main(String args[]) {
        new InvokeSpecial().runTest(args);
    }

    /**
     * A native caller method, assumed to called "callee"/"calleeNative"
     */
    @Override
    public native void callerNative();

    /**
     * A caller method, assumed to called "callee"
     */
    @Override
    public void callerCallNormal() {
        Asserts.assertTrue(callee(1, 2L, 3, 4, 5));
    }

    /**
     * A caller method, assumed to called "calleeNative"
     */
    @Override
    public void callerCallNative() {
        Asserts.assertTrue(calleeNative(1, 2L, 3, 4, 5));
    }

    /**
     * A callee method, assumed to be called by "caller"/"callerNative"
     */
    private boolean callee(int param1, long param2, int param3, int param4,
            int param5) {
        CallsBase.checkValues(param1, param2, param3, param4, param5);
        return true;
    }

    /**
     * A native callee method, assumed to be called by "caller"/"callerNative"
     */
    private native boolean calleeNative(int param1, long param2, int param3,
            int param4, int param5);

    /**
     * Returns object to lock execution on
     * @return lock object
     */
    @Override
    protected Object getLockObject() {
        return LOCK;
    }
}
