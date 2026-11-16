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
 * @summary Packed array copy groups remain virtual across active-frame deoptimization
 * @library /test/lib /
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @compile --patch-module java.base=${test.src} java/lang/JeandlePEACopyPackedDeopt.java
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler compiler.jeandle.pea.TestPEACopyPackedDeopt
 * @run main/othervm -XX:-UseJeandleCompiler -XX:-UseCompressedOops
 *      -XX:-UseCompressedClassPointers compiler.jeandle.pea.TestPEACopyPackedDeopt
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;
import jdk.test.whitebox.WhiteBox;

public class TestPEACopyPackedDeopt {
    public static void main(String[] args) throws Exception {
        Path patch = Files.createTempDirectory(Path.of("."), "packed-deopt-patch");
        Path helper = Path.of("java", "lang", "JeandlePEACopyPackedDeopt.class");
        Files.createDirectories(patch.resolve(helper).getParent());
        Files.copy(Path.of(System.getProperty("test.classes")).resolve(helper),
                patch.resolve(helper));
        for (String collector : List.of("-XX:+UseG1GC", "-XX:+UseSerialGC")) {
            run(patch, collector, true, false);
            run(patch, collector, false, false);
            run(patch, collector, true, true);
        }
    }

    private static void run(Path patch, String collector, boolean fold, boolean shape)
            throws Exception {
        ArrayList<String> options = new ArrayList<>(List.of(
                "--patch-module=java.base=" + patch.toAbsolutePath(),
                "-Xbootclasspath/a:.", "-XX:+UnlockDiagnosticVMOptions", "-XX:+WhiteBoxAPI",
                "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler",
                "-XX:+JeandleDoPEA", collector, "-Xms64m", "-Xmx64m",
                "-XX:+VerifyBeforeGC", "-XX:+VerifyAfterGC",
                "-XX:CompileCommand=compileonly,java.lang.JeandlePEACopyPackedDeopt::testPackedDeopt",
                "-XX:JeandleLLVMOptions=-jeandle-pea-fold-copy=" + (fold ? 1 : 0)
                        + (shape ? " -jeandle-dump-pea-stats"
                                + " -jeandle-dump-pea-ir=JeandlePEACopyPackedDeopt" : "")));
        if (!PEATestUtils.useCompressedOops()) {
            options.add("-XX:-UseCompressedOops");
        }
        if (!PEATestUtils.useCompressedClassPointers()) {
            options.add("-XX:-UseCompressedClassPointers");
        }
        options.add(Child.class.getName());
        OutputAnalyzer output = ProcessTools.executeCommand(
                ProcessTools.createLimitedTestJavaProcessBuilder(options));
        output.shouldHaveExitValue(0).shouldContain("PACKED-DEOPT-PASSED");
        output.shouldContain("PACKED-DEOPT:level=4:marked=1:frame=true");
        if (shape) {
            output.shouldContain("store atomic i16").shouldContain("@jeandle.copy");
            output.shouldMatch("PEA stats @java_lang_JeandlePEACopyPackedDeopt_testPackedDeopt"
                    + "[^\\r\\n]*: NeverEscapes=3 PartiallyEscapes=0 AlwaysEscapes=0 CopyFolds=2");
            boolean inAfter = false;
            boolean foundAfter = false;
            for (String line : output.getStderr().split("\\R")) {
                if (line.startsWith(";; PEA-DUMP ")) {
                    inAfter = line.startsWith(";; PEA-DUMP after ")
                            && line.contains("java_lang_JeandlePEACopyPackedDeopt_testPackedDeopt");
                    foundAfter |= inAfter;
                } else if (inAfter) {
                    Asserts.assertFalse(line.contains("@jeandle.new_array(")
                                    || line.contains("@jeandle.copy("),
                            "packed deopt state must not retain allocations or copies: " + line);
                    if (line.equals("}")) {
                        inAfter = false;
                    }
                }
            }
            Asserts.assertTrue(foundAfter, "target after-transform IR was recorded");
        }
    }

    public static final class Child {
        private static Method target;

        public static void main(String[] args) throws Exception {
            Class.forName("java.lang.StringUTF16", true, null);
            target = Class.forName("java.lang.JeandlePEACopyPackedDeopt")
                    .getMethod("testPackedDeopt", Runnable.class, int.class);
            Runnable hook = new DeoptHook();
            for (int value : new int[]{0x2211, 0xfedc, 0x8001}) {
                PEATestUtils.enqueueAndAwaitLevel4(target);
                PEATestUtils.confirmLevel4(target);
                long result = (Long) target.invoke(null, hook, value);
                int a = value & 0xffff;
                int b = (value ^ 0xa55a) & 0xffff;
                long expected = ((long) a << 48) | ((long) b << 32) | ((long) a << 16) | b;
                Asserts.assertEquals(result, expected,
                        "source and shifted destination packed bytes after deopt");
            }
            System.out.println("PACKED-DEOPT-PASSED");
        }

        private static final class DeoptHook implements Runnable {
            @Override
            public void run() {
                PEATestUtils.ActiveFrameDeoptEvidence evidence =
                        PEATestUtils.deoptimizeActiveFrame(target, 2);
                Asserts.assertTrue(evidence.frameDeoptimized(), "packed target frame deoptimized");
                WhiteBox.getWhiteBox().youngGC();
                System.out.println("PACKED-DEOPT:level=" + evidence.compilationLevel()
                        + ":marked=" + evidence.markedNMethods()
                        + ":frame=" + evidence.frameDeoptimized());
            }
        }
    }
}
