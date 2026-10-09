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
 * @test TestRecoverTypeInfoEnablesChaDevirt.java
 * @summary Prove RecoverTypeInfo is a prerequisite for CHADevirtualization of
 *          field-load receivers, via a flag-gated counterfactual.
 *
 *          CHADevirtualization (llvm/lib/Transforms/Jeandle/CHADevirtualization,
 *          pass-arg "cha-devirtualization") devirtualizes an invokevirtual/
 *          invokeinterface only after resolving the receiver oop's klass via
 *          jeandle::getJavaType (CHADevirtualization.cpp:115). For a LoadInst,
 *          getJavaType's getBaseJavaType (JavaType.cpp:354-368) learns the
 *          klass ONLY from !java-klass metadata (there is no attribute entry for
 *          loads). EarlyCSE/InstCombine load CSE strip that custom metadata;
 *          RecoverTypeInfo (Pipeline.cpp:66) re-attaches it right before
 *          CHADevirtualization (Pipeline.cpp:67).
 *
 *          There is no production flag to disable RecoverTypeInfo alone, so this
 *          test uses the hidden LLVM test-hook --jeandle-disable-recover-type-info
 *          (cl::opt in RecoverTypeInfo.cpp), delivered via -XX:JeandleLLVMOptions,
 *          to make RecoverTypeInfo a no-op. It runs the SAME scenario in two
 *          modes (recovery ON/OFF) and asserts the post-CHADevirt marker
 *          "monomorphic-target" flips OFF when -- only when -- the receiver is
 *          a field load whose !java-klass was stripped. A param-receiver control
 *          scenario devirtualizes in BOTH modes (its klass comes from the
 *          java-klass param attribute, which survives CSE) -- isolating
 *          RecoverTypeInfo as the link.
 * @library /test/lib /
 * @run driver TestRecoverTypeInfoEnablesChaDevirt
 */

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestRecoverTypeInfoEnablesChaDevirt {

    // =========================================================================
    // Class hierarchy.
    //
    // I has exactly one concrete implementor (Impl), so CHA's GetCHAOptInfo
    // reports a unique target for an I.value() invokevirtual. The receiver of
    // the call is in each variable type I but the actual instance is Impl.
    // =========================================================================

    interface I {
        int value();
    }

    // The sole concrete implementor of I. No other implementor may be loaded in
    // this test's classpath, or CHA cannot report a unique target.
    static class Impl implements I {
        public int value() { return 1; }
    }

    // Holds a CONCRETE-class-typed instance field. The frontend attaches
    // !java-klass (and, because Impl has no loaded subclasses, !java-klass-exact)
    // to the field LOAD (jeandleAbstractInterpreter.cpp:2611-2629). That metadata
    // is what RecoverTypeInfo re-attaches after EarlyCSE/InstCombine strip it,
    // and what CHADevirt's getJavaType reads (JavaType.cpp:354-368) to learn the
    // receiver klass. (A field declared as an interface type would NEVER get
    // !java-klass -- is_unverified_interface skips it -- so the field MUST be a
    // concrete class type for RecoverTypeInfo to be the recovery path.)
    static class FieldHolder {
        Impl ifield;
    }

    // =========================================================================
    // Scenarios under test.
    // =========================================================================

    // S1 (FIELD-LOAD receiver): read h.ifield twice so PreCHACleanup's
    // EarlyCSE/InstCombine (Pipeline.cpp:61/62) dedups the two loads and strips
    // !java-klass from the surviving load BEFORE the pipeline-level RecoverTypeInfo
    // (Pipeline.cpp:66) and CHADevirtualization (Pipeline.cpp:67).
    //   - recovery ON  : the survivor's !java-klass is restored -> CHADevirt
    //                     resolves the receiver -> devirtualizes (monomorphic-target
    //                     present, __jeandle_dynamic_call shim gone).
    //   - recovery OFF : the survivor has no !java-klass -> getJavaType returns
    //                     {Klass:0} -> GetCHAOptInfo gives Constraint==0 -> CHADevirt
    //                     bails (monomorphic-target ABSENT, shim survives).
    static int testFieldLoadReceiver(FieldHolder h) {
        Impl a = h.ifield;
        Impl b = h.ifield;
        return (a == b) ? a.value() : -1;
    }

    // S2 (PARAMETER receiver, control): the receiver is the method parameter
    // %0, whose klass arrives via the java-klass PARAM ATTRIBUTE (attached by the
    // frontend in JeandleFuncSig::create_llvm_func, JavaType.cpp:321-335). That
    // attribute lives on the AttributeList and survives load CSE, so the klass is
    // independent of RecoverTypeInfo -> CHADevirt devirtualizes in BOTH modes.
    // If this fails in OFF mode, the disable flag broke more than RecoverTypeInfo.
    static int testParameterReceiver(I obj) {
        return obj.value();
    }

    // =========================================================================
    // Driver / child dispatch.
    //
    // Driver mode (no args): run each scenario in both modes (recovery ON/OFF),
    //   slice each child's stderr for CHADevirt before/after, assert.
    // Child mode (one arg): run one scenario for functional correctness
    //   (does NOT prove devirt -- the IR-marker assertions in the driver do).
    // =========================================================================

    public static void main(String[] args) throws Exception {
        // Force-load the concrete implementor so CHA sees a unique target at
        // compile time (GetCHAOptInfo must find exactly one loaded subclass of I).
        Class.forName("TestRecoverTypeInfoEnablesChaDevirt$I");
        Class.forName("TestRecoverTypeInfoEnablesChaDevirt$Impl");
        Class.forName("TestRecoverTypeInfoEnablesChaDevirt$FieldHolder");

        if (args.length == 0) {
            runAllTests();
        } else {
            runChildTest(args[0]);
        }
    }

    private static void runChildTest(String testName) {
        switch (testName) {
            case "testFieldLoadReceiver": {
                FieldHolder h = new FieldHolder();
                h.ifield = new Impl();
                int r = testFieldLoadReceiver(h);
                Asserts.assertEquals(r, 1,
                    "testFieldLoadReceiver must return Impl.value() == 1");
                break;
            }
            case "testParameterReceiver": {
                int r = testParameterReceiver(new Impl());
                Asserts.assertEquals(r, 1,
                    "testParameterReceiver must return Impl.value() == 1");
                break;
            }
            default:
                throw new IllegalArgumentException("Unknown test: " + testName);
        }
    }

    // =========================================================================
    // Child JVM spawn. Mirrors TestRecoverTypeInfo.runTestProcess, plus the
    // --jeandle-disable-recover-type-info toggle.
    // =========================================================================

    private static final String CHADEVIRT_OPTS =
        "--print-before=cha-devirtualization --print-after=cha-devirtualization";
    private static final String DISABLE_RECOVER =
        " --jeandle-disable-recover-type-info";

    private static final String[] BASE_ARGS = {
        "-Xcomp", "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler"
    };

    private static OutputAnalyzer runTestProcess(String testName, boolean disableRecover)
            throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("-Dtest.classes=" + System.getProperty("test.classes", "."));
        cmd.addAll(Arrays.asList(BASE_ARGS));
        String llvmOpts = CHADEVIRT_OPTS + (disableRecover ? DISABLE_RECOVER : "");
        cmd.add("-XX:JeandleLLVMOptions=" + llvmOpts);
        cmd.add("-XX:CompileCommand=compileonly,TestRecoverTypeInfoEnablesChaDevirt::" + testName);
        cmd.add("TestRecoverTypeInfoEnablesChaDevirt");
        cmd.add(testName);

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(cmd);
        return ProcessTools.executeProcess(pb);
    }

    // =========================================================================
    // IR-section slicing helpers (ported from TestRecoverTypeInfo.java, phase
    // label switched to "CHADevirtualization").
    //
    // CHADevirtualization is added once at the pipeline level (Pipeline.cpp:67)
    // and may run again per inline round inside the driver loop. The floor for
    // countSections is therefore 1 (not 2). The LAST after-section is the one
    // we assert against (the final devirt decision).
    //
    // Header line:
    //   ; *** IR Dump Before CHADevirtualization on <mangled-fn-signature> ***
    //   ; *** IR Dump After  CHADevirtualization on <mangled-fn-signature> ***
    // =========================================================================

    static int countSections(String stderr, String phase, String methodPattern) {
        String header = "IR Dump " + phase + " CHADevirtualization";
        int c = 0;
        for (String line : stderr.split("\\n"))
            if (line.contains(header) && line.contains(methodPattern)) c++;
        return c;
    }

    static String extractNthIR(String stderr, String phase, String methodPattern, int n) {
        String header = "IR Dump " + phase + " CHADevirtualization";
        String[] lines = stderr.split("\\n");
        StringBuilder sb = new StringBuilder();
        boolean inSection = false;
        int matched = -1;
        for (String line : lines) {
            if (line.contains(header) && line.contains(methodPattern)) {
                matched++;
                if (matched == n) { inSection = true; continue; }
                if (inSection) break;
            }
            if (inSection && line.contains("*** IR Dump ")) { inSection = false; break; }
            if (inSection) sb.append(line).append("\n");
        }
        return sb.toString();
    }

    static String extractLastBeforeIR(String stderr, String methodPattern) {
        int n = countSections(stderr, "Before", methodPattern);
        return n <= 0 ? "" : extractNthIR(stderr, "Before", methodPattern, n - 1);
    }

    static String extractLastAfterIR(String stderr, String methodPattern) {
        int n = countSections(stderr, "After", methodPattern);
        return n <= 0 ? "" : extractNthIR(stderr, "After", methodPattern, n - 1);
    }

    static void assertSectionExists(String ir, String phase, String method, String rawStderr) {
        Asserts.assertTrue(!ir.trim().isEmpty(),
            method + ": expected a non-empty '* IR Dump " + phase
            + " CHADevirtualization on ...' section; got empty. Before sections="
            + countSections(rawStderr, "Before", method) + " After sections="
            + countSections(rawStderr, "After", method)
            + ". Usually means (a) --print-before/after=cha-devirtualization not plumbed,"
            + " (b) method not compiled by Jeandle, or (c) the name suffix '"
            + method + "' does not match the mangled LLVM function name.");
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
    // The post-devirt marker "monomorphic-target" is a fn-attr that LLVM's print
    // instrumentation emits in the function's trailing "attributes #N = {...}"
    // group -- which is NOT part of the streamed "IR Dump" section body the test
    // slices (it lands after the closing `}` and is not captured reliably).
    // Instead we assert on markers that live INSIDE the function body:
    //   - "__jeandle_dynamic_call." -- the virtual-call shim emitted by the
    //     frontend for a non-statically-bound invokevirtual
    //     (jeandleAbstractInterpreter.cpp:2072). CHADevirtualization rewrites
    //     the callee (CHADevirtualization.cpp:139-140), so the shim is ABSENT
    //     iff the call was devirtualized; PRESENT iff it stayed virtual.
    //   - "jeandle.check_instanceof" -- the type guard CHADevirt inserts
    //     (CHADevirtualization.cpp:131); PRESENT iff devirtualized, ABSENT
    //     otherwise.
    // =========================================================================

    /** true iff CHADevirtualization rewrote the invokevirtual in this IR
     *  (shim gone AND receiver-type guard present). */
    static boolean isDevirtualized(String ir) {
        return countOccurrences(ir, "__jeandle_dynamic_call.") == 0
            && countOccurrences(ir, "jeandle.check_instanceof") >= 1;
    }

    private static void runFieldLoadReceiverPair() throws Exception {
        String name = "testFieldLoadReceiver";
        String suffix = "_" + name;

        // --- Recovery ON: CHADevirt MUST devirtualize. ---
        OutputAnalyzer on = runTestProcess(name, false);
        on.shouldHaveExitValue(0);
        String onStderr = on.getOutput();
        String onAfter = extractLastAfterIR(onStderr, suffix);
        assertSectionExists(onAfter, "After", suffix, onStderr);
        Asserts.assertTrue(isDevirtualized(onAfter),
            name + "/ON: with RecoverTypeInfo enabled, CHADevirt must devirtualize "
            + "the field-load-receiver invokevirtual (the stripped !java-klass was "
            + "recovered at Pipeline.cpp:66 before CHADevirt at :67). Expected the "
            + "__jeandle_dynamic_call. shim gone and a jeandle.check_instanceof guard "
            + "present; got shim=" + countOccurrences(onAfter, "__jeandle_dynamic_call.")
            + " guard=" + countOccurrences(onAfter, "jeandle.check_instanceof")
            + ".\n--- after-IR ---\n" + onAfter);

        // --- Recovery OFF: CHADevirt must NOT devirtualize (the counterfactual). ---
        OutputAnalyzer off = runTestProcess(name, true);
        off.shouldHaveExitValue(0);
        String offStderr = off.getOutput();
        String offAfter = extractLastAfterIR(offStderr, suffix);
        assertSectionExists(offAfter, "After", suffix, offStderr);
        Asserts.assertFalse(isDevirtualized(offAfter),
            name + "/OFF: with RecoverTypeInfo disabled (--jeandle-disable-recover"
            + "-type-info), the field-load receiver has no !java-klass metadata, so "
            + "getJavaType returns {Klass:0}, GetCHAOptInfo yields Constraint==0, "
            + "and CHADevirt MUST bail (leave the invokevirtual un-devirtualized). "
            + "Expected __jeandle_dynamic_call. shim present and NO check_instanceof "
            + "guard; got shim=" + countOccurrences(offAfter, "__jeandle_dynamic_call.")
            + " guard=" + countOccurrences(offAfter, "jeandle.check_instanceof")
            + " -- the RecoverTypeInfo->CHADevirt dependency is broken OR the field "
            + "load's !java-klass was not actually stripped by PreCHACleanup CSE."
            + "\n--- after-IR ---\n" + offAfter);
        // The shim must survive in OFF mode (call stayed indirect).
        Asserts.assertTrue(countOccurrences(offAfter, "__jeandle_dynamic_call.") >= 1,
            name + "/OFF: the invokevirtual shim __jeandle_dynamic_call. must survive "
            + "when CHADevirt bailed.\n--- after-IR ---\n" + offAfter);
    }

    private static void runParameterReceiverPair() throws Exception {
        String name = "testParameterReceiver";
        String suffix = "_" + name;
        // Param-receiver: must devirtualize in BOTH modes (control). The klass
        // comes from the java-klass param attribute, independent of RecoverTypeInfo.
        for (boolean disable : new boolean[]{ false, true }) {
            OutputAnalyzer out = runTestProcess(name, disable);
            out.shouldHaveExitValue(0);
            String stderr = out.getOutput();
            String after = extractLastAfterIR(stderr, suffix);
            assertSectionExists(after, "After", suffix, stderr);
            Asserts.assertTrue(isDevirtualized(after),
                name + " (param-receiver, recovery=" + (disable ? "OFF" : "ON")
                + "): CHADevirt must devirtualize from the java-klass param attribute "
                + "INDEPENDENT of RecoverTypeInfo. Expected shim gone and a "
                + "jeandle.check_instanceof guard present; got shim="
                + countOccurrences(after, "__jeandle_dynamic_call.") + " guard="
                + countOccurrences(after, "jeandle.check_instanceof")
                + ". If this fails in OFF mode, --jeandle-disable-recover-type-info "
                + "broke more than just RecoverTypeInfo.\n--- after-IR ---\n" + after);
        }
    }

    private static void runAllTests() throws Exception {
        // The counterfactual: field-load receiver devirtualizes only with
        // RecoverTypeInfo enabled.
        runFieldLoadReceiverPair();
        // The control: param receiver devirtualizes in both modes.
        runParameterReceiverPair();
        System.out.println("All RecoverTypeInfo->CHADevirt tests passed.");
    }
}
