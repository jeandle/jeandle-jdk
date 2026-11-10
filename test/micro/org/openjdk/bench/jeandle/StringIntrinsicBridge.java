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
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 */
package org.openjdk.bench.jeandle;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;

import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.Label;
import jdk.internal.org.objectweb.asm.MethodVisitor;
import jdk.internal.org.objectweb.asm.Opcodes;
import jdk.internal.org.objectweb.asm.Type;

/** Creates direct intrinsic callers during benchmark initialization. */
final class StringIntrinsicBridge {
    private static final String NAME = "java.lang.JeandleStringIntrinsicCaller";
    private static final Class<?> TYPE = load();

    private static Class<?> load() {
        try {
            try {
                return Class.forName(NAME, true, null);
            } catch (ClassNotFoundException absent) {
                return MethodHandles.privateLookupIn(String.class, MethodHandles.lookup())
                        .defineClass(bridgeBytes());
            }
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static byte[] bridgeBytes() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                NAME.replace('.', '/'), null, "java/lang/Object", null);
        add(writer, "equalsL", "StringLatin1", "equals", boolean.class, byte[].class, byte[].class);
        add(writer, "equalsU", "StringUTF16", "equals", boolean.class, byte[].class, byte[].class);
        add(writer, "compareToL", "StringLatin1", "compareTo", int.class, byte[].class, byte[].class);
        add(writer, "compareToU", "StringUTF16", "compareTo", int.class, byte[].class, byte[].class);
        add(writer, "compareToLU", "StringLatin1", "compareToUTF16", int.class, byte[].class, byte[].class);
        add(writer, "compareToUL", "StringUTF16", "compareToLatin1", int.class, byte[].class, byte[].class);
        add(writer, "indexOfL", "StringLatin1", "indexOf", int.class, byte[].class, byte[].class);
        add(writer, "indexOfU", "StringUTF16", "indexOf", int.class, byte[].class, byte[].class);
        add(writer, "indexOfUL", "StringUTF16", "indexOfLatin1", int.class, byte[].class, byte[].class);
        add(writer, "indexOfIL", "StringLatin1", "indexOf", int.class,
                byte[].class, int.class, byte[].class, int.class, int.class);
        add(writer, "indexOfIU", "StringUTF16", "indexOf", int.class,
                byte[].class, int.class, byte[].class, int.class, int.class);
        add(writer, "indexOfIUL", "StringUTF16", "indexOfLatin1", int.class,
                byte[].class, int.class, byte[].class, int.class, int.class);
        add(writer, "toBytesStringU", "StringUTF16", "toBytes", byte[].class,
                char[].class, int.class, int.class);
        add(writer, "getCharsStringU", "StringUTF16", "getChars", void.class,
                byte[].class, int.class, int.class, char[].class, int.class);
        add(writer, "compressStringC", "StringUTF16", "compress", int.class,
                char[].class, int.class, byte[].class, int.class, int.class);
        add(writer, "compressStringB", "StringUTF16", "compress", int.class,
                byte[].class, int.class, byte[].class, int.class, int.class);
        add(writer, "inflateStringC", "StringLatin1", "inflate", void.class,
                byte[].class, int.class, char[].class, int.class, int.class);
        add(writer, "inflateStringB", "StringLatin1", "inflate", void.class,
                byte[].class, int.class, byte[].class, int.class, int.class);
        add(writer, "equalsKnown16", "StringLatin1", "equals", boolean.class, byte[].class, byte[].class);
        add(writer, "compareKnown16", "StringLatin1", "compareTo", int.class, byte[].class, byte[].class);

        // Hidden nestmates require a lookup retaining java.base's module access.
        MethodVisitor lookup = writer.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                "lookup", "()Ljava/lang/invoke/MethodHandles$Lookup;", null, null);
        lookup.visitCode();
        lookup.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/invoke/MethodHandles", "lookup",
                "()Ljava/lang/invoke/MethodHandles$Lookup;", false);
        lookup.visitInsn(Opcodes.ARETURN);
        lookup.visitMaxs(0, 0);
        lookup.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void add(ClassWriter writer, String wrapper, String holder, String target,
                            Class<?> result, Class<?>... args) {
        String descriptor = MethodType.methodType(result, args).toMethodDescriptorString();
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                wrapper, descriptor, null, null);
        method.visitCode();
        if (wrapper.equals("equalsKnown16") || wrapper.equals("compareKnown16")) {
            Label fail = new Label();
            Label ready = new Label();
            method.visitVarInsn(Opcodes.ALOAD, 0);
            method.visitInsn(Opcodes.ARRAYLENGTH);
            method.visitIntInsn(Opcodes.BIPUSH, 16);
            method.visitJumpInsn(Opcodes.IF_ICMPNE, fail);
            method.visitVarInsn(Opcodes.ALOAD, 1);
            method.visitInsn(Opcodes.ARRAYLENGTH);
            method.visitIntInsn(Opcodes.BIPUSH, 16);
            method.visitJumpInsn(Opcodes.IF_ICMPEQ, ready);
            method.visitLabel(fail);
            method.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalArgumentException");
            method.visitInsn(Opcodes.DUP);
            method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IllegalArgumentException",
                    "<init>", "()V", false);
            method.visitInsn(Opcodes.ATHROW);
            method.visitLabel(ready);
        }
        int slot = 0;
        for (Class<?> arg : args) {
            Type type = Type.getType(arg);
            method.visitVarInsn(type.getOpcode(Opcodes.ILOAD), slot);
            slot += type.getSize();
        }
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/" + holder, target, descriptor, false);
        method.visitInsn(Type.getType(result).getOpcode(Opcodes.IRETURN));
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static MethodHandle charSearch(String holder, MethodType type) throws ReflectiveOperationException {
        String descriptor = "([BIII)I";
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                "java/lang/Jeandle" + holder + "CharBridge", null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "indexOfChar", descriptor, null, null);
        method.visitAnnotation("Ljdk/internal/vm/annotation/ForceInline;", true).visitEnd();
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        for (int slot = 1; slot <= 3; slot++) {
            method.visitVarInsn(Opcodes.ILOAD, slot);
        }
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/" + holder,
                "indexOfChar", descriptor, false);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();

        Method bootstrapLookup = TYPE.getDeclaredMethod("lookup");
        bootstrapLookup.setAccessible(true);
        MethodHandles.Lookup lookup = (MethodHandles.Lookup) bootstrapLookup.invoke(null);
        Class<?> owner = Class.forName("java.lang." + holder);
        MethodHandles.Lookup bridge = MethodHandles.privateLookupIn(owner, lookup)
                .defineHiddenClass(writer.toByteArray(), true, MethodHandles.Lookup.ClassOption.NESTMATE);
        return bridge.findStatic(bridge.lookupClass(), "indexOfChar", type);
    }

    static MethodHandle find(String name, Class<?> result, Class<?>... args) {
        try {
            MethodType type = MethodType.methodType(result, args);
            try {
                return MethodHandles.publicLookup().findStatic(TYPE, name, type);
            } catch (NoSuchMethodException absent) {
                return switch (name) {
                    case "indexOfL_char" -> charSearch("StringLatin1", type);
                    case "indexOfU_char" -> charSearch("StringUTF16", type);
                    default -> throw absent;
                };
            }
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
