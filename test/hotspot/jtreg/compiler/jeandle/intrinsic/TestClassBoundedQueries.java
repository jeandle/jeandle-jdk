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
 * @summary Fold Class checks and properties using non-exact object and array bounds
 * @requires os.arch=="amd64" | os.arch=="x86_64" | os.arch=="aarch64"
 * @library /test/lib /
 * @build jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions
 *      -XX:+WhiteBoxAPI -XX:-UseJeandleCompiler compiler.jeandle.intrinsic.TestClassBoundedQueries
 */

package compiler.jeandle.intrinsic;

import compiler.jeandle.fileCheck.FileCheck;
import java.lang.invoke.MethodHandles;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import jdk.test.lib.process.ProcessTools;
import jdk.test.whitebox.WhiteBox;

public class TestClassBoundedQueries {
    private static final WhiteBox WB = WhiteBox.getWhiteBox();
    private static final List<String> TARGETS = List.of("boundedAssignable", "helperAssignable",
            "interfaceAssignable", "excludedAssignable", "incompatibleAssignable",
            "arraySuperclass", "primitiveSuperclass", "multiSuperclass", "arrayHidden",
            "arrayModifiers", "opaqueSuperclass");

    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            exercise();
            System.out.println("Bounded Class queries passed");
            return;
        }
        Path dumps = Files.createTempDirectory("jeandle-class-bounded-queries");
        Path log = dumps.resolve("compilation.xml");
        List<String> command = new ArrayList<>(List.of("-Xbootclasspath/a:.", "-Xbatch",
                "-XX:+UnlockDiagnosticVMOptions", "-XX:+WhiteBoxAPI",
                "-XX:-BackgroundCompilation", "-XX:-TieredCompilation", "-XX:-UseOnStackReplacement",
                "-XX:CompileThreshold=1000000", "-XX:-UseTypeSpeculation",
                "-XX:+UseJeandleCompiler", "-XX:+InlineClassNatives",
                "-XX:+UseCompressedOops", "-XX:+UseCompressedClassPointers",
                "-XX:+LogCompilation", "-XX:LogFile=" + log,
                "-XX:+JeandleDumpIR", "-XX:JeandleDumpDirectory=" + dumps));
        for (String name : TARGETS) {
            command.add("-XX:CompileCommand=compileonly," + TestClassBoundedQueries.class.getName() + "::" + name);
        }
        command.add(TestClassBoundedQueries.class.getName());
        command.add("child");
        var output = ProcessTools.executeTestJava(command);
        Files.writeString(dumps.resolve("output.log"), output.getOutput());
        System.out.println("Bounded Class query IR: " + dumps);
        output.shouldHaveExitValue(0).shouldContain("Bounded Class queries passed");
        String xml = Files.readString(log);
        for (String name : TARGETS) {
            require(xml.lines().anyMatch(line -> line.contains("<nmethod")
                    && line.contains("compiler='jeandle'") && !line.contains("compile_kind='osr'")
                    && line.contains(TestClassBoundedQueries.class.getName() + " " + name + " ")),
                    "Missing ordinary Jeandle nmethod: " + name);
            output.shouldContain("SEMANTICS " + name + " compiled=true");
            output.shouldContain("NULL " + name + " compiled=true");
        }
        for (String name : List.of("boundedAssignable", "helperAssignable",
                "incompatibleAssignable", "arraySuperclass", "multiSuperclass", "arrayHidden")) {
            FileCheck after = ir(dumps, name);
            after.checkNotPattern("load atomic (i32|ptr), ptr addrspace\\(1\\)");
            after.checkNotPattern("(class_is_|class_get_|load_mirror_klass|secondary_supers)");
            after.checkPattern("icmp (eq|ne) ptr addrspace\\(1\\) %0, null");
        }
        for (String name : List.of("boundedAssignable", "helperAssignable")) {
            ir(dumps, name).checkPattern("ret i32 1");
        }
        for (String name : List.of("incompatibleAssignable", "excludedAssignable", "arrayHidden")) {
            ir(dumps, name).checkPattern("ret i32 0");
        }
        for (String name : List.of("arraySuperclass", "multiSuperclass")) {
            ir(dumps, name).checkPattern("load ptr addrspace\\(1\\), ptr @oop_handle_");
        }
        // The verifier does not establish a trusted interface parameter bound.
        for (String name : List.of("interfaceAssignable", "arrayModifiers", "opaqueSuperclass")) {
            ir(dumps, name).checkPattern("load atomic (i32|ptr), ptr addrspace\\(1\\)");
        }
    }

    private static Method method(String name) throws Exception {
        Class<?> parameter = switch (name) {
            case "boundedAssignable", "helperAssignable", "incompatibleAssignable" -> Number.class;
            case "interfaceAssignable" -> Runnable.class;
            case "arraySuperclass", "arrayHidden", "arrayModifiers" -> Object[].class;
            case "primitiveSuperclass" -> int[].class;
            case "multiSuperclass" -> Object[][].class;
            default -> Object.class;
        };
        return TestClassBoundedQueries.class.getMethod(name, parameter);
    }

    private static void compile(String name) throws Exception {
        Method method = method(name);
        WB.deoptimizeMethod(method);
        require(WB.enqueueMethodForCompilation(method, 4), "Cannot enqueue " + name);
        require(WB.isMethodCompiled(method) && WB.getMethodCompilationLevel(method) == 4,
                "Missing compiled entry: " + name);
    }

    private static void marker(String phase, String name) throws Exception {
        require(WB.isMethodCompiled(method(name)), "Unexpected fallback: " + phase + " " + name);
        System.out.println(phase + " " + name + " compiled=true uptime_ms="
                + ManagementFactory.getRuntimeMXBean().getUptime());
    }

    private static FileCheck ir(Path dumps, String name) throws Exception {
        FileCheck check = new FileCheck(dumps.toString(), method(name), true);
        check.selectFunction("define hotspotcc .*" + name);
        return check;
    }

    private static void exercise() throws Exception {
        Number[] numbers = {Integer.valueOf(1), Double.valueOf(2), Long.valueOf(3), Float.valueOf(4)};
        Runnable[] runnables = {new Task(), new OtherTask()};
        Other other = new Other();
        Object[] opaque = {"text", new int[2], new Object(), new Task()};
        Object[][] arrays;
        try (var stream = HiddenTemplate.class.getResourceAsStream("TestClassBoundedQueries$HiddenTemplate.class")) {
            Class<?> hidden = MethodHandles.lookup().defineHiddenClass(stream.readAllBytes(), true).lookupClass();
            require(hidden.isHidden(), "Expected a hidden component");
            Object[] hiddenArray = (Object[]) Array.newInstance(hidden, 2);
            require(!hiddenArray.getClass().isHidden(), "Array Class must not be hidden");
            arrays = new Object[][] {new String[2], new Integer[2], new PrivateComponent[2], hiddenArray};
        }
        for (String name : TARGETS) compile(name);
        for (String name : TARGETS) marker("SEMANTICS", name);
        for (int i = 0; i < 2_000; i++) {
            Number number = numbers[i & 3];
            Object[] array = arrays[i & 3];
            require(boundedAssignable(number) && helperAssignable(number), "Non-exact Number bound");
            require(interfaceAssignable(runnables[i & 1]), "Interface bound");
            require(!incompatibleAssignable(number), "Disjoint class bounds");
            require(!excludedAssignable(opaque[i & 3]) && !excludedAssignable(number), "Excluded Number");
            require(arraySuperclass(array) == Object.class, "Array Java superclass");
            require(primitiveSuperclass(new int[i & 1]) == Object.class, "Primitive array superclass");
            require(multiSuperclass(arrays) == Object.class, "Multidimensional array superclass");
            require(!arrayHidden(array), "Array hidden property");
            require(arrayModifiers(array) == array.getClass().getModifiers(), "Array component visibility");
            require(opaqueSuperclass(other) == Object.class, "Opaque non-array control");
            require(opaqueSuperclass(array) == Object.class, "Opaque array control");
        }
        require(!Modifier.isPublic(arrayModifiers(new PrivateComponent[0])), "Private component visibility");
        for (String name : TARGETS) {
            marker("NULL", name);
            try {
                method(name).invoke(null, new Object[] {null});
                throw new AssertionError("Expected NullPointerException: " + name);
            } catch (InvocationTargetException e) {
                require(e.getCause() instanceof NullPointerException, "Wrong null exception: " + name);
            }
        }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static boolean boundedAssignable(Number value) {
        return Number.class.isAssignableFrom(value.getClass());
    }

    public static boolean helperAssignable(Number value) {
        return Number.class.isAssignableFrom(typeOf(value));
    }

    private static Class<?> typeOf(Object value) {
        return value.getClass();
    }

    public static boolean interfaceAssignable(Runnable value) {
        return Runnable.class.isAssignableFrom(value.getClass());
    }

    public static boolean excludedAssignable(Object value) {
        if (value instanceof Number) return false;
        return Number.class.isAssignableFrom(value.getClass());
    }

    public static boolean incompatibleAssignable(Number value) {
        return Other.class.isAssignableFrom(value.getClass());
    }

    public static Class<?> arraySuperclass(Object[] value) {
        return value.getClass().getSuperclass();
    }

    public static Class<?> primitiveSuperclass(int[] value) {
        return value.getClass().getSuperclass();
    }

    public static Class<?> multiSuperclass(Object[][] value) {
        return value.getClass().getSuperclass();
    }

    public static boolean arrayHidden(Object[] value) {
        return value.getClass().isHidden();
    }

    public static int arrayModifiers(Object[] value) {
        return value.getClass().getModifiers();
    }

    public static Class<?> opaqueSuperclass(Object value) {
        return value.getClass().getSuperclass();
    }

    static class Other {}
    static class Task implements Runnable { public void run() {} }
    static class OtherTask implements Runnable { public void run() {} }
    static class HiddenTemplate {}
    private static class PrivateComponent {}
}
