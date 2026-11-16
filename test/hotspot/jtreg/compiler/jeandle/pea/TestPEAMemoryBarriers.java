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
 * @summary Object ordering follows PEA virtualization, replay and clone ownership
 * @library /test/lib /
 * @modules java.base/jdk.internal.misc java.base/jdk.internal.vm.annotation
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler compiler.jeandle.pea.TestPEAMemoryBarriers
 */

package compiler.jeandle.pea;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import jdk.internal.misc.Unsafe;
import jdk.internal.vm.annotation.Stable;
import jdk.test.lib.Asserts;

public class TestPEAMemoryBarriers {
    public static void main(String[] args) throws Exception {
        Method local = TestWrapper.class.getMethod("localFinal", int.class);
        Method escape = TestWrapper.class.getMethod("escapeFinal", int.class);
        Method virtualClone = TestWrapper.class.getMethod("virtualClone", int.class);
        Method concreteClone = TestWrapper.class.getMethod("concreteClone", TestWrapper.class);
        Method stable = TestWrapper.class.getMethod("stableLocal", int.class);
        Method staticStable = TestWrapper.class.getMethod("staticStable", int.class);
        Method fences = TestWrapper.class.getMethod("unsafeFences", int.class);
        Method reference = TestWrapper.class.getMethod("readReference", WeakReference.class);
        Method[] targets = {local, escape, virtualClone, concreteClone, stable, staticStable, fences, reference};
        exports(PEATestUtils.behaviorRun(TestWrapper.class.getName(), targets))
                .runPEAOnOffEquivalent();
        try (PEATestUtils.RunResult run =
                exports(PEATestUtils.shapeRun(TestWrapper.class.getName(), targets)).run()) {
            var localReport = run.report(local);
            localReport.round0Before().assertPresent("@jeandle.membar_constructor_freeze");
            localReport.finalAfter().assertAbsent("@jeandle.new_instance");
            localReport.finalAfter().assertAbsent("@jeandle.membar_constructor_freeze");
            var escaped = run.report(escape).finalAfter();
            escaped.assertPresent("@jeandle.new_instance");
            escaped.assertBefore("store atomic i32", 0, "@jeandle.membar_allocation_init", 0);
            escaped.assertAbsent("@jeandle.membar_constructor_freeze");
            for (Method clone : new Method[] {virtualClone, concreteClone}) {
                run.report(clone).round0Before().assertPresent("@jeandle.copy");
                run.report(clone).round0Before().assertPresent("@jeandle.membar_clone_source");
                run.report(clone).finalAfter().assertAbsent("@jeandle.copy");
                run.report(clone).finalAfter().assertAbsent("@jeandle.new_instance");
                run.report(clone).finalAfter().assertAbsent("@jeandle.membar_allocation_init");
            }
            run.report(virtualClone).finalAfter().assertAbsent("@jeandle.membar_clone_source");
            run.report(concreteClone).finalAfter().assertPresent("@jeandle.membar_clone_source");
            run.report(concreteClone).finalAfter().assertPresent("load atomic i32");
            run.report(stable).finalAfter().assertAbsent("@jeandle.new_instance");
            run.report(stable).finalAfter().assertPresent("@jeandle.membar_release");
            // Only INSTANCE @Stable writes order the value's publication at
            // method exit; a static @Stable store writes no freshly published
            // instance field, so no release marker may appear.
            run.report(staticStable).finalAfter().assertAbsent("@jeandle.membar_release");
            for (String kind : new String[] {"acquire", "release", "full"}) {
                run.report(fences).round0Before().assertPresent("@jeandle.membar_" + kind);
                run.report(fences).finalAfter().assertPresent("@jeandle.membar_" + kind);
            }
            run.report(reference).round0Before().assertPresent("@jeandle.reference_get");
            for (Method target : targets) {
                run.report(target).assertFinalTransformIdle();
            }
        }
        // Parse Reference.get's actual getfield as well as its intrinsic path.
        try (PEATestUtils.RunResult run = exports(PEATestUtils.shapeRun(
                TestWrapper.class.getName(), reference))
                .inline(java.lang.ref.Reference.class.getMethod("get"))
                .extraFlags("-XX:DisableIntrinsic=_Reference_get").run()) {
            run.report(reference).round0Before().assertPresent("@jeandle.reference_get");
        }
    }

    private static PEATestUtils.RunBuilder exports(PEATestUtils.RunBuilder builder) {
        // HotSpot recognizes @Stable only on trusted (bootstrap) classes.
        return builder.extraFlags(
                "-Xbootclasspath/a:" + System.getProperty("test.class.path"),
                "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
                "--add-exports=java.base/jdk.internal.vm.annotation=ALL-UNNAMED");
    }

    public static final class TestWrapper implements Cloneable {
        final int value;
        TestWrapper(int value) { this.value = value; }
        static class StableBox { @Stable int value; }
        // The static @Stable field is read only by keepStaticStableObserved so
        // the store in staticStable stays observable; the benchmark path never
        // reads it back, keeping the test independent of @Stable value folding.
        static class StaticStableBox { @Stable static int value; }

        public static int localFinal(int value) {
            return new TestWrapper(value).value;
        }
        public static TestWrapper escapeFinal(int value) {
            return new TestWrapper(value);
        }
        public static int virtualClone(int value) throws CloneNotSupportedException {
            TestWrapper source = new TestWrapper(value);
            return ((TestWrapper) source.clone()).value;
        }
        public static int concreteClone(TestWrapper source) throws CloneNotSupportedException {
            return ((TestWrapper) source.clone()).value;
        }
        public static int stableLocal(int value) {
            StableBox box = new StableBox();
            box.value = value;
            return box.value;
        }
        public static int staticStable(int value) {
            StaticStableBox.value = value;
            return value * 2;
        }
        static int keepStaticStableObserved() {
            return StaticStableBox.value;
        }
        public static int unsafeFences(int value) {
            Unsafe unsafe = Unsafe.getUnsafe();
            unsafe.loadFence();
            int result = new TestWrapper(value).value;
            unsafe.storeFence();
            unsafe.fullFence();
            return result;
        }
        public static Object readReference(WeakReference<Object> reference) {
            return reference.get();
        }
        public static void main(String[] args) throws Exception {
            TestWrapper source = new TestWrapper(37);
            source.clone();
            Object referent = new Object();
            WeakReference<Object> reference = new WeakReference<>(referent);
            reference.get();
            Unsafe.getUnsafe();
            new StableBox();
            // Initialize StaticStableBox before compilation: an uninitialized
            // holder compiles the putstatic to an entry uncommon trap, whose
            // body has no store and no barrier to assert on.
            keepStaticStableObserved();
            PEATestUtils.compileConfiguredTargetsAtLevel4();
            for (int value : new int[] {0, 17, -51}) {
                Asserts.assertEquals(localFinal(value), value);
                Asserts.assertEquals(escapeFinal(value).value, value);
                Asserts.assertEquals(virtualClone(value), value);
                Asserts.assertEquals(stableLocal(value), value);
                Asserts.assertEquals(staticStable(value), value * 2);
                Asserts.assertEquals(unsafeFences(value), value);
            }
            if (keepStaticStableObserved() == Integer.MIN_VALUE) {
                throw new AssertionError("unreachable");
            }
            Asserts.assertEquals(concreteClone(source), 37);
            Asserts.assertEQ(readReference(reference), referent);
            reference.clear();
            Asserts.assertNull(readReference(reference));
            System.out.println("PEA-RESULT:memory-barriers-passed");
        }
    }
}
