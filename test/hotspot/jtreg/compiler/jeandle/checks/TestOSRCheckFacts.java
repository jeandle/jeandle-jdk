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
 * @summary Live Java OSR locals carry definedness into guarded affine loops
 * @library /test/lib /
 * @build compiler.jeandle.checks.CheckLoopShape jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *      -Xbatch -XX:-TieredCompilation -XX:+UseJeandleCompiler -XX:+UseOnStackReplacement
 *      -XX:CompileThreshold=1000 -XX:+JeandleDumpIR -XX:-JeandleDoPEA
 *      -XX:CompileCommand=compileonly,compiler.jeandle.checks.TestOSRCheckFacts::scaled
 *      compiler.jeandle.checks.TestOSRCheckFacts
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *      -Xbatch -XX:-TieredCompilation -XX:+UseJeandleCompiler -XX:+UseOnStackReplacement
 *      -XX:CompileThreshold=1000 -XX:+JeandleDumpIR -XX:+JeandleDoPEA
 *      -XX:CompileCommand=compileonly,compiler.jeandle.checks.TestOSRCheckFacts::scaled
 *      compiler.jeandle.checks.TestOSRCheckFacts
 */
package compiler.jeandle.checks;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import jdk.test.lib.Asserts;
import jdk.test.whitebox.WhiteBox;

public class TestOSRCheckFacts {
    private static int side;

    public static int scaled(int[] a, int n, int off) {
        int sum = a.length;
        for (int i = 0; i < n; i++) {
            side++;
            sum += a[3 * i + off];
        }
        return sum;
    }

    public static void main(String[] args) throws Exception {
        int n = 500000;
        int[] a = new int[3 * n + 1];
        Arrays.fill(a, 1);
        Asserts.assertEquals(scaled(a, n, 1), 4 * n + 1);
        Asserts.assertEquals(side, n);
        Method target = TestOSRCheckFacts.class.getDeclaredMethod("scaled", int[].class, int.class, int.class);
        Asserts.assertTrue(WhiteBox.getWhiteBox().isMethodCompiled(target, true), "OSR entry compiled and executed");
        List<Path> dumps;
        try (var files = Files.list(Path.of(System.getProperty("user.dir")))) {
            dumps = files.filter(p -> p.getFileName().toString().startsWith(
                    TestOSRCheckFacts.class.getName().replace('.', '_') + "_scaled_"))
                    .filter(p -> p.toString().endsWith(".ll")).toList();
        }
        boolean inputSeen = false;
        boolean optimizedSeen = false;
        for (Path dump : dumps) {
            String ir = Files.readString(dump);
            if (!ir.lines().anyMatch(line -> line.startsWith("define ") && line.contains("__jeandle_osr"))) {
                continue;
            }
            if (dump.toString().endsWith("_optimized.ll")) {
                CheckLoopShape.assertVersioned(target, true);
                optimizedSeen = true;
            } else {
                String migration = ir.substring(ir.indexOf("osr_migration:"), ir.indexOf("\nosr_entry_check", ir.indexOf("osr_migration:")));
                long locals = migration.lines().filter(line -> line.contains("load atomic i32")).count();
                Asserts.assertTrue(locals >= 4, "live integer locals migrated");
                Asserts.assertEquals(migration.lines().filter(line -> line.contains("load atomic i32")
                        && line.contains("!noundef")).count(), locals, "every live integer local is defined");
                inputSeen = true;
            }
        }
        Asserts.assertTrue(inputSeen && optimizedSeen, "input and optimized OSR dumps examined");
        int before = side;
        try {
            scaled(new int[3 * (n / 2) + 1], n, 1);
            throw new AssertionError("missing OSR slow-loop range check");
        } catch (ArrayIndexOutOfBoundsException expected) {
            Asserts.assertEquals(side, before + n / 2 + 1, "partial effects survive the failing iteration");
        }
        before = side;
        Asserts.assertEquals(scaled(a, 0, Integer.MAX_VALUE), a.length);
        try {
            scaled(null, n, 1);
            throw new AssertionError("missing null check");
        } catch (NullPointerException expected) {
            Asserts.assertEquals(side, before, "null fails before loop effects");
        }
        System.out.println("OSR_CHECK_FACTS_EXECUTED " + side);
    }
}
