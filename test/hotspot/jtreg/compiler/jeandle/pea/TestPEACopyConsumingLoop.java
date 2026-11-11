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
 * @summary Copy consumers with throwing safepoint backedges unroll before PEA
 * @library /test/lib /
 * @modules java.base/jdk.internal.misc
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler compiler.jeandle.pea.TestPEACopyConsumingLoop
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;
import jdk.test.lib.Asserts;

public class TestPEACopyConsumingLoop {
    public static void main(String[] args) throws Exception {
        String wrapper = TestWrapper.class.getName();
        Method copy16 = TestWrapper.class.getMethod("copy16", int[].class);
        Method copy64 = TestWrapper.class.getMethod("copy64", int[].class);
        Method caughtCopy64 = TestWrapper.class.getMethod("caughtCopy64", int[].class);
        Method sum = TestWrapper.class.getMethod("sum", int[].class, int.class);
        PEATestUtils.behaviorRun(wrapper, copy16, copy64, caughtCopy64)
                .inline(sum).inline(copy64)
                .runPEAOnOffEquivalent();
        try (PEATestUtils.RunResult run = PEATestUtils.shapeRun(
                wrapper, copy16, copy64, caughtCopy64).inline(sum).inline(copy64)
                .extraLLVMOptions("--print-before=safepoint-poll-normalization")
                .extraFlags("-Dpea.copy.consuming.shape=true").run()) {
            run.output().stderrShouldContain("SafepointPollNormalization");
            run.output().stderrShouldContain("@jeandle.safepoint_poll");
            for (Method target : new Method[] {copy16, copy64, caughtCopy64}) {
                if (!target.equals(caughtCopy64)) {
                    run.frontendIR(target).assertPresent("@jeandle.copy");
                }
                Asserts.assertEquals(run.report(target).round(0).copyFolds(), 1,
                        target + ": copy reached PEA and folded");
                Asserts.assertEquals(run.report(target).round(0).neverEscapes(), 1,
                        target + ": consuming loop keeps destination virtual");
                run.finalIR(target).assertAbsent("@jeandle.copy");
                run.finalIR(target).assertAbsent("@new_array");
                int polls = run.finalIR(target).occurrenceCount("@safepoint_handler");
                // The caught method also has a separate exceptional return poll.
                int maxPolls = target.equals(caughtCopy64) ? 3 : 2;
                Asserts.assertTrue(polls <= maxPolls,
                        target + ": fully unrolled consumer retained " + polls + " polls");
                long invokePolls = run.finalIR(target).lines().stream()
                        .filter(line -> line.contains("invoke ")
                                && line.contains("@safepoint_handler")).count();
                Asserts.assertEquals(invokePolls, 0L,
                        target + ": fully unrolled consumer retained " + invokePolls
                                + " throwing polls");
            }
        }
    }

    public static class TestWrapper {
        public static void main(String[] args) throws Exception {
            int[] source = new int[66];
            for (int i = 0; i < source.length; i++) {
                source[i] = i * 7 - 31;
            }
            System.arraycopy(source, 0, new int[1], 0, 1);
            PEATestUtils.compileConfiguredTargetsAtLevel4();
            Asserts.assertEquals(copy16(source), reference(source, 16));
            Asserts.assertEquals(copy64(source), reference(source, 64));
            Asserts.assertEquals(caughtCopy64(source), reference(source, 64));
            source[2] = Integer.MIN_VALUE;
            source[65] = Integer.MAX_VALUE;
            Asserts.assertEquals(copy16(source), reference(source, 16));
            Asserts.assertEquals(copy64(source), reference(source, 64));
            Asserts.assertEquals(caughtCopy64(source), reference(source, 64));
            if (!Boolean.getBoolean("pea.copy.consuming.shape")) {
                Asserts.assertEquals(caughtCopy64(null), -37L);
                Asserts.assertEquals(caughtCopy64(new int[65]), -37L);
                try {
                    copy16(null);
                    throw new AssertionError("missing null check");
                } catch (NullPointerException expected) {
                }
                try {
                    copy64(new int[65]);
                    throw new AssertionError("missing source bounds check");
                } catch (ArrayIndexOutOfBoundsException expected) {
                }
            }
            System.out.println("PEA-RESULT:copy-consuming-loop-passed");
        }

        private static long reference(int[] source, int length) {
            long result = 0;
            for (int i = 2; i < length + 2; i++) {
                result += source[i];
            }
            return result;
        }

        public static long copy16(int[] source) {
            int[] destination = new int[16];
            System.arraycopy(source, 2, destination, 0, 16);
            return sum(destination, 16);
        }

        public static long copy64(int[] source) {
            int[] destination = new int[64];
            System.arraycopy(source, 2, destination, 0, 64);
            return sum(destination, 64);
        }

        public static long caughtCopy64(int[] source) {
            try {
                return copy64(source);
            } catch (Throwable failure) {
                return -37;
            }
        }

        public static long sum(int[] values, int length) {
            long result = 0;
            for (int i = 0; i < length; i++) {
                result += values[i];
            }
            return result;
        }
    }
}
