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
 * @summary Publish observed normal entry profiles without treating training trips as semantic bounds
 * @library /test/lib /
 * @build jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *      -Xbatch -XX:-TieredCompilation -XX:+UseJeandleCompiler -XX:+UseOnStackReplacement
 *      -XX:CompileThreshold=1000 -XX:+JeandleDumpIR
 *      -XX:CompileCommand=compileonly,compiler.jeandle.checks.TestFunctionEntryProfile::test*
 *      compiler.jeandle.checks.TestFunctionEntryProfile true
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *      -Xbatch -XX:-TieredCompilation -XX:+UseJeandleCompiler -XX:+UseOnStackReplacement
 *      -XX:CompileThreshold=1000 -XX:+JeandleDumpIR -XX:-JeandleUseProfile
 *      -XX:CompileCommand=compileonly,compiler.jeandle.checks.TestFunctionEntryProfile::test*
 *      compiler.jeandle.checks.TestFunctionEntryProfile false
 */
package compiler.jeandle.checks;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jdk.test.lib.Asserts;
import jdk.test.whitebox.WhiteBox;

public class TestFunctionEntryProfile {
    private static final WhiteBox WB = WhiteBox.getWhiteBox();
    private static int effects;

    public static int testCold(int value) {
        return value * 3 + 1;
    }

    public static int testHot(int[] a, int end, int offset) {
        int sum = 0;
        for (int i = 0; i < end; i++) {
            effects++;
            sum += a[3 * i + offset];
        }
        return sum;
    }

    public static int testOsr(int[] a, int end) {
        int sum = 0;
        for (int i = 0; i < end; i++) {
            effects++;
            sum += a[i & 7];
        }
        return sum;
    }

    private static int reference(int[] a, int end, int offset) {
        int sum = 0;
        for (int i = 0; i < end; i++) {
            effects++;
            sum += a[3 * i + offset];
        }
        return sum;
    }

    private static String input(Method method, boolean osr) throws Exception {
        String stem = method.getDeclaringClass().getName().replace('.', '_') + "_" + method.getName();
        try (var files = Files.list(Path.of(System.getProperty("user.dir")))) {
            for (Path path : files.filter(p -> p.getFileName().toString().startsWith(stem + "_")
                    && p.toString().endsWith(".ll") && !p.toString().endsWith("_optimized.ll"))
                    .sorted(Comparator.reverseOrder()).toList()) {
                String text = Files.readString(path);
                if (text.lines().anyMatch(line -> line.startsWith("define ")
                        && line.contains(stem + "(") && line.contains(".root")
                        && line.contains("__jeandle_osr") == osr)) {
                    return text;
                }
            }
        }
        throw new AssertionError("missing target input: " + method.getName() + " osr=" + osr);
    }

    private static void entryProfile(Method method, boolean osr, String ir, boolean expected) {
        String stem = method.getDeclaringClass().getName().replace('.', '_') + "_" + method.getName();
        String header = ir.lines().filter(line -> line.startsWith("define ")
                && line.contains(stem + "(") && line.contains(".root")
                && line.contains("__jeandle_osr") == osr).findFirst().orElseThrow();
        Matcher reference = Pattern.compile("!prof !(\\d+)").matcher(header);
        Asserts.assertEquals(reference.find(), expected, "entry profile on " + method.getName() + " osr=" + osr);
        if (expected) {
            Matcher count = Pattern.compile("(?m)^!" + reference.group(1)
                    + " = !\\{!\"function_entry_count\", i64 (\\d+)\\}$").matcher(ir);
            Asserts.assertTrue(count.find(), "real entry-count node referenced by target root");
            long value = Long.parseLong(count.group(1));
            Asserts.assertTrue(value > 1 && value < Integer.MAX_VALUE, "observed unsaturated entry count");
        }
    }

    private static void checkCase(int[] a, int end, int offset) {
        effects = 0;
        int expected = 0;
        Class<?> failure = null;
        try {
            expected = reference(a, end, offset);
        } catch (RuntimeException exception) {
            failure = exception.getClass();
        }
        int expectedEffects = effects;
        effects = 0;
        try {
            int actual = testHot(a, end, offset);
            Asserts.assertNull(failure, "expected exception");
            Asserts.assertEquals(actual, expected, "array result beyond training distribution");
        } catch (RuntimeException exception) {
            Asserts.assertEquals(exception.getClass(), failure, "exception type");
        }
        Asserts.assertEquals(effects, expectedEffects, "effects before return or exception");
    }

    public static void main(String[] args) throws Exception {
        boolean profile = Boolean.parseBoolean(args[0]);
        Method cold = TestFunctionEntryProfile.class.getDeclaredMethod("testCold", int.class);
        WB.enqueueMethodForCompilation(cold, 4);
        Asserts.assertTrue(WB.isMethodCompiled(cold), "cold ordinary target compiled");
        String coldInput = input(cold, false);
        Asserts.assertEquals(testCold(7), 22);
        entryProfile(cold, false, coldInput, false);

        int[] a = new int[256];
        for (int i = 0; i < a.length; i++) a[i] = i * 37 + 11;
        Method hot = TestFunctionEntryProfile.class.getDeclaredMethod("testHot", int[].class, int.class, int.class);
        for (int i = 0; i < 20000; i++) {
            int offset = i & 1;
            Asserts.assertEquals(testHot(a, 2, offset), a[offset] + a[3 + offset]);
        }
        WB.enqueueMethodForCompilation(hot, 4);
        Asserts.assertTrue(WB.isMethodCompiled(hot), "profiled ordinary target compiled");
        String hotInput = input(hot, false);
        checkCase(a, 82, 1);
        Asserts.assertTrue(WB.isMethodCompiled(hot), "larger trip input executes the installed target");
        checkCase(null, 0, Integer.MAX_VALUE);
        checkCase(a, -3, 1);
        checkCase(a, 100, 1);
        checkCase(a, 2, Integer.MAX_VALUE);
        checkCase(null, 2, 1);

        Method osr = TestFunctionEntryProfile.class.getDeclaredMethod("testOsr", int[].class, int.class);
        int[] ring = {1, 2, 3, 4, 5, 6, 7, 8};
        effects = 0;
        Asserts.assertEquals(testOsr(ring, 200000), 900000);
        Asserts.assertEquals(effects, 200000);
        Asserts.assertTrue(WB.isMethodCompiled(osr, true), "OSR installed during the live first invocation");
        int osrBci = WB.getMethodEntryBci(osr);
        Asserts.assertTrue(osrBci >= 0, "observed OSR entry BCI");
        for (int i = 0; i < 20000; i++) Asserts.assertEquals(testOsr(ring, 2), 3);
        WB.enqueueMethodForCompilation(osr, 4);
        Asserts.assertTrue(WB.isMethodCompiled(osr), "ordinary entry of the trained OSR target compiled");
        String osrNormalInput = input(osr, false);
        WB.deoptimizeMethod(osr, true);
        WB.enqueueMethodForCompilation(osr, 4, osrBci);
        Asserts.assertTrue(WB.isMethodCompiled(osr, true), "OSR recompiled after ordinary invocation training");
        entryProfile(osr, true, input(osr, true), false);

        entryProfile(osr, false, osrNormalInput, profile);
        entryProfile(hot, false, hotInput, profile);
        System.out.println("FUNCTION_ENTRY_PROFILE_EXECUTED " + profile);
    }
}
