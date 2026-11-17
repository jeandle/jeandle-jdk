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
 * @summary Verify exact Class checks, dynamic object origins and subtype guard edges
 * @requires os.arch=="amd64" | os.arch=="x86_64" | os.arch=="aarch64"
 * @library /test/lib /
 * @run main/othervm -XX:-UseJeandleCompiler compiler.jeandle.intrinsic.TestClassCheckOptimizations
 */

package compiler.jeandle.intrinsic;

import compiler.jeandle.fileCheck.FileCheck;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jdk.test.lib.process.ProcessTools;

public class TestClassCheckOptimizations {
    private static final List<String> TARGETS = List.of("finalInstance", "primitiveArrayInstance",
            "referenceArrayInstance", "primitiveMultiArrayInstance", "covariantArrayInstance",
            "originDirect", "originHelper", "originOrdered", "dynamicClassEquals",
            "untypedMirrorEquals", "impossibleInterface", "impossibleAbstract",
            "exactEq", "exactNe", "exactFailureEdge", "dynamicAssignable");
    private static int observations;

    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            exercise();
            System.out.println("Class check optimizations passed");
            return;
        }
        Path dumps = Files.createTempDirectory("jeandle-class-check-optimizations");
        Path log = dumps.resolve("compilation.xml");
        List<String> command = new ArrayList<>(List.of("-Xbatch", "-XX:-TieredCompilation",
                "-XX:-UseOnStackReplacement", "-XX:CompileThreshold=100",
                "-XX:+UseJeandleCompiler", "-XX:+InlineClassNatives",
                "-XX:+UnlockDiagnosticVMOptions", "-XX:+LogCompilation", "-XX:LogFile=" + log,
                "-XX:+JeandleDumpIR", "-XX:JeandleDumpDirectory=" + dumps));
        for (String method : TARGETS) {
            command.add("-XX:CompileCommand=compileonly," + TestClassCheckOptimizations.class.getName() + "::" + method);
        }
        command.add(TestClassCheckOptimizations.class.getName());
        command.add("child");
        var output = ProcessTools.executeTestJava(command);
        Files.writeString(dumps.resolve("output.log"), output.getOutput());
        output.shouldHaveExitValue(0).shouldContain("Class check optimizations passed");
        String xml = Files.readString(log);
        for (String method : TARGETS) {
            if (xml.lines().noneMatch(line -> line.contains("<nmethod")
                    && line.contains("compiler='jeandle'") && !line.contains("compile_kind='osr'")
                    && line.contains(TestClassCheckOptimizations.class.getName() + " " + method + " "))) {
                throw new AssertionError("Missing ordinary Jeandle nmethod: " + method + "; " + dumps);
            }
        }
        System.out.println("Class check IR: " + dumps);
        for (String method : List.of("finalInstance", "primitiveArrayInstance",
                "referenceArrayInstance", "primitiveMultiArrayInstance")) {
            FileCheck before = ir(dumps, method, false, Object.class);
            before.checkPattern("call hotspotcc i32 @jeandle\\.instanceof");
            FileCheck after = ir(dumps, method, true, Object.class);
            after.checkPattern("icmp (eq|ne) (i32|i64|ptr) ");
            after.checkNotPattern("(super_check|secondary_supers|check_primary_supers)");
        }
        // Object[] accepts arrays whose element class is a subclass of Object.
        FileCheck covariant = ir(dumps, "covariantArrayInstance", true, Object.class);
        covariant.checkPattern("(super_check|secondary_supers)");
        for (String method : List.of("originDirect", "originHelper")) {
            FileCheck before = ir(dumps, method, false, Class.class, Object.class);
            before.checkPattern("class\\.assignable\\.same_mirror");
            FileCheck after = ir(dumps, method, true, Class.class, Object.class);
            after.checkPattern("(super_check|secondary_supers)");
            after.checkNotPattern("class\\.assignable\\.same_mirror");
            checkOriginMirrorLoads(dumps, method);
        }
        FileCheck mirror = ir(dumps, "dynamicClassEquals", true, Class.class, Object.class);
        mirror.checkPattern("icmp (eq|ne) (i32|i64|ptr) ");
        mirror.checkNotPattern("load ptr addrspace\\(1\\), ptr %");
        FileCheck untyped = ir(dumps, "untypedMirrorEquals", true, Object.class, Object.class);
        untyped.checkPattern("load ptr addrspace\\(1\\), ptr %");
        untyped.checkPattern("icmp eq ptr addrspace\\(1\\) ");
        for (String method : List.of("exactEq", "exactNe")) {
            FileCheck exact = ir(dumps, method, true, Object.class);
            exact.checkPattern("class\\.mirror\\.klass_eq");
            exact.checkNotPattern("(class_is_instance|super_check|secondary_supers)");
        }
        for (String method : List.of("impossibleInterface", "impossibleAbstract")) {
            FileCheck impossible = ir(dumps, method, true, Object.class);
            impossible.checkPattern("ret i32 0");
            impossible.checkNotPattern("load atomic i32, ptr addrspace\\(1\\)");
        }
        FileCheck failure = ir(dumps, "exactFailureEdge", true, Object.class);
        failure.checkPattern("(super_check|secondary_supers)");
    }

    private static FileCheck ir(Path dumps, String method, boolean optimized, Class<?>... parameters) throws Exception {
        FileCheck check = new FileCheck(dumps.toString(), TestClassCheckOptimizations.class.getMethod(method, parameters), optimized);
        check.selectFunction("define hotspotcc .*" + method);
        return check;
    }

    private static void checkOriginMirrorLoads(Path dumps, String method) throws Exception {
        String prefix = TestClassCheckOptimizations.class.getName().replace('.', '_') + "_" + method + "_";
        List<Path> files;
        try (var paths = Files.list(dumps)) {
            files = paths.filter(path -> path.getFileName().toString().startsWith(prefix)
                    && path.getFileName().toString().endsWith("_optimized.ll")).sorted().toList();
        }
        require(!files.isEmpty(), "Missing optimized IR for " + method);
        String module = Files.readString(files.getLast());
        Matcher definition = Pattern.compile("(?m)^define hotspotcc [^\\r\\n]*" + method
                + "[^\\r\\n]*\\{\\R([\\s\\S]*?)^}").matcher(module);
        require(definition.find(), "Missing optimized function: " + method);
        String function = definition.group(1);
        Matcher cold = Pattern.compile("(?m)^bci_5_null_check_fail:[^\\r\\n]*\\R"
                + "((?:[ \\t]+[^\\r\\n]*\\R)+)").matcher(function);
        require(cold.find(), "Missing receiver-null deopt block: " + method);
        String deopt = cold.group(1);
        require(deopt.contains("@__llvm_deoptimize") && deopt.stripTrailing().endsWith("unreachable"),
                "Mirror materialization must terminate in deopt: " + method);
        Pattern mirrorLoad = Pattern.compile("(%[^\\s]+) = load(?: atomic)? ptr addrspace\\(1\\), ptr %");
        Matcher mirrors = mirrorLoad.matcher(deopt);
        require(mirrors.find(), "Missing evaluated getClass argument for deopt: " + method);
        Matcher state = Pattern.compile("\"deopt\"\\(([^\\r\\n]*?)\\)(?:, \"| \\])").matcher(deopt);
        require(state.find(), "Missing deopt state: " + method);
        do {
            String value = mirrors.group(1);
            require(Pattern.compile("ptr addrspace\\(1\\) " + Pattern.quote(value) + "(?:,|$)")
                    .matcher(state.group(1)).find(), "Mirror is not retained in deopt state: " + method);
        } while (mirrors.find());
        // The already evaluated getClass argument is needed only when the
        // subsequent isAssignableFrom receiver is null and must deoptimize.
        String normal = function.substring(0, cold.start()) + function.substring(cold.end());
        require(!mirrorLoad.matcher(normal).find(), "Mirror materialized on a normal path: " + method);
        Matcher nullBranch = Pattern.compile("(?m)^[ \\t]*(%[^\\s]+) = icmp eq ptr addrspace\\(1\\) %0, null\\R"
                + "[ \\t]*br i1 \\1, label %bci_5_null_check_fail, label %").matcher(function);
        require(nullBranch.find(), "Mirror deopt is not guarded by the null receiver: " + method);
        Matcher incoming = Pattern.compile("label %bci_5_null_check_fail(?:,|\\s)").matcher(function);
        require(incoming.find() && !incoming.find(), "Unexpected incoming mirror path: " + method);
    }

    private static void exercise() throws Exception {
        Object[] values = {new FinalValue(), new Object(), null, new int[2], new long[2],
                new String[2], new Object[2], new int[2][1], new Base(), new Child(), new Deep9(), "text"};
        Class<?>[] targets = {FinalValue.class, Object.class, int.class, void.class, int[].class,
                Object[].class, String[].class, Marker.class, Missing.class, Base.class, Deep4.class, Cloneable.class};
        Class<?>[] sources = {FinalValue.class, Object.class, int.class, void.class, int[].class,
                int[][].class, String[].class, Child.class, Deep9.class, Deep4.class, Marker.class, Missing.class};
        // Load subclasses before compiling the equality guards.
        Class.forName(Child.class.getName());
        Class.forName(Deep9.class.getName());
        for (int i = 0; i < 12_000; i++) {
            int index = i % values.length;
            Object value = values[index];
            require(finalInstance(value) == (value instanceof FinalValue), "final class");
            require(primitiveArrayInstance(value) == (value instanceof int[]), "primitive array");
            require(referenceArrayInstance(value) == (value instanceof String[]), "final element array");
            require(primitiveMultiArrayInstance(value) == (value instanceof int[][]), "primitive multi array");
            require(covariantArrayInstance(value) == (value instanceof Object[]), "covariant Object[]");
            Class<?> target = targets[(i / values.length) % targets.length];
            if (value != null) {
                boolean expected = target.isInstance(value);
                require(originDirect(target, value) == expected, "direct dynamic object origin");
                require(originHelper(target, value) == expected, "helper dynamic object origin");
                require(originOrdered(target, value) == expected, "ordered dynamic object origin");
                require(dynamicClassEquals(target, value) == (value.getClass() == target), "dynamic mirror equality");
                Object mirror = switch (i & 3) {
                    case 0 -> value.getClass();
                    case 1 -> "not a mirror";
                    case 2 -> new Object[0];
                    default -> null;
                };
                require(untypedMirrorEquals(mirror, value) == (mirror == value.getClass()), "untyped mirror equality");
                require(!impossibleInterface(value) && !impossibleAbstract(value), "impossible runtime class");
                int exact = value.getClass() == Base.class ? 2 : 3;
                require(exactEq(value) == exact && exactNe(value) == exact, "exact success edge");
                int failure = value.getClass() == Base.class ? 0 : (value instanceof Base ? 1 : 2);
                require(exactFailureEdge(value) == failure, "exact failure edge preserves subclasses");
            }
            Class<?> source = sources[index];
            require(dynamicAssignable(target, source) == target.isAssignableFrom(source), "dynamic assignable matrix");
        }
        require(covariantArrayInstance(new String[1]), "String[] is an Object[]");
        require(covariantArrayInstance(new int[1][]), "int[][] is an Object[]");
        require(!covariantArrayInstance(new int[1]), "int[] is not an Object[]");
        require(exactFailureEdge(new Child()) == 1, "subclass on equality failure edge");
        require(dynamicAssignable(Marker.class, Deep9.class), "deep secondary hit");
        require(!dynamicAssignable(Missing.class, Deep9.class), "deep secondary miss");
        require(dynamicAssignable(Deep4.class, Deep9.class), "deep primary hit");
        require(!dynamicAssignable(Deep9.class, Deep4.class), "deep primary miss");
        require(dynamicAssignable(Object[].class, String[].class), "reference covariance");
        require(dynamicAssignable(Cloneable.class, int[].class), "array interface");
        require(dynamicAssignable(int.class, int.class), "primitive identity");
        require(!dynamicAssignable(int.class, long.class), "primitive mismatch");
        // Check exceptional arguments after ordinary Jeandle compilation.
        for (Class<?> target : new Class<?>[] {FinalValue.class, int.class, void.class, null}) {
            expectNull(() -> originDirect(target, null), "originDirect");
            expectNull(() -> originHelper(target, null), "typeOf");
            observations = 0;
            expectNull(() -> originOrdered(target, null), "observeClass");
            require(observations == 1, "getClass argument evaluated before null receiver");
        }
        observations = 0;
        expectNull(() -> originOrdered(null, new Object()), "originOrdered");
        require(observations == 1, "argument evaluated for null receiver");
        expectNull(() -> originDirect(null, new Object()), "originDirect");
        expectNull(() -> originHelper(null, new Object()), "originHelper");
        expectNull(() -> dynamicAssignable(null, Object.class), "dynamicAssignable");
        expectNull(() -> dynamicAssignable(Object.class, null), "isAssignableFrom");
        expectNull(() -> impossibleInterface(null), "impossibleInterface");
        expectNull(() -> impossibleAbstract(null), "impossibleAbstract");
        expectNull(() -> exactEq(null), "exactEq");
        expectNull(() -> exactNe(null), "exactNe");
        require(!dynamicClassEquals(null, null), "null target short circuit");
        require(!dynamicClassEquals(null, new Object()), "null Class target");
        expectNull(() -> dynamicClassEquals(Object.class, null), "dynamicClassEquals");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void expectNull(Runnable action, String frame) {
        try {
            action.run();
            throw new AssertionError("NullPointerException expected: " + frame);
        } catch (NullPointerException expected) {
            require(expected.getStackTrace().length != 0
                    && expected.getStackTrace()[0].getMethodName().equals(frame),
                    "wrong null exception order: " + expected);
        }
    }

    public static boolean finalInstance(Object object) {
        return FinalValue.class.isInstance(object);
    }

    public static boolean primitiveArrayInstance(Object object) {
        return int[].class.isInstance(object);
    }

    public static boolean referenceArrayInstance(Object object) {
        return String[].class.isInstance(object);
    }

    public static boolean primitiveMultiArrayInstance(Object object) {
        return int[][].class.isInstance(object);
    }

    public static boolean covariantArrayInstance(Object object) {
        return Object[].class.isInstance(object);
    }

    public static boolean originDirect(Class<?> target, Object object) {
        return target.isAssignableFrom(object.getClass());
    }

    public static boolean originHelper(Class<?> target, Object object) {
        return target.isAssignableFrom(typeOf(object));
    }

    private static Class<?> typeOf(Object object) {
        return object.getClass();
    }

    public static boolean originOrdered(Class<?> target, Object object) {
        return target.isAssignableFrom(observeClass(object));
    }

    private static Class<?> observeClass(Object object) {
        observations++;
        return object.getClass();
    }

    public static boolean dynamicClassEquals(Class<?> target, Object object) {
        return target != null && object.getClass() == target;
    }

    public static boolean untypedMirrorEquals(Object mirror, Object object) {
        return object.getClass() == mirror;
    }

    public static boolean impossibleInterface(Object object) {
        return object.getClass() == Marker.class;
    }

    public static boolean impossibleAbstract(Object object) {
        return AbstractBase.class == object.getClass();
    }

    public static int exactEq(Object object) {
        if (object.getClass() == Base.class) {
            return Child.class.isInstance(object) ? 1 : 2;
        }
        return 3;
    }

    public static int exactNe(Object object) {
        if (Base.class != object.getClass()) {
            return 3;
        }
        return Child.class.isInstance(object) ? 1 : 2;
    }

    public static int exactFailureEdge(Object object) {
        if (object.getClass() == Base.class) {
            return 0;
        }
        return Base.class.isInstance(object) ? 1 : 2;
    }

    public static boolean dynamicAssignable(Class<?> target, Class<?> source) {
        return target.isAssignableFrom(source);
    }

    interface Marker {}
    interface Missing {}
    static final class FinalValue {}
    abstract static class AbstractBase {}
    static class Base {}
    static class Child extends Base {}
    static class Deep1 extends Base implements Marker {}
    static class Deep2 extends Deep1 {}
    static class Deep3 extends Deep2 {}
    static class Deep4 extends Deep3 {}
    static class Deep5 extends Deep4 {}
    static class Deep6 extends Deep5 {}
    static class Deep7 extends Deep6 {}
    static class Deep8 extends Deep7 {}
    static class Deep9 extends Deep8 {}
}
