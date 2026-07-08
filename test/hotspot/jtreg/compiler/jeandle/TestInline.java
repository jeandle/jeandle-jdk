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

/**
 * @test test inline feature for jeandle compiler
 * @library /test/lib /
 * @build jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run driver compiler.jeandle.TestInline
 */

package compiler.jeandle;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;
import jdk.test.whitebox.WhiteBox;

public class TestInline {
    private static final String CLASS = TestInline.class.getName();
    private static WhiteBox wb;

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            runDriver();
            return;
        }
        if ("run".equals(args[0])) {
            runWorkload();
            return;
        }
        throw new IllegalArgumentException("unknown mode: " + args[0]);
    }

    private static void runDriver() throws Exception {
        OutputAnalyzer output = runTestProcess();
        output.shouldHaveExitValue(0);

        assertInlined(output, "callerSimple", "calleeSimple");
        assertInlined(output, "callerWithException", "calleeThrow");
        assertInlined(output, "callerWithLock", "calleeWithLock");
        assertInlined(output, "callerDeopt", "calleeDeopt");
        assertInlined(output, "callerWithBranch", "calleeBranch");
        assertInlined(output, "callerWithLoop", "calleeLoop");
        assertInlined(output, "callerChained", "calleeB");
        assertInlinedCount(output, "callerRepeatedCallee", "calleeRepeated", 2);
        assertNotInlined(output, "callerDontInline", "helperDontInline");
    }

    private static OutputAnalyzer runTestProcess() throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("-Xbootclasspath/a:.");
        cmd.add("-XX:+UnlockDiagnosticVMOptions");
        cmd.add("-XX:+WhiteBoxAPI");
        cmd.add("-XX:+UseJeandleCompiler");
        cmd.add("-XX:+JeandlePrintInlineTree");
        cmd.add("-XX:-TieredCompilation");
        cmd.add("-Xbatch");
        cmd.add("-XX:CompileCommand=quiet");
        cmd.add("-XX:CompileCommand=compileonly," + CLASS + "::caller*");
        cmd.add("-XX:CompileCommand=compileonly," + CLASS + "::callee*");
        cmd.add("-XX:CompileCommand=compileonly," + CLASS + "::helperDontInline");
        cmd.add("-XX:CompileCommand=inline," + CLASS + "::callee*");
        cmd.add("-XX:CompileCommand=dontinline," + CLASS + "::helperDontInline");
        cmd.add(CLASS);
        cmd.add("run");

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(cmd);
        return ProcessTools.executeProcess(pb);
    }

    private static void assertInlined(OutputAnalyzer output, String rootMethod, String calleeMethod) {
        List<String> tree = inlineTreeFor(output, rootMethod);
        Asserts.assertTrue(containsMethod(tree, calleeMethod),
                "expected " + calleeMethod + " to be inlined into " + rootMethod + "\n" + formatTree(tree));
    }

    private static void assertNotInlined(OutputAnalyzer output, String rootMethod, String calleeMethod) {
        List<String> tree = inlineTreeFor(output, rootMethod);
        Asserts.assertFalse(containsMethod(tree, calleeMethod),
                "expected " + calleeMethod + " not to be inlined into " + rootMethod + "\n" + formatTree(tree));
    }

    private static void assertInlinedCount(OutputAnalyzer output, String rootMethod, String calleeMethod, int expectedCount) {
        List<String> tree = inlineTreeFor(output, rootMethod);
        int count = countMethod(tree, calleeMethod);
        Asserts.assertEquals(count, expectedCount,
                "unexpected inline count for " + calleeMethod + " in " + rootMethod + "\n" + formatTree(tree));
    }

    private static List<String> inlineTreeFor(OutputAnalyzer output, String rootMethod) {
        List<String> lines = output.asLines();
        List<String> lastMatch = null;
        for (int i = 0; i < lines.size(); i++) {
            if (!"Jeandle inline tree:".equals(lines.get(i))) {
                continue;
            }
            if (++i >= lines.size()) {
                break;
            }
            List<String> tree = new ArrayList<>();
            tree.add(lines.get(i));
            for (int j = i + 1; j < lines.size(); j++) {
                String line = lines.get(j);
                if ("Jeandle inline tree:".equals(line)) {
                    break;
                }
                if (line.startsWith("`- ") || line.startsWith("|- ") ||
                    line.startsWith("   ") || line.startsWith("|  ")) {
                    tree.add(line);
                    continue;
                }
                break;
            }
            if (containsMethodName(tree.get(0), rootMethod)) {
                lastMatch = tree;
            }
        }
        if (lastMatch != null) {
            return lastMatch;
        }
        throw new AssertionError("did not find Jeandle inline tree for " + rootMethod + "\n" + output.getOutput());
    }

    private static boolean containsMethod(List<String> tree, String method) {
        return countMethod(tree, method) > 0;
    }

    private static int countMethod(List<String> tree, String method) {
        int count = 0;
        for (int i = 1; i < tree.size(); i++) {
            if (containsMethodName(tree.get(i), method)) {
                count++;
            }
        }
        return count;
    }

    private static boolean containsMethodName(String line, String method) {
        String marker = "::" + method;
        int idx = line.indexOf(marker);
        if (idx < 0) {
            return false;
        }
        int end = idx + marker.length();
        return end == line.length() || line.charAt(end) == '(';
    }

    private static String formatTree(List<String> tree) {
        return String.join(System.lineSeparator(), tree);
    }

    private static void runWorkload() throws Exception {
        wb = WhiteBox.getWhiteBox();
        testSimpleInline();
        testInlineWithException();
        testInlineWithLock();
        testInlineWithDeopt();
        testInlineWithBranch();
        testInlineWithLoop();
        testInlineChained();
        testInlineRepeatedCallee();
        testDontInline();
    }

    // -------------------------------------------------------
    // 1. Simple inline: callee returns a computed value
    // -------------------------------------------------------
    public static int calleeSimple(int x) {
        return x * 2 + 1;
    }

    public static int callerSimple() {
        int sum = 0;
        for (int i = 0; i < 100_000; i++) {
            sum += calleeSimple(i);
        }
        return sum;
    }

    static void testSimpleInline() throws Exception {
        int result = callerSimple();
        int expected = 0;
        for (int i = 0; i < 100_000; i++) {
            expected += i * 2 + 1;
        }
        Asserts.assertEquals(result, expected);

        Method m = TestInline.class.getDeclaredMethod("callerSimple");
        Asserts.assertTrue(wb.isMethodCompiled(m));
    }

    // -------------------------------------------------------
    // 2. Inline with exception: callee throws, caller catches
    // -------------------------------------------------------
    public static int calleeThrow(int x) {
        if (x < 0) {
            throw new IllegalArgumentException("negative");
        }
        return x;
    }

    public static int callerWithException() {
        int sum = 0;
        for (int i = -10; i < 100_000; i++) {
            try {
                sum += calleeThrow(i);
            } catch (IllegalArgumentException e) {
                sum += 1;
            }
        }
        return sum;
    }

    static void testInlineWithException() throws Exception {
        int result = callerWithException();
        int expected = 10;
        for (int i = 0; i < 100_000; i++) {
            expected += i;
        }
        Asserts.assertEquals(result, expected);

        Method m = TestInline.class.getDeclaredMethod("callerWithException");
        Asserts.assertTrue(wb.isMethodCompiled(m));
    }

    // -------------------------------------------------------
    // 3. Inline with lock: callee uses synchronized block
    // -------------------------------------------------------
    private static long lockCounter = 0;
    private static final Object lockObj = new Object();

    public static void calleeWithLock(long val) {
        synchronized (lockObj) {
            lockCounter += val;
        }
    }

    public static long callerWithLock() {
        for (int i = 0; i < 100_000; i++) {
            calleeWithLock(i);
        }
        return lockCounter;
    }

    static void testInlineWithLock() throws Exception {
        lockCounter = 0;
        long result = callerWithLock();
        long expected = 0;
        for (int i = 0; i < 100_000; i++) {
            expected += i;
        }
        Asserts.assertEquals(result, expected);

        Method m = TestInline.class.getDeclaredMethod("callerWithLock");
        Asserts.assertTrue(wb.isMethodCompiled(m));
    }

    // -------------------------------------------------------
    // 4. Inline with deoptimization: callee's type assumption
    //    is invalidated at runtime, triggering deopt.
    // -------------------------------------------------------
    interface I { int value(); }

    static class A implements I {
        public int value() { return 1; }
    }

    static class B implements I {
        public int value() { return 2; }
    }

    public static int calleeDeopt(I obj) {
        return obj.value();
    }

    public static int callerDeopt(I obj) {
        int sum = 0;
        for (int i = 0; i < 100_000; i++) {
            sum += calleeDeopt(obj);
        }
        return sum;
    }

    static void testInlineWithDeopt() throws Exception {
        int result = callerDeopt(new A());
        Asserts.assertEquals(result, 100_000);

        Method m = TestInline.class.getDeclaredMethod("callerDeopt", I.class);
        Asserts.assertTrue(wb.isMethodCompiled(m));

        int result2 = callerDeopt(new B());
        Asserts.assertEquals(result2, 200_000);
    }

    // -------------------------------------------------------
    // 5. Inline with branch: callee has conditional logic
    // -------------------------------------------------------
    public static int calleeBranch(int x) {
        if (x % 2 == 0) {
            return x / 2;
        } else {
            return x * 3 + 1;
        }
    }

    public static int callerWithBranch() {
        int sum = 0;
        for (int i = 0; i < 100_000; i++) {
            sum += calleeBranch(i);
        }
        return sum;
    }

    static void testInlineWithBranch() throws Exception {
        int result = callerWithBranch();
        int expected = 0;
        for (int i = 0; i < 100_000; i++) {
            expected += calleeBranch(i);
        }
        Asserts.assertEquals(result, expected);

        Method m = TestInline.class.getDeclaredMethod("callerWithBranch");
        Asserts.assertTrue(wb.isMethodCompiled(m));
    }

    // -------------------------------------------------------
    // 6. Inline with loop: callee contains a loop
    // -------------------------------------------------------
    public static int calleeLoop(int n) {
        int s = 0;
        for (int i = 0; i < n; i++) {
            s += i;
        }
        return s;
    }

    public static int callerWithLoop() {
        int sum = 0;
        for (int i = 0; i < 100_000; i++) {
            sum += calleeLoop(5);
        }
        return sum;
    }

    static void testInlineWithLoop() throws Exception {
        int result = callerWithLoop();
        Asserts.assertEquals(result, 100_000 * 10);

        Method m = TestInline.class.getDeclaredMethod("callerWithLoop");
        Asserts.assertTrue(wb.isMethodCompiled(m));
    }

    // -------------------------------------------------------
    // 7. Chained call: verify the caller inlines its direct callee and keeps
    //    correct behavior when that callee contains another call.
    // -------------------------------------------------------
    public static int calleeC(int x) {
        return x + 1;
    }

    public static int calleeB(int x) {
        return calleeC(x) * 2;
    }

    public static int callerChained() {
        int sum = 0;
        for (int i = 0; i < 100_000; i++) {
            sum += calleeB(i);
        }
        return sum;
    }

    static void testInlineChained() throws Exception {
        int result = callerChained();
        int expected = 0;
        for (int i = 0; i < 100_000; i++) {
            expected += (i + 1) * 2;
        }
        Asserts.assertEquals(result, expected);

        Method m = TestInline.class.getDeclaredMethod("callerChained");
        Asserts.assertTrue(wb.isMethodCompiled(m));
    }

    // -------------------------------------------------------
    // 8. Same callee at multiple call sites.
    // -------------------------------------------------------
    public static int calleeRepeated(int x) {
        return x + 7;
    }

    public static int callerRepeatedCallee() {
        int sum = 0;
        for (int i = 0; i < 100_000; i++) {
            sum += calleeRepeated(i);
            sum += calleeRepeated(i + 1);
        }
        return sum;
    }

    static void testInlineRepeatedCallee() throws Exception {
        int result = callerRepeatedCallee();
        int expected = 0;
        for (int i = 0; i < 100_000; i++) {
            expected += i + 7;
            expected += i + 8;
        }
        Asserts.assertEquals(result, expected);

        Method m = TestInline.class.getDeclaredMethod("callerRepeatedCallee");
        Asserts.assertTrue(wb.isMethodCompiled(m));
    }

    // -------------------------------------------------------
    // 9. Explicitly disabled inline.
    // -------------------------------------------------------
    public static int helperDontInline(int x) {
        return x * 17 + 3;
    }

    public static int callerDontInline() {
        int sum = 0;
        for (int i = 0; i < 100_000; i++) {
            sum += helperDontInline(i);
        }
        return sum;
    }

    static void testDontInline() throws Exception {
        int result = callerDontInline();
        int expected = 0;
        for (int i = 0; i < 100_000; i++) {
            expected += i * 17 + 3;
        }
        Asserts.assertEquals(result, expected);

        Method m = TestInline.class.getDeclaredMethod("callerDontInline");
        Asserts.assertTrue(wb.isMethodCompiled(m));
    }
}
