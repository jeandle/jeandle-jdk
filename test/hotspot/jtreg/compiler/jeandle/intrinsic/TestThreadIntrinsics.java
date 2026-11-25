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
 * @summary Carrier identity, current-thread changes and native ScopedValue cache roots
 * @library /test/lib /
 * @build jdk.test.whitebox.WhiteBox
 * @compile --patch-module java.base=${test.src} java/lang/JeandleThreadHelper.java
 * @compile --patch-module java.base=${test.classes} TestThreadIntrinsics.java
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run driver compiler.jeandle.intrinsic.TestThreadIntrinsics
 */

package compiler.jeandle.intrinsic;

import java.lang.JeandleThreadHelper;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;
import jdk.test.whitebox.WhiteBox;

public class TestThreadIntrinsics {
    private static final String IDS = "_currentCarrierThread,_setCurrentThread,_scopedValueCache,_setScopedValueCache";

    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            exercise();
            return;
        }
        Path patch = Files.createTempDirectory("jeandle-thread-patch");
        Path relative = Path.of("java/lang/JeandleThreadHelper.class");
        Files.createDirectories(patch.resolve(relative).getParent());
        Files.copy(Path.of(System.getProperty("test.classes")).resolve(relative), patch.resolve(relative));
        for (String mode : List.of("enabled", "disabled", "uncompressed", "serial")) {
            Path dump = Files.createTempDirectory("jeandle-thread-" + mode);
            var command = new ArrayList<>(List.of(
                    "--patch-module=java.base=" + patch,
                    "-Xbootclasspath/a:.", "-XX:+UnlockDiagnosticVMOptions", "-XX:+WhiteBoxAPI",
                    "-Xbatch", "-Xcomp", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler",
                    "-Xmx128m", "-Xlog:jeandle=debug", "-Xlog:gc=info", "-XX:+JeandleDumpIR",
                    "-XX:JeandleDumpDirectory=" + dump,
                    "-XX:CompileCommand=compileonly,java.lang.JeandleThreadHelper::*"));
            if (mode.equals("disabled")) command.addAll(List.of("-XX:+UnlockDiagnosticVMOptions", "-XX:DisableIntrinsic=" + IDS));
            if (mode.equals("uncompressed")) command.add("-XX:-UseCompressedOops");
            if (mode.equals("serial")) command.add("-XX:+UseSerialGC");
            command.addAll(List.of(TestThreadIntrinsics.class.getName(), "child"));
            OutputAnalyzer output = ProcessTools.executeProcess(ProcessTools.createLimitedTestJavaProcessBuilder(command));
            Files.writeString(dump.resolve("child.log"), output.getOutput());
            output.shouldHaveExitValue(0).shouldContain("Thread intrinsics verified");
            output.shouldContain(mode.equals("serial") ? "Using Serial" : "Using G1").shouldContain("System.gc()");
            for (String name : List.of("currentCarrierThread", "setCurrentThread", "scopedValueCache", "setScopedValueCache")) {
                String log = "java.lang.Thread." + name;
                if (mode.equals("disabled")) {
                    if (output.getOutput().lines().anyMatch(line -> line.contains(log) && line.contains("is parsed as intrinsic")))
                        throw new AssertionError("Disabled intrinsic was lowered: " + name);
                } else if (output.getOutput().lines().noneMatch(line -> line.contains(log) && line.contains("is parsed as intrinsic"))) {
                    throw new AssertionError("Missing intrinsic: " + name);
                }
            }
            if (!mode.equals("disabled")) {
                String initial;
                try (var paths = Files.list(dump)) {
                    initial = paths.filter(p -> p.toString().endsWith(".ll") && !p.toString().endsWith("_optimized.ll"))
                            .map(TestThreadIntrinsics::read).reduce("", String::concat);
                }
                for (String name : List.of("current_carrier_thread_obj", "scoped_value_cache", "set_scoped_value_cache", "set_current_thread_obj"))
                    if (!initial.contains("@jeandle." + name)) throw new AssertionError("Missing JavaOp " + name);
                for (String method : List.of("carrier", "cache", "setCache", "changeAndRestore")) {
                    String optimized;
                    try (var paths = Files.list(dump)) {
                        optimized = paths.filter(p -> p.getFileName().toString().startsWith("java_lang_JeandleThreadHelper_" + method + "_"))
                                .filter(p -> p.toString().endsWith("_optimized.ll"))
                                .map(TestThreadIntrinsics::read).reduce("", String::concat);
                    }
                    if (!(mode.equals("serial") && method.equals("setCache"))
                            && !optimized.contains("load atomic ptr addrspace(1), ptr "))
                        throw new AssertionError("Native handle must load a wide oop: " + method);
                    if (method.equals("setCache") || method.equals("changeAndRestore")) {
                        if (!optimized.contains("store atomic ptr addrspace(1)"))
                            throw new AssertionError("Native handle must store a wide oop: " + method);
                        if (!mode.equals("serial")
                                && !optimized.contains("jeandle.g1_pre_barrier_loaded.exit"))
                            throw new AssertionError("Missing native-root SATB pre-barrier: " + method);
                    }
                    if (method.equals("changeAndRestore") && output.getOutput().contains("JFR included: true")) {
                        var alias = Pattern.compile("@JeandleRuntime_jfr_on_set_current_thread = alias ptr, inttoptr \\(i64 ([0-9]+) to ptr\\)")
                                .matcher(optimized);
                        if (!alias.find()) throw new AssertionError("Missing JFR runtime address");
                        var call = Pattern.compile("call void inttoptr \\(i64 " + alias.group(1)
                                + " to ptr\\).* #([0-9]+)").matcher(optimized);
                        int updates = 0;
                        while (call.find()) {
                            ++updates;
                            if (!Pattern.compile("attributes #" + call.group(1) + " = \\{[^\\n]*\\\"gc-leaf-function\\\"")
                                    .matcher(optimized).find())
                                throw new AssertionError("JFR update is not a leaf call");
                        }
                        if (updates < 2) throw new AssertionError("Missing JFR update on change/restore");
                    }
                }
            }
        }
    }

    private static String read(Path path) {
        try { return Files.readString(path); }
        catch (Exception ex) { throw new RuntimeException(ex); }
    }

    private static void exercise() throws Exception {
        System.out.println("JFR included: " + WhiteBox.getWhiteBox().isJFRIncluded());
        Thread original = Thread.currentThread();
        if (JeandleThreadHelper.carrier() != original) throw new AssertionError("Wrong platform carrier");
        Thread replacement = new Thread(() -> {});
        if (JeandleThreadHelper.changeAndRestore(replacement) != replacement || Thread.currentThread() != original)
            throw new AssertionError("Thread identity change/restore failed");
        Object[] originalCache = JeandleThreadHelper.cache();
        try {
            JeandleThreadHelper.setCache(null);
            if (JeandleThreadHelper.cache() != null) throw new AssertionError("Cache was not cleared");
            for (int i = 0; i < 24; ++i) {
                WeakReference<Object[]> expected = installCache(i);
                System.gc();
                Object[] cache = JeandleThreadHelper.cache();
                if (cache == null || cache != expected.get() || !cache[0].equals(i))
                    throw new AssertionError("Native cache root lost across GC");
            }
        } finally {
            JeandleThreadHelper.setCache(originalCache);
        }
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = Thread.startVirtualThread(() -> {
            try {
                if (JeandleThreadHelper.carrier() != Thread.currentThread())
                    throw new AssertionError("Bound virtual thread should be its own carrier");
            } catch (Throwable ex) {
                failure.set(ex);
            }
        });
        thread.join();
        if (failure.get() != null) throw new AssertionError(failure.get());
        System.out.println("Thread intrinsics verified");
    }

    private static WeakReference<Object[]> installCache(int marker) {
        Object[] cache = {marker, new byte[1024]};
        JeandleThreadHelper.setCache(cache);
        return new WeakReference<>(cache);
    }
}
