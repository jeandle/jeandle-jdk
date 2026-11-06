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
 * @summary A Jeandle-compiled adversarial substring search keeps time-to-safepoint bounded
 * @library /test/lib
 * @run main/othervm/timeout=300 compiler.jeandle.intrinsic.TestStringSafepointLatency
 */
package compiler.jeandle.intrinsic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import jdk.test.lib.process.ProcessTools;

/**
 * The substring-search kernel is a leaf routine without safepoint polls, so
 * the duration of one search call bounds its contribution to time-to-safepoint
 * (the caller's loop polls between calls). Repetitive text initially matches
 * the fixed filter characters and differs in the middle of the needle.
 * An adaptive filter can reject whole groups of candidates after learning
 * the mismatch position. This case guards against gross regressions in
 * search and GC pause durations; it does not establish a worst-case
 * complexity bound.
 */
public class TestStringSafepointLatency {
    // Generous upper bounds; measured values are printed for inspection and
    // must stay far below these limits.
    private static final long MAX_SEARCH_MS = 500;
    private static final long MAX_GC_MS = 500;
    private static final int SOURCE_CHARS = 1 << 20;
    private static final int NEEDLE_CHARS = 256;
    private static final int GC_PROBES = 10;

    private static volatile boolean running = true;
    private static volatile int sink;

    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            runChild();
            System.out.println("String safepoint latency PASSED");
            return;
        }
        Path log = Files.createTempFile("string-safepoint-latency", ".xml");
        var command = new ArrayList<>(List.of("-Xmixed", "-Xbatch", "-Xcomp",
                "-XX:-TieredCompilation", "-XX:-BackgroundCompilation", "-XX:+UseJeandleCompiler",
                "-XX:+UnlockDiagnosticVMOptions", "-XX:+LogCompilation", "-XX:LogFile=" + log,
                "-Xlog:jeandle=debug", "-Xmx64m", "-XX:+UseSerialGC",
                "-XX:CompileCommand=inline,java.lang.String::indexOf",
                "-XX:CompileCommand=compileonly," + TestStringSafepointLatency.class.getName() + "::search"));
        command.add(TestStringSafepointLatency.class.getName());
        command.add("child");
        var output = ProcessTools.executeTestJava(command.toArray(String[]::new));
        output.shouldHaveExitValue(0).shouldContain("String safepoint latency PASSED");
        output.shouldContain("StringLatin1.indexOf").shouldContain("is parsed as intrinsic");
        String xml = Files.readString(log);
        if (xml.lines().noneMatch(line -> line.contains("<nmethod")
                && line.contains("compiler='jeandle'")
                && line.contains(TestStringSafepointLatency.class.getName() + " search "))) {
            throw new AssertionError("Missing Jeandle compilation of the search root");
        }
    }

    private static void runChild() throws Exception {
        String source = "a".repeat(SOURCE_CHARS);
        String needle = "a".repeat(NEEDLE_CHARS / 2) + "b" + "a".repeat(NEEDLE_CHARS - NEEDLE_CHARS / 2 - 1);
        for (int i = 0; i < 50; i++) {
            sink += search(source, needle);
        }
        if (search(source, needle) != -1) {
            throw new AssertionError("adversarial fixture must not match");
        }
        long worstSearch = 0;
        for (int i = 0; i < 5; i++) {
            long begin = System.nanoTime();
            sink += search(source, needle);
            worstSearch = Math.max(worstSearch, System.nanoTime() - begin);
        }
        Thread worker = new Thread(() -> {
            while (running) {
                sink += search(source, needle);
            }
        });
        worker.start();
        long worstGc = 0;
        for (int i = 0; i < GC_PROBES; i++) {
            long begin = System.nanoTime();
            System.gc();
            worstGc = Math.max(worstGc, System.nanoTime() - begin);
            Thread.sleep(50);
        }
        running = false;
        worker.join();
        long searchMs = (worstSearch + 500_000) / 1_000_000;
        long gcMs = (worstGc + 500_000) / 1_000_000;
        System.out.println("worst adversarial search: " + searchMs + " ms");
        System.out.println("worst full GC while searching: " + gcMs + " ms");
        System.out.println("sink: " + sink);
        if (searchMs > MAX_SEARCH_MS) {
            throw new AssertionError("single adversarial search took " + searchMs + " ms");
        }
        if (gcMs > MAX_GC_MS) {
            throw new AssertionError("full GC while searching took " + gcMs + " ms");
        }
    }

    public static int search(String source, String target) {
        return source.indexOf(target, 0);
    }
}
