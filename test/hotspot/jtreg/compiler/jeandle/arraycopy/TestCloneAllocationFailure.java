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
 * @summary Clone allocation failure reexecutes and reaches the Java handler
 * @requires vm.gc.Serial & vm.gc.G1
 * @library /test/lib /
 * @build compiler.jeandle.fileCheck.FileCheck
 * @run driver TestCloneAllocationFailure
 */

import java.nio.file.Files;
import java.util.List;

import compiler.jeandle.fileCheck.FileCheck;
import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestCloneAllocationFailure {
    private static Object[] retained;
    private static Object result;

    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            runWorkload(args[0].equals("reference"));
            return;
        }
        for (String gc : List.of("-XX:+UseSerialGC", "-XX:+UseG1GC")) {
            for (String kind : List.of("primitive", "reference")) {
                runCase(gc, kind);
            }
        }
    }

    private static void runCase(String gc, String kind) throws Exception {
        var dump = Files.createTempDirectory("clone_allocation_failure_");
        String method = kind.equals("reference") ? "cloneReferences" : "cloneBytes";
        OutputAnalyzer output = ProcessTools.executeProcess(
                ProcessTools.createLimitedTestJavaProcessBuilder(
                        "-Xms32m", "-Xmx32m", gc, "-XX:-UseTLAB",
                        "-XX:-UseGCOverheadLimit", "-Xcomp", "-Xbatch",
                        "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler",
                        "-XX:+CIPrintCompilerName",
                        "-XX:+UnlockDiagnosticVMOptions", "-XX:-ReduceInitialCardMarks",
                        "-XX:+JeandleDumpIR", "-XX:JeandleDumpDirectory=" + dump,
                        "-XX:CompileCommand=compileonly,TestCloneAllocationFailure::" + method,
                        "-Xlog:jeandle=debug,jit+compilation=debug",
                        TestCloneAllocationFailure.class.getName(), kind));
        output.shouldHaveExitValue(0).shouldContain("CLONE_ALLOCATION_FAILURE_PASS")
                .shouldContain("is parsed as intrinsic");
        output.shouldMatch("Jeandle:.*TestCloneAllocationFailure::" + method
                + " \\([0-9]+ bytes\\)");
        Class<?> arrayType = kind.equals("reference") ? Object[].class : byte[].class;
        FileCheck raw = new FileCheck(dump.toString(),
                TestCloneAllocationFailure.class.getDeclaredMethod(method, arrayType), false);
        raw.checkPatternAnywhere("invoke.*@jeandle\\.new_array.*\\[ \"deopt\"\\(i64 1,");
        raw.checkPatternAnywhere("landingpad");
        raw.checkPattern("store volatile ptr addrspace\\(1\\) null, ptr addrspace\\(2\\)");
        raw.checkPattern("call.*@llvm\\.experimental\\.deoptimize");
    }

    static byte[] cloneBytes(byte[] source) {
        return source.clone();
    }

    static Object[] cloneReferences(Object[] source) {
        return source.clone();
    }

    private static void runWorkload(boolean references) {
        Object source = references ? new Object[1 << 20] : new byte[1 << 22];
        // Compile and link before filling the heap so the failure is in clone's
        // allocation, rather than in class loading or compilation setup.
        result = references ? cloneReferences((Object[]) source) : cloneBytes((byte[]) source);
        result = null;
        retained = new Object[64];
        boolean full = false;
        try {
            for (int i = 0; i < retained.length; i++) {
                retained[i] = new byte[1 << 20];
            }
        } catch (OutOfMemoryError expected) {
            full = true;
        }
        int caught = 0;
        int finalized = 0;
        try {
            result = references ? cloneReferences((Object[]) source) : cloneBytes((byte[]) source);
        } catch (OutOfMemoryError expected) {
            caught++;
        } finally {
            finalized++;
        }
        // Keep all fillers live across the failed allocation and its deopt.
        // Free them before assertions or diagnostic output can allocate.
        retained = null;
        Asserts.assertTrue(full, "heap must be full before cloning");
        Asserts.assertEquals(caught, 1, "allocation failure must reach the caller once");
        Asserts.assertEquals(finalized, 1, "finally must execute once");
        Asserts.assertNull(result, "failed clone must not return an object");
        result = references ? cloneReferences((Object[]) source) : cloneBytes((byte[]) source);
        Asserts.assertTrue(result != source, "clone must recover after memory is released");
        System.out.println("CLONE_ALLOCATION_FAILURE_PASS");
    }
}
