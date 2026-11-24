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
 * @summary Size-derived reflective allocation limits and empty copy clear intervals
 * @library /test/lib /
 * @build compiler.jeandle.fileCheck.FileCheck
 * @run driver compiler.jeandle.intrinsic.TestArrayAllocationIntrinsics
 */
package compiler.jeandle.intrinsic;

import java.lang.reflect.Array;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import compiler.jeandle.fileCheck.FileCheck;
import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestArrayAllocationIntrinsics {
    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            runWorkload();
            return;
        }
        var dump = Files.createTempDirectory("array_allocation_intrinsics_");
        OutputAnalyzer output = ProcessTools.executeProcess(
                ProcessTools.createLimitedTestJavaProcessBuilder(List.of(
                        "-Xbatch", "-Xcomp", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler",
                        "-XX:+JeandleDumpIR", "-XX:JeandleDumpDirectory=" + dump,
                        "-XX:CompileCommand=compileonly," + TestArrayAllocationIntrinsics.class.getName() + "::allocate",
                        "-XX:CompileCommand=compileonly," + TestArrayAllocationIntrinsics.class.getName() + "::copy",
                        "-XX:CompileCommand=compileonly,java.lang.reflect.Array::newInstance",
                        TestArrayAllocationIntrinsics.class.getName(), "child")));
        output.shouldHaveExitValue(0).shouldContain("ARRAY_ALLOCATION_INTRINSICS_OK");
        FileCheck allocation = new FileCheck(dump.toString(),
                Array.class.getMethod("newInstance", Class.class, int.class), false);
        allocation.checkPatternAnywhere("shl i32 [0-9]+, %new_array\\.length_limit_shift");
        FileCheck copy = new FileCheck(dump.toString(),
                TestArrayAllocationIntrinsics.class.getMethod("copy", Object[].class, int.class), false);
        copy.checkPatternAnywhere("icmp eq i32 %zero_start, %size_in_bytes");
    }

    public static Object allocate(Class<?> component, int length) {
        return Array.newInstance(component, length);
    }

    public static Object[] copy(Object[] source, int length) {
        return Arrays.copyOf(source, length, Object[].class);
    }

    private static void runWorkload() {
        // Resolve the intrinsic callees before the first forced compilation.
        Array.newInstance(byte.class, 0);
        Arrays.copyOf(new Object[0], 0, Object[].class);
        Class<?>[] components = {byte.class, short.class, int.class, long.class, Object.class};
        for (Class<?> component : components) {
            for (int size : new int[] {0, 1, 131071, 131072, 131073, 262144, 1048577}) {
                Object array = allocate(component, size);
                Asserts.assertEquals(Array.getLength(array), size);
                Asserts.assertEquals(array.getClass().getComponentType(), component);
                if (size != 0) {
                    Object last = Array.get(array, size - 1);
                    Asserts.assertTrue(component == Object.class ? last == null : ((Number) last).longValue() == 0);
                }
            }
        }
        Object[] source = {new Object(), new Object(), new Object()};
        for (int size : new int[] {0, 1, 3, 4, 17}) {
            Object[] result = copy(source, size);
            Asserts.assertTrue(result != source);
            Asserts.assertEquals(result.length, size);
            for (int i = 0; i < size; i++) {
                Asserts.assertTrue(result[i] == (i < source.length ? source[i] : null));
            }
        }
        try { allocate(byte.class, -1); throw new AssertionError(); }
        catch (NegativeArraySizeException expected) { }
        try { allocate(null, 1); throw new AssertionError(); }
        catch (NullPointerException expected) { }
        try { allocate(void.class, 1); throw new AssertionError(); }
        catch (IllegalArgumentException expected) { }
        try { allocate(long.class, Integer.MAX_VALUE); throw new AssertionError(); }
        catch (OutOfMemoryError expected) { }
        System.out.println("ARRAY_ALLOCATION_INTRINSICS_OK");
    }
}
