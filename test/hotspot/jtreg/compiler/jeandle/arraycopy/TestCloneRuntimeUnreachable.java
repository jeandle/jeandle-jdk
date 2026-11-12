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
 * @summary Runtime clone supports targets outside the direct-call range
 * @requires vm.gc.Serial
 * @library /test/lib
 * @build TestObjectCloneSerialGCBarrier jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run driver TestCloneRuntimeUnreachable
 */

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import jdk.test.lib.Asserts;
import jdk.test.lib.Platform;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;
import jdk.test.whitebox.WhiteBox;

public class TestCloneRuntimeUnreachable {
    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            Object marker = new Object();
            var source = new TestObjectCloneSerialGCBarrier.CloneTarget(marker);
            var clone = source.copy();
            Asserts.assertNotNull(clone, "runtime clone result");
            Asserts.assertTrue(clone != source, "runtime clone identity");
            clone.assertReferences(marker);
            Asserts.assertTrue(WhiteBox.getWhiteBox().isMethodCompiled(
                    TestObjectCloneSerialGCBarrier.CloneTarget.class.getDeclaredMethod("copy")),
                    "clone target must have installed compiled code");
            System.out.println("RUNTIME_CLONE_PASS");
            return;
        }
        runCase(false, true);
        runCase(false, false);
        if (Platform.isX64()) {
            // ForceUnreachable changes direct-call reachability on x86 only.
            runCase(true, true);
            runCase(true, false);
        }
    }

    private static void runCase(boolean unreachable, boolean enabled) throws Exception {
        String method = TestObjectCloneSerialGCBarrier.CloneTarget.class.getName() + "::copy";
        ArrayList<String> command = new ArrayList<>(List.of(
                "-Xbootclasspath/a:.", "-XX:+UnlockDiagnosticVMOptions", "-XX:+WhiteBoxAPI",
                "-Xcomp", "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler",
                "-XX:+UseSerialGC", "-XX:-UseTLAB", "-XX:-ReduceInitialCardMarks",
                "-XX:+CIPrintCompilerName", "-Xlog:jeandle=debug,jit+compilation=debug",
                "-XX:CompileCommand=compileonly," + method));
        if (unreachable) {
            command.add("-XX:+ForceUnreachable");
        }
        if (!enabled) {
            command.add("-XX:ControlIntrinsic=-_clone");
        }
        command.add(TestCloneRuntimeUnreachable.class.getName());
        command.add("child");
        OutputAnalyzer output = ProcessTools.executeProcess(
                ProcessTools.createLimitedTestJavaProcessBuilder(command));
        output.shouldHaveExitValue(0).shouldContain("RUNTIME_CLONE_PASS");
        output.shouldMatch("Jeandle:.*" + Pattern.quote(method) + " \\([0-9]+ bytes\\)");
        if (enabled) {
            output.shouldContain("Method `virtual jobject java.lang.Object.clone()` is parsed as intrinsic");
        }
    }
}
