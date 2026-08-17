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
 * You should have received a copy of the GNU General Public License
 * version 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 */

/*
 * @test
 * @summary PEA virtual receiver of instanceof: jeandle.instanceof stays
 *          lower-phase="0" so its expansion exposes the null check (folded
 *          by foldICmpEquality) and jeandle.check_instanceof (folded by
 *          foldCheckCast). The klass read behind the instanceof must NOT
 *          force the receiver to materialize.
 * @library /test/lib /
 * @build jdk.test.lib.Asserts
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.pea.TestInstanceOfVirtualObjectPEA
 */

package compiler.jeandle.pea;

import compiler.jeandle.fileCheck.FileCheck;
import java.util.ArrayList;
import java.util.List;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestInstanceOfVirtualObjectPEA {
    public static void main(String[] args) throws Exception {
        String dump_path = System.getProperty("user.dir");
        String wrapper = "compiler.jeandle.pea.TestInstanceOfVirtualObjectPEA$TestWrapper";
        ArrayList<String> command_args = new ArrayList<String>(List.of(
                "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
                "-XX:+JeandleDoPEA",
                "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dump_path,
                "-XX:-UseCompressedOops", "-XX:-UseCompressedClassPointers",
                "-XX:CompileCommand=compileonly," + wrapper + "::test",
                wrapper));

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(command_args);
        OutputAnalyzer output = ProcessTools.executeCommand(pb);
        output.shouldHaveExitValue(0);

        // p is virtual and instanceof-folded: the allocation is eliminated
        // even though the method performs a subtype query on it.
        FileCheck checker = new FileCheck(dump_path,
                TestWrapper.class.getMethod("test"),
                /*optimized=*/true);
        checker.checkPattern("define hotspotcc.*i32 .*test.*");
        checker.checkNot("jeandle.new_instance");

        output.shouldContain("TestInstanceOfVirtualObjectPEA result: 42");
    }

    public static class TestWrapper {
        public static class Point { public int x; }

        public static void main(String[] args) {
            // Initialize Point so test() compiles a real body (no class-init
            // trap stub).
            new Point();
            int r = test();
            System.out.println("TestInstanceOfVirtualObjectPEA result: " + r);
            Asserts.assertEquals(r, 42);
        }

        // Compiled by Jeandle. p never escapes: the instanceof folds to a
        // compile-time constant (Point's exact klass is known), so the
        // allocation and both field accesses fold away.
        public static int test() {
            Point p = new Point();
            p.x = 42;
            if (p instanceof Point) {
                return p.x;
            }
            return -1;
        }
    }
}
