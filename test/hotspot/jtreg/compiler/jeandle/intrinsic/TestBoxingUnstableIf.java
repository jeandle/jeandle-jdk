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
 * 2 along with this work; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 */

/*
 * @test
 * @summary A mature cache-hit-only valueOf profile uses an unstable-if miss
 *          edge and restores the complete allocation path after a real miss.
 * @library /test/lib /
 * @build compiler.jeandle.fileCheck.FileCheck jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm/timeout=300 -Xbootclasspath/a:.
 *      -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *      -XX:-TieredCompilation -Xbatch -XX:+UseJeandleCompiler
 *      -XX:+JeandleUseProfile -XX:+JeandleDumpIR
 *      -XX:CompileCommand=compileonly,compiler.jeandle.intrinsic.TestBoxingUnstableIf::boxInteger
 *      -XX:CompileCommand=dontinline,compiler.jeandle.intrinsic.TestBoxingUnstableIf::boxInteger
 *      compiler.jeandle.intrinsic.TestBoxingUnstableIf
 */

package compiler.jeandle.intrinsic;

import compiler.jeandle.fileCheck.FileCheck;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

import jdk.test.lib.Asserts;
import jdk.test.whitebox.WhiteBox;

public class TestBoxingUnstableIf {
    private static final WhiteBox WB = WhiteBox.getWhiteBox();
    private static final int TIER4 = 4;
    private static final long DEADLINE_NANOS = TimeUnit.SECONDS.toNanos(30);

    private static volatile Object sink;

    public static void main(String[] args) throws Exception {
        // compileonly keeps Integer.valueOf interpreted, so these executions
        // mature its own range-check MDO without recording a cache miss.
        for (int i = 0; i < 200_000; i++) {
            sink = Integer.valueOf((i & 127) - 64);
        }

        Method box = TestBoxingUnstableIf.class.getDeclaredMethod(
                "boxInteger", int.class);
        WB.testSetDontInlineMethod(box, true);
        Asserts.assertTrue(WB.enqueueMethodForCompilation(box, TIER4),
                "failed to enqueue initial boxing compilation");
        waitUntil(() -> WB.isMethodCompiled(box),
                "initial boxing method was not compiled");

        FileCheck speculative = new FileCheck(
                System.getProperty("user.dir"), box, true);
        speculative.checkPattern("box\\.cache\\.in_range.*icmp ult i32");
        speculative.checkPattern("box\\.cache_miss\\.unstable_if");
        speculative.checkNotPattern("@new_instance");

        Asserts.assertSame(boxInteger(42), Integer.valueOf(42),
                "cache-hit identity changed");
        Integer miss = boxInteger(Integer.MAX_VALUE);
        Asserts.assertEquals(miss.intValue(), Integer.MAX_VALUE,
                "cache miss lost its payload");
        Asserts.assertTrue(miss != Integer.valueOf(Integer.MAX_VALUE),
                "out-of-cache Integer.valueOf reused an object");

        // Action_make_not_entrant preserves hotness, so the miss should cause
        // an automatic recompilation. Extra hit calls merely give the compiler
        // thread an entry opportunity on very slow test machines.
        long recompileDeadline = System.nanoTime() + DEADLINE_NANOS;
        for (int i = 0;
                !WB.isMethodCompiled(box) &&
                System.nanoTime() < recompileDeadline;
                i++) {
            sink = boxInteger(i & 63);
        }
        Asserts.assertTrue(WB.isMethodCompiled(box),
                "boxing method was not automatically recompiled after cache miss");

        FileCheck generic = new FileCheck(
                System.getProperty("user.dir"), box, true);
        generic.checkPattern("box\\.cache\\.in_range");
        generic.checkNotPattern("box\\.cache_miss\\.unstable_if");
        generic.checkPattern("@new_instance");
    }

    private static Integer boxInteger(int value) {
        return Integer.valueOf(value);
    }

    private static void waitUntil(BooleanSupplier condition, String message)
            throws InterruptedException {
        long deadline = System.nanoTime() + DEADLINE_NANOS;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        Asserts.assertTrue(condition.getAsBoolean(), message);
    }

    @FunctionalInterface
    private interface BooleanSupplier {
        boolean getAsBoolean();
    }
}
