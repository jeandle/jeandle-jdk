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
 * @summary Filter Jeandle IR dumps by the Java root method
 * @bug 680
 * @library /test/lib /
 * @run driver compiler.jeandle.TestJeandleDumpIRFilter
 */

package compiler.jeandle;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestJeandleDumpIRFilter {
    private static final String CLASS_NAME = TestJeandleDumpIRFilter.class.getName();
    private static final String CLASS_PATH = CLASS_NAME.replace('.', '/');
    private static final String PASS = "JEANDLE_DUMP_IR_FILTER_PASS";
    private static final String[] METHODS = { "hot", "hotPrefix", "cold", "Hot" };

    private record RunResult(OutputAnalyzer output, Path dump) { }

    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            runWorkload();
            return;
        }

        expectDumpSet(runChild(null, true), 2, 1, 1, 1);
        expectDumpSet(runChild("", true), 2, 1, 1, 1);

        // An exact method name without a signature includes both overloads.
        expectDumpSet(runChild(CLASS_NAME + "::hot", true), 2, 0, 0, 0);
        expectDumpSet(runChild(CLASS_NAME + "::hot*", true), 2, 1, 0, 0);
        expectDumpSet(runChild("*DumpIRF*::*hot*", true), 2, 1, 0, 0);
        expectDumpSet(runChild("*::*", true), 2, 1, 1, 1);
        expectDumpSet(runChild(CLASS_NAME + "::Hot", true), 0, 0, 0, 1);

        RunResult intOverload = runChild(CLASS_PATH + ".hot(I)I", true);
        expectDumpSet(intOverload, 1, 0, 0, 0);
        checkOverload(intOverload, "(I)I", "(J)J");
        RunResult longOverload = runChild(CLASS_NAME + "::hot(J)J", true);
        expectDumpSet(longOverload, 1, 0, 0, 0);
        checkOverload(longOverload, "(J)J", "(I)I");

        expectDumpSet(runChild(CLASS_NAME + "::missing", true), 0, 0, 0, 0);
        expectDumpSet(runChild(CLASS_NAME + "::hot", false), 0, 0, 0, 0);

        assertInvalid(runChild(CLASS_NAME + "::ho*t", true), "Embedded * not allowed");
        assertInvalid(runChild(CLASS_PATH + "::hot", true), "uses '/' together with '::'");
        assertInvalid(runChild(CLASS_NAME + "::hot trailing", true),
                      "Unrecognized trailing text after method pattern");
        assertInvalid(runChild(CLASS_NAME + "::", true), "Could not parse method pattern");

        RunResult stubs = runChild(CLASS_NAME + "::missing", false,
                                   "-XX:+JeandleDumpRuntimeStubs");
        expectNoTargetDumps(stubs);
        Asserts.assertTrue(allIrFiles(stubs.dump()).size() > 0,
                           "runtime stub IR must not be filtered");

        RunResult objects = runChild(CLASS_NAME + "::missing", true,
                                     "-XX:+JeandleDumpObjects");
        expectDumpSet(objects, 0, 0, 0, 0);
        try (var paths = Files.list(objects.dump())) {
            long objectDumps = paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".o"))
                    .count();
            Asserts.assertTrue(objectDumps > 0,
                               "IR filtering must not suppress object dumps");
        }
    }

    private static RunResult runChild(String filter, boolean dumpIR,
                                      String... extraFlags) throws Exception {
        Path dump = Files.createTempDirectory("jeandle_dump_ir_filter_");
        List<String> command = new ArrayList<>(List.of(
                "-Xcomp", "-Xbatch", "-XX:-TieredCompilation",
                "-XX:+UnlockDiagnosticVMOptions",
                "-XX:+UseJeandleCompiler", "-XX:+CIPrintCompilerName",
                "-XX:+PrintCompilation",
                "-XX:CompileCommand=compileonly," + CLASS_NAME + "::hot*",
                "-XX:CompileCommand=compileonly," + CLASS_NAME + "::cold",
                "-XX:CompileCommand=compileonly," + CLASS_NAME + "::Hot",
                "-XX:JeandleDumpDirectory=" + dump));
        if (dumpIR) {
            command.add("-XX:+JeandleDumpIR");
        }
        if (filter != null) {
            command.add("-XX:JeandleDumpIRFilter=" + filter);
        }
        command.addAll(List.of(extraFlags));
        command.add(CLASS_NAME);
        command.add("child");

        OutputAnalyzer output = ProcessTools.executeProcess(
                ProcessTools.createLimitedTestJavaProcessBuilder(command));
        output.shouldHaveExitValue(0).shouldContain(PASS);
        checkCompilations(output);
        return new RunResult(output, dump);
    }

    private static void checkCompilations(OutputAnalyzer output) {
        for (String method : new String[] { "hotPrefix", "cold", "Hot" }) {
            output.shouldMatch("Jeandle:.*" + Pattern.quote(CLASS_NAME + "::" + method)
                               + " \\([0-9]+ bytes\\)");
        }
        long hotCompilations = output.getOutput().lines()
                .filter(line -> line.contains("Jeandle:")
                                && line.contains(CLASS_NAME + "::hot ("))
                .count();
        Asserts.assertEquals(hotCompilations, 2L,
                             "both hot overloads must be compiled, not just executed");
    }

    private static void expectDumpSet(RunResult result, int hot, int hotPrefix,
                                      int cold, int upperHot) throws Exception {
        int[] expected = { hot, hotPrefix, cold, upperHot };
        for (int i = 0; i < METHODS.length; i++) {
            Asserts.assertEquals(irFiles(result.dump(), METHODS[i], false).size(), expected[i],
                                 METHODS[i] + " unoptimized IR dump count");
            Asserts.assertEquals(irFiles(result.dump(), METHODS[i], true).size(), expected[i],
                                 METHODS[i] + " optimized IR dump count");
        }
        int expectedTotal = 2 * (hot + hotPrefix + cold + upperHot);
        Asserts.assertEquals(allIrFiles(result.dump()).size(), expectedTotal,
                             "unexpected IR files in " + result.dump());
    }

    private static void expectNoTargetDumps(RunResult result) throws Exception {
        for (String method : METHODS) {
            Asserts.assertTrue(irFiles(result.dump(), method, false).isEmpty(),
                               method + " must not have unoptimized IR");
            Asserts.assertTrue(irFiles(result.dump(), method, true).isEmpty(),
                               method + " must not have optimized IR");
        }
    }

    private static List<Path> irFiles(Path dump, String method, boolean optimized) throws Exception {
        String prefix = CLASS_NAME.replace('.', '_') + "_" + method + "_";
        try (var paths = Files.list(dump)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.startsWith(prefix)
                                && (optimized ? name.endsWith("_optimized.ll")
                                              : name.endsWith(".ll") && !name.endsWith("_optimized.ll"));
                    })
                    .sorted()
                    .toList();
        }
    }

    private static List<Path> allIrFiles(Path dump) throws Exception {
        try (var paths = Files.list(dump)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".ll"))
                    .sorted()
                    .toList();
        }
    }

    private static void checkOverload(RunResult result, String expected, String excluded)
            throws Exception {
        List<Path> files = new ArrayList<>(irFiles(result.dump(), "hot", false));
        files.addAll(irFiles(result.dump(), "hot", true));
        for (Path file : files) {
            String ir = Files.readString(file);
            Asserts.assertTrue(ir.contains("hot" + expected + "."),
                               "expected root signature in " + file);
            Asserts.assertFalse(ir.contains("hot" + excluded + "."),
                                "excluded root signature in " + file);
        }
    }

    private static void assertInvalid(RunResult result, String reason) throws Exception {
        result.output().shouldContain("JeandleDumpIRFilter").shouldContain(reason);
        long diagnostics = result.output().getOutput().lines()
                .filter(line -> line.contains("Invalid JeandleDumpIRFilter pattern"))
                .count();
        Asserts.assertEquals(diagnostics, 1L, "invalid pattern must be diagnosed once");
        expectDumpSet(result, 0, 0, 0, 0);
    }

    private static void runWorkload() throws Exception {
        long result = hot(1);
        // Dump filenames omit signatures, so keep the overload timestamps distinct.
        Thread.sleep(5);
        result += hot(2L) + hotPrefix(3) + cold(4) + Hot(5);
        if (result != 16L) {
            throw new AssertionError("unexpected result: " + result);
        }
        System.out.println(PASS);
    }

    private static int hot(int value) {
        return value + 1;
    }

    private static long hot(long value) {
        return value + 1;
    }

    private static int hotPrefix(int value) {
        return value + 1;
    }

    private static int cold(int value) {
        return value - 3;
    }

    private static int Hot(int value) {
        return value + 1;
    }
}
