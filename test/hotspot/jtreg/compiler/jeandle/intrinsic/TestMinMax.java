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
 *
 */

/*
 * @test
 * @key randomness
 * @requires os.arch=="amd64" | os.arch=="x86_64" | os.arch=="aarch64"
 * @summary Test the intrinsics implementation of Math/StrictMath.min|max(int,int)
 * @library /test/lib /
 * @run main/othervm compiler.jeandle.intrinsic.TestMinMax
 */

package compiler.jeandle.intrinsic;

import compiler.jeandle.fileCheck.FileCheck;
import jdk.test.lib.Asserts;
import jdk.test.lib.Utils;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class TestMinMax {
    public static void main(String[] args) throws Exception {
        String dumpPath = Files.createTempDirectory("jeandle_min_max").toString();

        ArrayList<String> commandArgs = new ArrayList<>(List.of(
                "-Xbatch",
                "-XX:-TieredCompilation",
                "-XX:+UseJeandleCompiler",
                "-Xcomp",
                "-Xlog:jeandle=debug",
                "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dumpPath,
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::min_int",
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::max_int",
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::min_int_strict",
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::max_int_strict",
                TestWrapper.class.getName()));

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(commandArgs);
        OutputAnalyzer output = ProcessTools.executeCommand(pb);

        output.shouldHaveExitValue(0)
              .shouldContain("Method `static jint java.lang.Math.min(jint, jint)` is parsed as intrinsic")
              .shouldContain("Method `static jint java.lang.Math.max(jint, jint)` is parsed as intrinsic")
              .shouldContain("Method `static jint java.lang.StrictMath.min(jint, jint)` is parsed as intrinsic")
              .shouldContain("Method `static jint java.lang.StrictMath.max(jint, jint)` is parsed as intrinsic");

        // Verify the llvm intrinsic is used for each variant.
        FileCheck minCheck = new FileCheck(dumpPath, TestWrapper.class.getMethod("min_int", int.class, int.class), false);
        minCheck.checkPattern("call i32 @llvm.smin.i32");

        FileCheck maxCheck = new FileCheck(dumpPath, TestWrapper.class.getMethod("max_int", int.class, int.class), false);
        maxCheck.checkPattern("call i32 @llvm.smax.i32");

        FileCheck minStrictCheck = new FileCheck(dumpPath, TestWrapper.class.getMethod("min_int_strict", int.class, int.class), false);
        minStrictCheck.checkPattern("call i32 @llvm.smin.i32");

        FileCheck maxStrictCheck = new FileCheck(dumpPath, TestWrapper.class.getMethod("max_int_strict", int.class, int.class), false);
        maxStrictCheck.checkPattern("call i32 @llvm.smax.i32");
    }

    static class TestWrapper {
        static final int DUMMY1 = Math.min(0, 0);       // force load java.lang.Math
        static final int DUMMY2 = StrictMath.min(0, 0); // force load java.lang.StrictMath

        public static void main(String[] args) {
            var random = Utils.getRandomInstance();

            // Basic values
            Asserts.assertEquals(3, min_int(3, 5), "min(3,5)");
            Asserts.assertEquals(5, max_int(3, 5), "max(3,5)");
            Asserts.assertEquals(-5, min_int(-3, -5), "min(-3,-5)");
            Asserts.assertEquals(-3, max_int(-3, -5), "max(-3,-5)");
            Asserts.assertEquals(-3, min_int(-3, 5), "min(-3,5)");
            Asserts.assertEquals(5, max_int(-3, 5), "max(-3,5)");

            // Equal values
            Asserts.assertEquals(5, min_int(5, 5), "min(5,5)");
            Asserts.assertEquals(5, max_int(5, 5), "max(5,5)");

            // Zero
            Asserts.assertEquals(0, min_int(0, 0), "min(0,0)");
            Asserts.assertEquals(-1, min_int(0, -1), "min(0,-1)");
            Asserts.assertEquals(0, max_int(0, -1), "max(0,-1)");

            // Boundary values
            Asserts.assertEquals(Integer.MIN_VALUE, min_int(Integer.MIN_VALUE, Integer.MAX_VALUE), "min(MIN,MAX)");
            Asserts.assertEquals(Integer.MAX_VALUE, max_int(Integer.MIN_VALUE, Integer.MAX_VALUE), "max(MIN,MAX)");
            Asserts.assertEquals(Integer.MIN_VALUE, min_int(Integer.MIN_VALUE, Integer.MIN_VALUE), "min(MIN,MIN)");
            Asserts.assertEquals(Integer.MAX_VALUE, max_int(Integer.MAX_VALUE, Integer.MAX_VALUE), "max(MAX,MAX)");

            // StrictMath variants share the exact same semantics as Math for ints.
            Asserts.assertEquals(3, min_int_strict(3, 5), "strictMin(3,5)");
            Asserts.assertEquals(5, max_int_strict(3, 5), "strictMax(3,5)");
            Asserts.assertEquals(Integer.MIN_VALUE, min_int_strict(Integer.MIN_VALUE, Integer.MAX_VALUE), "strictMin(MIN,MAX)");
            Asserts.assertEquals(Integer.MAX_VALUE, max_int_strict(Integer.MIN_VALUE, Integer.MAX_VALUE), "strictMax(MIN,MAX)");

            // Random values
            for (int i = 0; i < 2000; i++) {
                int x = random.nextInt();
                int y = random.nextInt();
                int expectedMin = (x <= y) ? x : y;
                int expectedMax = (x >= y) ? x : y;
                Asserts.assertEquals(expectedMin, min_int(x, y), "min(" + x + "," + y + ")");
                Asserts.assertEquals(expectedMax, max_int(x, y), "max(" + x + "," + y + ")");
                Asserts.assertEquals(expectedMin, min_int_strict(x, y), "strictMin(" + x + "," + y + ")");
                Asserts.assertEquals(expectedMax, max_int_strict(x, y), "strictMax(" + x + "," + y + ")");
            }
        }

        public static int min_int(int a, int b) {
            return Math.min(a, b);
        }

        public static int max_int(int a, int b) {
            return Math.max(a, b);
        }

        public static int min_int_strict(int a, int b) {
            return StrictMath.min(a, b);
        }

        public static int max_int_strict(int a, int b) {
            return StrictMath.max(a, b);
        }
    }
}
