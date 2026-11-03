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
 * @summary PEA keeps a temporary String virtual across clone's CPU-order fence
 * @library /test/lib /
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.pea.TestPEAStringCopyAcrossCPUOrder
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import jdk.test.lib.Asserts;

public class TestPEAStringCopyAcrossCPUOrder {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestPEAStringCopyAcrossCPUOrder$TestWrapper";
    private static final String CPU_ORDER_FENCE =
            "fence syncscope(\"singlethread\") seq_cst";

    public static void main(String[] args) throws Exception {
        Method target = TestWrapper.class.getMethod("copyWrapperProbe", String.class);
        Method getBytes = String.class.getMethod("getBytes", Charset.class);
        Method coder = String.class.getDeclaredMethod("coder");
        Method encode = String.class.getDeclaredMethod(
                "encode", Charset.class, byte.class, byte[].class);
        Method encodeUTF8 = String.class.getDeclaredMethod(
                "encodeUTF8", byte.class, byte[].class, boolean.class);

        // The exact-target harness uses compileonly for reproducibility.  List
        // the encoding entry points so this test isolates PEA on the
        // same post-inlining clone path as an ordinary hot compilation.
        try (PEATestUtils.RunResult run = PEATestUtils.shapeRun(WRAPPER, target)
                .inline(getBytes).inline(coder).inline(encode).inline(encodeUTF8).run()) {
            assertShape(run, target);
        }

        PEATestUtils.behaviorRun(WRAPPER, target)
                .inline(getBytes).inline(coder).inline(encode).inline(encodeUTF8)
                .runPEAOnOffEquivalent();
    }

    private static void assertShape(PEATestUtils.RunResult run, Method target)
            throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.PEARound first = report.round(0);
        PEATestUtils.IRBody before = first.before();
        PEATestUtils.AllocationKey wrapper = new PEATestUtils.AllocationKey(
                PEATestUtils.AllocationKind.INSTANCE, 0);
        PEATestUtils.AllocationKey cloneArray = new PEATestUtils.AllocationKey(
                PEATestUtils.AllocationKind.ARRAY, 11);

        Asserts.assertEquals(run.frontendIR(target).allocations().stream()
                        .map(PEATestUtils.AllocationSite::key).toList(),
                List.of(wrapper), target + ": frontend wrapper allocation");
        Asserts.assertEquals(before.allocations().stream()
                        .map(PEATestUtils.AllocationSite::key).toList(),
                List.of(wrapper, cloneArray),
                target + ": post-inlining allocation sites");
        Asserts.assertEquals(first.neverEscapes(), 1, target + ": NeverEscapes");
        Asserts.assertEquals(first.partiallyEscapes(), 0, target + ": PartiallyEscapes");
        Asserts.assertEquals(first.alwaysEscapes(), 0, target + ": AlwaysEscapes");
        Asserts.assertEquals(report.effects("EliminateAllocation").size(), 1,
                target + ": eliminate only the temporary String");
        Asserts.assertEquals(report.effects("Materialize").size(), 0,
                target + ": CPU-order fences must not materialize the String");

        first.after().assertRetainsExactlyOriginalAllocations(
                before, cloneArray);
        report.finalAfter().assertRetainsExactlyOriginalAllocations(
                before, cloneArray);
        before.assertLineCount(CPU_ORDER_FENCE, 1);
        before.assertBefore("@jeandle.new_instance", 0, CPU_ORDER_FENCE, 0);
        report.finalAfter().assertLineCount(CPU_ORDER_FENCE, 1);
        report.finalAfter().assertAbsent("@jeandle.new_instance");

        PEATestUtils.IRBody finalIR = run.finalIR(target);
        Asserts.assertEquals(finalIR.loweredAllocCount(), 1,
                target + ": clone result allocations must remain");
        finalIR.assertLineCount(CPU_ORDER_FENCE, 1);
    }

    public static class TestWrapper {
        private static volatile int warmupSink;

        public static void main(String[] args) throws Exception {
            // Resolve the constructor, charset and encoding paths before the
            // exact WhiteBox compilation.
            warmupSink += copyWrapperProbe("plain-ascii-0123456789").length;
            PEATestUtils.compileConfiguredTargetsAtLevel4();

            String[] inputs = {
                    "",
                    "plain-ascii-0123456789",
                    "caf\u00e9",
                    "\u4e2d\u6587-UTF16-\ud83d\ude00"
            };
            long digest = 0x243f6a8885a308d3L;
            for (String input : inputs) {
                byte[] actual = copyWrapperProbe(input);
                byte[] expected = input.getBytes(StandardCharsets.UTF_8);
                Asserts.assertTrue(Arrays.equals(actual, expected), input);
                Asserts.assertTrue(actual != expected, "UTF-8 result must be a fresh array");
                digest = mix(digest, actual.length);
                for (byte value : actual) {
                    digest = mix(digest, Byte.toUnsignedInt(value));
                }
            }
            System.out.println("PEA-RESULT:" + Long.toUnsignedString(digest, 16));
        }

        public static byte[] copyWrapperProbe(String original) {
            return new String(original).getBytes(StandardCharsets.UTF_8);
        }

        private static long mix(long state, long value) {
            return Long.rotateLeft(state ^ value, 17) * 0x9e3779b97f4a7c15L;
        }
    }
}
