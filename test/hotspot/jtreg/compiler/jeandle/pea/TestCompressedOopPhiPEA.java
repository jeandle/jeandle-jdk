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
 * @summary PEA handles compressed-oop AS3 PHI Cases A/B/C and holder replay
 * @library /test/lib /
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler -XX:+UseCompressedOops -XX:+UseCompressedClassPointers
 *      compiler.jeandle.pea.TestCompressedOopPhiPEA
 */

package compiler.jeandle.pea;

import java.lang.invoke.VarHandle;
import java.lang.reflect.Method;

import jdk.test.lib.Asserts;

public class TestCompressedOopPhiPEA {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestCompressedOopPhiPEA$TestWrapper";

    public static void main(String[] args) throws Exception {
        Method sameIdentity = TestWrapper.class.getMethod(
                "sameIdentity", boolean.class, int.class);
        Method distinctIdentity = TestWrapper.class.getMethod(
                "distinctIdentity", boolean.class, int.class, int.class);
        Method replayedValueMerge = TestWrapper.class.getMethod(
                "replayedValueMerge", boolean.class, int.class, int.class);
        Method selfLoaded = TestWrapper.class.getMethod(
                "selfLoaded", boolean.class, int.class, int.class);
        Method mixedVirtualAndReal = TestWrapper.class.getMethod(
                "mixedVirtualAndReal", boolean.class, int.class);
        Method opaque = TestWrapper.class.getDeclaredMethod("opaque", TestWrapper.Node.class);

        try (PEATestUtils.RunResult run = PEATestUtils.shapeRun(
                WRAPPER, sameIdentity, distinctIdentity, replayedValueMerge,
                selfLoaded, mixedVirtualAndReal)
                .dontinline(opaque).peaIterations(3).run()) {
            assertCaseB(run, sameIdentity);
            assertCaseA(run, mixedVirtualAndReal);
            assertNarrowCaseCMaterialization(run, distinctIdentity);
            assertReplayedAS1CaseC(run, replayedValueMerge);
            assertNarrowCaseCElimination(run, selfLoaded);
        }
        PEATestUtils.behaviorRun(
                WRAPPER, sameIdentity, distinctIdentity, replayedValueMerge,
                selfLoaded, mixedVirtualAndReal)
                .dontinline(opaque).peaIterations(3).runPEAOnOffEquivalent();
    }

    private static void assertCaseB(PEATestUtils.RunResult run, Method target)
            throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody firstAfter = report.round(0).after();
        assertCompressedPhiInput(before, target);
        Asserts.assertEquals(2, before.peaAllocCount(),
                target + ": Case B starts with holder and one referenced object");
        Asserts.assertEquals(0L, report.round(0).effectCount("CreatePHI"),
                target + ": same ObjectID must be an alias, not a synthetic object");
        firstAfter.assertAbsent("phi ptr addrspace(3)");
        Asserts.assertEquals(0, firstAfter.peaAllocCount(),
                target + ": Case B must eliminate both allocations in the first transform");
        Asserts.assertEquals(0, report.finalAfter().peaAllocCount(),
                target + ": same-identity reference merge must stay virtual");
        report.assertFinalTransformIdle();
    }

    private static void assertCaseA(
            PEATestUtils.RunResult run, Method target) throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody firstAfter = report.round(0).after();
        PEATestUtils.IRBody after = report.finalAfter();
        assertCompressedPhiInput(before, target);
        Asserts.assertEquals(1, before.peaAllocCount(),
                target + ": Case A starts with one virtual allocation");
        Asserts.assertEquals(0L, report.round(0).effectCount("CreatePHI"),
                target + ": mixed virtual/non-virtual inputs must not synthesize Case C");
        Asserts.assertTrue(report.round(0).effectCount(
                "Materialize", "[VO=0]") >= 1,
                target + ": Case A must materialize the virtual incoming");
        firstAfter.assertAbsent("pea.casec.field.phi");
        firstAfter.assertAbsent("pea.casec.replay.phi");
        Asserts.assertEquals(1, firstAfter.peaAllocCount(),
                target + ": Case A must retain the source allocation");
        for (PEATestUtils.PEARound round : report.rounds()) {
            Asserts.assertEquals(0L, round.effectCount("CreatePHI"),
                    target + ": Case A must not become Case C in a later round");
            round.after().assertAbsent("pea.casec.field.phi");
            round.after().assertAbsent("pea.casec.replay.phi");
        }
        Asserts.assertEquals(1, after.peaAllocCount(),
                target + ": stable Case A must keep the source allocation real");
        report.assertFinalTransformIdle();
    }

    private static void assertNarrowCaseCMaterialization(
            PEATestUtils.RunResult run, Method target) throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody firstAfter = report.round(0).after();
        PEATestUtils.IRBody after = report.finalAfter();
        assertCompressedPhiInput(before, target);
        Asserts.assertEquals(2, before.peaAllocCount(),
                target + ": materializing Case C starts with two distinct virtual objects");
        Asserts.assertEquals(2L, report.round(0).effectCount("CreatePHI"),
                target + ": Case C must merge the scalar field and replay identity");
        Asserts.assertEquals(1L, report.round(0).effectCount(
                "CreatePHI", "role=SyntheticReplayIdentity"),
                target + ": compressed Case C needs exactly one AS1 replay identity");
        Asserts.assertTrue(report.round(0).effectCount(
                "Materialize", "[VO=2]") >= 1,
                target + ": the opaque consumer must materialize the synthetic object");
        firstAfter.assertPresent("pea.casec.field.phi");
        firstAfter.assertPresent("pea.casec.replay.phi");
        firstAfter.assertAbsent("phi ptr addrspace(3)");
        firstAfter.assertPresent("store atomic i32 %pea.casec.field.phi");
        firstAfter.assertPresent("store atomic ptr addrspace(3)");
        Asserts.assertEquals(2, firstAfter.peaAllocCount(),
                target + ": materialization must retain both source allocations");
        after.assertPresent("pea.casec.field.phi");
        after.assertPresent("pea.casec.replay.phi");
        after.assertAbsent("phi ptr addrspace(3)");
        Asserts.assertEquals(2, after.peaAllocCount(),
                target + ": final IR must retain only the two source allocations");
        report.assertFinalTransformIdle();
    }

    private static void assertReplayedAS1CaseC(PEATestUtils.RunResult run, Method target)
            throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.PEARound first = report.round(0);
        PEATestUtils.PEARound second = report.round(1);
        PEATestUtils.IRBody before = first.before();
        PEATestUtils.IRBody replayed = first.after();
        PEATestUtils.IRBody merged = second.after();
        PEATestUtils.IRBody after = report.finalAfter();
        assertCompressedPhiInput(before, target);
        Asserts.assertEquals(3, before.peaAllocCount(),
                target + ": AS3 field merge starts with a holder and two distinct objects");
        Asserts.assertTrue(first.effectCount("Materialize", "[VO=1]") >= 1,
                target + ": AS3 field merge must replay the first distinct object");
        Asserts.assertTrue(first.effectCount("Materialize", "[VO=2]") >= 1,
                target + ": AS3 field merge must replay the second distinct object");
        replayed.assertAbsent("phi ptr addrspace(3)");
        replayed.assertPresent("phi ptr addrspace(1)");
        Asserts.assertEquals(2, replayed.peaAllocCount(),
                target + ": replay must retain exactly the two selected objects");
        Asserts.assertTrue(second.effectCount("CreatePHI", "[VO=2]") >= 1,
                target + ": replayed AS1 merge must synthesize the merged object field PHI");
        merged.assertPresent("pea.casec.field.phi");
        merged.assertAbsent("phi ptr addrspace(3)");
        Asserts.assertEquals(0, merged.peaAllocCount(),
                target + ": reanalyzed Case C must eliminate both replayed allocations");
        after.assertAbsent("phi ptr addrspace(3)");
        Asserts.assertEquals(0, after.peaAllocCount(),
                target + ": Case C must eliminate both replayed source allocations");
        report.assertFinalTransformIdle();
    }

    private static void assertNarrowCaseCElimination(
            PEATestUtils.RunResult run, Method target) throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody firstAfter = report.round(0).after();
        PEATestUtils.IRBody after = report.finalAfter();
        assertCompressedPhiInput(before, target);
        Asserts.assertEquals(2, before.peaAllocCount(),
                target + ": scalar Case C starts with two distinct virtual objects");
        Asserts.assertEquals(1L, report.round(0).effectCount("CreatePHI"),
                target + ": the differing scalar field needs exactly one merge");
        Asserts.assertEquals(1L, report.round(0).effectCount(
                "CreatePHI", "offset=12"),
                target + ": Case C must merge the Node.value field");
        firstAfter.assertPresent("pea.casec.field.phi");
        firstAfter.assertAbsent("pea.casec.replay.phi");
        firstAfter.assertAbsent("phi ptr addrspace(3)");
        firstAfter.assertAbsent("load atomic i32");
        firstAfter.assertAbsent("store atomic ptr addrspace(3)");
        Asserts.assertEquals(0, firstAfter.peaAllocCount(),
                target + ": non-escaping Case C must eliminate both source allocations");
        after.assertPresent("pea.casec.field.phi");
        after.assertAbsent("pea.casec.replay.phi");
        after.assertAbsent("phi ptr addrspace(3)");
        after.assertAbsent("load atomic i32");
        after.assertAbsent("store atomic ptr addrspace(3)");
        Asserts.assertEquals(0, after.peaAllocCount(),
                target + ": scalarized Case C must remain allocation-free");
        report.assertFinalTransformIdle();
    }

    private static void assertCompressedPhiInput(PEATestUtils.IRBody before, Method target)
            throws Exception {
        before.assertLineCount("phi ptr addrspace(3)", 1);
        before.assertPresent("addrspacecast ptr addrspace(3)");
        before.assertPresent("fence acquire");
    }

    public static class TestWrapper {
        static final class Node {
            int value;
            Node self;
        }

        static final class Holder {
            Node first;
            Node second;
        }

        private static Node publishedNode;

        public static void main(String[] args) throws Exception {
            new Node();
            new Holder();
            publishedNode = new Node();
            publishedNode.value = 97;
            publishedNode.self = publishedNode;
            // The hot path force-inlines VarHandle.acquireFence to Unsafe.loadFence.
            // The resulting fence retains two unordered reference loads for PEA.
            for (int i = 0; i < 20_000; i++) {
                sameIdentity((i & 1) == 0, i);
                distinctIdentity((i & 1) == 0, i, -i);
                replayedValueMerge((i & 1) == 0, i, -i);
                selfLoaded((i & 1) == 0, i, -i);
                mixedVirtualAndReal((i & 1) == 0, i);
            }
            PEATestUtils.compileConfiguredTargetsAtLevel4();

            int result = 0;
            for (boolean chooseFirst : new boolean[] {false, true}) {
                int value = sameIdentity(chooseFirst, 17);
                Asserts.assertEquals(value, 17, "sameIdentity");
                result = result * 31 + value;
            }
            for (boolean chooseFirst : new boolean[] {false, true}) {
                int left = 29;
                int right = 41;
                int value = distinctIdentity(chooseFirst, left, right);
                int expected = (chooseFirst ? left : right) * 7 + 3;
                Asserts.assertEquals(value, expected, "distinctIdentity");
                result = result * 31 + value;
            }
            for (boolean chooseFirst : new boolean[] {false, true}) {
                int left = 53;
                int right = 67;
                int value = replayedValueMerge(chooseFirst, left, right);
                Asserts.assertEquals(value, chooseFirst ? left : right, "replayedValueMerge");
                result = result * 31 + value;
            }
            for (boolean chooseFirst : new boolean[] {false, true}) {
                int left = 71;
                int right = 83;
                int value = selfLoaded(chooseFirst, left, right);
                Asserts.assertEquals(value, chooseFirst ? left : right, "selfLoaded");
                result = result * 31 + value;
            }
            for (boolean chooseVirtual : new boolean[] {false, true}) {
                int localValue = 89;
                int value = mixedVirtualAndReal(chooseVirtual, localValue);
                int selectedValue = chooseVirtual ? localValue : publishedNode.value;
                int expected = selectedValue * 7 + 3;
                Asserts.assertEquals(value, expected, "mixedVirtualAndReal");
                result = result * 31 + value;
            }
            System.out.println("PEA-RESULT:" + result);
        }

        public static int sameIdentity(boolean chooseFirst, int value) {
            Holder holder = new Holder();
            Node node = new Node();
            node.value = value;
            holder.first = node;
            holder.second = node;
            // Keep the two reference-field loads visible to PEA.
            VarHandle.acquireFence();

            Node selected;
            if (chooseFirst) {
                selected = holder.first;
            } else {
                selected = holder.second;
            }
            return selected.value;
        }

        public static int mixedVirtualAndReal(boolean chooseVirtual, int value) {
            Node local = new Node();
            local.value = value;
            local.self = local;
            // Preserve the compressed self-field load until PEA. The other
            // incoming comes from a published object and is therefore real.
            VarHandle.acquireFence();

            Node selected;
            if (chooseVirtual) {
                selected = local.self;
            } else {
                selected = publishedNode;
            }
            return opaque(selected);
        }

        public static int distinctIdentity(boolean chooseFirst, int leftValue, int rightValue) {
            Node left = new Node();
            left.value = leftValue;
            left.self = left;
            Node right = new Node();
            right.value = rightValue;
            right.self = right;
            // Keep the two self-field loads visible to PEA.
            VarHandle.acquireFence();

            Node selected;
            if (chooseFirst) {
                selected = left.self;
            } else {
                selected = right.self;
            }
            return opaque(selected);
        }

        public static int replayedValueMerge(boolean chooseFirst, int leftValue, int rightValue) {
            Holder holder = new Holder();
            Node left = new Node();
            Node right = new Node();
            left.value = leftValue;
            right.value = rightValue;
            holder.first = left;
            holder.second = right;
            // Keep the two reference-field loads visible to PEA.
            VarHandle.acquireFence();

            Node selected;
            if (chooseFirst) {
                selected = holder.first;
            } else {
                selected = holder.second;
            }
            return selected.value;
        }

        public static int selfLoaded(boolean chooseFirst,
                                     int leftValue, int rightValue) {
            Node left = new Node();
            left.value = leftValue;
            left.self = left;
            Node right = new Node();
            right.value = rightValue;
            right.self = right;
            // Keep the two compressed self-field loads visible to PEA. Case C
            // can then merge the per-edge self references into one synthetic
            // self reference without materializing either source object.
            VarHandle.acquireFence();

            Node selected;
            if (chooseFirst) {
                selected = left.self;
            } else {
                selected = right.self;
            }
            return selected.value;
        }

        private static int opaque(Node node) {
            return node.value * 7 + 3;
        }
    }
}
