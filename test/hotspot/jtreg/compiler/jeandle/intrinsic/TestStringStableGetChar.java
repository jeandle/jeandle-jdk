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
 * @summary Stable UTF16 byte characters fold in LLVM while mutable word reads stay live
 * @requires vm.debug
 * @library /test/lib /
 * @modules java.base/jdk.internal.vm.annotation
 * @build jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @compile --patch-module java.base=${test.src} java/lang/JeandleStringIntrinsicCaller.java
 * @compile --patch-module java.base=${test.classes} TestStringStableGetChar.java
 * @run main/othervm/timeout=240 -XX:-UseJeandleCompiler compiler.jeandle.intrinsic.TestStringStableGetChar
 */
package compiler.jeandle.intrinsic;

import java.lang.JeandleStringIntrinsicCaller;
import java.lang.reflect.Method;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import compiler.jeandle.pea.PEATestUtils;
import jdk.internal.vm.annotation.Stable;
import jdk.test.lib.Asserts;
import jdk.test.lib.process.ProcessTools;

public class TestStringStableGetChar {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            Path patch = Files.createTempDirectory("string-getchar-patch");
            Path target = patch.resolve("java/lang/JeandleStringIntrinsicCaller.class");
            Files.createDirectories(target.getParent());
            try (var input = TestStringStableGetChar.class.getClassLoader()
                    .getResourceAsStream("java/lang/JeandleStringIntrinsicCaller.class")) {
                if (input == null) throw new AssertionError("Missing compiled String helper");
                Files.copy(input, target);
            }
            ProcessTools.executeTestJava("--patch-module=java.base=" + patch,
                    "--add-exports=java.base/jdk.internal.vm.annotation=ALL-UNNAMED",
                    "-Dtest.jdk=" + System.getProperty("test.jdk"),
                    "-Dtest.class.path=" + System.getProperty("test.class.path"),
                    "-Dtest.classes=" + System.getProperty("test.classes"),
                    "-XX:-UseJeandleCompiler", TestStringStableGetChar.class.getName(), patch.toString())
                    .shouldHaveExitValue(0).shouldContain("String stable getChar PASSED");
            return;
        }
        Method stable = Wrapper.class.getMethod("stable");
        Method mutable = Wrapper.class.getMethod("mutable");
        Method dynamic = Wrapper.class.getMethod("dynamic", byte[].class, int.class);
        Method late = Wrapper.class.getMethod("late");
        Method virtualDefault = Wrapper.class.getMethod("virtualDefault");
        Method virtualStored = Wrapper.class.getMethod("virtualStored", char.class);
        Method defaultBridge = JeandleStringIntrinsicCaller.class.getMethod("virtualDefaultGetCharStringU");
        Method storedBridge = JeandleStringIntrinsicCaller.class.getMethod("virtualStoredGetCharStringU", char.class);
        Method bridge = JeandleStringIntrinsicCaller.class.getMethod("getCharStringU", byte[].class, int.class);
        Method lateBridge = JeandleStringIntrinsicCaller.class.getMethod("lateGetCharStringU",
                byte[][].class, byte[].class, int.class);
        try (var run = PEATestUtils.shapeRun(Wrapper.class.getName(), stable, mutable, dynamic, late, virtualDefault, virtualStored)
                .inline(bridge).inline(lateBridge).inline(defaultBridge).inline(storedBridge).extraLLVMOptions("-debug-only=constant-field-folding").extraFlags("--patch-module=java.base=" + args[0],
                    "-Xbootclasspath/a:.:" + System.getProperty("test.class.path"),
                    "--add-exports=java.base/jdk.internal.vm.annotation=ALL-UNNAMED")
                .keepDumps().run()) {
            // Check the early semantic operation separately from the final material load.
            Asserts.assertTrue(run.report(dynamic).round0Before().lines().stream()
                    .anyMatch(line -> line.contains("@jeandle.string_getchar(")),
                    "The executed dynamic root reaches the getChar intrinsic");
            String stableBase = PEATestUtils.MethodId.of(stable).llvmFunctionName();
            Pattern fold = Pattern.compile("CFF: StringUTF16.getChar in " + Pattern.quote(stableBase)
                    + "(?:\\.[0-9]+)?\\.root oop=-?[0-9]+ offset=16 value=" + expected());
            Asserts.assertTrue(run.output().getStderr().lines().anyMatch(line -> fold.matcher(line).matches()),
                    "The same compiled stable root folds the getChar operation");
            Asserts.assertTrue(run.finalIR(stable).lines().contains("ret i32 " + expected()),
                    "VM-authorized stable bytes fold to a native-endian character");
            Asserts.assertTrue(run.finalIR(late).lines().contains("ret i32 " + (2 * expected())),
                    "Late stable identity survives CSE and folds the complete character pair");
            String lateBase = PEATestUtils.MethodId.of(late).llvmFunctionName();
            Pattern lateFold = Pattern.compile("CFF: StringUTF16.getChar in " + Pattern.quote(lateBase)
                    + "(?:\\.[0-9]+)?\\.root oop=-?[0-9]+ offset=16 value=" + expected());
            Asserts.assertTrue(run.output().getStderr().lines()
                    .anyMatch(line -> lateFold.matcher(line).matches()),
                    "The executed late root uses VM-authorized getChar folding");
            for (Method method : new Method[]{virtualDefault, virtualStored}) {
                String ir = String.join("\n", run.finalIR(method).lines());
                Asserts.assertFalse(ir.contains("@jeandle.new_array") || ir.contains("@new_array")
                        || ir.contains("@jeandle.string_getchar") || ir.contains("load atomic i16"),
                        "Virtual byte arrays retain scalar word access: " + method.getName());
                Asserts.assertTrue(run.output().getStdout().contains("PEA-COMPILED:"
                        + PEATestUtils.MethodId.of(method).llvmFunctionName() + ".root:level=4"),
                        "Virtual getChar root reached level 4 before the child value assertions");
            }
            for (Method method : new Method[]{mutable, dynamic}) {
                Asserts.assertEquals(run.finalIR(method).lines().stream()
                        .filter(line -> line.contains("load atomic i16")).count(), 1L,
                        "Material getChar retains one atomic word read: " + method.getName());
            }
        }
        System.out.println("String stable getChar PASSED");
    }

    private static int expected() {
        return ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? 0xa512 : 0x12a5;
    }

    public static class Wrapper {
        @Stable private static final byte[] STABLE = {0x12, (byte) 0xa5};
        private static final byte[] MUTABLE = {0x12, (byte) 0xa5};

        public static void main(String[] args) throws Exception {
            Asserts.assertNull(Wrapper.class.getClassLoader(), "Stable array needs a trusted loader");
            stable();
            late();
            mutable();
            dynamic(MUTABLE, 0);
            virtualDefault();
            virtualStored((char) 0xa512);
            PEATestUtils.compileConfiguredTargetsAtLevel4();
            Asserts.assertEquals(virtualDefault(), 0);
            for (char value : new char[]{0, 1, 255, 256, 0xa512, 0xffff}) {
                Asserts.assertEquals(virtualStored(value), (int) value);
            }
            Asserts.assertEquals(stable(), expected());
            Asserts.assertEquals(late(), 2 * expected());
            Asserts.assertEquals(mutable(), expected());
            Asserts.assertEquals(dynamic(MUTABLE, 0), expected());
            MUTABLE[0] = 0x31;
            MUTABLE[1] = 0x27;
            int changed = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? 0x2731 : 0x3127;
            Asserts.assertEquals(mutable(), changed);
            Asserts.assertEquals(dynamic(MUTABLE, 0), changed);
            System.out.println("PEA-RESULT:" + changed);
        }

        public static int late() {
            return JeandleStringIntrinsicCaller.lateGetCharStringU(new byte[1][], STABLE, 0);
        }

        public static int virtualDefault() {
            return JeandleStringIntrinsicCaller.virtualDefaultGetCharStringU();
        }

        public static int virtualStored(char value) {
            return JeandleStringIntrinsicCaller.virtualStoredGetCharStringU(value);
        }

        public static int stable() { return JeandleStringIntrinsicCaller.getCharStringU(STABLE, 0); }
        public static int mutable() { return JeandleStringIntrinsicCaller.getCharStringU(MUTABLE, 0); }
        public static int dynamic(byte[] src, int index) { return JeandleStringIntrinsicCaller.getCharStringU(src, index); }
    }
}
