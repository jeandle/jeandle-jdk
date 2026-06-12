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
 * @test TestConstantFieldFolding.java
 * @summary Test ConstantFieldFolding pass folds constant Java fields
 * @library /test/lib /
 * @run driver TestConstantFieldFolding
 */

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestConstantFieldFolding {

    // =========================================================================
    // Test data: constant fields of various types
    // =========================================================================

    static final int CONST_INT = 42;
    static final long CONST_LONG = 123456789L;
    static final float CONST_FLOAT = 3.14f;
    static final double CONST_DOUBLE = 2.718;
    static final boolean CONST_BOOLEAN = true;
    static final byte CONST_BYTE = -7;
    static final char CONST_CHAR = 'Z';
    static final short CONST_SHORT = -300;
    static final String CONST_STRING = "hello";
    static final Object CONST_NULL = null;

    static int nonFinalField = 99;

    static class Inner {
        final int value;
        Inner(int v) { this.value = v; }
    }

    static class Holder {
        final Inner inner;
        Holder(Inner i) { this.inner = i; }
    }

    static final Holder CHAIN_HOLDER = new Holder(new Inner(77));

    enum Color { RED, GREEN, BLUE }

    // =========================================================================
    // Test methods — each exercises a specific folding scenario
    // =========================================================================

    static int testStaticFinalInt() {
        return CONST_INT;
    }

    static long testStaticFinalLong() {
        return CONST_LONG;
    }

    static float testStaticFinalFloat() {
        return CONST_FLOAT;
    }

    static double testStaticFinalDouble() {
        return CONST_DOUBLE;
    }

    static boolean testStaticFinalBoolean() {
        return CONST_BOOLEAN;
    }

    static byte testStaticFinalByte() {
        return CONST_BYTE;
    }

    static char testStaticFinalChar() {
        return CONST_CHAR;
    }

    static short testStaticFinalShort() {
        return CONST_SHORT;
    }

    static String testStaticFinalObject() {
        return CONST_STRING;
    }

    static Object testStaticFinalNull() {
        return CONST_NULL;
    }

    static int testObjectChain() {
        return CHAIN_HOLDER.inner.value;
    }

    static int testNonConstant() {
        return nonFinalField;
    }

    static int testEnumOrdinal() {
        return Color.GREEN.ordinal();
    }

    // =========================================================================
    // Driver infrastructure
    // =========================================================================

    static String extractBeforeIR(String output, String methodPattern) {
        String[] lines = output.split("\\n");
        StringBuilder sb = new StringBuilder();
        boolean inSection = false;
        for (String line : lines) {
            if (line.contains("IR Dump Before ConstantFieldFolding") &&
                line.contains(methodPattern)) {
                inSection = true;
                continue;
            }
            if (inSection && line.contains("*** IR Dump ")) {
                break;
            }
            if (inSection) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }

    static String extractAfterIR(String output, String methodPattern) {
        String[] lines = output.split("\\n");
        StringBuilder sb = new StringBuilder();
        boolean inSection = false;
        for (String line : lines) {
            if (line.contains("IR Dump After ConstantFieldFolding") &&
                line.contains(methodPattern)) {
                inSection = true;
                continue;
            }
            if (inSection && line.contains("*** IR Dump ")) {
                break;
            }
            if (inSection) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }

    private static final String LLVM_OPTIONS =
        "-XX:JeandleLLVMOptions=--print-before=constant-field-folding --print-after=constant-field-folding";

    private static final String[] BASE_ARGS = {
        "-Xcomp", "-Xbatch", "-XX:-TieredCompilation",
        "-XX:+UseJeandleCompiler"
    };

    private static OutputAnalyzer runTestProcess(String testName, String compileOnly) throws Exception {
        List<String> cmd = new ArrayList<>();
        String testClassPath = System.getProperty("test.classes", ".");
        cmd.add("-Dtest.classes=" + testClassPath);
        cmd.addAll(Arrays.asList(BASE_ARGS));
        cmd.add(LLVM_OPTIONS);
        cmd.add("-XX:CompileCommand=compileonly,TestConstantFieldFolding::" + compileOnly);
        cmd.add("TestConstantFieldFolding");
        cmd.add(testName);

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(cmd);
        return ProcessTools.executeProcess(pb);
    }

    private static void runChildTest(String testName) {
        switch (testName) {
            case "testStaticFinalInt":
                Asserts.assertEquals(testStaticFinalInt(), 42);
                break;
            case "testStaticFinalLong":
                Asserts.assertEquals(testStaticFinalLong(), 123456789L);
                break;
            case "testStaticFinalFloat":
                Asserts.assertEquals(testStaticFinalFloat(), 3.14f);
                break;
            case "testStaticFinalDouble":
                Asserts.assertEquals(testStaticFinalDouble(), 2.718);
                break;
            case "testStaticFinalBoolean":
                Asserts.assertTrue(testStaticFinalBoolean());
                break;
            case "testStaticFinalByte":
                Asserts.assertEquals(testStaticFinalByte(), (byte) -7);
                break;
            case "testStaticFinalChar":
                Asserts.assertEquals(testStaticFinalChar(), 'Z');
                break;
            case "testStaticFinalShort":
                Asserts.assertEquals(testStaticFinalShort(), (short) -300);
                break;
            case "testStaticFinalObject":
                Asserts.assertEquals(testStaticFinalObject(), "hello");
                break;
            case "testStaticFinalNull":
                Asserts.assertNull(testStaticFinalNull());
                break;
            case "testObjectChain":
                Asserts.assertEquals(testObjectChain(), 77);
                break;
            case "testNonConstant":
                Asserts.assertEquals(testNonConstant(), 99);
                break;
            case "testEnumOrdinal":
                Asserts.assertEquals(testEnumOrdinal(), 1);
                break;
            default:
                throw new IllegalArgumentException("Unknown test: " + testName);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            runChildTest(args[0]);
            return;
        }

        // --- Static final int ---
        {
            OutputAnalyzer output = runTestProcess("testStaticFinalInt", "testStaticFinalInt");
            output.shouldHaveExitValue(0);
            String afterIR = extractAfterIR(output.getOutput(), "testStaticFinalInt");
            Asserts.assertTrue(afterIR.contains("i32 42") || afterIR.contains("ret i32 42"),
                "Static final int should be folded to 42. After IR:\n" + afterIR);
        }

        // --- Static final long ---
        {
            OutputAnalyzer output = runTestProcess("testStaticFinalLong", "testStaticFinalLong");
            output.shouldHaveExitValue(0);
            String afterIR = extractAfterIR(output.getOutput(), "testStaticFinalLong");
            Asserts.assertTrue(afterIR.contains("i64 123456789") || afterIR.contains("ret i64 123456789"),
                "Static final long should be folded. After IR:\n" + afterIR);
        }

        // --- Static final float ---
        {
            OutputAnalyzer output = runTestProcess("testStaticFinalFloat", "testStaticFinalFloat");
            output.shouldHaveExitValue(0);
        }

        // --- Static final double ---
        {
            OutputAnalyzer output = runTestProcess("testStaticFinalDouble", "testStaticFinalDouble");
            output.shouldHaveExitValue(0);
        }

        // --- Static final boolean ---
        {
            OutputAnalyzer output = runTestProcess("testStaticFinalBoolean", "testStaticFinalBoolean");
            output.shouldHaveExitValue(0);
        }

        // --- Static final object (String) ---
        {
            OutputAnalyzer output = runTestProcess("testStaticFinalObject", "testStaticFinalObject");
            output.shouldHaveExitValue(0);
            String afterIR = extractAfterIR(output.getOutput(), "testStaticFinalObject");
            Asserts.assertTrue(afterIR.contains("oop_handle"),
                "Static final object should produce an oop_handle load. After IR:\n" + afterIR);
        }

        // --- Static final null ---
        {
            OutputAnalyzer output = runTestProcess("testStaticFinalNull", "testStaticFinalNull");
            output.shouldHaveExitValue(0);
            String afterIR = extractAfterIR(output.getOutput(), "testStaticFinalNull");
            Asserts.assertTrue(afterIR.contains("null") || afterIR.contains("zeroinitializer"),
                "Static final null should be folded to null. After IR:\n" + afterIR);
        }

        // --- Object chain ---
        {
            OutputAnalyzer output = runTestProcess("testObjectChain", "testObjectChain");
            output.shouldHaveExitValue(0);
            String afterIR = extractAfterIR(output.getOutput(), "testObjectChain");
            Asserts.assertTrue(afterIR.contains("i32 77") || afterIR.contains("ret i32 77"),
                "Object chain should be folded to 77. After IR:\n" + afterIR);
        }

        // --- Non-constant field (should NOT be folded) ---
        {
            OutputAnalyzer output = runTestProcess("testNonConstant", "testNonConstant");
            output.shouldHaveExitValue(0);
            String beforeIR = extractBeforeIR(output.getOutput(), "testNonConstant");
            String afterIR = extractAfterIR(output.getOutput(), "testNonConstant");
            int beforeLoads = countOccurrences(beforeIR, "load i32");
            int afterLoads = countOccurrences(afterIR, "load i32");
            Asserts.assertEquals(beforeLoads, afterLoads,
                "Non-constant field should not be folded. before=" + beforeLoads +
                " after=" + afterLoads);
        }

        // --- Enum ordinal ---
        {
            OutputAnalyzer output = runTestProcess("testEnumOrdinal", "testEnumOrdinal");
            output.shouldHaveExitValue(0);
        }

        System.out.println("All ConstantFieldFolding tests passed.");
    }

    static int countOccurrences(String text, String pattern) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(pattern, idx)) != -1) {
            count++;
            idx += pattern.length();
        }
        return count;
    }
}
