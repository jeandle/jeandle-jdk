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
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 */

/*
 * @test
 * @summary Count positives short tails, early rejection, and Java range fallback
 * @library /test/lib
 * @build jdk.test.whitebox.WhiteBox
 * @compile --patch-module java.base=${test.src} java/lang/JeandleCharacterIntrinsicCaller.java
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm/timeout=240 -XX:-UseJeandleCompiler compiler.jeandle.intrinsic.TestCountPositives
 */
package compiler.jeandle.intrinsic;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import jdk.test.lib.Platform;
import jdk.test.lib.process.ProcessTools;
import jdk.test.whitebox.WhiteBox;

public class TestCountPositives {
    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            Method scan = Class.forName("java.lang.JeandleCharacterIntrinsicCaller")
                    .getMethod("countPositives", byte[].class, int.class, int.class);
            Method target = Class.forName("java.lang.StringCoding")
                    .getDeclaredMethod("countPositives", byte[].class, int.class, int.class);
            WhiteBox wb = WhiteBox.getWhiteBox();
            wb.clearMethodState(target);
            byte[] warm = new byte[65];
            for (int i = 0; i < (args[0].equals("cold") ? 1 : 80000); i++) {
                warm[0] = args[0].equals("first") ? (byte) -1 : (byte) (i & 1);
                scan.invoke(null, warm, 0, 65);
            }
            if (!wb.enqueueMethodForCompilation(scan, 4) || !wb.isMethodCompiled(scan)) {
                throw new AssertionError("Could not compile scan wrapper");
            }
            scan.invoke(null, warm, 0, 65);
            long checks = 0;
            // Each slice ends at the allocation boundary, at every short-tail
            // size and alignment. Negatives before the slice must be ignored.
            for (int n = 0; n <= 65; n++) {
                for (int off = 0; off < 32; off++) {
                    byte[] src = new byte[off + n];
                    for (int bad = 0; bad <= n; bad++) {
                        Arrays.fill(src, (byte) 127);
                        Arrays.fill(src, 0, off, (byte) -1);
                        if (bad < n) src[off + bad] = -128;
                        check(scan, src, off, n, bad);
                        checks++;
                    }
                }
            }
            check(scan, null, Integer.MIN_VALUE, 0, 0);
            check(scan, null, 0, -5, -5);
            check(scan, new byte[] {1, -1}, 1, Integer.MAX_VALUE, Integer.MAX_VALUE);
            check(scan, new byte[] {-1}, 0, 2, 0);
            System.out.println("COUNT POSITIVES PASSED checks=" + checks);
            return;
        }
        for (String profile : List.of("clean", "first", "cold", "no-profile")) {
            for (String mode : List.of("default", "sse", "scalar")) {
                if (!Platform.isX64() && !mode.equals("default")) continue;
                Path dumps = Files.createTempDirectory("count-positives-" + profile + "-" + mode);
                Path log = dumps.resolve("compilation.xml");
                var flags = new ArrayList<>(List.of(
                        "--patch-module=java.base=" + System.getProperty("test.class.path"),
                        "-Xbootclasspath/a:.", "-XX:+UnlockDiagnosticVMOptions", "-XX:+WhiteBoxAPI",
                        "-Xbatch", "-XX:-TieredCompilation", "-XX:CompileThreshold=100000",
                        "-XX:+UseJeandleCompiler",
                        "-XX:+LogCompilation", "-XX:LogFile=" + log,
                        "-XX:+JeandleDumpIR", "-XX:JeandleDumpDirectory=" + dumps,
                        "-XX:CompileCommand=compileonly,java.lang.JeandleCharacterIntrinsicCaller::countPositives"));
                if (mode.equals("sse")) flags.add("-XX:UseAVX=0");
                if (mode.equals("scalar")) flags.add("-XX:MaxVectorSize=8");
                if (profile.equals("no-profile")) flags.add("-XX:-JeandleUseProfile");
                flags.addAll(List.of(TestCountPositives.class.getName(), profile));
                var output = ProcessTools.executeCommand(ProcessTools.createLimitedTestJavaProcessBuilder(flags));
                Files.writeString(dumps.resolve("stdout.log"), output.getOutput());
                output.shouldHaveExitValue(0).shouldContain("COUNT POSITIVES PASSED");
                if (Files.readString(log).lines().noneMatch(line -> line.startsWith("<nmethod ")
                        && line.contains("compiler='jeandle'")
                        && line.contains("method='java.lang.JeandleCharacterIntrinsicCaller countPositives "))) {
                    throw new AssertionError("Missing Jeandle scan compilation: " + dumps);
                }
                boolean early = false;
                boolean stub = false;
                try (var files = Files.list(dumps)) {
                    for (Path file : files.filter(p -> p.toString().endsWith("_optimized.ll")
                            && p.getFileName().toString().startsWith("java_lang_JeandleCharacterIntrinsicCaller_countPositives_")).toList()) {
                        String ir = Files.readString(file);
                        int start = ir.indexOf("define hotspotcc");
                        String body = ir.substring(start, ir.indexOf("\n}", start));
                        if (body.contains("@count_positives_stub(")) {
                            stub = true;
                            early |= body.contains("load atomic i8") && body.contains("icmp slt i8");
                        }
                    }
                }
                boolean expectedEarly = !profile.equals("clean");
                if (!stub || early != expectedEarly) {
                    throw new AssertionError("Unexpected first-byte scan check for " + profile + ": " + dumps);
                }
                System.out.println(profile + "/" + mode + " evidence: " + dumps);
            }
        }
    }

    private static void check(Method method, byte[] src, int off, int n, int expected) throws Exception {
        int result = (int) method.invoke(null, src, off, n);
        if (result != expected) {
            throw new AssertionError("off=" + off + " n=" + n + " got=" + result + " expected=" + expected);
        }
    }
}
