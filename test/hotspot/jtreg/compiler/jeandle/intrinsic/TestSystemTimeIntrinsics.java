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
 * @summary Verify Jeandle System time calls retain their external-state effects
 * @library /test/lib /
 * @build jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler compiler.jeandle.intrinsic.TestSystemTimeIntrinsics
 */

package compiler.jeandle.intrinsic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;
import jdk.test.whitebox.WhiteBox;

public class TestSystemTimeIntrinsics {
    public static void main(String[] args) throws Exception {
        for (boolean nanos : new boolean[] {false, true}) {
            String id = nanos ? "_nanoTime" : "_currentTimeMillis";
            runCase(nanos, "enabled", true);
            runCase(nanos, "control_disabled", false, "-XX:ControlIntrinsic=-" + id);
            runCase(nanos, "inline_disabled", false, "-XX:-InlineNatives");
        }
    }

    private static void runCase(boolean nanos, String mode, boolean enabled,
                                String... options) throws Exception {
        Path dump = Files.createTempDirectory("system_time_" + nanos + "_" + mode);
        String single = nanos ? "readNanos" : "readMillis";
        String pair = nanos ? "twoNanosReads" : "twoMillisReads";
        List<String> command = new ArrayList<>(List.of(
                "-Xbatch", "-Xcomp", "-XX:-TieredCompilation",
                "-XX:+UseJeandleCompiler", "-XX:+UnlockDiagnosticVMOptions",
                "-XX:+WhiteBoxAPI", "-Xbootclasspath/a:.",
                "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dump,
                "-XX:CompileCommand=compileonly," + Cases.class.getName() + "::" + single,
                "-XX:CompileCommand=compileonly," + Cases.class.getName() + "::" + pair));
        command.addAll(List.of(options));
        command.add(Cases.class.getName());
        command.add(Boolean.toString(nanos));
        OutputAnalyzer output = ProcessTools.executeProcess(
                ProcessTools.createLimitedTestJavaProcessBuilder(command));
        output.shouldHaveExitValue(0).shouldContain("System time cases passed");
        String method = nanos ? "nanoTime" : "currentTimeMillis";
        String log = "Method `static jlong java.lang.System." + method
                + "()` is parsed as intrinsic";
        if (enabled) {
            output.shouldContain(log);
        } else {
            output.shouldNotContain(log);
        }
        String callee = nanos ? "os_javaTimeNanos" : "os_javaTimeMillis";
        checkCalls(dump, single, callee, enabled ? 1 : 0);
        checkCalls(dump, pair, callee, enabled ? 2 : 0);
    }

    private static void checkCalls(Path dump, String method, String callee,
                                   int expectedCalls) throws Exception {
        String prefix = Cases.class.getName().replace('.', '_') + "_" + method + "_";
        Path ir;
        try (Stream<Path> files = Files.list(dump)) {
            ir = files.filter(path -> path.getFileName().toString().startsWith(prefix))
                    .filter(path -> path.getFileName().toString().endsWith("_optimized.ll"))
                    .max(Comparator.comparing(path -> path.getFileName().toString()))
                    .orElseThrow(() -> new AssertionError("No optimized IR for " + method));
        }
        String text = Files.readString(ir);
        Map<String, String> attributes = new HashMap<>();
        Matcher groups = Pattern.compile("(?m)^attributes #(\\d+) = \\{([^\\r\\n]*)}")
                .matcher(text);
        while (groups.find()) {
            attributes.put(groups.group(1), groups.group(2));
        }
        // Direct routines are called through their address embedded as an
        // inttoptr constant. The module-level alias keeps the routine identity
        // in the dumped IR, so calls are matched through the alias address.
        Matcher alias = Pattern.compile("@" + Pattern.quote(callee)
                + " = alias ptr, inttoptr \\(i64 (\\d+) to ptr\\)").matcher(text);
        // A disabled intrinsic never creates the alias; "-1" then matches nothing.
        String address = alias.find() ? alias.group(1) : "-1";
        Pattern target = Pattern.compile("\\bcall\\b[^\\r\\n]*inttoptr \\(i64 "
                + address + " to ptr\\)\\(\\)([^\\r\\n]*)");
        Matcher calls = target.matcher(text);
        int count = 0;
        while (calls.find()) {
            count++;
            Matcher group = Pattern.compile("#(\\d+)").matcher(calls.group(1));
            Asserts.assertTrue(group.find(), "Missing time call attribute reference");
            String attrs = attributes.get(group.group(1));
            Asserts.assertNotNull(attrs, "Missing call attribute group");
            Asserts.assertTrue(attrs.contains("\"gc-leaf-function\""), "Time call must be GC leaf");
            Asserts.assertTrue(attrs.contains("nounwind"), "Time call must not throw");
            Matcher memory = Pattern.compile("\\bmemory\\(([^)]*)\\)").matcher(attrs);
            if (memory.find()) {
                Asserts.assertEquals(memory.group(1).trim(), "readwrite",
                        "Time call must retain unrestricted memory effects");
            }
            Asserts.assertFalse(attrs.contains("readnone") || attrs.contains("readonly"),
                    "Time call must not be treated as pure or readonly");
        }
        Asserts.assertEquals(count, expectedCalls, "Time calls in " + method);
    }

    public static class Cases {
        public static void main(String[] args) throws Exception {
            boolean nanos = Boolean.parseBoolean(args[0]);
            System.getProperty("java.version");
            if (nanos) {
                long previous = readNanos();
                for (int i = 0; i < 20_000; i++) {
                    long current = readNanos();
                    Asserts.assertGTE(current - previous, 0L);
                    previous = current;
                }
                Asserts.assertLTE(twoNanosReads(), 0L);
                long before = readNanos();
                Thread.sleep(10);
                Asserts.assertGT(readNanos() - before, 0L);
            } else {
                // The wall clock can be adjusted in either direction.
                for (int i = 0; i < 1_000; i++) {
                    readMillis();
                }
                twoMillisReads();
            }
            WhiteBox wb = WhiteBox.getWhiteBox();
            String single = nanos ? "readNanos" : "readMillis";
            String pair = nanos ? "twoNanosReads" : "twoMillisReads";
            Asserts.assertTrue(wb.isMethodCompiled(Cases.class.getDeclaredMethod(single)),
                    single + " must execute compiled code");
            Asserts.assertTrue(wb.isMethodCompiled(Cases.class.getDeclaredMethod(pair)),
                    pair + " must execute compiled code");
            System.out.println("System time cases passed");
        }

        private static long readMillis() {
            return System.currentTimeMillis();
        }

        private static long readNanos() {
            return System.nanoTime();
        }

        private static long twoMillisReads() {
            return System.currentTimeMillis() - System.currentTimeMillis();
        }

        private static long twoNanosReads() {
            return System.nanoTime() - System.nanoTime();
        }
    }
}
