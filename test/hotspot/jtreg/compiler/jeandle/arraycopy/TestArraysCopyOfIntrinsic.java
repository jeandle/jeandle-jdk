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
 * @test
 * @summary Verify Jeandle Arrays.copyOf/copyOfRange exact-array fast path and fallback
 * @library /test/lib /
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run driver compiler.jeandle.intrinsic.TestArraysCopyOfIntrinsic
 */

package compiler.jeandle.intrinsic;

import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;
import jdk.test.whitebox.WhiteBox;

public class TestArraysCopyOfIntrinsic {
    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            Workload.prelink();
            if (args[0].equals("checkcast")) {
                Workload.runCheckcast();
            } else if (args[0].equals("barrier")) {
                Workload.runBarrier();
            } else {
                Workload.run();
            }
            return;
        }
        runComparison("G1 compressed", "-XX:+UseG1GC");
        runComparison("G1 uncompressed", "-XX:+UseG1GC", "-XX:-UseCompressedOops");
        runComparison("Serial", "-XX:+UseSerialGC");
        compare(runCase(true, true, "-XX:+UseG1GC"),
                runCase(true, false, "-XX:+UseG1GC"),
                "ReduceBulkZeroing stub selection");
        runCheckcastCase(true, "-XX:+UseG1GC");
        runCheckcastCase(true, "-XX:+UseSerialGC");
        runCheckcastCase(false, "-XX:+UseG1GC");
        runBarrierCase("-XX:+UseG1GC", 200_000, "-XX:G1HeapRegionSize=1m");
        runBarrierCase("-XX:+UseSerialGC", 1_024, "-XX:PretenureSizeThreshold=64");
    }

    private static void runComparison(String label, String... gcOptions) throws Exception {
        RunResult enabled = runCase(true, true, gcOptions);
        compare(enabled, runCase(false, true, gcOptions), label + " Java fallback");
        compare(enabled, runC2Case(gcOptions), label + " C2 baseline");
    }

    private static void compare(RunResult actual, RunResult expected, String label) {
        Asserts.assertEquals(actual.checksum, expected.checksum,
                label + ": results differ");
    }

    private static RunResult runCase(boolean enabled, boolean reduceBulkZeroing,
                                     String... gcOptions) throws Exception {
        Path dump = Files.createTempDirectory("jeandle_copyof_");
        ArrayList<String> command = new ArrayList<>(List.of(
                "-Xbatch", "-Xcomp", "-XX:-TieredCompilation",
                "-XX:-BackgroundCompilation", "-XX:+UseJeandleCompiler",
                reduceBulkZeroing ? "-XX:+ReduceBulkZeroing" : "-XX:-ReduceBulkZeroing",
                "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dump,
                "-XX:CompileCommand=compileonly,compiler.jeandle.intrinsic.TestArraysCopyOfIntrinsic$Workload::copyOfExact",
                "-XX:CompileCommand=compileonly,compiler.jeandle.intrinsic.TestArraysCopyOfIntrinsic$Workload::copyOfRangeExact"));
        command.addAll(List.of(gcOptions));
        if (!enabled) {
            command.add("-XX:+UnlockDiagnosticVMOptions");
            command.add("-XX:ControlIntrinsic=-_copyOf,-_copyOfRange");
        }
        command.add(TestArraysCopyOfIntrinsic.class.getName());
        command.add("regular");
        OutputAnalyzer output = ProcessTools.executeProcess(
                ProcessTools.createLimitedTestJavaProcessBuilder(command));
        output.shouldHaveExitValue(0);
        String text = output.getOutput();
        if (enabled) {
            Asserts.assertTrue(text.contains("static jobject java.util.Arrays.copyOf(jobject, jint, jobject)")
                            && text.contains("is parsed as intrinsic"),
                    "missing Arrays.copyOf intrinsic log: " + text);
            Asserts.assertTrue(text.contains("static jobject java.util.Arrays.copyOfRange(jobject, jint, jint, jobject)")
                            && text.contains("is parsed as intrinsic"),
                    "missing Arrays.copyOfRange intrinsic log: " + text);
        } else {
            Asserts.assertFalse(text.contains("java.util.Arrays.copyOf(jobject, jint, jobject)")
                            && text.contains("is parsed as intrinsic"),
                    "copyOf unexpectedly lowered when disabled: " + text);
            Asserts.assertFalse(text.contains("java.util.Arrays.copyOfRange(jobject, jint, jint, jobject)")
                            && text.contains("is parsed as intrinsic"),
                    "copyOfRange unexpectedly lowered when disabled: " + text);
            Asserts.assertTrue(containsOptimizedMethod(
                            dump, "copyOfExact", "define hotspotcc"),
                    "disabled Arrays.copyOf wrapper was not compiled");
            Asserts.assertTrue(containsOptimizedMethod(
                            dump, "copyOfRangeExact", "define hotspotcc"),
                    "disabled Arrays.copyOfRange wrapper was not compiled");
            Asserts.assertTrue(containsOptimizedMethod(
                            dump, "copyOfExact",
                            "java_util_Arrays_copyOf([Ljava/lang/Object;ILjava/lang/Class;)[Ljava/lang/Object;"),
                    "disabled Arrays.copyOf did not retain the Java fallback call");
            Asserts.assertTrue(containsOptimizedMethod(
                            dump, "copyOfRangeExact",
                            "java_util_Arrays_copyOfRange([Ljava/lang/Object;IILjava/lang/Class;)[Ljava/lang/Object;"),
                    "disabled Arrays.copyOfRange did not retain the Java fallback call");
        }
        boolean copyOfUninitialized = containsOptimizedMethod(
                dump, "copyOfExact",
                "call void @StubRoutines_arrayof_oop_disjoint_arraycopy_uninit(");
        boolean copyOfRangeUninitialized = containsOptimizedMethod(
                dump, "copyOfRangeExact",
                "call void @StubRoutines_oop_disjoint_arraycopy_uninit(")
                || containsOptimizedMethod(
                        dump, "copyOfRangeExact",
                        "call void @StubRoutines_arrayof_oop_disjoint_arraycopy_uninit(");
        boolean expectUninitialized = enabled && reduceBulkZeroing;
        Asserts.assertEquals(expectUninitialized, copyOfUninitialized,
                "unexpected Arrays.copyOf uninitialized-disjoint stub call");
        Asserts.assertEquals(expectUninitialized, copyOfRangeUninitialized,
                "unexpected Arrays.copyOfRange uninitialized-disjoint stub call");
        if (enabled) {
            boolean copyOfInitialized = containsOptimizedMethod(
                    dump, "copyOfExact",
                    "call void @StubRoutines_arrayof_oop_disjoint_arraycopy(");
            boolean copyOfRangeInitialized = containsOptimizedMethod(
                    dump, "copyOfRangeExact",
                    "call void @StubRoutines_oop_disjoint_arraycopy(")
                    || containsOptimizedMethod(
                            dump, "copyOfRangeExact",
                            "call void @StubRoutines_arrayof_oop_disjoint_arraycopy(");
            Asserts.assertEquals(!reduceBulkZeroing, copyOfInitialized,
                    "unexpected Arrays.copyOf initialized-destination stub call");
            Asserts.assertEquals(!reduceBulkZeroing, copyOfRangeInitialized,
                    "unexpected Arrays.copyOfRange initialized-destination stub call");
            Asserts.assertFalse(containsOptimizedMethod(
                            dump, "copyOfExact", "@jeandle.arraycopy")
                            || containsOptimizedMethod(
                                    dump, "copyOfRangeExact", "@jeandle.arraycopy"),
                    "Arrays.copyOf pseudo call survived specialization");
        }
        String checksum = text.lines().filter(line -> line.startsWith("CHECKSUM="))
                .map(line -> line.substring("CHECKSUM=".length())).findFirst().orElseThrow();
        return new RunResult(checksum);
    }

    private static RunResult runC2Case(String... gcOptions) throws Exception {
        ArrayList<String> command = new ArrayList<>(List.of(
                "-Xbatch", "-Xcomp", "-XX:-TieredCompilation",
                "-XX:-BackgroundCompilation", "-XX:-UseJeandleCompiler",
                "-XX:CompileCommand=compileonly,compiler.jeandle.intrinsic.TestArraysCopyOfIntrinsic$Workload::copyOfExact",
                "-XX:CompileCommand=compileonly,compiler.jeandle.intrinsic.TestArraysCopyOfIntrinsic$Workload::copyOfRangeExact"));
        command.addAll(List.of(gcOptions));
        command.add(TestArraysCopyOfIntrinsic.class.getName());
        command.add("regular");
        OutputAnalyzer output = ProcessTools.executeProcess(
                ProcessTools.createLimitedTestJavaProcessBuilder(command));
        output.shouldHaveExitValue(0);
        String checksum = output.getOutput().lines()
                .filter(line -> line.startsWith("CHECKSUM="))
                .map(line -> line.substring("CHECKSUM=".length()))
                .findFirst().orElseThrow();
        return new RunResult(checksum);
    }

    private static void runCheckcastCase(boolean reduceBulkZeroing,
                                         String gcOption) throws Exception {
        Path dump = Files.createTempDirectory("jeandle_copyof_checkcast_");
        ArrayList<String> command = new ArrayList<>(List.of(
                "-Xbatch", "-Xcomp", "-XX:-TieredCompilation",
                "-XX:-BackgroundCompilation", "-XX:+UseJeandleCompiler",
                reduceBulkZeroing ? "-XX:+ReduceBulkZeroing" : "-XX:-ReduceBulkZeroing",
                "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dump,
                "-XX:CompileCommand=compileonly,compiler.jeandle.intrinsic.TestArraysCopyOfIntrinsic$Workload::copyOfCheckcast",
                "-XX:CompileCommand=compileonly,compiler.jeandle.intrinsic.TestArraysCopyOfIntrinsic$Workload::copyOfRangeCheckcast",
                gcOption,
                TestArraysCopyOfIntrinsic.class.getName(),
                "checkcast"));
        OutputAnalyzer output = ProcessTools.executeProcess(
                ProcessTools.createLimitedTestJavaProcessBuilder(command));
        output.shouldHaveExitValue(0);
        output.shouldContain("CHECKCAST_OK");

        String text = output.getOutput();
        Asserts.assertTrue(text.contains("static jobject java.util.Arrays.copyOf(jobject, jint, jobject)")
                        && text.contains("is parsed as intrinsic"),
                "missing Arrays.copyOf intrinsic log: " + text);
        Asserts.assertTrue(text.contains("static jobject java.util.Arrays.copyOfRange(jobject, jint, jint, jobject)")
                        && text.contains("is parsed as intrinsic"),
                "missing Arrays.copyOfRange intrinsic log: " + text);
        boolean copyOfUninitialized = containsOptimizedMethod(
                dump, "copyOfCheckcast",
                "call i32 @StubRoutines_checkcast_arraycopy_uninit(");
        boolean copyOfRangeUninitialized = containsOptimizedMethod(
                dump, "copyOfRangeCheckcast",
                "call i32 @StubRoutines_checkcast_arraycopy_uninit(");
        boolean copyOfInitialized = containsOptimizedMethod(
                dump, "copyOfCheckcast",
                "call i32 @StubRoutines_checkcast_arraycopy(");
        boolean copyOfRangeInitialized = containsOptimizedMethod(
                dump, "copyOfRangeCheckcast",
                "call i32 @StubRoutines_checkcast_arraycopy(");
        Asserts.assertEquals(reduceBulkZeroing, copyOfUninitialized,
                "unexpected Arrays.copyOf uninitialized checkcast stub call");
        Asserts.assertEquals(reduceBulkZeroing, copyOfRangeUninitialized,
                "unexpected Arrays.copyOfRange uninitialized checkcast stub call");
        Asserts.assertEquals(!reduceBulkZeroing, copyOfInitialized,
                "unexpected Arrays.copyOf initialized checkcast stub call");
        Asserts.assertEquals(!reduceBulkZeroing, copyOfRangeInitialized,
                "unexpected Arrays.copyOfRange initialized checkcast stub call");
    }

    private static void runBarrierCase(String gcOption, int length,
                                       String... allocationOptions) throws Exception {
        ArrayList<String> command = new ArrayList<>(List.of(
                "-Xbootclasspath/a:.", "-Xms64m", "-Xmx64m",
                "-Xbatch", "-Xcomp", "-XX:-TieredCompilation",
                "-XX:-BackgroundCompilation", "-XX:+UseJeandleCompiler",
                "-XX:+UnlockDiagnosticVMOptions", "-XX:+WhiteBoxAPI",
                "-XX:-ReduceInitialCardMarks", "-XX:-UseTLAB",
                "-Dcopyof.barrier.length=" + length,
                "-Xlog:jeandle=debug,gc=debug",
                "-XX:CompileCommand=compileonly,compiler.jeandle.intrinsic.TestArraysCopyOfIntrinsic$Workload::copyOfExact",
                "-XX:CompileCommand=compileonly,compiler.jeandle.intrinsic.TestArraysCopyOfIntrinsic$Workload::copyOfRangeExact",
                gcOption));
        command.addAll(List.of(allocationOptions));
        command.add(TestArraysCopyOfIntrinsic.class.getName());
        command.add("barrier");
        OutputAnalyzer output = ProcessTools.executeProcess(
                ProcessTools.createLimitedTestJavaProcessBuilder(command));
        output.shouldHaveExitValue(0)
                .shouldContain("COPYOF_BARRIER_OK")
                .shouldContain("is parsed as intrinsic");
    }

    private static boolean containsOptimizedMethod(
            Path dump, String method, String marker) throws Exception {
        try (Stream<Path> paths = Files.walk(dump)) {
            return paths.filter(path -> {
                        String name = path.getFileName().toString();
                        return name.contains("_" + method + "_")
                                && name.endsWith("_optimized.ll");
                    })
                    .anyMatch(path -> {
                        try {
                            return Files.readString(path).contains(marker);
                        } catch (Exception exception) {
                            throw new RuntimeException(exception);
                        }
                    });
        }
    }

    private static class Workload {
        private static final String[] SOURCE = new String[] {"zero", "one", "two", "three"};
        private static Object[] copyOfBarrierRoot;
        private static Object[] copyOfRangeBarrierRoot;
        private static WeakReference<BarrierMarker> copyOfBarrierWeak;
        private static WeakReference<BarrierMarker> copyOfRangeBarrierWeak;
        private static int barrierIndex;

        static void prelink() {
            // With -Xcomp, resolve both callee Method*s before the two wrapper
            // methods are first compiled. This keeps the test focused on their
            // lowering rather than an unrelated unresolved-call deoptimization.
            Arrays.copyOf(SOURCE, 0, Object[].class);
            Arrays.copyOfRange(SOURCE, 0, 0, Object[].class);
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        static Object[] copyOfExact(Object[] source, int length, Class type) {
            return Arrays.copyOf(source, length, type);
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        static Object[] copyOfRangeExact(Object[] source, int from, int to, Class type) {
            return Arrays.copyOfRange(source, from, to, type);
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        static Object[] copyOfCheckcast(Object[] source, int length, Class type) {
            return Arrays.copyOf(source, length, type);
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        static Object[] copyOfRangeCheckcast(Object[] source, int from, int to, Class type) {
            return Arrays.copyOfRange(source, from, to, type);
        }

        static void run() {
            long checksum = 0;
            for (int iteration = 0; iteration < 200; iteration++) {
                String[] grown = (String[]) copyOfExact(SOURCE, 6, String[].class);
                Asserts.assertEquals(grown.getClass(), String[].class,
                        "copyOf exact runtime type");
                Asserts.assertEquals(grown.length, 6, "copyOf length");
                Asserts.assertEquals(grown[iteration & 3], SOURCE[iteration & 3], "copyOf element");
                Asserts.assertNull(grown[4], "copyOf zero tail");

                String[] range = (String[]) copyOfRangeExact(SOURCE, 1, 6, String[].class);
                Asserts.assertEquals(range.getClass(), String[].class,
                        "copyOfRange exact runtime type");
                Asserts.assertEquals(range.length, 5, "copyOfRange length");
                Asserts.assertEquals(range[0], "one", "copyOfRange first");
                Asserts.assertNull(range[3], "copyOfRange zero tail");
                checksum = Long.rotateLeft(checksum ^ grown[0].hashCode() ^ range[0].hashCode(), 3);
            }

            // A destination whose element type is wider than the source is
            // eligible for the validated fast path.
            Object[] widened = copyOfExact(SOURCE, 5, Object[].class);
            Asserts.assertEquals(widened.getClass(), Object[].class,
                    "copyOf covariant runtime type");
            Asserts.assertEquals(widened[2], "two", "covariant fallback");
            Asserts.assertNull(widened[4], "covariant fallback tail");

            Object[] mixed = {"ok", new Object()};
            Asserts.assertThrows(ArrayStoreException.class,
                    () -> copyOfExact(mixed, 2, String[].class));

            // Invalid range cases are deliberately deoptimized so Java chooses
            // the precise exception type/order.
            Asserts.assertThrows(IllegalArgumentException.class,
                    () -> copyOfRangeExact(SOURCE, 3, 1, String[].class));
            Asserts.assertThrows(ArrayIndexOutOfBoundsException.class,
                    () -> copyOfRangeExact(SOURCE, -1, 2, String[].class));
            Asserts.assertThrows(NullPointerException.class,
                    () -> copyOfExact(null, 2, String[].class));
            Asserts.assertThrows(NullPointerException.class,
                    () -> copyOfExact(SOURCE, 2, null));
            Asserts.assertThrows(NegativeArraySizeException.class,
                    () -> copyOfExact(SOURCE, -1, String[].class));
            Asserts.assertThrows(NullPointerException.class,
                    () -> copyOfRangeExact(null, 0, 2, String[].class));
            Asserts.assertThrows(NullPointerException.class,
                    () -> copyOfRangeExact(SOURCE, 0, 2, null));
            // A raw primitive-array mirror is legal at the erased signature,
            // but the Java implementation ultimately fails its T[] cast.
            Asserts.assertThrows(ClassCastException.class,
                    () -> copyOfExact(SOURCE, 2, int[].class));
            System.out.println("CHECKSUM=" + Long.toUnsignedString(checksum, 16));
        }

        static void runCheckcast() {
            Object[] good = {"ok"};
            long checksum = 0;
            // The first incompatible source/destination Klass pair takes the
            // class-check uncommon trap. Repeated calls make that trap hot so
            // recompilation uses the checkcast_arraycopy_uninit stub instead.
            for (int iteration = 0; iteration < 20_000; iteration++) {
                String[] copy = (String[]) copyOfCheckcast(
                        good, good.length, String[].class);
                String[] range = (String[]) copyOfRangeCheckcast(
                        good, 0, good.length, String[].class);
                Asserts.assertEquals(copy.getClass(), String[].class,
                        "copyOf checkcast runtime type");
                Asserts.assertEquals(range.getClass(), String[].class,
                        "copyOfRange checkcast runtime type");
                Asserts.assertEquals(copy[0], "ok", "copyOf checkcast element");
                Asserts.assertEquals(range[0], "ok", "copyOfRange checkcast element");
                checksum += copy.length + range.length;
            }

            Object marker = new Object();
            Object[] mixed = {"ok", marker};
            Asserts.assertThrows(ArrayStoreException.class,
                    () -> copyOfCheckcast(mixed, mixed.length, String[].class));
            Asserts.assertThrows(ArrayStoreException.class,
                    () -> copyOfRangeCheckcast(
                            mixed, 0, mixed.length, String[].class));
            System.out.println("CHECKCAST_OK=" + checksum);
        }

        static void runBarrier() {
            createBarrierFixture();
            WhiteBox.getWhiteBox().youngGC();

            BarrierMarker copyOfMarker = copyOfBarrierWeak.get();
            BarrierMarker copyOfRangeMarker = copyOfRangeBarrierWeak.get();
            Asserts.assertNotNull(copyOfMarker,
                    "copyOf young referent was not discovered through the old destination array");
            Asserts.assertNotNull(copyOfRangeMarker,
                    "copyOfRange young referent was not discovered through the old destination array");
            Asserts.assertSame(copyOfBarrierRoot[barrierIndex], copyOfMarker,
                    "copyOf destination lost its young referent");
            Asserts.assertSame(copyOfRangeBarrierRoot[barrierIndex], copyOfRangeMarker,
                    "copyOfRange destination lost its young referent");
            System.out.println("COPYOF_BARRIER_OK");
        }

        private static void createBarrierFixture() {
            WhiteBox whiteBox = WhiteBox.getWhiteBox();
            int length = Integer.getInteger("copyof.barrier.length");
            barrierIndex = length / 2;

            BarrierMarker copyOfMarker = new BarrierMarker(0x5a17c0de);
            BarrierMarker copyOfRangeMarker = new BarrierMarker(0x6b28d1ef);
            Object[] copyOfSource = new Object[length];
            Object[] copyOfRangeSource = new Object[length];
            copyOfSource[barrierIndex] = copyOfMarker;
            copyOfRangeSource[barrierIndex] = copyOfRangeMarker;

            Asserts.assertTrue(whiteBox.isObjectInOldGen(copyOfSource),
                    "copyOf source must be allocated in the old generation");
            Asserts.assertTrue(whiteBox.isObjectInOldGen(copyOfRangeSource),
                    "copyOfRange source must be allocated in the old generation");
            Asserts.assertFalse(whiteBox.isObjectInOldGen(copyOfMarker),
                    "copyOf referent must initially be young");
            Asserts.assertFalse(whiteBox.isObjectInOldGen(copyOfRangeMarker),
                    "copyOfRange referent must initially be young");

            copyOfBarrierRoot = copyOfExact(copyOfSource, length, Object[].class);
            copyOfRangeBarrierRoot = copyOfRangeExact(
                    copyOfRangeSource, 0, length, Object[].class);
            Asserts.assertTrue(whiteBox.isObjectInOldGen(copyOfBarrierRoot),
                    "copyOf destination must be allocated in the old generation");
            Asserts.assertTrue(whiteBox.isObjectInOldGen(copyOfRangeBarrierRoot),
                    "copyOfRange destination must be allocated in the old generation");

            copyOfBarrierWeak = new WeakReference<>(copyOfMarker);
            copyOfRangeBarrierWeak = new WeakReference<>(copyOfRangeMarker);
            copyOfSource[barrierIndex] = null;
            copyOfRangeSource[barrierIndex] = null;
        }

        private static final class BarrierMarker {
            final int value;

            BarrierMarker(int value) {
                this.value = value;
            }
        }
    }

    private record RunResult(String checksum) { }
}
