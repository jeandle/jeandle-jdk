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
 * @summary Instrumentation size lowering matches native size for polymorphic objects and all array kinds
 * @library /test/lib /
 * @modules java.instrument/sun.instrument
 * @build jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run driver compiler.jeandle.intrinsic.TestObjectSize
 */

package compiler.jeandle.intrinsic;

import compiler.jeandle.fileCheck.FileCheck;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;
import jdk.test.whitebox.WhiteBox;
import sun.instrument.InstrumentationImpl;

public class TestObjectSize {
    public static class Agent {
        static Instrumentation instrumentation;
        public static void premain(String arguments, Instrumentation value) { instrumentation = value; }
    }
    public static class Parent { int value; }
    public static class Child extends Parent { long a, b, c, d; }
    public static class Finalizable { protected void finalize() {} }

    public static void main(String[] args) throws Exception {
        if (args.length != 0) { exercise(); return; }
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Premain-Class", Agent.class.getName());
        Path agent = Files.createTempFile("jeandle-size-agent", ".jar");
        String resource = Agent.class.getName().replace('.', '/') + ".class";
        try (var out = new JarOutputStream(Files.newOutputStream(agent), manifest);
             var in = TestObjectSize.class.getClassLoader().getResourceAsStream(resource)) {
            out.putNextEntry(new JarEntry(resource));
            in.transferTo(out);
            out.closeEntry();
        }
        for (String mode : List.of("enabled", "disabled", "uncompressed", "aligned")) {
            Path dump = Files.createTempDirectory("jeandle-size-" + mode);
            var command = new ArrayList<>(List.of(
                    "--add-exports=java.instrument/sun.instrument=ALL-UNNAMED",
                    "-javaagent:" + agent, "-Xbootclasspath/a:.",
                    "-XX:+UnlockDiagnosticVMOptions", "-XX:+WhiteBoxAPI",
                    "-Xbatch", "-Xcomp", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler",
                    "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR", "-XX:JeandleDumpDirectory=" + dump,
                    "-XX:CompileCommand=compileonly," + TestObjectSize.class.getName() + "::size*",
                    "-XX:CompileCommand=inline,sun.instrument.InstrumentationImpl::getObjectSize"));
            if (mode.equals("disabled")) command.add("-XX:DisableIntrinsic=_getObjectSize");
            if (mode.equals("uncompressed")) command.addAll(List.of("-XX:-UseCompressedOops", "-XX:-UseCompressedClassPointers"));
            if (mode.equals("aligned")) command.add("-XX:ObjectAlignmentInBytes=16");
            command.addAll(List.of(TestObjectSize.class.getName(), "child"));
            OutputAnalyzer output = ProcessTools.executeProcess(ProcessTools.createLimitedTestJavaProcessBuilder(command));
            Files.writeString(dump.resolve("child.log"), output.getOutput());
            output.shouldHaveExitValue(0).shouldContain("Object sizes verified");
            String intrinsic = "sun.instrument.InstrumentationImpl.getObjectSize0";
            boolean parsed = output.getOutput().lines().anyMatch(line -> line.contains(intrinsic) && line.contains("is parsed as intrinsic"));
            if (parsed == mode.equals("disabled")) throw new AssertionError("Wrong object-size intrinsic mode: " + mode);
            var method = TestObjectSize.class.getMethod("size", InstrumentationImpl.class, Object.class);
            if (!mode.equals("disabled")) {
                new FileCheck(dump.toString(), method, false).checkPattern("call hotspotcc i32 @jeandle\\.layout_helper");
                new FileCheck(dump.toString(), method, true).checkNotPattern("InstrumentationImpl_getObjectSize0");
                for (String name : List.of("sizeOfFreshInstance", "sizeOfFreshArray")) {
                    var fresh = TestObjectSize.class.getMethod(name, InstrumentationImpl.class);
                    var check = new FileCheck(dump.toString(), fresh, true);
                    check.selectFunction("define hotspotcc.*\\.root");
                    check.checkNotPattern("@new_instance|@new_array|@jeandle\\.new_");
                    check.checkPatternAnywhere("ret i64");
                    check.checkPattern("ret i64 [1-9][0-9]*|phi i64.*\\[ [1-9][0-9]*,");
                }
            }
        }
    }

    public static long size(InstrumentationImpl instrumentation, Object object) {
        return instrumentation.getObjectSize(object);
    }
    public static long sizeOfFreshInstance(InstrumentationImpl instrumentation) {
        return instrumentation.getObjectSize(new Child());
    }
    public static long sizeOfFreshArray(InstrumentationImpl instrumentation) {
        return instrumentation.getObjectSize(new byte[37]);
    }
    public static long sizeParent(InstrumentationImpl instrumentation, Parent object) {
        return instrumentation.getObjectSize(object);
    }
    public static long sizeSelected(InstrumentationImpl instrumentation, Object first, Object second, boolean select) {
        return instrumentation.getObjectSize(select ? first : second);
    }
    public static long sizeLoop(InstrumentationImpl instrumentation, Object[] objects) {
        long result = 0;
        for (Object object : objects) result += instrumentation.getObjectSize(object);
        return result;
    }
    private static void exercise() {
        InstrumentationImpl instrumentation = (InstrumentationImpl) Agent.instrumentation;
        WhiteBox wb = WhiteBox.getWhiteBox();
        var inputs = new ArrayList<Object>(List.of(new Object(), new Parent(), new Child(), new Finalizable(), "text",
                int.class, String.class, Thread.class, Class.class, Child.class));
        for (int length : new int[] {0, 1, 7, 8, 37, 10000}) {
            inputs.addAll(List.of(new boolean[length], new byte[length], new char[length], new short[length],
                    new int[length], new long[length], new float[length], new double[length],
                    new Object[length], new String[length], new int[length][]));
        }
        for (Object object : inputs) {
            long expected = wb.getObjectSize(object);
            if (size(instrumentation, object) != expected) throw new AssertionError("Wrong size: " + object.getClass());
        }
        Parent parent = new Parent();
        Parent child = new Child();
        if (sizeParent(instrumentation, parent) != wb.getObjectSize(parent)
                || sizeParent(instrumentation, child) != wb.getObjectSize(child))
            throw new AssertionError("Declared parent type hid the subclass size");
        for (boolean select : new boolean[] {false, true}) {
            if (sizeSelected(instrumentation, parent, child, select) != wb.getObjectSize(select ? parent : child))
                throw new AssertionError("Wrong selected object size");
        }
        long total = 0;
        for (Object object : inputs) total += wb.getObjectSize(object);
        if (sizeLoop(instrumentation, inputs.toArray()) != total) throw new AssertionError("Wrong loop size total");
        if (sizeOfFreshInstance(instrumentation) != wb.getObjectSize(new Child())) throw new AssertionError("Wrong fresh instance size");
        if (sizeOfFreshArray(instrumentation) != wb.getObjectSize(new byte[37])) throw new AssertionError("Wrong fresh array size");
        try { size(instrumentation, null); throw new AssertionError("Null size accepted"); }
        catch (NullPointerException expected) {}
        System.out.println("Object sizes verified");
    }
}
