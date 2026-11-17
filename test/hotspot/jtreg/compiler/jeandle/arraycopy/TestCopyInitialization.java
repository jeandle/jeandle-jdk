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
 * @summary Fresh copies initialize padding, preserve checked copies, and scalarize small clones
 * @requires vm.gc.Serial & vm.gc.G1
 * @library /test/lib /
 * @build compiler.jeandle.fileCheck.FileCheck jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run driver compiler.jeandle.arraycopy.TestCopyInitialization
 */

package compiler.jeandle.arraycopy;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
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
import jdk.test.whitebox.WhiteBox;

public class TestCopyInitialization {
    private static final String PASS = "COPY_INITIALIZATION_PASS";

    private static Method method(String name, Class<?>... types) throws Exception {
        return Workload.class.getDeclaredMethod(name, types);
    }

    private static List<Method> targets() throws Exception {
        return List.of(
                method("copyFull", Object[].class),
                method("copyConstant", Object[].class),
                method("copyDynamic", Object[].class, int.class),
                method("copyRange", Object[].class, int.class, int.class),
                method("cloneReferences", Object[].class),
                method("zeroCopy", Object[].class, int.class),
                method("zeroRange", Object[].class, int.class, int.class),
                method("checkedCopy", Object[].class, int.class),
                method("checkedRange", Object[].class, int.class, int.class),
                method("smallBoolean", boolean.class),
                method("smallByte", byte.class),
                method("smallChar", char.class),
                method("smallShort", short.class),
                method("smallInt", int.class),
                method("smallLong", long.class),
                method("smallFloat", float.class),
                method("smallDouble", double.class));
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            Workload.run(args[0].equals("no_tlab"));
            return;
        }
        runCase("g1", true, true, true, true);
        runCase("g1_alignment_16", true, true, true, true);
        runCase("g1_alignment_32", true, true, true, true);
        runCase("serial", false, true, true, true);
        runCase("g1_uncompressed", true, false, true, true);
        runCase("serial_no_tlab", false, true, false, true);
        runCase("bulk_zeroing_disabled", true, true, true, false);
        runCase("g1_initial_card_marks", true, true, true, true);
    }

    private static void runCase(String name, boolean g1, boolean compressed,
                                boolean tlab, boolean reduceBulkZeroing) throws Exception {
        Path dump = Files.createTempDirectory("copy_initialization_" + name);
        ArrayList<String> command = new ArrayList<>(List.of(
                "-Xbatch", "-XX:-BackgroundCompilation", "-XX:-TieredCompilation",
                "-XX:CompileThreshold=1000000", "-XX:+UseJeandleCompiler",
                "-XX:+UnlockDiagnosticVMOptions", "-XX:+WhiteBoxAPI", "-Xbootclasspath/a:.",
                "-XX:-JeandleDoPEA", "-XX:-ZeroTLAB",
                name.equals("g1_initial_card_marks") ? "-XX:+ReduceInitialCardMarks" : "-XX:-ReduceInitialCardMarks",
                "-XX:+CIPrintCompilerName", "-Xlog:jeandle=debug,jit+compilation=debug",
                "-XX:+JeandleDumpIR", "-XX:JeandleDumpDirectory=" + dump,
                "-Xms32m", "-Xmx32m", g1 ? "-XX:+UseG1GC" : "-XX:+UseSerialGC",
                compressed ? "-XX:+UseCompressedOops" : "-XX:-UseCompressedOops",
                compressed ? "-XX:+UseCompressedClassPointers" : "-XX:-UseCompressedClassPointers",
                tlab ? "-XX:+UseTLAB" : "-XX:-UseTLAB",
                reduceBulkZeroing ? "-XX:+ReduceBulkZeroing" : "-XX:-ReduceBulkZeroing"));
        if (name.startsWith("g1_alignment_")) {
            command.add("-XX:ObjectAlignmentInBytes=" + name.substring("g1_alignment_".length()));
        }
        if (!tlab) {
            command.add("-XX:PretenureSizeThreshold=32");
        }
        for (Method target : targets()) {
            command.add("-XX:CompileCommand=compileonly," + Workload.class.getName()
                    + "::" + target.getName());
            command.add("-XX:CompileCommand=dontinline," + Workload.class.getName()
                    + "::" + target.getName());
        }
        command.add(TestCopyInitialization.class.getName());
        command.add(tlab ? "workload" : "no_tlab");
        OutputAnalyzer output = ProcessTools.executeProcess(
                ProcessTools.createLimitedTestJavaProcessBuilder(command));
        output.shouldHaveExitValue(0).shouldContain(PASS).shouldContain("ZERO_COPY_NO_CLASS_TRAP")
                .shouldContain("CHECKED_COPY_COMPILED");
        for (Method target : targets()) {
            output.shouldMatch("Jeandle:.*" + Pattern.quote(Workload.class.getName()
                    + "::" + target.getName()) + " \\([0-9]+ bytes\\)");
            FileCheck raw = new FileCheck(dump.toString(), target, false);
            raw.checkPatternAnywhere("invoke.*@jeandle\\.new_array");
            raw.checkPatternAnywhere("(?:call|invoke).*@jeandle\\.copy");
            FileCheck optimized = new FileCheck(dump.toString(), target, true);
            optimized.checkNotPattern("(?:call|invoke).*@jeandle\\.copy\\(");
            optimized.checkPatternAnywhere("ret ptr addrspace\\(1\\)");
            if (target.getName().startsWith("small")) {
                optimized.checkNotPattern("(?:call|invoke).*@StubRoutines_jlong_disjoint_arraycopy");
            }
        }
        checkCopyIR(dump, "copyFull", reduceBulkZeroing, tlab, Object[].class);
        if (name.equals("g1_initial_card_marks")) {
            FileCheck clone = new FileCheck(dump.toString(), method("cloneReferences", Object[].class), true);
            clone.checkPatternAnywhere("call.*@StubRoutines_jlong_disjoint_arraycopy");
            clone.checkNotPattern("call.*@zero_heap_words_stub|call.*@llvm\\.memset");
        } else {
            checkCopyIR(dump, "cloneReferences", reduceBulkZeroing, tlab, Object[].class);
        }
        for (String nameWithTail : List.of("copyDynamic", "copyRange")) {
            Method target = nameWithTail.equals("copyDynamic")
                    ? method(nameWithTail, Object[].class, int.class)
                    : method(nameWithTail, Object[].class, int.class, int.class);
            FileCheck optimized = new FileCheck(dump.toString(), target, true);
            if (reduceBulkZeroing) {
                optimized.checkPatternAnywhere("arraycopy\\.zero_tail");
                optimized.checkPatternAnywhere("call.*@zero_heap_words_stub");
                optimized.checkNotPattern("call.*@llvm\\.memset");
            }
        }
        for (String checked : List.of("checkedCopy", "checkedRange")) {
            Method target = checked.equals("checkedCopy")
                    ? method(checked, Object[].class, int.class)
                    : method(checked, Object[].class, int.class, int.class);
            FileCheck optimized = new FileCheck(dump.toString(), target, true);
            optimized.checkPatternAnywhere("call.*@StubRoutines_checkcast_arraycopy");
        }
    }

    private static void checkCopyIR(Path dump, String name, boolean reduceBulkZeroing,
                                    boolean tlab, Class<?>... types) throws Exception {
        FileCheck optimized = new FileCheck(dump.toString(), method(name, types), true);
        optimized.checkPatternAnywhere("call.*@StubRoutines_(?:arrayof_)?oop_disjoint_arraycopy");
        if (reduceBulkZeroing) {
            optimized.checkNotPattern("call.*@zero_heap_words_stub");
            optimized.checkNotPattern("call.*@llvm\\.memset");
        } else if (tlab) {
            optimized.checkPatternAnywhere("call.*@zero_heap_words_stub");
        }
    }

    static class Workload {
        private static volatile Object[][] retained;

        static Object[] copyFull(Object[] source) {
            return Arrays.copyOf(source, source.length, Object[].class);
        }

        static Object[] copyConstant(Object[] source) {
            return Arrays.copyOf(source, 7, Object[].class);
        }

        static Object[] copyDynamic(Object[] source, int length) {
            return Arrays.copyOf(source, length, Object[].class);
        }

        static Object[] copyRange(Object[] source, int from, int to) {
            return Arrays.copyOfRange(source, from, to, Object[].class);
        }

        static Object[] cloneReferences(Object[] source) {
            return source.clone();
        }

        static String[] zeroCopy(Object[] source, int length) {
            return Arrays.copyOf(source, length, String[].class);
        }

        static String[] zeroRange(Object[] source, int from, int to) {
            return Arrays.copyOfRange(source, from, to, String[].class);
        }

        static String[] checkedCopy(Object[] source, int length) {
            return Arrays.copyOf(source, length, String[].class);
        }

        static String[] checkedRange(Object[] source, int from, int to) {
            return Arrays.copyOfRange(source, from, to, String[].class);
        }

        static boolean[] smallBoolean(boolean value) {
            return new boolean[] {value}.clone();
        }

        static byte[] smallByte(byte value) {
            return new byte[] {value}.clone();
        }

        static char[] smallChar(char value) {
            return new char[] {value, 'x', value}.clone();
        }

        static short[] smallShort(short value) {
            return new short[] {value, -3, value}.clone();
        }

        static int[] smallInt(int value) {
            return new int[] {value, -17, value}.clone();
        }

        static long[] smallLong(long value) {
            return new long[] {value}.clone();
        }

        static float[] smallFloat(float value) {
            return new float[] {value, -0.0f, value}.clone();
        }

        static double[] smallDouble(double value) {
            return new double[] {value}.clone();
        }

        static void run(boolean noTlab) throws Exception {
            // Resolve the library calls and array classes before requesting compilation.
            Object marker = new Object();
            Arrays.copyOf(new String[] {"linked"}, 0, String[].class);
            Arrays.copyOfRange(new String[] {"linked"}, 0, 1, Object[].class);
            Object[] linked = new Object[] {marker}.clone();
            Asserts.assertSame(linked[0], marker);
            WhiteBox wb = WhiteBox.getWhiteBox();
            for (Method target : targets()) {
                compile(wb, target);
            }
            verifyZeroCopy(wb);
            for (int iteration = 0; iteration < 512; iteration++) {
                Object a = new Marker(iteration);
                Object b = new Marker(-iteration);
                Object[] source = {a, null, b};
                checkReferences(copyFull(source), source, 0, 3);
                checkReferences(copyConstant(source), source, 0, 7);
                int length = iteration % 9;
                checkReferences(copyDynamic(source, length), source, 0, length);
                int from = iteration % 4;
                checkReferences(copyRange(source, from, from + length), source, from, length);
                checkReferences(cloneReferences(source), source, 0, 3);
                checkReferences(copyDynamic(new Object[0], length), new Object[0], 0, length);
                verifySmallClones(iteration);
            }
            verifyCheckedCopies(wb);
            WeakReference<Marker> weak = createRetainedCopies();
            if (noTlab) {
                Asserts.assertTrue(wb.isObjectInOldGen(retained[0]), "copyOf destination must be old");
                Asserts.assertTrue(wb.isObjectInOldGen(retained[1]), "range destination must be old");
            }
            wb.youngGC();
            Marker surviving = weak.get();
            Asserts.assertNotNull(surviving, "copied references must keep the young marker alive");
            Asserts.assertEquals(surviving.value, 73);
            for (Object[] copy : retained) {
                Asserts.assertSame(copy[0], surviving, "reference must survive collection");
                for (int index = 1; index < copy.length; index++) {
                    Asserts.assertNull(copy[index], "padding must remain null after collection");
                }
            }
            for (Method target : targets()) {
                Asserts.assertTrue(wb.isMethodCompiled(target), "target must remain compiled: " + target);
            }
            System.out.println(PASS);
        }

        private static void compile(WhiteBox wb, Method target) {
            if (!wb.isMethodCompiled(target)) {
                Asserts.assertTrue(wb.enqueueMethodForCompilation(target, 4), "enqueue " + target);
                Asserts.assertTrue(wb.isMethodCompiled(target), "compile " + target);
            }
        }

        private static void verifyZeroCopy(WhiteBox wb) throws Exception {
            Object[] incompatible = {Integer.valueOf(7)};
            Method copy = method("zeroCopy", Object[].class, int.class);
            Method range = method("zeroRange", Object[].class, int.class, int.class);
            int before = wb.getDeoptCount("class_check", null);
            Asserts.assertEquals(zeroCopy(incompatible, 0).length, 0);
            String[] padded = zeroRange(incompatible, 1, 6);
            Asserts.assertEquals(padded.getClass(), String[].class);
            Asserts.assertEquals(padded.length, 5);
            for (String value : padded) {
                Asserts.assertNull(value, "zero-element incompatible copy must be null padded");
            }
            Asserts.assertEquals(wb.getDeoptCount("class_check", null), before,
                    "zero-element copy must not speculate source array assignability");
            Asserts.assertTrue(wb.isMethodCompiled(copy), "zeroCopy must remain compiled");
            Asserts.assertTrue(wb.isMethodCompiled(range), "zeroRange must remain compiled");
            System.out.println("ZERO_COPY_NO_CLASS_TRAP");
        }

        private static void checkReferences(Object[] actual, Object[] source, int from, int length) {
            Asserts.assertTrue(actual != source, "copies must have distinct identity");
            Asserts.assertEquals(actual.length, length);
            for (int index = 0; index < length; index++) {
                Object expected = from + index < source.length ? source[from + index] : null;
                Asserts.assertSame(actual[index], expected, "reference or padding at " + index);
            }
        }

        private static void verifySmallClones(int value) {
            Asserts.assertTrue(Arrays.equals(smallBoolean((value & 1) != 0),
                    new boolean[] {(value & 1) != 0}));
            Asserts.assertTrue(Arrays.equals(smallByte((byte) value), new byte[] {(byte) value}));
            Asserts.assertTrue(Arrays.equals(smallChar((char) value), new char[] {(char) value, 'x', (char) value}));
            Asserts.assertTrue(Arrays.equals(smallShort((short) value),
                    new short[] {(short) value, -3, (short) value}));
            Asserts.assertTrue(Arrays.equals(smallInt(value), new int[] {value, -17, value}));
            long wide = 0x7fff000000000000L | value;
            Asserts.assertTrue(Arrays.equals(smallLong(wide), new long[] {wide}));
            float fractional = value + 0.25f;
            Asserts.assertTrue(Arrays.equals(smallFloat(fractional), new float[] {fractional, -0.0f, fractional}));
            double precise = value + 0.125;
            Asserts.assertTrue(Arrays.equals(smallDouble(precise), new double[] {precise}));
        }

        private static void verifyCheckedCopies(WhiteBox wb) throws Exception {
            Method copy = method("checkedCopy", Object[].class, int.class);
            Method range = method("checkedRange", Object[].class, int.class, int.class);
            Object[] good = {"checked"};
            boolean compiledPath = false;
            for (int attempt = 0; attempt < 12; attempt++) {
                compile(wb, copy);
                compile(wb, range);
                int before = wb.getDeoptCount("class_check", null);
                String[] copied = checkedCopy(good, 5);
                String[] ranged = checkedRange(good, 0, 5);
                checkReferences(copied, good, 0, 5);
                checkReferences(ranged, good, 0, 5);
                if (wb.isMethodCompiled(copy) && wb.isMethodCompiled(range)
                        && wb.getDeoptCount("class_check", null) == before) {
                    compiledPath = true;
                    break;
                }
            }
            Asserts.assertTrue(compiledPath, "checked copies must reach a stable compiled fallback");
            Object[] bad = {"checked", new Object()};
            Asserts.assertThrows(ArrayStoreException.class, () -> checkedCopy(bad, 5));
            Asserts.assertThrows(ArrayStoreException.class, () -> checkedRange(bad, 0, 5));
            checkReferences(checkedCopy(good, 5), good, 0, 5);
            checkReferences(checkedRange(good, 0, 5), good, 0, 5);
            System.out.println("CHECKED_COPY_COMPILED");
        }

        private static WeakReference<Marker> createRetainedCopies() {
            Marker marker = new Marker(73);
            Object[] source = {marker};
            retained = new Object[][] {copyDynamic(source, 12), copyRange(source, 0, 12), cloneReferences(source)};
            return new WeakReference<>(marker);
        }
    }

    static final class Marker {
        final int value;

        Marker(int value) {
            this.value = value;
        }
    }
}
