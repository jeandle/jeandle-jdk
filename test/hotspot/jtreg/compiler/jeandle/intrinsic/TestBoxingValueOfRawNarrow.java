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
 * 2 along with this work; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 */

/*
 * @test id=semantics
 * @summary Exercise Jeandle boxing valueOf intrinsics with arbitrary JVM int values at narrow descriptors.
 * @library /test/lib /
 * @modules java.base/jdk.internal.org.objectweb.asm
 * @build jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -Xbatch -XX:-TieredCompilation -XX:+UnlockDiagnosticVMOptions
 *      -Xbootclasspath/a:. -XX:+WhiteBoxAPI
 *      -XX:+UseJeandleCompiler -Xcomp -Xlog:jeandle=debug
 *      -XX:CompileCommand=compileonly,compiler.jeandle.intrinsic.RawNarrowBooleanValueOfCall::apply
 *      -XX:CompileCommand=compileonly,compiler.jeandle.intrinsic.RawNarrowBooleanValueOfCall::isCached
 *      -XX:CompileCommand=compileonly,compiler.jeandle.intrinsic.RawNarrowByteValueOfCall::apply
 *      -XX:CompileCommand=compileonly,compiler.jeandle.intrinsic.RawNarrowByteValueOfCall::isCached
 *      -XX:CompileCommand=compileonly,compiler.jeandle.intrinsic.RawNarrowCharacterValueOfCall::apply
 *      -XX:CompileCommand=compileonly,compiler.jeandle.intrinsic.RawNarrowCharacterValueOfCall::isCached
 *      -XX:CompileCommand=compileonly,compiler.jeandle.intrinsic.RawNarrowShortValueOfCall::apply
 *      -XX:CompileCommand=compileonly,compiler.jeandle.intrinsic.RawNarrowShortValueOfCall::isCached
 *      -XX:CompileCommand=compileonly,compiler.jeandle.intrinsic.RawNarrowByteValueOfCall::deoptBox
 *      -XX:CompileCommand=compileonly,compiler.jeandle.intrinsic.RawNarrowCharacterValueOfCall::deoptBox
 *      -XX:CompileCommand=compileonly,compiler.jeandle.intrinsic.RawNarrowShortValueOfCall::deoptBox
 *      -XX:CompileCommand=exclude,compiler.jeandle.intrinsic.RawNarrowBooleanValueOfCall::resolve
 *      -XX:CompileCommand=exclude,compiler.jeandle.intrinsic.RawNarrowByteValueOfCall::resolve
 *      -XX:CompileCommand=exclude,compiler.jeandle.intrinsic.RawNarrowCharacterValueOfCall::resolve
 *      -XX:CompileCommand=exclude,compiler.jeandle.intrinsic.RawNarrowShortValueOfCall::resolve
 *      compiler.jeandle.intrinsic.TestBoxingValueOfRawNarrow
 * @run main/othervm -Xbatch -XX:-TieredCompilation -XX:+UnlockDiagnosticVMOptions
 *      -Xbootclasspath/a:. -XX:+WhiteBoxAPI
 *      -XX:+UseJeandleCompiler -Xcomp -Xlog:jeandle=debug
 *      -XX:-UseCompressedOops -XX:-UseCompressedClassPointers
 *      -XX:CompileCommand=compileonly,compiler.jeandle.intrinsic.RawNarrowBooleanValueOfCall::apply
 *      -XX:CompileCommand=compileonly,compiler.jeandle.intrinsic.RawNarrowBooleanValueOfCall::isCached
 *      -XX:CompileCommand=compileonly,compiler.jeandle.intrinsic.RawNarrowByteValueOfCall::apply
 *      -XX:CompileCommand=compileonly,compiler.jeandle.intrinsic.RawNarrowByteValueOfCall::isCached
 *      -XX:CompileCommand=compileonly,compiler.jeandle.intrinsic.RawNarrowCharacterValueOfCall::apply
 *      -XX:CompileCommand=compileonly,compiler.jeandle.intrinsic.RawNarrowCharacterValueOfCall::isCached
 *      -XX:CompileCommand=compileonly,compiler.jeandle.intrinsic.RawNarrowShortValueOfCall::apply
 *      -XX:CompileCommand=compileonly,compiler.jeandle.intrinsic.RawNarrowShortValueOfCall::isCached
 *      -XX:CompileCommand=compileonly,compiler.jeandle.intrinsic.RawNarrowByteValueOfCall::deoptBox
 *      -XX:CompileCommand=compileonly,compiler.jeandle.intrinsic.RawNarrowCharacterValueOfCall::deoptBox
 *      -XX:CompileCommand=compileonly,compiler.jeandle.intrinsic.RawNarrowShortValueOfCall::deoptBox
 *      -XX:CompileCommand=exclude,compiler.jeandle.intrinsic.RawNarrowBooleanValueOfCall::resolve
 *      -XX:CompileCommand=exclude,compiler.jeandle.intrinsic.RawNarrowByteValueOfCall::resolve
 *      -XX:CompileCommand=exclude,compiler.jeandle.intrinsic.RawNarrowCharacterValueOfCall::resolve
 *      -XX:CompileCommand=exclude,compiler.jeandle.intrinsic.RawNarrowShortValueOfCall::resolve
 *      compiler.jeandle.intrinsic.TestBoxingValueOfRawNarrow
 */

/*
 * @test id=allocation-deopt
 * @summary Verify narrow valueOf recovery when deoptimization occurs inside allocation.
 * @requires vm.debug == true & vm.compiler2.enabled
 * @library /test/lib /
 * @modules java.base/jdk.internal.org.objectweb.asm
 * @build jdk.test.whitebox.WhiteBox
 * @run driver compiler.jeandle.intrinsic.TestBoxingValueOfRawNarrow allocation-deopt-driver
 */

package compiler.jeandle.intrinsic;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Pattern;

import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.MethodVisitor;
import jdk.internal.org.objectweb.asm.Opcodes;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;
import jdk.test.whitebox.WhiteBox;

public class TestBoxingValueOfRawNarrow implements Opcodes {
    private static final String TEST_CLASS =
            "compiler.jeandle.intrinsic.TestBoxingValueOfRawNarrow";
    private static final String GENERATED_CLASS_PREFIX =
            "compiler.jeandle.intrinsic.RawNarrow";
    private static final int ALLOCATION_DEOPT_BOX_COUNT = 16;
    private static WhiteBox WB;
    private static final int BOOLEAN_NORMALIZATION = -1;
    private static final int[] BOUNDARIES = {
            0, 1, -1, Byte.MIN_VALUE, Byte.MAX_VALUE,
            Short.MIN_VALUE, Short.MAX_VALUE, Character.MAX_VALUE,
            0x0000_007f, 0x0000_0080, 0x0000_00ff, 0x0000_0100,
            -0x0000_0100, 0x0000_ff80,
            0x0001_0000, 0x0001_0001, 0x1234_5678,
            0x7fff_0000, 0xffff_0000, Integer.MIN_VALUE, Integer.MAX_VALUE
    };
    private static volatile boolean activeDeoptRequested;
    private static volatile Method deoptTarget;

    public static void main(String[] args) throws Throwable {
        if (args.length != 0) {
            switch (args[0]) {
                case "allocation-deopt-driver" -> runAllocationDeoptDriver();
                case "allocation-deopt-child" -> runAllocationDeoptChild();
                default -> throw new IllegalArgumentException("unknown mode: " + args[0]);
            }
            return;
        }

        WB = WhiteBox.getWhiteBox();
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        Class<?> booleanClass = lookup.defineClass(generate(
                "compiler/jeandle/intrinsic/RawNarrowBooleanValueOfCall",
                "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;",
                "booleanValue", "()Z", BOOLEAN_NORMALIZATION));
        Class<?> byteClass = lookup.defineClass(generate(
                "compiler/jeandle/intrinsic/RawNarrowByteValueOfCall",
                "java/lang/Byte", "valueOf", "(B)Ljava/lang/Byte;",
                "byteValue", "()B", I2B));
        Class<?> characterClass = lookup.defineClass(generate(
                "compiler/jeandle/intrinsic/RawNarrowCharacterValueOfCall",
                "java/lang/Character", "valueOf", "(C)Ljava/lang/Character;",
                "charValue", "()C", I2C));
        Class<?> shortClass = lookup.defineClass(generate(
                "compiler/jeandle/intrinsic/RawNarrowShortValueOfCall",
                "java/lang/Short", "valueOf", "(S)Ljava/lang/Short;",
                "shortValue", "()S", I2S));

        MethodHandle booleanCall = lookup.findStatic(booleanClass, "apply",
                MethodType.methodType(int.class, int.class));
        MethodHandle booleanIsCached = lookup.findStatic(booleanClass, "isCached",
                MethodType.methodType(int.class, int.class));
        MethodHandle byteCall = lookup.findStatic(byteClass, "apply",
                MethodType.methodType(int.class, int.class));
        MethodHandle byteIsCached = lookup.findStatic(byteClass, "isCached",
                MethodType.methodType(int.class, int.class));
        MethodHandle characterCall = lookup.findStatic(characterClass, "apply",
                MethodType.methodType(int.class, int.class));
        MethodHandle characterIsCached = lookup.findStatic(characterClass, "isCached",
                MethodType.methodType(int.class, int.class));
        MethodHandle shortCall = lookup.findStatic(shortClass, "apply",
                MethodType.methodType(int.class, int.class));
        MethodHandle shortIsCached = lookup.findStatic(shortClass, "isCached",
                MethodType.methodType(int.class, int.class));
        MethodHandle byteDeoptBox = lookup.findStatic(byteClass, "deoptBox",
                MethodType.methodType(Object.class, int.class));
        MethodHandle characterDeoptBox = lookup.findStatic(characterClass, "deoptBox",
                MethodType.methodType(Object.class, int.class));
        MethodHandle shortDeoptBox = lookup.findStatic(shortClass, "deoptBox",
                MethodType.methodType(Object.class, int.class));

        lookup.findStatic(booleanClass, "resolve", MethodType.methodType(void.class)).invokeExact();
        lookup.findStatic(byteClass, "resolve", MethodType.methodType(void.class)).invokeExact();
        lookup.findStatic(characterClass, "resolve", MethodType.methodType(void.class)).invokeExact();
        lookup.findStatic(shortClass, "resolve", MethodType.methodType(void.class)).invokeExact();

        checkByteDeopt(byteDeoptBox,
                byteClass.getMethod("deoptBox", int.class));
        checkCharacterDeopt(characterDeoptBox,
                characterClass.getMethod("deoptBox", int.class));
        checkShortDeopt(shortDeoptBox,
                shortClass.getMethod("deoptBox", int.class));

        for (int value : BOUNDARIES) {
            checkBoolean(booleanCall, booleanIsCached, value);
            checkByte(byteCall, byteIsCached, value);
            checkCharacter(characterCall, characterIsCached, value);
            checkShort(shortCall, shortIsCached, value);
        }

        for (int round = 0; round < 20_000; round++) {
            int value = BOUNDARIES[round % BOUNDARIES.length] ^ round * 0x9e37_79b9;
            checkBoolean(booleanCall, booleanIsCached, value);
            checkByte(byteCall, byteIsCached, value);
            checkCharacter(characterCall, characterIsCached, value);
            checkShort(shortCall, shortIsCached, value);
        }

        Random random = new Random(0x5eed_cafeL);
        for (int index = 0; index < 50_000; index++) {
            int value = random.nextInt();
            checkBoolean(booleanCall, booleanIsCached, value);
            checkByte(byteCall, byteIsCached, value);
            checkCharacter(characterCall, characterIsCached, value);
            checkShort(shortCall, shortIsCached, value);
        }
    }

    private static void runAllocationDeoptDriver() throws Exception {
        ArrayList<String> options = new ArrayList<>(List.of(
                "-Xbatch", "-Xcomp", "-XX:-TieredCompilation",
                "-XX:-BackgroundCompilation", "-XX:+UseJeandleCompiler",
                "--add-exports=java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED",
                "-XX:+UnlockDiagnosticVMOptions", "-XX:-UseTLAB",
                "-XX:+DeoptimizeALot", "-XX:DeoptimizeALotInterval=0",
                "-XX:+TraceDeoptimization", "-XX:-CreateCoredumpOnCrash",
                "-XX:CompileCommand=compileonly," + GENERATED_CLASS_PREFIX
                        + "CharacterValueOfCall::allocationDeoptBox",
                "-XX:CompileCommand=compileonly," + GENERATED_CLASS_PREFIX
                        + "ShortValueOfCall::allocationDeoptBox",
                TEST_CLASS, "allocation-deopt-child"));
        OutputAnalyzer output = ProcessTools.executeCommand(
                ProcessTools.createLimitedTestJavaProcessBuilder(options));
        output.shouldHaveExitValue(0);
        output.shouldContain("ALLOCATION-DEOPT-PASS");
        assertAllocationDeoptTrace(output.getOutput(), "Character");
        assertAllocationDeoptTrace(output.getOutput(), "Short");
    }

    private static void assertAllocationDeoptTrace(String output, String wrapper) {
        String className = GENERATED_CLASS_PREFIX + wrapper + "ValueOfCall";
        String normalReturnDeopt =
                "DEOPT UNPACKING[^\\n]*mode=0\\R"
                + "[^\\n]*Virtual frames[^\\n]*\\R"
                + "[^\\n]*VFrame[^\\n]*"
                + Pattern.quote(className) + "\\.allocationDeoptBox";
        if (!Pattern.compile(normalReturnDeopt).matcher(output).find()) {
            throw new AssertionError("no allocation return-time deoptimization in "
                    + className + "::allocationDeoptBox\n" + output);
        }
    }

    private static void runAllocationDeoptChild() throws Throwable {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        Class<?> characterClass = lookup.defineClass(generate(
                "compiler/jeandle/intrinsic/RawNarrowCharacterValueOfCall",
                "java/lang/Character", "valueOf", "(C)Ljava/lang/Character;",
                "charValue", "()C", I2C));
        Class<?> shortClass = lookup.defineClass(generate(
                "compiler/jeandle/intrinsic/RawNarrowShortValueOfCall",
                "java/lang/Short", "valueOf", "(S)Ljava/lang/Short;",
                "shortValue", "()S", I2S));

        lookup.findStatic(characterClass, "resolve",
                MethodType.methodType(void.class)).invokeExact();
        lookup.findStatic(shortClass, "resolve",
                MethodType.methodType(void.class)).invokeExact();
        MethodHandle characterTarget = lookup.findStatic(characterClass,
                "allocationDeoptBox", MethodType.methodType(Object.class, int.class));
        MethodHandle shortTarget = lookup.findStatic(shortClass,
                "allocationDeoptBox", MethodType.methodType(Object.class, int.class));

        int characterRaw = 0xcafe;
        Character characterBox = (Character) (Object)
                characterTarget.invokeExact(characterRaw);
        if (characterBox == null || characterBox.charValue() != (char) characterRaw) {
            throw new AssertionError("Character allocation deopt lost raw argument: "
                    + characterBox);
        }
        if (characterBox == Character.valueOf((char) characterRaw)) {
            throw new AssertionError("Character allocation deopt changed fresh identity");
        }

        int shortRaw = (short) 0xa5a5;
        Short shortBox = (Short) (Object) shortTarget.invokeExact(shortRaw);
        if (shortBox == null || shortBox.shortValue() != (short) shortRaw) {
            throw new AssertionError("Short allocation deopt lost raw argument: "
                    + shortBox);
        }
        if (shortBox == Short.valueOf((short) shortRaw)) {
            throw new AssertionError("Short allocation deopt changed fresh identity");
        }
        System.out.println("ALLOCATION-DEOPT-PASS");
    }

    private static void checkBoolean(MethodHandle target, MethodHandle cachedTarget, int value)
            throws Throwable {
        int actual = (int) target.invokeExact(value);
        int expected = value == 0 ? 0 : 1;
        if (actual != expected) {
            throw new AssertionError("Boolean.valueOf raw input=0x"
                    + Integer.toHexString(value) + " expected=" + expected
                    + " actual=" + actual);
        }

        int actualCached = (int) cachedTarget.invokeExact(value);
        if (actualCached != 1) {
            throw new AssertionError("Boolean.valueOf cache identity raw input=0x"
                    + Integer.toHexString(value) + " expected=1 actual="
                    + actualCached);
        }
    }

    private static void checkByte(MethodHandle target, MethodHandle cachedTarget, int value)
            throws Throwable {
        boolean canonical = value == (byte)value;
        try {
            int actual = (int) target.invokeExact(value);
            if (!canonical) {
                throw new AssertionError("Byte.valueOf raw input=0x"
                        + Integer.toHexString(value) + " should throw");
            }
            if (actual != (byte)value) {
                throw new AssertionError("Byte.valueOf raw input=0x" + Integer.toHexString(value)
                        + " expected=" + (byte)value + " actual=" + actual);
            }
        } catch (ArrayIndexOutOfBoundsException exception) {
            if (canonical) {
                throw exception;
            }
            return;
        }

        int actualCached = (int) cachedTarget.invokeExact(value);
        if (actualCached != 1) {
            throw new AssertionError("Byte.valueOf cache identity raw input=0x"
                    + Integer.toHexString(value) + " expected=1 actual=" + actualCached);
        }
    }

    private static void checkCharacter(MethodHandle target, MethodHandle cachedTarget, int value)
            throws Throwable {
        boolean shouldThrow = value < 0;
        try {
            int actual = (int) target.invokeExact(value);
            if (shouldThrow) {
                throw new AssertionError("Character.valueOf raw input=0x"
                        + Integer.toHexString(value) + " should throw");
            }
            int expected = value & 0xffff;
            if (actual != expected) {
                throw new AssertionError("Character.valueOf raw input=0x"
                        + Integer.toHexString(value) + " expected=" + expected
                        + " actual=" + actual);
            }
        } catch (ArrayIndexOutOfBoundsException exception) {
            if (!shouldThrow) {
                throw exception;
            }
            return;
        }

        int actualCached = (int) cachedTarget.invokeExact(value);
        int expectedCached = value >= 0 && value <= 127 ? 1 : 0;
        if (actualCached != expectedCached) {
            throw new AssertionError("Character.valueOf cache identity raw input=0x"
                    + Integer.toHexString(value) + " expected=" + expectedCached
                    + " actual=" + actualCached);
        }
    }

    private static void checkShort(MethodHandle target, MethodHandle cachedTarget, int value)
            throws Throwable {
        int actual = (int) target.invokeExact(value);
        int expected = (short)value;
        if (actual != expected) {
            throw new AssertionError("Short.valueOf raw input=0x" + Integer.toHexString(value)
                    + " expected=" + expected + " actual=" + actual);
        }

        int actualCached = (int) cachedTarget.invokeExact(value);
        int expectedCached = value >= -128 && value <= 127 ? 1 : 0;
        if (actualCached != expectedCached) {
            throw new AssertionError("Short.valueOf cache identity raw input=0x"
                    + Integer.toHexString(value) + " expected=" + expectedCached
                    + " actual=" + actualCached);
        }
    }

    private static void checkByteDeopt(MethodHandle target, Method method)
            throws Throwable {
        activeDeoptRequested = false;
        Byte box = (Byte) invokeWithDeopt(target, method, 0);
        if (!activeDeoptRequested) {
            throw new AssertionError("Byte boxing target was not actively deoptimized");
        }
        if (box.byteValue() != 0 || box != Byte.valueOf((byte) 0)) {
            throw new AssertionError("Byte box changed across active deoptimization");
        }
    }

    private static void checkCharacterDeopt(MethodHandle target, Method method)
            throws Throwable {
        int raw = 0x0001_0000;
        Character box = (Character) invokeWithDeopt(target, method, raw);
        if (box.charValue() != '\0') {
            throw new AssertionError("Character raw payload changed across deoptimization");
        }
        if (box == Character.valueOf('\0')) {
            throw new AssertionError("non-canonical Character raw value restored a cached box");
        }
    }

    private static void checkShortDeopt(MethodHandle target, Method method)
            throws Throwable {
        int raw = 0x0001_0000;
        Short box = (Short) invokeWithDeopt(target, method, raw);
        if (box.shortValue() != 0) {
            throw new AssertionError("Short raw payload changed across deoptimization");
        }
        if (box == Short.valueOf((short) 0)) {
            throw new AssertionError("non-canonical Short raw value restored a cached box");
        }
    }

    private static Object invokeWithDeopt(
            MethodHandle target, Method method, int raw) throws Throwable {
        deoptTarget = method;
        if (!WB.enqueueMethodForCompilation(method, 4)) {
            throw new AssertionError("failed to enqueue boxing deopt target");
        }
        for (int attempt = 0; attempt < 3_000 && !WB.isMethodCompiled(method);
                attempt++) {
            Thread.sleep(10);
        }
        if (!WB.isMethodCompiled(method)
                || WB.getMethodCompilationLevel(method) != 4) {
            throw new AssertionError("boxing deopt target was not compiled at level 4");
        }
        return (Object) target.invokeExact(raw);
    }

    public static void requestDeopt() {
        Method target = deoptTarget;
        if (target == null || !WB.isMethodCompiled(target)) {
            // A non-canonical raw value takes the intrinsic guard first. The
            // reexecuted valueOf then reaches this helper in the interpreter.
            return;
        }
        int marked = WB.deoptimizeMethod(target);
        if (marked != 1) {
            throw new AssertionError("expected one deoptimized boxing nmethod, got "
                    + marked);
        }
        activeDeoptRequested = true;
    }

    private static byte[] generate(String className, String targetOwner,
            String targetMethod, String targetDescriptor, String unboxMethod,
            String unboxDescriptor, int conversionOpcode) {
        ClassWriter writer = new ClassWriter(
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_FINAL, className, null,
                "java/lang/Object", null);
        emitApply(writer, targetOwner, targetMethod, targetDescriptor,
                unboxMethod, unboxDescriptor);
        emitIsCached(writer, targetOwner, targetMethod, targetDescriptor,
                conversionOpcode);
        emitDeoptBox(writer, targetOwner, targetMethod, targetDescriptor);
        emitAllocationDeoptBox(writer, targetOwner, targetMethod,
                targetDescriptor);
        emitResolver(writer, targetOwner, targetMethod, targetDescriptor);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void emitApply(ClassWriter writer, String targetOwner,
            String targetMethod, String targetDescriptor, String unboxMethod,
            String unboxDescriptor) {
        MethodVisitor method = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC, "apply", "(I)I", null, null);
        method.visitCode();
        method.visitVarInsn(ILOAD, 0);
        method.visitMethodInsn(INVOKESTATIC, targetOwner, targetMethod,
                targetDescriptor, false);
        method.visitMethodInsn(INVOKEVIRTUAL, targetOwner, unboxMethod,
                unboxDescriptor, false);
        method.visitInsn(IRETURN);
        method.visitMaxs(1, 1);
        method.visitEnd();
    }

    private static void emitIsCached(ClassWriter writer, String targetOwner,
            String targetMethod, String targetDescriptor, int conversionOpcode) {
        MethodVisitor method = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC, "isCached", "(I)I", null, null);
        method.visitCode();
        method.visitVarInsn(ILOAD, 0);
        method.visitMethodInsn(INVOKESTATIC, targetOwner, targetMethod,
                targetDescriptor, false);
        emitCanonicalArgument(method, conversionOpcode);
        method.visitMethodInsn(INVOKESTATIC, targetOwner, targetMethod,
                targetDescriptor, false);
        var notSame = new jdk.internal.org.objectweb.asm.Label();
        var done = new jdk.internal.org.objectweb.asm.Label();
        method.visitJumpInsn(IF_ACMPNE, notSame);
        method.visitInsn(ICONST_1);
        method.visitJumpInsn(GOTO, done);
        method.visitLabel(notSame);
        method.visitInsn(ICONST_0);
        method.visitLabel(done);
        method.visitInsn(IRETURN);
        method.visitMaxs(2, 1);
        method.visitEnd();
    }

    private static void emitDeoptBox(ClassWriter writer, String targetOwner,
            String targetMethod, String targetDescriptor) {
        MethodVisitor method = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC, "deoptBox", "(I)Ljava/lang/Object;",
                null, null);
        method.visitCode();
        method.visitVarInsn(ILOAD, 0);
        method.visitMethodInsn(INVOKESTATIC, targetOwner, targetMethod,
                targetDescriptor, false);
        method.visitVarInsn(ASTORE, 1);
        method.visitMethodInsn(INVOKESTATIC,
                "compiler/jeandle/intrinsic/TestBoxingValueOfRawNarrow",
                "requestDeopt", "()V", false);
        method.visitVarInsn(ALOAD, 1);
        method.visitInsn(ARETURN);
        method.visitMaxs(1, 2);
        method.visitEnd();
    }

    private static void emitAllocationDeoptBox(ClassWriter writer,
            String targetOwner, String targetMethod, String targetDescriptor) {
        MethodVisitor method = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC, "allocationDeoptBox",
                "(I)Ljava/lang/Object;", null, null);
        method.visitCode();
        method.visitVarInsn(ILOAD, 0);
        method.visitMethodInsn(INVOKESTATIC, targetOwner, targetMethod,
                targetDescriptor, false);
        method.visitVarInsn(ASTORE, 1);
        for (int index = 1; index < ALLOCATION_DEOPT_BOX_COUNT; index++) {
            method.visitVarInsn(ILOAD, 0);
            method.visitMethodInsn(INVOKESTATIC, targetOwner, targetMethod,
                    targetDescriptor, false);
            method.visitVarInsn(ASTORE, 2);
            method.visitVarInsn(ALOAD, 1);
            method.visitVarInsn(ALOAD, 2);
            var fresh = new jdk.internal.org.objectweb.asm.Label();
            method.visitJumpInsn(IF_ACMPNE, fresh);
            method.visitInsn(ACONST_NULL);
            method.visitInsn(ARETURN);
            method.visitLabel(fresh);
            method.visitVarInsn(ALOAD, 2);
            method.visitVarInsn(ASTORE, 1);
        }
        method.visitVarInsn(ALOAD, 1);
        method.visitInsn(ARETURN);
        method.visitMaxs(2, 3);
        method.visitEnd();
    }

    private static void emitCanonicalArgument(MethodVisitor method,
            int conversionOpcode) {
        method.visitVarInsn(ILOAD, 0);
        if (conversionOpcode != BOOLEAN_NORMALIZATION) {
            method.visitInsn(conversionOpcode);
            return;
        }

        var isFalse = new jdk.internal.org.objectweb.asm.Label();
        var done = new jdk.internal.org.objectweb.asm.Label();
        method.visitJumpInsn(IFEQ, isFalse);
        method.visitInsn(ICONST_1);
        method.visitJumpInsn(GOTO, done);
        method.visitLabel(isFalse);
        method.visitInsn(ICONST_0);
        method.visitLabel(done);
    }

    private static void emitResolver(ClassWriter writer, String targetOwner,
            String targetMethod, String targetDescriptor) {
        MethodVisitor method = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC, "resolve", "()V", null, null);
        method.visitCode();
        method.visitInsn(ICONST_0);
        method.visitMethodInsn(INVOKESTATIC, targetOwner, targetMethod,
                targetDescriptor, false);
        method.visitInsn(POP);
        method.visitInsn(RETURN);
        method.visitMaxs(1, 0);
        method.visitEnd();
    }
}
