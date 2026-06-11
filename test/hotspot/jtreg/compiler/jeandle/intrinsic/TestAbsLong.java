/*
 * Copyright (c) 2025, 2026, the Jeandle-JDK Authors. All Rights Reserved.
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
 * @library /test/lib /
 * @build jdk.test.lib.Asserts
 * @run main/othervm compiler.jeandle.intrinsic.TestAbsLong
 */

package compiler.jeandle.intrinsic;

import compiler.jeandle.fileCheck.FileCheck;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestAbsLong {
    public static void main(String[] args) throws Exception {
        String dump_path = Files.createTempDirectory("jeandle_test_abslong").toString();
        ArrayList<String> command_args = new ArrayList<String>(List.of(
                "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
                "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dump_path,
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::abs_long",
                TestWrapper.class.getName()));

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(command_args);
        OutputAnalyzer output = ProcessTools.executeCommand(pb);

        output.shouldHaveExitValue(0)
                .shouldContain("is parsed as intrinsic");

        // Verify the llvm intrinsic is used — only check the intrinsic call, not control flow
        FileCheck checker = new FileCheck(dump_path, TestWrapper.class.getMethod("abs_long", long.class), false);
        checker.checkPattern("llvm\\.abs\\.i64");
    }

    static public class TestWrapper {
        static long v = Math.abs(1L); // Force load java.lang.Math class

        public static void main(String[] args) {
            Random random = new Random();
            Asserts.assertEquals(1L, abs_long(1L));
            Asserts.assertEquals(1L, abs_long(-1L));
            Asserts.assertEquals(Long.MAX_VALUE, abs_long(Long.MAX_VALUE));
            Asserts.assertEquals(Long.MIN_VALUE, abs_long(Long.MIN_VALUE)); // overflow corner case
            for (int i = 0; i < 1000; i++) {
                long l = random.nextLong();
                long r = l > 0 ? l : -1 * l;
                Asserts.assertEquals(r, abs_long(l));
            }
        }

        public static long abs_long(long a) {
            return Math.abs(a);
        }
    }
}
