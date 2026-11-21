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

package java.lang;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.MethodVisitor;
import jdk.internal.org.objectweb.asm.Opcodes;

/** Direct calls to the character and encoding intrinsic signatures. */
public final class JeandleCharacterIntrinsicCaller {
    private static final MethodHandle ISO = isoBridge();

    private static MethodHandle isoBridge() {
        try {
            Class<?> holder = Class.forName("sun.nio.cs.ISO_8859_1$Encoder");
            String descriptor = "([CI[BII)I";
            ClassWriter writer = new ClassWriter(0);
            writer.visit(Opcodes.V17, Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                    "sun/nio/cs/JeandleISOEncoderBridge", null, "java/lang/Object", null);
            MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                    "encode", descriptor, null, null);
            method.visitAnnotation("Ljdk/internal/vm/annotation/ForceInline;", true).visitEnd();
            method.visitCode();
            method.visitVarInsn(Opcodes.ALOAD, 0);
            method.visitVarInsn(Opcodes.ILOAD, 1);
            method.visitVarInsn(Opcodes.ALOAD, 2);
            method.visitVarInsn(Opcodes.ILOAD, 3);
            method.visitVarInsn(Opcodes.ILOAD, 4);
            method.visitMethodInsn(Opcodes.INVOKESTATIC, "sun/nio/cs/ISO_8859_1$Encoder",
                    "implEncodeISOArray", descriptor, false);
            method.visitInsn(Opcodes.IRETURN);
            method.visitMaxs(5, 5);
            method.visitEnd();
            writer.visitEnd();
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(holder, MethodHandles.lookup())
                    .defineHiddenClass(writer.toByteArray(), true, MethodHandles.Lookup.ClassOption.NESTMATE);
            return lookup.findStatic(lookup.lookupClass(), "encode",
                    MethodType.methodType(int.class, char[].class, int.class, byte[].class, int.class, int.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public static int encodeISO(char[] src, int sp, byte[] dst, int dp, int n) throws Throwable {
        return (int) ISO.invokeExact(src, sp, dst, dp, n);
    }

    public static int encodeISOBytes(byte[] src, int sp, byte[] dst, int dp, int n) {
        return StringCoding.implEncodeISOArray(src, sp, dst, dp, n);
    }

    @jdk.internal.vm.annotation.ForceInline
    public static int encodeASCII(char[] src, int sp, byte[] dst, int dp, int n) {
        return StringCoding.implEncodeAsciiArray(src, sp, dst, dp, n);
    }

    @jdk.internal.vm.annotation.ForceInline
    public static int countPositives(byte[] src, int off, int n) {
        return StringCoding.countPositives(src, off, n);
    }

    public static boolean isDigit(int ch) {
        return CharacterDataLatin1.instance.isDigit(ch);
    }

    public static boolean isLowerCase(int ch) {
        return CharacterDataLatin1.instance.isLowerCase(ch);
    }

    public static boolean isUpperCase(int ch) {
        return CharacterDataLatin1.instance.isUpperCase(ch);
    }

    public static boolean isWhitespace(int ch) {
        return CharacterDataLatin1.instance.isWhitespace(ch);
    }

    public static int classify(int ch) {
        return (CharacterDataLatin1.instance.isDigit(ch) ? 1 : 0)
                + (CharacterDataLatin1.instance.isLowerCase(ch) ? 2 : 0)
                + (CharacterDataLatin1.instance.isUpperCase(ch) ? 4 : 0)
                + (CharacterDataLatin1.instance.isWhitespace(ch) ? 8 : 0);
    }

    public static int publicText(String value, int mode) {
        java.nio.charset.Charset charset = switch (mode) {
            case 0 -> java.nio.charset.StandardCharsets.ISO_8859_1;
            case 1 -> java.nio.charset.StandardCharsets.US_ASCII;
            default -> java.nio.charset.StandardCharsets.UTF_8;
        };
        byte[] encoded = value.getBytes(charset);
        String decoded = new String(encoded, charset);
        int result = java.util.Arrays.hashCode(encoded) ^ decoded.hashCode();
        for (int i = 0; i < value.length(); i++) {
            int c = value.charAt(i);
            result += (Character.isDigit(c) ? 1 : 0) + (Character.isLowerCase(c) ? 2 : 0)
                    + (Character.isUpperCase(c) ? 4 : 0) + (Character.isWhitespace(c) ? 8 : 0);
        }
        return result;
    }

    public static boolean nullReceiver(int ch) {
        CharacterDataLatin1 receiver = null;
        return receiver.isDigit(ch);
    }
}
