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
 * @summary Structurally verify all JVMTI virtual-thread intrinsic paths without enabling continuations
 * @requires vm.jvmti
 * @library /test/lib /
 * @build jdk.test.whitebox.WhiteBox
 * @compile --patch-module java.base=${test.src} java/lang/JeandleJvmtiHelper.java
 * @compile --patch-module java.base=${test.classes} TestJvmtiThreadIntrinsics.java
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run driver compiler.jeandle.intrinsic.TestJvmtiThreadIntrinsics
 */

package compiler.jeandle.intrinsic;

import java.lang.JeandleJvmtiHelper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;
import jdk.test.whitebox.WhiteBox;

public class TestJvmtiThreadIntrinsics {
    private static final String IDS = "_notifyJvmtiVThreadStart,_notifyJvmtiVThreadEnd,_notifyJvmtiVThreadMount,_notifyJvmtiVThreadUnmount,_notifyJvmtiVThreadHideFrames";

    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            WhiteBox wb = WhiteBox.getWhiteBox();
            Class<?> probes = JeandleJvmtiHelper.defineProbes();
            for (var method : probes.getDeclaredMethods()) {
                if (!wb.enqueueMethodForCompilation(method, 4)) throw new AssertionError("Compilation rejected: " + method);
                for (int retry = 0; retry < 1000 && !wb.isMethodCompiled(method); ++retry) Thread.sleep(10);
                if (!wb.isMethodCompiled(method)) throw new AssertionError("Probe was not compiled: " + method);
            }
            System.out.println("Five JVMTI probes compiled; lifecycle execution is unsupported with VMContinuations disabled");
            return;
        }
        Path patch = Files.createTempDirectory("jeandle-jvmti-patch");
        Path relative = Path.of("java/lang/JeandleJvmtiHelper.class");
        Files.createDirectories(patch.resolve(relative).getParent());
        Files.copy(Path.of(System.getProperty("test.classes")).resolve(relative), patch.resolve(relative));
        for (String mode : List.of("enabled", "disabled", "no-transitions")) {
            Path dump = Files.createTempDirectory("jeandle-jvmti-" + mode);
            var command = new ArrayList<>(List.of(
                    "--patch-module=java.base=" + patch,
                    "-Xbootclasspath/a:.",
                    "-XX:+UnlockDiagnosticVMOptions", "-XX:+WhiteBoxAPI",
                    "-XX:+UnlockExperimentalVMOptions", "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler",
                    "-XX:CompileCommand=compileonly,java.lang.JeandleJvmtiProbe*::*",
                    "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR", "-XX:JeandleDumpDirectory=" + dump));
            if (mode.equals("disabled")) command.add("-XX:DisableIntrinsic=" + IDS);
            if (mode.equals("no-transitions")) command.add("-XX:-DoJVMTIVirtualThreadTransitions");
            command.addAll(List.of(TestJvmtiThreadIntrinsics.class.getName(), "child"));
            OutputAnalyzer output = ProcessTools.executeProcess(ProcessTools.createLimitedTestJavaProcessBuilder(command));
            Files.writeString(dump.resolve("child.log"), output.getOutput());
            output.shouldHaveExitValue(0).shouldContain("Five JVMTI probes compiled");
            for (String name : List.of("Start", "End", "Mount", "Unmount", "HideFrames")) {
                boolean parsed = output.getOutput().lines().anyMatch(line -> line.contains("VirtualThread.notifyJvmti" + name)
                        && line.contains("is parsed as intrinsic"));
                if (parsed == mode.equals("disabled")) throw new AssertionError("Wrong intrinsic mode: " + name + " / " + mode);
            }
            for (String name : List.of("Start", "End", "Mount", "Unmount", "HideFrames")) {
                String optimized;
                try (var paths = Files.list(dump)) {
                    optimized = paths.filter(p -> p.getFileName().toString().contains("_probe" + name + "_"))
                            .filter(p -> p.toString().endsWith("_optimized.ll"))
                            .map(TestJvmtiThreadIntrinsics::read).reduce("", String::concat);
                }
                if (optimized.isEmpty()) throw new AssertionError("Missing compiled probe: " + name);
                if (mode.equals("enabled")) {
                    String hide = name.equals("Start") ? "0" : name.equals("End") ? "1" : "%[a-zA-Z0-9_.]+";
                    if (!name.equals("Start") && !name.equals("End")) {
                        var trunc = Pattern.compile("(%[a-zA-Z0-9_.]+) = trunc i32 %1 to i8").matcher(optimized);
                        if (!trunc.find()) throw new AssertionError("Missing actual hide argument: " + name);
                        hide = Pattern.quote(trunc.group(1));
                    }
                    require(optimized, "store atomic i8 " + hide + ", ptr %", "Native transition bit: " + name);
                    long stores = optimized.lines().filter(line -> line.contains("store atomic i8")).count();
                    if (stores != (name.equals("HideFrames") ? 1 : 2))
                        throw new AssertionError("Wrong transition store count: " + name);
                    if (name.equals("HideFrames")) {
                        if (optimized.contains("@notify_jvmti_vthread_")
                                || Pattern.compile("store atomic i8 [^,]+, ptr addrspace\\(1\\)").matcher(optimized).find())
                            throw new AssertionError("HideFrames must only update the temporary native bit");
                    } else {
                        require(optimized, "store atomic i8 " + hide + ", ptr addrspace\\(1\\)", "Heap transition bit: " + name);
                        require(optimized, "load atomic i8, ptr inttoptr", "Dynamic event enable flag: " + name);
                        require(optimized, "invoke hotspotcc token .*@llvm\\.experimental\\.gc\\.statepoint.*"
                                + "elementtype\\(void \\(ptr addrspace\\(1\\), i8, ptr\\)\\) @notify_jvmti_vthread_"
                                + name.toLowerCase() + ".*i8 " + hide + ", ptr .*\\\"gc-live\\\"\\(ptr addrspace\\(1\\) %0\\)",
                                "Non-leaf JVMTI statepoint and live receiver: " + name);
                    }
                } else if (mode.equals("no-transitions")) {
                    if (optimized.contains("@notify_jvmti_vthread_") || optimized.contains("store atomic i8"))
                        throw new AssertionError("JVMTI transition survived disabled transitions: " + name);
                }
            }
        }
    }
    private static String read(Path path) {
        try { return Files.readString(path); }
        catch (Exception ex) { throw new RuntimeException(ex); }
    }

    private static void require(String text, String pattern, String message) {
        if (!Pattern.compile(pattern).matcher(text).find()) throw new AssertionError(message);
    }
}
