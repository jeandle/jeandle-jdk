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
 * @summary Support the safepoint poll and related to a issue about incorrect alignment of patch address.
 *  issue: https://github.com/jeandle/jeandle-jdk/issues/63
 * @library /test/lib /
 * @build jdk.test.whitebox.WhiteBox compiler.jeandle.fileCheck.FileCheck jdk.test.lib.Asserts
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.safepoint.TestSafepointPoll::test
 *      -Xcomp -XX:-TieredCompilation
 *      -XX:+UseJeandleCompiler  -XX:+JeandleDumpIR compiler.jeandle.bytecodeTranslate.safepoint.TestSafepointPoll
 */

package compiler.jeandle.bytecodeTranslate.safepoint;

import java.lang.reflect.Method;

import compiler.jeandle.fileCheck.FileCheck;
import jdk.test.whitebox.WhiteBox;

public class TestSafepointPoll {
    private final static WhiteBox wb = WhiteBox.getWhiteBox();
    static volatile boolean stop = false;

    public static void main(String[] args) throws Exception {
        new Thread(() -> {
            test();
        }).start();

        Method method = TestSafepointPoll.class.getDeclaredMethod("test");
        while (!wb.isMethodCompiled(method)) {
            Thread.yield();
        }

        Thread.sleep(100);

        wb.fullGC();

        stop = true;

        Thread.yield();

        String currentDir = System.getProperty("user.dir");
        {
            FileCheck fileCheck = new FileCheck(currentDir,
                                                TestSafepointPoll.class.getDeclaredMethod("test"),
                                                false);
            fileCheck.check("define private hotspotcc void @jeandle.safepoint_poll()");
            fileCheck.checkNext("entry:");
            fileCheck.checkNext("%0 = load volatile i64, ptr addrspace(2) inttoptr");
            fileCheck.checkNext("%1 = icmp eq i64 %0, -2");
            fileCheck.checkNext("br i1 %1, label %return, label %do_safepoint");
            fileCheck.checkNext("return:");
            fileCheck.checkNext("ret void");
            fileCheck.checkNext("do_safepoint:");
            fileCheck.checkNext("%2 = call hotspotcc ptr @jeandle.current_thread()");
            fileCheck.checkNext("call hotspotcc void @safepoint_handler(ptr %2)");
            fileCheck.checkNext("br label %return");
            fileCheck.checkNext("}");
        }
    }

    static void test() {
        while (!stop) {}
    }
}
