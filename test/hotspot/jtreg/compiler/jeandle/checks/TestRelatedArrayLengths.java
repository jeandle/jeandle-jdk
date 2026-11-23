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
 * @summary Reuse lengths matching the selected array across merges and loops
 * @library /test/lib /
 * @build jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *      -Xbatch -XX:-TieredCompilation -XX:+UseJeandleCompiler -XX:CompileThreshold=100
 *      -XX:+JeandleDumpIR
 *      -XX:CompileCommand=compileonly,compiler.jeandle.checks.TestRelatedArrayLengths::test*
 *      -XX:CompileCommand=dontinline,compiler.jeandle.checks.TestRelatedArrayLengths::touch
 *      compiler.jeandle.checks.TestRelatedArrayLengths
 */
package compiler.jeandle.checks;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import jdk.test.lib.Asserts;
import jdk.test.whitebox.WhiteBox;

public class TestRelatedArrayLengths {
    private static volatile int sink;
    private static volatile int touches;

    static void touch(int[] a) {
        a[0]++;
        sink = a[0];
        touches++;
    }

    public static int testCorrelated(int[] a, int[] b, boolean which, int index) {
        int first;
        int[] chosen;
        if (which) {
            first = a[index];
            touch(a);
            chosen = a;
        } else {
            first = b[index];
            touch(b);
            chosen = b;
        }
        touch(chosen);
        return first + chosen[index];
    }

    public static int testAcrossLoop(int[] a, int[] b, boolean which, int count) {
        int first;
        int[] chosen;
        if (which) {
            first = a.length;
            touch(a);
            chosen = a;
        } else {
            int i = 0;
            do {
                first = b.length;
                touch(b);
            } while (++i < count);
            chosen = b;
        }
        touch(chosen);
        return first + chosen.length;
    }

    private static void checkCorrelated(int[] a, int[] b, boolean which, int index) {
        int[] chosen = which ? a : b;
        int before = chosen[index];
        int oldTouches = touches;
        int expected = 2 * before + (index == 0 ? 2 : 0);
        Asserts.assertEquals(testCorrelated(a, b, which, index), expected);
        Asserts.assertEquals(touches, oldTouches + 2, "opaque calls survive length reuse");
    }

    private static void checkAcrossLoop(int[] a, int[] b, boolean which, int count) {
        int[] chosen = which ? a : b;
        int oldTouches = touches;
        Asserts.assertEquals(testAcrossLoop(a, b, which, count), 2 * chosen.length);
        Asserts.assertEquals(touches, oldTouches + (which ? 2 : count + 1));
    }

    private static void checkCompiledLengthReads(String name) throws Exception {
        Method target = TestRelatedArrayLengths.class.getDeclaredMethod(name,
                int[].class, int[].class, boolean.class, int.class);
        Asserts.assertTrue(WhiteBox.getWhiteBox().isMethodCompiled(target), name + " ordinary entry compiled");
        Path dump;
        try (var paths = Files.list(Path.of(System.getProperty("user.dir")))) {
            dump = paths.filter(p -> p.getFileName().toString().startsWith(
                    "compiler_jeandle_checks_TestRelatedArrayLengths_" + name + "_")
                    && p.getFileName().toString().endsWith("_optimized.ll"))
                    .max(Comparator.naturalOrder()).orElseThrow();
        }
        long reads = Files.readAllLines(dump).stream()
                .filter(line -> line.contains("load atomic i32") && line.contains("!range")).count();
        Asserts.assertTrue(reads > 0 && reads <= 2,
                name + " reuses the selected length after the merge: " + reads);
        System.out.println("RELATED_LENGTH_COMPILED method=" + name + " headerLoads=" + reads + " ir=" + dump);
    }

    private static void expectNull(int[] a, int[] b, boolean which) {
        int oldTouches = touches;
        try {
            testCorrelated(a, b, which, -1);
            throw new AssertionError("selected null array must throw before the bounds check");
        } catch (NullPointerException expected) {
            Asserts.assertEquals(touches, oldTouches);
        }
    }

    private static void expectBounds(int[] a, int[] b, boolean which, int index) {
        int oldTouches = touches;
        try {
            testCorrelated(a, b, which, index);
            throw new AssertionError("selected index must fail");
        } catch (ArrayIndexOutOfBoundsException expected) {
            Asserts.assertEquals(touches, oldTouches);
        }
    }

    public static void main(String[] args) throws Exception {
        int[] a = new int[32];
        int[] b = new int[64];
        a[7] = 11;
        b[7] = 29;
        for (int i = 0; i < 20000; i++) {
            boolean which = (i & 1) == 0;
            int[] left = (i & 7) == 1 ? null : a;
            int[] right = (i & 7) == 0 ? null : b;
            checkCorrelated(left, right, which, (i & 2) == 0 ? 0 : 7);
            checkAcrossLoop(left, right, which, 3);
        }
        checkCompiledLengthReads("testCorrelated");
        checkCompiledLengthReads("testAcrossLoop");
        checkCorrelated(a, b, true, 7);
        checkCorrelated(a, b, false, 7);
        checkCorrelated(a, null, true, 0);
        checkCorrelated(null, b, false, 0);
        checkCorrelated(a, a, true, 7);
        checkCorrelated(a, a, false, 7);
        checkAcrossLoop(a, null, true, 3);
        checkAcrossLoop(null, b, false, 3);
        System.out.println("RELATED_LENGTH_EXECUTED");
        expectNull(null, b, true);
        expectNull(a, null, false);
        expectBounds(a, b, true, -1);
        expectBounds(a, b, true, a.length);
        expectBounds(a, b, false, b.length);
        System.out.println("RELATED_LENGTH_EXCEPTIONS_EXECUTED");
    }
}
