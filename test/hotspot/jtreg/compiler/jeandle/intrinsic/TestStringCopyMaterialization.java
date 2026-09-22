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
 * @summary Materialize local arrays before String copy intrinsics, with PEA on and off
 * @requires (os.arch=="amd64" | os.arch=="x86_64" | os.arch=="aarch64") & os.family!="windows"
 * @library /test/lib /
 * @build jdk.test.whitebox.WhiteBox jdk.test.whitebox.code.NMethod compiler.jeandle.intrinsic.StringIntrinsicTestSupport
 * @compile --patch-module java.base=${test.src} java/lang/JeandleStringCodingEncodeCaller.java
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm compiler.jeandle.intrinsic.TestStringCopyMaterialization
 */

package compiler.jeandle.intrinsic;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

import static compiler.jeandle.intrinsic.StringIntrinsicTestSupport.*;

public class TestStringCopyMaterialization {
    public static void main(String[] args) throws Exception {
        run(true);
        run(false);
    }

    private static void run(boolean pea) throws Exception {
        String dump = Files.createTempDirectory(Path.of(System.getProperty("user.dir")),
                "string_materialization_" + (pea ? "on" : "off")).toString();
        List<String> command = List.of(
                "-Xbatch", "-XX:-TieredCompilation", "-XX:CompileThreshold=100",
                "-XX:+UseJeandleCompiler", pea ? "-XX:+JeandleDoPEA" : "-XX:-JeandleDoPEA",
                "-XX:JeandleLLVMOptions=-jeandle-dump-pea-ir=FreshArrays",
                "-Xbootclasspath/a:.", "-XX:+UnlockDiagnosticVMOptions", "-XX:+WhiteBoxAPI",
                "--patch-module=java.base=" + System.getProperty("test.class.path"),
                "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR", "-XX:JeandleDumpDirectory=" + dump,
                "-XX:CompileCommand=compileonly,java.lang.JeandleStringCodingEncodeCaller::*FreshArrays",
                Worker.class.getName(), dump);
        OutputAnalyzer output = ProcessTools.executeCommand(
                ProcessTools.createLimitedTestJavaProcessBuilder(command));
        output.shouldHaveExitValue(0).shouldContain("TestStringCopyMaterialization PASSED");
        if (!pea) {
            output.shouldNotContain("PEA-DUMP");
        }
        for (String method : List.of("compressFreshArrays", "inflateFreshArrays")) {
            assertMaterializationMarker(dump, method, true);
            if (pea) {
                assertPEAMaterialization(dump,
                        "java.lang.JeandleStringCodingEncodeCaller::" + method, output.getStderr());
            }
        }
    }

    public static class Worker {
        public static void main(String[] args) throws Exception {
            Class<?> caller = Class.forName("java.lang.JeandleStringCodingEncodeCaller");
            Method compress = caller.getMethod("compressFreshArrays", int.class);
            Method inflate = caller.getMethod("inflateFreshArrays", int.class);
            for (int seed = 0; seed < 2_000; seed++) {
                check(compress, seed, true);
                check(inflate, seed, false);
            }
            int[] ids = beginChecks(args[0], compress, inflate);
            for (int seed : new int[] {0, 1, 31, 63, 127, 128, 255, -1,
                                       Integer.MIN_VALUE, Integer.MAX_VALUE}) {
                check(compress, seed, true);
                check(inflate, seed, false);
            }
            endChecks(ids, compress, inflate);
            System.out.println("TestStringCopyMaterialization PASSED");
        }

        private static void check(Method method, int seed, boolean compress) throws Exception {
            int expected = compress ? 32 : 0;
            for (int i = 0; i < 48; i++) {
                int value = 0;
                if (i == 5) {
                    value = seed & (compress ? 0x7f : 0xff);
                } else if (i == 36) {
                    value = (seed + 31) & (compress ? 0x7f : 0xff);
                } else if (i == 0 || i == 47) {
                    value = compress ? 0x5a : 0x3456;
                }
                expected = 31 * expected + value;
            }
            Asserts.assertEquals(((Integer) method.invoke(null, seed)).intValue(), expected,
                    method.getName() + " seed=" + seed);
        }
    }
}
