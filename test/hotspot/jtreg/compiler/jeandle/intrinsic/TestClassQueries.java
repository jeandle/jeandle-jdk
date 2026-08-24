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
 * @summary Test the Jeandle Class-query intrinsic family
 * @requires os.arch=="amd64" | os.arch=="x86_64" | os.arch=="aarch64"
 * @modules java.base/jdk.internal.reflect
 * @library /test/lib /
 * @build jdk.test.lib.Asserts
 * @run main/othervm -XX:-UseJeandleCompiler compiler.jeandle.intrinsic.TestClassQueries
 */

package compiler.jeandle.intrinsic;

import compiler.jeandle.fileCheck.FileCheck;

import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;
import jdk.internal.reflect.Reflection;

public class TestClassQueries {
    private static final String IS_INSTANCE_LOG =
            "Method `virtual jboolean java.lang.Class.isInstance(jobject)` is parsed as intrinsic";
    private static final String GET_MODIFIERS_LOG =
            "Method `virtual jint java.lang.Class.getModifiers()` is parsed as intrinsic";
    private static final String IS_ARRAY_LOG =
            "Method `virtual jboolean java.lang.Class.isArray()` is parsed as intrinsic";
    private static final String IS_PRIMITIVE_LOG =
            "Method `virtual jboolean java.lang.Class.isPrimitive()` is parsed as intrinsic";
    private static final String IS_INTERFACE_LOG =
            "Method `virtual jboolean java.lang.Class.isInterface()` is parsed as intrinsic";
    private static final String IS_HIDDEN_LOG =
            "Method `virtual jboolean java.lang.Class.isHidden()` is parsed as intrinsic";
    private static final String GET_SUPERCLASS_LOG =
            "Method `virtual jobject java.lang.Class.getSuperclass()` is parsed as intrinsic";
    private static final String GET_CLASS_ACCESS_FLAGS_LOG =
            "Method `static jint jdk.internal.reflect.Reflection.getClassAccessFlags(jobject)`"
                    + " is parsed as intrinsic";
    private static final String CAST_LOG =
            "Method `virtual jobject java.lang.Class.cast(jobject)` is parsed as intrinsic";
    private static final String IS_ASSIGNABLE_FROM_LOG =
            "Method `virtual jboolean java.lang.Class.isAssignableFrom(jobject)`"
                    + " is parsed as intrinsic";

    public static void main(String[] args) throws Exception {
        runChild(true);
        runChild(false);
    }

    private static void runChild(boolean enabled) throws Exception {
        String mode = enabled ? "enabled" : "disabled";
        String dumpPath = Files.createTempDirectory("jeandle_class_queries_" + mode).toString();
        ArrayList<String> commandArgs = new ArrayList<>(List.of(
                "--add-exports=java.base/jdk.internal.reflect=ALL-UNNAMED",
                "-XX:+UnlockDiagnosticVMOptions",
                "-XX:+InlineClassNatives",
                "-Xbatch",
                "-XX:-TieredCompilation",
                "-XX:+UseJeandleCompiler",
                "-XX:CompileThreshold=100",
                "-Xlog:jeandle=debug",
                "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dumpPath,
                compileOnly("queryIsInstance"),
                compileOnly("queryGetModifiers"),
                compileOnly("queryIsArray"),
                compileOnly("queryIsPrimitive"),
                compileOnly("queryIsInterface"),
                compileOnly("queryIsHidden"),
                compileOnly("constantIsArray"),
                compileOnly("constantIsPrimitive"),
                compileOnly("constantIsInterface"),
                compileOnly("constantIsHidden"),
                compileOnly("constantGetModifiers"),
                compileOnly("constantArrayGetModifiers"),
                compileOnly("constantObjectIsPrimitive"),
                compileOnly("constantPrimitiveIsArray"),
                compileOnly("constantPrimitiveIsInterface"),
                compileOnly("constantPrimitiveIsHidden"),
                compileOnly("constantPrimitiveGetModifiers"),
                compileOnly("constantVoidIsPrimitive"),
                compileOnly("constantVoidGetModifiers"),
                compileOnly("constantPrimitiveIsInstance"),
                compileOnly("constantStringIsInstance"),
                compileOnly("constantStringGetSuperclass"),
                compileOnly("constantObjectGetSuperclass"),
                compileOnly("constantArrayGetSuperclass"),
                compileOnly("constantPrimitiveGetSuperclass"),
                compileOnly("constantGetClassAccessFlags"),
                compileOnly("constantPrimitiveGetClassAccessFlags"),
                compileOnly("constantStringCast"),
                compileOnly("constantStringCastTyped"),
                compileOnly("constantStringIsInstanceTyped"),
                compileOnly("constantAssignableTrue"),
                compileOnly("constantAssignableFalse"),
                compileOnly("constantPrimitiveAssignableTrue"),
                compileOnly("constantPrimitiveAssignableFalse"),
                compileOnly("constantReceiverAssignable"),
                compileOnly("queryGetSuperclass"),
                compileOnly("queryGetClassAccessFlags"),
                compileOnly("queryCast"),
                compileOnly("queryIsAssignableFrom")));
        if (!enabled) {
            commandArgs.add("-XX:ControlIntrinsic=-_isInstance,-_getModifiers,-_isArray,"
                    + "-_isPrimitive,-_isInterface,-_isHidden,-_getSuperclass,"
                    + "-_getClassAccessFlags,-_Class_cast,-_isAssignableFrom");
        }
        commandArgs.add(TestWrapper.class.getName());

        OutputAnalyzer output = ProcessTools.executeCommand(
                ProcessTools.createLimitedTestJavaProcessBuilder(commandArgs));
        output.shouldHaveExitValue(0);
        checkLogs(output, enabled);
        checkIR(dumpPath, enabled);
    }

    private static String compileOnly(String method) {
        return "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::" + method;
    }

    private static void checkLogs(OutputAnalyzer output, boolean enabled) {
        String[] logs = {
                IS_INSTANCE_LOG, GET_MODIFIERS_LOG,
                IS_ARRAY_LOG, IS_PRIMITIVE_LOG, IS_INTERFACE_LOG, IS_HIDDEN_LOG,
                GET_SUPERCLASS_LOG, GET_CLASS_ACCESS_FLAGS_LOG, CAST_LOG,
                IS_ASSIGNABLE_FROM_LOG
        };
        for (String log : logs) {
            if (enabled) {
                output.shouldContain(log);
            } else {
                output.shouldNotContain(log);
            }
        }
    }

    private static void checkIR(String dumpPath, boolean enabled) throws Exception {
        checkMethodIR(dumpPath, enabled, "queryIsInstance", "java_lang_Class", "isInstance",
                new Class<?>[] {Class.class, Object.class}, List.of(
                        "%class\\.is_instance\\.is_primitive = icmp eq ptr "
                                + "%class\\.is_instance\\.klass, null",
                        "ret i32"));
        checkMethodIR(dumpPath, enabled, "queryGetModifiers", "java_lang_Class", "getModifiers",
                new Class<?>[] {Class.class}, List.of(
                        "%class\\.flags\\.is_primitive = icmp eq ptr %class\\.flags\\.klass, null",
                        "%class\\.modifiers\\.value = load i32",
                        "%class\\.flags\\.result = phi i32"));
        checkMethodIR(dumpPath, enabled, "queryIsArray", "java_lang_Class", "isArray",
                new Class<?>[] {Class.class}, List.of(
                        "%class\\.query\\.is_primitive = icmp eq ptr %class\\.query\\.klass, null",
                        "%class\\.query\\.is_array = icmp slt i32 "
                                + "%class\\.query\\.layout_helper, 0",
                        "%class\\.query\\.non_primitive_result = zext i1 "
                                + "%class\\.query\\.is_array to i32"));
        checkMethodIR(dumpPath, enabled, "queryIsPrimitive", "java_lang_Class", "isPrimitive",
                new Class<?>[] {Class.class}, List.of(
                        "%class\\.query\\.is_primitive = icmp eq ptr %class\\.query\\.klass, null",
                        "%class\\.query\\.result = zext i1 %class\\.query\\.is_primitive to i32"));
        checkMethodIR(dumpPath, enabled, "queryIsInterface", "java_lang_Class", "isInterface",
                new Class<?>[] {Class.class}, List.of(
                        "%class\\.query\\.is_primitive = icmp eq ptr %class\\.query\\.klass, null",
                        "and i32 %class\\.query\\.access_flags, 512",
                        "%class\\.query\\.non_primitive_result = zext i1 "
                                + "%class\\.query\\.flag_set to i32"));
        checkMethodIR(dumpPath, enabled, "queryIsHidden", "java_lang_Class", "isHidden",
                new Class<?>[] {Class.class}, List.of(
                        "%class\\.query\\.is_primitive = icmp eq ptr %class\\.query\\.klass, null",
                        "and i32 %class\\.query\\.access_flags, 67108864",
                        "%class\\.query\\.non_primitive_result = zext i1 "
                                + "%class\\.query\\.flag_set to i32"));
        checkMethodIR(dumpPath, enabled, "queryGetSuperclass", "java_lang_Class", "getSuperclass",
                new Class<?>[] {Class.class}, List.of(
                        "%class\\.super\\.is_primitive = icmp eq ptr "
                                + "%class\\.super\\.klass_from_mirror, null",
                        "%class\\.super\\.is_interface = icmp ne i32",
                        "%class\\.super\\.is_array = icmp slt i32",
                        "%class\\.super\\.klass = load ptr"));
        checkMethodIR(dumpPath, enabled, "queryGetClassAccessFlags",
                "jdk_internal_reflect_Reflection", "getClassAccessFlags",
                new Class<?>[] {Class.class}, List.of(
                        "%class\\.flags\\.is_primitive = icmp eq ptr %class\\.flags\\.klass, null",
                        "call hotspotcc i32 \\(\\.\\.\\.\\) "
                                + "@llvm\\.experimental\\.deoptimize\\.i32\\(i32 -10\\)",
                        "%class\\.access_flags\\.value = load i32",
                        "%class\\.flags\\.result = phi i32"));
        if (enabled) {
            checkConstantIR(dumpPath, "constantIsArray", "ret i32 1");
            checkConstantIR(dumpPath, "constantIsPrimitive", "ret i32 1");
            checkConstantIR(dumpPath, "constantIsInterface", "ret i32 1");
            checkConstantIR(dumpPath, "constantIsHidden", "ret i32 0");
            checkConstantIR(dumpPath, "constantGetModifiers", "ret i32 17");
            checkConstantIR(dumpPath, "constantArrayGetModifiers", "ret i32 1041");
            checkConstantIR(dumpPath, "constantObjectIsPrimitive", "ret i32 0");
            checkConstantIR(dumpPath, "constantPrimitiveIsArray", "ret i32 0");
            checkConstantIR(dumpPath, "constantPrimitiveIsInterface", "ret i32 0");
            checkConstantIR(dumpPath, "constantPrimitiveIsHidden", "ret i32 0");
            checkConstantIR(dumpPath, "constantPrimitiveGetModifiers", "ret i32 1041");
            checkConstantIR(dumpPath, "constantVoidIsPrimitive", "ret i32 1");
            checkConstantIR(dumpPath, "constantVoidGetModifiers", "ret i32 1041");
            checkConstantObjectResultIR(dumpPath, "constantPrimitiveIsInstance", "ret i32 0");
            checkConstantIR(dumpPath, "constantGetClassAccessFlags", "ret i32 49");
            checkConstantIR(dumpPath, "constantPrimitiveGetClassAccessFlags", "ret i32 1041");
            checkConstantIR(dumpPath, "constantAssignableTrue", "ret i32 1");
            checkConstantIR(dumpPath, "constantAssignableFalse", "ret i32 0");
            checkConstantIR(dumpPath, "constantPrimitiveAssignableTrue", "ret i32 1");
            checkConstantIR(dumpPath, "constantPrimitiveAssignableFalse", "ret i32 0");
            checkKnownMirrorAssignableIR(dumpPath);
            checkConstantSuperclassIR(dumpPath, "constantStringGetSuperclass", false);
            checkConstantSuperclassIR(dumpPath, "constantObjectGetSuperclass", true);
            checkConstantSuperclassIR(dumpPath, "constantArrayGetSuperclass", false);
            checkConstantSuperclassIR(dumpPath, "constantPrimitiveGetSuperclass", true);
            checkKnownMirrorObjectQueryIR(dumpPath, "constantStringIsInstance",
                    "class_is_instance_merge");
            checkKnownMirrorObjectQueryIR(dumpPath, "constantStringCast",
                    "class_cast_pass");
            checkConstantTypedObjectResultIR(dumpPath, "constantStringCastTyped", String.class, "ret ptr addrspace\\(1\\)");
            checkConstantTypedIR(dumpPath, "constantStringIsInstanceTyped", Integer.class, "ret i32 0");
        }
        checkMethodIR(dumpPath, enabled, "queryCast", "java_lang_Class", "cast",
                new Class<?>[] {Class.class, Object.class}, List.of(
                        "%class\\.cast\\.is_primitive = icmp eq ptr",
                        "%class\\.cast\\.is_null = icmp eq ptr addrspace\\(1\\)",
                        "ret ptr addrspace\\(1\\)"));
        checkMethodIR(dumpPath, enabled, "queryIsAssignableFrom", "java_lang_Class",
                "isAssignableFrom", new Class<?>[] {Class.class, Class.class}, List.of(
                        "%class\\.assignable\\.any_primitive = or i1",
                        "ret i32"));
    }

    private static void checkConstantTypedIR(String dumpPath, String methodName,
                                              Class<?> parameterType,
                                              String resultPattern) throws Exception {
        Method method = TestWrapper.class.getMethod(methodName, parameterType);
        FileCheck optimized = new FileCheck(dumpPath, method, true);
        optimized.checkPattern(resultPattern);
        optimized.checkNotPattern("jeandle\\.load_mirror_klass");
    }

    private static void checkConstantIR(String dumpPath, String methodName,
                                        String resultPattern) throws Exception {
        Method method = TestWrapper.class.getMethod(methodName);
        FileCheck optimized = new FileCheck(dumpPath, method, true);
        optimized.checkPattern(resultPattern);
        optimized.checkNotPattern("java_lang_Class_(isArray|isPrimitive|isInterface|isHidden|getModifiers)");
        optimized.checkNotPattern("jeandle\\.load_mirror_klass");
        optimized.checkNotPattern("(layout_helper|access_flags|modifier_flags)");
    }

    private static void checkConstantSuperclassIR(String dumpPath, String methodName,
                                                   boolean returnsNull) throws Exception {
        Method method = TestWrapper.class.getMethod(methodName);
        FileCheck optimized = new FileCheck(dumpPath, method, true);
        optimized.checkPattern(returnsNull
                ? "ret ptr addrspace\\(1\\) null"
                : "ret ptr addrspace\\(1\\) %[-A-Za-z$._0-9]+");
        optimized.checkNotPattern("jeandle\\.(load_mirror_klass|load_mirror_from_klass)");
        optimized.checkNotPattern("class\\.super\\.(access_flags|layout_helper|klass)");
    }

    private static void checkConstantObjectResultIR(String dumpPath, String methodName,
                                                     String resultPattern) throws Exception {
        Method method = TestWrapper.class.getMethod(methodName, Object.class);
        FileCheck optimized = new FileCheck(dumpPath, method, true);
        optimized.checkPattern(resultPattern);
        optimized.checkNotPattern("jeandle\\.load_mirror_klass");
    }

    private static void checkConstantTypedObjectResultIR(String dumpPath, String methodName,
                                                          Class<?> parameterType,
                                                          String resultPattern) throws Exception {
        Method method = TestWrapper.class.getMethod(methodName, parameterType);
        FileCheck optimized = new FileCheck(dumpPath, method, true);
        optimized.checkPattern(resultPattern);
        optimized.checkNotPattern("jeandle\\.load_mirror_klass");
    }

    private static void checkKnownMirrorObjectQueryIR(String dumpPath, String methodName,
                                                       String operation) throws Exception {
        Method method = TestWrapper.class.getMethod(methodName, Object.class);
        FileCheck optimized = new FileCheck(dumpPath, method, true);
        optimized.checkPattern(operation);
        optimized.checkNotPattern("jeandle\\.load_mirror_klass");
        optimized.checkNotPattern("class\\.(is_instance|cast)\\.is_primitive");
    }

    private static void checkKnownMirrorAssignableIR(String dumpPath) throws Exception {
        Method method = TestWrapper.class.getMethod("constantReceiverAssignable", Class.class);
        FileCheck optimized = new FileCheck(dumpPath, method, true);
        optimized.checkPattern("class_assignable_merge");
        optimized.checkNotPattern("jeandle\\.load_mirror_klass");
    }

    private static void checkMethodIR(String dumpPath, boolean enabled,
                                      String wrapperName, String targetOwner, String targetName,
                                      Class<?>[] parameterTypes,
                                      List<String> semanticPatterns) throws Exception {
        Method wrapper = TestWrapper.class.getMethod(wrapperName, parameterTypes);
        FileCheck checker = enabled && isDirectClassQuery(wrapperName)
                ? new FileCheck(dumpPath, wrapper, false, 0)
                : new FileCheck(dumpPath, wrapper, false);
        checker.checkPattern("define hotspotcc .*" + wrapperName);

        String nativeCall = "(call|invoke) hotspotcc [^\\r\\n]*" + targetOwner + "_"
                + targetName;
        if (enabled) {
            if (isDirectClassQuery(wrapperName)) {
                checker.checkPattern("call hotspotcc ptr @jeandle\\.load_mirror_klass"
                        + "\\(ptr addrspace\\(1\\) %[-A-Za-z$._0-9]+\\)");
                checker.checkNotPattern("call hotspotcc [^\\r\\n]*"
                        + "@jeandle\\.load_mirror_klass[^\\r\\n]*"
                        + "\\[ \"deopt\"");
                checker.checkNotPattern("call hotspotcc [^\\r\\n]*\"java-klass\""
                        + "[^\\r\\n]*@jeandle\\.load_mirror_klass");
            } else {
                if (wrapperName.equals("queryGetSuperclass")) {
                    checker.checkNotPattern("call hotspotcc [^\\r\\n]*@jeandle\\.load_mirror_from_klass");
                } else {
                    // Loading Klass* from a nonnull mirror is a GC-leaf JavaOp. It
                    // must not inherit the Class intrinsic's deopt state or Java
                    // return-type metadata.
                    checker.checkPattern("call hotspotcc ptr @jeandle\\.load_mirror_klass"
                            + "\\(ptr addrspace\\(1\\) %[-A-Za-z$._0-9]+\\)");
                    checker.checkNotPattern("call hotspotcc [^\\r\\n]*"
                            + "@jeandle\\.load_mirror_klass[^\\r\\n]*"
                            + "\\[ \\\"deopt\\\"");
                    checker.checkNotPattern("call hotspotcc [^\\r\\n]*\\\"java-klass\\\""
                            + "[^\\r\\n]*@jeandle\\.load_mirror_klass");
                }
            }
            for (String pattern : semanticPatterns) {
                checker.checkPattern(pattern);
            }
            if (wrapperName.equals("queryGetSuperclass")) {
                checker.checkNotPattern("%class\\.super\\.klass_from_mirror = call hotspotcc "
                        + "[^\\r\\n]*\\\"java-klass\\\"[^\\r\\n]*"
                        + "@jeandle\\.load_mirror_klass");
            }
            if (wrapperName.equals("queryCast")) {
                checker.checkNotPattern("call hotspotcc [^\\r\\n]*\\\"java-klass\\\""
                        + "[^\\r\\n]*@jeandle\\.checkcast");
            }
            checker.checkNotPattern(nativeCall);
        } else {
            checker.checkPattern(nativeCall);
            checker.checkNotPattern("(call|invoke)[^\\r\\n]*"
                    + "@jeandle\\.load_mirror_klass\\(");
            checker.checkNotPattern("(call|invoke)[^\\r\\n]*@jeandle\\.instanceof\\(");
            checker.checkNotPattern("(call|invoke)[^\\r\\n]*"
                    + "@jeandle\\.load_mirror_from_klass\\(");
        }
    }

    private static boolean isDirectClassQuery(String wrapperName) {
        return wrapperName.equals("queryGetModifiers")
                || wrapperName.equals("queryGetClassAccessFlags")
                || wrapperName.equals("queryIsArray")
                || wrapperName.equals("queryIsPrimitive")
                || wrapperName.equals("queryIsInterface")
                || wrapperName.equals("queryIsHidden");
    }

    static class TestWrapper {
        interface TestInterface {}

        @interface TestAnnotation {}

        static class Parent {}

        static class Child extends Parent {}

        static final class HiddenTemplate {}

        interface HiddenInterfaceTemplate {}

        public static void main(String[] args) throws Exception {
            warmup();

            Asserts.assertTrue(constantIsArray(), "constant isArray");
            Asserts.assertTrue(constantIsPrimitive(), "constant isPrimitive");
            Asserts.assertTrue(constantIsInterface(), "constant isInterface");
            Asserts.assertFalse(constantIsHidden(), "constant isHidden");
            Asserts.assertEquals(17, constantGetModifiers(), "constant getModifiers");
            Asserts.assertEquals(Modifier.PUBLIC | Modifier.ABSTRACT | Modifier.FINAL,
                    constantArrayGetModifiers(), "constant array getModifiers");
            Asserts.assertFalse(constantObjectIsPrimitive(), "constant object isPrimitive");
            Asserts.assertFalse(constantPrimitiveIsArray(), "constant primitive isArray");
            Asserts.assertFalse(constantPrimitiveIsInterface(), "constant primitive isInterface");
            Asserts.assertFalse(constantPrimitiveIsHidden(), "constant primitive isHidden");
            Asserts.assertEquals(Modifier.PUBLIC | Modifier.ABSTRACT | Modifier.FINAL,
                    constantPrimitiveGetModifiers(), "constant primitive getModifiers");
            Asserts.assertTrue(constantVoidIsPrimitive(), "constant void isPrimitive");
            Asserts.assertEquals(Modifier.PUBLIC | Modifier.ABSTRACT | Modifier.FINAL,
                    constantVoidGetModifiers(), "constant void getModifiers");
            Asserts.assertFalse(constantPrimitiveIsInstance(Integer.valueOf(1)));
            Asserts.assertTrue(constantStringIsInstance("value"));
            Asserts.assertFalse(constantStringIsInstance(Integer.valueOf(1)));
            Asserts.assertEquals(Object.class, constantStringGetSuperclass());
            Asserts.assertEquals(null, constantObjectGetSuperclass());
            Asserts.assertEquals(Object.class, constantArrayGetSuperclass());
            Asserts.assertEquals(null, constantPrimitiveGetSuperclass());
            Asserts.assertEquals(Reflection.getClassAccessFlags(String.class),
                    constantGetClassAccessFlags());
            Asserts.assertEquals(Modifier.PUBLIC | Modifier.ABSTRACT | Modifier.FINAL,
                    constantPrimitiveGetClassAccessFlags());
            Asserts.assertSame("value", constantStringCast("value"));
            Asserts.assertSame(null, constantStringCast(null));
            expectCCE(() -> constantStringCast(Integer.valueOf(1)), "constant String cast");
            Asserts.assertSame("value", constantStringCastTyped("value"));
            Asserts.assertSame(null, constantStringCastTyped(null));
            Asserts.assertFalse(constantStringIsInstanceTyped(Integer.valueOf(1)));
            Asserts.assertTrue(constantAssignableTrue());
            Asserts.assertFalse(constantAssignableFalse());
            Asserts.assertTrue(constantPrimitiveAssignableTrue());
            Asserts.assertFalse(constantPrimitiveAssignableFalse());
            Asserts.assertTrue(constantReceiverAssignable(String.class));
            Asserts.assertFalse(constantReceiverAssignable(int.class));

            verify(Object.class, false, false, false, false, null);
            verify(TestWrapper.class, false, false, false, false, Object.class);
            verify(Child.class, false, false, false, false, Parent.class);
            verify(TestInterface.class, false, false, true, false, null);
            verify(TestAnnotation.class, false, false, true, false, null);

            Class<?>[] primitives = {
                    boolean.class, byte.class, char.class, short.class,
                    int.class, long.class, float.class, double.class, void.class
            };
            for (Class<?> primitive : primitives) {
                verify(primitive, false, true, false, false, null);
            }

            verify(Object[].class, true, false, false, false, Object.class);
            verify(int[].class, true, false, false, false, Object.class);
            verify(String[][].class, true, false, false, false, Object.class);

            Class<?> hidden = defineHiddenClass(
                    "TestClassQueries$TestWrapper$HiddenTemplate.class");
            verify(hidden, false, false, false, true, Object.class);
            verify(Array.newInstance(hidden, 0).getClass(),
                    true, false, false, false, Object.class);

            Class<?> hiddenInterface = defineHiddenClass(
                    "TestClassQueries$TestWrapper$HiddenInterfaceTemplate.class");
            verify(hiddenInterface, false, false, true, true, null);

            verifyInstance(String.class, "value", true);
            verifyInstance(String.class, new Object(), false);
            verifyInstance(Runnable.class, (Runnable) () -> {}, true);
            verifyInstance(Runnable.class, new Object(), false);
            verifyInstance(Object[].class, new String[0], true);
            verifyInstance(int[].class, new int[0], true);
            verifyInstance(int[].class, new long[0], false);
            verifyInstance(int.class, Integer.valueOf(1), false);
            verifyInstance(Object.class, null, false);

            expectNPE(() -> queryIsInstance(null, new Object()), "isInstance");
            expectNPE(() -> queryGetModifiers(null), "getModifiers");
            expectNPE(() -> queryIsArray(null), "isArray");
            expectNPE(() -> queryIsPrimitive(null), "isPrimitive");
            expectNPE(() -> queryIsInterface(null), "isInterface");
            expectNPE(() -> queryIsHidden(null), "isHidden");
            expectNPE(() -> queryGetSuperclass(null), "getSuperclass");

            Asserts.assertSame("x", queryCast(String.class, "x"));
            Asserts.assertSame(null, queryCast(String.class, null));
            for (int i = 0; i < 20_000; i++) {
                Asserts.assertSame(null, queryCast(int.class, null));
                Asserts.assertSame(null, queryCast(void.class, null));
            }
            expectCCE(() -> queryCast(String.class, Integer.valueOf(1)), "cast");
            expectCCE(() -> queryCast(int.class, Integer.valueOf(1)), "primitive cast");
            Asserts.assertTrue(queryIsAssignableFrom(Object.class, String.class));
            Asserts.assertTrue(queryIsAssignableFrom(int.class, int.class));
            Asserts.assertFalse(queryIsAssignableFrom(int.class, long.class));
            Asserts.assertFalse(queryIsAssignableFrom(String.class, Object.class));
        }

        private static void warmup() {
            for (int i = 0; i < 1_000; i++) {
                queryIsInstance(String.class, "value");
                queryGetModifiers(String.class);
                queryIsArray(String[].class);
                queryIsPrimitive(Object.class);
                queryIsInterface(TestInterface.class);
                queryIsHidden(Object.class);
                constantIsArray();
                constantIsPrimitive();
                constantIsInterface();
                constantIsHidden();
                constantGetModifiers();
                constantArrayGetModifiers();
                constantObjectIsPrimitive();
                constantPrimitiveIsArray();
                constantPrimitiveIsInterface();
                constantPrimitiveIsHidden();
                constantPrimitiveGetModifiers();
                constantVoidIsPrimitive();
                constantVoidGetModifiers();
                constantPrimitiveIsInstance(Integer.valueOf(1));
                constantStringIsInstance("value");
                constantStringGetSuperclass();
                constantObjectGetSuperclass();
                constantArrayGetSuperclass();
                constantPrimitiveGetSuperclass();
                constantGetClassAccessFlags();
                constantPrimitiveGetClassAccessFlags();
                constantStringCast("value");
                constantStringCastTyped("value");
                constantStringIsInstanceTyped(Integer.valueOf(1));
                constantAssignableTrue();
                constantAssignableFalse();
                constantPrimitiveAssignableTrue();
                constantPrimitiveAssignableFalse();
                constantReceiverAssignable(String.class);
                queryGetSuperclass(String.class);
                queryGetClassAccessFlags(String.class);
                queryCast(String.class, "value");
                queryIsAssignableFrom(Object.class, String.class);
            }
        }

        private static Class<?> defineHiddenClass(String resourceName) throws Exception {
            try (InputStream in = TestClassQueries.class.getResourceAsStream(
                    resourceName)) {
                byte[] bytes = Objects.requireNonNull(in, "hidden class bytes").readAllBytes();
                return MethodHandles.lookup().defineHiddenClass(bytes, false).lookupClass();
            }
        }

        private static void verify(Class<?> klass, boolean array, boolean primitive,
                                   boolean iface, boolean hidden,
                                   Class<?> expectedSuperclass) {
            Asserts.assertEquals(array, queryIsArray(klass), klass + " isArray");
            Asserts.assertEquals(primitive, queryIsPrimitive(klass), klass + " isPrimitive");
            Asserts.assertEquals(iface, queryIsInterface(klass), klass + " isInterface");
            Asserts.assertEquals(hidden, queryIsHidden(klass), klass + " isHidden");

            int modifiers = queryGetModifiers(klass);
            Asserts.assertEquals(oracleGetModifiers(klass), modifiers,
                    klass + " getModifiers");
            if (primitive) {
                Asserts.assertEquals(Modifier.PUBLIC | Modifier.ABSTRACT | Modifier.FINAL,
                        modifiers, klass + " primitive modifiers");
            }

            Asserts.assertEquals(expectedSuperclass, queryGetSuperclass(klass),
                    klass + " getSuperclass");
            Asserts.assertEquals(oracleGetSuperclass(klass), queryGetSuperclass(klass),
                    klass + " getSuperclass oracle");

            // Reflection only guarantees the low 13 class-file flag bits.
            int expectedAccessFlags = oracleGetClassAccessFlags(klass) & 0x1fff;
            int actualAccessFlags = queryGetClassAccessFlags(klass) & 0x1fff;
            Asserts.assertEquals(expectedAccessFlags, actualAccessFlags,
                    klass + " getClassAccessFlags");
            if (primitive) {
                Asserts.assertEquals(Modifier.PUBLIC | Modifier.ABSTRACT | Modifier.FINAL,
                        actualAccessFlags, klass + " primitive access flags");
            }
        }

        private static void verifyInstance(Class<?> klass, Object object, boolean expected) {
            Asserts.assertEquals(expected, queryIsInstance(klass, object),
                    klass + " isInstance(" + object + ")");
            Asserts.assertEquals(oracleIsInstance(klass, object),
                    queryIsInstance(klass, object), klass + " isInstance oracle");
        }

        private static void expectCCE(Runnable action, String name) {
            try { action.run(); throw new AssertionError(name + " did not throw ClassCastException"); }
            catch (ClassCastException expected) { }
        }

        private static void expectNPE(Runnable action, String name) {
            try {
                action.run();
                throw new AssertionError(name + " did not throw NullPointerException");
            } catch (NullPointerException expected) {
                // Expected.
            }
        }

        public static boolean queryIsArray(Class<?> klass) {
            return klass.isArray();
        }

        public static boolean queryIsInstance(Class<?> klass, Object object) {
            return klass.isInstance(object);
        }

        public static int queryGetModifiers(Class<?> klass) {
            return klass.getModifiers();
        }

        public static boolean queryIsPrimitive(Class<?> klass) {
            return klass.isPrimitive();
        }

        public static boolean queryIsInterface(Class<?> klass) {
            return klass.isInterface();
        }

        public static boolean queryIsHidden(Class<?> klass) {
            return klass.isHidden();
        }

        public static boolean constantIsArray() {
            return String[].class.isArray();
        }

        public static boolean constantIsPrimitive() {
            return int.class.isPrimitive();
        }

        public static boolean constantIsInterface() {
            return TestInterface.class.isInterface();
        }

        public static boolean constantIsHidden() {
            return Object.class.isHidden();
        }

        public static int constantGetModifiers() {
            return String.class.getModifiers();
        }

        public static int constantArrayGetModifiers() {
            return String[].class.getModifiers();
        }

        public static boolean constantObjectIsPrimitive() {
            return Object.class.isPrimitive();
        }

        public static boolean constantPrimitiveIsArray() {
            return int.class.isArray();
        }

        public static boolean constantPrimitiveIsInterface() {
            return int.class.isInterface();
        }

        public static boolean constantPrimitiveIsHidden() {
            return int.class.isHidden();
        }

        public static int constantPrimitiveGetModifiers() {
            return int.class.getModifiers();
        }

        public static boolean constantVoidIsPrimitive() {
            return void.class.isPrimitive();
        }

        public static int constantVoidGetModifiers() {
            return void.class.getModifiers();
        }

        public static boolean constantPrimitiveIsInstance(Object object) {
            return int.class.isInstance(object);
        }

        public static boolean constantStringIsInstance(Object object) {
            return String.class.isInstance(object);
        }

        public static Class<?> constantStringGetSuperclass() {
            return String.class.getSuperclass();
        }

        public static Class<?> constantObjectGetSuperclass() {
            return Object.class.getSuperclass();
        }

        public static Class<?> constantArrayGetSuperclass() {
            return String[].class.getSuperclass();
        }

        public static Class<?> constantPrimitiveGetSuperclass() {
            return int.class.getSuperclass();
        }

        public static int constantGetClassAccessFlags() {
            return Reflection.getClassAccessFlags(String.class);
        }

        public static int constantPrimitiveGetClassAccessFlags() {
            return Reflection.getClassAccessFlags(int.class);
        }

        public static Object constantStringCast(Object object) {
            return String.class.cast(object);
        }

        public static Object constantStringCastTyped(String object) {
            return String.class.cast(object);
        }

        public static boolean constantStringIsInstanceTyped(Integer object) {
            return String.class.isInstance(object);
        }

        public static boolean constantAssignableTrue() {
            return Object.class.isAssignableFrom(String.class);
        }

        public static boolean constantAssignableFalse() {
            return String.class.isAssignableFrom(Object.class);
        }

        public static boolean constantPrimitiveAssignableTrue() {
            return int.class.isAssignableFrom(int.class);
        }

        public static boolean constantPrimitiveAssignableFalse() {
            return int.class.isAssignableFrom(long.class);
        }

        public static boolean constantReceiverAssignable(Class<?> other) {
            return Object.class.isAssignableFrom(other);
        }

        public static Class<?> queryGetSuperclass(Class<?> klass) {
            return klass.getSuperclass();
        }

        public static Object queryCast(Class<?> klass, Object object) {
            return klass.cast(object);
        }

        public static boolean queryIsAssignableFrom(Class<?> klass, Class<?> other) {
            return klass.isAssignableFrom(other);
        }

        public static int queryGetClassAccessFlags(Class<?> klass) {
            return Reflection.getClassAccessFlags(klass);
        }

        private static boolean oracleIsInstance(Class<?> klass, Object object) {
            return klass.isInstance(object);
        }

        private static int oracleGetModifiers(Class<?> klass) {
            return klass.getModifiers();
        }

        private static Class<?> oracleGetSuperclass(Class<?> klass) {
            return klass.getSuperclass();
        }

        private static int oracleGetClassAccessFlags(Class<?> klass) {
            return Reflection.getClassAccessFlags(klass);
        }
    }
}
