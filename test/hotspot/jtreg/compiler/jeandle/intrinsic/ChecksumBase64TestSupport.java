/*
 * Copyright (c) 2026, the Jeandle-JDK Authors. All Rights Reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 */

package compiler.jeandle.intrinsic;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;
import jdk.test.whitebox.WhiteBox;

final class ChecksumBase64TestSupport {
    record Entry(String id, String flag, String owner, String method,
                 String irOwner, String irPattern) { }

    static void run(Class<?> worker, List<Entry> entries, String... compileOnly) throws Exception {
        OutputAnalyzer reference = child(worker, null, List.of("-Xint"), compileOnly);
        String result = result(reference);
        check(worker, entries, compileOnly, "enabled", List.of(), Set.of(), result);
        check(worker, entries, compileOnly, "uncompressed-oops",
                List.of("-XX:-UseCompressedOops"), Set.of(), result);
        List<String> families = entries.stream().map(Entry::flag).distinct().toList();
        if (families.size() > 1) {
            for (String flag : families) {
                Set<String> ids = entries.stream().filter(e -> e.flag().equals(flag))
                        .map(Entry::id).collect(java.util.stream.Collectors.toSet());
                check(worker, entries, compileOnly, flag, List.of("-XX:-" + flag), ids, result);
            }
        }
        List<String> disabledFlags = entries.stream().map(Entry::flag).distinct()
                .map(flag -> "-XX:-" + flag).toList();
        check(worker, entries, compileOnly, "flags-disabled", disabledFlags,
                entries.stream().map(Entry::id).collect(java.util.stream.Collectors.toSet()), result);
        for (Entry entry : entries) {
            check(worker, entries, compileOnly, entry.id(),
                    List.of("-XX:DisableIntrinsic=" + entry.id()), Set.of(entry.id()), result);
        }
        Entry first = entries.get(0);
        check(worker, entries, compileOnly, "directive",
                List.of("-XX:CompileCommand=option," + first.owner() + "::*,ccstrlist,DisableIntrinsic,"
                        + first.id()), Set.of(first.id()), result);
    }

    private static OutputAnalyzer child(Class<?> worker, Path dump, List<String> extra,
                                        String[] compileOnly) throws Exception {
        List<String> args = new ArrayList<>(List.of(
                "-XX:+UnlockDiagnosticVMOptions", "-XX:+WhiteBoxAPI", "-Xbootclasspath/a:.",
                "-XX:+UseJeandleCompiler", "-XX:-TieredCompilation", "-Xbatch", "-Xmx256m",
                "-XX:CompileThreshold=1000000"));
        if (dump != null) {
            args.addAll(List.of("-Xlog:jeandle=debug", "-XX:+JeandleDumpIR",
                    "-XX:JeandleDumpDirectory=" + dump));
            for (String target : compileOnly) {
                args.add("-XX:CompileCommand=compileonly," + target);
            }
        }
        args.addAll(extra);
        args.add(worker.getName());
        args.add(dump == null ? "reference" : "compiled");
        OutputAnalyzer output = ProcessTools.executeCommand(
                ProcessTools.createLimitedTestJavaProcessBuilder(args));
        output.shouldHaveExitValue(0).shouldContain("RESULT ");
        return output;
    }

    private static void check(Class<?> worker, List<Entry> entries, String[] compileOnly,
                              String mode, List<String> extra, Set<String> disabled,
                              String reference) throws Exception {
        Path dump = Files.createTempDirectory("jeandle_" + worker.getSimpleName() + "_" + mode);
        OutputAnalyzer output = child(worker, dump, extra, compileOnly);
        Asserts.assertEQ(result(output), reference, "interpreter comparison in " + mode);
        output.shouldContain("EXECUTED compiled callers");
        for (Entry entry : entries) {
            boolean enabled = !disabled.contains(entry.id())
                    && output.getOutput().contains("FLAG " + entry.flag() + " true");
            Pattern log = Pattern.compile("Method `[^\\n]*" + Pattern.quote(entry.owner() + "."
                    + entry.method() + "(") + "[^\\n]* is parsed as intrinsic");
            Asserts.assertEQ(log.matcher(output.getOutput()).find(), enabled,
                    entry.id() + " intrinsic recognition in " + mode);
            StringBuilder ir = new StringBuilder();
            try (var paths = Files.list(dump)) {
                for (Path path : paths.filter(p -> p.getFileName().toString()
                        .startsWith(entry.irOwner().split("\\(", 2)[0])).filter(p -> p.toString().endsWith(".ll"))
                        .toList()) {
                    String module = Files.readString(path);
                    if (module.contains("@\"" + entry.irOwner() + ".")) {
                        ir.append(module);
                    }
                }
            }
            Asserts.assertTrue(ir.length() > 0, "no caller IR for " + entry.id());
            Asserts.assertEQ(Pattern.compile(entry.irPattern()).matcher(ir).find(), enabled,
                    entry.id() + " emitted implementation in " + mode + " at " + dump);
            if (enabled && !entry.id().equals("_updateCRC32")) {
                checkStubAbi(ir.toString(), entry.id());
            }
            System.out.println("PATH " + mode + " " + entry.id() + " " + enabled + " " + dump);
        }
    }

    private static void checkStubAbi(String ir, String id) {
        boolean base64 = id.startsWith("_base64_");
        boolean direct = id.contains("ByteBuffer");
        String heap = "ptr addrspace(1)";
        String data = direct ? "ptr" : heap;
        String parameters = base64
                ? heap + ", i64, i64, " + heap + ", i64, i64"
                : "i32, " + data + ", i64";
        if (id.contains("CRC32C")) {
            parameters += ", " + heap;
        } else if (id.equals("_base64_decodeBlock")) {
            parameters += ", i64";
        }
        String stub = base64 ? id.substring(1)
                : id.contains("CRC32C") ? "updateBytesCRC32C"
                : id.contains("Adler32") ? "updateBytesAdler32" : "updateBytesCRC32";
        if (direct) {
            stub += "_direct";
        }
        Pattern declaration = Pattern.compile("declare (?:void|i32) "
                + Pattern.quote("@StubRoutines_" + stub + "(" + parameters + ")"));
        Asserts.assertTrue(declaration.matcher(ir).find(), "native-word stub ABI for " + id);
        if (!base64 && !direct) {
            // The Java offset must be extended before the array header is added.
            Pattern address = Pattern.compile("(%[a-zA-Z0-9_.]+) = sext i32 [^\\n]+ to i64\\n"
                    + "\\s*(%[a-zA-Z0-9_.]+) = add i64 [0-9]+, \\1\\n"
                    + "\\s*%[a-zA-Z0-9_.]+ = getelementptr inbounds i8, ptr addrspace\\(1\\) "
                    + "%[a-zA-Z0-9_.]+, i64 \\2");
            Asserts.assertTrue(address.matcher(ir).find(), "native-width array offset for " + id);
        }
    }

    private static String result(OutputAnalyzer output) {
        return output.getStdout().lines().filter(line -> line.startsWith("RESULT "))
                .findFirst().orElseThrow();
    }

    static void compile(List<Method> methods) {
        WhiteBox wb = WhiteBox.getWhiteBox();
        Asserts.assertTrue(Boolean.TRUE.equals(wb.getBooleanVMFlag("UseJeandleCompiler")));
        for (Method method : methods) {
            Asserts.assertTrue(wb.enqueueMethodForCompilation(method, 4), "enqueue " + method);
            Asserts.assertTrue(wb.isMethodCompiled(method), "install " + method);
            Asserts.assertEQ(wb.getMethodCompilationLevel(method), 4, "compiler level " + method);
            System.out.println("COMPILED " + method);
        }
    }

    static void executed(List<Method> methods) {
        WhiteBox wb = WhiteBox.getWhiteBox();
        for (Method method : methods) {
            Asserts.assertTrue(wb.isMethodCompiled(method), "caller remained installed: " + method);
        }
        System.out.println("EXECUTED compiled callers");
    }

    static void flags(String... flags) {
        WhiteBox wb = WhiteBox.getWhiteBox();
        for (String flag : flags) {
            System.out.println("FLAG " + flag + " " + wb.getBooleanVMFlag(flag));
        }
    }

    static void throwsType(Class<? extends Throwable> type, Runnable action) {
        try {
            action.run();
        } catch (Throwable failure) {
            if (type.isInstance(failure)) {
                return;
            }
            throw new AssertionError("Expected " + type + ", got " + failure, failure);
        }
        throw new AssertionError("Expected " + type);
    }
}
