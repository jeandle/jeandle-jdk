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
 * @run driver jdk.test.lib.helpers.ClassFileInstaller -jar character-state-bridge.jar java.lang.JeandleCharacterIntrinsicCaller
 * @run main/othervm --patch-module=java.base=character-state-bridge.jar -XX:-UseJeandleCompiler compiler.jeandle.pea.TestCharacterIntrinsicState
 */
package compiler.jeandle.pea;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.nio.ByteOrder;
import jdk.test.lib.Asserts;
import jdk.test.whitebox.WhiteBox;

public class TestCharacterIntrinsicState {
    public static void main(String[] args) throws Exception {
        Method target = TestWrapper.class.getMethod("test");
        Method utf16 = TestWrapper.class.getMethod("utf1664", int.class, boolean.class);
        Method concrete = TestWrapper.class.getMethod("utf1665", int.class, boolean.class);
        Method[] targets = {target, utf16, concrete};
        Method deopt = TestWrapper.class.getDeclaredMethod("requestDeopt");
        Method bridge = Class.forName("java.lang.JeandleCharacterIntrinsicCaller")
                .getMethod("encodeISOBytes", byte[].class, int.class, byte[].class,
                        int.class, int.class);
        String patch = "--patch-module=java.base=character-state-bridge.jar";
        PEATestUtils.behaviorRun(TestWrapper.class.getName(), targets)
                .dontinline(deopt).inline(bridge).extraFlags(patch).runPEAOnOffEquivalent();
        try (var run = PEATestUtils.shapeRun(TestWrapper.class.getName(), targets)
                .dontinline(deopt).inline(bridge).extraFlags(patch).keepDumps().run()) {
            for (Method method : targets) {
                var report = run.report(method);
                Asserts.assertEquals(report.round0Before().allocationBCIs().size(), 2);
                int remaining = method.equals(concrete) ? 1 : 0;
                Asserts.assertEquals(report.finalAfter().allocationBCIs().size(), remaining);
                Asserts.assertEquals(run.finalIR(method).loweredAllocCount(), remaining);
                String call = PEATestUtils.MethodId.of(deopt).llvmFunctionName();
                if (method.equals(concrete)) {
                    report.finalAfter().deoptBundleAtCall(call, 0).assertVirtualObjectIds(0);
                } else {
                    report.finalAfter().deoptBundleAtCall(call, 0).assertVirtualObjectIds(0, 1);
                }
            }
        }
    }

    public static class TestWrapper {
        private static boolean deoptEnabled;
        private static final Method TARGET = target("test");
        private static final Method UTF16_TARGET = target("utf1664", int.class, boolean.class);
        private static final Method CONCRETE_TARGET = target("utf1665", int.class, boolean.class);
        private static int utf16Length;
        private static final boolean LITTLE = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;
        private static final MethodHandle ISO = find("encodeISOBytes", byte[].class, int.class,
                byte[].class, int.class, int.class);
        private static final MethodHandle ENCODE = find("encodeASCII", char[].class, int.class,
                byte[].class, int.class, int.class);
        private static final MethodHandle SCAN = find("countPositives", byte[].class, int.class, int.class);

        public static void main(String[] args) throws Throwable {
            for (int i = 0; i < 100; i++) {
                Asserts.assertEquals(test(), 759);
                utf1664(i, (i & 1) != 0);
                utf1665(i, (i & 1) != 0);
            }
            deoptEnabled = true;
            PEATestUtils.compileConfiguredTargetsAtLevel4();
            utf16Length = 65;
            utf1665(9, true);
            utf16Length = 64;
            utf1664(9, true);
            utf16Length = 0;
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

        public static int utf1664(int seed, boolean fail) throws Throwable {
            byte[] src = new byte[128];
            byte[] dst = new byte[64];
            int value = 'a' + (seed & 7);
            for (int i = 0; i < 64; i++) {
                src[2 * i + (LITTLE ? 0 : 1)] = (byte) value;
                dst[i] = 7;
            }
            if (fail) {
                src[64 + (LITTLE ? 0 : 1)] = 0;
                src[64 + (LITTLE ? 1 : 0)] = 1;
            }
            int prefix = (int) ISO.invokeExact(src, 0, dst, 0, 64);
            requestDeopt();
            int expectedPrefix = fail ? 32 : 64;
            if (prefix != expectedPrefix) throw new AssertionError("UTF16 prefix lost");
            int low = LITTLE ? 0 : 1;
            int high = LITTLE ? 1 : 0;
            int middle = fail ? 256 : value;
            if ((src[low] & 255) != value || src[high] != 0
                    || (src[64 + low] & 255) != (middle & 255)
                    || (src[64 + high] & 255) != middle >>> 8
                    || (src[126 + low] & 255) != value || src[126 + high] != 0
                    || (dst[0] & 255) != value || (dst[31] & 255) != value
                    || (dst[32] & 255) != (fail ? 7 : value)
                    || (dst[63] & 255) != (fail ? 7 : value)) {
                throw new AssertionError("UTF16 source or partial destination state lost");
            }
            return prefix + dst[63];
        }

        public static int utf1665(int seed, boolean fail) throws Throwable {
            byte[] src = new byte[130];
            byte[] dst = new byte[65];
            int value = 'a' + (seed & 7);
            for (int i = 0; i < 65; i++) {
                src[2 * i + (LITTLE ? 0 : 1)] = (byte) value;
                dst[i] = 7;
            }
            if (fail) {
                src[64 + (LITTLE ? 0 : 1)] = 0;
                src[64 + (LITTLE ? 1 : 0)] = 1;
            }
            int prefix = (int) ISO.invokeExact(src, 0, dst, 0, 65);
            requestDeopt();
            int expectedPrefix = fail ? 32 : 65;
            if (prefix != expectedPrefix) throw new AssertionError("UTF16 prefix lost");
            int low = LITTLE ? 0 : 1;
            int high = LITTLE ? 1 : 0;
            int middle = fail ? 256 : value;
            if ((src[low] & 255) != value || src[high] != 0
                    || (src[64 + low] & 255) != (middle & 255)
                    || (src[64 + high] & 255) != middle >>> 8
                    || (src[128 + low] & 255) != value || src[128 + high] != 0
                    || (dst[0] & 255) != value || (dst[31] & 255) != value
                    || (dst[32] & 255) != (fail ? 7 : value)
                    || (dst[64] & 255) != (fail ? 7 : value)) {
                throw new AssertionError("UTF16 source or partial destination state lost");
            }
            return prefix + dst[64];
        }

        private static void requestDeopt() {
            if (!deoptEnabled) return;
            WhiteBox.getWhiteBox().fullGC();
            var evidence = PEATestUtils.deoptimizeActiveFrame(utf16Length == 65 ? CONCRETE_TARGET : utf16Length == 64 ? UTF16_TARGET : TARGET, 2);
            Asserts.assertTrue(evidence.frameDeoptimized());
            System.out.println("ENCODING-GC-DEOPT:" + evidence);
        }

        private static Method target(String name, Class<?>... args) {
            try { return TestWrapper.class.getMethod(name, args); }
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
