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
 * @summary Test the intrinsic implementation of System.identityHashCode
 * @requires os.arch=="amd64" | os.arch=="x86_64" | os.arch=="aarch64"
 * @library /test/lib /
 * @run main/othervm compiler.jeandle.intrinsic.TestIdentityHashCode
 */

package compiler.jeandle.intrinsic;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import compiler.jeandle.fileCheck.FileCheck;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestIdentityHashCode {

    // Per-call-site log line emitted by Jeandle when identityHashCode is recognized.
    private static final String INTRINSIC_LOG_LINE =
        "Method `static jint java.lang.System.identityHashCode(jobject)` is parsed as intrinsic";

    public static void main(String[] args) throws Exception {
        String dumpPath = Files.createTempDirectory("jeandle_identity_hashcode").toString();

        ArrayList<String> commandArgs = new ArrayList<>(List.of(
                "-Xbatch",
                "-XX:-TieredCompilation",
                "-XX:+UseJeandleCompiler",
                "-Xcomp",
                "-Xlog:jeandle=debug",
                "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dumpPath,
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::hashOf",
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::hashOfNull",
                TestWrapper.class.getName()));

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(commandArgs);
        OutputAnalyzer output = ProcessTools.executeCommand(pb);

        output.shouldHaveExitValue(0);

        // Expecting count = 2 (not just shouldContain) ensures both hashOf and hashOfNull are intrinsified.
        Matcher m = Pattern.compile(Pattern.quote(INTRINSIC_LOG_LINE)).matcher(output.getOutput());
        int intrinsicCount = 0;
        while (m.find()) intrinsicCount++;
        Asserts.assertEQ(intrinsicCount, 2,
            "Expected exactly 2 intrinsic-log entries (one each for hashOf/hashOfNull), got "
            + intrinsicCount);

        // Verify the fast path is generated as a call to jeandle.hashcode_fast
        // (the JavaOp defined in jeandleRuntimeDefinedJavaOps.cpp), with the slow
        // path falling back to a static Java call to System.identityHashCode
        // (matching C2's design). Patterns walk the file in order.
        FileCheck fc = new FileCheck(dumpPath,
                TestWrapper.class.getDeclaredMethod("hashOf", Object.class), false);
        // Null check branches to merge (returns 0) or fast-path block.
        fc.checkPattern("identityHashCode_not_null");
        fc.checkPattern("call hotspotcc i32 @jeandle\\.hashcode_fast");
        // Slow-path Java call (static dispatch to System.identityHashCode).
        fc.checkPattern("hashCode_slow_call");
        fc.checkPattern("@\"java_lang_System_identityHashCode\\(Ljava/lang/Object;\\)I\"");
        // Merge PHI for null (0) / fast result / slow result.
        fc.checkPattern("hashCode_merge");
    }

    static class TestWrapper {
        public static void main(String[] args) {
            // Pre-load System.identityHashCode so the first wrapper compiled avoids Jeandle's Reason_unloaded deopt at the invokestatic.
            int preload = System.identityHashCode(new Object());
            if (preload == 0) {
                throw new RuntimeException("prime failed");
            }

            Object o = new Object();
            int a = hashOf(o);
            int b = hashOfNull();

            // --- Correctness: identityHashCode value & null semantics ---
            Asserts.assertEquals(a, System.identityHashCode(o),
                                 "Jeandle-compiled hash must match interpreter result");
            Asserts.assertEquals(0, b, "System.identityHashCode(null) must be 0");

            System.out.println("TestIdentityHashCode PASSED");
        }

        // Compiled by Jeandle — exercises lower_hash_code(identityHashCode) on non-null path.
        static int hashOf(Object o) {
            return System.identityHashCode(o);
        }

        // Compiled by Jeandle — exercises the null path of lower_hash_code (returns 0 inline).
        static int hashOfNull() {
            return System.identityHashCode(null);
        }
    }
}
