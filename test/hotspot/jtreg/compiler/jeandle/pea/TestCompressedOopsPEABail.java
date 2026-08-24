/*
 * Copyright (c) 2026, the Jeandle-JDK Authors. All Rights Reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public
 * License version 2 for more details (a copy is included in the LICENSE
 * file that accompanied this code).
 *
 * You should have received a copy of the GNU General Public License
 * along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 */

/*
 * @test
 * @summary PEA compressed-oops graceful bail: with the
 *          DEFAULT VM configuration (UseCompressedOops +
 *          UseCompressedClassPointers ON), PEA must skip cleanly instead of
 *          crashing in getOrCreateFieldIndex (fastdebug assert / release
 *          miscompile). The module DataLayout narrow-oop gate in
 *          PartialEscapeAnalysis::run makes the whole analysis idle, so the
 *          program runs correctly under -XX:+JeandleDoPEA.
 * @library /test/lib /
 * @build jdk.test.lib.Asserts
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.pea.TestCompressedOopsPEABail
 */

package compiler.jeandle.pea;

import java.util.ArrayList;
import java.util.List;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestCompressedOopsPEABail {
    public static void main(String[] args) throws Exception {
        String wrapper = "compiler.jeandle.pea.TestCompressedOopsPEABail$TestWrapper";
        ArrayList<String> command_args = new ArrayList<String>(List.of(
                "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
                // PEA explicitly ON; compressed oops left at their DEFAULT
                // (enabled) values — the bug's production configuration.
                "-XX:+JeandleDoPEA",
                "-XX:CompileCommand=compileonly," + wrapper + "::test",
                wrapper));

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(command_args);
        OutputAnalyzer output = ProcessTools.executeCommand(pb);
        // Pre-fix: SIGSEGV / abort in getOrCreateFieldIndex (exit != 0).
        output.shouldHaveExitValue(0);
        output.shouldContain("TestCompressedOopsPEABail result: 900030000");
    }

    public static class TestWrapper {
        public static class Point { public int x; public int y; public Point next; }

        // Allocation- and reference-field-heavy so PEA would virtualize
        // aggressively if it ran — the bail must not affect semantics.
        public static int test(int n) {
            int sum = 0;
            for (int i = 0; i < n; i++) {
                Point p = new Point();
                p.x = i;
                p.y = i + 1;
                p.next = p;
                sum += p.x + p.y + (p.next == p ? 1 : 0);
            }
            return sum;
        }

        public static void main(String[] args) {
            new Point(); // init class
            int r = test(30000); // sum(2i+2, i<30000) = 30000*29999 + 60000
            System.out.println("TestCompressedOopsPEABail result: " + r);
            Asserts.assertEquals(r, 900030000);
        }
    }
}
