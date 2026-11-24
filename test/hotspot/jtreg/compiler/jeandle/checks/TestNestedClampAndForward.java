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
 * @summary Nested modular starts, clamped indices and repeated field checks preserve Java behavior
 * @library /test/lib /
 * @build compiler.jeandle.fileCheck.FileCheck compiler.jeandle.checks.CheckLoopShape
 * @run main/othervm -Xcomp -Xbatch -XX:-TieredCompilation -XX:+UseJeandleCompiler
 *      -XX:+JeandleDumpIR -XX:+UseCompressedOops
 *      -XX:CompileCommand=compileonly,compiler.jeandle.checks.TestNestedClampAndForward::test*
 *      compiler.jeandle.checks.TestNestedClampAndForward
 * @run main/othervm -Xcomp -Xbatch -XX:-TieredCompilation -XX:+UseJeandleCompiler
 *      -XX:+JeandleDumpIR -XX:-UseCompressedOops
 *      -XX:CompileCommand=compileonly,compiler.jeandle.checks.TestNestedClampAndForward::test*
 *      compiler.jeandle.checks.TestNestedClampAndForward
 */
package compiler.jeandle.checks;

import compiler.jeandle.fileCheck.FileCheck;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import jdk.test.lib.Asserts;

public class TestNestedClampAndForward {
    private int[] values;
    private static int side;

    public static int testNested(int[] a, int rows, int columns) {
        int sum = a.length;
        for (int r = 0; r < rows; r++) {
            for (int j = 0; j < columns; j++) {
                side++;
                sum += a[7 * r + 3 * j];
            }
        }
        return sum;
    }

    public static int testClamp(int[] a, int end, int offset) {
        int length = a.length;
        if (length == 0) return 0;
        int sum = 0;
        for (int i = 0; i < end; i++) {
            sum += a[Math.min(Math.max(i + offset, 0), length - 1)];
        }
        return sum;
    }

    public int testForward() {
        int sum = 0;
        for (int i = 0; i < values.length; i++) sum += values[i];
        return sum;
    }

    private static FileCheck optimized(String name, Class<?>... params) throws Exception {
        Method m = TestNestedClampAndForward.class.getDeclaredMethod(name, params);
        FileCheck check = new FileCheck(System.getProperty("user.dir"), m, true);
        check.selectFunction("define.*TestNestedClampAndForward_" + name + ".*root");
        return check;
    }

    private static void assertForwardChecksOnReloads() throws Exception {
        Path ir;
        try (var files = Files.list(Path.of(System.getProperty("user.dir")))) {
            ir = files.filter(p -> p.getFileName().toString().startsWith(
                            "compiler_jeandle_checks_TestNestedClampAndForward_testForward_")
                    && p.getFileName().toString().endsWith("_optimized.ll"))
                    .max(Comparator.comparing(Path::toString)).orElseThrow();
        }
        String text = Files.readString(ir);
        int start = text.indexOf("define hotspotcc i32 @\"compiler_jeandle_checks_"
                + "TestNestedClampAndForward_testForward");
        Asserts.assertTrue(start >= 0, "compiled forward root");
        String root = text.substring(start, text.indexOf("\n}", start));
        String block = "";
        boolean reload = false;
        int checks = 0;
        for (String line : root.split("\n")) {
            if (line.matches("^[a-zA-Z0-9_.$-]+:.*")) {
                block = line.substring(0, line.indexOf(':'));
                reload = false;
            }
            reload |= line.contains("load atomic ptr addrspace(");
            if (line.trim().startsWith("br i1 ") && line.contains("null_check_fail")) {
                Asserts.assertTrue(block.equals("entry") || reload,
                        "null check on reused reference in " + block);
                checks++;
            }
        }
        Asserts.assertEquals(checks, 2, "entry and fresh-reload checks");
    }

    public static void main(String[] args) throws Exception {
        Math.min(Math.max(0, 0), 1);
        int[] a = new int[1000];
        Arrays.fill(a, 1);
        Asserts.assertEquals(testNested(a, 5, 30), 1150);
        CheckLoopShape.assertVersioned(TestNestedClampAndForward.class.getDeclaredMethod(
                "testNested", int[].class, int.class, int.class));
        int[] distinct = new int[1000];
        for (int i = 0; i < distinct.length; i++) distinct[i] = i + 1;
        Asserts.assertEquals(testClamp(distinct, 100, Integer.MAX_VALUE - 3), 4096);
        Asserts.assertEquals(testClamp(distinct, 100, Integer.MIN_VALUE), 100);
        Asserts.assertEquals(testClamp(new int[0], 100, 1), 0);
        optimized("testClamp", int[].class, int.class, int.class)
                .checkNotPattern("label %bci_[0-9]+_boundary_check_fail");
        CheckLoopShape.assertElementLoop(TestNestedClampAndForward.class.getDeclaredMethod(
                "testClamp", int[].class, int.class, int.class));
        TestNestedClampAndForward holder = new TestNestedClampAndForward();
        holder.values = a;
        Asserts.assertEquals(holder.testForward(), 1000);
        CheckLoopShape.assertElementLoop(TestNestedClampAndForward.class.getDeclaredMethod("testForward"));
        // The iaload at BCI 19 uses the reference already checked by arraylength.
        optimized("testForward").checkNotPattern("bci_19_null_check_fail");
        optimized("testForward").checkPattern("label %bci_9_null_check_fail");
        optimized("testForward").checkPattern("label %bci_9_null_check_fail.loopexit");
        assertForwardChecksOnReloads();
        for (int round = 0; round < 10; round++) {
            holder.values = new int[1000 + round];
            Arrays.fill(holder.values, round);
            System.gc();
            Asserts.assertEquals(holder.testForward(), (1000 + round) * round);
        }
        int previous = side;
        try {
            testNested(new int[2], 3, 10);
            throw new AssertionError("missing nested bounds exception");
        } catch (ArrayIndexOutOfBoundsException expected) {
            Asserts.assertEquals(side, previous + 2);
        }
        Asserts.assertEquals(testNested(a, 0, Integer.MAX_VALUE), 1000);
        holder.values = null;
        try {
            holder.testForward();
            throw new AssertionError("missing forward null exception");
        } catch (NullPointerException expected) { }
        System.out.println("NESTED_CLAMP_FORWARD_EXECUTED " + side);
    }
}
