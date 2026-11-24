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
 * @summary Local 17- and 64-element encoding retains scalar replacement and partial-write semantics
 * @library /test/lib /
 * @modules java.base/jdk.internal.misc
 * @build jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @compile --patch-module java.base=${test.src}/../intrinsic ../intrinsic/java/lang/JeandleCharacterIntrinsicCaller.java
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller -jar character-bridge.jar java.lang.JeandleCharacterIntrinsicCaller
 * @run main/othervm --patch-module=java.base=character-bridge.jar -XX:-UseJeandleCompiler compiler.jeandle.pea.TestCharacterLocalEncoding
 */
package compiler.jeandle.pea;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.nio.ByteOrder;
import java.util.List;
import jdk.test.lib.Asserts;

public class TestCharacterLocalEncoding {
    public static void main(String[] args) throws Exception {
        Method chars = TestWrapper.class.getMethod("chars17", int.class, boolean.class);
        Method utf16 = TestWrapper.class.getMethod("utf1617", int.class, boolean.class);
        Method utf1664 = TestWrapper.class.getMethod("utf1664", int.class, boolean.class);
        Method[] targets = {chars, utf16, utf1664};
        String wrapper = TestWrapper.class.getName();
        String patch = "--patch-module=java.base=character-bridge.jar";
        Method bridge = Class.forName("java.lang.JeandleCharacterIntrinsicCaller")
                .getMethod("encodeISOBytes", byte[].class, int.class, byte[].class,
                        int.class, int.class);
        PEATestUtils.behaviorRun(wrapper, targets).inline(bridge).extraFlags(patch).runPEAOnOffEquivalent();
        try (var run = PEATestUtils.shapeRun(wrapper, targets).inline(bridge).extraFlags(patch).keepDumps().run()) {
            for (Method target : targets) {
                var report = run.report(target);
                Asserts.assertEquals(report.round0Before().allocationBCIs().size(), 2,
                        target + ": source and destination reach PEA");
                Asserts.assertTrue(report.round0Before().lineCount("store atomic") > 0,
                        target + ": runtime contents reach PEA");
                Asserts.assertEquals(report.finalAfter().allocationBCIs(), List.of(),
                        target + ": both local arrays are scalar replaced");
                Asserts.assertEquals(run.finalIR(target).loweredAllocCount(), 0,
                        target + ": no allocation after lowering");
                run.finalIR(target).assertAbsent("encode_ascii_array_stub");
                run.finalIR(target).assertAbsent("encode_iso_array_stub");
                System.out.println("LOCAL-ENCODING-SCALAR:" + target.getName()
                        + ":before=2:after=0:lowered=0");
            }
            System.out.println("LOCAL-ENCODING-DUMPS:" + run.dumpDir());
        }
    }

    public static class TestWrapper {
        private static final boolean LITTLE = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;
        private static final MethodHandle ASCII = find("encodeASCII", char[].class);
        private static final MethodHandle ISO_BYTES = find("encodeISOBytes", byte[].class);

        public static void main(String[] args) throws Throwable {
            for (int i = 0; i < 100; i++) {
                check(i, false);
                check(i, true);
            }
            PEATestUtils.compileConfiguredTargetsAtLevel4();
            int digest = 0;
            for (int i = 0; i < 1024; i++) {
                digest = 31 * digest + check(i, false);
                digest = 31 * digest + check(i, true);
            }
            System.out.println("PEA-RESULT:local-encoding-" + digest);
        }

        private static int check(int seed, boolean fail) throws Throwable {
            int expected = reference(seed, fail);
            int chars = chars17(seed, fail);
            int utf16 = utf1617(seed, fail);
            Asserts.assertEquals(utf1664(seed, fail), reference64(seed, fail),
                    "64-character UTF16 symbolic prefix and suffix");
            Asserts.assertEquals(chars, expected, "char[] destination including untouched suffix");
            Asserts.assertEquals(utf16, expected, "UTF16 destination including untouched suffix");
            return chars ^ Integer.rotateLeft(utf16, 7);
        }

        public static int chars17(int seed, boolean fail) throws Throwable {
            char[] src = new char[17];
            byte[] dst = new byte[17];
            char value = (char) ('a' + (seed & 7));
            for (int i = 0; i < 17; i++) {
                src[i] = value;
                dst[i] = 7;
            }
            if (fail) src[8] = '\u0100';
            int prefix = (int) ASCII.invokeExact(src, 0, dst, 0, 17);
            int result = prefix;
            for (int i = 0; i < dst.length; i++) result = 31 * result + dst[i];
            return result;
        }

        public static int utf1617(int seed, boolean fail) throws Throwable {
            byte[] src = new byte[34];
            byte[] dst = new byte[17];
            int value = 'a' + (seed & 7);
            for (int i = 0; i < 17; i++) {
                src[2 * i] = (byte) (value >>> (LITTLE ? 0 : 8));
                src[2 * i + 1] = (byte) (value >>> (LITTLE ? 8 : 0));
                dst[i] = 7;
            }
            if (fail) {
                src[16] = (byte) (0x100 >>> (LITTLE ? 0 : 8));
                src[17] = (byte) (0x100 >>> (LITTLE ? 8 : 0));
            }
            int prefix = (int) ISO_BYTES.invokeExact(src, 0, dst, 0, 17);
            int result = prefix;
            for (int i = 0; i < dst.length; i++) result = 31 * result + dst[i];
            return result;
        }

        public static int utf1664(int seed, boolean fail) throws Throwable {
            byte[] src = new byte[128];
            byte[] dst = new byte[64];
            int value = 'a' + (seed & 7);
            for (int i = 0; i < 64; i++) {
                src[2 * i] = (byte) (value >>> (LITTLE ? 0 : 8));
                src[2 * i + 1] = (byte) (value >>> (LITTLE ? 8 : 0));
                dst[i] = 7;
            }
            if (fail) {
                src[64] = (byte) (0x100 >>> (LITTLE ? 0 : 8));
                src[65] = (byte) (0x100 >>> (LITTLE ? 8 : 0));
            }
            int prefix = (int) ISO_BYTES.invokeExact(src, 0, dst, 0, 64);
            int result = prefix;
            for (int i = 0; i < dst.length; i++) result = 31 * result + dst[i];
            return result;
        }

        private static int reference(int seed, boolean fail) {
            int prefix = fail ? 8 : 17;
            int result = prefix;
            for (int i = 0; i < 17; i++) {
                int cell = i < prefix ? 'a' + (seed & 7) : 7;
                result = 31 * result + cell;
            }
            return result;
        }

        private static int reference64(int seed, boolean fail) {
            int prefix = fail ? 32 : 64;
            int result = prefix;
            for (int i = 0; i < 64; i++) {
                int cell = i < prefix ? 'a' + (seed & 7) : 7;
                result = 31 * result + cell;
            }
            return result;
        }

        private static MethodHandle find(String name, Class<?> source) {
            try {
                return MethodHandles.publicLookup().findStatic(
                        Class.forName("java.lang.JeandleCharacterIntrinsicCaller"), name,
                        MethodType.methodType(int.class, source, int.class, byte[].class,
                                int.class, int.class));
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }
    }
}
