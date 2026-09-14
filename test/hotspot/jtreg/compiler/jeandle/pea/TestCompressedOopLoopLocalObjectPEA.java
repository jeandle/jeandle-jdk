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
 * @summary PEA keeps compressed reference fields as wide semantic oops and
 *          eliminates a loop-local three-capture object across a throwing call.
 * @library /test/lib /
 * @build jdk.test.lib.Asserts
 * @run main/othervm
 *      compiler.jeandle.pea.TestCompressedOopLoopLocalObjectPEA
 */

package compiler.jeandle.pea;

import compiler.jeandle.fileCheck.FileCheck;
import java.util.ArrayList;
import java.util.List;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestCompressedOopLoopLocalObjectPEA {
    public static void main(String[] args) throws Exception {
        String dumpPath = System.getProperty("user.dir");
        String wrapper =
                "compiler.jeandle.pea.TestCompressedOopLoopLocalObjectPEA$TestWrapper";
        ArrayList<String> commandArgs = new ArrayList<>(List.of(
                "-Xbatch", "-XX:-TieredCompilation", "-XX:CompileThreshold=1",
                "-XX:+UseJeandleCompiler", "-XX:+JeandleDoPEA",
                "-XX:+UseCompressedOops", "-XX:+UseCompressedClassPointers",
                "-XX:JeandleLLVMOptions=-jeandle-dump-pea-stats",
                "-XX:+JeandleDumpIR", "-XX:JeandleDumpDirectory=" + dumpPath,
                "-XX:CompileCommand=compileonly," + wrapper + "::test",
                "-XX:CompileCommand=dontinline," + wrapper + "::opaque",
                wrapper));

        ProcessBuilder pb =
                ProcessTools.createLimitedTestJavaProcessBuilder(commandArgs);
        OutputAnalyzer output = ProcessTools.executeCommand(pb);
        output.shouldHaveExitValue(0);
        output.shouldContain("TestCompressedOopLoopLocalObjectPEA result: 448");

        FileCheck after = new FileCheck(
                dumpPath,
                TestWrapper.class.getMethod(
                        "test", Object.class, Object.class, Object.class, int.class),
                /*optimized=*/true);
        after.checkPattern("define hotspotcc i32 .*test.*");
        after.check("ret i32");
        after.checkNot("jeandle.new_instance");

        boolean sawEliminatedAllocation = false;
        for (String line : output.getOutput().split("\\R")) {
            if (line.contains("PEA stats @")
                    && line.contains(
                            "TestCompressedOopLoopLocalObjectPEA$TestWrapper_test")
                    && line.matches(".*NeverEscapes=[1-9][0-9]*.*")) {
                sawEliminatedAllocation = true;
                break;
            }
        }
        Asserts.assertTrue(sawEliminatedAllocation,
                "PEA did not eliminate the loop-local compressed-oop object");
    }

    public static class TestWrapper {
        private static final class Captures {
            final Object first;
            final Object second;
            final Object third;

            Captures(Object first, Object second, Object third) {
                this.first = first;
                this.second = second;
                this.third = third;
            }
        }

        static int opaque(int value) {
            return value & 1;
        }

        public static int test(
                Object first, Object second, Object third, int count) {
            int result = 0;
            for (int i = 0; i < count; i++) {
                Captures captures = new Captures(first, second, third);
                try {
                    result += opaque(i);
                } catch (RuntimeException unexpected) {
                    result--;
                }
                if (captures.first == first) {
                    result++;
                }
                if (captures.second == second) {
                    result++;
                }
                if (captures.third == third) {
                    result++;
                }
            }
            return result;
        }

        public static void main(String[] args) {
            Object first = new Object();
            Object second = new Object();
            Object third = new Object();
            int result = 0;
            for (int i = 0; i < 20_000; i++) {
                result = test(first, second, third, 128);
            }
            System.out.println(
                    "TestCompressedOopLoopLocalObjectPEA result: " + result);
            Asserts.assertEquals(result, 448);
        }
    }
}
