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
 * @summary Verify Jeandle handles misaligned Unsafe primitive atomic accesses
 * @modules java.base/jdk.internal.misc
 * @library /test/lib /
 * @build compiler.jeandle.fileCheck.FileCheck
 * @run driver TestUnsafeAtomicAlignment
 */

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import compiler.jeandle.fileCheck.FileCheck;
import jdk.internal.misc.Unsafe;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestUnsafeAtomicAlignment {
    private static final Unsafe U = Unsafe.getUnsafe();

    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            runSemantics();
            return;
        }

        Path dumpPath = Files.createTempDirectory("jeandle_unsafe_atomic_alignment");
        List<String> command = new ArrayList<>(List.of(
                "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
                "-Xbatch", "-Xcomp", "-XX:-TieredCompilation", "-XX:-BackgroundCompilation",
                "-XX:+UseJeandleCompiler", "-Xlog:jeandle=debug,jit+compilation=debug",
                "-XX:CompileCommand=compileonly,TestUnsafeAtomicAlignment::*",
                "-XX:CompileCommand=dontinline,TestUnsafeAtomicAlignment::*",
                "-XX:+UnlockDiagnosticVMOptions", "-XX:+CIPrintCompilerName",
                "-XX:+JeandleDumpIR", "-XX:JeandleDumpDirectory=" + dumpPath,
                TestUnsafeAtomicAlignment.class.getName(), "child"));
        OutputAnalyzer output = ProcessTools.executeCommand(
                ProcessTools.createLimitedTestJavaProcessBuilder(command));
        output.shouldHaveExitValue(0);
        output.shouldContain("Unsafe.getAndSetShort");
        output.shouldContain("Unsafe.getAndAddInt");
        output.shouldContain("Unsafe.compareAndSetLong");
        checkInstalledByJeandle(output, "getAndSetShort");
        checkInstalledByJeandle(output, "dynamicGetAndSetShort");
        checkInstalledByJeandle(output, "getAndAddInt");
        checkInstalledByJeandle(output, "compareAndSetLong");
        checkInstalledByJeandle(output, "staticMisalignedGetAndSetShort");
        checkInstalledByJeandle(output, "staticMisalignedGetAndAddInt");
        checkInstalledByJeandle(output, "staticMisalignedCompareAndSetLong");

        checkGuard(dumpPath, "getAndSetShort", short.class, 1, "atomicrmw xchg", "i16");
        checkGuard(dumpPath, "getAndAddInt", int.class, 3, "atomicrmw add", "i32");
        checkCasGuard(dumpPath, 7);
        checkStaticFallback(dumpPath, "staticMisalignedGetAndSetShort", "atomicrmw");
        checkStaticFallback(dumpPath, "staticMisalignedGetAndAddInt", "atomicrmw");
        checkStaticFallback(dumpPath, "staticMisalignedCompareAndSetLong", "cmpxchg");
    }

    private static void checkInstalledByJeandle(OutputAnalyzer output, String method) {
        output.shouldMatch("(?s).*Jeandle:.*TestUnsafeAtomicAlignment::" + method + ".*");
    }

    private static void checkGuard(Path dumpPath, String name, Class<?> valueType,
                                   int alignmentMask, String atomic, String llvmType)
            throws Exception {
        java.lang.reflect.Method method = TestUnsafeAtomicAlignment.class
                .getDeclaredMethod(name, long.class, valueType);
        checkPattern(dumpPath, method,
                "unsafe_atomic_alignment_bits = and i64 .*?, " + alignmentMask);
        checkPattern(dumpPath, method, "unsafe_atomic_is_misaligned");
        checkPattern(dumpPath, method, "unsafe_atomic_misaligned");
        checkPattern(dumpPath, method, "@__llvm_deoptimize");
        checkPattern(dumpPath, method, atomic + ".*" + llvmType);
    }

    private static void checkCasGuard(Path dumpPath, int alignmentMask) throws Exception {
        java.lang.reflect.Method method = TestUnsafeAtomicAlignment.class.getDeclaredMethod(
                "compareAndSetLong", long.class, long.class, long.class);
        checkPattern(dumpPath, method,
                "unsafe_atomic_alignment_bits = and i64 .*?, " + alignmentMask);
        checkPattern(dumpPath, method, "unsafe_atomic_is_misaligned");
        checkPattern(dumpPath, method, "unsafe_atomic_misaligned");
        checkPattern(dumpPath, method, "@__llvm_deoptimize");
        checkPattern(dumpPath, method, "cmpxchg.*i64");
    }

    private static void checkPattern(Path dumpPath, java.lang.reflect.Method method,
                                     String pattern) throws Exception {
        new FileCheck(dumpPath.toString(), method, true).checkPattern(pattern);
    }

    private static void checkStaticFallback(Path dumpPath, String name, String atomic)
            throws Exception {
        FileCheck check = new FileCheck(dumpPath.toString(),
                TestUnsafeAtomicAlignment.class.getDeclaredMethod(name, int.class), true);
        check.checkNotPattern(atomic);
        check.checkPattern("invoke.*jdk_internal_misc_Unsafe_");
    }

    private static short getAndSetShort(long address, short update) {
        return U.getAndSetShort(null, address, update);
    }

    private static short dynamicGetAndSetShort(long base, int lowBits, short update) {
        return U.getAndSetShort(null, base + lowBits, update);
    }

    private static int getAndAddInt(long address, int delta) {
        return U.getAndAddInt(null, address, delta);
    }

    private static boolean compareAndSetLong(long address, long expected, long update) {
        return U.compareAndSetLong(null, address, expected, update);
    }

    private static short staticMisalignedGetAndSetShort(int selector) {
        if (selector == 0) {
            return U.getAndSetShort(null, 1L, (short) 9);
        }
        return 0;
    }

    private static int staticMisalignedGetAndAddInt(int selector) {
        if (selector == 0) {
            return U.getAndAddInt(null, 2L, 5);
        }
        return 0;
    }

    private static boolean staticMisalignedCompareAndSetLong(int selector) {
        return selector == 0 && U.compareAndSetLong(null, 4L, 23L, 29L);
    }

    private static long alignUp(long address, int alignment) {
        return (address + alignment - 1) & -alignment;
    }

    private static void runSemantics() {
        long memory = U.allocateMemory(32);
        try {
            long shortAddress = alignUp(memory, 2);
            long intAddress = alignUp(memory + 8, 4);
            long longAddress = alignUp(memory + 16, 8);

            U.putShort(null, shortAddress, (short) -7);
            check(getAndSetShort(shortAddress, (short) 9) == -7, "getAndSet short old value");
            check(U.getShort(null, shortAddress) == 9, "getAndSet short new value");

            U.putInt(null, intAddress, 17);
            check(getAndAddInt(intAddress, 5) == 17, "getAndAdd int old value");
            check(U.getInt(null, intAddress) == 22, "getAndAdd int new value");

            U.putLong(null, longAddress, 23L);
            check(compareAndSetLong(longAddress, 23L, 29L), "compareAndSet long success");
            check(U.getLong(null, longAddress) == 29L, "compareAndSet long new value");

            long shortWordBase = alignUp(memory, 4);
            U.putShort(null, shortWordBase + 1, (short) -31);
            check(dynamicGetAndSetShort(shortWordBase, 1, (short) 41) == -31,
                    "dynamic short offset 1 old value");
            check(U.getShort(null, shortWordBase + 1) == 41,
                    "dynamic short offset 1 new value");

            U.putShort(null, shortWordBase + 2, (short) -32);
            check(dynamicGetAndSetShort(shortWordBase, 2, (short) 42) == -32,
                    "dynamic short offset 2 old value");
            check(U.getShort(null, shortWordBase + 2) == 42,
                    "dynamic short offset 2 new value");

            try {
                dynamicGetAndSetShort(shortWordBase, 3, (short) 43);
                throw new AssertionError("dynamic short offset 3 did not throw");
            } catch (IllegalArgumentException expected) {
                // The original Unsafe narrow CAS fallback rejects a word-spanning short.
            }

            check(staticMisalignedGetAndSetShort(1) == 0, "static getAndSet fallback path");
            check(staticMisalignedGetAndAddInt(1) == 0, "static getAndAdd fallback path");
            check(!staticMisalignedCompareAndSetLong(1), "static CAS fallback path");
        } finally {
            U.freeMemory(memory);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
