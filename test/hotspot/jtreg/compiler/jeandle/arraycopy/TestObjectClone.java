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
 * @summary Verify Object.clone semantics, Jeandle lowering, virtual fallback,
 *          array boundaries, and collector barriers.
 * @library /test/lib /
 * @build compiler.jeandle.fileCheck.FileCheck
 * @run driver TestObjectClone
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

public class TestObjectClone {
    private static final String PASS = "OBJECT_CLONE_PASS";
    private static final String INTRINSIC_LOG =
            "Method `virtual jobject java.lang.Object.clone()` is parsed as intrinsic";

    public static void main(String[] args) throws Exception {
        runCase("jeandle_serial", true, true, "+UseSerialGC", true);
        runCase("jeandle_serial_no_ricm", true, true, "+UseSerialGC", false);
        runCase("jeandle_g1", true, true, "+UseG1GC", true);
        runCase("jeandle_g1_no_ricm", true, true, "+UseG1GC", false);
        runCase("jeandle_clone_disabled", true, false, "+UseG1GC", true);
        runCase("c2_baseline", false, true, "+UseG1GC", true);
    }

    private static void runCase(String name, boolean useJeandle,
                                boolean intrinsicEnabled, String gcOption,
                                boolean reduceInitialCardMarks) throws Exception {
        Path dumpPath = Files.createTempDirectory("object_clone_" + name + "_ir");
        ArrayList<String> command = new ArrayList<>(List.of(
                "-Xbatch", "-Xcomp", "-XX:-TieredCompilation",
                "-XX:-BackgroundCompilation", "-Xms128m", "-Xmx128m",
                "-XX:+UnlockDiagnosticVMOptions",
                useJeandle ? "-XX:+UseJeandleCompiler" : "-XX:-UseJeandleCompiler",
                "-XX:" + gcOption,
                reduceInitialCardMarks
                        ? "-XX:+ReduceInitialCardMarks"
                        : "-XX:-ReduceInitialCardMarks",
                "-XX:CompileCommand=compileonly," + ExactCloneable.class.getName() + "::copy",
                "-XX:CompileCommand=compileonly," + MixedCloneable.class.getName() + "::copy",
                "-XX:CompileCommand=compileonly," + CloneBase.class.getName()
                        + "::cloneThroughBase",
                "-XX:CompileCommand=compileonly," + NonCloneable.class.getName()
                        + "::cloneThroughBase",
                "-XX:CompileCommand=compileonly," + TestMethods.class.getName() + "::*",
                "-XX:CompileCommand=dontinline," + ExactCloneable.class.getName() + "::copy",
                "-XX:CompileCommand=dontinline," + MixedCloneable.class.getName() + "::copy",
                "-XX:CompileCommand=dontinline," + CloneBase.class.getName()
                        + "::cloneThroughBase",
                "-XX:CompileCommand=dontinline," + NonCloneable.class.getName()
                        + "::cloneThroughBase",
                "-XX:CompileCommand=dontinline," + TestMethods.class.getName() + "::*"));

        if (!reduceInitialCardMarks) {
            // Exercise allocation slow paths as well as TLAB allocation.
            command.add("-XX:-UseTLAB");
        }
        if (!intrinsicEnabled) {
            command.add("-XX:ControlIntrinsic=-_clone");
        }
        if (useJeandle) {
            command.add("-Xlog:jeandle=debug,jit+compilation=debug");
            command.add("-XX:+CIPrintCompilerName");
            command.add("-XX:+JeandleDumpIR");
            command.add("-XX:JeandleDumpDirectory=" + dumpPath);
        }
        command.add(Workload.class.getName());

        OutputAnalyzer output = ProcessTools.executeCommand(
                ProcessTools.createLimitedTestJavaProcessBuilder(command));
        output.shouldHaveExitValue(0).shouldContain(PASS);

        if (useJeandle && intrinsicEnabled) {
            output.shouldContain(INTRINSIC_LOG);
            checkInstalledByJeandle(output, ExactCloneable.class, "copy");
            checkInstalledByJeandle(output, MixedCloneable.class, "copy");
            if (gcOption.equals("+UseSerialGC") && reduceInitialCardMarks) {
                FileCheck mixed = new FileCheck(dumpPath.toString(),
                        MixedCloneable.class.getDeclaredMethod("copy"), true);
                // Prove the real VM callback's field list reached scalar copy.
                mixed.checkPatternAnywhere("clone\\.instance\\.load");
                mixed.checkNotPattern("call.*@JeandleRuntime_clone");
                mixed.checkNotPattern("call.*@StubRoutines_jlong_disjoint_arraycopy");
            }
            checkInstalledByJeandle(output, CloneBase.class, "cloneThroughBase");
            checkInstalledByJeandle(output, TestMethods.class, "cloneByteArray");
            checkEnabledIR(dumpPath, reduceInitialCardMarks);
        } else if (useJeandle) {
            output.shouldNotContain(INTRINSIC_LOG);
            checkDisabledIR(dumpPath);
        } else {
            output.shouldNotContain(INTRINSIC_LOG);
        }
    }

    private static void checkInstalledByJeandle(OutputAnalyzer output,
                                                 Class<?> holder,
                                                 String method) {
        output.shouldMatch("(?s).*Jeandle:.*"
                + Pattern.quote(holder.getName() + "::" + method) + ".*");
    }

    private static void checkEnabledIR(Path dumpPath,
                                       boolean reduceInitialCardMarks) throws Exception {
        FileCheck exact = new FileCheck(dumpPath.toString(),
                ExactCloneable.class.getDeclaredMethod("copy"), false);
        exact.checkPatternAnywhere(
                "invoke.*@jeandle\\.new_instance.*\\[ \"deopt\"\\(i64 1,");
        exact.checkNotPattern(
                "invoke.*@jeandle\\.new_instance.*\\[ \"deopt\"\\(i64 0,");
        exact.checkPatternAnywhere(
                "(?:call|invoke).*@jeandle\\.arraycopy.*\\[ \"deopt\"\\(i64 1,");
        exact.checkNotPattern(
                "(?:call|invoke).*@jeandle\\.arraycopy.*\\[ \"deopt\"\\(i64 0,");
        exact.checkNotPattern("call.*@jeandle\\.clone_post_barrier");

        if (!reduceInitialCardMarks) {
            FileCheck optimized = new FileCheck(dumpPath.toString(),
                    ExactCloneable.class.getDeclaredMethod("copy"), true);
            optimized.checkPatternAnywhere(
                    "call void @JeandleRuntime_clone"
                    + "\\(ptr addrspace\\(1\\).*ptr addrspace\\(1\\).*i64 [0-9]+\\)");
            optimized.checkNotPattern(
                    "call void @StubRoutines_jlong_disjoint_arraycopy");
        }

        FileCheck virtual = new FileCheck(dumpPath.toString(),
                CloneBase.class.getDeclaredMethod("cloneThroughBase", CloneBase.class), false);
        virtual.checkPatternAnywhere("clone\\.virtual_target_mismatch");
        virtual.checkPatternAnywhere(
                "invoke.*__jeandle_dynamic_call.*java_lang_Object_clone.*\\[ \"deopt\"\\(i64 1,");
        virtual.checkNotPattern(
                "invoke.*__jeandle_dynamic_call.*java_lang_Object_clone.*\\[ \"deopt\"\\(i64 0,");

        FileCheck array = new FileCheck(dumpPath.toString(),
                TestMethods.class.getDeclaredMethod("cloneByteArray", byte[].class), false);
        array.checkPatternAnywhere(
                "invoke.*@jeandle\\.new_array.*\\[ \"deopt\"\\(i64 1,");
        array.checkNotPattern(
                "invoke.*@jeandle\\.new_array.*\\[ \"deopt\"\\(i64 0,");
        array.checkPatternAnywhere(
                "(?:call|invoke).*@jeandle\\.arraycopy.*\\[ \"deopt\"\\(i64 1,");
        array.checkNotPattern(
                "(?:call|invoke).*@jeandle\\.arraycopy.*\\[ \"deopt\"\\(i64 0,");

        checkPrimitiveArrayFastPath(dumpPath, "cloneByteArray", byte[].class);
        checkPrimitiveArrayFastPath(dumpPath, "cloneShortArray", short[].class);
        checkPrimitiveArrayFastPath(dumpPath, "cloneIntArray", int[].class);
        checkPrimitiveArrayFastPath(dumpPath, "cloneLongArray", long[].class);
        checkPrimitiveArrayFastPath(dumpPath, "cloneBooleanArray", boolean[].class);
        checkPrimitiveArrayFastPath(dumpPath, "cloneCharArray", char[].class);
        checkPrimitiveArrayFastPath(dumpPath, "cloneFloatArray", float[].class);
        checkPrimitiveArrayFastPath(dumpPath, "cloneDoubleArray", double[].class);

        // aaload has no frontend klass metadata. RecoverTypeInfo and CFF must
        // discover the primitive layout without leaving a guaranteed deopt.
        checkPrimitiveArrayFastPath(dumpPath, "cloneIntArrayElement", int[][].class);
        FileCheck dynamic = new FileCheck(dumpPath.toString(),
                TestMethods.class.getDeclaredMethod("cloneObjectArray", Object[].class), false);
        dynamic.checkNotPattern("new_array\\.object_array_layout_matches");
        dynamic.checkNotPattern("new_array\\.class_check_deopt");
        dynamic.checkPatternAnywhere("new_array\\.log2_element_size = and i32");
        dynamic.checkPatternAnywhere(
                "invoke.*@jeandle\\.new_array.*\\[ \"deopt\"\\(i64 1,");
        dynamic.checkNotPattern(
                "invoke.*@jeandle\\.new_array.*\\[ \"deopt\"\\(i64 0,");
        dynamic.checkPatternAnywhere(
                "(?:call|invoke).*@jeandle\\.arraycopy.*\\[ \"deopt\"\\(i64 1,");
        dynamic.checkNotPattern(
                "(?:call|invoke).*@jeandle\\.arraycopy.*\\[ \"deopt\"\\(i64 0,");
        dynamic.checkNotPattern("clone\\.virtual_target_mismatch");
        dynamic.checkNotPattern(
                "__jeandle_dynamic_call.*java_lang_Object_clone");
        if (!reduceInitialCardMarks) {
            // With card marks enabled, oop-array clone uses a distinct
            // exception-producing arraycopy rather than the generic clone copy.
            dynamic.checkPatternAnywhere(
                    "invoke.*@jeandle\\.arraycopy.*\\[ \"deopt\"\\(i64 1,");
        }
    }

    static void checkPrimitiveArrayFastPath(Path dumpPath,
                                                    String methodName,
                                                    Class<?> arrayClass) throws Exception {
        FileCheck raw = new FileCheck(dumpPath.toString(),
                TestMethods.class.getDeclaredMethod(methodName, arrayClass), false);
        raw.checkNotPattern("new_array\\.object_array_layout_matches");
        raw.checkNotPattern("new_array\\.class_check_deopt");
        raw.checkPatternAnywhere("new_array\\.log2_element_size = and i32");
        raw.checkNotPattern("clone\\.virtual_target_mismatch");
        raw.checkNotPattern(
                "__jeandle_dynamic_call.*java_lang_Object_clone");

        FileCheck optimized = new FileCheck(
                dumpPath.toString(),
                TestMethods.class.getDeclaredMethod(methodName, arrayClass),
                true);

        // Keep the optimized primitive-array clone on its allocation/copy
        // fast path. A raw-IR-only check misses regressions where layout
        // folding turns the entire non-null path into a class-check deopt.
        optimized.checkPatternAnywhere(
                "@llvm\\.experimental\\.gc\\.statepoint.*@new_array");
        optimized.checkPatternAnywhere(
                "call void @StubRoutines_jlong_disjoint_arraycopy\\(");
        optimized.checkPatternAnywhere("ret ptr addrspace\\(1\\)");
        optimized.checkNotPattern("new_array\\.log2_element_size = and i32");
    }

    private static void checkDisabledIR(Path dumpPath) throws Exception {
        FileCheck exact = new FileCheck(dumpPath.toString(),
                ExactCloneable.class.getDeclaredMethod("copy"), false);
        exact.checkNotPattern("invoke.*@jeandle\\.new_instance");
        exact.checkNotPattern("call.*@jeandle\\.clone_post_barrier");

        FileCheck virtual = new FileCheck(dumpPath.toString(),
                CloneBase.class.getDeclaredMethod("cloneThroughBase", CloneBase.class), false);
        virtual.checkNotPattern("clone\\.virtual_target_mismatch");
        virtual.checkPatternAnywhere("invoke.*__jeandle_dynamic_call.*java_lang_Object_clone");
    }

    static final class ExactCloneable implements Cloneable {
        int intValue;
        long longValue;
        Object referenceValue;

        ExactCloneable(int intValue, long longValue, Object referenceValue) {
            this.intValue = intValue;
            this.longValue = longValue;
            this.referenceValue = referenceValue;
        }

        ExactCloneable copy() {
            try {
                return (ExactCloneable) super.clone();
            } catch (CloneNotSupportedException exception) {
                throw new AssertionError(exception);
            }
        }
    }

    static class CloneBase implements Cloneable {
        int intValue;
        Object referenceValue;

        CloneBase(int intValue, Object referenceValue) {
            this.intValue = intValue;
            this.referenceValue = referenceValue;
        }

        static Object cloneThroughBase(CloneBase source)
                throws CloneNotSupportedException {
            return source.clone();
        }
    }

    static final class OverridingClone extends CloneBase {
        static int cloneCalls;

        OverridingClone(int intValue, Object referenceValue) {
            super(intValue, referenceValue);
        }

        @Override
        protected Object clone() {
            cloneCalls++;
            return this;
        }
    }

    static final class NonCloneable {
        static Object cloneThroughBase(NonCloneable source)
                throws CloneNotSupportedException {
            return source.clone();
        }
    }

    static final class Marker {
        final int value;

        Marker(int value) {
            this.value = value;
        }
    }

    static final class TestMethods {
        static byte[] cloneByteArray(byte[] source) {
            return source.clone();
        }

        static short[] cloneShortArray(short[] source) {
            return source.clone();
        }

        static int[] cloneIntArray(int[] source) {
            return source.clone();
        }

        static long[] cloneLongArray(long[] source) {
            return source.clone();
        }

        static boolean[] cloneBooleanArray(boolean[] source) { return source.clone(); }
        static char[] cloneCharArray(char[] source) { return source.clone(); }
        static float[] cloneFloatArray(float[] source) { return source.clone(); }
        static double[] cloneDoubleArray(double[] source) { return source.clone(); }
        static int[] cloneIntArrayElement(int[][] source) { return source[0].clone(); }

        static Object[] cloneObjectArray(Object[] source) {
            return source.clone();
        }
    }

    // Eight fields stay within ArrayCopyLoadStoreMaxElem's default threshold.
    // Together with ExactCloneable these exercise every JBasicType encoding
    // and both object and array references through the real VM callback.
    static final class MixedCloneable implements Cloneable {
        boolean flag;
        byte byteValue;
        char charValue;
        short shortValue;
        float floatValue;
        double doubleValue;
        Object reference;
        int[] array;

        MixedCloneable copy() {
            try {
                return (MixedCloneable) super.clone();
            } catch (CloneNotSupportedException e) {
                throw new AssertionError(e);
            }
        }
    }

    static final class Workload {
        private static final int[] LENGTHS = {
                0, 1, 7, 8, 9, 15, 16, 17, 31, 32, 33,
                127, 128, 129, 1023, 1024, 1025, 1_000_003
        };

        public static void main(String[] args) throws Exception {
            if (args.length != 0 && args[0].equals("arrays")) {
                verifyPrimitiveArrayBoundaries();
                verifyObjectArrayBoundaries();
                verifyReferenceBarrierUnderGc();
                System.out.println(PASS);
                return;
            }
            verifyExactClone();
            verifyMixedClone();
            verifyVirtualCloneAndOverride();
            verifyExceptionalReceivers();
            verifyPrimitiveArrayBoundaries();
            verifyObjectArrayBoundaries();
            verifyReferenceBarrierUnderGc();
            System.out.println(PASS);
        }

        private static void verifyExactClone() {
            Object reference = new Object();
            ExactCloneable source = new ExactCloneable(
                    0x12345678, 0x123456789abcdefL, reference);
            ExactCloneable clone = source.copy();
            Asserts.assertNotEquals(clone, source, "instance clone identity");
            Asserts.assertEQ(clone.intValue, source.intValue, "instance int field");
            Asserts.assertEQ(clone.longValue, source.longValue, "instance long field");
            Asserts.assertSame(clone.referenceValue, reference, "instance reference field");

            clone.intValue++;
            clone.longValue++;
            Asserts.assertNE(clone.intValue, source.intValue, "independent int field");
            Asserts.assertNE(clone.longValue, source.longValue, "independent long field");
        }

        private static void verifyMixedClone() {
            int[] floatBits = {0, 0x80000000, 0x7fc12345, 0xff800000, 1};
            long[] doubleBits = {0, 0x8000000000000000L, 0x7ff8123456789abcL,
                                 0xfff0000000000000L, 1};
            for (int i = 0; i < floatBits.length; i++) {
                MixedCloneable source = new MixedCloneable();
                source.flag = (i & 1) != 0;
                source.byteValue = (byte) (0x80 + i);
                source.charValue = (char) (0xff00 + i);
                source.shortValue = (short) (0x8000 + i);
                source.floatValue = Float.intBitsToFloat(floatBits[i]);
                source.doubleValue = Double.longBitsToDouble(doubleBits[i]);
                source.reference = i == 0 ? null : new Object();
                source.array = i == 0 ? null : new int[] {i};
                MixedCloneable clone = source.copy();
                Asserts.assertNotEquals(clone, source, "mixed clone identity");
                Asserts.assertEQ(clone.flag, source.flag, "boolean field");
                Asserts.assertEQ(clone.byteValue, source.byteValue, "byte field");
                Asserts.assertEQ(clone.charValue, source.charValue, "char field");
                Asserts.assertEQ(clone.shortValue, source.shortValue, "short field");
                Asserts.assertEQ(Float.floatToRawIntBits(clone.floatValue),
                        floatBits[i], "float field bits");
                Asserts.assertEQ(Double.doubleToRawLongBits(clone.doubleValue),
                        doubleBits[i], "double field bits");
                Asserts.assertSame(clone.reference, source.reference, "object field");
                Asserts.assertSame(clone.array, source.array, "shallow array field");
                clone.byteValue++;
                clone.reference = new Object();
                Asserts.assertNE(clone.byteValue, source.byteValue, "independent byte field");
                Asserts.assertNotEquals(clone.reference, source.reference,
                        "independent reference slot");
            }
        }

        private static void verifyVirtualCloneAndOverride() throws Exception {
            Object reference = new Object();
            CloneBase source = new CloneBase(42, reference);
            CloneBase clone = (CloneBase) CloneBase.cloneThroughBase(source);
            Asserts.assertNotEquals(clone, source, "virtual Object.clone fast path");
            Asserts.assertEQ(clone.intValue, 42, "virtual clone primitive field");
            Asserts.assertSame(clone.referenceValue, reference,
                    "virtual clone reference field");

            OverridingClone.cloneCalls = 0;
            OverridingClone overriding = new OverridingClone(7, reference);
            for (int i = 1; i <= 10; i++) {
                Object result = CloneBase.cloneThroughBase(overriding);
                Asserts.assertSame(result, overriding,
                        "guard failure must dynamically invoke override");
                Asserts.assertEQ(OverridingClone.cloneCalls, i,
                        "override side effect must be observed exactly once");
            }
        }

        private static void verifyExceptionalReceivers() {
            try {
                CloneBase.cloneThroughBase(null);
                throw new AssertionError("null clone receiver did not throw");
            } catch (NullPointerException expected) {
                // Expected.
            } catch (CloneNotSupportedException exception) {
                throw new AssertionError(exception);
            }

            try {
                NonCloneable.cloneThroughBase(new NonCloneable());
                throw new AssertionError("non-Cloneable receiver did not throw");
            } catch (CloneNotSupportedException expected) {
                // Expected.
            }

            expectArrayCloneNpe();
        }

        private static void expectArrayCloneNpe() {
            try {
                TestMethods.cloneByteArray(null);
                throw new AssertionError("null byte[] clone receiver did not throw");
            } catch (NullPointerException expected) {
                // Expected.
            }
            try {
                TestMethods.cloneShortArray(null);
                throw new AssertionError("null short[] clone receiver did not throw");
            } catch (NullPointerException expected) {
                // Expected.
            }
            try {
                TestMethods.cloneIntArray(null);
                throw new AssertionError("null int[] clone receiver did not throw");
            } catch (NullPointerException expected) {
                // Expected.
            }
            try {
                TestMethods.cloneLongArray(null);
                throw new AssertionError("null long[] clone receiver did not throw");
            } catch (NullPointerException expected) {
                // Expected.
            }
        }

        private static void verifyPrimitiveArrayBoundaries() {
            for (int length : LENGTHS) {
                byte[] bytes = new byte[length];
                short[] shorts = new short[length];
                int[] ints = new int[length];
                long[] longs = new long[length];
                for (int i = 0; i < length; i++) {
                    int value = i * 31 + length;
                    bytes[i] = (byte) value;
                    shorts[i] = (short) (value * 17);
                    ints[i] = value * 65537;
                    longs[i] = ((long) value << 32) ^ (value * 0x9e3779b9L);
                }

                byte[] byteClone = TestMethods.cloneByteArray(bytes);
                short[] shortClone = TestMethods.cloneShortArray(shorts);
                int[] intClone = TestMethods.cloneIntArray(ints);
                long[] longClone = TestMethods.cloneLongArray(longs);
                Asserts.assertNotEquals(byteClone, bytes, "byte[] identity, length=" + length);
                Asserts.assertNotEquals(shortClone, shorts, "short[] identity, length=" + length);
                Asserts.assertNotEquals(intClone, ints, "int[] identity, length=" + length);
                Asserts.assertNotEquals(longClone, longs, "long[] identity, length=" + length);
                Asserts.assertTrue(Arrays.equals(byteClone, bytes),
                        "byte[] contents, length=" + length);
                Asserts.assertTrue(Arrays.equals(shortClone, shorts),
                        "short[] contents, length=" + length);
                Asserts.assertTrue(Arrays.equals(intClone, ints),
                        "int[] contents, length=" + length);
                Asserts.assertTrue(Arrays.equals(longClone, longs),
                        "long[] contents, length=" + length);

                boolean[] booleans = new boolean[length];
                char[] chars = new char[length];
                float[] floats = new float[length];
                double[] doubles = new double[length];
                for (int i = 0; i < length; i++) {
                    booleans[i] = (i & 1) != 0;
                    chars[i] = (char) (i * 31);
                    floats[i] = Float.intBitsToFloat(i * 0x9e3779b9);
                    doubles[i] = Double.longBitsToDouble((long) i * 0x9e3779b97f4a7c15L);
                }
                boolean[] bc = TestMethods.cloneBooleanArray(booleans);
                char[] cc = TestMethods.cloneCharArray(chars);
                float[] fc = TestMethods.cloneFloatArray(floats);
                double[] dc = TestMethods.cloneDoubleArray(doubles);
                int[] nested = TestMethods.cloneIntArrayElement(new int[][] { ints });
                Asserts.assertTrue(bc != booleans && Arrays.equals(bc, booleans));
                Asserts.assertTrue(cc != chars && Arrays.equals(cc, chars));
                Asserts.assertTrue(fc != floats && Arrays.equals(fc, floats));
                Asserts.assertTrue(dc != doubles && Arrays.equals(dc, doubles));
                Asserts.assertTrue(nested != ints && Arrays.equals(nested, ints));
            }
        }

        private static void verifyObjectArrayBoundaries() {
            // One compiled call site must retain the actual covariant klass,
            // including nested primitive arrays, not manufacture Object[].
            for (Object[] source : new Object[][] {
                    new Object[] { null, new Object() }, new String[] { "x", null },
                    new int[][] { new int[] { 1, 2 }, null } }) {
                Object[] copy = TestMethods.cloneObjectArray(source);
                Asserts.assertEquals(copy.getClass(), source.getClass());
                Asserts.assertTrue(copy != source && Arrays.equals(copy, source));
                Asserts.assertTrue(copy[0] == source[0]);
                copy[0] = null;
                Asserts.assertTrue(source[0] != null || source.getClass() == Object[].class);
            }
            for (int length : LENGTHS) {
                Object first = new Marker(length);
                Object second = new Marker(~length);
                Object[] source = new Object[length];
                for (int i = 0; i < length; i++) {
                    source[i] = switch (i % 3) {
                        case 0 -> first;
                        case 1 -> second;
                        default -> null;
                    };
                }
                Object[] clone = TestMethods.cloneObjectArray(source);
                Asserts.assertNotEquals(clone, source,
                        "Object[] identity, length=" + length);
                Asserts.assertTrue(Arrays.equals(clone, source),
                        "Object[] contents, length=" + length);
            }
        }

        private static void verifyReferenceBarrierUnderGc() {
            final int length = 200_003;
            Marker marker = new Marker(0x5a17c0de);
            Object[] source = new Object[length];
            Arrays.fill(source, marker);
            Object[] clone = TestMethods.cloneObjectArray(source);

            source = null;
            marker = null;
            for (int i = 0; i < 96; i++) {
                byte[] garbage = new byte[1024 * 1024];
                garbage[0] = (byte) i;
            }

            int[] probes = {0, 1, length / 2, length - 2, length - 1};
            for (int index : probes) {
                Object value = clone[index];
                Asserts.assertTrue(value instanceof Marker,
                        "reference lost after GC at index " + index);
                Asserts.assertEQ(((Marker) value).value, 0x5a17c0de,
                        "reference contents after GC at index " + index);
            }
        }
    }
}
