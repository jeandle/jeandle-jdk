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

import java.lang.invoke.MethodHandles;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.MethodVisitor;
import static jdk.internal.org.objectweb.asm.Opcodes.*;

public class JeandleJvmtiHelper {
    public static Class<?> defineProbes() throws Exception {
        Class.forName("java.lang.VirtualThread", true, null);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        String owner = "java/lang/VirtualThread";
        writer.visit(V17, ACC_PUBLIC, "java/lang/JeandleJvmtiProbe", null, "java/lang/Object", null);
        for (String name : new String[] {"Start", "End", "Mount", "Unmount", "HideFrames"}) {
            boolean hasHide = !name.equals("Start") && !name.equals("End");
            String descriptor = "(Ljava/lang/VirtualThread;" + (hasHide ? "Z" : "") + ")V";
            MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "probe" + name, descriptor, null, null);
            method.visitCode();
            method.visitVarInsn(ALOAD, 0);
            if (hasHide) method.visitVarInsn(ILOAD, 1);
            method.visitMethodInsn(INVOKEVIRTUAL, owner, "notifyJvmti" + name, hasHide ? "(Z)V" : "()V", false);
            method.visitInsn(RETURN);
            method.visitMaxs(0, 0);
            method.visitEnd();
        }
        writer.visitEnd();
        return MethodHandles.privateLookupIn(VirtualThread.class, MethodHandles.lookup())
                .defineHiddenClass(writer.toByteArray(), true, MethodHandles.Lookup.ClassOption.NESTMATE).lookupClass();
    }
}
