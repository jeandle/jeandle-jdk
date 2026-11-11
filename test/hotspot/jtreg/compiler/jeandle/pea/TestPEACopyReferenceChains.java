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
 * @summary Chained reference copy folds preserve component and clone-field types
 * @library /test/lib /
 * @modules java.base/jdk.internal.misc
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler compiler.jeandle.pea.TestPEACopyReferenceChains
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;
import jdk.test.lib.Asserts;

public class TestPEACopyReferenceChains {
    private static final String WRAPPER = TestWrapper.class.getName();

    public static void main(String[] args) throws Exception {
        Method copy = TestWrapper.class.getMethod("copyChain");
        Method clone = TestWrapper.class.getMethod("cloneChain", TestWrapper.class);
        Method unknown = TestWrapper.class.getMethod("unknownChain", Object[].class);
        Method[] targets = {copy, clone};
        // Keep the narrowing copy's checked path instead of a speculative
        // subtype trap on the declared Object[] source.
        for (String compressed : new String[] {"-XX:+UseCompressedOops", "-XX:-UseCompressedOops"}) {
            PEATestUtils.behaviorRun(WRAPPER, targets)
                    .extraFlags(compressed, "-XX:PerMethodTrapLimit=0")
                    .runPEAOnOffEquivalent();
            try (PEATestUtils.RunResult run = PEATestUtils.shapeRun(WRAPPER, targets)
                    .extraFlags(compressed, "-XX:PerMethodTrapLimit=0").run()) {
                for (Method target : new Method[] {copy, clone}) {
                    Asserts.assertEquals(run.report(target).round(0).copyFolds(), 2,
                            target + ": concrete load and narrowing copy fold together");
                    Asserts.assertEquals(run.report(target).round(0).neverEscapes(), 3,
                            target + ": all local allocations stay virtual");
                    run.frontendIR(target).assertPresent("@jeandle.copy");
                    run.finalIR(target).assertAbsent("@jeandle.copy");
                }
            }
            PEATestUtils.behaviorRun(WRAPPER, unknown)
                    .extraFlags(compressed, "-XX:PerMethodTrapLimit=0")
                    .runPEAOnOffEquivalent();
            try (PEATestUtils.RunResult run = PEATestUtils.shapeRun(WRAPPER, unknown)
                    .extraFlags(compressed, "-XX:PerMethodTrapLimit=0").run()) {
                Asserts.assertEquals(run.report(unknown).round(0).copyFolds(), 0,
                        "unknown source bounds and component keep both copies");
                Asserts.assertEquals(run.report(unknown).round(0).partiallyEscapes(), 3,
                        "all arrays materialize for checked copies");
                run.frontendIR(unknown).assertPresent("@jeandle.copy");
            }
        }
    }

    public static final class TestWrapper implements Cloneable {
        // Constant length proves the first copy's bounds even with traps
        // disabled. Mutable entries keep the reference load observable.
        private static final String[] SOURCE = {"value"};
        public String value;

        public static void main(String[] args) throws Exception {
            String[] strings = SOURCE;
            System.arraycopy(strings, 0, new String[1], 0, 1);
            TestWrapper holder = new TestWrapper();
            holder.value = strings[0];
            holder.clone();
            PEATestUtils.compileConfiguredTargetsAtLevel4();
            for (String value : new String[] {"value", null, "second"}) {
                strings[0] = value;
                holder.value = value;
                Asserts.assertTrue(copyChain() == value, "copy identity");
                Asserts.assertTrue(cloneChain(holder) == value, "clone field identity");
                Asserts.assertTrue(unknownChain(strings) == value, "compatible unknown component");
            }
            try {
                unknownChain(new Object[] {Integer.valueOf(7)});
                throw new AssertionError("missing checked narrowing copy");
            } catch (ArrayStoreException expected) {
            }
            try {
                unknownChain(new Object[0]);
                throw new AssertionError("missing source bounds check");
            } catch (IndexOutOfBoundsException expected) {
            }
            try {
                unknownChain(null);
                throw new AssertionError("missing source null check");
            } catch (NullPointerException expected) {
            }
            System.out.println("PEA-RESULT:reference-chains-passed");
        }

        public static String copyChain() {
            String[] source = SOURCE;
            String[] temporary = new String[1];
            System.arraycopy(source, 0, temporary, 0, 1);
            Object[] box = {temporary[0]};
            String[] destination = new String[1];
            System.arraycopy(box, 0, destination, 0, 1);
            return destination[0];
        }

        public static String cloneChain(TestWrapper source) throws CloneNotSupportedException {
            TestWrapper temporary = (TestWrapper) source.clone();
            Object[] box = {temporary.value};
            String[] destination = new String[1];
            System.arraycopy(box, 0, destination, 0, 1);
            return destination[0];
        }

        public static String unknownChain(Object[] source) {
            Object[] temporary = new Object[1];
            System.arraycopy(source, 0, temporary, 0, 1);
            Object[] box = {temporary[0]};
            String[] destination = new String[1];
            System.arraycopy(box, 0, destination, 0, 1);
            return destination[0];
        }
    }
}
