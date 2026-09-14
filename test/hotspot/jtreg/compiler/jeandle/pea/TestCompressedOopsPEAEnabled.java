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
 * @summary PEA executes with compressed oops and preserves object semantics.
 *          The default UseCompressedOops and UseCompressedClassPointers
 *          configuration must remain enabled while JeandleDoPEA runs.
 *          This guards against the old module-wide compressed-oop bail.
 * @library /test/lib /
 * @build jdk.test.lib.Asserts
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.pea.TestCompressedOopsPEAEnabled
 */

package compiler.jeandle.pea;

import java.util.ArrayList;
import java.util.List;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestCompressedOopsPEAEnabled {
    public static void main(String[] args) throws Exception {
        String wrapper = "compiler.jeandle.pea.TestCompressedOopsPEAEnabled$TestWrapper";
        ArrayList<String> command_args = new ArrayList<String>(List.of(
                "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
                // PEA and both compressed-reference modes are explicitly enabled;
                // compressed-reference values are the production configuration.
                "-XX:+JeandleDoPEA", "-XX:+UseCompressedOops", "-XX:+UseCompressedClassPointers", "-XX:+PrintFlagsFinal",
                "-XX:CompileCommand=compileonly," + wrapper + "::test",
                wrapper));

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(command_args);
        OutputAnalyzer output = ProcessTools.executeCommand(pb);
        // Pre-fix: SIGSEGV / abort in getOrCreateFieldIndex (exit != 0).
        output.shouldHaveExitValue(0);
        output.shouldContain("UseCompressedClassPointers               = true");
        output.shouldContain("UseCompressedOops                        = true");
        output.shouldContain("TestCompressedOopsPEAEnabled result: 900030000");
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
            System.out.println("TestCompressedOopsPEAEnabled result: " + r);
            Asserts.assertEquals(r, 900030000);
        }
    }
}
