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
 * @summary Profile devirtualization must distinguish same-named methods from
 *          different class loaders
 * @library /test/lib /
 * @build compiler.jeandle.fileCheck.FileCheck
 * @modules java.base/jdk.internal.org.objectweb.asm
 * @run main/othervm -Xbatch -XX:-TieredCompilation -XX:+UseJeandleCompiler
 *                   -XX:+JeandleUseProfiledVirtualCallDevirtualization
 *                   -XX:-Inline -XX:+JeandleDumpIR
 *                   -XX:CompileCommand=quiet
 *                   -XX:CompileCommand=compileonly,compiler.jeandle.TestProfileDevirtualizationClassLoader::profiledCall
 *                   compiler.jeandle.TestProfileDevirtualizationClassLoader
 */

package compiler.jeandle;

import compiler.jeandle.fileCheck.FileCheck;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.MethodVisitor;

import static jdk.internal.org.objectweb.asm.Opcodes.ACC_FINAL;
import static jdk.internal.org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static jdk.internal.org.objectweb.asm.Opcodes.ALOAD;
import static jdk.internal.org.objectweb.asm.Opcodes.ICONST_1;
import static jdk.internal.org.objectweb.asm.Opcodes.ICONST_2;
import static jdk.internal.org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static jdk.internal.org.objectweb.asm.Opcodes.IRETURN;
import static jdk.internal.org.objectweb.asm.Opcodes.RETURN;
import static jdk.internal.org.objectweb.asm.Opcodes.V17;

public class TestProfileDevirtualizationClassLoader {
    private static final String TARGET_NAME =
            "compiler.jeandle.generated.ProfileTarget";
    private static final String TARGET_INTERNAL_NAME =
            TARGET_NAME.replace('.', '/');
    private static final String VALUE_INTERNAL_NAME =
            Value.class.getName().replace('.', '/');

    public interface Value {
        int value();
    }

    private static final class ByteArrayLoader extends ClassLoader {
        private final byte[] targetBytes;

        ByteArrayLoader(byte[] targetBytes) {
            super(TestProfileDevirtualizationClassLoader.class.getClassLoader());
            this.targetBytes = targetBytes;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (!TARGET_NAME.equals(name)) {
                throw new ClassNotFoundException(name);
            }
            return defineClass(name, targetBytes, 0, targetBytes.length);
        }
    }

    private static int profiledCall(Value receiver) {
        return receiver.value();
    }

    public static void main(String[] args) throws Exception {
        Value first = newTarget(1);
        Value second = newTarget(2);

        if (first.getClass() == second.getClass() ||
                first.getClass().getClassLoader() ==
                        second.getClass().getClassLoader()) {
            throw new AssertionError("test requires two defining class loaders");
        }

        int iterations = 20_000_000;
        long actual = 0;
        for (int i = 0; i < iterations; i++) {
            actual += profiledCall((i & 1) == 0 ? first : second);
        }

        long expected = (long) iterations / 2 * 3;
        if (actual != expected) {
            throw new AssertionError("wrong result: expected=" + expected +
                    ", actual=" + actual);
        }

        FileCheck fileCheck = new FileCheck(System.getProperty("user.dir"),
                TestProfileDevirtualizationClassLoader.class.getDeclaredMethod(
                        "profiledCall", Value.class), true);
        String targetPattern =
                "declare hotspotcc i32 .*ProfileTarget_value.*\\.[0-9]+";
        fileCheck.checkPattern(targetPattern);
        fileCheck.checkPattern(targetPattern);
        fileCheck.checkNot("__jeandle_dynamic_call.");
    }

    private static Value newTarget(int result) throws Exception {
        Class<?> target = Class.forName(TARGET_NAME, true,
                new ByteArrayLoader(generateTarget(result)));
        return (Value) target.getDeclaredConstructor().newInstance();
    }

    private static byte[] generateTarget(int result) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS |
                                             ClassWriter.COMPUTE_FRAMES);
        writer.visit(V17, ACC_PUBLIC | ACC_FINAL, TARGET_INTERNAL_NAME, null,
                "java/lang/Object", new String[] { VALUE_INTERNAL_NAME });

        MethodVisitor init = writer.visitMethod(ACC_PUBLIC, "<init>", "()V",
                null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>",
                "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        MethodVisitor value = writer.visitMethod(ACC_PUBLIC, "value", "()I",
                null, null);
        value.visitCode();
        value.visitInsn(result == 1 ? ICONST_1 : ICONST_2);
        value.visitInsn(IRETURN);
        value.visitMaxs(0, 0);
        value.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }
}
