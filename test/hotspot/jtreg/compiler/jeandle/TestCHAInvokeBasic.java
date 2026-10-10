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
 * @summary CHA must handle the tagged target holder returned for _invokeBasic
 * @run main/othervm -Xbatch -XX:+UseJeandleCompiler -XX:-TieredCompilation
 *      -XX:CompileThreshold=1000 compiler.jeandle.TestCHAInvokeBasic
 */

package compiler.jeandle;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

public class TestCHAInvokeBasic {
    private static final MethodHandle ADAPTED;
    private static volatile int calls;

    static {
        try {
            MethodHandle target = MethodHandles.lookup().findStatic(
                    TestCHAInvokeBasic.class, "target",
                    MethodType.methodType(char.class, Object.class, int.class));
            // Keep the target behind an adapter so invokeExact exercises
            // _invokeBasic with a constant MethodHandle receiver.
            ADAPTED = target.asType(
                    MethodType.methodType(void.class, String.class, int.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static char target(Object receiver, int value) {
        calls++;
        return (char) (receiver.hashCode() + value);
    }

    private static void invoke(int value) throws Throwable {
        ADAPTED.invokeExact("receiver", value);
    }

    public static void main(String[] args) throws Throwable {
        final int iterations = 20_000;
        for (int i = 0; i < iterations; i++) {
            invoke(i);
        }
        if (calls != iterations) {
            throw new AssertionError(
                    "Expected " + iterations + " calls, got " + calls);
        }
    }
}
