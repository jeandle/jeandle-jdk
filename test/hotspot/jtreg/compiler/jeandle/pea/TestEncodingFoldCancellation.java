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
 * @summary A cancelled encoding fold retains source stores before the revived call
 * @library /test/lib /
 * @modules java.base/jdk.internal.org.objectweb.asm
 * @build jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler compiler.jeandle.pea.TestEncodingFoldCancellation
 */
package compiler.jeandle.pea;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.Label;
import jdk.internal.org.objectweb.asm.MethodVisitor;
import static jdk.internal.org.objectweb.asm.Opcodes.*;
import jdk.test.lib.Asserts;
import jdk.test.lib.process.ProcessTools;
import jdk.test.whitebox.WhiteBox;

public class TestEncodingFoldCancellation {
    private static final String PROBE = "java/lang/JeandleEncodingCancellation";
    private static final String HOLDER = "java/lang/JeandleEncodingHolder";

    public static void main(String[] args) throws Exception {
        Path patch = Files.createTempDirectory(Path.of("."), "encoding-cancellation");
        generate(patch);
        for (boolean pea : new boolean[]{true, false}) {
            var options = new ArrayList<>(List.of(
                    "--patch-module=java.base=" + patch.toAbsolutePath(),
                    "-Xbootclasspath/a:.", "-XX:+UnlockDiagnosticVMOptions", "-XX:+WhiteBoxAPI",
                    "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler",
                    pea ? "-XX:+JeandleDoPEA" : "-XX:-JeandleDoPEA",
                    "-XX:CompileCommand=compileonly,java.lang.JeandleEncodingCancellation::test",
                    "-XX:JeandleLLVMOptions=-jeandle-dump-pea-ir=JeandleEncodingCancellation",
                    Child.class.getName()));
            var output = ProcessTools.executeCommand(
                    ProcessTools.createLimitedTestJavaProcessBuilder(options));
            output.shouldHaveExitValue(0).shouldContain("ENCODING-CANCELLATION:level=4:sum=1950000");
            if (pea) {
                output.shouldContain("PEA-DUMP before").shouldContain("PEA-DUMP after")
                        .shouldContain("@jeandle.encode_array");
            }
        }
    }

    private static void generate(Path patch) throws Exception {
        ClassWriter holder = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        holder.visit(V17, ACC_PUBLIC, HOLDER, null, "java/lang/Object", null);
        holder.visitField(ACC_PUBLIC, "n", "I", null, null).visitEnd();
        MethodVisitor m = holder.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        holder.visitEnd();
        write(patch, HOLDER, holder.toByteArray());

        ClassWriter probe = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        probe.visit(V17, ACC_PUBLIC, PROBE, null, "java/lang/Object", null);
        probe.visitField(ACC_PUBLIC | ACC_STATIC, "published", "[C", null, null).visitEnd();
        probe.visitField(ACC_PUBLIC | ACC_STATIC, "holderPublished", "L" + HOLDER + ";", null, null).visitEnd();
        probe.visitField(ACC_PUBLIC | ACC_STATIC | ACC_VOLATILE, "state", "I", null, null).visitEnd();
        m = probe.visitMethod(ACC_PUBLIC | ACC_STATIC, "test", "(ZI)I", null, null);
        m.visitCode();
        m.visitInsn(ICONST_2);
        m.visitIntInsn(NEWARRAY, T_CHAR);
        m.visitVarInsn(ASTORE, 2);
        m.visitTypeInsn(NEW, HOLDER);
        m.visitInsn(DUP);
        m.visitMethodInsn(INVOKESPECIAL, HOLDER, "<init>", "()V", false);
        m.visitVarInsn(ASTORE, 3);
        m.visitInsn(ICONST_4);
        m.visitIntInsn(NEWARRAY, T_BYTE);
        m.visitVarInsn(ASTORE, 4);
        m.visitVarInsn(ALOAD, 2);
        m.visitInsn(ICONST_0);
        m.visitIntInsn(BIPUSH, 65);
        m.visitInsn(CASTORE);
        m.visitVarInsn(ALOAD, 3);
        m.visitInsn(ICONST_1);
        m.visitFieldInsn(PUTFIELD, HOLDER, "n", "I");
        m.visitMethodInsn(INVOKESTATIC, "java/lang/invoke/VarHandle", "fullFence", "()V", false);
        m.visitVarInsn(ALOAD, 3);
        m.visitFieldInsn(GETFIELD, HOLDER, "n", "I");
        m.visitVarInsn(ISTORE, 5);
        m.visitVarInsn(ALOAD, 3);
        m.visitFieldInsn(PUTSTATIC, PROBE, "holderPublished", "L" + HOLDER + ";");
        Label fallback = new Label();
        Label left = new Label();
        Label right = new Label();
        m.visitVarInsn(ILOAD, 5);
        m.visitInsn(ICONST_1);
        m.visitJumpInsn(IF_ICMPLT, fallback);
        m.visitVarInsn(ILOAD, 5);
        m.visitInsn(ICONST_2);
        m.visitJumpInsn(IF_ICMPGT, fallback);
        m.visitVarInsn(ALOAD, 2);
        m.visitInsn(ICONST_0);
        m.visitVarInsn(ALOAD, 4);
        m.visitInsn(ICONST_0);
        m.visitVarInsn(ILOAD, 5);
        m.visitMethodInsn(INVOKESTATIC, "java/lang/StringCoding", "implEncodeAsciiArray", "([CI[BII)I", false);
        m.visitInsn(POP);
        m.visitVarInsn(ALOAD, 2);
        m.visitFieldInsn(PUTSTATIC, PROBE, "published", "[C");
        // Two loop entries make destination eligibility depend on a later CFG visit.
        m.visitVarInsn(ILOAD, 0);
        m.visitJumpInsn(IFNE, left);
        m.visitJumpInsn(GOTO, right);
        m.visitLabel(left);
        m.visitInsn(ICONST_1);
        m.visitFieldInsn(PUTSTATIC, PROBE, "state", "I");
        m.visitJumpInsn(GOTO, right);
        m.visitLabel(right);
        m.visitInsn(ICONST_2);
        m.visitFieldInsn(PUTSTATIC, PROBE, "state", "I");
        m.visitVarInsn(ILOAD, 1);
        m.visitIincInsn(1, -1);
        m.visitJumpInsn(IFGT, left);
        m.visitVarInsn(ALOAD, 4);
        m.visitInsn(ICONST_0);
        m.visitInsn(BALOAD);
        m.visitInsn(IRETURN);
        m.visitLabel(fallback);
        m.visitInsn(ICONST_M1);
        m.visitInsn(IRETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        probe.visitEnd();
        write(patch, PROBE, probe.toByteArray());
    }

    private static void write(Path patch, String name, byte[] bytes) throws Exception {
        Path file = patch.resolve(name + ".class");
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
    }

    public static class Child {
        public static void main(String[] args) throws Exception {
            Method target = Class.forName(PROBE.replace('/', '.')).getMethod("test", boolean.class, int.class);
            long sum = 0;
            for (int i = 0; i < 30000; i++) {
                int result = (Integer) target.invoke(null, (i & 1) != 0, i & 1);
                Asserts.assertEquals(result, 65, "iteration " + i);
                sum += result;
            }
            WhiteBox wb = WhiteBox.getWhiteBox();
            Asserts.assertEquals(wb.getMethodCompilationLevel(target), 4, "compiled target remains installed");
            System.out.println("ENCODING-CANCELLATION:level=4:sum=" + sum);
        }
    }
}
