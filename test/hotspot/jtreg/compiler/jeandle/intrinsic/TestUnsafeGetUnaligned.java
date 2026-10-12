/*
 * Copyright (c) 2026, the Jeandle-JDK Authors. All Rights Reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 */
/*
 * @test
 * @summary Verify Jeandle Unsafe unaligned primitive load lowerings
 * @modules java.base/jdk.internal.misc
 * @library /test/lib /
 * @build compiler.jeandle.fileCheck.FileCheck
 * @run driver TestUnsafeGetUnaligned
 */
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import compiler.jeandle.fileCheck.FileCheck;
import jdk.internal.misc.Unsafe;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestUnsafeGetUnaligned {
    private static final Unsafe U = Unsafe.getUnsafe();
    private static final long BASE = U.arrayBaseOffset(byte[].class);

    public static void main(String[] args) throws Exception {
        if (args.length != 0) { semantics(); return; }
        run(true, null);
        run(false, "-XX:ControlIntrinsic=-_getShortUnaligned,-_getCharUnaligned,-_getIntUnaligned,-_getLongUnaligned");
        run(false, "-XX:-InlineUnsafeOps");
    }

    private static void run(boolean enabled, String option) throws Exception {
        Path dump = Files.createTempDirectory("jeandle_getunaligned_");
        List<String> command = new ArrayList<>(List.of(
                "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
                "-Xbatch", "-Xcomp", "-XX:-TieredCompilation", "-XX:-BackgroundCompilation",
                "-XX:+UseJeandleCompiler", "-XX:+UnlockDiagnosticVMOptions",
                "-XX:+JeandleDumpIR", "-XX:JeandleDumpDirectory=" + dump,
                "-Xlog:jeandle=debug,jit+compilation=debug", "-XX:+CIPrintCompilerName",
                "-XX:CompileCommand=compileonly,TestUnsafeGetUnaligned::*",
                "-XX:CompileCommand=dontinline,TestUnsafeGetUnaligned::getShort",
                "-XX:CompileCommand=dontinline,TestUnsafeGetUnaligned::getChar",
                "-XX:CompileCommand=dontinline,TestUnsafeGetUnaligned::getInt",
                "-XX:CompileCommand=dontinline,TestUnsafeGetUnaligned::getLong",
                "-XX:CompileCommand=dontinline,TestUnsafeGetUnaligned::rawInt",
                "-XX:CompileCommand=dontinline,TestUnsafeGetUnaligned::maybeZero"));
        if (option != null) command.add(option);
        command.add(TestUnsafeGetUnaligned.class.getName());
        command.add("child");
        OutputAnalyzer output = ProcessTools.executeCommand(
                ProcessTools.createLimitedTestJavaProcessBuilder(command));
        output.shouldHaveExitValue(0);
        String log = "(?s).*Unsafe\\.get(Short|Char|Int|Long)Unaligned.*is parsed as intrinsic.*";
        if (enabled) output.shouldMatch(log); else output.shouldNotMatch(log);
        checkIR(dump, enabled);
    }

    private static void checkIR(Path dump, boolean enabled) throws Exception {
        check(dump, "getShort", byte[].class, long.class, "i16", false, enabled);
        check(dump, "getChar", byte[].class, long.class, "i16", false, enabled);
        check(dump, "getInt", byte[].class, long.class, "i32", false, enabled);
        check(dump, "getLong", byte[].class, long.class, "i64", false, enabled);
        check(dump, "rawInt", long.class, null, "i32", true, enabled);
        FileCheck zero = new FileCheck(dump.toString(),
                TestUnsafeGetUnaligned.class.getDeclaredMethod("maybeZero", byte[].class, boolean.class), false);
        if (enabled) {
            zero.checkPattern("invoke hotspotcc i32.*Unsafe_getIntUnaligned");
            zero.checkNotPattern("inttoptr i64 0 to ptr");
        }
    }

    private static void check(Path dump, String name, Class<?> first, Class<?> second,
                              String type, boolean raw, boolean enabled) throws Exception {
        java.lang.reflect.Method method = raw
                ? TestUnsafeGetUnaligned.class.getDeclaredMethod(name, first)
                : TestUnsafeGetUnaligned.class.getDeclaredMethod(name, first, second);
        FileCheck check = new FileCheck(dump.toString(), method, false);
        if (!enabled) {
            String fallback = raw ? "Unsafe_getIntUnaligned"
                                  : "Unsafe_" + name + "Unaligned";
            check.checkPattern(fallback);
            return;
        }
        String load = "load " + type + ", ptr " +
                (raw ? ".*align 1" : "addrspace\\(1\\).*align 1");
        check.checkPattern(load);
        check.checkNotPattern(load.replace("load ", "load atomic "));
        if (raw) check.checkPattern("inttoptr i64");
    }

    private static short getShort(byte[] a, long o) { return U.getShortUnaligned(a, o); }
    private static char getChar(byte[] a, long o) { return U.getCharUnaligned(a, o); }
    private static int getInt(byte[] a, long o) { return U.getIntUnaligned(a, o); }
    private static long getLong(byte[] a, long o) { return U.getLongUnaligned(a, o); }
    private static int rawInt(long o) { return U.getIntUnaligned(null, o); }
    private static int maybeZero(byte[] a, boolean zero) {
        return zero ? U.getIntUnaligned(null, 0L) : U.getIntUnaligned(a, BASE + 1);
    }

    private static void semantics() {
        byte[] a = new byte[32];
        long odd = BASE + 1;
        U.putShortUnaligned(a, odd, (short) 0x8123);
        check(getShort(a, odd) == (short) 0x8123, "heap short");
        U.putCharUnaligned(a, odd, (char) 0x8123);
        check(getChar(a, odd) == (char) 0x8123, "heap char");
        U.putIntUnaligned(a, odd, 0x81234567);
        check(getInt(a, odd) == 0x81234567, "heap int");
        U.putLongUnaligned(a, odd, 0x8123456789abcdefL);
        check(getLong(a, odd) == 0x8123456789abcdefL, "heap long");
        maybeZero(a, false);
        long memory = U.allocateMemory(16);
        try {
            U.putIntUnaligned(null, memory + 1, 0x81234567);
            check(rawInt(memory + 1) == 0x81234567, "raw int");
        } finally { U.freeMemory(memory); }
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
