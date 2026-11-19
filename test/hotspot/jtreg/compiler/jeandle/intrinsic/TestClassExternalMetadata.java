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
 * @summary Folded Class properties keep external Klass metadata alive in an active compiled frame
 * @requires os.arch=="amd64" | os.arch=="x86_64" | os.arch=="aarch64"
 * @modules java.base/jdk.internal.misc
 * @library /test/lib /
 * @build jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *      -XX:-UseJeandleCompiler compiler.jeandle.intrinsic.TestClassExternalMetadata
 */

package compiler.jeandle.intrinsic;

import compiler.jeandle.fileCheck.FileCheck;
import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import jdk.internal.misc.Unsafe;
import jdk.test.whitebox.WhiteBox;
import jdk.test.lib.process.ProcessTools;

public class TestClassExternalMetadata {
    private static final WhiteBox WB = WhiteBox.getWhiteBox();
    private static final Unsafe UNSAFE = Unsafe.getUnsafe();
    private static final String TARGET = TestClassExternalMetadata.class.getName() + "$Payload";
    private static Class<?> pending;
    private static boolean collect;
    private static boolean checkedActiveFrame;
    private static Method rootMethod;

    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            exercise(args[0]);
            return;
        }
        for (String kind : List.of("custom", "hidden")) {
            Path dumps = Files.createTempDirectory("jeandle-external-metadata-" + kind);
            Path log = dumps.resolve("compilation.xml");
            var output = ProcessTools.executeTestJava("-Xbootclasspath/a:.",
                    "-XX:+UnlockDiagnosticVMOptions", "-XX:+WhiteBoxAPI", "-XX:+ClassUnloading",
                    "-XX:-BackgroundCompilation", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler",
                    "-XX:+InlineClassNatives", "-XX:+LogCompilation", "-XX:LogFile=" + log,
                    "-XX:CompileCommand=compileonly," + TestClassExternalMetadata.class.getName() + "::query",
                    "-XX:CompileCommand=dontinline," + TestClassExternalMetadata.class.getName() + "::collectAndCheck",
                    "-XX:+JeandleDumpIR", "-XX:JeandleDumpDirectory=" + dumps,
                    "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
                    TestClassExternalMetadata.class.getName(), kind);
            Files.writeString(dumps.resolve("output.log"), output.getOutput());
            output.shouldHaveExitValue(0).shouldContain("External metadata lifecycle passed: " + kind);
            if (Files.readString(log).lines().noneMatch(line -> line.contains("<nmethod")
                    && line.contains("compiler='jeandle'")
                    && line.contains(TestClassExternalMetadata.class.getName() + " query "))) {
                throw new AssertionError("Missing Jeandle nmethod; " + dumps);
            }
            Method method = TestClassExternalMetadata.class.getMethod("query", WeakReference.class);
            FileCheck parsed = new FileCheck(dumps.toString(), method, false, 0);
            parsed.selectFunction("define hotspotcc .*query");
            parsed.checkPattern("call hotspotcc i32 @jeandle\\.class_get_modifiers");
            FileCheck optimized = new FileCheck(dumps.toString(), method, true);
            optimized.selectFunction("define hotspotcc .*query");
            optimized.checkNotPattern("(class_get_modifiers|klass_modifiers|load_mirror_klass)");
            optimized.checkNotPattern("load ptr addrspace\\(1\\), ptr @oop_handle_");
            optimized.checkPattern("phi i32 .*\\[ 9,");
        }
    }

    private static void exercise(String kind) throws Exception {
        rootMethod = TestClassExternalMetadata.class.getMethod("query", WeakReference.class);
        WeakReference<Class<?>> target = prepare(kind);
        query(target);
        if (!WB.enqueueMethodForCompilation(rootMethod, 4) || !WB.isMethodCompiled(rootMethod)) {
            throw new AssertionError("Query did not compile");
        }
        // Remove the source field's strong root after constant folding. The
        // query's scalar result is unaffected by the deliberately changed final
        // field; this tests nmethod metadata rather than final-field semantics.
        var field = Holder.class.getDeclaredField("TYPE");
        UNSAFE.putReferenceVolatile(UNSAFE.staticFieldBase(field), UNSAFE.staticFieldOffset(field), null);
        collect = true;
        if (query(target) != 9 || !checkedActiveFrame || !WB.isMethodCompiled(rootMethod)) {
            throw new AssertionError("Compiled property query was not executed");
        }
        WB.deoptimizeMethod(rootMethod);
        for (int i = 0; i < 40; i++) {
            WB.fullGC();
            if (target.get() == null) {
                System.out.println("External metadata lifecycle passed: " + kind);
                return;
            }
        }
        throw new AssertionError("External class remained alive after query deoptimization");
    }

    private static WeakReference<Class<?>> prepare(String kind) throws Exception {
        byte[] bytes;
        try (InputStream in = TestClassExternalMetadata.class.getResourceAsStream("TestClassExternalMetadata$Payload.class")) {
            bytes = Objects.requireNonNull(in).readAllBytes();
        }
        Class<?> target;
        if (kind.equals("hidden")) {
            target = MethodHandles.lookup().defineHiddenClass(bytes, true).lookupClass();
        } else {
            target = new ClassLoader(TestClassExternalMetadata.class.getClassLoader()) {
                Class<?> define() { return defineClass(TARGET, bytes, 0, bytes.length); }
            }.define();
        }
        pending = target;
        if (Holder.TYPE != target || target.getModifiers() != 9) {
            throw new AssertionError("Unexpected payload");
        }
        pending = null;
        return new WeakReference<>(target);
    }

    public static int query(WeakReference<Class<?>> target) {
        int modifiers = Holder.TYPE.getModifiers();
        collectAndCheck(target);
        return modifiers;
    }

    private static void collectAndCheck(WeakReference<Class<?>> target) {
        if (!collect) return;
        WB.fullGC();
        if (target.get() == null || !WB.isMethodCompiled(rootMethod)) {
            throw new AssertionError("External Klass not kept alive by active query frame");
        }
        checkedActiveFrame = true;
    }

    private static class Holder {
        static final Class<?> TYPE = pending;
    }

    public static class Payload {}
}
