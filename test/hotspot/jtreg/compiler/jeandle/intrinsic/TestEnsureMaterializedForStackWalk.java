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
 * @summary Verify that ensureMaterializedForStackWalk materializes a
 *          scalar-replaced array argument in Jeandle PEA
 * @requires os.arch=="amd64" | os.arch=="x86_64" | os.arch=="aarch64"
 * @library /test/lib /
 * @build compiler.jeandle.fileCheck.FileCheck
 * @compile --patch-module java.base=${test.src} java/lang/JeandleEnsureMaterializedForStackWalkCaller.java
 * @run main/othervm compiler.jeandle.intrinsic.TestEnsureMaterializedForStackWalk
 */

package compiler.jeandle.intrinsic;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import compiler.jeandle.fileCheck.FileCheck;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

/**
 * The two methods compiled by this test have identical allocation and field
 * accesses.  The only difference is the call to
 * Thread.ensureMaterializedForStackWalk.  Thus, PEA should eliminate
 * plainArray, while the intrinsic must force the array in ensureArray to be
 * materialized.  Checking the PEA trace as well as the final IR avoids using
 * a runtime allocation retained for an unrelated reason as evidence of
 * intrinsic materialization.
 */
public class TestEnsureMaterializedForStackWalk {
    private static final String CALLER =
            "java.lang.JeandleEnsureMaterializedForStackWalkCaller";
    private static final String INTRINSIC_LOG =
            "Method `static void java.lang.Thread.ensureMaterializedForStackWalk(jobject)` is parsed as intrinsic";
    private static final String JAVA_OP_CALL =
            "call hotspotcc void @jeandle.ensure_materialized_for_stack_walk";
    private static final String PLAIN_METHOD = "plainArray";
    private static final String ENSURE_METHOD = "ensureArray";

    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            runChild(Path.of(args[0]));
            return;
        }

        Path dumpPath = Files.createTempDirectory(
                "jeandle_ensure_materialized_pea_ir");
        OutputAnalyzer output = ProcessTools.executeCommand(
                ProcessTools.createLimitedTestJavaProcessBuilder(List.of(
                        "-Xbatch", "-Xcomp", "-XX:-TieredCompilation",
                        "-XX:-BackgroundCompilation", "-XX:+UseJeandleCompiler",
                        "-XX:+JeandleDoPEA", "--patch-module=java.base="
                                + System.getProperty("test.class.path"),
                        "-Xlog:jeandle=debug,jit+compilation=debug",
                        "-XX:CompileCommand=compileonly," + CALLER
                                + "::plainArray",
                        "-XX:CompileCommand=compileonly," + CALLER
                                + "::ensureArray",
                        "-XX:+UnlockDiagnosticVMOptions", "-XX:+CIPrintCompilerName",
                        "-XX:+JeandleDumpIR", "-XX:+JeandleDumpObjects",
                        "-XX:JeandleDumpDirectory=" + dumpPath,
                        "-XX:JeandleLLVMOptions=-jeandle-dump-pea-stats -jeandle-trace-pea",
                        TestEnsureMaterializedForStackWalk.class.getName(),
                        dumpPath.toString())));

        output.shouldHaveExitValue(0).shouldContain(INTRINSIC_LOG);
        output.shouldMatch("(?s).*PEA: EliminateAllocation.*" + PLAIN_METHOD + ".*");
        output.shouldMatch("(?s).*PEA: Materialize.*" + ENSURE_METHOD + ".*");
    }

    private static void runChild(Path dumpPath) throws Exception {
        Class<?> caller = Class.forName(CALLER);
        Method plainArray = caller.getMethod(PLAIN_METHOD, int.class);
        Method ensureArray = caller.getMethod(ENSURE_METHOD, int.class);
        for (int i = 0; i < 20_000; i++) {
            int expected = i - 10_000;
            if ((int) plainArray.invoke(null, expected) != expected
                    || (int) ensureArray.invoke(null, expected) != expected) {
                throw new AssertionError("array value mismatch");
            }
        }
        // The caller is patched into java.base, so inspect its Method objects
        // from this child VM, which has the patch module installed.
        checkIR(dumpPath);
    }

    private static void checkIR(Path dumpPath) throws Exception {
        Class<?> caller = Class.forName(CALLER);
        checkPlainArrayIR(dumpPath, caller.getMethod(PLAIN_METHOD, int.class));
        checkEnsureArrayIR(dumpPath, caller.getMethod(ENSURE_METHOD, int.class));
    }

    private static void checkPlainArrayIR(Path dumpPath, Method method)
            throws Exception {
        FileCheck raw = new FileCheck(dumpPath.toString(), method, false);
        FileCheck optimized = new FileCheck(dumpPath.toString(), method, true);
        raw.checkPattern("@jeandle\\.new_array");
        optimized.checkNotPattern("@jeandle\\.new_array");
        optimized.checkNotPattern(
                "@llvm\\.experimental\\.gc\\.statepoint.*@new_array");
    }

    private static void checkEnsureArrayIR(Path dumpPath, Method method)
            throws Exception {
        FileCheck raw = new FileCheck(dumpPath.toString(), method, false);
        FileCheck optimized = new FileCheck(dumpPath.toString(), method, true);
        raw.check(JAVA_OP_CALL);
        optimized.checkNot(JAVA_OP_CALL);
        optimized.checkPattern(
                "@llvm\\.experimental\\.gc\\.statepoint.*@new_array");
    }
}
