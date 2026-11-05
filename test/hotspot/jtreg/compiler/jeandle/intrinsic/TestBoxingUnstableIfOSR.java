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
 */

/*
 * @test
 * @summary A cache-hit-only boxing profile may use unstable-if in an OSR
 *          compilation; a real miss must reexecute valueOf and automatically
 *          produce a non-speculative OSR recompilation.
 * @library /test/lib /
 * @build jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm/timeout=300 -Xbootclasspath/a:.
 *      -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *      -XX:-TieredCompilation -Xbatch -XX:+UseOnStackReplacement
 *      -XX:+UseJeandleCompiler -XX:+JeandleUseProfile -XX:+JeandleDumpIR
 *      -XX:CompileThreshold=10000
 *      -XX:CompileCommand=compileonly,compiler.jeandle.intrinsic.TestBoxingUnstableIfOSR::osrBoxLoop
 *      compiler.jeandle.intrinsic.TestBoxingUnstableIfOSR
 */

package compiler.jeandle.intrinsic;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jdk.test.lib.Asserts;
import jdk.test.whitebox.WhiteBox;

public class TestBoxingUnstableIfOSR {
    private static final WhiteBox WB = WhiteBox.getWhiteBox();
    private static final int TIER4 = 4;
    private static final long WAIT_NANOS = TimeUnit.SECONDS.toNanos(60);

    private static volatile boolean requestMiss;
    private static volatile boolean missDone;
    private static volatile boolean stop;
    private static volatile Integer sink;
    private static volatile Integer missResult;
    private static volatile int missCount;
    private static volatile Throwable workerFailure;

    public static void main(String[] args) throws Exception {
        // Keep Integer.valueOf interpreted and mature its shared range-check
        // MDO with cache hits only. The compileonly command admits only the
        // OSR target below.
        for (int i = 0; i < 200_000; i++) {
            sink = Integer.valueOf((i & 63) - 32);
        }

        Method method = TestBoxingUnstableIfOSR.class.getDeclaredMethod(
                "osrBoxLoop");
        Path dumpDirectory = Paths.get(System.getProperty("user.dir"));
        String dumpPrefix = method.getDeclaringClass().getName()
                .replace('.', '_') + "_" + method.getName();
        Set<Path> initialDumps = optimizedDumps(dumpDirectory, dumpPrefix);

        Thread worker = new Thread(() -> {
            try {
                osrBoxLoop();
            } catch (Throwable throwable) {
                workerFailure = throwable;
            }
        }, "boxing-unstable-if-osr");
        worker.start();

        try {
            Path speculative = awaitFreshOSRDump(
                    method, dumpDirectory, dumpPrefix, initialDumps);
            String speculativeIR = Files.readString(speculative);
            Asserts.assertTrue(
                    speculativeIR.contains("box.cache_miss.unstable_if"),
                    "first OSR compilation did not speculate cache hit: "
                            + speculative);
            Asserts.assertFalse(speculativeIR.contains("@new_instance"),
                    "speculative OSR compilation retained allocation: "
                            + speculative);

            Set<Path> beforeMiss = optimizedDumps(dumpDirectory, dumpPrefix);
            requestMiss = true;
            waitUntil(() -> missDone,
                    "OSR cache miss did not resume after valueOf reexecution");

            Asserts.assertEquals(missResult.intValue(), Integer.MAX_VALUE,
                    "OSR cache miss lost its payload");
            Asserts.assertEquals(missCount, 1,
                    "OSR cache miss continuation executed more than once");
            Asserts.assertTrue(missResult != Integer.valueOf(Integer.MAX_VALUE),
                    "out-of-cache Integer.valueOf reused an object");
            Asserts.assertTrue(
                    WB.getMethodTrapCount(method, "unstable_if") > 0,
                    "OSR cache miss did not record an unstable-if trap");

            Path generic = awaitFreshOSRDump(
                    method, dumpDirectory, dumpPrefix, beforeMiss);
            String genericIR = Files.readString(generic);
            Asserts.assertFalse(
                    genericIR.contains("box.cache_miss.unstable_if"),
                    "post-trap OSR compilation repeated cache speculation: "
                            + generic);
            Asserts.assertTrue(genericIR.contains("@new_instance"),
                    "post-trap OSR compilation did not restore allocation: "
                            + generic);
        } finally {
            stop = true;
            worker.join(TimeUnit.SECONDS.toMillis(30));
        }

        Asserts.assertFalse(worker.isAlive(), "OSR worker did not terminate");
        if (workerFailure != null) {
            throw new RuntimeException("OSR worker failed", workerFailure);
        }
    }

    private static void osrBoxLoop() {
        int iteration = 0;
        while (!stop) {
            boolean miss = requestMiss;
            int value = miss ? Integer.MAX_VALUE : (iteration & 63) - 32;
            Integer boxed = Integer.valueOf(value);
            sink = boxed;
            if (miss) {
                missResult = boxed;
                missCount++;
                requestMiss = false;
                missDone = true;
            }
            iteration++;
        }
    }

    private static Path awaitFreshOSRDump(Method method, Path directory,
                                          String prefix, Set<Path> previous)
            throws Exception {
        long deadline = System.nanoTime() + WAIT_NANOS;
        while (System.nanoTime() < deadline) {
            if (workerFailure != null) {
                throw new RuntimeException("OSR worker failed", workerFailure);
            }
            if (WB.isMethodCompiled(method, true)
                    && WB.getMethodCompilationLevel(method, true) == TIER4) {
                Path fresh = optimizedDumps(directory, prefix).stream()
                        .filter(path -> !previous.contains(path))
                        .max(Comparator.comparingLong(
                                path -> path.toFile().lastModified()))
                        .orElse(null);
                if (fresh != null) {
                    return fresh;
                }
            }
            Thread.sleep(10);
        }
        throw new RuntimeException(
                "timed out waiting for a fresh level-4 OSR IR dump for "
                        + method);
    }

    private static Set<Path> optimizedDumps(Path directory, String prefix)
            throws Exception {
        try (Stream<Path> paths = Files.list(directory)) {
            return paths.filter(path -> {
                        String name = path.getFileName().toString();
                        return name.startsWith(prefix)
                                && name.endsWith("_optimized.ll");
                    })
                    .collect(Collectors.toCollection(HashSet::new));
        }
    }

    private static void waitUntil(BooleanSupplier condition, String message)
            throws Exception {
        long deadline = System.nanoTime() + WAIT_NANOS;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            if (workerFailure != null) {
                throw new RuntimeException("OSR worker failed", workerFailure);
            }
            Thread.sleep(1);
        }
        Asserts.assertTrue(condition.getAsBoolean(), message);
    }

    @FunctionalInterface
    private interface BooleanSupplier {
        boolean getAsBoolean();
    }
}
