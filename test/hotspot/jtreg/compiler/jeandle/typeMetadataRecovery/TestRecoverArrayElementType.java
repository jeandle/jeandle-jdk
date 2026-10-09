/*
 * Copyright (c) 2026, the Jeandle-JDK Authors. All Rights Reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details (a copy is included in the
 * LICENSE file that accompanied this code).
 *
 * You should have received a copy of the GNU General Public License
 * version 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 */

/*
 * @test TestRecoverArrayElementType.java
 * @summary Prove RecoverTypeInfo attaches !java-klass metadata to aaloads
 *          (array element loads) on the LLVM side, using the array's klass --
 *          including the sharpened klass proved by a dominating instanceof.
 *          The frontend deliberately leaves aaloads untyped (typing them during
 *          IR construction would query getJavaType on incomplete IR), so the
 *          first RecoverTypeInfo run in the pipeline is what attaches the
 *          element klass: its BEFORE section has no !java-klass on the aaload
 *          and its AFTER section does. Exactness doubles as the sharpening
 *          proof: the element of Animal[] is not effectively final (no
 *          !java-klass-exact), while the element of the sharpened Dog[] is
 *          (so !java-klass-exact appears). Also covers a negative
 *          interface-element case (no attachment -- proves we do not
 *          fabricate).
 * @library /test/lib /
 * @run driver TestRecoverArrayElementType
 */

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestRecoverArrayElementType {

    // =========================================================================
    // Type hierarchy. Dog is declared final while Animal is not, which is what
    // makes !java-klass-exact a usable sharpening witness (the VM's
    // is_effectively_final requires a final class declaration for instance
    // klasses):
    //   * the element klass of a declared Animal[] is Animal -> !java-klass
    //     without -exact;
    //   * the element klass of a declared (or instanceof-sharpened) Dog[] is
    //     Dog -> !java-klass WITH -exact.
    // =========================================================================

    static class Animal { }
    interface Pet { }
    static final class Dog extends Animal implements Pet { }

    // =========================================================================
    // Scenarios under test. Each reads the same array element twice; both
    // aaloads must be typed by RecoverTypeInfo.
    // =========================================================================

    // S1 (control): declared Animal[]. Element klass Animal is not effectively
    // final -> !java-klass attached, but never !java-klass-exact.
    static Animal testPlainArray(Animal[] arr) {
        Animal a = arr[0];
        Animal b = arr[0];
        return (a == b) ? a : b;
    }

    // S2: declared Dog[]. Element klass Dog is effectively final
    // -> !java-klass AND !java-klass-exact attached.
    static Dog testExactElementArray(Dog[] arr) {
        Dog a = arr[0];
        Dog b = arr[0];
        return (a == b) ? a : b;
    }

    // S3 (context-sensitive proof): declared Animal[], but the dominating
    // `instanceof Dog[]` sharpens the array's type to Dog[] at the aaload. The
    // element metadata must use the sharpened element klass Dog (effectively
    // final -> !java-klass-exact), which the declared Animal[] type alone
    // could never yield (S1 control shows no -exact there).
    static Animal testSharpenedArray(Animal[] arr) {
        if (arr instanceof Dog[]) {
            Animal a = arr[0];
            Animal b = arr[0];
            return (a == b) ? a : b;
        }
        return null;
    }

    // S4 (negative): declared Pet[]. The element klass is the unverified
    // interface Pet -> no metadata before OR after, in every run, proving we
    // do not fabricate.
    static Pet testInterfaceElementArray(Pet[] arr) {
        Pet a = arr[0];
        Pet b = arr[0];
        return (a == b) ? a : b;
    }

    // =========================================================================
    // Driver / child dispatch (same protocol as TestRecoverTypeInfo).
    // =========================================================================

    public static void main(String[] args) throws Exception {
        // Force-load every referenced class so it and its oop_handle global
        // exist before compilation of the scenario methods.
        Class.forName("TestRecoverArrayElementType$Animal");
        Class.forName("TestRecoverArrayElementType$Pet");
        Class.forName("TestRecoverArrayElementType$Dog");

        if (args.length == 0) {
            runAllTests();
        } else {
            runChildTest(args[0]);
        }
    }

    private static void runChildTest(String testName) {
        switch (testName) {
            case "testPlainArray": {
                Animal[] arr = { new Dog() };
                Animal r = testPlainArray(arr);
                Asserts.assertEquals(r.getClass(), Dog.class,
                    "testPlainArray must return the stored Dog");
                break;
            }
            case "testExactElementArray": {
                Dog[] arr = { new Dog() };
                Dog r = testExactElementArray(arr);
                Asserts.assertEquals(r.getClass(), Dog.class,
                    "testExactElementArray must return the stored Dog");
                break;
            }
            case "testSharpenedArray": {
                Animal[] arr = new Dog[] { new Dog() };
                Animal r = testSharpenedArray(arr);
                Asserts.assertEquals(r.getClass(), Dog.class,
                    "testSharpenedArray must return the stored Dog");
                break;
            }
            case "testInterfaceElementArray": {
                Pet[] arr = { new Dog() };
                Pet r = testInterfaceElementArray(arr);
                Asserts.assertEquals(r.getClass(), Dog.class,
                    "testInterfaceElementArray must return the stored Pet");
                break;
            }
            default:
                throw new IllegalArgumentException("Unknown test: " + testName);
        }
    }

    // =========================================================================
    // Child JVM spawn.
    // =========================================================================

    private static final String LLVM_OPTIONS =
        "-XX:JeandleLLVMOptions=--print-before=recover-type-info --print-after=recover-type-info";

    private static final String[] BASE_ARGS = {
        "-Xcomp", "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler"
    };

    private static OutputAnalyzer runTestProcess(String testName, String compileOnly) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("-Dtest.classes=" + System.getProperty("test.classes", "."));
        cmd.addAll(Arrays.asList(BASE_ARGS));
        cmd.add(LLVM_OPTIONS);
        cmd.add("-XX:CompileCommand=compileonly,TestRecoverArrayElementType::" + compileOnly);
        cmd.add("TestRecoverArrayElementType");
        cmd.add(testName);

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(cmd);
        return ProcessTools.executeProcess(pb);
    }

    // =========================================================================
    // IR-section slicing helpers (same as TestRecoverTypeInfo).
    // =========================================================================

    /** Count the "IR Dump <phase> RecoverTypeInfo" section headers that also
     *  contain methodPattern (the lowered LLVM function suffix). */
    static int countSections(String stderr, String phase, String methodPattern) {
        String header = "IR Dump " + phase + " RecoverTypeInfo";
        int c = 0;
        for (String line : stderr.split("\\n"))
            if (line.contains(header) && line.contains(methodPattern)) c++;
        return c;
    }

    /** Extract the Nth (0-based) "IR Dump <phase> RecoverTypeInfo ...methodPattern"
     *  section body (lines until the next "*** IR Dump " marker). */
    static String extractNthIR(String stderr, String phase, String methodPattern, int n) {
        String header = "IR Dump " + phase + " RecoverTypeInfo";
        String[] lines = stderr.split("\\n");
        StringBuilder sb = new StringBuilder();
        boolean inSection = false;
        int matched = -1;
        for (String line : lines) {
            if (line.contains(header) && line.contains(methodPattern)) {
                matched++;
                if (matched == n) { inSection = true; continue; } // start this section
                if (inSection) break;                              // a later matching header closes us
            }
            if (inSection && line.contains("*** IR Dump ")) { inSection = false; break; }
            if (inSection) sb.append(line).append("\n");
        }
        return sb.toString();
    }

    /** A single before/after RecoverTypeInfo run, with the !java-klass /
     *  !java-klass-exact load-attachment counts in each. */
    static final class RunPair {
        final int index;
        final int beforeKlass;   // "!java-klass !" occurrences in the BEFORE section
        final int afterKlass;    //                              ... in the AFTER  section
        final int beforeExact;   // "!java-klass-exact !"    in BEFORE
        final int afterExact;    //                          in AFTER
        RunPair(int i, int bk, int ak, int be, int ae) {
            index = i; beforeKlass = bk; afterKlass = ak; beforeExact = be; afterExact = ae;
        }
        @Override public String toString() {
            return String.format("run#%d before{klass=%d, exact=%d} after{klass=%d, exact=%d}",
                index, beforeKlass, beforeExact, afterKlass, afterExact);
        }
    }

    /** Collect every before/after RecoverTypeInfo run pair for the function whose
     *  mangled name contains methodPattern. Runs come in Before/After matched
     *  pairs in stream order. */
    static List<RunPair> collectRunPairs(String stderr, String methodPattern) {
        int pairs = Math.min(countSections(stderr, "Before", methodPattern),
                             countSections(stderr, "After",  methodPattern));
        List<RunPair> result = new ArrayList<>();
        for (int i = 0; i < pairs; i++) {
            String beforeIR = extractNthIR(stderr, "Before", methodPattern, i);
            String afterIR  = extractNthIR(stderr, "After",  methodPattern, i);
            result.add(new RunPair(i,
                countOccurrences(beforeIR, "!java-klass !"),
                countOccurrences(afterIR,  "!java-klass !"),
                countOccurrences(beforeIR, "!java-klass-exact !"),
                countOccurrences(afterIR,  "!java-klass-exact !")));
        }
        return result;
    }

    static int countOccurrences(String text, String pattern) {
        int count = 0, idx = 0;
        while ((idx = text.indexOf(pattern, idx)) != -1) {
            count++;
            idx += pattern.length();
        }
        return count;
    }

    // =========================================================================
    // Assertion runners.
    //
    // The frontend attaches no !java-klass to aaloads, so the FIRST
    // RecoverTypeInfo run on a scenario method always has beforeKlass == 0;
    // attachment is proven by a strict 0 -> >=1 rise. (The scenarios contain no
    // oop field loads, so "!java-klass !" occurrences can only come from the
    // aaloads.)
    // =========================================================================

    /** Common positive check: some run attaches !java-klass where its BEFORE
     *  had none. Returns the collected run pairs for further assertions. */
    private static List<RunPair> runRecoveryCheck(String name) throws Exception {
        OutputAnalyzer out = runTestProcess(name, name);
        out.shouldHaveExitValue(0);
        String stderr = out.getOutput();
        String suffix = "_" + name;

        int beforeSecs = countSections(stderr, "Before", suffix);
        Asserts.assertGTE(beforeSecs, 2,
            name + ": RecoverTypeInfo must run on at least the two pipeline "
            + "sites for a -Xcomp Jeandle-compiled method; got " + beforeSecs
            + " Before sections. Usually means the method was not compiled by "
            + "Jeandle, or --print-before/after=recover-type-info was not "
            + "plumbed.");

        List<RunPair> pairs = collectRunPairs(stderr, suffix);
        Asserts.assertTrue(!pairs.isEmpty(),
            name + ": expected at least one before/after RecoverTypeInfo run pair; got 0.");

        StringBuilder diag = new StringBuilder();
        for (RunPair p : pairs) diag.append("  ").append(p).append("\n");

        boolean foundRise = false;
        for (RunPair p : pairs) {
            if (p.beforeKlass == 0 && p.afterKlass >= 1 && p.afterKlass > p.beforeKlass) {
                foundRise = true;
                break;
            }
        }
        Asserts.assertTrue(foundRise,
            name + ": no RecoverTypeInfo run showed the expected 0 -> >=1 !java-klass "
            + "rise on the aaloads (the frontend attaches nothing; RecoverTypeInfo "
            + "must attach the element klass). Run pairs:\n" + diag);
        return pairs;
    }

    /** Element klass is NOT effectively final: metadata attached, but no run
     *  may carry !java-klass-exact. */
    private static void runNonExactCheck(String name) throws Exception {
        List<RunPair> pairs = runRecoveryCheck(name);
        StringBuilder diag = new StringBuilder();
        for (RunPair p : pairs) diag.append("  ").append(p).append("\n");
        for (RunPair p : pairs) {
            Asserts.assertEquals(p.afterExact, 0,
                name + ": element of Animal[] is not effectively final -- no run "
                + "may attach !java-klass-exact; " + p + "\n" + diag);
        }
    }

    /** Element klass is effectively final: some run must attach
     *  !java-klass-exact alongside !java-klass. */
    private static void runExactCheck(String name) throws Exception {
        List<RunPair> pairs = runRecoveryCheck(name);
        StringBuilder diag = new StringBuilder();
        for (RunPair p : pairs) diag.append("  ").append(p).append("\n");
        boolean foundExact = false;
        for (RunPair p : pairs) {
            if (p.afterExact >= 1) { foundExact = true; break; }
        }
        Asserts.assertTrue(foundExact,
            name + ": element klass is effectively final, so some run must attach "
            + "!java-klass-exact; none did. Run pairs:\n" + diag);
    }

    /** Negative check: unverified interface element -- no metadata in any run. */
    private static void runNoRecoveryCheck(String name) throws Exception {
        OutputAnalyzer out = runTestProcess(name, name);
        out.shouldHaveExitValue(0);
        String stderr = out.getOutput();
        String suffix = "_" + name;

        int beforeSecs = countSections(stderr, "Before", suffix);
        Asserts.assertGTE(beforeSecs, 2,
            name + ": RecoverTypeInfo must run on at least the two pipeline sites; got "
            + beforeSecs);

        List<RunPair> pairs = collectRunPairs(stderr, suffix);
        Asserts.assertTrue(!pairs.isEmpty(),
            name + ": expected at least one before/after run pair; got 0.");

        StringBuilder diag = new StringBuilder();
        for (RunPair p : pairs) diag.append("  ").append(p).append("\n");

        for (RunPair p : pairs) {
            Asserts.assertEquals(p.beforeKlass, 0,
                name + " (negative): interface-element aaload must have no !java-klass "
                + "in any before-section; " + p + "\n" + diag);
            Asserts.assertEquals(p.afterKlass, 0,
                name + " (negative): RecoverTypeInfo must NOT attach !java-klass to an "
                + "unverified-interface element in any after-section; " + p + "\n" + diag);
        }
    }

    // =========================================================================
    // runAllTests.
    // =========================================================================

    private static void runAllTests() throws Exception {
        // Control: declared Animal[] gets !java-klass (element Animal), never
        // !java-klass-exact (Animal has a subclass).
        runNonExactCheck("testPlainArray");
        // Declared Dog[] gets !java-klass AND !java-klass-exact (element Dog is
        // effectively final).
        runExactCheck("testExactElementArray");
        // Context-sensitive: Animal[] sharpened to Dog[] by a dominating
        // instanceof -> element Dog -> !java-klass-exact appears, which the
        // declared type alone could never produce (see the control above).
        runExactCheck("testSharpenedArray");
        // Negative: interface element, no metadata before or after, in every
        // run -- proves we do not fabricate type info.
        runNoRecoveryCheck("testInterfaceElementArray");

        System.out.println("All RecoverArrayElementType tests passed.");
    }
}
