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
 * @summary Test Unsafe cache writeback intrinsics and their disabled fallback
 * @requires os.arch=="amd64" | os.arch=="x86_64" | os.arch=="aarch64"
 * @library /test/lib /
 * @modules java.base/jdk.internal.misc
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions
 *                   -XX:+WhiteBoxAPI compiler.jeandle.intrinsic.TestWriteback0
 */

package compiler.jeandle.intrinsic;

import compiler.jeandle.fileCheck.FileCheck;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jdk.internal.misc.Unsafe;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;
import jdk.test.whitebox.WhiteBox;

public class TestWriteback0 {
    private static final Unsafe U = Unsafe.getUnsafe();
    private static final String WRITEBACK_LOG =
            "jdk.internal.misc.Unsafe.writeback0(jlong)` is parsed as intrinsic";
    private static final String PRE_SYNC_LOG =
            "jdk.internal.misc.Unsafe.writebackPreSync0()` is parsed as intrinsic";
    private static final String POST_SYNC_LOG =
            "jdk.internal.misc.Unsafe.writebackPostSync0()` is parsed as intrinsic";
    private static final String FALLBACK_SYMBOL =
            "jdk_internal_misc_Unsafe_writeback(PreSync0|PostSync0|0)";

    public static void main(String[] args) throws Exception {
        if (!Unsafe.isWritebackEnabled()) {
            System.out.println("Unsafe cache writeback is not supported; test skipped");
            return;
        }

        runIntrinsicMode(true);
        runIntrinsicMode(false);
        if (System.getProperty("os.arch").equals("aarch64")) {
            runStoreOrderingMode();
        }
    }

    private static void runIntrinsicMode(boolean enabled) throws Exception {
        String dumpPath = Files.createTempDirectory(
                enabled ? "jeandle_writeback_enabled" : "jeandle_writeback_disabled").toString();
        ArrayList<String> args = new ArrayList<>(List.of(
                "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
                "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
                "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR", "-XX:+JeandleDumpObjects",
                "-XX:JeandleDumpDirectory=" + dumpPath,
                "-XX:CompileCommand=compileonly,jdk.internal.misc.Unsafe::writebackMemory"));
        if (!enabled) {
            args.add("-XX:+UnlockDiagnosticVMOptions");
            args.add("-XX:DisableIntrinsic=_writeback0,_writebackPreSync0,_writebackPostSync0");
        }
        args.add(TestWrapper.class.getName());

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(args);
        OutputAnalyzer output = ProcessTools.executeCommand(pb);
        output.shouldHaveExitValue(0).shouldContain("TestWriteback0 PASSED");

        var method = Unsafe.class.getMethod("writebackMemory", long.class, long.class);
        if (enabled) {
            output.shouldContain(WRITEBACK_LOG)
                  .shouldContain(PRE_SYNC_LOG)
                  .shouldContain(POST_SYNC_LOG);
            checkEnabledIR(dumpPath, method, false);
            checkEnabledIR(dumpPath, method, true);
        } else {
            output.shouldNotContain(WRITEBACK_LOG)
                  .shouldNotContain(PRE_SYNC_LOG)
                  .shouldNotContain(POST_SYNC_LOG);
            checkDisabledIR(dumpPath, method, false);
            checkDisabledIR(dumpPath, method, true);
        }
    }

    private static void checkDisabledIR(String dumpPath, java.lang.reflect.Method method,
                                        boolean optimized) throws Exception {
        for (String name : List.of("writebackPreSync0", "writeback0", "writebackPostSync0")) {
            FileCheck call = new FileCheck(dumpPath, method, optimized);
            call.checkPattern("invoke .*jdk_internal_misc_Unsafe_" + name);
        }
        if (optimized) {
            // Raw dumps still contain unused template helper bodies with target
            // intrinsics. Optimization removes them, so absence is meaningful
            // only in the optimized module containing the compiled root.
            FileCheck checker = new FileCheck(dumpPath, method, true);
            checker.checkNotPattern(
                    "(call|invoke) .*@llvm\\.x86\\.(clwb|clflushopt|sse2\\.clflush|sse\\.sfence)");
            checker.checkNotPattern("(call|invoke) .*@llvm\\.aarch64\\.dmb");
            checker.checkNotPattern("(call|invoke) .*@StubRoutines_data_cache_writeback");
        }
    }

    private static void checkEnabledIR(String dumpPath, java.lang.reflect.Method method,
                                       boolean optimized) throws Exception {
        FileCheck checker = new FileCheck(dumpPath, method, optimized);
        String arch = System.getProperty("os.arch");
        if (arch.equals("amd64") || arch.equals("x86_64")) {
            boolean hasClflushopt = cpuSupports("clflushopt");
            boolean hasClwb = cpuSupports("clwb");
            if (hasClflushopt || hasClwb) {
                checkPatternAnywhere(dumpPath, method, optimized,
                        "call void @llvm\\.x86\\.sse\\.sfence");
            } else {
                checker.checkNotPattern("call void @llvm\\.x86\\.sse\\.sfence");
            }
            if (hasClflushopt && hasClwb) {
                checkPatternAnywhere(dumpPath, method, optimized,
                        "call void @llvm\\.x86\\.clwb");
            } else if (hasClflushopt) {
                checkPatternAnywhere(dumpPath, method, optimized,
                        "call void @llvm\\.x86\\.clflushopt");
            } else {
                checkPatternAnywhere(dumpPath, method, optimized,
                        "call void @llvm\\.x86\\.sse2\\.clflush");
            }
        } else {
            // writebackMemory is a loop: LLVM block layout may print its exit
            // (post-sync DMB) before the loop body (cache-writeback call).
            // Check presence here; the straight-line ordering probe below
            // verifies the executable order precisely.
            checkPatternAnywhere(dumpPath, method, optimized,
                    "call void @StubRoutines_data_cache_writeback");
            checkPatternAnywhere(dumpPath, method, optimized,
                    "call void @llvm\\.aarch64\\.dmb\\(i32 11\\)$");
            checkAArch64MemoryContract(dumpPath, method, optimized);
        }
        checker.checkNotPattern(FALLBACK_SYMBOL);
    }

    private static void checkPatternAnywhere(String dumpPath,
                                             java.lang.reflect.Method method,
                                             boolean optimized,
                                             String pattern) throws Exception {
        new FileCheck(dumpPath, method, optimized).checkPattern(pattern);
    }

    private static void checkAArch64MemoryContract(String dumpPath,
                                                   java.lang.reflect.Method method,
                                                   boolean optimized) throws Exception {
        String ir = Files.readString(findIRDump(dumpPath, method, optimized));

        Matcher writebackCall = Pattern.compile(
                "(?m)^\\s*call void @StubRoutines_data_cache_writeback\\([^\\n]*\\) #(\\d+)\\s*$")
                .matcher(ir);
        if (!writebackCall.find()) {
            throw new AssertionError("writeback stub call has no attribute group");
        }
        if (writebackCall.group().contains("addrspace(")) {
            throw new AssertionError("writeback address is not in the raw address space");
        }
        String writebackAttrs = findAttributeGroup(ir, writebackCall.group(1));
        if (!writebackAttrs.contains("\"gc-leaf-function\"")) {
            throw new AssertionError("writeback stub call is not gc-leaf");
        }
        assertUnknownMemoryEffects(writebackCall.group(), writebackAttrs,
                "writeback stub call");

        Matcher dmbDeclaration = Pattern.compile(
                "(?m)^declare void @llvm\\.aarch64\\.dmb\\(i32\\) #(\\d+)\\s*$")
                .matcher(ir);
        if (!dmbDeclaration.find()) {
            throw new AssertionError("AArch64 DMB declaration has no attribute group");
        }
        assertUnknownMemoryEffects(dmbDeclaration.group(),
                findAttributeGroup(ir, dmbDeclaration.group(1)), "AArch64 DMB");
    }

    private static String findAttributeGroup(String ir, String group) {
        Matcher attributes = Pattern.compile(
                "(?m)^attributes #" + group + " = \\{([^\\n]*)\\}\\s*$").matcher(ir);
        if (!attributes.find()) {
            throw new AssertionError("missing LLVM attribute group #" + group);
        }
        return attributes.group(1);
    }

    private static void assertUnknownMemoryEffects(String site, String attributes,
                                                   String description) {
        if (site.contains("memory(") || attributes.contains("memory(")) {
            throw new AssertionError(description + " has restricted LLVM memory effects");
        }
    }

    private static Path findIRDump(String dumpPath, java.lang.reflect.Method method,
                                   boolean optimized) throws Exception {
        String prefix = method.getDeclaringClass().getName().replace('.', '_')
                + "_" + method.getName();
        try (var files = Files.list(Path.of(dumpPath))) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        if (!name.startsWith(prefix)) {
                            return false;
                        }
                        return optimized ? name.endsWith("_optimized.ll")
                                : name.endsWith(".ll") && !name.endsWith("_optimized.ll");
                    })
                    .sorted()
                    .reduce((first, second) -> second)
                    .orElseThrow(() -> new AssertionError("No matched IR dump found"));
        }
    }

    private static void runStoreOrderingMode() throws Exception {
        String dumpPath = Files.createTempDirectory("jeandle_writeback_store_order").toString();
        ArrayList<String> args = new ArrayList<>(List.of(
                "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
                "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
                "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR", "-XX:+JeandleDumpObjects",
                "-XX:JeandleDumpDirectory=" + dumpPath,
                "-XX:CompileCommand=compileonly," + StoreOrderingWrapper.class.getName()
                        + "::storeWritebackStore",
                "-XX:CompileCommand=inline,jdk.internal.misc.Unsafe::writebackMemory",
                StoreOrderingWrapper.class.getName()));

        OutputAnalyzer output = ProcessTools.executeCommand(
                ProcessTools.createLimitedTestJavaProcessBuilder(args));
        output.shouldHaveExitValue(0)
              .shouldContain("StoreOrderingWrapper PASSED")
              .shouldContain(WRITEBACK_LOG)
              .shouldContain(PRE_SYNC_LOG)
              .shouldContain(POST_SYNC_LOG);

        FileCheck checker = new FileCheck(dumpPath,
                StoreOrderingWrapper.class.getDeclaredMethod(
                        "storeWritebackStore", long.class, long.class, long.class), true);
        checker.checkPattern("store (atomic )?i64 %1");
        checker.checkPattern("call void @StubRoutines_data_cache_writeback");
        checker.checkPattern("call void @llvm\\.aarch64\\.dmb\\(i32 11\\)$");
        checker.checkPattern("store (atomic )?i64 %2");
        checker.checkNotPattern(FALLBACK_SYMBOL);
        checkAArch64MemoryContract(dumpPath,
                StoreOrderingWrapper.class.getDeclaredMethod(
                        "storeWritebackStore", long.class, long.class, long.class), true);
    }

    private static boolean cpuSupports(String feature) {
        String features = WhiteBox.getWhiteBox().getCPUFeatures();
        return Pattern.compile("(?<![a-z0-9])" + Pattern.quote(feature) + "(?![a-z0-9])")
                      .matcher(features).find();
    }

    static class TestWrapper {
        public static void main(String[] args) {
            int lineSize = U.dataCacheLineFlushSize();
            long allocation = U.allocateMemory(lineSize * 6L);
            long address = (allocation + lineSize - 1) & -lineSize;
            try {
                U.setMemory(address, lineSize * 4L, (byte) 0x5a);
                U.writebackMemory(address, 0);
                U.writebackMemory(address, 1);
                U.writebackMemory(address + 1, lineSize - 1L);
                U.writebackMemory(address, lineSize);
                U.writebackMemory(address + 1, lineSize);
                U.writebackMemory(address, lineSize * 4L);
                System.out.println("TestWriteback0 PASSED");
            } finally {
                U.freeMemory(allocation);
            }
        }
    }

    static class StoreOrderingWrapper {
        private static long value;

        static void storeWritebackStore(long address, long first, long second) {
            value = first;
            U.writebackMemory(address, Long.BYTES);
            value = second;
        }

        public static void main(String[] args) {
            int lineSize = U.dataCacheLineFlushSize();
            long allocation = U.allocateMemory(lineSize * 2L);
            long address = (allocation + lineSize - 1) & -lineSize;
            try {
                long second = 0x7766554433221100L;
                storeWritebackStore(address, 0x1122334455667788L, second);
                if (value != second) {
                    throw new AssertionError("second store was not observed");
                }
                System.out.println("StoreOrderingWrapper PASSED");
            } finally {
                U.freeMemory(allocation);
            }
        }
    }
}
