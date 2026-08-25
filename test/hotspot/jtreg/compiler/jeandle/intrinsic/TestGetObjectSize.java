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
 * @summary Verify the Jeandle intrinsic for InstrumentationImpl.getObjectSize0
 * @requires vm.jvmti
 * @requires os.arch=="amd64" | os.arch=="x86_64" | os.arch=="aarch64"
 * @modules java.instrument
 * @library /test/lib /
 * @build compiler.jeandle.fileCheck.FileCheck
 * @run driver compiler.jeandle.intrinsic.TestGetObjectSize
 */

package compiler.jeandle.intrinsic;

import compiler.jeandle.fileCheck.FileCheck;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestGetObjectSize {
    private static final String INTRINSIC_LOG =
            "Method `virtual jlong sun.instrument.InstrumentationImpl.getObjectSize0"
                    + "(jlong, jobject)` is parsed as intrinsic";
    private static final String SIZE_PREFIX = "OBJECT_SIZE ";
    private static final String VARIABLE_MIRROR = "variableMirror";
    private static Path agentJar;

    public static class Agent {
        static volatile Instrumentation instrumentation;

        public static void premain(String args, Instrumentation inst) {
            instrumentation = inst;
        }
    }

    public static void main(String[] args) throws Exception {
        agentJar = createAgentJar();
        RunResult enabled = run("enabled", true);
        RunResult disabled = run("disabled", false);

        assertComparableSizes(enabled.sizes, disabled.sizes);
        enabled.output.shouldContain(INTRINSIC_LOG);
        disabled.output.shouldNotContain(INTRINSIC_LOG);
        checkIR(enabled.dumpDir, true);
        checkIR(disabled.dumpDir, false);
        checkObjectCode(enabled.dumpDir);
    }

    private static RunResult run(String name, boolean intrinsicEnabled) throws Exception {
        Path dumpDir = Files.createTempDirectory("jeandle_get_object_size_" + name);
        String control = intrinsicEnabled ? "+_getObjectSize" : "-_getObjectSize";
        List<String> command = new ArrayList<>(List.of(
                "-javaagent:" + agentJar,
                "-Xbatch", "-Xcomp", "-XX:-TieredCompilation", "-XX:-BackgroundCompilation",
                "-XX:+UseJeandleCompiler", "-Xlog:jeandle=debug,jit+compilation=debug",
                "-XX:CompileCommand=compileonly,sun.instrument.InstrumentationImpl::getObjectSize",
                "-XX:+UnlockDiagnosticVMOptions", "-XX:ControlIntrinsic=" + control,
                "-XX:+CIPrintCompilerName", "-XX:+JeandleDumpIR", "-XX:+JeandleDumpObjects",
                "-XX:JeandleDumpDirectory=" + dumpDir,
                Runner.class.getName()));
        OutputAnalyzer output = ProcessTools.executeCommand(
                ProcessTools.createLimitedTestJavaProcessBuilder(command));
        output.shouldHaveExitValue(0);
        if (intrinsicEnabled) {
            output.shouldMatch("(?s).*Jeandle:.*sun.instrument.InstrumentationImpl::getObjectSize.*");
        }
        return new RunResult(output, dumpDir, parseSizes(output.getOutput()));
    }

    private static void assertComparableSizes(Map<String, Long> enabled,
                                              Map<String, Long> disabled) {
        if (!enabled.keySet().equals(disabled.keySet())) {
            throw new AssertionError("enabled/disabled cases differ: "
                    + enabled.keySet() + " vs " + disabled.keySet());
        }
        for (String name : enabled.keySet()) {
            long enabledSize = enabled.get(name);
            long disabledSize = disabled.get(name);
            if (enabledSize <= 0 || disabledSize <= 0) {
                throw new AssertionError("non-positive size for " + name + ": "
                        + enabledSize + " vs " + disabledSize);
            }
            // Class mirrors with static fields are variable-sized. C1/C2 and
            // Jeandle intentionally return an implementation-specific layout
            // approximation, while the disabled native path may return oop::size().
            if (name.equals(VARIABLE_MIRROR)) {
                if (enabledSize >= disabledSize) {
                    throw new AssertionError("variable Class mirror did not exercise the "
                            + "layout approximation: " + enabledSize + " vs " + disabledSize);
                }
            } else if (enabledSize != disabledSize) {
                throw new AssertionError("intrinsic/native size mismatch for " + name + ": "
                        + enabledSize + " vs " + disabledSize);
            }
        }
    }

    private static Map<String, Long> parseSizes(String output) {
        Map<String, Long> sizes = new LinkedHashMap<>();
        for (String line : output.lines().toList()) {
            if (!line.startsWith(SIZE_PREFIX)) {
                continue;
            }
            int equals = line.indexOf('=', SIZE_PREFIX.length());
            if (equals < 0) {
                throw new AssertionError("malformed object-size result: " + line);
            }
            String name = line.substring(SIZE_PREFIX.length(), equals);
            long size = Long.parseLong(line.substring(equals + 1));
            if (sizes.putIfAbsent(name, size) != null) {
                throw new AssertionError("duplicate object-size result: " + name);
            }
        }
        if (sizes.isEmpty()) {
            throw new AssertionError("child produced no object-size results\n" + output);
        }
        return sizes;
    }

    private static void checkIR(Path dumpDir, boolean intrinsicEnabled) throws Exception {
        Class<?> impl = Class.forName("sun.instrument.InstrumentationImpl");
        Method wrapper = impl.getMethod("getObjectSize", Object.class);
        FileCheck raw = new FileCheck(dumpDir.toString(), wrapper, false);
        FileCheck optimized = new FileCheck(dumpDir.toString(), wrapper, true);
        if (intrinsicEnabled) {
            raw.checkPattern("call hotspotcc ptr @jeandle\\.load_klass");
            raw.checkPattern("call hotspotcc i32 @jeandle\\.layout_helper");
            raw.checkPattern("get_object_size\\.instance\\.bytes");
            raw.checkPattern("call hotspotcc i32 @jeandle\\.arraylength");
            raw.checkPattern("get_object_size\\.array\\.result");

            optimized.checkNotPattern(
                    "call .*@jeandle\\.(?:load_klass|layout_helper|arraylength)");
            optimized.checkPattern("shl i64");
            optimized.checkNotPattern("getObjectSize0");
        } else {
            raw.checkNotPattern("get_object_size\\.");
            raw.checkPattern("getObjectSize0");
            optimized.checkNotPattern("get_object_size\\.");
            optimized.checkPattern("getObjectSize0");
        }
    }

    private static void checkObjectCode(Path dumpDir) throws Exception {
        Path object;
        String prefix = "sun_instrument_InstrumentationImpl_getObjectSize";
        try (Stream<Path> files = Files.list(dumpDir)) {
            object = files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith(prefix))
                    .filter(path -> path.getFileName().toString().endsWith(".o"))
                    .max(Comparator.comparing(path -> path.getFileName().toString()))
                    .orElseThrow(() -> new AssertionError("No object dump for " + prefix));
        }

        OutputAnalyzer disassembly;
        try {
            disassembly = ProcessTools.executeCommand("objdump", "-dr", object.toString());
        } catch (IOException e) {
            if (e.getMessage().contains("Cannot run program \"objdump\"")) {
                System.out.println("Skipping object-code check: system objdump is unavailable");
                return;
            }
            throw e;
        }
        disassembly.shouldHaveExitValue(0)
                   .shouldContain(prefix)
                   .shouldNotContain("getObjectSize0")
                   .shouldMatch("(?im)^\\s*[0-9a-f]+:\\s+[0-9a-f ]+\\s+"
                           + "(?:shl\\w*|sal\\w*|lsl|lslv|sll\\w*)\\b.*$");
    }

    private static Path createAgentJar() throws Exception {
        Manifest manifest = new Manifest();
        Attributes attrs = manifest.getMainAttributes();
        attrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attrs.put(new Attributes.Name("Premain-Class"), Agent.class.getName());

        Path jar = Files.createTempFile("get-object-size-agent", ".jar");
        String resource = Agent.class.getName().replace('.', '/') + ".class";
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest);
             InputStream in = Agent.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new AssertionError("missing agent class resource: " + resource);
            }
            out.putNextEntry(new JarEntry(resource));
            in.transferTo(out);
            out.closeEntry();
        }
        return jar;
    }

    private record RunResult(OutputAnalyzer output, Path dumpDir, Map<String, Long> sizes) { }

    public static class Runner {
        public static void main(String[] args) {
            Instrumentation instrumentation = Agent.instrumentation;
            if (instrumentation == null) {
                throw new AssertionError("javaagent did not initialize Instrumentation");
            }

            print(instrumentation, "instance", new Holder());
            print(instrumentation, "fixedMirror", Holder.class);
            print(instrumentation, VARIABLE_MIRROR, StaticHeavy.class);
            int[] byteLengths = {0, 1, 7, 8, 9, 15, 16, 17, 1024};
            for (int length : byteLengths) {
                print(instrumentation, "byte" + length, new byte[length]);
            }
            int[] intLengths = {0, 1, 2, 3, 4, 7, 8, 9};
            for (int length : intLengths) {
                print(instrumentation, "int" + length, new int[length]);
            }
            print(instrumentation, "long0", new long[0]);
            print(instrumentation, "long8", new long[8]);
            print(instrumentation, "object0", new Object[0]);
            print(instrumentation, "object1024", new Object[1024]);

            try {
                instrumentation.getObjectSize(null);
                throw new AssertionError("getObjectSize(null) did not throw");
            } catch (NullPointerException expected) {
                // The public wrapper rejects null before calling getObjectSize0.
            }
        }

        private static void print(Instrumentation instrumentation, String name, Object value) {
            System.out.println(SIZE_PREFIX + name + "=" + instrumentation.getObjectSize(value));
        }

        static class Holder {
            long value;
            Object reference;
        }

        static class StaticHeavy {
            static long a, b, c, d, e, f, g, h;
            static Object i, j, k, l;
        }
    }
}
