/*
 * Copyright (c) 2026, the Jeandle-JDK Authors. All Rights Reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 */
/*
 * @test
 * @summary Verify Jeandle Unsafe unaligned primitive store lowerings
 * @modules java.base/jdk.internal.misc
 * @library /test/lib /
 * @build compiler.jeandle.fileCheck.FileCheck
 * @run driver TestUnsafePutUnaligned
 */
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import compiler.jeandle.fileCheck.FileCheck;
import jdk.internal.misc.Unsafe;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestUnsafePutUnaligned {
    private static final Unsafe U = Unsafe.getUnsafe();
    private static final long BASE = U.arrayBaseOffset(byte[].class);
    private static final List<String> INTRINSIC_LOGS = List.of(
            "Unsafe.putShortUnaligned(jobject, jlong, jshort)` is parsed as intrinsic",
            "Unsafe.putCharUnaligned(jobject, jlong, jchar)` is parsed as intrinsic",
            "Unsafe.putIntUnaligned(jobject, jlong, jint)` is parsed as intrinsic",
            "Unsafe.putLongUnaligned(jobject, jlong, jlong)` is parsed as intrinsic");

    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            semantics();
            return;
        }
        run(true, null);
        run(false, "-XX:ControlIntrinsic=-_putShortUnaligned,-_putCharUnaligned,-_putIntUnaligned,-_putLongUnaligned");
        run(false, "-XX:-InlineUnsafeOps");
    }

    private static void run(boolean enabled, String option) throws Exception {
        Path dump = Files.createTempDirectory("jeandle_putunaligned_");
        List<String> command = new ArrayList<>(List.of(
                "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
                "-Xbatch", "-Xcomp", "-XX:-TieredCompilation", "-XX:-BackgroundCompilation",
                "-XX:+UseJeandleCompiler", "-XX:+UnlockDiagnosticVMOptions",
                "-XX:+JeandleDumpIR", "-XX:JeandleDumpDirectory=" + dump,
                "-Xlog:jeandle=debug,jit+compilation=debug", "-XX:+CIPrintCompilerName",
                "-XX:CompileCommand=compileonly,TestUnsafePutUnaligned::*",
                "-XX:CompileCommand=dontinline,TestUnsafePutUnaligned::putShort",
                "-XX:CompileCommand=dontinline,TestUnsafePutUnaligned::putChar",
                "-XX:CompileCommand=dontinline,TestUnsafePutUnaligned::putInt",
                "-XX:CompileCommand=dontinline,TestUnsafePutUnaligned::putLong",
                "-XX:CompileCommand=dontinline,TestUnsafePutUnaligned::rawShort",
                "-XX:CompileCommand=dontinline,TestUnsafePutUnaligned::rawChar",
                "-XX:CompileCommand=dontinline,TestUnsafePutUnaligned::rawInt",
                "-XX:CompileCommand=dontinline,TestUnsafePutUnaligned::rawLong",
                "-XX:CompileCommand=dontinline,TestUnsafePutUnaligned::maybeZero"));
        if (option != null) command.add(option);
        command.add(TestUnsafePutUnaligned.class.getName());
        command.add("child");
        OutputAnalyzer output = ProcessTools.executeCommand(
                ProcessTools.createLimitedTestJavaProcessBuilder(command));
        output.shouldHaveExitValue(0);
        for (String intrinsicLog : INTRINSIC_LOGS) {
            if (enabled) {
                output.shouldContain(intrinsicLog);
            } else {
                output.shouldNotContain(intrinsicLog);
            }
        }
        checkIR(dump, enabled);
    }

    private static void checkIR(Path dump, boolean enabled) throws Exception {
        check(dump, "putShort", byte[].class, long.class, short.class, "i16", false, enabled);
        check(dump, "putChar", byte[].class, long.class, char.class, "i16", false, enabled);
        check(dump, "putInt", byte[].class, long.class, int.class, "i32", false, enabled);
        check(dump, "putLong", byte[].class, long.class, long.class, "i64", false, enabled);
        check(dump, "rawShort", long.class, short.class, null, "i16", true, enabled);
        check(dump, "rawChar", long.class, char.class, null, "i16", true, enabled);
        check(dump, "rawInt", long.class, int.class, null, "i32", true, enabled);
        check(dump, "rawLong", long.class, long.class, null, "i64", true, enabled);
        FileCheck zero = new FileCheck(dump.toString(),
                TestUnsafePutUnaligned.class.getDeclaredMethod("maybeZero", byte[].class, boolean.class), false);
        if (enabled) {
            zero.checkPattern("invoke hotspotcc void.*Unsafe_putLongUnaligned");
            zero.checkNotPattern("inttoptr i64 0 to ptr");
        }
    }

    private static void check(Path dump, String name, Class<?> first, Class<?> second, Class<?> value,
                              String type, boolean raw, boolean enabled) throws Exception {
        java.lang.reflect.Method method = raw
                ? TestUnsafePutUnaligned.class.getDeclaredMethod(name, first, second)
                : TestUnsafePutUnaligned.class.getDeclaredMethod(name, first, second, value);
        FileCheck check = new FileCheck(dump.toString(), method, false);
        if (!enabled) {
            check.checkNotPattern("unsafe_plain_put_");
            return;
        }
        String store = "store " + type + ".*?, ptr " +
                (raw ? ".*align 1" : "addrspace\\(1\\).*align 1");
        check.checkPattern(store);
        check.checkNotPattern(store.replace("store ", "store atomic "));
        if (raw) check.checkPattern("inttoptr i64");
    }

    private static void putShort(byte[] a, long o, short v) { U.putShortUnaligned(a, o, v); }
    private static void putChar(byte[] a, long o, char v) { U.putCharUnaligned(a, o, v); }
    private static void putInt(byte[] a, long o, int v) { U.putIntUnaligned(a, o, v); }
    private static void putLong(byte[] a, long o, long v) { U.putLongUnaligned(a, o, v); }
    private static void rawShort(long o, short v) { U.putShortUnaligned(null, o, v); }
    private static void rawChar(long o, char v) { U.putCharUnaligned(null, o, v); }
    private static void rawInt(long o, int v) { U.putIntUnaligned(null, o, v); }
    private static void rawLong(long o, long v) { U.putLongUnaligned(null, o, v); }
    private static void maybeZero(byte[] a, boolean zero) {
        if (zero) U.putLongUnaligned(null, 0L, 0L);
        else U.putLongUnaligned(a, BASE + 1, 0L);
    }

    private static void semantics() {
        byte[] a = new byte[32];
        long odd = BASE + 1;
        putShort(a, odd, (short) 0x8123);
        check(U.getShortUnaligned(a, odd) == (short) 0x8123, "heap short");
        putChar(a, odd, (char) 0x8123);
        check(U.getCharUnaligned(a, odd) == (char) 0x8123, "heap char");
        putInt(a, odd, 0x81234567);
        check(U.getIntUnaligned(a, odd) == 0x81234567, "heap int");
        putLong(a, odd, 0x8123456789abcdefL);
        check(U.getLongUnaligned(a, odd) == 0x8123456789abcdefL, "heap long");
        maybeZero(a, false);

        long memory = U.allocateMemory(32);
        try {
            long raw = memory + 1;
            rawShort(raw, (short) 0x8123);
            check(U.getShortUnaligned(null, raw) == (short) 0x8123, "raw short");
            rawChar(raw, (char) 0x8123);
            check(U.getCharUnaligned(null, raw) == (char) 0x8123, "raw char");
            rawInt(raw, 0x81234567);
            check(U.getIntUnaligned(null, raw) == 0x81234567, "raw int");
            rawLong(raw, 0x8123456789abcdefL);
            check(U.getLongUnaligned(null, raw) == 0x8123456789abcdefL, "raw long");
        } finally {
            U.freeMemory(memory);
        }
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
