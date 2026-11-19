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
 * @summary Preserve Class query semantics while eliminating getClass mirror round trips
 * @requires os.arch=="amd64" | os.arch=="x86_64" | os.arch=="aarch64"
 * @library /test/lib /
 * @build jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions
 *      -XX:+WhiteBoxAPI -XX:-UseJeandleCompiler
 *      compiler.jeandle.intrinsic.TestClassQueryCanonicalization
 */

package compiler.jeandle.intrinsic;

import compiler.jeandle.fileCheck.FileCheck;
import java.io.Serializable;
import java.lang.invoke.MethodHandles;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import jdk.test.lib.process.ProcessTools;
import jdk.test.whitebox.WhiteBox;

public class TestClassQueryCanonicalization {
    private static final WhiteBox WB = WhiteBox.getWhiteBox();
    private static final List<String> ORIGINS = List.of("originIsArray", "originModifiers",
            "originHidden", "originSuperclass", "helperIsArray", "helperSuperclass");
    private static final List<String> TARGETS = List.of("originIsArray", "originModifiers",
            "originHidden", "originSuperclass", "helperIsArray", "helperSuperclass",
            "numberIsArray", "arrayIsArray", "objectIsArray", "serializableIsArray", "cloneableIsArray");

    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            exercise();
            System.out.println("Class query canonicalization semantics passed");
            return;
        }
        Path dumps = Files.createTempDirectory("jeandle-class-query-canonicalization");
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
            command.add("-XX:CompileCommand=compileonly," + TestClassQueryCanonicalization.class.getName() + "::" + name);
        }
        command.add(TestClassQueryCanonicalization.class.getName());
        command.add("child");
        var output = ProcessTools.executeTestJava(command);
        Files.writeString(dumps.resolve("output.log"), output.getOutput());
        System.out.println("Class query canonicalization IR: " + dumps);
        output.shouldHaveExitValue(0).shouldContain("Class query canonicalization semantics passed");
        String xml = Files.readString(log);
        for (String name : TARGETS) {
            require(xml.lines().anyMatch(line -> line.contains("<nmethod")
                    && line.contains("compiler='jeandle'") && !line.contains("compile_kind='osr'")
                    && line.contains(TestClassQueryCanonicalization.class.getName() + " " + name + " ")),
                    "Missing ordinary Jeandle nmethod: " + name);
            output.shouldContain("SEMANTICS " + name + " compiled=true");
            output.shouldContain("NULL " + name + " compiled=true");
        }
        checkIR(dumps);
    }

    private static Class<?> parameter(String name) {
        return switch (name) {
            case "numberIsArray" -> Number.class;
            case "arrayIsArray" -> Object[].class;
            case "serializableIsArray" -> Serializable.class;
            case "cloneableIsArray" -> Cloneable.class;
            default -> Object.class;
        };
    }

    private static Method method(String name) throws Exception {
        return TestClassQueryCanonicalization.class.getMethod(name, parameter(name));
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

    private static FileCheck ir(Path dumps, String name, boolean optimized) throws Exception {
        FileCheck check = new FileCheck(dumps.toString(), method(name), optimized);
        check.selectFunction("define hotspotcc .*" + name);
        return check;
    }

    private static void checkIR(Path dumps) throws Exception {
        for (String name : TARGETS) {
            if (!name.startsWith("helper")) {
                FileCheck before = ir(dumps, name, false);
                before.checkPattern("call hotspotcc .*@jeandle\\.get_class");
                before.checkPattern("call hotspotcc .*@jeandle\\.class_");
            }
            FileCheck after = ir(dumps, name, true);
            after.checkPattern("icmp (eq|ne) ptr addrspace\\(1\\) %0, null");
            after.checkPatternAnywhere("(@__llvm_deoptimize|@install_exceptional_return)");
        }
        for (String name : ORIGINS) {
            FileCheck after = ir(dumps, name, true);
            // Compressed object headers load i32; a Class::_klass round trip
            // would instead load an AS0 pointer from an AS1 Class mirror.
            after.checkPattern("load atomic i32, ptr addrspace\\(1\\)");
            after.checkNotPattern("load atomic ptr, ptr addrspace\\(1\\)");
            after.checkNotPattern("(class_is_|class_get_|load_mirror_klass|any_primitive)");
        }
        for (String name : List.of("numberIsArray", "arrayIsArray")) {
            FileCheck after = ir(dumps, name, true);
            after.checkPattern("ret i32 " + (name.equals("numberIsArray") ? 0 : 1));
            after.checkNotPattern("(load atomic|klass_addr|class_is_array)");
        }
        for (String name : List.of("objectIsArray", "serializableIsArray", "cloneableIsArray")) {
            FileCheck after = ir(dumps, name, true);
            after.checkPattern("load atomic i32, ptr addrspace\\(1\\)");
            after.checkPattern("(icmp slt i32|lshr i32 .*, 31)");
            after.checkNotPattern("ret i32 [01]$");
        }
    }

    private static void exercise() throws Exception {
        Object hidden;
        try (var bytes = TestClassQueryCanonicalization.class.getResourceAsStream(
                "TestClassQueryCanonicalization$HiddenValue.class")) {
            require(bytes != null, "Missing hidden class bytes");
            Class<?> type = MethodHandles.lookup().defineHiddenClass(bytes.readAllBytes(), true).lookupClass();
            hidden = type.getDeclaredConstructor().newInstance();
        }
        int arrayModifiers = Modifier.PUBLIC | Modifier.FINAL | Modifier.ABSTRACT;
        Sample[] samples = {
                new Sample(new Object(), false, Modifier.PUBLIC, null, false),
                new Sample("text", false, Modifier.PUBLIC | Modifier.FINAL, Object.class, false),
                new Sample(Integer.valueOf(1), false, Modifier.PUBLIC | Modifier.FINAL, Number.class, false),
                new Sample(new int[1], true, arrayModifiers, Object.class, false),
                new Sample(new String[1], true, arrayModifiers, Object.class, false),
                new Sample(new int[1][1], true, arrayModifiers, Object.class, false),
                new Sample(hidden, false, Modifier.STATIC | Modifier.FINAL, Object.class, true)
        };
        Serializable[] serializable = {"text", Integer.valueOf(2), new int[1], new String[1]};
        Cloneable[] cloneable = {new CloneValue(), new int[1], new String[1], new CloneChild()};
        boolean[] serializableArrays = {false, false, true, true};
        boolean[] cloneableArrays = {false, true, true, false};
        for (String name : TARGETS) {
            compile(name);
        }
        for (int i = 0; i < 128; i++) {
            for (Sample sample : samples) {
                Object value = sample.value();
                require(originIsArray(value) == sample.array(), "origin array: " + value);
                require(helperIsArray(value) == sample.array(), "helper array: " + value);
                require(objectIsArray(value) == sample.array(), "Object bound: " + value);
                require(originModifiers(value) == sample.modifiers(), "origin modifiers: " + value);
                require(originHidden(value) == sample.hidden(), "origin hidden: " + value);
                require(originSuperclass(value) == sample.superclass(), "origin superclass: " + value);
                require(helperSuperclass(value) == sample.superclass(), "helper superclass: " + value);
            }
            require(!numberIsArray(Integer.valueOf(i)) && !numberIsArray(Double.valueOf(i)), "Number bound");
            require(arrayIsArray(new String[0]) && arrayIsArray(new int[0][]), "Object[] bound");
            for (int j = 0; j < serializable.length; j++) {
                require(serializableIsArray(serializable[j]) == serializableArrays[j], "Serializable bound " + j);
                require(cloneableIsArray(cloneable[j]) == cloneableArrays[j], "Cloneable bound " + j);
            }
        }
        for (String name : TARGETS) {
            marker("SEMANTICS", name);
        }
        for (String name : TARGETS) {
            compile(name);
            marker("NULL", name);
            expectNull(switch (name) {
                case "originIsArray" -> () -> originIsArray(null);
                case "originModifiers" -> () -> originModifiers(null);
                case "originHidden" -> () -> originHidden(null);
                case "originSuperclass" -> () -> originSuperclass(null);
                case "helperIsArray" -> () -> helperIsArray(null);
                case "helperSuperclass" -> () -> helperSuperclass(null);
                case "numberIsArray" -> () -> numberIsArray(null);
                case "arrayIsArray" -> () -> arrayIsArray(null);
                case "objectIsArray" -> () -> objectIsArray(null);
                case "serializableIsArray" -> () -> serializableIsArray(null);
                case "cloneableIsArray" -> () -> cloneableIsArray(null);
                default -> throw new AssertionError(name);
            });
        }
    }

    private static void expectNull(Runnable action) {
        try {
            action.run();
            throw new AssertionError("NullPointerException expected");
        } catch (NullPointerException expected) {
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static boolean originIsArray(Object value) { return value.getClass().isArray(); }
    public static int originModifiers(Object value) { return value.getClass().getModifiers(); }
    public static boolean originHidden(Object value) { return value.getClass().isHidden(); }
    public static Class<?> originSuperclass(Object value) { return value.getClass().getSuperclass(); }
    public static boolean helperIsArray(Object value) { return typeOf(value).isArray(); }
    public static Class<?> helperSuperclass(Object value) { return typeOf(value).getSuperclass(); }
    private static Class<?> typeOf(Object value) { return value.getClass(); }
    public static boolean numberIsArray(Number value) { return value.getClass().isArray(); }
    public static boolean arrayIsArray(Object[] value) { return value.getClass().isArray(); }
    public static boolean objectIsArray(Object value) { return value.getClass().isArray(); }
    public static boolean serializableIsArray(Serializable value) { return value.getClass().isArray(); }
    public static boolean cloneableIsArray(Cloneable value) { return value.getClass().isArray(); }

    private record Sample(Object value, boolean array, int modifiers, Class<?> superclass, boolean hidden) {}
    static final class HiddenValue {}
    static class CloneValue implements Cloneable {}
    static final class CloneChild extends CloneValue {}
}
