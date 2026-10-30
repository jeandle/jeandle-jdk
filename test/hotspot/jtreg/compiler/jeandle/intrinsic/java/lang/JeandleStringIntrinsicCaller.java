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

package java.lang;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.MethodVisitor;
import jdk.internal.org.objectweb.asm.Opcodes;

/** Direct entry points for the VM's string intrinsic signatures. */
public final class JeandleStringIntrinsicCaller {
    private JeandleStringIntrinsicCaller() {}

    private static final MethodHandle LATIN_CHAR = charSearch(StringLatin1.class);
    private static final MethodHandle UTF_CHAR = charSearch(StringUTF16.class);

    private static MethodHandle charSearch(Class<?> holder) {
        try {
            String owner = holder.getName().replace('.', '/');
            String name = "java/lang/Jeandle" + holder.getSimpleName() + "CharBridge";
            String descriptor = "([BIII)I";
            ClassWriter writer = new ClassWriter(0);
            writer.visit(Opcodes.V17, Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, name, null,
                    "java/lang/Object", null);
            MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                    "indexOfChar", descriptor, null, null);
            method.visitAnnotation("Ljdk/internal/vm/annotation/ForceInline;", true).visitEnd();
            method.visitCode();
            method.visitVarInsn(Opcodes.ALOAD, 0);
            method.visitVarInsn(Opcodes.ILOAD, 1);
            method.visitVarInsn(Opcodes.ILOAD, 2);
            method.visitVarInsn(Opcodes.ILOAD, 3);
            method.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "indexOfChar", descriptor, false);
            method.visitInsn(Opcodes.IRETURN);
            method.visitMaxs(4, 4);
            method.visitEnd();
            writer.visitEnd();
            // A nestmate can call the exact private helper with ordinary bytecode.
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(holder, MethodHandles.lookup())
                    .defineHiddenClass(writer.toByteArray(), true, MethodHandles.Lookup.ClassOption.NESTMATE);
            return lookup.findStatic(lookup.lookupClass(), "indexOfChar",
                    MethodType.methodType(int.class, byte[].class, int.class, int.class, int.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public static int compareToL(byte[] a, byte[] b) {
        return StringLatin1.compareTo(a, b);
    }

    public static int compareToU(byte[] a, byte[] b) {
        return StringUTF16.compareTo(a, b);
    }

    public static int compareToLU(byte[] a, byte[] b) {
        return StringLatin1.compareToUTF16(a, b);
    }

    public static int compareToUL(byte[] a, byte[] b) {
        return StringUTF16.compareToLatin1(a, b);
    }

    public static int indexOfL(byte[] a, byte[] b) {
        return StringLatin1.indexOf(a, b);
    }

    public static int indexOfU(byte[] a, byte[] b) {
        return StringUTF16.indexOf(a, b);
    }

    public static int indexOfUL(byte[] a, byte[] b) {
        return StringUTF16.indexOfLatin1(a, b);
    }

    public static int indexOfIL(byte[] a, int n, byte[] b, int m, int from) {
        return StringLatin1.indexOf(a, n, b, m, from);
    }

    public static int indexOfIU(byte[] a, int n, byte[] b, int m, int from) {
        return StringUTF16.indexOf(a, n, b, m, from);
    }

    public static int indexOfIUL(byte[] a, int n, byte[] b, int m, int from) {
        return StringUTF16.indexOfLatin1(a, n, b, m, from);
    }

    public static int indexOfL_char(byte[] a, int ch, int from, int max) throws Throwable {
        return (int) LATIN_CHAR.invokeExact(a, ch, from, max);
    }

    public static int indexOfU_char(byte[] a, int ch, int from, int max) throws Throwable {
        return (int) UTF_CHAR.invokeExact(a, ch, from, max);
    }

    public static boolean equalsL(byte[] a, byte[] b) {
        return StringLatin1.equals(a, b);
    }

    public static boolean equalsU(byte[] a, byte[] b) {
        return StringUTF16.equals(a, b);
    }

    public static byte[] toBytesStringU(char[] src, int off, int n) {
        return StringUTF16.toBytes(src, off, n);
    }

    public static void getCharsStringU(byte[] src, int begin, int end, char[] dst, int off) {
        StringUTF16.getChars(src, begin, end, dst, off);
    }

    public static int compressStringC(char[] src, int off, byte[] dst, int dstOff, int n) {
        return StringUTF16.compress(src, off, dst, dstOff, n);
    }

    public static int compressStringB(byte[] src, int off, byte[] dst, int dstOff, int n) {
        return StringUTF16.compress(src, off, dst, dstOff, n);
    }

    public static void inflateStringC(byte[] src, int off, char[] dst, int dstOff, int n) {
        StringLatin1.inflate(src, off, dst, dstOff, n);
    }

    public static void inflateStringB(byte[] src, int off, byte[] dst, int dstOff, int n) {
        StringLatin1.inflate(src, off, dst, dstOff, n);
    }

}
