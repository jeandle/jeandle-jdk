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
 * @summary PEA batches all publication owners before observers and lock replay
 * @requires vm.gc.Serial & vm.gc.G1
 * @library /test/lib /
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler compiler.jeandle.pea.TestPEAPublicationBatch
 */

package compiler.jeandle.pea;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import jdk.test.lib.Asserts;

public class TestPEAPublicationBatch {
    public static void main(String[] args) throws Exception {
        Method primitive = TestWrapper.class.getMethod("primitive", int.class);
        Method graph = TestWrapper.class.getMethod("graph", int.class, Object.class);
        Method locked = TestWrapper.class.getMethod("locked", int.class);
        Method observePairs = TestWrapper.class.getDeclaredMethod(
                "observePairs", TestWrapper.Pair.class, TestWrapper.Pair.class);
        Method observeGraph = TestWrapper.class.getDeclaredMethod(
                "observeGraph", TestWrapper.Node.class, TestWrapper.Node.class, Object.class);
        Method[] targets = {primitive, graph, locked};
        for (String gc : new String[] {"-XX:+UseG1GC", "-XX:+UseSerialGC"}) {
            configure(PEATestUtils.behaviorRun(TestWrapper.class.getName(), targets),
                    gc, observePairs, observeGraph).runPEAOnOffEquivalent();
            try (var run = configure(PEATestUtils.shapeRun(TestWrapper.class.getName(), targets),
                    gc, observePairs, observeGraph).peaIterations(8).run()) {
                for (Method target : targets) {
                    var report = run.report(target);
                    report.round0Before().assertPresent("@jeandle.new_instance");
                    var after = report.finalAfter();
                    after.assertOccurrenceCount("@jeandle.membar_replay_publish", 1);
                    after.assertAbsent("@jeandle.membar_allocation_init");
                    after.assertAbsent("@jeandle.membar_constructor_freeze");
                    after.assertBefore("store atomic", 0, "@jeandle.membar_replay_publish", 0);
                    report.assertStoppedAtFixpoint();
                }
                var graphAfter = run.report(graph).finalAfter();
                graphAfter.assertPresent(PEATestUtils.referenceStore());
                run.report(locked).finalAfter().assertBefore("@jeandle.membar_replay_publish", 0,
                        "@jeandle.monitorenter", 0);
            }
        }
    }

    private static PEATestUtils.RunBuilder configure(PEATestUtils.RunBuilder builder,
            String gc, Method observePairs, Method observeGraph) throws Exception {
        return builder.extraFlags(gc, "-Xms64m", "-Xmx64m")
                .dontinline(observePairs).dontinline(observeGraph)
                .inline(TestWrapper.Pair.class.getDeclaredConstructor(int.class, int.class));
    }

    public static class TestWrapper {
        static final class Pair {
            final int first;
            final int second;
            Pair(int first, int second) { this.first = first; this.second = second; }
        }
        static final class Node {
            int value;
            Node next;
            Object payload;
        }
        static int collections;

        public static long primitive(int value) {
            Pair a = new Pair(value, ~value);
            Pair b = new Pair(value + 1, value ^ 0x5a5a);
            return observePairs(a, b);
        }

        public static int graph(int value, Object payload) {
            Node a = new Node();
            Node b = new Node();
            a.value = value;
            b.value = value + 1;
            a.next = b;
            b.next = a;
            a.payload = payload;
            b.payload = null;
            return observeGraph(a, b, payload);
        }

        public static long locked(int value) {
            Pair a = new Pair(value, ~value);
            Pair b = new Pair(value + 1, value ^ 0x5a5a);
            synchronized (a) {
                synchronized (b) {
                    return observePairs(a, b);
                }
            }
        }

        static long observePairs(Pair a, Pair b) {
            Asserts.assertNE(a, b);
            Asserts.assertEquals(a.second, ~a.first);
            Asserts.assertEquals(b.first, a.first + 1);
            Asserts.assertEquals(b.second, a.first ^ 0x5a5a);
            return ((long) a.first << 32) ^ (b.second & 0xffffffffL);
        }

        static int observeGraph(Node a, Node b, Object payload) {
            if ((a.value & 255) == 0) {
                long before = collectionCount();
                System.gc();
                Asserts.assertGT(collectionCount(), before, "full GC must execute");
                collections++;
            }
            Asserts.assertNE(a, b);
            Asserts.assertEQ(a.next, b);
            Asserts.assertEQ(b.next, a);
            Asserts.assertEQ(a.payload, payload);
            Asserts.assertNull(b.payload);
            Asserts.assertEquals(b.value, a.value + 1);
            return a.value ^ b.value;
        }

        static long collectionCount() {
            return ManagementFactory.getGarbageCollectorMXBeans().stream()
                    .mapToLong(bean -> Math.max(0, bean.getCollectionCount())).sum();
        }

        public static void main(String[] args) throws Exception {
            Object payload = new Object();
            primitive(1);
            graph(1, payload);
            locked(1);
            PEATestUtils.compileConfiguredTargetsAtLevel4();
            for (int value = -512; value < 512; value++) {
                long expected = ((long) value << 32) ^ ((value ^ 0x5a5a) & 0xffffffffL);
                Asserts.assertEquals(primitive(value), expected);
                Asserts.assertEquals(locked(value), expected);
                Asserts.assertEquals(graph(value, payload), value ^ (value + 1));
            }
            Asserts.assertGT(collections, 0);
            for (String target : new String[] {"primitive", "locked"}) {
                Method method = TestWrapper.class.getMethod(target, int.class);
                Asserts.assertTrue(jdk.test.whitebox.WhiteBox.getWhiteBox().isMethodCompiled(method));
            }
            Asserts.assertTrue(jdk.test.whitebox.WhiteBox.getWhiteBox().isMethodCompiled(
                    TestWrapper.class.getMethod("graph", int.class, Object.class)));
            Asserts.assertEquals(graph(0, payload), 1);
            System.out.println("PEA-RESULT:publication-batch-passed");
        }
    }
}
