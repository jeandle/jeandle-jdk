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
 * @summary Verify OSR preserves primitive array types for arraycopy specialization.
 * @library /test/lib /
 * @run driver TestArrayCopyOSR
 */

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestArrayCopyOSR {
    private static final String OSR_ROOT = "__jeandle_osr.TestArrayCopyOSR_copyLoop";
    private static final String CHAR_ARRAY_COPY_STUB =
            "StubRoutines_arrayof_jshort_disjoint_arraycopy";
    private static final String GENERIC_ARRAY_COPY_STUB =
            "StubRoutines_generic_arraycopy";

    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            runChild();
            return;
        }

        Path dumpDirectory = Files.createTempDirectory("jeandle_arraycopy_osr_ir");
        List<String> command = new ArrayList<>(List.of(
                "-Xbatch",
                "-XX:-TieredCompilation",
                "-XX:+UseOnStackReplacement",
                "-XX:+UseJeandleCompiler",
                "-XX:CompileThreshold=1000",
                "-XX:CompileCommand=compileonly,TestArrayCopyOSR::copyLoop",
                "-XX:+UnlockDiagnosticVMOptions",
                "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dumpDirectory,
                "TestArrayCopyOSR",
                "child"));

        OutputAnalyzer output = ProcessTools.executeProcess(
                ProcessTools.createLimitedTestJavaProcessBuilder(command));
        output.shouldHaveExitValue(0).shouldContain("ARRAYCOPY_OSR_PASS");

        List<Path> osrDumps = findOsrOptimizedDumps(dumpDirectory);
        Asserts.assertFalse(osrDumps.isEmpty(),
                "no optimized OSR IR dump found for copyLoop");

        for (Path dump : osrDumps) {
            String ir = Files.readString(dump);
            Asserts.assertTrue(ir.contains(CHAR_ARRAY_COPY_STUB),
                    "OSR copyLoop did not select the char[] arraycopy stub: " + dump);
            Asserts.assertFalse(ir.contains(GENERIC_ARRAY_COPY_STUB),
                    "OSR copyLoop still contains generic arraycopy: " + dump);
        }
    }

    private static List<Path> findOsrOptimizedDumps(Path dumpDirectory)
            throws Exception {
        try (Stream<Path> files = Files.walk(dumpDirectory)) {
            return files.filter(path -> path.getFileName().toString()
                                    .endsWith("_optimized.ll"))
                    .filter(path -> {
                        try {
                            return Files.readString(path).contains(OSR_ROOT);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
    }

    private static void runChild() {
        char[] source = new char[256];
        char[] destination = new char[source.length];
        for (int i = 0; i < source.length; i++) {
            source[i] = (char) (i * 17);
        }

        copyLoop(source, destination, 20_000);

        for (int i = 0; i < source.length; i++) {
            Asserts.assertEquals(destination[i], source[i],
                    "char[] copy mismatch at index " + i);
        }
        System.out.println("ARRAYCOPY_OSR_PASS");
    }

    /*
     * The OSR entry loads src and dst from the OSR buffer as generic
     * addrspace(1) pointers.  Their dominating checkcast(char[]) calls must
     * let getJavaType recover the array element type before ArrayCopy
     * specialization runs.
     */
    static void copyLoop(char[] src, char[] dst, int iterations) {
        for (int i = 0; i < iterations; i++) {
            System.arraycopy(src, 0, dst, 0, src.length);
        }
    }
}