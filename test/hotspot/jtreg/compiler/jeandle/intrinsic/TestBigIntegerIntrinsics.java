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
 * @summary Test BigInteger intrinsic results, array reuse, GC and stub selection
 * @requires vm.debug & (vm.simpleArch == "x64" | vm.simpleArch == "aarch64")
 * @library /test/lib /
 * @build jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm/timeout=600 -XX:-UseJeandleCompiler compiler.jeandle.intrinsic.TestBigIntegerIntrinsics
 */

package compiler.jeandle.intrinsic;

import com.sun.management.HotSpotDiagnosticMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.regex.Pattern;
import jdk.test.lib.Platform;
import jdk.test.lib.process.ProcessTools;
import jdk.test.whitebox.WhiteBox;

public class TestBigIntegerIntrinsics {
    private static final String[] FLAGS = {"UseMultiplyToLenIntrinsic", "UseSquareToLenIntrinsic",
            "UseMulAddIntrinsic", "UseMontgomeryMultiplyIntrinsic", "UseMontgomerySquareIntrinsic"};
    private static final String IDS = "_multiplyToLen,_squareToLen,_mulAdd,_montgomeryMultiply,"
            + "_montgomerySquare,_bigIntegerRightShiftWorker,_bigIntegerLeftShiftWorker";
    private static final String[] STUBS = {"multiplyToLen", "squareToLen", "mulAdd",
            "montgomeryMultiply", "montgomerySquare", "bigIntegerLeftShift", "bigIntegerRightShift"};
    private static final String[] WRAPPERS = {"multiplyToLen", "squareToLen", "mulAdd",
            "montgomeryMultiply", "montgomerySquare", "shiftLeft", "shiftRightImpl"};
    private static final Class<?> I = int.class, A = int[].class;
    private static final Class<?>[][] SIGNATURES = {{A, I, A, I, A}, {A, I, A}, {A, A, I, I, I},
            {A, A, A, I, long.class, A}, {A, A, I, long.class, A}, {A, I}, {I}};
    private static Method[] methods;
    private static volatile int[] allocationSink;

    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            exercise();
            return;
        }
        for (String mode : List.of("enabled", "flags", "disabled", "directive", "serial")) {
            run(mode);
        }
    }

    private static void run(String mode) throws Exception {
        Path dump = Files.createTempDirectory("biginteger-" + mode);
        Path log = dump.resolve("compilation.xml");
        List<String> command = new ArrayList<>(List.of("--add-opens=java.base/java.math=ALL-UNNAMED",
                "-Xbootclasspath/a:.", "-XX:+UnlockDiagnosticVMOptions", "-XX:+WhiteBoxAPI",
                "-XX:+UseJeandleCompiler", "-XX:-TieredCompilation", "-Xbatch",
                "-XX:-UseOnStackReplacement", "-XX:+LogCompilation", "-XX:LogFile=" + log,
                "-XX:+JeandleDumpIR", "-XX:+JeandleDumpObjects", "-XX:JeandleDumpDirectory=" + dump,
                "-Xms32m", "-Xmx64m", "-XX:CompileCommand=quiet"));
        for (String wrapper : WRAPPERS) {
            command.add("-XX:CompileCommand=compileonly,java.math.BigInteger::" + wrapper);
        }
        if (mode.equals("serial")) {
            command.addAll(List.of("-XX:+UseSerialGC", "-XX:-UseCompressedOops", "-XX:-UseTLAB"));
        } else {
            command.add("-XX:+UseG1GC");
        }
        for (String flag : FLAGS) {
            command.add("-XX:" + (mode.equals("flags") ? "-" : "+") + flag);
        }
        if (mode.equals("disabled")) {
            command.add("-XX:DisableIntrinsic=" + IDS);
        } else if (mode.equals("directive")) {
            command.add("-XX:CompileCommand=option,java.math.BigInteger::*,ccstrlist,DisableIntrinsic," + IDS);
        }
        command.add(TestBigIntegerIntrinsics.class.getName());
        command.add(mode);
        var output = ProcessTools.executeTestJava(command);
        Files.writeString(dump.resolve("output.log"), output.getOutput());
        output.shouldHaveExitValue(0).shouldContain("BigInteger assertions passed after compilation");
        String xml = Files.readString(log);
        if (mode.equals("enabled") || mode.equals("serial")) {
            check(Pattern.compile("<uncommon_trap[^>]*thread='[0-9]+'[^>]*reason='unhandled'[^>]*>\\s*"
                    + "<jvms bci='16' method='java.math.BigInteger multiplyToLen ").matcher(xml).find(),
                    "missing allocation exception deoptimization: " + dump);
        }
        StringBuilder ir = new StringBuilder();
        try (var paths = Files.list(dump)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".ll")
                    && !p.toString().endsWith("_optimized.ll")).toList()) {
                ir.append(Files.readString(path));
            }
        }
        for (int i = 0; i < STUBS.length; i++) {
            String wrapper = WRAPPERS[i];
            check(xml.lines().anyMatch(line -> line.contains("<nmethod")
                    && line.contains("compiler='jeandle'") && !line.contains("compile_kind='osr'")
                    && line.contains("java.math.BigInteger " + wrapper + " ")), "no Jeandle nmethod: " + wrapper);
            boolean expected = !mode.equals("disabled") && !mode.equals("directive") && (i < 5
                    ? output.getOutput().contains(FLAGS[i] + "=true")
                    : output.getOutput().contains("shiftStubs=true"));
            String name = "StubRoutines_" + STUBS[i];
            boolean present = ir.toString().contains("@" + name + "(");
            if (!present) {
                var alias = Pattern.compile("@" + name + " = alias [^\\n]*inttoptr \\(i64 ([0-9]+) to ptr\\)")
                        .matcher(ir);
                present = alias.find() && ir.toString().contains("call void inttoptr (i64 " + alias.group(1) + " to ptr)(");
            }
            check(present == expected, mode + ": " + name + " expected=" + expected + "; dumps=" + dump);
        }
        System.out.println("Verified " + mode + ": " + dump);
    }

    private static Object call(int index, Object receiver, Object... args) throws Exception {
        try {
            return methods[index].invoke(receiver, args);
        } catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof Exception cause) throw cause;
            throw ex;
        }
    }

    private static void exercise() throws Exception {
        WhiteBox wb = WhiteBox.getWhiteBox();
        methods = new Method[WRAPPERS.length];
        var bean = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
        for (String flag : FLAGS) System.out.println(flag + "=" + bean.getVMOption(flag).getValue());
        boolean shiftStubs = Platform.isX64() ? wb.getCPUFeatures().contains("avx512_vbmi2")
                : Boolean.parseBoolean(bean.getVMOption("UseSIMDForBigIntegerShiftIntrinsics").getValue());
        System.out.println("shiftStubs=" + shiftStubs);
        for (int i = 0; i < methods.length; i++) {
            methods[i] = BigInteger.class.getDeclaredMethod(WRAPPERS[i], SIGNATURES[i]);
            methods[i].setAccessible(true);
        }
        // Load callees and collect normal profiles before compiling the wrappers.
        arithmetic();
        for (Method method : methods) {
            wb.deoptimizeMethod(method);
            check(wb.enqueueMethodForCompilation(method, 4), "cannot enqueue " + method);
            check(wb.isMethodCompiled(method) && wb.getMethodCompilationLevel(method) == 4,
                    "not compiled: " + method);
            System.out.println("Compiled " + method.getName());
        }
        long gcBefore = gcCount();
        for (int repeat = 0; repeat < 12; repeat++) {
            arithmetic();
            wb.fullGC();
        }
        check(gcCount() > gcBefore, "GC did not occur");
        for (Method method : methods) check(wb.isMethodCompiled(method), "lost compiled wrapper: " + method);
        allocationGC();
        System.out.println("BigInteger assertions passed after compilation; GC=" + (gcCount() - gcBefore));
        allocationException();
        for (Method method : methods) wb.deoptimizeMethod(method);
        arithmetic();
        exceptions();
        System.out.println("BigInteger assertions passed after explicit deoptimization");
    }

    private static long gcCount() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(bean -> Math.max(0, bean.getCollectionCount())).sum();
    }

    private static void allocationGC() throws Exception {
        int[] x = new int[300_000];
        Arrays.fill(x, 0x87654321);
        int[] one = {1};
        long before = gcCount();
        for (int i = 0; i < 96; i++) {
            int[] result = (int[]) call(0, null, x, x.length, one, 1, null);
            check(result.length == x.length + 1 && result[0] == 0, "allocation length");
            for (int j = 0; j < x.length; j++) check(result[j + 1] == x[j], "allocation GC result");
            allocationSink = result;
        }
        check(gcCount() > before, "allocation loop did not trigger GC");
        System.out.println("Allocation assertions after GC=" + (gcCount() - before));
    }

    private static void allocationException() throws Exception {
        // Prepare the reflection call before filling the heap, so failure of
        // the 12 MB result allocation is observed inside the compiled wrapper.
        Object[] arguments = {new int[1_500_000], 1_500_000, new int[1_500_000], 1_500_000, null};
        List<byte[]> pressure = new ArrayList<>();
        OutOfMemoryError failure = null;
        boolean finalized = false;
        try {
            try {
                while (true) pressure.add(new byte[1_000_000]);
            } catch (OutOfMemoryError expected) { }
            // Leave room for reflection's InvocationTargetException and its
            // stack trace while keeping the 12 MB result unallocatable.
            for (int i = 0; i < 3; i++) pressure.remove(pressure.size() - 1);
            try {
                methods[0].invoke(null, arguments);
                throw new AssertionError("result allocation unexpectedly succeeded");
            } catch (InvocationTargetException ex) {
                check(ex.getCause() instanceof OutOfMemoryError, "wrong allocation exception");
                failure = (OutOfMemoryError) ex.getCause();
            }
        } finally {
            pressure.clear();
            finalized = true;
        }
        check(failure != null && finalized, "allocation catch/finally");
        check(Arrays.stream(failure.getStackTrace()).anyMatch(frame -> frame.getClassName().equals("java.math.BigInteger")
                && frame.getMethodName().equals("implMultiplyToLen")), "OOM did not come from BigInteger result allocation");
        System.out.println("Caught BigInteger allocation OOM after compiled invocation; finally completed");
    }

    private static void arithmetic() throws Exception {
        Random random = new Random(0x5eed);
        for (int len : new int[]{1, 2, 3, 7, 16, 31, 32, 33, 64, 127}) {
            int[] x = random.ints(len).toArray();
            int[] y = random.ints(len + 1).toArray();
            for (int fill : new int[]{0, -1, 0x80000000}) {
                x[0] = fill;
                for (int extra : new int[]{-2, -1, 0, 3}) {
                    int needed = x.length + y.length;
                    int[] z = extra == -2 ? null : new int[Math.max(0, needed + extra)];
                    if (z != null) Arrays.fill(z, 0x76543210);
                    int[] expected = product(x, y, z);
                    int[] result = (int[]) call(0, null, x, x.length, y, y.length, z);
                    check(Arrays.equals(result, expected), "multiply len=" + len + " extra=" + extra);
                    check((result == z) == (z != null && z.length >= needed), "multiply identity");
                }
                int[] z = new int[2 * len];
                int[] expected = product(x, x, z);
                int[] result = (int[]) call(1, null, x, len, z);
                check(result == z && Arrays.equals(result, expected), "square len=" + len);
            }
            for (int k : new int[]{0, 1, -1, Integer.MIN_VALUE}) {
                for (int offset : new int[]{0, 2}) {
                    int[] out = random.ints(len + offset + 3).toArray();
                    int[] expected = out.clone();
                    long carry = 0;
                    for (int j = len - 1, pos = out.length - offset - 1; j >= 0; j--, pos--) {
                        long p = Integer.toUnsignedLong(x[j]) * Integer.toUnsignedLong(k)
                                + Integer.toUnsignedLong(expected[pos]) + carry;
                        expected[pos] = (int) p;
                        carry = p >>> 32;
                    }
                    int result = (int) call(2, null, out, x, offset, len, k);
                    check(result == (int) carry && Arrays.equals(out, expected), "mulAdd");
                }
            }
            int[] out = x.clone();
            check((int) call(2, null, out, x, 0, 0, -1) == 0 && Arrays.equals(out, x), "mulAdd empty");
            out = x.clone();
            int[] aliasExpected = x.clone();
            long aliasCarry = 0;
            for (int j = len - 1; j >= 0; j--) {
                long p = Integer.toUnsignedLong(x[j]) * 0xffffffffL
                        + Integer.toUnsignedLong(x[j]) + aliasCarry;
                aliasExpected[j] = (int) p;
                aliasCarry = p >>> 32;
            }
            check((int) call(2, null, out, out, 0, len, -1) == (int) aliasCarry
                    && Arrays.equals(out, aliasExpected), "mulAdd alias");
            for (int shift : new int[]{1, 16, 31}) {
                BigInteger value = number(x, x.length);
                int[] leftExpected = new int[len + 1];
                int[] rightExpected = new int[len];
                long carry = 0;
                for (int j = len - 1; j >= 0; j--) {
                    long shifted = Integer.toUnsignedLong(x[j]) << shift | carry;
                    leftExpected[j + 1] = (int) shifted;
                    carry = shifted >>> 32;
                    rightExpected[j] = (x[j] >>> shift) | (j == 0 ? 0 : x[j - 1] << (32 - shift));
                }
                leftExpected[0] = (int) carry;
                int[] left = (int[]) call(5, null, x, shift);
                check(number(left, left.length).equals(number(leftExpected, leftExpected.length)), "left shift");
                for (BigInteger signed : new BigInteger[]{value, value.negate()}) {
                    BigInteger right = (BigInteger) call(6, signed, shift);
                    BigInteger expected = number(rightExpected, rightExpected.length);
                    if (signed.signum() < 0) {
                        if ((Integer.toUnsignedLong(x[len - 1]) & ((1L << shift) - 1)) != 0) {
                            expected = expected.add(BigInteger.ONE);
                        }
                        expected = expected.negate();
                    }
                    check(right.equals(expected), "right shift");
                }
            }
        }
        for (int len : new int[]{2, 4, 16, 64, 128, 512, 514}) {
            int[] n = random.ints(len).toArray();
            n[0] |= Integer.MIN_VALUE;
            n[len - 1] |= 1;
            BigInteger modulus = number(n, len);
            BigInteger a = new BigInteger(len * 32, random).mod(modulus);
            BigInteger b = new BigInteger(len * 32, random).mod(modulus);
            int[] aa = limbs(a, len), bb = limbs(b, len);
            long low = Integer.toUnsignedLong(n[len - 2]) << 32 | Integer.toUnsignedLong(n[len - 1]);
            long inverse = low;
            for (int i = 0; i < 6; i++) inverse *= 2 - low * inverse;
            BigInteger rInverse = BigInteger.ONE.shiftLeft(32 * len).modInverse(modulus);
            int[] result = (int[]) call(3, null, aa, bb, n, len, -inverse, null);
            check(number(result, len).mod(modulus).equals(a.multiply(b).multiply(rInverse).mod(modulus)), "Montgomery multiply");
            result = (int[]) call(4, null, aa, n, len, -inverse, null);
            check(number(result, len).mod(modulus).equals(a.multiply(a).multiply(rInverse).mod(modulus)), "Montgomery square");
        }
    }

    private static void exceptions() throws Exception {
        try {
            call(2, null, new int[2], new int[1], -1, 1, 2);
            throw new AssertionError("missing mulAdd argument exception");
        } catch (IllegalArgumentException expected) { }
        try {
            call(0, null, new int[1], 2, new int[1], 1, null);
            throw new AssertionError("missing multiply bounds exception");
        } catch (ArrayIndexOutOfBoundsException expected) { }
    }

    private static int[] product(int[] x, int[] y, int[] destination) {
        int needed = x.length + y.length;
        int[] z = destination == null || destination.length < needed ? new int[needed] : destination.clone();
        Arrays.fill(z, 0, needed, 0);
        for (int i = x.length - 1; i >= 0; i--) {
            long carry = 0;
            for (int j = y.length - 1; j >= 0; j--) {
                int pos = i + j + 1;
                long p = Integer.toUnsignedLong(x[i]) * Integer.toUnsignedLong(y[j])
                        + Integer.toUnsignedLong(z[pos]) + carry;
                z[pos] = (int) p;
                carry = p >>> 32;
            }
            z[i] = (int) carry;
        }
        return z;
    }

    private static BigInteger number(int[] limbs, int len) {
        byte[] bytes = new byte[len * 4];
        for (int i = 0; i < len; i++) {
            for (int j = 0; j < 4; j++) bytes[4 * i + j] = (byte) (limbs[i] >>> (24 - 8 * j));
        }
        return new BigInteger(1, bytes);
    }

    private static int[] limbs(BigInteger value, int len) {
        int[] result = new int[len];
        for (int i = len - 1; i >= 0; i--) {
            result[i] = value.intValue();
            value = value.shiftRight(32);
        }
        return result;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
