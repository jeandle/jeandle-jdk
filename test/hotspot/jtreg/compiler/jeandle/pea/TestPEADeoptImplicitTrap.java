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
 * @summary PEA reconstructs virtual graphs at natural implicit exception and uncommon traps
 * @library /test/lib /
 * @modules java.base/jdk.internal.misc
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      -XX:-UseCompressedOops -XX:-UseCompressedClassPointers
 *      compiler.jeandle.pea.TestPEADeoptImplicitTrap
 */

package compiler.jeandle.pea;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jdk.internal.misc.Unsafe;
import jdk.test.lib.Asserts;
import jdk.test.whitebox.WhiteBox;

public class TestPEADeoptImplicitTrap {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestPEADeoptImplicitTrap$TestWrapper";
    private static final String SCENARIO_PROPERTY =
            "compiler.jeandle.pea.implicitTrapScenario";
    private static final String DEOPT_CALLEE =
            "llvm.experimental.deoptimize.i32";
    private static final Unsafe UNSAFE = Unsafe.getUnsafe();
    private static final int NULL_TRAP_BCI = 35;
    private static final int CHECKCAST_TRAP_BCI = 25;
    private static final int BOUNDS_TRAP_BCI = 33;
    private static final int DIVIDE_TRAP_BCI = 38;
    private static final int UNCOMMON_TRAP_BCI = 30;

    private enum Scenario {
        NULL("null", true, 1, 0, NULL_TRAP_BCI),
        CHECKCAST("checkcast", false, 1, 0, CHECKCAST_TRAP_BCI),
        BOUNDS("bounds", false, 2, 1, BOUNDS_TRAP_BCI),
        DIVIDE("divide-zero", true, 1, 0, DIVIDE_TRAP_BCI),
        UNCOMMON("uncommon branch", true, 1, 0, UNCOMMON_TRAP_BCI);

        private final String kind;
        private final boolean hasMonitor;
        private final int trapCountAtBCI;
        private final int trapOccurrenceAtBCI;
        private final int trapBCI;

        Scenario(String kind, boolean hasMonitor, int trapCount, int trapOccurrence,
                 int trapBCI) {
            this.kind = kind;
            this.hasMonitor = hasMonitor;
            this.trapCountAtBCI = trapCount;
            this.trapOccurrenceAtBCI = trapOccurrence;
            this.trapBCI = trapBCI;
        }
    }

    public static void main(String[] args) throws Exception {
        for (Scenario scenario : Scenario.values()) {
            runScenario(scenario);
        }
    }

    private static void runScenario(Scenario scenario) throws Exception {
        Method target = targetFor(scenario);
        builder(false, target, scenario).runPEAOnOffEquivalent();
        try (PEATestUtils.RunResult run = builder(true, target, scenario).run()) {
            assertTrapShape(run, target, scenario);
        }
    }

    private static Method targetFor(Scenario scenario) throws ReflectiveOperationException {
        return switch (scenario) {
            case NULL -> TestWrapper.class.getMethod(
                    "nullTrap", TestWrapper.External.class, int.class);
            case CHECKCAST -> TestWrapper.class.getMethod("checkcastTrap", Object.class,
                    int.class);
            case BOUNDS -> TestWrapper.class.getMethod("boundsTrap", int[].class,
                    int.class, int.class);
            case DIVIDE -> TestWrapper.class.getMethod("divideTrap", int.class, int.class);
            case UNCOMMON -> TestWrapper.class.getMethod("uncommonTrap", int.class, int.class);
        };
    }

    private static PEATestUtils.RunBuilder builder(boolean shape, Method target,
                                                    Scenario scenario)
            throws ReflectiveOperationException {
        PEATestUtils.RunBuilder builder = shape ? PEATestUtils.shapeRun(WRAPPER, target)
                : PEATestUtils.behaviorRun(WRAPPER, target);
        return builder.extraFlags("-D" + SCENARIO_PROPERTY + "=" + scenario.name(),
                        "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
                        "-XX:+JeandleUseProfile",
                        "-XX:ProfileMaturityPercentage=0",
                        "-XX:CompileThreshold=20000")
                .inline(TestWrapper.class.getDeclaredMethod("graphRoot"))
                .inline(TestWrapper.class.getDeclaredMethod(
                        "graphArray", TestWrapper.Node.class,
                        TestWrapper.Node.class, TestWrapper.Node.class));
    }

    private static void assertTrapShape(PEATestUtils.RunResult run, Method target,
                                        Scenario scenario)
            throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        report.assertFinalTransformIdle();
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();

        Asserts.assertEquals(before.peaAllocCount(), 4,
                target + ": root, peer, shared child, and object array enter PEA");
        assertTrapSelection(before, target, scenario, "frontend");
        assertTrapSelection(after, target, scenario, "final");
        List<Integer> occurrences = trapOccurrences(after, target, scenario, "final");
        int trapOccurrence = occurrences.get(scenario.trapOccurrenceAtBCI);
        PEATestUtils.DeoptBundle bundle = after.deoptBundleAtCall(
                DEOPT_CALLEE, trapOccurrence);
        Asserts.assertEquals(bundle.scopes().size(), 1,
                target + ": trap has one active Java scope");
        Asserts.assertEquals(bundle.rootScope().duplicateBCI(), bundle.rootScope().bci(),
                target + ": trap BCI is duplicated exactly");
        assertGraph(bundle, target, scenario.hasMonitor);

        PEATestUtils.IRBlock trapBlock =
                after.blockContaining("@llvm.experimental.deoptimize", trapOccurrence);
        trapBlock.assertAbsent("@jeandle.new_");
        trapBlock.assertAbsent("store atomic i32");
        trapBlock.assertAbsent("store atomic i32 900");
    }

    private static void assertTrapSelection(PEATestUtils.IRBody body, Method target,
                                            Scenario scenario, String phase) {
        List<Integer> occurrences = trapOccurrences(body, target, scenario, phase);
        int selectedOccurrence = occurrences.get(scenario.trapOccurrenceAtBCI);
        PEATestUtils.DeoptBundle selected = body.deoptBundleAtCall(
                DEOPT_CALLEE, selectedOccurrence);
        Asserts.assertEquals(selected.rootScope().bci(), scenario.trapBCI,
                target + ": selected " + phase + " " + scenario.kind + " trap BCI");
        if (scenario == Scenario.BOUNDS) {
            // The frontend emits the array null guard before the bounds guard;
            // both naturally describe the iaload BCI but use distinct fail blocks.
            PEATestUtils.DeoptBundle first = body.deoptBundleAtCall(
                    DEOPT_CALLEE, occurrences.get(0));
            Asserts.assertEquals(first.rootScope().bci(), scenario.trapBCI,
                    target + ": first " + phase + " bounds-method trap BCI");
            if (phase.equals("frontend")) {
                assertBoundsFailBlocks(body, target, occurrences);
            }
        }
    }

    private static List<Integer> trapOccurrences(
            PEATestUtils.IRBody body, Method target,
            Scenario scenario, String phase) {
        List<Integer> occurrences = body.callOccurrencesAtBCI(
                DEOPT_CALLEE, scenario.trapBCI);
        Asserts.assertEquals(occurrences.size(), scenario.trapCountAtBCI,
                target + ": exact " + phase + " " + scenario.kind
                        + " trap count at BCI " + scenario.trapBCI);
        return occurrences;
    }

    private static void assertBoundsFailBlocks(
            PEATestUtils.IRBody body, Method target, List<Integer> occurrences) {
        PEATestUtils.IRBlock nullFail = body.blockContaining(
                "@llvm.experimental.deoptimize", occurrences.get(0));
        Asserts.assertEquals(nullFail.occurrenceCount("bci_33_null_check_fail:"), 1,
                target + ": first bounds-method trap is the null-check fail block");
        PEATestUtils.IRBlock rangeFail = body.blockContaining(
                "@llvm.experimental.deoptimize", occurrences.get(1));
        Asserts.assertEquals(rangeFail.occurrenceCount("bci_33_boundary_check_fail:"), 1,
                target + ": second bounds-method trap is the range-check fail block");
        body.assertBefore("label %bci_33_null_check_fail", 0,
                "bci_33_null_check_fail:", 0);
        body.assertBefore("label %bci_33_boundary_check_fail", 0,
                "bci_33_boundary_check_fail:", 0);
    }

    private static void assertGraph(PEATestUtils.DeoptBundle bundle, Method target,
                                    boolean hasMonitor) throws Exception {
        bundle.assertVirtualObjectIds(0, 1, 2, 3);
        int valueOffset = offset("value");
        int leftOffset = offset("left");
        int sharedOffset = offset("shared");
        int base = Unsafe.ARRAY_OBJECT_BASE_OFFSET;
        int scale = Unsafe.ARRAY_OBJECT_INDEX_SCALE;

        Map<Integer, PEATestUtils.VirtualObjectDescriptor> nodes = new HashMap<>();
        PEATestUtils.VirtualObjectDescriptor array = null;
        for (PEATestUtils.VirtualObjectDescriptor descriptor : bundle.virtualObjects().values()) {
            if (descriptor.kind() == PEATestUtils.DescriptorKind.ARRAY) {
                Asserts.assertNull(array, target + ": exactly one object-array descriptor");
                array = descriptor;
                continue;
            }
            Asserts.assertEquals(descriptor.kind(), PEATestUtils.DescriptorKind.INSTANCE,
                    target + ": graph node descriptor kind");
            Asserts.assertEquals(descriptor.fields().keySet(),
                    Set.of(valueOffset, leftOffset, sharedOffset),
                    target + ": exact graph node field topology");
            int value = scalarValue(descriptor, valueOffset, target);
            Asserts.assertNull(nodes.put(value, descriptor),
                    target + ": graph node value is unique");
        }
        Asserts.assertNotNull(array, target + ": object-array descriptor");
        Asserts.assertEquals(nodes.keySet(), Set.of(101, 202, 303),
                target + ": exact graph node values at trap");
        Asserts.assertEquals(array.elements().keySet(),
                Set.of(base, base + scale, base + 2 * scale),
                target + ": exact object-array element topology");

        PEATestUtils.VirtualObjectDescriptor root = nodes.get(101);
        PEATestUtils.VirtualObjectDescriptor peer = nodes.get(202);
        PEATestUtils.VirtualObjectDescriptor shared = nodes.get(303);
        bundle.assertVORef(root.id(), leftOffset, peer.id());
        bundle.assertVORef(root.id(), sharedOffset, shared.id());
        bundle.assertVORef(peer.id(), leftOffset, root.id());
        bundle.assertVORef(peer.id(), sharedOffset, shared.id());
        bundle.assertVORef(shared.id(), leftOffset, root.id());
        bundle.assertVORef(shared.id(), sharedOffset, shared.id());
        bundle.assertVORef(array.id(), base, root.id());
        bundle.assertVORef(array.id(), base + scale, peer.id());
        bundle.assertVORef(array.id(), base + 2 * scale, shared.id());

        if (hasMonitor) {
            Asserts.assertEquals(bundle.rootScope().monitors().size(), 1,
                    target + ": one active monitor at the trap");
            PEATestUtils.DeoptMonitor monitor = bundle.rootScope().monitors().get(0);
            Asserts.assertTrue(monitor.eliminated(),
                    target + ": monitor is represented as eliminated virtual state");
            Asserts.assertEquals(monitor.owner().kind(),
                    PEATestUtils.DeoptValueKind.VO_REF,
                    target + ": monitor owner is a typed VORef");
            Asserts.assertEquals(monitor.owner().virtualObjectId(), root.id(),
                    target + ": monitor owner retains root identity");
        } else {
            Asserts.assertEquals(bundle.rootScope().monitors().size(), 0,
                    target + ": no monitor in this trap state");
        }
    }

    private static int scalarValue(PEATestUtils.VirtualObjectDescriptor descriptor,
                                   int valueOffset, Method target) {
        PEATestUtils.VirtualObjectEntry value = descriptor.fields().get(valueOffset);
        Asserts.assertNotNull(value, target + ": graph node value field");
        Asserts.assertEquals(value.basicType(), PEATestUtils.DeoptBasicType.INT,
                target + ": graph node value type");
        Asserts.assertEquals(value.value().kind(), PEATestUtils.DeoptValueKind.SCALAR,
                target + ": graph node value is scalar");
        String operand = value.value().operand();
        Asserts.assertTrue(operand.startsWith("i32 "),
                target + ": graph node value uses an exact i32 operand");
        return Integer.parseInt(operand.substring("i32 ".length()));
    }

    private static int offset(String name) throws Exception {
        Field field = TestWrapper.Node.class.getDeclaredField(name);
        return Math.toIntExact(UNSAFE.objectFieldOffset(field));
    }

    public static class TestWrapper {
        private static final int WARMUP_ITERATIONS = 10_000;
        private static final WhiteBox WB = WhiteBox.getWhiteBox();
        private static int normalPaths;
        private static int nullCatches;
        private static int checkcastCatches;
        private static int boundsCatches;
        private static int divideCatches;
        private static int uncommonBranches;
        private static int monitorReacquires;

        public static class Node {
            int value;
            Node left;
            Node shared;
        }

        public static class External {
            int value;
        }

        public static class Accepted {
        }

        public static class Rejected { }

        public static void main(String[] args) throws Exception {
            Scenario scenario = selectedScenario();
            new Node();
            new External();
            new Accepted();
            new Rejected();
            long digest = 0x6A09E667F3BCC909L;
            warmNormalPath(scenario);
            Asserts.assertEquals(normalPaths, WARMUP_ITERATIONS,
                    scenario + ": repeated warm normal path");

            PEATestUtils.compileConfiguredTargetsAtLevel4();

            Method target = targetFor(scenario);
            assertCompiledBeforeColdTrap(target);
            digest = mix(digest, triggerColdPath(scenario));
            assertColdOutcome(scenario);
            digest = mix(digest, normalPaths);
            digest = mix(digest, nullCatches);
            digest = mix(digest, checkcastCatches);
            digest = mix(digest, boundsCatches);
            digest = mix(digest, divideCatches);
            digest = mix(digest, uncommonBranches);
            digest = mix(digest, monitorReacquires);
            System.out.println("PEA-RESULT:" + Long.toUnsignedString(digest, 16));
        }

        // Add future trap categories only with a natural trigger, behavior path,
        // and exact typed bundle assertion like the five cases below.
        public static int nullTrap(External external, int seed) {
            Node root = graphRoot();
            Node peer = root.left;
            Node shared = root.shared;
            Object[] array = graphArray(root, peer, shared);
            try {
                synchronized (root) {
                    root.value += external.value;
                    peer.value += array.length;
                }
                normalPaths++;
            } catch (NullPointerException expected) {
                nullCatches++;
                mutateAfterTrap(root, peer, shared, array, seed);
                if (Thread.holdsLock(root)) {
                    return Integer.MIN_VALUE + 1;
                }
                synchronized (root) {
                    if (!Thread.holdsLock(root)) {
                        return Integer.MIN_VALUE + 2;
                    }
                    root.value += 900;
                    monitorReacquires++;
                }
            }
            return verifyAndDigest(root, peer, shared, array, seed);
        }

        public static int checkcastTrap(Object candidate, int seed) {
            Node root = graphRoot();
            Node peer = root.left;
            Node shared = root.shared;
            Object[] array = graphArray(root, peer, shared);
            try {
                Accepted accepted = (Accepted) candidate;
                root.value += accepted == candidate ? 11 : 0;
                peer.value += array.length;
                normalPaths++;
            } catch (ClassCastException expected) {
                checkcastCatches++;
                mutateAfterTrap(root, peer, shared, array, seed);
            }
            return verifyAndDigest(root, peer, shared, array, seed);
        }

        public static int boundsTrap(int[] values, int index, int seed) {
            Node root = graphRoot();
            Node peer = root.left;
            Node shared = root.shared;
            Object[] array = graphArray(root, peer, shared);
            try {
                root.value += values[index];
                peer.value += array.length;
                normalPaths++;
            } catch (ArrayIndexOutOfBoundsException expected) {
                boundsCatches++;
                mutateAfterTrap(root, peer, shared, array, seed);
            }
            return verifyAndDigest(root, peer, shared, array, seed);
        }

        public static int divideTrap(int divisor, int seed) {
            Node root = graphRoot();
            Node peer = root.left;
            Node shared = root.shared;
            Object[] array = graphArray(root, peer, shared);
            try {
                synchronized (root) {
                    root.value += 900 / divisor;
                    peer.value += array.length;
                }
                normalPaths++;
            } catch (ArithmeticException expected) {
                divideCatches++;
                mutateAfterTrap(root, peer, shared, array, seed);
            }
            return verifyAndDigest(root, peer, shared, array, seed);
        }

        public static int uncommonTrap(int value, int seed) {
            Node root = graphRoot();
            Node peer = root.left;
            Node shared = root.shared;
            Object[] array = graphArray(root, peer, shared);
            synchronized (root) {
                if (value < 0) {
                    root.value += -value;
                    peer.value += array.length;
                    uncommonBranches++;
                } else {
                    root.value += value;
                    peer.value += array.length;
                    normalPaths++;
                }
            }
            return verifyAndDigest(root, peer, shared, array, seed);
        }

        private static Node graphRoot() {
            Node root = new Node();
            Node peer = new Node();
            Node shared = new Node();
            root.value = 101;
            peer.value = 202;
            shared.value = 303;
            root.left = peer;
            root.shared = shared;
            peer.left = root;
            peer.shared = shared;
            shared.left = root;
            shared.shared = shared;
            return root;
        }

        private static Object[] graphArray(Node root, Node peer, Node shared) {
            Object[] array = new Object[3];
            array[0] = root;
            array[1] = peer;
            array[2] = shared;
            return array;
        }

        private static void mutateAfterTrap(Node root, Node peer, Node shared,
                                            Object[] array, int seed) {
            if (array[0] != root || array[1] != peer || array[2] != shared
                    || root.left != peer || root.shared != shared
                    || peer.left != root || peer.shared != shared
                    || shared.left != root || shared.shared != shared) {
                throw new AssertionError("reconstructed graph identity");
            }
            root.value += seed;
            peer.value += seed * 2;
            shared.value += seed * 3;
        }

        private static int verifyAndDigest(Node root, Node peer, Node shared,
                                           Object[] array, int seed) {
            if (array[0] != root || array[1] != peer || array[2] != shared
                    || root.left != peer || root.shared != shared
                    || peer.left != root || peer.shared != shared
                    || shared.left != root || shared.shared != shared) {
                return Integer.MIN_VALUE + 3;
            }
            return root.value * 31 + peer.value * 17 + shared.value * 7 + seed;
        }

        private static External external(int value) {
            External external = new External();
            external.value = value;
            return external;
        }

        private static long mix(long accumulator, long value) {
            return Long.rotateLeft(accumulator ^ value, 13)
                    * 0x9E3779B97F4A7C15L;
        }

        private static Scenario selectedScenario() {
            String configured = System.getProperty(SCENARIO_PROPERTY);
            if (configured == null || configured.isEmpty()) {
                throw new IllegalStateException("No implicit-trap scenario configured");
            }
            try {
                return Scenario.valueOf(configured);
            } catch (IllegalArgumentException failure) {
                throw new IllegalStateException("Unknown implicit-trap scenario " + configured,
                        failure);
            }
        }

        private static void warmNormalPath(Scenario scenario) {
            for (int iteration = 0; iteration < WARMUP_ITERATIONS; iteration++) {
                switch (scenario) {
                    case NULL -> nullTrap(external(7), 1);
                    case CHECKCAST -> checkcastTrap(new Accepted(), 2);
                    case BOUNDS -> boundsTrap(new int[] {17, 29, 43}, 1, 3);
                    case DIVIDE -> divideTrap(9, 4);
                    case UNCOMMON -> uncommonTrap(17, 5);
                }
            }
        }

        private static int triggerColdPath(Scenario scenario) {
            return switch (scenario) {
                case NULL -> nullTrap(null, 4);
                case CHECKCAST -> checkcastTrap(new Rejected(), 5);
                case BOUNDS -> boundsTrap(new int[] {17, 29, 43}, 4, 6);
                case DIVIDE -> divideTrap(0, 7);
                case UNCOMMON -> uncommonTrap(-17, 8);
            };
        }

        private static void assertColdOutcome(Scenario scenario) {
            Asserts.assertEquals(nullCatches, scenario == Scenario.NULL ? 1 : 0,
                    scenario + ": natural null catches");
            Asserts.assertEquals(checkcastCatches, scenario == Scenario.CHECKCAST ? 1 : 0,
                    scenario + ": natural checkcast catches");
            Asserts.assertEquals(boundsCatches, scenario == Scenario.BOUNDS ? 1 : 0,
                    scenario + ": natural bounds catches");
            Asserts.assertEquals(divideCatches, scenario == Scenario.DIVIDE ? 1 : 0,
                    scenario + ": natural divide-zero catches");
            Asserts.assertEquals(uncommonBranches, scenario == Scenario.UNCOMMON ? 1 : 0,
                    scenario + ": natural uncommon branches");
            Asserts.assertEquals(monitorReacquires, scenario == Scenario.NULL ? 1 : 0,
                    scenario + ": null monitor reacquires");
        }

        private static void assertCompiledBeforeColdTrap(Method target) {
            boolean compiled = WB.isMethodCompiled(target);
            int level = WB.getMethodCompilationLevel(target);
            Asserts.assertTrue(compiled && level == 4,
                    target + ": level-4 nmethod before natural trap");
        }
    }
}
