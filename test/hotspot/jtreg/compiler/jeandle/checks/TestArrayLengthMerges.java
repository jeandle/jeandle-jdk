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
 * @summary Reuse immutable lengths available on both sides of a Java diamond
 * @library /test/lib /
 * @build compiler.jeandle.fileCheck.FileCheck jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *      -Xbatch -XX:-TieredCompilation -XX:+UseJeandleCompiler -XX:CompileThreshold=100
 *      -XX:+JeandleDumpIR -XX:CompileCommand=compileonly,compiler.jeandle.checks.TestArrayLengthMerges::testDiamond
 *      -XX:CompileCommand=dontinline,compiler.jeandle.checks.TestArrayLengthMerges::touch
 *      compiler.jeandle.checks.TestArrayLengthMerges
 */
package compiler.jeandle.checks;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import jdk.test.lib.Asserts;
import jdk.test.whitebox.WhiteBox;

public class TestArrayLengthMerges {
    private static volatile int sink;

    static void touch(int[] a) {
        a[0]++;
        sink = a[0];
    }

    public static int testDiamond(int[] a, boolean branch) {
        int length;
        if (branch) { length = a.length; touch(a); }
        else { touch(a); length = a.length; }
        touch(a);
        return length + a.length;
    }

    public static void main(String[] args) throws Exception {
        int[] a = new int[32];
        for (int i = 0; i < 20000; i++) {
            Asserts.assertEquals(testDiamond(a, (i & 1) == 0), 64);
        }
        Method target = TestArrayLengthMerges.class.getDeclaredMethod("testDiamond", int[].class, boolean.class);
        Asserts.assertTrue(WhiteBox.getWhiteBox().isMethodCompiled(target));
        Path dump;
        try (var paths = Files.list(Path.of(System.getProperty("user.dir")))) {
            dump = paths.filter(p -> p.getFileName().toString().startsWith(
                    "compiler_jeandle_checks_TestArrayLengthMerges_testDiamond_")
                    && p.getFileName().toString().endsWith("_optimized.ll"))
                    .max(Comparator.naturalOrder()).orElseThrow();
        }
        long lengths = Files.readAllLines(dump).stream()
                .filter(line -> line.contains("load atomic i32") && line.contains("!range")).count();
        Asserts.assertTrue(lengths > 0 && lengths <= 2, "no third immutable header read after merge: " + lengths);
        int before = a[0];
        Asserts.assertEquals(testDiamond(a, true), 64);
        Asserts.assertEquals(testDiamond(a, false), 64);
        Asserts.assertEquals(a[0], before + 4, "element writes survive length reuse");
        System.out.println("LENGTH_MERGE_EXECUTED headerLoads=" + lengths);
    }
}
