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
 * @summary Encoding prefixes and scan results survive GC and active-frame deoptimization
 * @library /test/lib /
 * @modules java.base/jdk.internal.misc
 * @build jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @compile --patch-module java.base=${test.src}/../intrinsic ../intrinsic/java/lang/JeandleCharacterIntrinsicCaller.java
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler compiler.jeandle.pea.TestCharacterIntrinsicState
 */
package compiler.jeandle.pea;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.List;
import jdk.test.lib.Asserts;
import jdk.test.whitebox.WhiteBox;

public class TestCharacterIntrinsicState {
    public static void main(String[] args) throws Exception {
        Method target = TestWrapper.class.getMethod("test");
        Method deopt = TestWrapper.class.getDeclaredMethod("requestDeopt");
        String patch = "--patch-module=java.base=" + System.getProperty("test.class.path");
        PEATestUtils.behaviorRun(TestWrapper.class.getName(), target)
                .dontinline(deopt).extraFlags(patch).runPEAOnOffEquivalent();
        try (var run = PEATestUtils.shapeRun(TestWrapper.class.getName(), target)
                .dontinline(deopt).extraFlags(patch).keepDumps().run()) {
            var report = run.report(target);
            Asserts.assertEquals(report.round0Before().allocationBCIs().size(), 2);
            Asserts.assertEquals(report.finalAfter().allocationBCIs(), List.of());
            Asserts.assertEquals(run.finalIR(target).loweredAllocCount(), 0);
            String call = PEATestUtils.MethodId.of(deopt).llvmFunctionName();
            report.finalAfter().deoptBundleAtCall(call, 0).assertVirtualObjectIds(0, 1);
        }
    }

    public static class TestWrapper {
        private static boolean deoptEnabled;
        private static final Method TARGET = target();
        private static final MethodHandle ENCODE = find("encodeASCII", char[].class, int.class,
                byte[].class, int.class, int.class);
        private static final MethodHandle SCAN = find("countPositives", byte[].class, int.class, int.class);

        public static void main(String[] args) throws Throwable {
            for (int i = 0; i < 100; i++) Asserts.assertEquals(test(), 759);
            deoptEnabled = true;
            PEATestUtils.compileConfiguredTargetsAtLevel4();
            Asserts.assertEquals(test(), 759);
            System.out.println("PEA-RESULT:encoding-state-759");
        }

        public static int test() throws Throwable {
            char[] src = {'A', '\u0100'};
            byte[] dst = {0, 7};
            int prefix = (int) ENCODE.invokeExact(src, 0, dst, 0, 2);
            int positive = (int) SCAN.invokeExact(dst, 0, 2);
            requestDeopt();
            if (src.length != 2 || src[0] != 'A' || src[1] != '\u0100'
                    || dst.length != 2 || dst[0] != 65 || dst[1] != 7) {
                throw new AssertionError("encoding state lost");
            }
            return 100 * prefix + 10 * dst[0] + dst[1] + positive;
        }

        private static void requestDeopt() {
            if (!deoptEnabled) return;
            WhiteBox.getWhiteBox().fullGC();
            var evidence = PEATestUtils.deoptimizeActiveFrame(TARGET, 2);
            Asserts.assertTrue(evidence.frameDeoptimized());
            System.out.println("ENCODING-GC-DEOPT:" + evidence);
        }

        private static Method target() {
            try { return TestWrapper.class.getMethod("test"); }
            catch (ReflectiveOperationException e) { throw new ExceptionInInitializerError(e); }
        }

        private static MethodHandle find(String name, Class<?>... args) {
            try {
                return MethodHandles.publicLookup().findStatic(
                        Class.forName("java.lang.JeandleCharacterIntrinsicCaller"), name,
                        MethodType.methodType(int.class, args));
            } catch (ReflectiveOperationException e) { throw new ExceptionInInitializerError(e); }
        }
    }
}
