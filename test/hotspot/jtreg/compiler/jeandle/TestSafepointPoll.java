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
 * @summary Support the safepoint poll and related to a issue about incorrect alignment of patch address
 *  issue: https://github.com/jeandle/jeandle-jdk/issues/63
 * @library /test/lib
 * @build jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *      -XX:CompileCommand=compileonly,TestSafepointPoll::test -Xcomp -XX:-TieredCompilation
 *      -XX:+UseJeandleCompiler -Xlog:thread*=trace::time,tags -XX:+JeandleDumpIR -XX:+JeandleDumpObjects -XX:+JeandleDumpRuntimeStubs TestSafepointPoll
 */

import java.lang.reflect.Method;

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
    }

    static void test() {
        while (!stop) {}
    }
}
