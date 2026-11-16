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
 * @summary Caller-class specialization uses committed inline scopes and preserves VM stack-walk fallback
 * @library /test/lib /
 * @modules java.base/jdk.internal.reflect
 * @compile --patch-module java.base=${test.src} jdk/internal/reflect/JeandleCallerClassHelper.java
 * @compile --patch-module java.base=${test.classes} TestCallerClass.java
 * @run main/othervm/timeout=240 compiler.jeandle.intrinsic.TestCallerClass
 */

package compiler.jeandle.intrinsic;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import jdk.internal.reflect.CallerSensitive;
import jdk.internal.reflect.JeandleCallerClassHelper;
import jdk.internal.reflect.Reflection;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestCallerClass {
    private static final String HELPER = "jdk.internal.reflect.JeandleCallerClassHelper";
    private static final Pattern CALLER_CALL = Pattern.compile(
            "(?:call|invoke) hotspotcc [^\n]*@[^\n(]*Reflection_getCallerClass");

    public static void main(String[] args) throws Throwable {
        if (args.length != 0) {
            exercise();
            System.out.println("Caller-class semantics passed: " + args[0]);
            return;
        }
        run("interpreter");
        run("folded");
        run("disabled");
        run("no-inline");
        run("accessors-only");
    }

    private static void run(String mode) throws Exception {
        Path dumps = Files.createTempDirectory("jeandle-caller-" + mode);
        Path log = dumps.resolve("compilation.xml");
        Path patch = Files.createTempDirectory("jeandle-caller-helper");
        Path helperPackage = Path.of("jdk", "internal", "reflect");
        Files.createDirectories(patch.resolve(helperPackage));
        for (String name : List.of("JeandleCallerClassHelper.class", "JeandleCallerClassHelper$Ignored.class")) {
            Files.copy(Path.of(System.getProperty("test.classes")).resolve(helperPackage).resolve(name),
                    patch.resolve(helperPackage).resolve(name));
        }
        List<String> command = new ArrayList<>(List.of(
                "--patch-module=java.base=" + patch,
                "--add-exports=java.base/jdk.internal.reflect=ALL-UNNAMED"));
        if (mode.equals("interpreter")) {
            command.add("-Xint");
        } else {
            command.addAll(List.of("-Xcomp", "-Xbatch", "-XX:-TieredCompilation",
                    "-XX:+UseJeandleCompiler", "-XX:+JeandlePrintInlineTree", "-XX:+UnlockDiagnosticVMOptions",
                    "-XX:+LogCompilation", "-XX:LogFile=" + log,
                    "-XX:+JeandleDumpIR", "-XX:JeandleDumpDirectory=" + dumps,
                    "-XX:CompileCommand=compileonly," + TestCallerClass.class.getName() + "::sharedCaller",
                    "-XX:CompileCommand=compileonly," + TestCallerClass.class.getName() + "::ignoredCaller",
                    "-XX:CompileCommand=compileonly," + TestCallerClass.class.getName() + "::applicationCaller",
                    "-XX:CompileCommand=compileonly," + TestCallerClass.class.getName() + "::illegalInlinedCaller",
                    "-XX:CompileCommand=compileonly," + HELPER + "::caller",
                    "-XX:CompileCommand=compileonly," + HELPER + "::illegalCaller",
                    "-XX:CompileCommand=inline," + HELPER + "::caller",
                    "-XX:CompileCommand=inline," + HELPER + "::illegalCaller",
                    "-XX:CompileCommand=inline," + TestCallerClass.class.getName() + "::applicationCaller",
                    "-XX:CompileCommand=inline," + HELPER + "$Ignored::caller",
                    "-XX:CompileCommand=inline," + CallerA.class.getName() + "::caller",
                    "-XX:CompileCommand=inline," + CallerB.class.getName() + "::caller"));
            if (mode.equals("disabled")) {
                command.add("-XX:DisableIntrinsic=_getCallerClass");
            } else if (!mode.equals("folded")) {
                command.add("-XX:JeandleLLVMOptions=-jeandle-inline=" +
                        (mode.equals("no-inline") ? "off" : "accessors-only"));
            }
        }
        command.add(TestCallerClass.class.getName());
        command.add(mode);
        OutputAnalyzer output = ProcessTools.executeTestJava(command);
        Files.writeString(dumps.resolve("child-output.log"), output.getOutput());
        output.shouldHaveExitValue(0).shouldContain("Caller-class semantics passed: " + mode);
        if (mode.equals("interpreter")) {
            return;
        }
        String xml = Files.readString(log);
        for (String name : List.of("sharedCaller", "ignoredCaller", "applicationCaller", "illegalInlinedCaller")) {
            requireCompilation(xml, TestCallerClass.class.getName(), name);
        }
        requireCompilation(xml, HELPER, "caller");
        requireCompilation(xml, HELPER, "illegalCaller");
        String root = readIR(dumps, HELPER, "caller", true);
        require(CALLER_CALL.matcher(root).find(), "root caller must retain the native fallback", dumps);
        String illegal = readIR(dumps, HELPER, "illegalCaller", true);
        require(CALLER_CALL.matcher(illegal).find(), "illegal caller must retain InternalError fallback", dumps);
        String untrusted = readIR(dumps, TestCallerClass.class.getName(), "applicationCaller", true);
        require(CALLER_CALL.matcher(untrusted).find(), "application annotation must retain InternalError fallback", dumps);
        String inlinedIllegal = readIR(dumps, TestCallerClass.class.getName(), "illegalInlinedCaller", true);
        if (mode.equals("folded") || mode.equals("disabled")) {
            require(CALLER_CALL.matcher(inlinedIllegal).find(),
                    "inlined illegal caller must retain InternalError fallback", dumps);
        }
        if (mode.equals("folded")) {
            require(root.contains("\"jeandle-caller-class\""), "missing caller-class intrinsic candidate", dumps);
            for (String method : List.of("sharedCaller", "ignoredCaller")) {
                String ir = readIR(dumps, TestCallerClass.class.getName(), method, true);
                require(!CALLER_CALL.matcher(ir).find(), "caller native call was not specialized: " + method, dumps);
                require(ir.contains("oop_handle_"), "missing GC-safe caller mirror: " + method, dumps);
            }
        } else if (mode.equals("disabled")) {
            require(!root.contains("jeandle-caller-class"), "disabled intrinsic still marked a candidate", dumps);
            String shared = readIR(dumps, TestCallerClass.class.getName(), "sharedCaller", true);
            require(CALLER_CALL.matcher(shared).find(), "disabled intrinsic lost ordinary native call", dumps);
        } else {
            String shared = readIR(dumps, TestCallerClass.class.getName(), "sharedCaller", true);
            require(shared.contains("CallerA_caller") && shared.contains("CallerB_caller"),
                    "insufficient inline depth must preserve Java calls", dumps);
        }
    }

    private static void requireCompilation(String xml, String holder, String method) {
        if (!xml.lines().anyMatch(line -> line.contains("<nmethod") && line.contains("compiler='jeandle'")
                && line.contains(holder + " " + method + " "))) {
            throw new AssertionError("Missing Jeandle compilation: " + holder + "::" + method);
        }
    }

    private static String readIR(Path dumps, String holder, String method, boolean optimized) throws Exception {
        String prefix = holder.replace('.', '_') + "_" + method + "_";
        try (var paths = Files.list(dumps)) {
            Path file = paths.filter(path -> path.getFileName().toString().startsWith(prefix))
                    .filter(path -> path.toString().endsWith("_optimized.ll") == optimized)
                    .filter(path -> path.toString().endsWith(".ll"))
                    .sorted().reduce((first, last) -> last)
                    .orElseThrow(() -> new AssertionError("Missing IR: " + prefix + " in " + dumps));
            return Files.readString(file);
        }
    }

    private static void require(boolean condition, String message, Path dumps) {
        if (!condition) {
            throw new AssertionError(message + "; IR: " + dumps);
        }
    }

    private static void exercise() throws Throwable {
        Class.forName(CallerA.class.getName());
        Class.forName(CallerB.class.getName());
        Class.forName(HELPER);
        Class.forName(HELPER + "$Ignored");
        if (JeandleCallerClassHelper.class.getClassLoader() != null
                || !Reflection.isCallerSensitive(JeandleCallerClassHelper.class.getMethod("caller"))
                || Reflection.isCallerSensitive(TestCallerClass.class.getMethod("applicationCaller"))) {
            throw new AssertionError("CallerSensitive helper trust was not established");
        }
        // Resolve the inlinees' constant-pool call sites before -Xcomp compiles
        // sharedCaller. The inliner requires resolved calls in this mode.
        check(CallerA.caller(), CallerA.class);
        check(CallerB.caller(), CallerB.class);
        check(JeandleCallerClassHelper.Ignored.caller(), TestCallerClass.class);
        for (int i = 0; i < 100; i++) {
            check(sharedCaller(false), CallerA.class);
            check(sharedCaller(true), CallerB.class);
            check(ignoredCaller(), TestCallerClass.class);
            check(JeandleCallerClassHelper.caller(), TestCallerClass.class);
            expectInternalError(false, false);
            expectInternalError(true, false);
            expectInternalError(false, true);
            expectInternalError(true, true);
        }
        Class<?> reflected = (Class<?>) JeandleCallerClassHelper.class.getMethod("caller").invoke(null);
        if (!reflected.isNestmateOf(TestCallerClass.class)) {
            throw new AssertionError("reflection caller: " + reflected);
        }
        var handle = MethodHandles.lookup().findStatic(JeandleCallerClassHelper.class, "caller",
                MethodType.methodType(Class.class));
        Class<?> handled = (Class<?>) handle.invokeExact();
        if (!handled.isNestmateOf(TestCallerClass.class)) {
            throw new AssertionError("method handle caller: " + handled);
        }
    }

    private static void check(Class<?> actual, Class<?> expected) {
        if (actual != expected) {
            throw new AssertionError(actual + " != " + expected);
        }
    }

    private static void expectInternalError(boolean application, boolean inline) {
        try {
            if (inline) {
                illegalInlinedCaller(application);
            } else if (application) {
                applicationCaller();
            } else {
                JeandleCallerClassHelper.illegalCaller();
            }
            throw new AssertionError("Illegal getCallerClass succeeded");
        } catch (InternalError expected) {
            if (!expected.getMessage().startsWith("CallerSensitive annotation expected")) {
                throw expected;
            }
        }
    }

    public static Class<?> sharedCaller(boolean second) {
        return second ? CallerB.caller() : CallerA.caller();
    }

    public static Class<?> ignoredCaller() {
        return JeandleCallerClassHelper.Ignored.caller();
    }

    public static Class<?> illegalInlinedCaller(boolean application) {
        return application ? applicationCaller() : JeandleCallerClassHelper.illegalCaller();
    }

    @CallerSensitive
    public static Class<?> applicationCaller() {
        return Reflection.getCallerClass();
    }

    public static class CallerA {
        public static Class<?> caller() { return JeandleCallerClassHelper.caller(); }
    }

    public static class CallerB {
        public static Class<?> caller() { return JeandleCallerClassHelper.caller(); }
    }
}
