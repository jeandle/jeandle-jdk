/*
 * Copyright (c) 2025, 2026, the Jeandle-JDK Authors. All Rights Reserved.
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
 * @library /test/lib /
 * @build jdk.test.whitebox.WhiteBox compiler.jeandle.fileCheck.FileCheck
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *      -XX:-TieredCompilation -Xcomp -Xbatch -XX:+UseJeandleCompiler
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.safepoint.TestBackedgeSafepointPoll::loopTest
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.safepoint.TestBackedgeSafepointPoll::add
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.safepoint.TestBackedgeSafepointPoll::countedLoop
 *      -XX:+JeandleDumpIR
 *      compiler.jeandle.bytecodeTranslate.safepoint.TestBackedgeSafepointPoll
 */

package compiler.jeandle.bytecodeTranslate.safepoint;

import java.lang.reflect.Method;

import compiler.jeandle.fileCheck.FileCheck;
import jdk.test.lib.Asserts;
import jdk.test.whitebox.WhiteBox;

public class TestBackedgeSafepointPoll {
    private final static WhiteBox wb = WhiteBox.getWhiteBox();

    public static void main(String[] args) throws Exception {
        if (countedLoop(10) != 10) {
            throw new RuntimeException("countedLoop returned an unexpected result");
        }

        Method countedLoop = TestBackedgeSafepointPoll.class.getDeclaredMethod("countedLoop", int.class);
        Asserts.assertTrue(wb.isMethodCompiled(countedLoop), "countedLoop must be compiled");

        FileCheck fileCheck = new FileCheck(System.getProperty("user.dir"), countedLoop, false);
        fileCheck.checkPattern("define hotspotcc i32 .*TestBackedgeSafepointPoll_countedLoop");
        // No poll may remain before the conditional branch.
        fileCheck.checkPattern(" = add i32 ");
        fileCheck.checkNextPattern(" = icmp slt i32 ");
        // The taken back-edge must enter the dedicated poll block. The
        // fallthrough target must remain an ordinary bytecode block.
        fileCheck.checkNextPattern(
                "br i1 [^,]+, label %bci_[0-9]+_backedge_safepoint, label %bci_[0-9]+(?:,.*)?$");
        fileCheck.checkPattern("bci_[0-9]+_backedge_safepoint:");
        fileCheck.checkNextPattern("call hotspotcc void @jeandle\\.safepoint_poll\\(\\)");
        fileCheck.checkNextPattern("br label %bci_[0-9]+$");

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

    public static int countedLoop(int limit) {
        int count = 0;
        do {
            count++;
        } while (count < limit);
        return count;
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
