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
 * @summary Test the intrinsic implementation of Object.hashCode with vtable guard
 * @requires os.arch=="amd64" | os.arch=="x86_64" | os.arch=="aarch64"
 * @library /test/lib /
 * @modules java.base/jdk.internal.misc
 * @compile TestHashCodeInterface.jasm TestHashCode.java
 * @run main/othervm compiler.jeandle.intrinsic.TestHashCode
 */

package compiler.jeandle.intrinsic;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import compiler.jeandle.fileCheck.FileCheck;

import jdk.internal.misc.Unsafe;
import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestHashCode {

    private static final int[] LOCKING_MODES = {0, 1, 2};

    // Per-call-site log line emitted by Jeandle when Object.hashCode is recognized.
    private static final String INTRINSIC_LOG_LINE =
        "Method `virtual jint java.lang.Object.hashCode()` is parsed as intrinsic";

    public static void main(String[] args) throws Exception {
        for (int lockingMode : LOCKING_MODES) {
            String dumpPath = Files.createTempDirectory(
                    "jeandle_hashcode_mode_" + lockingMode).toString();
            OutputAnalyzer output = runChild(dumpPath, lockingMode, true);
            output.shouldHaveExitValue(0);
            Asserts.assertEQ(countIntrinsicLogs(output), 3,
                    "Expected hashOf, TestHashCodeInterface.hash, and "
                    + "SpecialHash.hashFromSuper to intrinsify "
                    + "with LockingMode=" + lockingMode);

            if (lockingMode == 2) {
                checkEnabledIR(dumpPath);
            }
        }

        String disabledDumpPath = Files.createTempDirectory(
                "jeandle_hashcode_disabled").toString();
        OutputAnalyzer disabled = runChild(disabledDumpPath, 2, false);
        disabled.shouldHaveExitValue(0);
        Asserts.assertEQ(countIntrinsicLogs(disabled), 0,
                "Disabled _hashCode must use normal invoke handling");

        FileCheck disabledIR = new FileCheck(disabledDumpPath,
                TestWrapper.class.getDeclaredMethod("hashOf", Object.class), false);
        disabledIR.checkNotPattern("call hotspotcc i32 @jeandle\\.hashcode_fast");
        disabledIR.checkPattern("__jeandle_dynamic_call");
    }

    private static OutputAnalyzer runChild(String dumpPath, int lockingMode,
                                           boolean enableIntrinsic) throws Exception {
        ArrayList<String> commandArgs = new ArrayList<>(List.of(
                "-Xbatch",
                "-XX:-TieredCompilation",
                "-XX:+UseJeandleCompiler",
                "-Xcomp",
                "-XX:+UnlockDiagnosticVMOptions",
                "-XX:+UnlockExperimentalVMOptions",
                "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
                "-XX:LockingMode=" + lockingMode,
                "-Xlog:jeandle=debug",
                "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dumpPath,
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::hashOf",
                "-XX:CompileCommand=compileonly," + TestHashCodeInterface.class.getName() + "::hash",
                "-XX:CompileCommand=compileonly," + SpecialHash.class.getName() + "::hashFromSuper",
                TestWrapper.class.getName()));
        if (!enableIntrinsic) {
            commandArgs.add(commandArgs.size() - 1,
                    "-XX:ControlIntrinsic=-_hashCode");
        }
        commandArgs.add(Boolean.toString(enableIntrinsic));

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(commandArgs);
        return ProcessTools.executeCommand(pb);
    }

    private static int countIntrinsicLogs(OutputAnalyzer output) {
        Matcher m = Pattern.compile(Pattern.quote(INTRINSIC_LOG_LINE)).matcher(output.getOutput());
        int intrinsicCount = 0;
        while (m.find()) {
            intrinsicCount++;
        }
        return intrinsicCount;
    }

    private static void checkEnabledIR(String dumpPath) throws Exception {
        // Verify the IR contains: vtable guard + fast path + Java-call slow path + merge.
        FileCheck fc = new FileCheck(dumpPath,
                TestWrapper.class.getDeclaredMethod("hashOf", Object.class),
                false,  // raw IR (.ll), not optimized
                0);     // first compilation
        // Vtable guard: load receiver's vtable slot, compare to Object.hashCode's Method*.
        fc.checkPattern("hashCode.methods_match");
        // Fast path block + jeandle.hashcode_fast JavaOp call.
        fc.checkPattern("hashCode_fast");
        fc.checkPattern("call hotspotcc i32 @jeandle\\.hashcode_fast");
        // Slow path block — a Java call to Object.hashCode, NOT uncommon_trap.
        fc.checkPattern("hashCode_slow_call");
        fc.checkPattern("@\"__jeandle_dynamic_call\\.java_lang_Object_hashCode\\(\\)I\"");
        // Merge block + result PHI.
        fc.checkPattern("hashCode_merge");
        fc.checkPattern("hashCode.result");

        FileCheck special = new FileCheck(dumpPath,
                SpecialHash.class.getDeclaredMethod("hashFromSuper"), false);
        special.checkNotPattern("hashCode\\.methods_match");
        special.checkPattern("call hotspotcc i32 @jeandle\\.hashcode_fast");
        special.checkPattern("@\"java_lang_Object_hashCode\\(\\)I\"");
        special.checkNotPattern("__jeandle_dynamic_call");

        FileCheck interfaceCall = new FileCheck(dumpPath,
                TestHashCodeInterface.class.getDeclaredMethod("hash", EmptyInterface.class), false);
        interfaceCall.checkPattern("@jeandle\\.check_instanceof\\(ptr inttoptr");
        FileCheck interfaceVtable = new FileCheck(dumpPath,
                TestHashCodeInterface.class.getDeclaredMethod("hash", EmptyInterface.class), false);
        interfaceVtable.checkPattern("hashCode\\.methods_match");
        FileCheck interfaceFast = new FileCheck(dumpPath,
                TestHashCodeInterface.class.getDeclaredMethod("hash", EmptyInterface.class), false);
        interfaceFast.checkPattern("call hotspotcc i32 @jeandle\\.hashcode_fast");
        FileCheck interfaceSlow = new FileCheck(dumpPath,
                TestHashCodeInterface.class.getDeclaredMethod("hash", EmptyInterface.class), false);
        interfaceSlow.checkPattern("__jeandle_dynamic_call");
        FileCheck interfaceDeopt = new FileCheck(dumpPath,
                TestHashCodeInterface.class.getDeclaredMethod("hash", EmptyInterface.class), false);
        interfaceDeopt.checkPattern("^hashCode\\.interface_check_fail:");
        interfaceDeopt.checkPattern(
                "llvm\\.experimental\\.deoptimize.*\\[ \\\"deopt\\\"\\(.*ptr addrspace\\(1\\) %0");
    }

    // TestWrapper exercises all four combinations of:
    //   (a) receiver class overrides hashCode?  → drives vtable guard
    //   (b) fast path succeeds?                 → drives fast/slow selection
    static class TestWrapper {
        public static void main(String[] args) {
            boolean enableIntrinsic = Boolean.parseBoolean(args[0]);

            // Prime CP resolution for Object.hashCode so the first compilation
            // of hashOf avoids Jeandle's Reason_unloaded deopt at the invokevirtual.
            int preload = new Object().hashCode();
            if (preload == 0) {
                throw new RuntimeException("prime failed");
            }

            // === Scenario 1: not overridden + fast path fails (no_hash) ===
            // Fresh objects have no hash installed → slow path installs it via FastHashCode.
            for (int i = 0; i < 50; i++) {
                Object fresh = new Object();
                int h = hashOf(fresh);
                int id = System.identityHashCode(fresh);
                if (h != id) {
                    throw new RuntimeException("scenario 1 (no_hash) mismatch at i=" + i
                        + ": " + h + " != " + id);
                }
            }

            // === Scenario 2: not overridden + fast path fails (locked) ===
            // Pre-install hash; synchronized → stack-locked mark (LM_LEGACY) → slow path.
            Object locked = new Object();
            int lockedExpected = System.identityHashCode(locked);
            synchronized (locked) {
                int h = hashOf(locked);
                if (h != lockedExpected) {
                    throw new RuntimeException("scenario 2 (locked) mismatch: "
                        + h + " != " + lockedExpected);
                }
            }

            // === Scenario 3: not overridden + fast path succeeds ===
            // Hash pre-installed, mark unlocked → jeandle.hashcode_fast returns hash inline.
            Object o = new Object();
            int expected = System.identityHashCode(o);
            for (int i = 0; i < 20_000; i++) {
                int h = hashOf(o);
                if (h != expected) {
                    throw new RuntimeException("scenario 3 (fast path) mismatch at i=" + i
                        + ": " + h + " != " + expected);
                }
            }

            // === Scenario 4: overridden + vtable guard fails ===
            // Overridden trips the guard → slow path Java call dispatches to
            // Overridden.hashCode() → returns 42.
            Overridden ov = new Overridden();
            int overrideResult = hashOf(ov);
            if (overrideResult != 42) {
                throw new RuntimeException("scenario 4 (override) mismatch: "
                    + overrideResult + " != 42");
            }

            // === Scenario 5: override exceptions retain the Java exception edge ===
            try {
                hashOf(new ThrowingHash());
                throw new RuntimeException("scenario 5: expected HashCodeException");
            } catch (HashCodeException expectedException) {
                // Expected.
            }

            // === Scenario 6: invokeinterface preserves dispatch and ICCE ===
            PlainHashable plain = new PlainHashable();
            int plainExpected = System.identityHashCode(plain);
            Asserts.assertEquals(plainExpected, TestHashCodeInterface.hash(plain));
            Asserts.assertEquals(73, TestHashCodeInterface.hash(new InterfaceOverride()));
            if (enableIntrinsic) {
                try {
                    TestHashCodeInterface.hash(forgeEmptyInterface(new Object()));
                    throw new RuntimeException("scenario 6: expected ICCE");
                } catch (IncompatibleClassChangeError expectedException) {
                    // Expected.
                }
                try {
                    TestHashCodeInterface.hash(
                            forgeEmptyInterface(new NonImplementingOverride()));
                    throw new RuntimeException("scenario 6: expected ICCE for override");
                } catch (IncompatibleClassChangeError expectedException) {
                    // Expected.
                }
            }

            // === Scenario 7: invokespecial has an exact Object.hashCode target ===
            SpecialHash special = new SpecialHash();
            int specialExpected = System.identityHashCode(special);
            Asserts.assertEquals(specialExpected, special.hashFromSuper());
            Asserts.assertEquals(99, special.hashCode());

            // === Scenario 8: null receiver throws before intrinsic lowering ===
            try {
                hashOf(null);
                throw new RuntimeException("scenario 8: expected NullPointerException");
            } catch (NullPointerException expectedException) {
                // Expected.
            }

            System.out.println("TestHashCode PASSED");
        }

        // Compiled by Jeandle — invokevirtual Object.hashCode with vtable guard.
        static int hashOf(Object o) {
            return o.hashCode();
        }

        // Overrides hashCode — loaded after hashOf is compiled to exercise the guard.
        static class Overridden {
            @Override
            public int hashCode() {
                return 42;
            }
        }

        static class ThrowingHash {
            @Override
            public int hashCode() {
                throw new HashCodeException();
            }
        }
    }

    interface EmptyInterface {
    }

    static class PlainHashable implements EmptyInterface {
    }

    static class InterfaceOverride implements EmptyInterface {
        @Override
        public int hashCode() {
            return 73;
        }
    }

    static class NonImplementingOverride {
        @Override
        public int hashCode() {
            return 81;
        }
    }

    static class SpecialHash {
        int hashFromSuper() {
            return super.hashCode();
        }

        @Override
        public int hashCode() {
            return 99;
        }
    }

    static class HashCodeException extends RuntimeException {
    }

    private static final Unsafe UNSAFE = Unsafe.getUnsafe();
    private static EmptyInterface forgedReceiver;

    static EmptyInterface forgeEmptyInterface(Object obj) {
        try {
            Field field = TestHashCode.class.getDeclaredField("forgedReceiver");
            Object base = UNSAFE.staticFieldBase(field);
            long offset = UNSAFE.staticFieldOffset(field);
            UNSAFE.putReference(base, offset, obj);
            return forgedReceiver;
        } catch (ReflectiveOperationException exception) {
            throw new Error(exception);
        }
    }
}
