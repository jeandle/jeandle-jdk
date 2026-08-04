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
 * @key randomness
 * @summary Test reverseBytes lowering
 * @requires os.arch=="amd64" | os.arch=="x86_64" | os.arch=="aarch64"
 * @library /test/lib /
 * @modules java.base/jdk.internal.org.objectweb.asm
 * @build jdk.test.lib.Asserts
 * @run main/othervm compiler.jeandle.intrinsic.TestReverseBytes
 */

package compiler.jeandle.intrinsic;

import compiler.jeandle.fileCheck.FileCheck;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.MethodVisitor;
import jdk.internal.org.objectweb.asm.Opcodes;
import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestReverseBytes {
    public static void main(String[] args) throws Exception {
        String dumpPath = Files.createTempDirectory("jeandle_test_reversebytes").toString();

        ArrayList<String> commandArgs = new ArrayList<>(List.of(
                "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
                "--add-exports=java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED",
                "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dumpPath,
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::reverseBytes_int",
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::reverseBytes_long",
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::reverseBytes_char",
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::reverseBytes_short",
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::reverseBytes_char_to_int",
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::reverseBytes_short_to_int",
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::reverseBytes_char_cast_to_int",
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::reverseBytes_char_array_to_int",
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::reverseBytes_char_field_to_int",
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::reverseBytes_short_cast_to_int",
                "-XX:CompileCommand=compileonly," + RawReverseBytes.NAME + "::reverseChar",
                "-XX:CompileCommand=compileonly," + RawReverseBytes.NAME + "::reverseShort",
                TestWrapper.class.getName()));

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(commandArgs);
        OutputAnalyzer output = ProcessTools.executeCommand(pb);

        output.shouldHaveExitValue(0)
              .shouldContain("Method `static jint java.lang.Integer.reverseBytes(jint)` is parsed as intrinsic")
              .shouldContain("Method `static jlong java.lang.Long.reverseBytes(jlong)` is parsed as intrinsic")
              .shouldContain("Method `static jchar java.lang.Character.reverseBytes(jchar)` is parsed as intrinsic")
              .shouldContain("Method `static jshort java.lang.Short.reverseBytes(jshort)` is parsed as intrinsic");

        new FileCheck(dumpPath, TestWrapper.class.getMethod("reverseBytes_int", int.class), false)
                .checkPattern("llvm\\.bswap\\.i32");
        new FileCheck(dumpPath, TestWrapper.class.getMethod("reverseBytes_long", long.class), false)
                .checkPattern("llvm\\.bswap\\.i64");
        checkCharIR(dumpPath, "reverseBytes_char", char.class);
        checkCharIR(dumpPath, "reverseBytes_char_to_int", char.class);
        checkCharIR(dumpPath, "reverseBytes_char_cast_to_int", int.class);
        checkCharIR(dumpPath, "reverseBytes_char_array_to_int", char[].class, int.class);
        checkCharIR(dumpPath, "reverseBytes_char_field_to_int", TestWrapper.CharBox.class);
        checkShortIR(dumpPath, "reverseBytes_short", short.class);
        checkShortIR(dumpPath, "reverseBytes_short_to_int", short.class);
        checkShortIR(dumpPath, "reverseBytes_short_cast_to_int", int.class);

        Class<?> rawClass = new RawLoader().define(RawReverseBytes.bytes());
        FileCheck rawChar = new FileCheck(
                dumpPath, rawClass.getMethod("reverseChar", int.class), false);
        rawChar.checkPattern("llvm\\.bswap\\.i16");
        rawChar.checkPattern("zext i16 .* to i32");
        FileCheck rawShort = new FileCheck(
                dumpPath, rawClass.getMethod("reverseShort", int.class), false);
        rawShort.checkPattern("llvm\\.bswap\\.i16");
        rawShort.checkPattern("sext i16 .* to i32");
    }

    private static void checkCharIR(String dumpPath, String methodName,
                                    Class<?>... parameterTypes) throws Exception {
        FileCheck checker = new FileCheck(dumpPath, TestWrapper.class.getMethod(methodName, parameterTypes), false);
        checker.checkPattern("llvm\\.bswap\\.i16");
        checker.checkPattern("zext i16 .* to i32");
    }

    private static void checkShortIR(String dumpPath, String methodName,
                                     Class<?>... parameterTypes) throws Exception {
        FileCheck checker = new FileCheck(dumpPath, TestWrapper.class.getMethod(methodName, parameterTypes), false);
        checker.checkPattern("llvm\\.bswap\\.i16");
        checker.checkPattern("sext i16 .* to i32");
    }

    static class TestWrapper {
        static int vi  = Integer.reverseBytes(1);         // Force load Integer class
        static long vl = Long.reverseBytes(1L);           // Force load Long class
        static char vc = Character.reverseBytes((char) 1); // Force load Character class
        static short vs = Short.reverseBytes((short) 1);  // Force load Short class

        public static void main(String[] args) throws Exception {
            Random random = new Random();

            // --- Integer.reverseBytes corner cases ---
            Asserts.assertEquals(0, reverseBytes_int(0), "reverseBytes(0)");
            Asserts.assertEquals(-1, reverseBytes_int(-1), "reverseBytes(0xFFFFFFFF)");
            Asserts.assertEquals(0x78563412, reverseBytes_int(0x12345678), "reverseBytes(0x12345678)");
            Asserts.assertEquals(0xFF000000, reverseBytes_int(0x000000FF), "reverseBytes(low byte)");
            Asserts.assertEquals(0x000000FF, reverseBytes_int(0xFF000000), "reverseBytes(high byte)");
            Asserts.assertEquals(0x00000080, reverseBytes_int(Integer.MIN_VALUE), "reverseBytes(MIN_VALUE)");

            for (int i = 0; i < 1000; i++) {
                int v = random.nextInt();
                int ref = refI(v);
                Asserts.assertEquals(ref, Integer.reverseBytes(v), "reference mismatch for int " + v);
                Asserts.assertEquals(ref, reverseBytes_int(v), "reverseBytes int mismatch for " + v);
            }

            // --- Long.reverseBytes corner cases ---
            Asserts.assertEquals(0L, reverseBytes_long(0L), "reverseBytes(0L)");
            Asserts.assertEquals(-1L, reverseBytes_long(-1L), "reverseBytes(0xFFFF...L)");
            Asserts.assertEquals(0xEFCDAB8967452301L, reverseBytes_long(0x0123456789ABCDEFL),
                                 "reverseBytes(0x0123456789ABCDEFL)");
            Asserts.assertEquals(0xFF00000000000000L, reverseBytes_long(0x00000000000000FFL),
                                 "reverseBytes(low byteL)");
            Asserts.assertEquals(0x0000000000000080L, reverseBytes_long(Long.MIN_VALUE),
                                 "reverseBytes(MIN_VALUEL)");

            for (int i = 0; i < 1000; i++) {
                long v = random.nextLong();
                long ref = refL(v);
                Asserts.assertEquals(ref, Long.reverseBytes(v), "reference mismatch for long " + v);
                Asserts.assertEquals(ref, reverseBytes_long(v), "reverseBytes long mismatch for " + v);
            }

            // --- Character.reverseBytes corner cases (unsigned, zero-extended) ---
            Asserts.assertEquals((char) 0, reverseBytes_char((char) 0), "reverseBytes((char)0)");
            Asserts.assertEquals((char) 0xFFFF, reverseBytes_char((char) 0xFFFF), "reverseBytes((char)0xFFFF)");
            Asserts.assertEquals((char) 0x3412, reverseBytes_char((char) 0x1234), "reverseBytes((char)0x1234)");
            Asserts.assertEquals((char) 0xFF00, reverseBytes_char((char) 0x00FF), "reverseBytes((char)0x00FF)");
            Asserts.assertEquals((char) 0x00FF, reverseBytes_char((char) 0xFF00), "reverseBytes((char)0xFF00)");
            Asserts.assertEquals(0x00008000, reverseBytes_char_to_int((char) 0x0080),
                                 "reverseBytes char result must be zero-extended to int");
            Asserts.assertEquals(0x0000FF00, reverseBytes_char_to_int((char) 0x00FF),
                                 "reverseBytes char high byte remains unsigned");
            Asserts.assertEquals(0x0000FFFF, reverseBytes_char_to_int((char) 0xFFFF),
                                 "reverseBytes char 0xFFFF must not sign-extend");
            Asserts.assertEquals(0x00008000, reverseBytes_char_cast_to_int(0x12340080),
                                 "reverseBytes char cast must drop non-canonical high bits");
            Asserts.assertEquals(0x00008000, reverseBytes_char_array_to_int(new char[] {(char) 0x0080}, 0),
                                 "reverseBytes char array load must stay zero-extended");
            Asserts.assertEquals(0x00008000, reverseBytes_char_field_to_int(new CharBox((char) 0x0080)),
                                 "reverseBytes char field load must stay zero-extended");

            for (int i = 0; i < 1000; i++) {
                char v = (char) random.nextInt(0x10000);
                char ref = refC(v);
                Asserts.assertEquals(ref, Character.reverseBytes(v), "reference mismatch for char " + (int) v);
                Asserts.assertEquals(ref, reverseBytes_char(v), "reverseBytes char mismatch for " + (int) v);
                Asserts.assertEquals((int) ref, reverseBytes_char_to_int(v),
                                     "reverseBytes char-to-int mismatch for " + (int) v);
            }

            // --- Short.reverseBytes corner cases (signed, sign-extended) ---
            Asserts.assertEquals((short) 0, reverseBytes_short((short) 0), "reverseBytes((short)0)");
            Asserts.assertEquals((short) -1, reverseBytes_short((short) -1), "reverseBytes((short)0xFFFF)");
            Asserts.assertEquals((short) 0x3412, reverseBytes_short((short) 0x1234), "reverseBytes((short)0x1234)");
            Asserts.assertEquals((short) 0x8000, reverseBytes_short((short) 0x0080), "reverseBytes(sign flip)");
            Asserts.assertEquals((short) 0x00FF, reverseBytes_short((short) 0xFF00), "reverseBytes((short)0xFF00)");
            Asserts.assertEquals(0xFFFF8000, reverseBytes_short_to_int((short) 0x0080),
                                 "reverseBytes short result must be sign-extended to int");
            Asserts.assertEquals(0x00000080, reverseBytes_short_to_int((short) 0x8000),
                                 "reverseBytes short positive result must not stay sign-extended");
            Asserts.assertEquals(0xFFFFFFFF, reverseBytes_short_to_int((short) -1),
                                 "reverseBytes short -1 remains sign-extended");
            Asserts.assertEquals(0xFFFF8000, reverseBytes_short_cast_to_int(0x12340080),
                                 "reverseBytes short cast must sign-extend the swapped result");

            for (int i = 0; i < 1000; i++) {
                short v = (short) random.nextInt();
                short ref = refS(v);
                Asserts.assertEquals(ref, Short.reverseBytes(v), "reference mismatch for short " + v);
                Asserts.assertEquals(ref, reverseBytes_short(v), "reverseBytes short mismatch for " + v);
                Asserts.assertEquals((int) ref, reverseBytes_short_to_int(v),
                                     "reverseBytes short-to-int mismatch for " + v);
            }

            Class<?> rawClass = new RawLoader().define(RawReverseBytes.bytes());
            rawClass.getMethod("resolveChar", int.class).invoke(null, 0);
            rawClass.getMethod("resolveShort", int.class).invoke(null, 0);
            Method rawChar = rawClass.getMethod("reverseChar", int.class);
            Method rawShort = rawClass.getMethod("reverseShort", int.class);
            int[] rawValues = {0, -1, 0x1234ABCD, 0xFFFF0080, 0x80000001};
            for (int v : rawValues) {
                checkRawReverseBytes(rawChar, rawShort, v);
            }
            for (int i = 0; i < 1000; i++) {
                checkRawReverseBytes(rawChar, rawShort, random.nextInt());
            }

            System.out.println("TestReverseBytes PASSED");
        }

        private static void checkRawReverseBytes(Method rawChar, Method rawShort, int value)
                throws Exception {
            Asserts.assertEquals(refRawC(value), (int) rawChar.invoke(null, value),
                                 "raw Character.reverseBytes mismatch for " + value);
            Asserts.assertEquals(refRawS(value), (int) rawShort.invoke(null, value),
                                 "raw Short.reverseBytes mismatch for " + value);
        }

        public static int reverseBytes_int(int a) {
            return Integer.reverseBytes(a);
        }

        public static long reverseBytes_long(long a) {
            return Long.reverseBytes(a);
        }

        public static char reverseBytes_char(char a) {
            return Character.reverseBytes(a);
        }

        public static short reverseBytes_short(short a) {
            return Short.reverseBytes(a);
        }

        public static int reverseBytes_char_to_int(char a) {
            return Character.reverseBytes(a);
        }

        public static int reverseBytes_short_to_int(short a) {
            return Short.reverseBytes(a);
        }

        public static int reverseBytes_char_cast_to_int(int a) {
            return Character.reverseBytes((char) a);
        }

        public static int reverseBytes_char_array_to_int(char[] a, int i) {
            return Character.reverseBytes(a[i]);
        }

        public static int reverseBytes_char_field_to_int(CharBox box) {
            return Character.reverseBytes(box.value);
        }

        public static int reverseBytes_short_cast_to_int(int a) {
            return Short.reverseBytes((short) a);
        }

        static class CharBox {
            char value;

            CharBox(char value) {
                this.value = value;
            }
        }

        // Independent references (byte assembly), so the assertions do not lean
        // on the library method they are validating.
        static int refI(int v) {
            return ((v & 0xFF) << 24)
                 | ((v & 0xFF00) << 8)
                 | ((v >>> 8) & 0xFF00)
                 | ((v >>> 24) & 0xFF);
        }

        static long refL(long v) {
            long r = 0;
            for (int i = 0; i < 8; i++) {
                r = (r << 8) | ((v >>> (i * 8)) & 0xFF);
            }
            return r;
        }

        static char refC(char v) {
            return (char) (((v & 0xFF) << 8) | ((v >>> 8) & 0xFF));
        }

        static short refS(short v) {
            return (short) (((v & 0xFF) << 8) | ((v >>> 8) & 0xFF));
        }

        static int refRawC(int v) {
            return ((v & 0xFF) << 8) | ((v >>> 8) & 0xFF);
        }

        static int refRawS(int v) {
            return (short) refRawC(v);
        }
    }

    static final class RawLoader extends ClassLoader {
        Class<?> define(byte[] bytes) {
            return defineClass(RawReverseBytes.NAME, bytes, 0, bytes.length);
        }
    }

    static final class RawReverseBytes {
        static final String NAME = "compiler.jeandle.intrinsic.RawReverseBytes";
        private static final String INTERNAL_NAME = NAME.replace('.', '/');

        static byte[] bytes() {
            ClassWriter writer = new ClassWriter(0);
            writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                         INTERNAL_NAME, null, "java/lang/Object", null);
            addMethod(writer, "resolveChar", "java/lang/Character", "(C)C");
            addMethod(writer, "resolveShort", "java/lang/Short", "(S)S");
            addMethod(writer, "reverseChar", "java/lang/Character", "(C)C");
            addMethod(writer, "reverseShort", "java/lang/Short", "(S)S");
            writer.visitEnd();
            return writer.toByteArray();
        }

        private static void addMethod(ClassWriter writer, String name, String owner,
                                      String targetDescriptor) {
            MethodVisitor method = writer.visitMethod(
                    Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, "(I)I", null, null);
            method.visitCode();
            method.visitVarInsn(Opcodes.ILOAD, 0);
            method.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "reverseBytes",
                                   targetDescriptor, false);
            method.visitInsn(Opcodes.IRETURN);
            method.visitMaxs(1, 1);
            method.visitEnd();
        }
    }
}
