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
 * @summary Verify Class query folding through object origins and cyclic mirror merges
 * @requires os.arch=="amd64" | os.arch=="x86_64" | os.arch=="aarch64"
 * @library /test/lib /
 * @run main/othervm -XX:-UseJeandleCompiler compiler.jeandle.intrinsic.TestClassQueryOptimizations
 */

package compiler.jeandle.intrinsic;

import compiler.jeandle.fileCheck.FileCheck;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import jdk.test.lib.process.ProcessTools;

public class TestClassQueryOptimizations {
    private static final List<String> TARGETS = List.of("constantInverse", "constantAssignable",
            "nonPrimitive", "nonInterface", "commonSuperclass", "differentSuperclass",
            "commonNullSuperclass", "loopProperty", "mixedLoopProperty");

    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            exercise();
            System.out.println("Class query optimizations passed");
            return;
        }
        Path dumps = Files.createTempDirectory("jeandle-class-query-optimizations");
        Path log = dumps.resolve("compilation.xml");
        List<String> command = new ArrayList<>(List.of("-Xbatch", "-XX:-TieredCompilation",
                "-XX:-UseOnStackReplacement", "-XX:CompileThreshold=100",
                "-XX:+UseJeandleCompiler", "-XX:+InlineClassNatives",
                "-XX:+UnlockDiagnosticVMOptions", "-XX:+LogCompilation", "-XX:LogFile=" + log,
                "-XX:+JeandleDumpIR", "-XX:JeandleDumpDirectory=" + dumps));
        for (String method : TARGETS) {
            command.add("-XX:CompileCommand=compileonly," + TestClassQueryOptimizations.class.getName() + "::" + method);
        }
        command.add(TestClassQueryOptimizations.class.getName());
        command.add("child");
        var output = ProcessTools.executeTestJava(command);
        Files.writeString(dumps.resolve("output.log"), output.getOutput());
        output.shouldHaveExitValue(0).shouldContain("Class query optimizations passed");
        String xml = Files.readString(log);
        for (String method : TARGETS) {
            if (xml.lines().noneMatch(line -> line.contains("<nmethod")
                    && line.contains("compiler='jeandle'") && !line.contains("compile_kind='osr'")
                    && line.contains(TestClassQueryOptimizations.class.getName() + " " + method + " "))) {
                throw new AssertionError("Missing ordinary Jeandle nmethod: " + method + "; " + dumps);
            }
        }
        checkFold(dumps, "nonPrimitive", "ret i32 0", Object.class);
        checkFold(dumps, "nonInterface", "ret i32 0", Object.class);
        checkFold(dumps, "commonNullSuperclass", "ret ptr addrspace\\(1\\) null", boolean.class);
        FileCheck common = ir(dumps, "commonSuperclass", boolean.class);
        common.checkPattern("load ptr addrspace\\(1\\), ptr @oop_handle_");
        common.checkNotPattern("(klass_super|load_mirror_klass|class_get_superclass|class\\.super)");
        common.checkNotPattern("select i1");
        FileCheck loop = ir(dumps, "loopProperty", boolean[].class, int.class);
        loop.checkPattern("ret i32 0");
        loop.checkNotPattern("load atomic ptr, ptr addrspace\\(1\\)");
        for (String method : List.of("constantAssignable", "constantInverse")) {
            FileCheck check = ir(dumps, method, method.equals("constantAssignable") ? Class.class : Object.class);
            check.checkNotPattern("(super_check_offset_addr|secondary_supers|java_mirror_offset)");
            if (method.equals("constantInverse")) {
                check.checkNotPattern("load ptr addrspace\\(1\\), ptr %");
                check.checkNotPattern("load atomic ptr, ptr addrspace\\(1\\)");
            }
            check.checkPattern("load atomic ptr");
        }
        FileCheck different = ir(dumps, "differentSuperclass", boolean.class);
        different.checkNotPattern("ret ptr addrspace\\(1\\) null");
        different.checkPattern("(select|phi|br i1)");
    }

    private static FileCheck ir(Path dumps, String method, Class<?>... parameters) throws Exception {
        FileCheck check = new FileCheck(dumps.toString(), TestClassQueryOptimizations.class.getMethod(method, parameters), true);
        check.selectFunction("define hotspotcc .*" + method);
        return check;
    }

    private static void checkFold(Path dumps, String method, String result, Class<?>... parameters) throws Exception {
        FileCheck check = ir(dumps, method, parameters);
        check.checkPattern(result);
        check.checkNotPattern("(class_is_|load_mirror_klass|klass_addr)");
    }

    private static void exercise() throws Exception {
        Class.forName(A.class.getName());
        Class.forName(B.class.getName());
        boolean[] choices = {true, false, true, false};
        Object[] objects = {"text", Integer.valueOf(1), new int[2], new A()};
        Class<?>[] types = {Integer.class, String.class, int.class, Runnable.class, int[].class, Number.class};
        for (int i = 0; i < 20_000; i++) {
            Object object = objects[i & 3];
            Class<?> type = types[i % types.length];
            require(constantInverse(object) == (object instanceof Number), "object-origin assignable");
            require(constantAssignable(type) == (type == Integer.class || type == Number.class), "primary offset");
            require(!nonPrimitive(object) && !nonInterface(object), "object-origin properties");
            require(commonSuperclass((i & 1) == 0) == Base.class, "common superclass");
            require(differentSuperclass(true) == Object.class && differentSuperclass(false) == Number.class,
                    "different superclasses");
            require(commonNullSuperclass((i & 1) == 0) == null, "common null superclass");
            require(loopProperty(choices, 17 + (i & 31)) == 0, "cyclic common property");
            require(mixedLoopProperty(choices, 17) == 16, "cyclic conflicting property");
        }
        // Exercise exceptions after compilation and before any interpreter fallback.
        expectNull(() -> nonPrimitive(null));
        expectNull(() -> nonInterface(null));
        expectNull(() -> constantInverse(null));
        expectNull(() -> constantAssignable(null));
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void expectNull(Runnable action) {
        try {
            action.run();
            throw new AssertionError("NullPointerException expected");
        } catch (NullPointerException expected) {
        }
    }

    public static boolean constantInverse(Object object) {
        return Number.class.isAssignableFrom(object.getClass());
    }

    public static boolean constantAssignable(Class<?> type) {
        return Number.class.isAssignableFrom(type);
    }

    public static boolean nonPrimitive(Object object) {
        return object.getClass().isPrimitive();
    }

    public static boolean nonInterface(Object object) {
        return object.getClass().isInterface();
    }

    public static Class<?> commonSuperclass(boolean choose) {
        return (choose ? A.class : B.class).getSuperclass();
    }

    public static Class<?> differentSuperclass(boolean choose) {
        return (choose ? String.class : Integer.class).getSuperclass();
    }

    public static Class<?> commonNullSuperclass(boolean choose) {
        return (choose ? int.class : Runnable.class).getSuperclass();
    }

    public static int loopProperty(boolean[] choices, int count) {
        Class<?> type = String.class;
        int total = 0;
        for (int i = 0; i < count; i++) {
            type = choices[i & 3] ? type : Integer.class;
            total += type.isPrimitive() ? 1 : 0;
        }
        return total;
    }

    public static int mixedLoopProperty(boolean[] choices, int count) {
        Class<?> type = String.class;
        int total = 0;
        for (int i = 0; i < count; i++) {
            type = choices[i & 3] ? type : int.class;
            total += type.isPrimitive() ? 1 : 0;
        }
        return total;
    }

    static class Base {}
    static class A extends Base {}
    static class B extends Base {}
}
