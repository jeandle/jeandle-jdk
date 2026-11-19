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
 *
 */

/*
 * @test
 * @key randomness
 * @summary Preserve precision loss and saturation when folding integer-source round
 * @requires os.arch == "amd64" | os.arch == "x86_64" | os.arch == "aarch64"
 * @library /test/lib /
 * @run main/othervm compiler.jeandle.intrinsic.TestRoundIntegerSource
 */

package compiler.jeandle.intrinsic;

import compiler.jeandle.fileCheck.FileCheck;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import javax.xml.parsers.DocumentBuilderFactory;
import jdk.test.lib.Asserts;
import jdk.test.lib.process.ProcessTools;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public class TestRoundIntegerSource {
    public static void main(String[] args) throws Exception {
        Path dump = Files.createTempDirectory("round_integer_source");
        Path log = dump.resolve("compilation.xml");
        ProcessTools.executeCommand(ProcessTools.createLimitedTestJavaProcessBuilder(
                "-Xcomp", "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler",
                "-XX:+JeandleUseHotspotIntrinsics", "-XX:+UnlockDiagnosticVMOptions",
                "-XX:+LogCompilation", "-XX:LogFile=" + log,
                "-XX:+JeandleDumpIR", "-XX:JeandleDumpDirectory=" + dump,
                "-XX:CompileCommand=compileonly," + Kernel.class.getName() + "::kernel*",
                Kernel.class.getName()))
                .shouldHaveExitValue(0).shouldContain("Integer-source round checks passed");

        NodeList compiled = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(log.toFile()).getElementsByTagName("nmethod");
        for (String name : List.of("kernelFloat", "kernelDouble", "kernelWideFloat",
                                   "kernelUnsignedFloat", "kernelShared")) {
            boolean wide = name.equals("kernelDouble") || name.equals("kernelWideFloat");
            Method method = Kernel.class.getMethod(name, wide ? long.class : int.class);
            FileCheck raw = new FileCheck(dump.toString(), method, false);
            raw.checkPattern("define hotspotcc .*" + name + ".*\\.root");
            raw.checkPattern("call .*@jeandle\\.round\\.");
            FileCheck optimized = new FileCheck(dump.toString(), method, true);
            optimized.checkPattern("define hotspotcc .*" + name + ".*\\.root");
            optimized.checkPattern("[su]itofp i(?:32|64)");
            optimized.checkPattern("call i(?:32|64) @llvm\\.fptosi\\.sat\\.");
            optimized.checkNot("ashr");
            optimized.checkNot("@__llvm_deoptimize");
            String target = Kernel.class.getName() + " " + name + " ("
                    + (wide ? "J" : "I") + ")" + (name.equals("kernelDouble") ? "J" : "I");
            boolean found = false;
            for (int i = 0; i < compiled.getLength(); i++) {
                Element nmethod = (Element) compiled.item(i);
                if (target.equals(nmethod.getAttribute("method"))) {
                    Asserts.assertEQ(nmethod.getAttribute("compiler"), "jeandle", target);
                    Asserts.assertFalse(nmethod.hasAttribute("compile_kind"), target);
                    found = true;
                }
            }
            Asserts.assertTrue(found, "Missing compiled kernel: " + target);
        }
    }

    public static class Kernel {
        static double initializeMath = Math.abs(1.0);
        static volatile float converted;

        public static int kernelFloat(int x) { return Math.round((float) x); }
        public static long kernelDouble(long x) { return Math.round((double) x); }
        public static int kernelWideFloat(long x) { return Math.round((float) x); }
        public static int kernelUnsignedFloat(int x) { return Math.round((float) (x & 0xffffffffL)); }
        public static int kernelShared(int x) {
            float value = (float) x;
            converted = value;
            return Math.round(value);
        }

        // Only kernel* methods are compiled, so these references execute in the interpreter.
        private static int referenceFloat(int x) { return Math.round((float) x); }
        private static long referenceDouble(long x) { return Math.round((double) x); }
        private static int referenceWideFloat(long x) { return Math.round((float) x); }
        private static int referenceUnsignedFloat(int x) { return Math.round((float) (x & 0xffffffffL)); }

        private static void checkInt(int x) {
            Asserts.assertEQ(kernelFloat(x), referenceFloat(x));
            Asserts.assertEQ(kernelShared(x), referenceFloat(x));
            Asserts.assertEQ(Float.floatToRawIntBits(converted), Float.floatToRawIntBits((float) x));
            Asserts.assertEQ(kernelUnsignedFloat(x), referenceUnsignedFloat(x));
        }

        private static void checkLong(long x) {
            Asserts.assertEQ(kernelDouble(x), referenceDouble(x));
            Asserts.assertEQ(kernelWideFloat(x), referenceWideFloat(x));
        }

        public static void main(String[] args) {
            Asserts.assertEQ(kernelFloat(16777217), 16777216);
            Asserts.assertEQ(kernelFloat(-16777217), -16777216);
            Asserts.assertEQ(kernelFloat(Integer.MAX_VALUE - 1), Integer.MAX_VALUE);
            Asserts.assertEQ(kernelFloat(Integer.MIN_VALUE + 1), Integer.MIN_VALUE);
            Asserts.assertEQ(kernelDouble(9007199254740993L), 9007199254740992L);
            Asserts.assertEQ(kernelDouble(-9007199254740993L), -9007199254740992L);
            Asserts.assertEQ(kernelDouble(Long.MAX_VALUE - 1), Long.MAX_VALUE);
            Asserts.assertEQ(kernelDouble(Long.MIN_VALUE + 1), Long.MIN_VALUE);
            Asserts.assertEQ(kernelWideFloat(Long.MAX_VALUE), Integer.MAX_VALUE);
            Asserts.assertEQ(kernelWideFloat(Long.MIN_VALUE), Integer.MIN_VALUE);
            Asserts.assertEQ(kernelUnsignedFloat(-1), Integer.MAX_VALUE);
            for (int delta = 0; delta <= 1024; delta++) {
                checkInt(Integer.MIN_VALUE + delta);
                checkInt(Integer.MAX_VALUE - delta);
                checkLong(Long.MIN_VALUE + delta);
                checkLong(Long.MAX_VALUE - delta);
            }
            for (int bit = 0; bit < 63; bit++) {
                for (int delta = -32; delta <= 32; delta++) {
                    long value = (1L << bit) + delta;
                    checkLong(value);
                    checkLong(-value);
                    checkInt((int) value);
                    checkInt((int) -value);
                }
            }
            Random random = new Random(662131);
            for (int i = 0; i < 20000; i++) {
                checkInt(random.nextInt());
                checkLong(random.nextLong());
            }
            System.out.println("Integer-source round checks passed");
        }
    }
}
