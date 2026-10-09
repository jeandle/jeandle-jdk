/*
 * Copyright (c) 2026, the Jeandle-JDK Authors. All Rights Reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but
 * ANY WARRANTY; without the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License
 * version 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 */

/*
 * @test TestRecoverTypeInfo.java
 * @summary Prove RecoverTypeInfo re-attaches !java-klass metadata on oop field
 *          loads, inside the real Jeandle pipeline, after a load-CSE pass
 *          (EarlyCSE/InstCombine) strips it. RecoverTypeInfo runs several times
 *          per method -- twice at the pipeline level (Pipeline.cpp:66 after
 *          PreCHACleanup, Pipeline.cpp:85 after the inline driver) AND twice per
 *          changed inline round inside JeandleInlineDriver::runRootInstSimplify
 *          (driver:150, driver:157). Not every run is load-bearing: a run whose
 *          BEFORE already carries !java-klass is a no-op. The proof is the
 *          existence of a run pair whose BEFORE has NO !java-klass (something
 *          between it and the previous run stripped it) and whose AFTER has
 *          !java-klass re-attached with a strict 0 -> >=1 rise. Scans every run
 *          pair rather than fixing on one, since which run carries the
 *          strip<->recover depends on whether inlining fired. Also covers a
 *          two-level field chain and a negative interface-typed-field case
 *          (no recovery -- proves we do not fabricate).
 * @library /test/lib /
 * @run driver TestRecoverTypeInfo
 */

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestRecoverTypeInfo {

    // =========================================================================
    // Holder hierarchy.
    //
    // The receiver/parameter oop of every scenario carries a "java-klass" string
    // attribute (attached by the frontend in JeandleFuncSig::create_llvm_func),
    // which survives load CSE because it lives on the AttributeList. That
    // surviving attribute is the seed RecoverTypeInfo uses to recompute
    // GetFieldType(klass, offset) and re-attach !java-klass to field loads whose
    // metadata was stripped by EarlyCSE/InstCombine.
    //
    // Every scenario reads the same instance field twice. The redundant read gives
    // load CSE a load to dedup, which is what strips the custom !java-klass
    // metadata (EarlyCSE/InstCombine's combineMetadata / copyMetadataForLoad only
    // preserve LLVM built-in kinds; "java-klass" falls into the default stripping
    // branch).
    // =========================================================================

    static class Animal { }
    interface Pet { }
    static class Dog extends Animal implements Pet { }

    static class Holder {
        Animal animalField;   // declared type Animal: non-final (Dog is a subclass)
    }
    static class FinalFieldHolder {
        Dog dogField;         // declared type Dog: effectively final (no subclasses)
    }
    static class InterfaceFieldHolder {
        Pet petField;         // interface-typed: frontend never attaches, and
                              // RecoverTypeInfo's isUnverifiedInterface branch
                              // returns Bottom -> no re-attach (negative case).
    }
    static class ChainLink {
        Holder next;          // two-level chain: next.animalField
    }

    // =========================================================================
    // Scenarios under test. Each is a small pure static method that takes a
    // holder parameter and reads an oop field twice into locals.
    // =========================================================================

    // S1: intra-method EarlyCSE dedups the two h.animalField reads -> strips the
    // surviving load's !java-klass; RecoverTypeInfo re-attaches it.
    static Animal testRecoverAfterCSE(Holder h) {
        Animal a = h.animalField;
        Animal b = h.animalField;
        return (a == b) ? a : b;
    }

    // Inlined into S2. Stripping here is caused by the inline driver collapsing
    // this getter's load with the direct read in the caller.
    static Animal getAnimal(Holder h) {
        return h.animalField;
    }

    // S2: the canonical "inline driver strips" shape. getAnimal is inlined so
    // both reads become one load that EarlyCSE/InstCombine dedup.
    static Animal testRecoverThroughInlinedGetter(Holder h) {
        Animal a = getAnimal(h);
        Animal b = h.animalField;
        return (a == b) ? a : b;
    }

    // S3: a field whose declared type has no subclasses. RecoverTypeInfo
    // re-attaches !java-klass; whether the VM also marks it effectively-final
    // (-> !java-klass-exact) is build-dependent, so this case asserts only the
    // !java-klass rise, not -exact.
    static Dog testRecoverFinalField(FinalFieldHolder h) {
        Dog d = h.dogField;
        Dog e = h.dogField;
        return (d == e) ? d : e;
    }

    // S4: two-level chain. Load CSE dedups both link.next and
    // link.next.animalField; RecoverTypeInfo forwards the recovered 'next' klass
    // then recovers animalField. Both load sites should end up with !java-klass.
    static Animal testRecoverFieldChain(ChainLink link) {
        Animal a = link.next.animalField;
        Animal b = link.next.animalField;
        return (a == b) ? a : b;
    }

    // S5 (negative): interface-typed field. Frontend never attaches metadata
    // (is_unverified_interface skip in jeandleAbstractInterpreter do_get_xxx),
    // and RecoverTypeInfo's isUnverifiedInterface(FK) branch returns Bottom ->
    // no re-attach. No metadata before OR after, in every run, proving we do not
    // fabricate.
    static Pet testNoRecoverInterfaceField(InterfaceFieldHolder h) {
        Pet p = h.petField;
        Pet q = h.petField;
        return (p == q) ? p : q;
    }

    // =========================================================================
    // Driver / child dispatch.
    //
    // Driver mode (no args): spawn one child JVM per scenario, slice that child's
    // stderr for the RecoverTypeInfo before/after IR dumps and assert recovery.
    // Child mode (one arg): run a single scenario for functional correctness
    // (does NOT prove recovery -- the IR count assertions in the driver do).
    // =========================================================================

    public static void main(String[] args) throws Exception {
        // Force-load every holder so its oop_handle global exists and its
        // <clinit> has run before compilation of the scenario methods.
        Class.forName("TestRecoverTypeInfo$Animal");
        Class.forName("TestRecoverTypeInfo$Pet");
        Class.forName("TestRecoverTypeInfo$Dog");
        Class.forName("TestRecoverTypeInfo$Holder");
        Class.forName("TestRecoverTypeInfo$FinalFieldHolder");
        Class.forName("TestRecoverTypeInfo$InterfaceFieldHolder");
        Class.forName("TestRecoverTypeInfo$ChainLink");

        if (args.length == 0) {
            runAllTests();
        } else {
            runChildTest(args[0]);
        }
    }

    private static void runChildTest(String testName) {
        switch (testName) {
            case "testRecoverAfterCSE": {
                Holder h = new Holder();
                h.animalField = new Dog();
                Animal r = testRecoverAfterCSE(h);
                Asserts.assertEquals(r.getClass(), Dog.class,
                    "testRecoverAfterCSE must return the stored Dog");
                break;
            }
            case "testRecoverThroughInlinedGetter": {
                Holder h = new Holder();
                h.animalField = new Dog();
                Animal r = testRecoverThroughInlinedGetter(h);
                Asserts.assertEquals(r.getClass(), Dog.class,
                    "testRecoverThroughInlinedGetter must return the stored Dog");
                break;
            }
            case "testRecoverFinalField": {
                FinalFieldHolder h = new FinalFieldHolder();
                h.dogField = new Dog();
                Dog r = testRecoverFinalField(h);
                Asserts.assertEquals(r.getClass(), Dog.class,
                    "testRecoverFinalField must return the stored Dog");
                break;
            }
            case "testRecoverFieldChain": {
                ChainLink link = new ChainLink();
                link.next = new Holder();
                link.next.animalField = new Dog();
                Animal r = testRecoverFieldChain(link);
                Asserts.assertEquals(r.getClass(), Dog.class,
                    "testRecoverFieldChain must return the stored Dog");
                break;
            }
            case "testNoRecoverInterfaceField": {
                InterfaceFieldHolder h = new InterfaceFieldHolder();
                h.petField = new Dog();
                Pet r = testNoRecoverInterfaceField(h);
                Asserts.assertEquals(r.getClass(), Dog.class,
                    "testNoRecoverInterfaceField must return the stored Pet");
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
        cmd.add("-XX:CompileCommand=compileonly,TestRecoverTypeInfo::" + compileOnly);
        cmd.add("TestRecoverTypeInfo");
        cmd.add(testName);

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(cmd);
        return ProcessTools.executeProcess(pb);
    }

    // =========================================================================
    // IR-section slicing helpers.
    //
    // RecoverTypeInfo runs several times per compiled method -- at two pipeline
    // insertion points (Pipeline.cpp:66 after PreCHACleanup, and Pipeline.cpp:85
    // after the inline driver) AND twice per changed inline round inside
    // JeandleInlineDriver::runRootInstSimplify (driver:150 early, driver:157 end).
    // The exact run count depends on how many inline rounds changed IR: for a
    // method where the inline driver performs zero changed rounds it is just the
    // two pipeline-level runs; each changed inline round adds two more.
    //
    // Crucially, NOT every run is "recovering": a run whose BEFORE-section already
    // carries !java-klass did not have anything stripped since the previous run,
    // so it is a no-op (RecoverTypeInfo.cpp:480 skips loads that already have the
    // metadata). The LOAD-BEARING evidence of recovery is a single run whose
    // BEFORE-section has NO !java-klass (something between it and the previous run
    // -- EarlyCSE or InstCombine load CSE -- stripped it) and whose AFTER-section
    // has !java-klass re-attached. That stripping happens both in PreCHACleanup
    // (before Pipeline.cpp:66) and inside the inline driver (driver:153/154,
    // which driver:157 then recovers); which run carries the 0 -> >=1 rise depends
    // on the scenario and on whether inlining fired under -Xcomp, so the
    // assertions below scan EVERY before/after pair rather than fixating on one.
    //
    // Header line (from StandardInstrumentation PrintIRInstrumentation):
    //   ; *** IR Dump Before RecoverTypeInfo on <mangled-fn-signature> ***
    //   ; *** IR Dump After  RecoverTypeInfo on <mangled-fn-signature> ***
    // The mangled fn name (e.g. "TestRecoverTypeInfo_testRecoverAfterCSE(...)")
    // contains the scenario method suffix used to disambiguate this method's dumps
    // from the runtime-stub functions also present in the module.
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
    // Precise substring for load-attached !java-klass (NOT the param attribute
    // "java-klass"="5", NOT !java-klass-exact): "!java-klass !". Matches the load
    // attachment "!java-klass !N" from the LLVM-side test recover-instance-field.ll.
    //
    // The positive proof is the existence of at least one run whose BEFORE has NO
    // !java-klass (a load-CSE pass between it and the previous run stripped it)
    // and whose AFTER has >= 1 !java-klass re-attached with a strict rise
    // (afterKlass > beforeKlass). We scan every run pair rather than fixing on a
    // specific one, because which run carries the stripping<->recovery depends on
    // whether the inline driver performed a changed round under -Xcomp.
    //
    // NOTE: functional correctness (Asserts.assertEquals in runChildTest) only
    // proves the program didn't crash; it does NOT prove recovery happened. If
    // RecoverTypeInfo were broken, the program still returns the right object at
    // runtime. The IR count assertions below are the only load-bearing proof.
    // =========================================================================

    private static void runRecoveryCheck(String name) throws Exception {
        OutputAnalyzer out = runTestProcess(name, name);
        out.shouldHaveExitValue(0);
        String stderr = out.getOutput();
        String suffix = "_" + name;

        // -Xcomp routes every compiled leaf method through both pipeline-level
        // RecoverTypeInfo sites (Pipeline.cpp:66 after PreCHACleanup, and
        // Pipeline.cpp:85 after the inline driver). That is the floor: the pass
        // must have run at least twice on this method. Fewer means the method was
        // not Jeandle-compiled, or the print-before/after options were not plumbed.
        int beforeSecs = countSections(stderr, "Before", suffix);
        Asserts.assertGTE(beforeSecs, 2,
            name + ": RecoverTypeInfo must run on at least the two pipeline sites "
            + "(Pipeline.cpp:66, :85) for a -Xcomp Jeandle-compiled method; got "
            + beforeSecs + " Before sections. Usually means (a) the method was not "
            + "compiled by Jeandle, (b) --print-before/after=recover-type-info was "
            + "not plumbed, or (c) the scenario name suffix '" + suffix
            + "' does not match the mangled LLVM function name in the dump headers.");

        List<RunPair> pairs = collectRunPairs(stderr, suffix);
        Asserts.assertTrue(!pairs.isEmpty(),
            name + ": expected at least one before/after RecoverTypeInfo run pair; got 0.");

        StringBuilder diag = new StringBuilder();
        for (RunPair p : pairs) diag.append("  ").append(p).append("\n");

        // Core proof: at least one run whose BEFORE has no !java-klass (stripped by
        // a load-CSE pass) and whose AFTER re-attached >= 1 with a strict rise.
        boolean foundRise = false;
        for (RunPair p : pairs) {
            if (p.beforeKlass == 0 && p.afterKlass >= 1 && p.afterKlass > p.beforeKlass) {
                foundRise = true;
                break;
            }
        }
        Asserts.assertTrue(foundRise,
            name + ": no RecoverTypeInfo run showed the expected 0 -> >=1 !java-klass "
            + "rise (a load-CSE pass stripped !java-klass and RecoverTypeInfo "
            + "re-attached it). Run pairs:\n" + diag);

        // Sanity: at least one run must have !java-klass present in its AFTER
        // (proves the pass actually attaches metadata somewhere).
        boolean anyAfterKlass = false;
        for (RunPair p : pairs) if (p.afterKlass >= 1) { anyAfterKlass = true; break; }
        Asserts.assertTrue(anyAfterKlass,
            name + ": every RecoverTypeInfo after-section had 0 !java-klass -- the "
            + "pass attached nothing. Run pairs:\n" + diag);
    }

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

        // Negative proof: on an interface-typed field, the frontend never attaches
        // !java-klass (jeandleAbstractInterpreter skips unverified interfaces) and
        // RecoverTypeInfo's isUnverifiedInterface(FK) branch returns Bottom, so it
        // re-attaches nothing. EVERY run pair must have 0 before AND 0 after.
        for (RunPair p : pairs) {
            Asserts.assertEquals(p.beforeKlass, 0,
                name + " (negative): interface-typed field must have no !java-klass in "
                + "any before-section; " + p + "\n" + diag);
            Asserts.assertEquals(p.afterKlass, 0,
                name + " (negative): RecoverTypeInfo must NOT attach !java-klass to an "
                + "interface-typed field in any after-section; " + p + "\n" + diag);
        }
    }

    // =========================================================================
    // runAllTests.
    // =========================================================================

    private static void runAllTests() throws Exception {
        // Core positive: PreCHACleanup EarlyCSE/InstCombine dedups the two
        // h.animalField reads (stripping !java-klass from the surviving load);
        // RecoverTypeInfo re-attaches it.
        runRecoveryCheck("testRecoverAfterCSE");
        // The inline-driver-strips shape: getAnimal is inlined into the caller, so
        // the inlined load and the direct read collapse to one load whose
        // !java-klass is stripped by the driver's own EarlyCSE/InstCombine and
        // re-attached by RecoverTypeInfo.
        runRecoveryCheck("testRecoverThroughInlinedGetter");
        // A field whose declared type has no declared subclasses still gets
        // !java-klass re-attached (whether the VM also marks it effectively-final
        // is build-dependent, so this case only asserts the !java-klass rise).
        runRecoveryCheck("testRecoverFinalField");
        // Two-level oop chain: both the link.next load and the animalField load get
        // !java-klass re-attached (forwarder + field-load propagation).
        runRecoveryCheck("testRecoverFieldChain");
        // Negative: interface-typed field, no metadata before or after, in every
        // run -- proves we do not fabricate type info.
        runNoRecoveryCheck("testNoRecoverInterfaceField");

        System.out.println("All RecoverTypeInfo tests passed.");
    }
}
