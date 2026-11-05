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
 * @summary Surviving semantic boxes expand to complete profiled valueOf
 *          cache/allocation control flow without cache-miss deoptimization.
 * @library /test/lib /
 * @build compiler.jeandle.fileCheck.FileCheck jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm/timeout=300 -Xbootclasspath/a:.
 *      -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *      -XX:-TieredCompilation -Xbatch -XX:+UseJeandleCompiler
 *      -XX:+JeandleUseProfile -XX:+JeandleDumpIR
 *      -XX:CompileCommand=compileonly,compiler.jeandle.intrinsic.TestBoxingProfiledCacheHit::box*
 *      -XX:CompileCommand=dontinline,compiler.jeandle.intrinsic.TestBoxingProfiledCacheHit::box*
 *      compiler.jeandle.intrinsic.TestBoxingProfiledCacheHit
 */

package compiler.jeandle.intrinsic;

import compiler.jeandle.fileCheck.FileCheck;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

import jdk.test.lib.Asserts;
import jdk.test.whitebox.WhiteBox;

public class TestBoxingProfiledCacheHit {
    private static final WhiteBox WB = WhiteBox.getWhiteBox();
    private static final int TIER4 = 4;
    private static final long DEADLINE_NANOS = TimeUnit.SECONDS.toNanos(30);

    private static volatile Object sink;

    public static void main(String[] args) throws Exception {
        warmValueOfProfiles();

        Method[] boxMethods = {
                TestBoxingProfiledCacheHit.class.getDeclaredMethod(
                        "boxCharacter", char.class),
                TestBoxingProfiledCacheHit.class.getDeclaredMethod(
                        "boxShort", short.class),
                TestBoxingProfiledCacheHit.class.getDeclaredMethod(
                        "boxInteger", int.class),
                TestBoxingProfiledCacheHit.class.getDeclaredMethod(
                        "boxLong", long.class)
        };
        for (Method box : boxMethods) {
            compileAndCheck(box);
        }

        checkCharacter(boxMethods[0]);
        checkShort(boxMethods[1]);
        checkInteger(boxMethods[2]);
        checkLong(boxMethods[3]);
    }

    private static void warmValueOfProfiles() {
        for (int i = 0; i < 20_000; i++) {
            boolean miss = (i & 31) == 0;
            sink = Character.valueOf(miss ? Character.MAX_VALUE
                                           : (char) (i & 127));
            sink = Short.valueOf(miss ? Short.MAX_VALUE
                                      : (short) ((i & 127) - 64));
            sink = Integer.valueOf(miss ? Integer.MAX_VALUE
                                        : (i & 127) - 64);
            sink = Long.valueOf(miss ? Long.MAX_VALUE : (i & 127) - 64L);
        }
    }

    private static void compileAndCheck(Method box) throws Exception {
        WB.testSetDontInlineMethod(box, true);
        Asserts.assertTrue(WB.enqueueMethodForCompilation(box, TIER4),
                "failed to enqueue " + box.getName());
        waitUntil(() -> WB.isMethodCompiled(box),
                box.getName() + " was not compiled");

        FileCheck before = new FileCheck(System.getProperty("user.dir"),
                box, false);
        before.checkPattern("invoke hotspotcc.*@jeandle\\.box\\(");

        FileCheck after = new FileCheck(System.getProperty("user.dir"),
                box, true);
        after.checkPattern("box\\.cache\\.in_range");
        after.checkNotPattern(
                "(?:call|invoke) hotspotcc.*@jeandle\\.box\\(");
    }

    private static void checkCharacter(Method box) {
        Asserts.assertSame(boxCharacter((char) 42), Character.valueOf((char) 42),
                "Character cache-hit identity changed");
        Character miss = boxCharacter(Character.MAX_VALUE);
        Asserts.assertEquals(miss.charValue(), Character.MAX_VALUE,
                "Character cache miss lost its payload");
        Asserts.assertTrue(miss != Character.valueOf(Character.MAX_VALUE),
                "out-of-cache Character.valueOf reused an object");
        Asserts.assertTrue(WB.isMethodCompiled(box),
                "Character cache miss invalidated compiled code");
    }

    private static void checkShort(Method box) {
        Asserts.assertSame(boxShort((short) 42), Short.valueOf((short) 42),
                "Short cache-hit identity changed");
        Short miss = boxShort(Short.MAX_VALUE);
        Asserts.assertEquals(miss.shortValue(), Short.MAX_VALUE,
                "Short cache miss lost its payload");
        Asserts.assertTrue(miss != Short.valueOf(Short.MAX_VALUE),
                "out-of-cache Short.valueOf reused an object");
        Asserts.assertTrue(WB.isMethodCompiled(box),
                "Short cache miss invalidated compiled code");
    }

    private static void checkInteger(Method box) {
        Asserts.assertSame(boxInteger(42), Integer.valueOf(42),
                "Integer cache-hit identity changed");
        Integer miss = boxInteger(Integer.MAX_VALUE);
        Asserts.assertEquals(miss.intValue(), Integer.MAX_VALUE,
                "Integer cache miss lost its payload");
        Asserts.assertTrue(miss != Integer.valueOf(Integer.MAX_VALUE),
                "out-of-cache Integer.valueOf reused an object");
        Asserts.assertTrue(WB.isMethodCompiled(box),
                "Integer cache miss invalidated compiled code");
    }

    private static void checkLong(Method box) {
        Asserts.assertSame(boxLong(42L), Long.valueOf(42L),
                "Long cache-hit identity changed");
        Long miss = boxLong(Long.MAX_VALUE);
        Asserts.assertEquals(miss.longValue(), Long.MAX_VALUE,
                "Long cache miss lost its payload");
        Asserts.assertTrue(miss != Long.valueOf(Long.MAX_VALUE),
                "out-of-cache Long.valueOf reused an object");
        Asserts.assertTrue(WB.isMethodCompiled(box),
                "Long cache miss invalidated compiled code");
    }

    private static Character boxCharacter(char value) {
        return Character.valueOf(value);
    }

    private static Short boxShort(short value) {
        return Short.valueOf(value);
    }

    private static Integer boxInteger(int value) {
        return Integer.valueOf(value);
    }

    private static Long boxLong(long value) {
        return Long.valueOf(value);
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
