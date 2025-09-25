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

/*
 * @test
 * @summary the back edge of the `if*` bytecode should have safepoint poll
 * @library /test/lib
 * @build jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *      -XX:-TieredCompilation -Xcomp -XX:+UseJeandleCompiler
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.safepoint.TestBackedgeSafepointPoll::loopTest
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.safepoint.TestBackedgeSafepointPoll::add
 *      compiler.jeandle.bytecodeTranslate.safepoint.TestBackedgeSafepointPoll
 */

package compiler.jeandle.bytecodeTranslate.safepoint;

import java.lang.reflect.Method;

import jdk.test.whitebox.WhiteBox;

public class TestBackedgeSafepointPoll {
    private final static WhiteBox wb = WhiteBox.getWhiteBox();

    public static void main(String[] args) throws Exception {
        for (int i =0; i < 10; i++) {
            runThread();
        }

        Method method = TestBackedgeSafepointPoll.class.getDeclaredMethod("loopTest");
        while (!wb.isMethodCompiled(method)) {
            Thread.yield();
        }

        Thread.sleep(100);
        System.out.println("Main thread ends");
    }

    public static void runThread() {
        Thread loopThread = new Thread(() -> {
            loopTest();
        });
        // The thread is marked as `daemon`, so when the main thread ends,
        // the daemon thread should be finished by the JVM too.
        // Such communication between main thread and the daemon thread
        // is implemented by the safepoint poll of the daemon thread.
        loopThread.setDaemon(true);
        loopThread.start();
    }

    public static void loopTest() {
        // The back edge of `if*` bytecode, which is generated from
        // the `while` statement here, should generate safepoint poll by compiler,
        // so that the daemon thread can communicate with the main thread periodically
        // and then ends according to the main thread instead of running forever.
        while (true) {
            add(2, 2);
            if (add(1, 2) != 3) {
            }
        }
    }

    public static int add(int a, int b) {
        return a + b;
    }
}
