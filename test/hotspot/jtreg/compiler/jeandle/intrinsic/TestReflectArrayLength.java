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
 * @summary Reflective array length guards, Java exceptions and allocation length folding
 * @library /test/lib /
 * @build compiler.jeandle.fileCheck.FileCheck
 * @run driver compiler.jeandle.intrinsic.TestReflectArrayLength
 */

package compiler.jeandle.intrinsic;

import java.lang.reflect.Array;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import compiler.jeandle.fileCheck.FileCheck;
import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestReflectArrayLength {
    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            Workload.run();
            return;
        }
        for (boolean enabled : new boolean[] {true, false}) {
            Path dump = Files.createTempDirectory("jeandle_reflect_length_");
            var command = new ArrayList<>(List.of(
                    "-Xbatch", "-Xcomp", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler",
                    "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR",
                    "-XX:JeandleDumpDirectory=" + dump,
                    "-XX:CompileCommand=compileonly," + Workload.class.getName() + "::length*",
                    "-XX:CompileCommand=compileonly," + Workload.class.getName() + "::newArrayAtDimensionLimit",
                    "-XX:CompileCommand=compileonly,java.lang.reflect.Array::newInstance"));
            if (!enabled) {
                command.addAll(List.of("-XX:+UnlockDiagnosticVMOptions", "-XX:ControlIntrinsic=-_getLength"));
            }
            command.addAll(List.of(TestReflectArrayLength.class.getName(), "child"));
            OutputAnalyzer output = ProcessTools.executeProcess(
                    ProcessTools.createLimitedTestJavaProcessBuilder(command));
            output.shouldHaveExitValue(0).shouldContain("reflective lengths verified");
            FileCheck initial = new FileCheck(dump.toString(),
                    Workload.class.getMethod("length", Object.class), false);
            if (enabled) {
                initial.checkPattern("call hotspotcc i32 @jeandle\\.arraylength");
                FileCheck folded = new FileCheck(dump.toString(),
                        Workload.class.getMethod("lengthOfAllocation"), true);
                folded.checkPattern("ret i32 37");
                folded.checkNotPattern("@jeandle\\.new_array|@new_array");
            } else {
                initial.checkNotPattern("call hotspotcc i32 @jeandle\\.arraylength");
            }
        }
    }

    public static class Workload {
        static final Class<?> MAX_ARRAY_CLASS = maxArrayClass();

        private static Class<?> maxArrayClass() {
            try {
                return Class.forName("[".repeat(255) + "I");
            } catch (ClassNotFoundException e) {
                throw new AssertionError(e);
            }
        }

        public static Object newArrayAtDimensionLimit(int length) {
            return Array.newInstance(MAX_ARRAY_CLASS, length);
        }

        public static int length(Object array) {
            return Array.getLength(array);
        }

        public static int lengthOfAllocation() {
            return Array.getLength(new int[37]);
        }

        public static int lengthInCatch(Object array) {
            try {
                return Array.getLength(array);
            } catch (NullPointerException e) {
                return -1;
            } catch (IllegalArgumentException e) {
                return -2;
            }
        }

        public static int lengthOfNonArray() {
            try {
                return Array.getLength(new Object());
            } catch (IllegalArgumentException expected) {
                return -2;
            }
        }

        static void run() {
            Asserts.assertEquals(Array.getLength(new int[0]), 0);
            Object[] arrays = {new boolean[0], new byte[1], new char[2], new short[3],
                    new int[4], new long[5], new float[6], new double[7],
                    new Object[8], new String[9], new int[10][]};
            for (int iteration = 0; iteration < 100; iteration++) {
                for (int i = 0; i < arrays.length; i++) {
                    Asserts.assertEquals(length(arrays[i]), i);
                    Asserts.assertEquals(lengthInCatch(arrays[i]), i);
                }
                Asserts.assertEquals(lengthOfAllocation(), 37);
            }
            Asserts.assertEquals(lengthInCatch(null), -1);
            Asserts.assertEquals(lengthOfNonArray(), -2);
            for (Object value : new Object[] {new Object(), "array", Integer.valueOf(3), int[].class}) {
                Asserts.assertEquals(lengthInCatch(value), -2);
            }
            // A failed array guard must restore the argument before Java reexecution.
            for (int i = 0; i < arrays.length; i++) {
                Asserts.assertEquals(length(arrays[i]), i);
            }
            try {
                newArrayAtDimensionLimit(0);
                throw new AssertionError("creating a 256-dimensional array must fail");
            } catch (IllegalArgumentException expected) {
            }
            try {
                newArrayAtDimensionLimit(-1);
                throw new AssertionError("negative length must be checked before array dimensions");
            } catch (NegativeArraySizeException expected) {
            }
            System.out.println("reflective lengths verified");
        }
    }
}
