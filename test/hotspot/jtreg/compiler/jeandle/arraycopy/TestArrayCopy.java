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
 * @test id=semantic
 * @summary Verify System.arraycopy semantics and Jeandle lowering
 * @library /test/lib /
 * @build compiler.jeandle.fileCheck.FileCheck
 * @run driver TestArrayCopy
 */

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import compiler.jeandle.fileCheck.FileCheck;
import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestArrayCopy {
    private static final String INTRINSIC_LOG =
            "Method `static void java.lang.System.arraycopy(jobject, jint, jobject, jint, jint)`"
                    + " is parsed as intrinsic";

    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            runSemantics();
            return;
        }

        runCase("enabled", true, null);
        runCase("control_intrinsic_disabled", false,
                "-XX:ControlIntrinsic=-_arraycopy");
        runCase("inline_natives_disabled", false, "-XX:-InlineNatives");
    }

    private static void runCase(String name, boolean intrinsicEnabled,
                                String additionalVmOption) throws Exception {
        Path dumpPath = Files.createTempDirectory("jeandle_arraycopy_" + name + "_ir");
        List<String> command = new ArrayList<>(List.of(
                "-Xbatch", "-Xcomp", "-XX:-TieredCompilation", "-XX:-BackgroundCompilation",
                "-XX:+UseJeandleCompiler", "-Xlog:jeandle=debug,jit+compilation=debug",
                "-XX:CompileCommand=compileonly," + TestMethods.class.getName() + "::*",
                "-XX:CompileCommand=dontinline," + TestMethods.class.getName() + "::*",
                "-XX:+UnlockDiagnosticVMOptions", "-XX:+CIPrintCompilerName",
                "-XX:+JeandleDumpIR", "-XX:JeandleDumpDirectory=" + dumpPath));
        if (additionalVmOption != null) {
            command.add(additionalVmOption);
        }
        command.add(TestMethods.class.getName());
        command.add("child");

        OutputAnalyzer output = ProcessTools.executeCommand(
                ProcessTools.createLimitedTestJavaProcessBuilder(command));
        output.shouldHaveExitValue(0).shouldContain("ARRAYCOPY_PASS");
        if (intrinsicEnabled) {
            output.shouldContain(INTRINSIC_LOG);
            checkInstalledByJeandle(output, "copyInts");
            checkInstalledByJeandle(output, "copyObjects");
        } else {
            output.shouldNotContain(INTRINSIC_LOG);
        }

        FileCheck raw = new FileCheck(dumpPath.toString(),
                TestMethods.class.getDeclaredMethod("copyInts", int[].class, int.class,
                        int[].class, int.class, int.class), false);
        if (intrinsicEnabled) {
            raw.checkNotPattern("call hotspotcc .*@jeandle\\.assume_java_type");
            raw.checkPattern("invoke hotspotcc void @jeandle\\.arraycopy");
            raw.checkPattern("attributes .*jeandle\\.arraycopy\\.validated");
        } else {
            raw.checkNotPattern("(?:call|invoke) hotspotcc void @jeandle\\.arraycopy");
        }
    }

    private static void checkInstalledByJeandle(OutputAnalyzer output, String method) {
        output.shouldMatch("(?s).*Jeandle:.*"
                + Pattern.quote(TestMethods.class.getName() + "::" + method) + ".*");
    }

    private static void runSemantics() {
        primitiveCopies();
        referenceCopies();
        overlappingCopies();
        exceptionSemantics();
        System.out.println("ARRAYCOPY_PASS");
    }

    private static void primitiveCopies() {
        int[] ints = {1, 2, 3, 4, 5};
        int[] intDst = new int[ints.length];
        for (int i = 0; i < 20_000; i++) {
            TestMethods.copyInts(ints, 0, intDst, 0, ints.length);
        }
        Asserts.assertTrue(Arrays.equals(ints, intDst), "int array copy");

        byte[] bytes = {Byte.MIN_VALUE, -1, 0, 1, Byte.MAX_VALUE};
        byte[] byteDst = new byte[bytes.length];
        TestMethods.copy(bytes, 0, byteDst, 0, bytes.length);
        Asserts.assertTrue(Arrays.equals(bytes, byteDst), "byte array copy");

        short[] shorts = {Short.MIN_VALUE, -1, 0, 1, Short.MAX_VALUE};
        short[] shortDst = new short[shorts.length];
        TestMethods.copy(shorts, 0, shortDst, 0, shorts.length);
        Asserts.assertTrue(Arrays.equals(shorts, shortDst), "short array copy");

        char[] chars = {Character.MIN_VALUE, 'a', '\uffff'};
        char[] charDst = new char[chars.length];
        TestMethods.copy(chars, 0, charDst, 0, chars.length);
        Asserts.assertTrue(Arrays.equals(chars, charDst), "char array copy");

        long[] longs = {Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE};
        long[] longDst = new long[longs.length];
        TestMethods.copy(longs, 0, longDst, 0, longs.length);
        Asserts.assertTrue(Arrays.equals(longs, longDst), "long array copy");

        float[] floats = {Float.NEGATIVE_INFINITY, -0.0f, 0.0f, Float.NaN};
        float[] floatDst = new float[floats.length];
        TestMethods.copy(floats, 0, floatDst, 0, floats.length);
        assertFloatBitsEqual(floats, floatDst);

        double[] doubles = {Double.NEGATIVE_INFINITY, -0.0d, 0.0d, Double.NaN};
        double[] doubleDst = new double[doubles.length];
        TestMethods.copy(doubles, 0, doubleDst, 0, doubles.length);
        assertDoubleBitsEqual(doubles, doubleDst);

        boolean[] booleans = {true, false, true};
        boolean[] booleanDst = new boolean[booleans.length];
        TestMethods.copy(booleans, 0, booleanDst, 0, booleans.length);
        Asserts.assertTrue(Arrays.equals(booleans, booleanDst), "boolean array copy");
    }

    private static void referenceCopies() {
        Integer[] integers = {1, null, 3, 4};
        Number[] numbers = new Number[integers.length];
        for (int i = 0; i < 20_000; i++) {
            TestMethods.copyObjects(integers, 0, numbers, 0, integers.length);
        }
        Asserts.assertTrue(Arrays.equals(integers, numbers), "reference array copy");

        String[] empty = {"unchanged"};
        TestMethods.copy(new Object[0], 0, empty, 1, 0);
        Asserts.assertEquals(empty[0], "unchanged", "zero-length copy");
    }

    private static void overlappingCopies() {
        int[] backward = {0, 1, 2, 3, 4, 5};
        TestMethods.copyInts(backward, 0, backward, 2, 4);
        Asserts.assertTrue(Arrays.equals(backward, new int[]{0, 1, 0, 1, 2, 3}),
                "overlapping backward copy");

        int[] forward = {0, 1, 2, 3, 4, 5};
        TestMethods.copyInts(forward, 2, forward, 0, 4);
        Asserts.assertTrue(Arrays.equals(forward, new int[]{2, 3, 4, 5, 4, 5}),
                "overlapping forward copy");
    }

    private static void exceptionSemantics() {
        expect(ArrayStoreException.class,
                () -> TestMethods.copy(new int[]{1}, 0, new long[]{0}, 0, 1));
        expect(ArrayStoreException.class,
                () -> TestMethods.copy(new Object[]{"ok", new Object()}, 0,
                        new String[2], 0, 2));
        expect(NullPointerException.class,
                () -> TestMethods.copy(null, 0, new int[1], 0, 0));
        expect(ArrayIndexOutOfBoundsException.class,
                () -> TestMethods.copy(new int[1], -1, new int[1], 0, 1));
        expect(ArrayIndexOutOfBoundsException.class,
                () -> TestMethods.copy(new int[1], 0, new int[1], 0, -1));
    }

    private static void expect(Class<? extends Throwable> type, Runnable action) {
        try {
            action.run();
            throw new AssertionError("missing " + type.getSimpleName());
        } catch (Throwable t) {
            if (!type.isInstance(t)) {
                throw new AssertionError("expected " + type.getName() + ", got " + t, t);
            }
        }
    }

    private static void assertFloatBitsEqual(float[] expected, float[] actual) {
        for (int i = 0; i < expected.length; i++) {
            Asserts.assertEquals(Float.floatToRawIntBits(actual[i]),
                    Float.floatToRawIntBits(expected[i]), "float bits at " + i);
        }
    }

    private static void assertDoubleBitsEqual(double[] expected, double[] actual) {
        for (int i = 0; i < expected.length; i++) {
            Asserts.assertEquals(Double.doubleToRawLongBits(actual[i]),
                    Double.doubleToRawLongBits(expected[i]), "double bits at " + i);
        }
    }

    static class TestMethods {
        static void copyInts(int[] src, int srcPos, int[] dst, int dstPos, int length) {
            System.arraycopy(src, srcPos, dst, dstPos, length);
        }

        static void copyObjects(Object[] src, int srcPos, Object[] dst,
                                int dstPos, int length) {
            System.arraycopy(src, srcPos, dst, dstPos, length);
        }

        static void copy(Object src, int srcPos, Object dst, int dstPos, int length) {
            System.arraycopy(src, srcPos, dst, dstPos, length);
        }

        public static void main(String[] args) {
            runSemantics();
        }
    }
}
