/*
 * Copyright (c) 2026, the Jeandle-JDK Authors. All Rights Reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 */

/*
 * @test
 * @summary PEA reconstructs all semantic primitive boxes with the correct
 *          cache identity and payload when an active Jeandle frame is
 *          deoptimized
 * @library /test/lib /
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.pea.TestBoxingDeopt
 * @run main/othervm -XX:-UseJeandleCompiler
 *      -XX:-UseCompressedOops -XX:-UseCompressedClassPointers
 *      compiler.jeandle.pea.TestBoxingDeopt
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;
import java.util.function.BooleanSupplier;

import jdk.test.lib.Asserts;

public class TestBoxingDeopt {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestBoxingDeopt$TestWrapper";
    private static final String EXTENDED_CACHE_PROPERTY =
            "compiler.jeandle.pea.extendedIntegerCache";

    public static void main(String[] args) throws Exception {
        Method booleanBoxes = TestWrapper.class.getMethod(
                "booleanBoxesAfterDeopt", boolean.class, boolean.class);
        Method byteBoxes = TestWrapper.class.getMethod(
                "byteBoxesAfterDeopt", byte.class, byte.class);
        Method characterBoxes = TestWrapper.class.getMethod(
                "characterBoxesAfterDeopt", char.class, char.class);
        Method shortBoxes = TestWrapper.class.getMethod(
                "shortBoxesAfterDeopt", short.class, short.class);
        Method integerBoxes = TestWrapper.class.getMethod(
                "integerBoxesAfterDeopt", int.class, int.class);
        Method longBoxes = TestWrapper.class.getMethod(
                "longBoxesAfterDeopt", long.class, long.class);
        Method floatBoxes = TestWrapper.class.getMethod(
                "floatBoxesAfterDeopt", float.class, float.class);
        Method doubleBoxes = TestWrapper.class.getMethod(
                "doubleBoxesAfterDeopt", double.class, double.class);
        Method requestDeopt = TestWrapper.class.getDeclaredMethod("requestDeopt");
        Method[] targets = {booleanBoxes, byteBoxes, characterBoxes, shortBoxes,
                integerBoxes, longBoxes, floatBoxes, doubleBoxes};

        PEATestUtils.behaviorRun(WRAPPER, targets)
                .dontinline(requestDeopt)
                .runPEAOnOffEquivalent();

        // Exercise IntegerBoxCache's runtime high bound instead of baking the
        // default [-128, 127] range into the deoptimization test.
        PEATestUtils.behaviorRun(WRAPPER, targets)
                .dontinline(requestDeopt)
                .extraFlags("-XX:AutoBoxCacheMax=2000",
                        "-D" + EXTENDED_CACHE_PROPERTY + "=true")
                .runPEAOnOffEquivalent();
    }

    public static class TestWrapper {
        private static final Method BOOLEAN_TARGET = target(
                "booleanBoxesAfterDeopt", boolean.class, boolean.class);
        private static final Method BYTE_TARGET = target(
                "byteBoxesAfterDeopt", byte.class, byte.class);
        private static final Method CHARACTER_TARGET = target(
                "characterBoxesAfterDeopt", char.class, char.class);
        private static final Method SHORT_TARGET = target(
                "shortBoxesAfterDeopt", short.class, short.class);
        private static final Method INTEGER_TARGET = target(
                "integerBoxesAfterDeopt", int.class, int.class);
        private static final Method LONG_TARGET = target(
                "longBoxesAfterDeopt", long.class, long.class);
        private static final Method FLOAT_TARGET = target(
                "floatBoxesAfterDeopt", float.class, float.class);
        private static final Method DOUBLE_TARGET = target(
                "doubleBoxesAfterDeopt", double.class, double.class);
        private static final boolean EXTENDED_INTEGER_CACHE =
                Boolean.getBoolean(EXTENDED_CACHE_PROPERTY);
        private static Method deoptTarget;
        private static boolean activeDeoptEnabled;
        private static volatile Object observed;
        private static volatile int longStatus;

        public static void main(String[] args) throws Exception {
            // Resolve every wrapper cache before compiling the exact targets.
            Boolean.valueOf(false);
            Byte.valueOf((byte) 0);
            Character.valueOf('\0');
            Short.valueOf((short) 0);
            Integer.valueOf(0);
            Long.valueOf(0L);
            Float.valueOf(0.0f);
            Double.valueOf(0.0d);
            PEATestUtils.compileConfiguredTargetsAtLevel4();
            activeDeoptEnabled = true;

            deoptTarget = BOOLEAN_TARGET;
            Asserts.assertTrue(booleanBoxesAfterDeopt(false, true),
                    "Boolean singletons must retain identity after deopt");

            deoptTarget = BYTE_TARGET;
            Asserts.assertTrue(byteBoxesAfterDeopt(Byte.MIN_VALUE, Byte.MAX_VALUE),
                    "the complete Byte cache must retain identity after deopt");

            deoptTarget = CHARACTER_TARGET;
            primeCacheMissFallback(CHARACTER_TARGET,
                    () -> characterBoxesAfterDeopt('\u007f', '\u0080'));
            Asserts.assertTrue(characterBoxesAfterDeopt('\u007f', '\u0080'),
                    "Character cache boundary must be restored after deopt");

            deoptTarget = SHORT_TARGET;
            primeCacheMissFallback(SHORT_TARGET,
                    () -> shortBoxesAfterDeopt((short) -128, (short) -129));
            Asserts.assertTrue(shortBoxesAfterDeopt((short) -128, (short) -129),
                    "Short cache boundary must be restored after deopt");

            deoptTarget = INTEGER_TARGET;
            primeCacheMissFallback(INTEGER_TARGET,
                    () -> integerBoxesAfterDeopt(-128, 1000));
            Asserts.assertTrue(integerBoxesAfterDeopt(-128, 1000),
                    "Integer runtime cache bound must be restored after deopt");

            deoptTarget = LONG_TARGET;
            primeCacheMissFallback(LONG_TARGET,
                    () -> longBoxesAfterDeopt(127L, 128L));
            Asserts.assertTrue(longBoxesAfterDeopt(127L, 128L),
                    "Long cache boundary must be restored after deopt, status="
                            + longStatus);

            deoptTarget = FLOAT_TARGET;
            Asserts.assertTrue(floatBoxesAfterDeopt(
                            Float.intBitsToFloat(0x7fc01234), -0.0f),
                    "Float boxes must be fresh and preserve raw payload bits");

            deoptTarget = DOUBLE_TARGET;
            Asserts.assertTrue(doubleBoxesAfterDeopt(
                            Double.longBitsToDouble(0x7ff8000000001234L), -0.0d),
                    "Double boxes must be fresh and preserve raw payload bits");

            System.out.println("PEA-RESULT:boxing-deopt-ok");
        }

        public static boolean booleanBoxesAfterDeopt(boolean first, boolean second) {
            Boolean firstBox = Boolean.valueOf(first);
            Boolean secondBox = Boolean.valueOf(second);
            requestDeopt();
            observed = firstBox;
            boolean firstOK = observed == Boolean.valueOf(first)
                    && ((Boolean) observed).booleanValue() == first;
            observed = secondBox;
            boolean secondOK = observed == Boolean.valueOf(second)
                    && ((Boolean) observed).booleanValue() == second;
            return firstOK && secondOK;
        }

        public static boolean byteBoxesAfterDeopt(byte first, byte second) {
            Byte firstBox = Byte.valueOf(first);
            Byte secondBox = Byte.valueOf(second);
            requestDeopt();
            observed = firstBox;
            boolean firstOK = observed == Byte.valueOf(first)
                    && ((Byte) observed).byteValue() == first;
            observed = secondBox;
            boolean secondOK = observed == Byte.valueOf(second)
                    && ((Byte) observed).byteValue() == second;
            return firstOK && secondOK;
        }

        public static boolean characterBoxesAfterDeopt(char cached, char uncached) {
            Character cachedBox = Character.valueOf(cached);
            Character uncachedBox = Character.valueOf(uncached);
            requestDeopt();
            observed = cachedBox;
            boolean cachedOK = observed == Character.valueOf(cached)
                    && ((Character) observed).charValue() == cached;
            observed = uncachedBox;
            boolean uncachedOK = observed != Character.valueOf(uncached)
                    && ((Character) observed).charValue() == uncached;
            return cachedOK && uncachedOK;
        }

        public static boolean shortBoxesAfterDeopt(short cached, short uncached) {
            Short cachedBox = Short.valueOf(cached);
            Short uncachedBox = Short.valueOf(uncached);
            requestDeopt();
            observed = cachedBox;
            boolean cachedOK = observed == Short.valueOf(cached)
                    && ((Short) observed).shortValue() == cached;
            observed = uncachedBox;
            boolean uncachedOK = observed != Short.valueOf(uncached)
                    && ((Short) observed).shortValue() == uncached;
            return cachedOK && uncachedOK;
        }

        public static boolean integerBoxesAfterDeopt(int cached, int extended) {
            Integer cachedBox = Integer.valueOf(cached);
            Integer extendedBox = Integer.valueOf(extended);
            requestDeopt();
            observed = cachedBox;
            boolean cachedOK = observed == Integer.valueOf(cached)
                    && ((Integer) observed).intValue() == cached;
            observed = extendedBox;
            boolean extendedIdentity = observed == Integer.valueOf(extended);
            boolean extendedOK = extendedIdentity == EXTENDED_INTEGER_CACHE
                    && ((Integer) observed).intValue() == extended;
            return cachedOK && extendedOK;
        }

        public static boolean longBoxesAfterDeopt(long cached, long uncached) {
            Long cachedBox = Long.valueOf(cached);
            Long uncachedBox = Long.valueOf(uncached);
            requestDeopt();
            observed = cachedBox;
            boolean cachedOK = observed == Long.valueOf(cached)
                    && ((Long) observed).longValue() == cached;
            observed = uncachedBox;
            boolean uncachedIdentity = observed != Long.valueOf(uncached);
            boolean uncachedPayload = ((Long) observed).longValue() == uncached;
            longStatus = (cachedOK ? 1 : 0)
                    | (uncachedIdentity ? 2 : 0)
                    | (uncachedPayload ? 4 : 0);
            return longStatus == 7;
        }

        public static boolean floatBoxesAfterDeopt(float first, float second) {
            Float firstBox = Float.valueOf(first);
            Float secondBox = Float.valueOf(second);
            requestDeopt();
            observed = firstBox;
            boolean firstOK = observed != Float.valueOf(first)
                    && Float.floatToRawIntBits(((Float) observed).floatValue())
                            == Float.floatToRawIntBits(first);
            observed = secondBox;
            boolean secondOK = observed != Float.valueOf(second)
                    && Float.floatToRawIntBits(((Float) observed).floatValue())
                            == Float.floatToRawIntBits(second);
            return firstOK && secondOK;
        }

        public static boolean doubleBoxesAfterDeopt(double first, double second) {
            Double firstBox = Double.valueOf(first);
            Double secondBox = Double.valueOf(second);
            requestDeopt();
            observed = firstBox;
            boolean firstOK = observed != Double.valueOf(first)
                    && Double.doubleToRawLongBits(((Double) observed).doubleValue())
                            == Double.doubleToRawLongBits(first);
            observed = secondBox;
            boolean secondOK = observed != Double.valueOf(second)
                    && Double.doubleToRawLongBits(((Double) observed).doubleValue())
                            == Double.doubleToRawLongBits(second);
            return firstOK && secondOK;
        }

        private static void primeCacheMissFallback(
                Method target, BooleanSupplier operation) throws Exception {
            // PEA-off leaves an escaping semantic box in the graph. Its first
            // out-of-cache value deliberately takes the speculative
            // unstable-if trap and reexecutes valueOf. Let that one-time trap
            // happen without also requesting the active-frame deoptimization
            // under test, then compile the caller with its original valueOf
            // invocation so the ordinary inline driver owns materialization.
            activeDeoptEnabled = false;
            try {
                Asserts.assertTrue(operation.getAsBoolean(),
                        "cache-miss fallback priming changed boxing semantics");
            } finally {
                activeDeoptEnabled = true;
            }
            PEATestUtils.enqueueAndAwaitLevel4(target);
        }

        private static void requestDeopt() {
            if (!activeDeoptEnabled) {
                return;
            }
            PEATestUtils.ActiveFrameDeoptEvidence evidence =
                    PEATestUtils.deoptimizeActiveFrame(deoptTarget, 2);
            if (!evidence.frameDeoptimized()
                    || evidence.compilationLevel() != 4
                    || evidence.markedNMethods() != 1) {
                throw new AssertionError("exact active-frame deopt evidence");
            }
        }

        private static Method target(String name, Class<?>... parameterTypes) {
            try {
                return TestWrapper.class.getMethod(name, parameterTypes);
            } catch (ReflectiveOperationException failure) {
                throw new ExceptionInInitializerError(failure);
            }
        }
    }
}
