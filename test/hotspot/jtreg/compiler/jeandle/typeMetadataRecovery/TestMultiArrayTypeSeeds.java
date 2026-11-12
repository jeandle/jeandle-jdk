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
 * @summary Multidimensional allocations carry their exact outer array type
 * @library /test/lib /
 * @build compiler.jeandle.fileCheck.FileCheck
 * @run driver TestMultiArrayTypeSeeds
 */

import java.nio.file.Files;
import compiler.jeandle.fileCheck.FileCheck;
import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestMultiArrayTypeSeeds {
    static class Item { }

    static Object allocateTwo(int first, int second) {
        return new Item[first][second];
    }

    static Object allocateThree(int length) {
        return new int[length][1][1];
    }

    static Object allocateFour(int length) {
        return new Object[length][1][1][1];
    }

    static Object allocateFive(int length) {
        return new int[length][1][1][1][1];
    }

    static Object allocateSix(int length) {
        return new Object[length][1][1][1][1][1];
    }

    static Object allocatePartial(int length) {
        return new Item[length][1][];
    }

    static Object allocateInterface(int length) {
        return new Runnable[length][1];
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            runWorkload();
            return;
        }
        var dump = Files.createTempDirectory("multi_array_type_seeds_");
        OutputAnalyzer output = ProcessTools.executeProcess(
                ProcessTools.createLimitedTestJavaProcessBuilder(
                        "-Xcomp", "-Xbatch", "-XX:-TieredCompilation",
                        "-XX:+UseJeandleCompiler", "-XX:+CIPrintCompilerName",
                        "-XX:+PrintCompilation", "-XX:+JeandleDumpIR",
                        "-XX:JeandleDumpDirectory=" + dump,
                        "-XX:CompileCommand=compileonly,TestMultiArrayTypeSeeds::allocate*",
                        TestMultiArrayTypeSeeds.class.getName(), "child"));
        output.shouldHaveExitValue(0).shouldContain("MULTI_ARRAY_TYPES_PASS");
        for (String name : new String[] { "allocateTwo", "allocateThree", "allocateFour",
                                        "allocateFive", "allocateSix", "allocatePartial",
                                        "allocateInterface" }) {
            output.shouldMatch("Jeandle:.*TestMultiArrayTypeSeeds::" + name + " \\([0-9]+ bytes\\)");
            var method = name.equals("allocateTwo")
                    ? TestMultiArrayTypeSeeds.class.getDeclaredMethod(name, int.class, int.class)
                    : TestMultiArrayTypeSeeds.class.getDeclaredMethod(name, int.class);
            FileCheck raw = new FileCheck(dump.toString(), method, false);
            raw.checkPatternAnywhere("invoke.*\"java-klass\"=\"([0-9]+)\".*\"java-klass-exact\""
                    + ".*@multianewarray[2-5N]\\(ptr inttoptr \\(i64 \\1 to ptr\\)");
        }
    }

    private static void runWorkload() throws Exception {
        for (Class<?> type : new Class<?>[] { Item[][].class, int[][][].class,
                Object[][][][].class, int[][][][][].class, Object[][][][][][].class,
                Item[][][].class, Runnable[][].class }) {
            Class.forName(type.getName());
        }
        Item[][] two = (Item[][]) allocateTwo(2, 3);
        Asserts.assertEquals(two.getClass(), Item[][].class);
        Asserts.assertEquals(two.length, 2);
        Asserts.assertEquals(two[0].length, 3);
        Asserts.assertNull(two[1][2]);
        Asserts.assertEquals(allocateThree(2).getClass(), int[][][].class);
        Asserts.assertEquals(allocateFour(2).getClass(), Object[][][][].class);
        Asserts.assertEquals(allocateFive(2).getClass(), int[][][][][].class);
        Asserts.assertEquals(allocateSix(2).getClass(), Object[][][][][][].class);
        Item[][][] partial = (Item[][][]) allocatePartial(2);
        Asserts.assertEquals(partial.getClass(), Item[][][].class);
        Asserts.assertNull(partial[0][0]);
        Asserts.assertEquals(allocateInterface(2).getClass(), Runnable[][].class);
        Asserts.assertEquals(((Object[]) allocateTwo(0, 3)).length, 0);
        for (int[] dimensions : new int[][] { {-1, 1}, {1, -1}, {0, -1} }) {
            try {
                allocateTwo(dimensions[0], dimensions[1]);
                throw new AssertionError("negative dimension must throw");
            } catch (NegativeArraySizeException expected) {
            }
        }
        System.out.println("MULTI_ARRAY_TYPES_PASS");
    }
}
