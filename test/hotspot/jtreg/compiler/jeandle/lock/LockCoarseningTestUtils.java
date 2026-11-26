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
 */

package compiler.jeandle.lock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

/** Shared helpers for the lock-coarsening jtreg suite. */
final class LockCoarseningTestUtils {
    static final int ITERATIONS = 100_000;

    private LockCoarseningTestUtils() {
    }

    static Path createDumpDirectory() throws IOException {
        return Files.createTempDirectory("jeandle_lock_coarsening");
    }

    static OutputAnalyzer runChild(Class<?> testClass, String compiledMethod,
                                   int lockingMode, String... inlinedMethods)
            throws Exception {
        List<String> command = new ArrayList<>(List.of(
                "-Xbatch", "-XX:-TieredCompilation",
                "-XX:-BackgroundCompilation", "-XX:-UseOnStackReplacement",
                "-XX:+UseJeandleCompiler", "-XX:+JeandleLockCoarsening",
                "-XX:+UnlockExperimentalVMOptions",
                "-XX:LockingMode=" + lockingMode,
                "-XX:CompileThreshold=100",
                "-XX:CompileCommand=compileonly," + testClass.getName()
                        + "::" + compiledMethod,
                "-XX:CompileCommand=dontinline," + testClass.getName()
                        + "::" + compiledMethod));
        for (String method : inlinedMethods) {
            command.add("-XX:CompileCommand=inline," + testClass.getName()
                    + "::" + method);
        }
        command.add(testClass.getName());
        command.add("child");
        return ProcessTools.executeTestJava(command.toArray(String[]::new));
    }

    static OutputAnalyzer runChildWithDump(Class<?> testClass,
                                           String compiledMethod,
                                           int lockingMode,
                                           Path dumpDirectory,
                                           String... inlinedMethods)
            throws Exception {
        List<String> command = new ArrayList<>(List.of(
                "-Xbatch", "-XX:-TieredCompilation",
                "-XX:-BackgroundCompilation", "-XX:-UseOnStackReplacement",
                "-XX:+UseJeandleCompiler", "-XX:+JeandleLockCoarsening",
                "-XX:+UnlockExperimentalVMOptions",
                "-XX:LockingMode=" + lockingMode,
                "-XX:CompileThreshold=100",
                "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dumpDirectory,
                "-XX:CompileCommand=compileonly," + testClass.getName()
                        + "::" + compiledMethod,
                "-XX:CompileCommand=dontinline," + testClass.getName()
                        + "::" + compiledMethod));
        for (String method : inlinedMethods) {
            command.add("-XX:CompileCommand=inline," + testClass.getName()
                    + "::" + method);
        }
        command.add(testClass.getName());
        command.add("child");
        return ProcessTools.executeTestJava(command.toArray(String[]::new));
    }

    static Path findOptimizedDump(Path directory, Class<?> testClass,
                                  String method) throws IOException {
        String prefix = testClass.getName().replace('.', '_') + "_" + method + "_";
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(path -> {
                        String name = path.getFileName().toString();
                        return name.startsWith(prefix)
                                && name.endsWith("_optimized.ll");
                    })
                    .max(Comparator.comparing(path ->
                            path.getFileName().toString()))
                    .orElseThrow(() -> new AssertionError(
                            "no optimized " + method + " IR dump in " + directory));
        }
    }

    static String read(Path path) throws IOException {
        return Files.readString(path);
    }

    static long countPattern(String text, Pattern pattern) {
        long count = 0;
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    static long countBasicLockSlots(String ir) {
        return countPattern(ir, Pattern.compile(
                "(?m)^\\s*%BasicLock\\S* = alloca i64\\b"));
    }

    static long countLifetimeStart(String ir) {
        return countPattern(ir, Pattern.compile(
                "\\bcall void @llvm\\.lifetime\\.start"));
    }

    static long countLifetimeEnd(String ir) {
        return countPattern(ir, Pattern.compile(
                "\\bcall void @llvm\\.lifetime\\.end"));
    }

    static void assertOneCoarsenedRegion(Path dumpDirectory, Class<?> testClass,
                                         String method) throws IOException {
        assertCoarsenedSlotCount(dumpDirectory, testClass, method, 1L, 1L);
    }

    static void assertAtMostOneCoarsenedRegion(Path dumpDirectory,
                                               Class<?> testClass,
                                               String method)
            throws IOException {
        assertCoarsenedSlotCount(dumpDirectory, testClass, method, 0L, 1L);
    }

    static void assertCoarsenedSlotCount(Path dumpDirectory,
                                         Class<?> testClass, String method,
                                         long minSlots, long maxSlots)
            throws IOException {
        Path dump = findOptimizedDump(dumpDirectory, testClass, method);
        String ir = read(dump);
        long slots = countBasicLockSlots(ir);
        long starts = countLifetimeStart(ir);
        long ends = countLifetimeEnd(ir);
        Asserts.assertTrue(slots >= minSlots && slots <= maxSlots,
                "expected " + minSlots + ".." + maxSlots
                        + " retained BasicLock slots in " + dump + ", got "
                        + slots);
        Asserts.assertTrue(starts <= slots,
                "BasicLock lifetime starts must not exceed slots in " + dump
                        + ", got slots=" + slots + ", starts=" + starts);
        Asserts.assertTrue(ends >= starts,
                "BasicLock lifetime ends must cover every start in " + dump
                        + ", got starts=" + starts + ", ends=" + ends);
    }
}
