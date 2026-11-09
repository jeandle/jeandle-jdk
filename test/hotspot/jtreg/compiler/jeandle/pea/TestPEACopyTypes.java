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
 * @summary PEA copy folding preserves boolean storage, guarded primitive
 *          array types and reference narrowing with partial-copy exceptions
 * @library /test/lib /
 * @modules java.base/jdk.internal.misc
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler compiler.jeandle.pea.TestPEACopyTypes
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;

import jdk.test.lib.Asserts;

public class TestPEACopyTypes {
    private static final String WRAPPER = TestWrapper.class.getName();

    public static void main(String[] args) throws Exception {
        Method boolVirtual = TestWrapper.class.getMethod("booleanVirtual", boolean.class);
        Method boolConcrete = TestWrapper.class.getMethod("booleanConcrete", boolean[].class);
        Method boolClone = TestWrapper.class.getMethod("booleanClone", boolean.class);
        Method intImplicit = TestWrapper.class.getMethod("intImplicit", Object.class);
        Method intExplicit = TestWrapper.class.getMethod("intExplicit", Object.class);
        Method narrowKnown = TestWrapper.class.getMethod("narrowKnown", String.class);
        Method narrowUnknown = TestWrapper.class.getMethod("narrowUnknown", Object.class);
        Method incompatible = TestWrapper.class.getMethod("incompatible");
        Method[] primitives = {boolVirtual, boolConcrete, boolClone,
                               intImplicit, intExplicit};
        Method[] references = {narrowKnown, narrowUnknown, incompatible};

        PEATestUtils.behaviorRun(WRAPPER, primitives).runPEAOnOffEquivalent();
        // Primitive type-check failures execute only in the behavior children.
        try (PEATestUtils.RunResult run = PEATestUtils.shapeRun(WRAPPER, primitives)
                .extraFlags("-Dpea.copy.types.shape=true").run()) {
            assertFold(run, boolVirtual, 2);
            assertFold(run, boolConcrete, 1);
            assertFold(run, boolClone, 2);
            assertFold(run, intImplicit, 1);
            assertFold(run, intExplicit, 1);
        }

        // Keep runtime type-check fallbacks in the compiled method, so a known
        // Object[] source copying to String[] reaches PEA rather than ending
        // at a speculative uncommon trap before the copy. Primitive tests use
        // the default policy to retain the guards on their unknown source length.
        PEATestUtils.behaviorRun(WRAPPER, references)
                .extraFlags("-XX:PerMethodTrapLimit=0").runPEAOnOffEquivalent();
        try (PEATestUtils.RunResult run = PEATestUtils.shapeRun(WRAPPER, references)
                .extraFlags("-XX:PerMethodTrapLimit=0", "-Dpea.copy.types.shape=true")
                .run()) {
            assertFold(run, narrowKnown, 2);
            for (Method target : new Method[] {narrowUnknown, incompatible}) {
                Asserts.assertTrue(run.report(target).round(0).hasStats(),
                        target + ": PEA processed the copy");
                Asserts.assertEquals(run.report(target).round(0).copyFolds(), 0,
                        target + ": runtime store checks must remain");
                Asserts.assertEquals(run.report(target).round(0).partiallyEscapes(), 2,
                        target + ": both arrays materialize for the checked copy");
                run.frontendIR(target).assertPresent("@jeandle.copy");
            }
        }
    }

    private static void assertFold(PEATestUtils.RunResult run, Method target,
                                   int neverEscapes) throws Exception {
        Asserts.assertEquals(run.report(target).round(0).copyFolds(), 1,
                target + ": one copy folded");
        Asserts.assertEquals(run.report(target).round(0).neverEscapes(), neverEscapes,
                target + ": local allocations remain virtual");
        run.frontendIR(target).assertPresent("@jeandle.copy");
        run.finalIR(target).assertAbsent("@jeandle.copy");
    }

    public static class TestWrapper {
        public static void main(String[] args) throws Exception {
            // Resolve the clone and copy intrinsic holders before compilation.
            boolean[] warm = {true, false, true};
            System.arraycopy(warm, 0, new boolean[3], 0, 3);
            warm.clone();
            Integer.valueOf(9);
            PEATestUtils.compileConfiguredTargetsAtLevel4();

            check(booleanVirtual(true), 5, "virtual booleans");
            check(booleanVirtual(false), 2, "virtual false booleans");
            check(booleanConcrete(new boolean[] {true, false, true}), 5,
                    "concrete booleans");
            check(booleanConcrete(new boolean[] {false, true, false}), 2,
                    "concrete false booleans");
            check(booleanClone(true), 5, "clone booleans");
            check(booleanClone(false), 2, "clone false booleans");
            check(intImplicit(new int[] {17, 29}), 556, "implicit primitive guard");
            check(intExplicit(new int[] {17, 29}), 556, "explicit primitive guard");
            check(narrowKnown("head"), 1, "known String entry and null entry");
            check(narrowKnown(null), 1, "known null entries");
            check(narrowUnknown("middle"), 1, "runtime compatible entry");
            check(narrowUnknown(null), 1, "runtime null entry");
            check(incompatible(), 1, "incompatible partial copy");

            if (!Boolean.getBoolean("pea.copy.types.shape")) {
                for (Object source : new Object[] {new long[] {17, 29},
                                                   new Object[] {17, 29}}) {
                    try {
                        intImplicit(source);
                        throw new AssertionError("implicit array type check missing");
                    } catch (ArrayStoreException expected) {
                    }
                    check(intExplicit(source), -1, "explicit primitive guard rejection");
                }
                check(narrowUnknown(Integer.valueOf(9)), 2,
                        "runtime incompatible entry preserves copied prefix");
            }
            System.out.println("PEA-RESULT:copy-types-passed");
        }

        public static int booleanVirtual(boolean value) {
            boolean[] source = {value, !value, value};
            boolean[] destination = new boolean[3];
            System.arraycopy(source, 0, destination, 0, 3);
            return (destination[0] ? 1 : 0) + (destination[1] ? 2 : 0)
                    + (destination[2] ? 4 : 0);
        }

        public static int booleanConcrete(boolean[] source) {
            boolean[] destination = new boolean[3];
            System.arraycopy(source, 0, destination, 0, 3);
            return (destination[0] ? 1 : 0) + (destination[1] ? 2 : 0)
                    + (destination[2] ? 4 : 0);
        }

        public static int booleanClone(boolean value) {
            boolean[] source = {value, !value, value};
            boolean[] destination = source.clone();
            return (destination[0] ? 1 : 0) + (destination[1] ? 2 : 0)
                    + (destination[2] ? 4 : 0);
        }

        public static int intImplicit(Object source) {
            int[] destination = new int[2];
            System.arraycopy(source, 0, destination, 0, 2);
            return destination[0] * 31 + destination[1];
        }

        public static int intExplicit(Object source) {
            if (!(source instanceof int[])) {
                return -1;
            }
            int[] destination = new int[2];
            System.arraycopy(source, 0, destination, 0, 2);
            return destination[0] * 31 + destination[1];
        }

        public static int narrowKnown(String value) {
            Object[] source = {value, null, "tail"};
            String[] destination = new String[3];
            System.arraycopy(source, 0, destination, 0, 3);
            if (destination[0] != value || destination[1] != null
                    || destination[2] != "tail") {
                throw new AssertionError("typed entries changed");
            }
            return 1;
        }

        public static int narrowUnknown(Object value) {
            Object[] source = {"first", value, "last"};
            String[] destination = {"old0", "old1", "old2"};
            try {
                System.arraycopy(source, 0, destination, 0, 3);
                if (destination[0] != "first" || destination[1] != value
                        || destination[2] != "last") {
                    throw new AssertionError("compatible copy changed entries");
                }
                return 1;
            } catch (ArrayStoreException expected) {
                if (destination[0] != "first" || destination[1] != "old1"
                        || destination[2] != "old2") {
                    throw new AssertionError("partial copy changed wrong entries");
                }
                return 2;
            }
        }

        public static int incompatible() {
            Object[] source = {"first", Integer.valueOf(9), "last"};
            String[] destination = {"old0", "old1", "old2"};
            try {
                System.arraycopy(source, 0, destination, 0, 3);
                throw new AssertionError("incompatible copy did not throw");
            } catch (ArrayStoreException expected) {
                if (destination[0] != "first" || destination[1] != "old1"
                        || destination[2] != "old2") {
                    throw new AssertionError("incompatible copy lost partial prefix");
                }
                return 1;
            }
        }

        private static void check(int actual, int expected, String message) {
            if (actual != expected) {
                throw new AssertionError(message + ": " + actual + " != " + expected);
            }
        }
    }
}
