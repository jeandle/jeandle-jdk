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
 * @summary Unsafe primitive-array intrinsic, nullable fallback, exceptions and initialized controls
 * @requires vm.gc.Serial & vm.gc.G1
 * @modules java.base/jdk.internal.misc java.base/jdk.internal.org.objectweb.asm
 * @library /test/lib /
 * @build compiler.jeandle.fileCheck.FileCheck jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run driver compiler.jeandle.intrinsic.TestAllocateUninitializedArray
 */

package compiler.jeandle.intrinsic;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import compiler.jeandle.fileCheck.FileCheck;
import jdk.internal.misc.Unsafe;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.MethodVisitor;
import jdk.internal.org.objectweb.asm.Opcodes;
import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;
import jdk.test.whitebox.WhiteBox;

public class TestAllocateUninitializedArray {
    public static void main(String[] args) throws Throwable {
        if (args.length != 0) {
            Workload.run();
            return;
        }
        run(true, "-XX:+UseG1GC");
        run(true, "-XX:+UseSerialGC", "-XX:-UseTLAB");
        run(true, "-XX:+UseG1GC", "-XX:-UseCompressedOops", "-XX:-UseCompressedClassPointers");
        run(false, "-XX:+UseG1GC");
    }

    private static void run(boolean enabled, String... options) throws Exception {
        Path dump = Files.createTempDirectory("jeandle_uninitialized_array_");
        var command = new ArrayList<>(List.of(
                "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
                "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
                "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED",
                "--add-exports=java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED",
                "-Xbatch", "-Xcomp", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler",
                "-XX:+UnlockDiagnosticVMOptions", "-XX:+WhiteBoxAPI", "-Xbootclasspath/a:.",
                "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR", "-XX:JeandleDumpDirectory=" + dump,
                "-XX:CompileCommand=compileonly,jdk.internal.misc.Unsafe::allocateUninitializedArray",
                "-XX:CompileCommand=inline,jdk.internal.misc.Unsafe::allocateUninitializedArray",
                "-XX:CompileCommand=compileonly," + Workload.class.getName() + "::allocateBytes",
                "-XX:CompileCommand=compileonly," + Workload.class.getName() + "::isPrimitive",
                "-XX:CompileCommand=compileonly,jdk.internal.misc.JeandleUnsafeArrayBridge*::allocate",
                "-XX:CompileCommand=compileonly," + Workload.class.getName() + "::initializedControl"));
        command.addAll(List.of(options));
        if (!enabled) {
            command.addAll(List.of("-XX:+UnlockDiagnosticVMOptions",
                    "-XX:ControlIntrinsic=-_allocateUninitializedArray"));
        }
        command.addAll(List.of(TestAllocateUninitializedArray.class.getName(), "child"));
        OutputAnalyzer output = ProcessTools.executeProcess(
                ProcessTools.createLimitedTestJavaProcessBuilder(command));
        output.shouldHaveExitValue(0).shouldContain("uninitialized arrays verified");
        if (enabled) {
            FileCheck primitive = new FileCheck(dump.toString(),
                    Workload.class.getMethod("isPrimitive", Class.class), false);
            primitive.checkPattern("call hotspotcc ptr @jeandle\\.load_mirror_klass");
            FileCheck initial = new FileCheck(dump.toString(), Unsafe.class.getMethod(
                    "allocateUninitializedArray", Class.class, int.class), false);
            initial.checkPattern("invoke hotspotcc .*@jeandle\\.new_array.*i1 true, i1 false");
            String privateIR;
            try (var files = Files.list(dump)) {
                Path path = files.filter(p -> p.getFileName().toString()
                                .startsWith("jdk_internal_misc_JeandleUnsafeArrayBridge"))
                        .filter(p -> p.toString().endsWith(".ll") && !p.toString().endsWith("_optimized.ll"))
                        .max(java.util.Comparator.comparing(Path::toString)).orElseThrow();
                privateIR = Files.readString(path);
            }
            Asserts.assertTrue(privateIR.lines().anyMatch(line ->
                    line.matches(".*invoke hotspotcc .*@jeandle\\.new_array.*i1 true, i1 false.*")));
            Asserts.assertTrue(privateIR.lines().anyMatch(line ->
                    line.contains("invoke hotspotcc") && line.contains("jdk_internal_misc_Unsafe_allocateUninitializedArray0")
                            && !line.contains("nonnull")), "private fallback must permit null");
            FileCheck folded = new FileCheck(dump.toString(),
                    Workload.class.getMethod("allocateBytes", int.class), true);
            folded.checkPattern("(call|invoke) hotspotcc token .*@llvm\\.experimental\\.gc\\.statepoint.*@new_array");
            folded.checkNotPattern("call.*@zero_heap_words_stub|@jeandle\\.zero_heap_words");
            folded.checkNotPattern("(call|invoke).*@jdk_internal_misc_Unsafe_allocateUninitializedArray");
        }
    }

    public static class Workload {
        static final Unsafe U = Unsafe.getUnsafe();
        static final MethodHandle PRIVATE_ALLOCATOR = privateAllocator();

        private static MethodHandle privateAllocator() {
            try {
                var field = MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
                field.setAccessible(true);
                var trusted = (MethodHandles.Lookup) field.get(null);
                ClassWriter writer = new ClassWriter(0);
                writer.visit(Opcodes.V17, Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                        "jdk/internal/misc/JeandleUnsafeArrayBridge", null, "java/lang/Object", null);
                MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                        "allocate", "(Ljdk/internal/misc/Unsafe;Ljava/lang/Class;I)Ljava/lang/Object;", null, null);
                method.visitCode();
                method.visitVarInsn(Opcodes.ALOAD, 0);
                method.visitVarInsn(Opcodes.ALOAD, 1);
                method.visitVarInsn(Opcodes.ILOAD, 2);
                method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "jdk/internal/misc/Unsafe",
                        "allocateUninitializedArray0", "(Ljava/lang/Class;I)Ljava/lang/Object;", false);
                method.visitInsn(Opcodes.ARETURN);
                method.visitMaxs(3, 3);
                method.visitEnd();
                writer.visitEnd();
                // A direct nestmate call reaches the intrinsic without relying on adapter inlining.
                var lookup = trusted.in(Unsafe.class).defineHiddenClass(writer.toByteArray(), true,
                        MethodHandles.Lookup.ClassOption.NESTMATE);
                return lookup.findStatic(lookup.lookupClass(), "allocate",
                        MethodType.methodType(Object.class, Unsafe.class, Class.class, int.class));
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        }

        public static Object allocatePrivate(Class<?> component, int length) throws Throwable {
            return (Object) PRIVATE_ALLOCATOR.invokeExact(U, component, length);
        }

        public static byte[] allocateBytes(int length) {
            return (byte[]) U.allocateUninitializedArray(byte.class, length);
        }

        public static int[] initializedControl(int length) {
            return new int[length];
        }

        public static boolean isPrimitive(Class<?> mirror) {
            return mirror.isPrimitive();
        }

        private static void checkIllegalArgument(Class<?> component, int length) {
            try {
                U.allocateUninitializedArray(component, length);
                throw new AssertionError("missing IllegalArgumentException");
            } catch (IllegalArgumentException expected) {
            }
        }

        static void run() throws Throwable {
            // Resolve invokeExact before requesting a compilation of its caller.
            Asserts.assertEquals(((int[]) allocatePrivate(int.class, 7)).length, 7);
            Method privateCaller = MethodHandles.reflectAs(Method.class, PRIVATE_ALLOCATOR);
            WhiteBox wb = WhiteBox.getWhiteBox();
            wb.deoptimizeMethod(privateCaller);
            Asserts.assertTrue(wb.enqueueMethodForCompilation(privateCaller, 4));
            Asserts.assertTrue(wb.isMethodCompiled(privateCaller));
            Asserts.assertEquals(((int[]) allocatePrivate(int.class, 7)).length, 7);
            Asserts.assertTrue(wb.isMethodCompiled(privateCaller));
            Class<?>[] components = {boolean.class, byte.class, char.class, short.class,
                    int.class, long.class, float.class, double.class};
            for (Class<?> component : components) {
                Asserts.assertTrue(isPrimitive(component));
                for (int length : new int[] {0, 1, 7, 15, 257, 131_072}) {
                    Object array = U.allocateUninitializedArray(component, length);
                    Asserts.assertEquals(array.getClass().componentType(), component);
                    Asserts.assertEquals(Array.getLength(array), length);
                    Object value = switch (component.getName()) {
                        case "boolean" -> true;
                        case "char" -> 'x';
                        case "byte" -> (byte) 7;
                        case "short" -> (short) 7;
                        case "int" -> 7;
                        case "long" -> 7L;
                        case "float" -> 7.0f;
                        case "double" -> 7.0;
                        default -> throw new AssertionError(component);
                    };
                    for (int i = 0; i < length; i++) {
                        Array.set(array, i, value);
                    }
                    System.gc();
                    for (int i = 0; i < length; i++) {
                        Asserts.assertEquals(Array.get(array, i), value);
                    }
                }
                checkIllegalArgument(component, -1);
            }
            for (int length : new int[] {0, 1, 1024}) {
                byte[] bytes = allocateBytes(length);
                for (int i = 0; i < length; i++) {
                    bytes[i] = (byte) i;
                }
                for (int i = 0; i < length; i++) {
                    Asserts.assertEquals(bytes[i], (byte) i);
                }
                for (int value : initializedControl(length)) {
                    Asserts.assertEquals(value, 0);
                }
            }
            Asserts.assertNull(U.allocateUninitializedArray(void.class, 0));
            Asserts.assertTrue(isPrimitive(void.class));
            Asserts.assertFalse(isPrimitive(Object.class));
            Asserts.assertFalse(isPrimitive(int[].class));
            Asserts.assertFalse(isPrimitive(Runnable.class));
            try {
                isPrimitive(null);
                throw new AssertionError("missing NullPointerException");
            } catch (NullPointerException expected) {
            }
            Asserts.assertNull(U.allocateUninitializedArray(void.class, 7));
            checkIllegalArgument(void.class, -1);
            checkIllegalArgument(null, 1);
            checkIllegalArgument(Object.class, 1);
            checkIllegalArgument(int[].class, 1);
            // The private fallback does not perform the public argument checks.
            Method implementation = Unsafe.class.getDeclaredMethod(
                    "allocateUninitializedArray0", Class.class, int.class);
            implementation.setAccessible(true);
            for (Class<?> invalid : new Class<?>[] {null, void.class, Object.class, int[].class}) {
                Asserts.assertNull(implementation.invoke(U, invalid, -1));
                Asserts.assertNull(allocatePrivate(invalid, -1));
                Asserts.assertNull(allocatePrivate(invalid, 7));
            }
            Asserts.assertTrue(wb.isMethodCompiled(privateCaller));
            Asserts.assertEquals(((int[]) allocatePrivate(int.class, 7)).length, 7);
            try {
                allocatePrivate(int.class, -1);
                throw new AssertionError("missing NegativeArraySizeException");
            } catch (NegativeArraySizeException expected) {
            }
            try {
                implementation.invoke(U, int.class, -1);
                throw new AssertionError("missing NegativeArraySizeException");
            } catch (InvocationTargetException e) {
                Asserts.assertTrue(e.getCause() instanceof NegativeArraySizeException);
            }
            System.out.println("uninitialized arrays verified");
        }
    }
}
