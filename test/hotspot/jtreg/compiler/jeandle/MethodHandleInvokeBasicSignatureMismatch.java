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
 */

/*
 * @test
 * @summary Verify invokeBasic handles an adapted wrapper signature whose return
 *          type differs from the target.
 * @run main/othervm -Xbatch -XX:+UseJeandleCompiler -XX:-TieredCompilation
 *      -XX:CompileThreshold=1000 compiler.jeandle.MethodHandleInvokeBasicSignatureMismatch
 */

package compiler.jeandle;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

public class MethodHandleInvokeBasicSignatureMismatch {
    private static final MethodHandle VOID_CHAR;
    private static final MethodHandle VOID_OBJECT;
    private static volatile int charCalls;
    private static volatile int objectCalls;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            MethodHandle charTarget = lookup.findStatic(
                    MethodHandleInvokeBasicSignatureMismatch.class, "charTarget",
                    MethodType.methodType(char.class, Object.class, int.class));
            MethodHandle objectTarget = lookup.findStatic(
                    MethodHandleInvokeBasicSignatureMismatch.class, "objectTarget",
                    MethodType.methodType(Object.class, Object.class, int.class));
            MethodType wrapperType = MethodType.methodType(void.class, String.class, int.class);
            VOID_CHAR = charTarget.asType(wrapperType);
            VOID_OBJECT = objectTarget.asType(wrapperType);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static char charTarget(Object receiver, int value) {
        charCalls++;
        return (char) (receiver.hashCode() + value);
    }

    private static Object objectTarget(Object receiver, int value) {
        objectCalls++;
        return receiver;
    }

    private static void invokeChar(MethodHandle mh, String receiver, int value) throws Throwable {
        mh.invokeExact(receiver, value);
    }

    private static void invokeObject(MethodHandle mh, String receiver, int value) throws Throwable {
        mh.invokeExact(receiver, value);
    }

    public static void main(String[] args) throws Throwable {
        final int iterations = 20_000;
        String receiver = "receiver";
        for (int i = 0; i < iterations; i++) {
            invokeChar(VOID_CHAR, receiver, i);
            invokeObject(VOID_OBJECT, receiver, i);
        }
        if (charCalls != iterations || objectCalls != iterations) {
            throw new AssertionError("unexpected target call counts: " + charCalls + ", " + objectCalls);
        }
    }
}
