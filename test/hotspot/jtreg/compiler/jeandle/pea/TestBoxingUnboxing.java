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
 * @summary Test Jeandle boxing/unboxing intrinsics and PEA elimination for
 *          all primitive wrapper kinds, wrapper identity semantics, generic
 *          callbacks, container fields, and loop-carried wrappers
 * @library /test/lib /
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.pea.TestBoxingUnboxing
 * @run main/othervm -XX:-UseJeandleCompiler -XX:-UseCompressedOops
 *      -XX:-UseCompressedClassPointers compiler.jeandle.pea.TestBoxingUnboxing
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.stream.Stream;

import jdk.test.lib.Asserts;
import jdk.test.whitebox.WhiteBox;

public class TestBoxingUnboxing {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestBoxingUnboxing$TestWrapper";
    private static final String STRICT_IDENTITY_WRAPPER =
            "compiler.jeandle.pea.TestBoxingUnboxing$StrictIdentityWrapper";

    public static void main(String[] args) throws Exception {
        Method[] targets = {
                TestWrapper.class.getMethod("booleanRoundTrip", boolean.class),
                TestWrapper.class.getMethod("byteRoundTrip", byte.class),
                TestWrapper.class.getMethod("charRoundTrip", char.class),
                TestWrapper.class.getMethod("shortRoundTrip", short.class),
                TestWrapper.class.getMethod("intRoundTrip", int.class),
                TestWrapper.class.getMethod("longRoundTrip", long.class),
                TestWrapper.class.getMethod("floatRoundTrip", float.class),
                TestWrapper.class.getMethod("doubleRoundTrip", double.class),
                TestWrapper.class.getMethod("legacyIntegerRoundTrip", int.class),
                TestWrapper.class.getMethod("cachedIntegerIdentity", int.class),
                TestWrapper.class.getMethod("newIntegerIdentity", int.class),
                TestWrapper.class.getMethod("semanticBoxIdentityHash", int.class),
                TestWrapper.class.getMethod("synchronizedSemanticBox", int.class),
                TestWrapper.class.getMethod("synchronizedConstantInteger"),
                TestWrapper.class.getMethod("synchronizedFloatBox", float.class),
                TestWrapper.class.getMethod("lambdaMapReduce",
                        int.class, int.class, int.class),
                TestWrapper.class.getMethod("streamMapReduce",
                        int.class, int.class, int.class),
                TestWrapper.class.getMethod("containerFieldRoundTrip", int.class),
                TestWrapper.class.getMethod("loopCarriedSum", int[].class)
        };
        Method identityHash = System.class.getMethod("identityHashCode", Object.class);
        Method[] strictIdentityTargets = {
                StrictIdentityWrapper.class.getMethod("strictIntegerIdentity",
                        int.class, int.class),
                StrictIdentityWrapper.class.getMethod("strictLongIdentity",
                        long.class, long.class),
                StrictIdentityWrapper.class.getMethod("strictBooleanIdentity",
                        boolean.class, boolean.class)
        };

        PEATestUtils.behaviorRun(WRAPPER, targets)
                .dontinline(identityHash)
                .runPEAOnOffEquivalent();

        try (PEATestUtils.RunResult run = PEATestUtils.shapeRun(WRAPPER, targets)
                .dontinline(identityHash)
                .lockingMode(2)
                .run()) {
            assertPrimitiveRoundTripsEliminated(run, targets);
            assertLegacyRoundTripConservative(run,
                    targets[8]);
            assertIdentityPathsPreserved(run, targets[9], targets[10]);
            assertIdentityObserversMaterialize(run, targets[11], targets[12],
                    targets[13], targets[14]);
            assertComposedRoundTripEliminated(run, targets[15]);
            Asserts.assertEquals(run.report(targets[17]).round0Before().peaAllocCount(), 1,
                    "expected the Pair allocation before PEA");
            assertComposedRoundTripEliminated(run, targets[17]);
            assertLoopCarriedBoxShape(run, targets[18]);
        }

        try (PEATestUtils.RunResult run = PEATestUtils
                .shapeRun(WRAPPER, targets)
                .dontinline(identityHash)
                .lockingMode(2)
                .extraFlags("-XX:-JeandleOptBoxing")
                .run()) {
            assertBoxingJavaOpsDisabled(run, targets);
        }

        try (PEATestUtils.RunResult run = PEATestUtils
                .shapeRun(STRICT_IDENTITY_WRAPPER, strictIdentityTargets)
                .run()) {
            assertStrictBoxEqualityPreserved(run, strictIdentityTargets);
        }

        // The legacy new Integer path is also optimizable when its constructor
        // is available to PEA. Keep this separate from the default run above:
        // the latter intentionally verifies the conservative non-inline path.
        Method legacy = targets[8];
        try (PEATestUtils.RunResult run = PEATestUtils
                .shapeRun(WRAPPER, legacy)
                .inline(Integer.class.getConstructor(int.class))
                .inline(Integer.class.getMethod("intValue"))
                .run()) {
            assertLegacyRoundTripEliminated(run, legacy);
        }

        // Integer.valueOf(1000) is fresh with the default cache but shared
        // when AutoBoxCacheMax extends the cache through 2000. The monitor
        // decision must follow the live VM cache rather than a hard-coded
        // range.
        try (PEATestUtils.RunResult run = PEATestUtils
                .shapeRun(WRAPPER, targets[13])
                .lockingMode(2)
                .extraFlags("-XX:AutoBoxCacheMax=2000")
                .run()) {
            assertBoxMonitorPreserved(run.report(targets[13]), targets[13]);
        }
    }

    private static void assertPrimitiveRoundTripsEliminated(
            PEATestUtils.RunResult run, Method[] targets) throws Exception {
        for (int i = 0; i < 8; i++) {
            PEATestUtils.PEAReport report = run.report(targets[i]);
            PEATestUtils.IRBody before = report.round0Before();
            PEATestUtils.IRBody after = report.finalAfter();
            // Semantic boxes are deliberately not counted by peaAllocCount(),
            // which tracks only explicit new_instance/new_array calls. Their
            // presence is checked directly before PEA and their elimination
            // is checked below.
            before.assertPresent("@jeandle.box");
            before.assertPresent("@jeandle.unbox");
            Asserts.assertEquals(after.peaAllocCount(), 0,
                    targets[i] + ": non-escaping box allocation remains after PEA");
            after.assertAbsent("@jeandle.box");
            after.assertAbsent("@jeandle.unbox");
            report.assertFinalTransformIdle();
        }
    }

    private static void assertLegacyRoundTripConservative(
            PEATestUtils.RunResult run, Method target) throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();
        Asserts.assertTrue(before.peaAllocCount() >= 1,
                target + ": expected new Integer allocation before PEA");
        Asserts.assertTrue(after.peaAllocCount() >= 1,
                target + ": non-inline constructor path must remain materialized");
        after.assertPresent("@jeandle.unbox");
        after.assertAbsent("@jeandle.box");
        report.assertFinalTransformIdle();
    }

    private static void assertComposedRoundTripEliminated(
            PEATestUtils.RunResult run, Method target) throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        report.round0Before().assertPresent("@jeandle.box");
        report.round0Before().assertPresent("@jeandle.unbox");
        PEATestUtils.IRBody after = report.finalAfter();
        after.assertAbsent("@jeandle.box");
        after.assertAbsent("@jeandle.unbox");
        Asserts.assertEquals(after.peaAllocCount(), 0,
                target + ": intermediate objects must be scalar replaced");
        PEATestUtils.assertStructuralSoundness(after, target.toString());
    }

    private static void assertLoopCarriedBoxShape(
            PEATestUtils.RunResult run, Method target) throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        // The array length is a runtime input, so this exercises a real loop
        // PHI rather than a fully unrolled sequence of round trips. PEA
        // currently materializes the carried wrapper at the backedge; this
        // case checks behavior and PHI soundness without claiming that the
        // accumulator has already been converted to a primitive PHI.
        report.round0Before().assertPresent(" = phi ptr addrspace(1) ");
        report.round0Before().assertLineCount("@jeandle.box", 2);
        report.round0Before().assertPresent("@jeandle.unbox");
        report.finalAfter().assertPresent(" = phi i32 ");
        for (PEATestUtils.PEARound round : report.rounds()) {
            PEATestUtils.assertStructuralSoundness(round.after(), target.toString());
        }
        PEATestUtils.assertStructuralSoundness(run.finalIR(target), target.toString());
    }

    private static void assertBoxingJavaOpsDisabled(
            PEATestUtils.RunResult run, Method[] targets) throws Exception {
        for (int i = 0; i < 8; i++) {
            PEATestUtils.PEAReport report = run.report(targets[i]);
            // JeandleOptBoxing is a frontend isolation boundary. The regular
            // invoke/inlining pipeline may still optimize the Java methods,
            // but PEA must never receive the semantic boxing operations.
            report.round0Before().assertAbsent("@jeandle.box");
            report.round0Before().assertAbsent("@jeandle.unbox");
            report.finalAfter().assertAbsent("@jeandle.box");
            report.finalAfter().assertAbsent("@jeandle.unbox");
            report.assertFinalTransformIdle();
        }
    }

    private static void assertLegacyRoundTripEliminated(
            PEATestUtils.RunResult run, Method target) throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();
        Asserts.assertTrue(before.peaAllocCount() >= 1,
                target + ": expected new Integer allocation before PEA");
        Asserts.assertEquals(after.peaAllocCount(), 0,
                target + ": legacy wrapper allocation remains after PEA");
        after.assertAbsent("java_lang_Integer_intValue");
        report.assertFinalTransformIdle();
    }

    private static void assertIdentityPathsPreserved(
            PEATestUtils.RunResult run, Method cached, Method fresh) throws Exception {
        PEATestUtils.PEAReport cachedReport = run.report(cached);
        PEATestUtils.PEAReport freshReport = run.report(fresh);
        cachedReport.round0Before().assertLineCount("@jeandle.box", 2);
        Asserts.assertTrue(freshReport.round0Before().peaAllocCount() >= 2,
                fresh + ": expected two new Integer allocations before PEA");
        // Equality between distinct valueOf sites depends on the live wrapper
        // cache, so both semantic boxes must retain their real identity.
        cachedReport.finalAfter().assertLineCount("@jeandle.box", 2);
        freshReport.assertFinalTransformIdle();
    }

    private static void assertStrictBoxEqualityPreserved(
            PEATestUtils.RunResult run, Method[] targets) throws Exception {
        for (Method target : targets) {
            PEATestUtils.PEAReport report = run.report(target);
            report.round0Before().assertLineCount("@jeandle.box", 2);
            report.round0Before().assertPresent("icmp eq ptr addrspace(1)");
            report.finalAfter().assertLineCount("@jeandle.box", 2);
            report.finalAfter().assertPresent("icmp eq ptr addrspace(1)");
        }
    }

    private static void assertIdentityObserversMaterialize(
            PEATestUtils.RunResult run, Method identityHash, Method monitor,
            Method freshInteger, Method freshFloat) throws Exception {
        PEATestUtils.PEAReport identityReport = run.report(identityHash);
        identityReport.round0Before().assertLineCount("@jeandle.box", 1);
        Asserts.assertEquals(identityReport.round(0).effectCount("Materialize"), 1L,
                identityHash + ": identityHashCode must materialize the semantic box once");
        identityReport.finalAfter().assertLineCount("@jeandle.box", 1);
        identityReport.finalAfter().assertOccurrenceCount("@jeandle.hashcode_fast(", 2);

        assertBoxMonitorPreserved(run.report(monitor), monitor);
        assertBoxMonitorEliminated(run.report(freshInteger), freshInteger);
        assertBoxMonitorEliminated(run.report(freshFloat), freshFloat);
    }

    private static void assertBoxMonitorPreserved(
            PEATestUtils.PEAReport report, Method target) throws Exception {
        PEATestUtils.PEARound first = report.round(0);
        String monitorEnter = "jeandle.monitorenter_with_lightweight_lock";
        String monitorExit = "jeandle.monitorexit_with_lightweight_lock";
        report.round0Before().assertLineCount("@jeandle.box", 1);
        Asserts.assertEquals(first.effectCount("Materialize"), 1L,
                target + ": locking a cached or possibly cached box must materialize it once");
        Asserts.assertEquals(first.effectCount("ReplaceCall", monitorEnter), 0L,
                target + ": monitor enter on a cached or possibly cached box must not be elided");
        Asserts.assertEquals(first.effectCount("ReplaceCall", monitorExit), 0L,
                target + ": monitor exit on a cached or possibly cached box must not be elided");
        report.finalAfter().assertLineCount("@jeandle.box", 1);
        report.finalAfter().assertPresent(monitorEnter);
        report.finalAfter().assertPresent(monitorExit);
    }

    private static void assertBoxMonitorEliminated(
            PEATestUtils.PEAReport report, Method target) throws Exception {
        String monitorEnter = "jeandle.monitorenter_with_lightweight_lock";
        String monitorExit = "jeandle.monitorexit_with_lightweight_lock";
        report.round0Before().assertLineCount("@jeandle.box", 1);
        report.finalAfter().assertAbsent("@jeandle.box");
        report.finalAfter().assertAbsent(monitorEnter);
        report.finalAfter().assertAbsent(monitorExit);
        report.assertFinalTransformIdle();
    }

    public static class StrictIdentityWrapper {
        public static void main(String[] args) throws Exception {
            Integer.valueOf(0);
            Long.valueOf(0L);
            Boolean.valueOf(false);

            PEATestUtils.compileConfiguredTargetsAtLevel4();

            Asserts.assertTrue(strictIntegerIdentity(100, 100),
                    "cached Integer values must share identity");
            Asserts.assertFalse(strictIntegerIdentity(1000, 1000),
                    "uncached Integer values must have distinct identity");
            Asserts.assertFalse(strictIntegerIdentity(1000, 1001),
                    "different Integer payloads must not compare equal");
            Asserts.assertTrue(strictLongIdentity(100L, 100L),
                    "cached Long values must share identity");
            Asserts.assertFalse(strictLongIdentity(1000L, 1000L),
                    "uncached Long values must have distinct identity");
            Asserts.assertFalse(strictLongIdentity(1000L, 1001L),
                    "different Long payloads must not compare equal");
            Asserts.assertTrue(strictBooleanIdentity(true, true),
                    "equal Boolean payloads must compare equal");
            Asserts.assertFalse(strictBooleanIdentity(false, true),
                    "different Boolean payloads must not compare equal");
            System.out.println("PEA-RESULT:strict-box-equality-ok");
        }

        public static boolean strictIntegerIdentity(int left, int right) {
            return Integer.valueOf(left) == Integer.valueOf(right);
        }

        public static boolean strictLongIdentity(long left, long right) {
            return Long.valueOf(left) == Long.valueOf(right);
        }

        public static boolean strictBooleanIdentity(boolean left,
                                                     boolean right) {
            return Boolean.valueOf(left) == Boolean.valueOf(right);
        }
    }

    public static class TestWrapper {
        private static volatile Object sink;
        private static final Function<Integer, Integer> MAP = value -> value + 1;
        private static final BinaryOperator<Integer> REDUCE = (left, right) -> left + right;

        public static void main(String[] args) throws Exception {
            // Resolve wrapper classes and caches before the shape compilation.
            Integer.valueOf(0);
            Long.valueOf(0L);
            Boolean.valueOf(false);
            Character.valueOf('\0');
            Byte.valueOf((byte) 0);
            Short.valueOf((short) 0);
            Float.valueOf(0.0f);
            Double.valueOf(0.0d);

            // Link the lambda call sites and resolve the record before forcing
            // compilation, including in the runs without interpreter profiling.
            lambdaMapReduce(1, 2, 3);
            streamMapReduce(1, 2, 3);
            containerFieldRoundTrip(0);

            // Hidden lambda class names vary between child VMs. Set the
            // inline hints on the resolved methods in this VM so the IR test
            // measures boxing elimination across callbacks after inlining.
            WhiteBox whiteBox = WhiteBox.getWhiteBox();
            whiteBox.testSetForceInlineMethod(
                    MAP.getClass().getDeclaredMethod("apply", Object.class), true);
            whiteBox.testSetForceInlineMethod(REDUCE.getClass().getDeclaredMethod(
                    "apply", Object.class, Object.class), true);
            for (Method method : TestWrapper.class.getDeclaredMethods()) {
                if (method.isSynthetic()) {
                    whiteBox.testSetForceInlineMethod(method, true);
                }
            }

            PEATestUtils.compileConfiguredTargetsAtLevel4();

            for (boolean value : new boolean[] {false, true}) {
                Asserts.assertEquals(booleanRoundTrip(value), value,
                        "boolean round-trip");
            }
            for (byte value : new byte[] {-128, -1, 0, 1, 127}) {
                Asserts.assertEquals(byteRoundTrip(value), value,
                        "byte round-trip");
            }
            for (char value : new char[] {'\0', '\1', '\u007f', '\u0080', '\uffff'}) {
                Asserts.assertEquals(charRoundTrip(value), value,
                        "char round-trip");
            }
            for (short value : new short[] {-32768, -129, -1, 0, 1, 32767}) {
                Asserts.assertEquals(shortRoundTrip(value), value,
                        "short round-trip");
            }
            for (int value : new int[] {
                    Integer.MIN_VALUE, -1000, -129, -128, -1, 0, 1, 127, 128,
                    1000, Integer.MAX_VALUE}) {
                Asserts.assertEquals(intRoundTrip(value), value,
                        "int round-trip");
                Asserts.assertEquals(legacyIntegerRoundTrip(value), value,
                        "legacy Integer round-trip");
                Asserts.assertEquals(containerFieldRoundTrip(value), value + value,
                        "shared wrapper through record fields");
            }
            for (long value : new long[] {
                    Long.MIN_VALUE, -1000L, -129L, -128L, -1L, 0L, 1L, 127L,
                    128L, 1000L, Long.MAX_VALUE}) {
                Asserts.assertEquals(longRoundTrip(value), value,
                        "long round-trip");
            }
            for (float value : new float[] {
                    -Float.MAX_VALUE, -1.0f, -0.0f, 0.0f, 1.0f, Float.MAX_VALUE,
                    Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
                Asserts.assertEquals(Float.floatToRawIntBits(floatRoundTrip(value)),
                        Float.floatToRawIntBits(value), "float round-trip");
            }
            for (double value : new double[] {
                    -Double.MAX_VALUE, -1.0d, -0.0d, 0.0d, 1.0d, Double.MAX_VALUE,
                    Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
                Asserts.assertEquals(Double.doubleToRawLongBits(doubleRoundTrip(value)),
                        Double.doubleToRawLongBits(value), "double round-trip");
            }

            Asserts.assertTrue(cachedIntegerIdentity(0),
                    "cached Integer identity must be preserved");
            Asserts.assertTrue(cachedIntegerIdentity(127),
                    "cached Integer identity must be preserved at high boundary");
            boolean expected128Identity =
                    Integer.valueOf(128) == Integer.valueOf(128);
            Asserts.assertEquals(cachedIntegerIdentity(128), expected128Identity,
                    "Integer identity must follow the live cache configuration");
            Asserts.assertFalse(newIntegerIdentity(1),
                    "distinct new Integer objects must not compare equal");
            Asserts.assertTrue(semanticBoxIdentityHash(0),
                    "cached semantic box identity hash must be stable");
            Asserts.assertTrue(semanticBoxIdentityHash(1000),
                    "fresh semantic box identity hash must be stable");
            Asserts.assertEquals(synchronizedSemanticBox(0), 1,
                    "monitor on cached semantic box must be preserved");
            Asserts.assertEquals(synchronizedConstantInteger(), 1001,
                    "monitor on a constant Integer box");
            Asserts.assertEquals(Float.floatToRawIntBits(synchronizedFloatBox(-0.0f)),
                    Float.floatToRawIntBits(-0.0f),
                    "monitor on a fresh Float box");

            for (int[] values : new int[][] {
                    {1, 2, 3}, {-129, 127, 128}, {1000, -2000, 3000},
                    {Integer.MAX_VALUE, 0, 1}, {Integer.MIN_VALUE, -1, 0}}) {
                int expected = values[0] + values[1] + values[2] + 3;
                Asserts.assertEquals(lambdaMapReduce(values[0], values[1], values[2]),
                        expected, "generic map/reduce callbacks");
                Asserts.assertEquals(streamMapReduce(values[0], values[1], values[2]),
                        expected, "Stream map/reduce callbacks");
            }
            int[] manyValues = new int[257];
            for (int i = 0; i < manyValues.length; i++) {
                manyValues[i] = (i & 1) == 0 ? 1000 + i : -1000 - i;
            }
            for (int[] values : new int[][] {
                    {}, {0}, {1000}, {127, 1, -257, 1, 1000, -1000},
                    {Integer.MAX_VALUE, 1, Integer.MIN_VALUE, -1},
                    manyValues}) {
                int expected = 0;
                for (int value : values) {
                    expected += value;
                }
                Asserts.assertEquals(loopCarriedSum(values), expected,
                        "dynamic loop-carried wrapper");
            }

            if (sink != null) {
                throw new AssertionError("unexpected sink value");
            }
            System.out.println("PEA-RESULT:boxing-unboxing-ok");
        }

        public static boolean booleanRoundTrip(boolean value) {
            return Boolean.valueOf(value).booleanValue();
        }

        public static byte byteRoundTrip(byte value) {
            return Byte.valueOf(value).byteValue();
        }

        public static char charRoundTrip(char value) {
            return Character.valueOf(value).charValue();
        }

        public static short shortRoundTrip(short value) {
            return Short.valueOf(value).shortValue();
        }

        public static int intRoundTrip(int value) {
            return Integer.valueOf(value).intValue();
        }

        public static long longRoundTrip(long value) {
            return Long.valueOf(value).longValue();
        }

        public static float floatRoundTrip(float value) {
            return Float.valueOf(value).floatValue();
        }

        public static double doubleRoundTrip(double value) {
            return Double.valueOf(value).doubleValue();
        }

        public static int lambdaMapReduce(int first, int second, int third) {
            return REDUCE.apply(REDUCE.apply(MAP.apply(first), MAP.apply(second)),
                    MAP.apply(third));
        }

        // Keep the full Stream pipeline as compiled behavior coverage. Its
        // library inlining decisions are independent of boxing elimination;
        // lambdaMapReduce checks the callback chain's PEA shape directly.
        public static int streamMapReduce(int first, int second, int third) {
            return Stream.of(first, second, third)
                    .map(value -> value + 1)
                    .reduce(0, (left, right) -> left + right);
        }

        public record Pair(Integer first, Integer second) {}

        public static int containerFieldRoundTrip(int value) {
            Integer box = Integer.valueOf(value);
            Pair pair = new Pair(box, box);
            return pair.first().intValue() + pair.second().intValue();
        }

        public static int loopCarriedSum(int[] values) {
            Integer sum = Integer.valueOf(0);
            for (int value : values) {
                sum = Integer.valueOf(sum.intValue() + value);
            }
            return sum.intValue();
        }

        @SuppressWarnings("deprecation")
        public static int legacyIntegerRoundTrip(int value) {
            return new Integer(value).intValue();
        }

        public static boolean cachedIntegerIdentity(int value) {
            return Integer.valueOf(value) == Integer.valueOf(value);
        }

        @SuppressWarnings("deprecation")
        public static boolean newIntegerIdentity(int value) {
            return new Integer(value) == new Integer(value);
        }

        public static boolean semanticBoxIdentityHash(int value) {
            Integer box = Integer.valueOf(value);
            int first = System.identityHashCode(box);
            int second = System.identityHashCode(box);
            return first == second && box.intValue() == value;
        }

        @SuppressWarnings("synchronization")
        public static int synchronizedSemanticBox(int value) {
            Integer box = Integer.valueOf(value);
            synchronized (box) {
                return box.intValue() + 1;
            }
        }

        @SuppressWarnings("synchronization")
        public static int synchronizedConstantInteger() {
            Integer box = Integer.valueOf(1000);
            synchronized (box) {
                return box.intValue() + 1;
            }
        }

        @SuppressWarnings("synchronization")
        public static float synchronizedFloatBox(float value) {
            Float box = Float.valueOf(value);
            synchronized (box) {
                return box.floatValue();
            }
        }
    }
}
