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
 * @summary Jeandle boxing and deoptimization honor IntegerCache.low/high when
 *          a CDS-restored backing array is larger than the runtime cache range
 * @requires vm.cds.write.archived.java.heap
 * @library /test/lib /
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox
 *        compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run driver compiler.jeandle.pea.TestIntegerCacheCDSRange
 */

package compiler.jeandle.pea;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import jdk.test.lib.Asserts;
import jdk.test.lib.cds.CDSOptions;
import jdk.test.lib.cds.CDSTestUtils;
import jdk.test.lib.process.OutputAnalyzer;

public class TestIntegerCacheCDSRange {
    private static final int ARCHIVED_HIGH = 20_000;
    private static final int RUNTIME_HIGH = 127;
    private static final int TEST_VALUE = 1_000;

    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            TestApp.run();
            return;
        }

        CDSTestUtils.createArchiveAndCheck(
                "-XX:AutoBoxCacheMax=" + ARCHIVED_HIGH);

        String testClassPath = System.getProperty("test.class.path");
        String appClass = TestIntegerCacheCDSRange.class.getName();
        String appHolder = appClass + "$TestApp";
        CDSOptions options = new CDSOptions()
                .setUseVersion(false)
                .addPrefix(
                        "-Xbootclasspath/a:.",
                        "-XX:+UnlockDiagnosticVMOptions",
                        "-XX:+UnlockExperimentalVMOptions",
                        "-XX:+WhiteBoxAPI",
                        "-XX:AutoBoxCacheMax=" + RUNTIME_HIGH,
                        "-XX:-TieredCompilation",
                        "-XX:-UseOnStackReplacement",
                        "-XX:CICompilerCount=1",
                        "-Xbatch",
                        "-XX:+UseJeandleCompiler",
                        "-XX:+JeandleDoPEA",
                        "-XX:CompileCommand=dontinline," +
                                appHolder + "::requestDeopt",
                        "--add-opens=java.base/java.lang=ALL-UNNAMED",
                        "-cp", testClassPath)
                .addSuffix(appClass, "child");

        OutputAnalyzer output = CDSTestUtils.runWithArchive(options);
        output.shouldHaveExitValue(0).shouldContain("TEST PASSED");
    }

    public static class TestApp {
        private static final Method DEOPT_TARGET = target(
                "deoptBoxesAreDistinct", int.class);
        private static volatile Object observedFirst;
        private static volatile Object observedSecond;

        static void run() throws Exception {
            verifyArchivedArrayIsLargerThanRuntimeRange();

            Method directTarget = target("directBoxesAreDistinct", int.class);
            Integer.valueOf(0);
            PEATestUtils.enqueueAndAwaitLevel4(directTarget, DEOPT_TARGET);
            PEATestUtils.confirmLevel4(directTarget, DEOPT_TARGET);

            Asserts.assertTrue(directBoxesAreDistinct(TEST_VALUE),
                    "compiled valueOf must not use the inactive archived tail");
            Asserts.assertTrue(deoptBoxesAreDistinct(TEST_VALUE),
                    "deopt must reconstruct two uncached boxes as distinct objects");
            Asserts.assertTrue(observedFirst != observedSecond,
                    "deopt restored both uncached boxes as one archived object");
            System.out.println("TEST PASSED");
        }

        public static boolean directBoxesAreDistinct(int value) {
            Integer first = Integer.valueOf(value);
            Integer second = Integer.valueOf(value);
            return first != second;
        }

        public static boolean deoptBoxesAreDistinct(int value) {
            Integer first = Integer.valueOf(value);
            Integer second = Integer.valueOf(value);
            requestDeopt();
            observedFirst = first;
            observedSecond = second;
            return observedFirst != observedSecond;
        }

        private static void requestDeopt() {
            PEATestUtils.ActiveFrameDeoptEvidence evidence =
                    PEATestUtils.deoptimizeActiveFrame(DEOPT_TARGET, 2);
            if (!evidence.frameDeoptimized()
                    || evidence.compilationLevel() != 4
                    || evidence.markedNMethods() != 1) {
                throw new AssertionError("exact active-frame deopt evidence");
            }
        }

        private static void verifyArchivedArrayIsLargerThanRuntimeRange()
                throws ReflectiveOperationException {
            Class<?> cacheClass = Class.forName("java.lang.Integer$IntegerCache");
            Field cacheField = cacheClass.getDeclaredField("cache");
            Field lowField = cacheClass.getDeclaredField("low");
            Field highField = cacheClass.getDeclaredField("high");
            cacheField.setAccessible(true);
            lowField.setAccessible(true);
            highField.setAccessible(true);

            Integer[] cache = (Integer[]) cacheField.get(null);
            int low = lowField.getInt(null);
            int high = highField.getInt(null);
            Asserts.assertEquals(low, -128, "unexpected IntegerCache.low");
            Asserts.assertEquals(high, RUNTIME_HIGH,
                    "runtime IntegerCache.high must remain at the default");
            Asserts.assertTrue(cache.length > TEST_VALUE - low,
                    "CDS archive did not retain the oversized backing array");
            Asserts.assertTrue(Integer.valueOf(TEST_VALUE) !=
                    Integer.valueOf(TEST_VALUE),
                    "interpreted valueOf must ignore the inactive archived tail");
        }

        private static Method target(String name, Class<?>... parameterTypes) {
            try {
                return TestApp.class.getMethod(name, parameterTypes);
            } catch (ReflectiveOperationException failure) {
                throw new ExceptionInInitializerError(failure);
            }
        }
    }
}
