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
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 */

/*
 * @test
 * @summary Test Unsafe acquire load intrinsics in Jeandle
 * @modules java.base/jdk.internal.misc
 *          java.base/java.lang.ref:open
 * @library /test/lib /
 * @build jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm compiler.jeandle.intrinsic.TestUnsafeAcquire
 */

package compiler.jeandle.intrinsic;

import compiler.jeandle.fileCheck.FileCheck;
import java.io.RandomAccessFile;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import jdk.internal.misc.Unsafe;
import jdk.test.lib.Asserts;
import jdk.test.lib.Platform;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;
import jdk.test.whitebox.WhiteBox;

public class TestUnsafeAcquire {
    private static final String ACQUIRE_ORDERING_METHOD = "consumePublishedLong";
    private static final List<String> METHODS = List.of(
            "getReference", "getBoolean", "getByte", "getShort", "getChar",
            "getInt", "getLong", "getFloat", "getDouble");

    private static List<Method> nullBaseReferenceMethods()
            throws NoSuchMethodException {
        return List.of(
                TestMethods.class.getDeclaredMethod(
                        "getReferenceNullBase", long.class),
                TestMethods.class.getDeclaredMethod(
                        "getReferenceNullBasePhi", boolean.class, long.class));
    }

    private static Method mixedBaseReferenceMethod() throws NoSuchMethodException {
        return TestMethods.class.getDeclaredMethod(
                "getReferenceMixedBasePhi",
                boolean.class, Object.class, long.class);
    }

    private static Method knownZeroRawAddressMethod() throws NoSuchMethodException {
        return TestMethods.class.getDeclaredMethod("getIntNullZero");
    }

    public static void main(String[] args) throws Exception {
        runWith("-XX:+UseG1GC");
        runWith("-XX:+UseSerialGC");
        runWith("-XX:+UseG1GC", "-XX:-UseCompressedOops");
        runNullBaseReferenceFallback();
        runSignalRecovery();
    }

    private static void runWith(String... gcFlags) throws Exception {
        String dumpPath = Files.createTempDirectory("jeandle_unsafe_acquire").toString();
        ArrayList<String> command = new ArrayList<>(List.of(
                "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
                "--add-opens=java.base/java.lang.ref=ALL-UNNAMED",
                "-Xbatch", "-Xcomp", "-XX:-TieredCompilation",
                "-XX:+UseJeandleCompiler", "-Xlog:jeandle=debug",
                "-XX:+JeandleDumpIR", "-XX:JeandleDumpDirectory=" + dumpPath));
        command.addAll(List.of(gcFlags));
        for (String method : METHODS) {
            command.add("-XX:CompileCommand=compileonly," + TestMethods.class.getName()
                    + "::" + method);
        }
        command.add("-XX:CompileCommand=compileonly," + TestMethods.class.getName()
                + "::" + ACQUIRE_ORDERING_METHOD);
        command.add(TestMethods.class.getName());

        OutputAnalyzer output = ProcessTools.executeProcess(
                ProcessTools.createLimitedTestJavaProcessBuilder(command));
        output.shouldHaveExitValue(0).shouldContain("TEST PASSED");
        for (String name : List.of(
                "getReferenceAcquire", "getBooleanAcquire", "getByteAcquire",
                "getShortAcquire", "getCharAcquire", "getIntAcquire",
                "getLongAcquire", "getFloatAcquire", "getDoubleAcquire")) {
            output.shouldContain(name).shouldContain("is parsed as intrinsic");
        }

        checkIR(dumpPath, "getReference", "load atomic .* acquire");
        checkIR(dumpPath, "getBoolean", "load atomic i8, .* acquire, align 1");
        checkIR(dumpPath, "getByte", "load atomic i8, .* acquire, align 1");
        checkIR(dumpPath, "getShort", "load atomic i16, .* acquire, align 2");
        checkIR(dumpPath, "getChar", "load atomic i16, .* acquire, align 2");
        checkIR(dumpPath, "getInt", "load atomic i32, .* acquire, align 4");
        checkIR(dumpPath, "getLong", "load atomic i64, .* acquire, align 8");
        checkIR(dumpPath, "getFloat", "load atomic i32, .* acquire, align 4");
        checkIR(dumpPath, "getDouble", "load atomic i64, .* acquire, align 8");
        checkPrimitiveAddressSpaces(dumpPath);
        checkAcquireOrderingIR(dumpPath);
        checkReferenceAcquireOrderingIR(dumpPath);
        if (List.of(gcFlags).contains("-XX:+UseG1GC")) {
            checkReferenceLeafCall(dumpPath);
            checkG1ReferenceBarrierIR(dumpPath);
        }
    }

    private static void runNullBaseReferenceFallback() throws Exception {
        String dumpPath = Files.createTempDirectory(
                "jeandle_unsafe_acquire_null_base").toString();
        List<Method> methods = nullBaseReferenceMethods();
        Method mixedMethod = mixedBaseReferenceMethod();
        Method zeroAddressMethod = knownZeroRawAddressMethod();
        ArrayList<String> command = new ArrayList<>(List.of(
                "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
                "-Xbootclasspath/a:.",
                "-XX:+UnlockDiagnosticVMOptions", "-XX:+WhiteBoxAPI",
                "-Xbatch", "-Xcomp", "-XX:-TieredCompilation",
                "-XX:+UseJeandleCompiler", "-Xlog:jeandle=debug",
                "-XX:+JeandleDumpIR", "-XX:JeandleDumpDirectory=" + dumpPath));
        for (Method method : methods) {
            command.add("-XX:CompileCommand=compileonly,"
                    + TestMethods.class.getName() + "::" + method.getName());
        }
        command.add("-XX:CompileCommand=compileonly,"
                + TestMethods.class.getName() + "::" + mixedMethod.getName());
        command.add("-XX:CompileCommand=compileonly,"
                + TestMethods.class.getName() + "::" + zeroAddressMethod.getName());
        command.add(TestMethods.class.getName());
        command.add("testNullBaseReference");

        OutputAnalyzer output = ProcessTools.executeProcess(
                ProcessTools.createLimitedTestJavaProcessBuilder(command));
        output.shouldHaveExitValue(0)
                .shouldContain("NULL-BASE REFERENCE FALLBACKS PASSED");

        for (Method method : methods) {
            FileCheck checker = new FileCheck(dumpPath, method, true);
            // A base proven null is not a Java-heap reference access. The
            // intrinsic must reexecute the original Unsafe call instead of
            // forming an acquire load through a null oop.
            checker.checkPattern("@__llvm_deoptimize");
            checker.checkNotPattern("unsafe_reference_load");
            checker.checkNotPattern("load atomic .* acquire");
        }

        FileCheck mixedChecker = new FileCheck(dumpPath, mixedMethod, true);
        mixedChecker.checkPattern("icmp eq ptr addrspace\\(1\\).*, null");
        mixedChecker.checkPattern("br i1 .*unsafe_reference_load_raw");
        mixedChecker.checkPattern("load atomic .* acquire");
        mixedChecker.checkNotPattern("Unsafe_getReferenceVolatile");

        FileCheck mixedRawChecker = new FileCheck(dumpPath, mixedMethod, false);
        mixedRawChecker.checkPattern("unsafe_reference_load_raw:");
        mixedRawChecker.checkPattern(
                "llvm\\.experimental\\.deoptimize.*"
                + "\\[ \\\"deopt\\\"\\(i64 1,");

        FileCheck zeroAddressChecker = new FileCheck(
                dumpPath, zeroAddressMethod, true);
        zeroAddressChecker.checkPattern("@__llvm_deoptimize");
        zeroAddressChecker.checkNotPattern("unsafe_acquire");
        zeroAddressChecker.checkNotPattern("load atomic .* acquire");
    }

    private static void runSignalRecovery() throws Exception {
        if (!Platform.isLinux()) {
            return;
        }

        List<String> command = List.of(
                "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
                "--add-opens=java.base/java.nio=ALL-UNNAMED",
                "-Xbatch", "-Xcomp", "-XX:-TieredCompilation",
                "-XX:+UseJeandleCompiler", "-Xlog:jeandle=debug",
                "-XX:CompileCommand=compileonly," + TestMethods.class.getName()
                        + "::getInt",
                TestMethods.class.getName(), "testSIGBUS");

        OutputAnalyzer output = ProcessTools.executeProcess(
                ProcessTools.createLimitedTestJavaProcessBuilder(command));
        output.shouldHaveExitValue(0)
                .shouldContain("getIntAcquire")
                .shouldContain("is parsed as intrinsic")
                .shouldContain("SIGBUS correctly converted to InternalError");
    }

    private static void checkIR(String dumpPath, String method, String pattern)
            throws Exception {
        FileCheck checker = new FileCheck(dumpPath,
                TestMethods.class.getDeclaredMethod(
                        method, Object.class, long.class), true);
        checker.checkPattern(pattern);
    }

    private static void checkPrimitiveAddressSpaces(String dumpPath)
            throws Exception {
        FileCheck checker = new FileCheck(dumpPath,
                TestMethods.class.getDeclaredMethod(
                        "getInt", Object.class, long.class), false);
        checker.checkPattern("unsafe_acquire_base_is_null = "
                + "icmp eq ptr addrspace\\(1\\).*, null");
        checker.checkPattern("unsafe_acquire_heap_address = "
                + "getelementptr i8, ptr addrspace\\(1\\)");
        checker.checkPattern("unsafe_acquire_raw_address = "
                + "inttoptr i64 .* to ptr");
        checker.checkPattern("load atomic i32, ptr %unsafe_acquire_raw_address "
                + "acquire, align 4");
    }

    private static void checkReferenceLeafCall(String dumpPath) throws Exception {
        FileCheck checker = new FileCheck(dumpPath,
                TestMethods.class.getDeclaredMethod(
                        "getReference", Object.class, long.class), false);
        // The reference-load JavaOp and its G1 SATB helper are GC leaves. Any
        // deoptimization state belongs to the preceding null/raw guards, not
        // to the leaf access itself.
        checker.checkPattern(
                "call hotspotcc .*ptr addrspace\\(1\\) "
                + "@jeandle\\.unsafe_get_reference_acquire");
        checker.checkNotPattern(
                "call .*@jeandle\\.unsafe_get_reference_acquire.*"
                + "\\[ \"deopt\"");
    }

    private static void checkG1ReferenceBarrierIR(String dumpPath) throws Exception {
        FileCheck checker = new FileCheck(dumpPath,
                TestMethods.class.getDeclaredMethod(
                        "getReference", Object.class, long.class), true);
        // A successful load from Reference.referent must participate in G1's
        // SATB protocol. Checking only the acquire load would let the ordinary
        // strong-reference case pass even if this barrier were missing.
        checker.checkPattern("load atomic .* acquire");
        checker.checkPattern("load i8, ptr addrspace\\(2\\).*, align 64");
        checker.checkPattern("store atomic ptr addrspace\\(1\\).* unordered, align 8");
    }

    private static void checkAcquireOrderingIR(String dumpPath) throws Exception {
        FileCheck checker = new FileCheck(dumpPath,
                TestMethods.class.getDeclaredMethod(
                        ACQUIRE_ORDERING_METHOD, Holder.class, long.class), true);
        // Both loads must remain in one optimized function, with the ordinary
        // payload load after the acquire flag load.
        checker.checkPattern("load atomic i32, .* acquire, align 4");
        checker.checkPattern("load atomic i64, .* unordered, align 8");
    }

    private static void checkReferenceAcquireOrderingIR(String dumpPath)
            throws Exception {
        FileCheck checker = new FileCheck(dumpPath,
                TestMethods.class.getDeclaredMethod(
                        "getReference", Object.class, long.class), true);
        // CPUOrder fences constrain LLVM only; the acquire load carries the
        // Java memory-ordering contract and should lower to LDAR on AArch64.
        checker.checkPattern("fence syncscope\\(\"singlethread\"\\) seq_cst");
        checker.checkPattern("load atomic .* acquire");
        checker.checkPattern("fence syncscope\\(\"singlethread\"\\) seq_cst");
    }

    static class TestMethods {
        static final Unsafe U = Unsafe.getUnsafe();

        static Object getReference(Object base, long offset) {
            return U.getReferenceAcquire(base, offset);
        }

        static Object getReferenceNullBase(long offset) {
            return U.getReferenceAcquire(null, offset);
        }

        static Object getReferenceNullBasePhi(boolean firstPath, long offset) {
            Object base;
            if (firstPath) {
                base = null;
            } else {
                base = null;
            }
            return U.getReferenceAcquire(base, offset);
        }

        static Object getReferenceMixedBasePhi(
                boolean nullPath, Object nonNullBase, long offset) {
            Object base;
            if (nullPath) {
                base = null;
            } else {
                base = nonNullBase;
            }
            return U.getReferenceAcquire(base, offset);
        }

        static boolean getBoolean(Object base, long offset) {
            return U.getBooleanAcquire(base, offset);
        }

        static byte getByte(Object base, long offset) {
            return U.getByteAcquire(base, offset);
        }

        static short getShort(Object base, long offset) {
            return U.getShortAcquire(base, offset);
        }

        static char getChar(Object base, long offset) {
            return U.getCharAcquire(base, offset);
        }

        static int getInt(Object base, long offset) {
            return U.getIntAcquire(base, offset);
        }

        static int getIntNullZero() {
            return U.getIntAcquire(null, 0L);
        }

        static long getLong(Object base, long offset) {
            return U.getLongAcquire(base, offset);
        }

        static float getFloat(Object base, long offset) {
            return U.getFloatAcquire(base, offset);
        }

        static double getDouble(Object base, long offset) {
            return U.getDoubleAcquire(base, offset);
        }

        static long consumePublishedLong(Holder holder, long flagOffset) {
            if (U.getIntAcquire(holder, flagOffset) == 0) {
                return 0;
            }
            return holder.longValue;
        }

        public static void main(String[] args) throws Exception {
            if (args.length == 1 && args[0].equals("testNullBaseReference")) {
                testNullBaseReferenceFallback();
                return;
            }
            if (args.length == 1 && args[0].equals("testSIGBUS")) {
                testAcquireSIGBUS();
                return;
            }

            testHeapFields();
            testNativeMemory();
            testReferenceReferent();
            testReleaseAcquirePublication();
            System.out.println("TEST PASSED");
        }

        private static void testHeapFields() throws Exception {
            Holder holder = new Holder();
            holder.referenceValue = "reference";
            holder.booleanValue = true;
            holder.byteValue = (byte) 0x81;
            holder.shortValue = (short) 0x8123;
            holder.charValue = (char) 0x9123;
            holder.intValue = 0x87654321;
            holder.longValue = 0x8877665544332211L;
            holder.floatValue = Float.intBitsToFloat(0x7fc12345);
            holder.doubleValue = Double.longBitsToDouble(0x7ff8123456789abCL);

            for (int i = 0; i < 20_000; i++) {
                Asserts.assertEQ("reference", getReference(holder, offset("referenceValue")));
                Asserts.assertTrue(getBoolean(holder, offset("booleanValue")));
                Asserts.assertEQ((byte) 0x81, getByte(holder, offset("byteValue")));
                Asserts.assertEQ((short) 0x8123, getShort(holder, offset("shortValue")));
                Asserts.assertEQ((char) 0x9123, getChar(holder, offset("charValue")));
                Asserts.assertEQ(0x87654321, getInt(holder, offset("intValue")));
                Asserts.assertEQ(0x8877665544332211L, getLong(holder, offset("longValue")));
                Asserts.assertEQ(0x7fc12345, Float.floatToRawIntBits(
                        getFloat(holder, offset("floatValue"))));
                Asserts.assertEQ(0x7ff8123456789abCL, Double.doubleToRawLongBits(
                        getDouble(holder, offset("doubleValue"))));
            }
        }

        private static void testNativeMemory() {
            long address = U.allocateMemory(64);
            try {
                U.putByte(null, address, (byte) 2);
                U.putByte(null, address + 1, (byte) 0x81);
                U.putShort(null, address + 2, (short) 0x8123);
                U.putChar(null, address + 4, (char) 0x9123);
                U.putInt(null, address + 8, 0x87654321);
                U.putLong(null, address + 16, 0x8877665544332211L);
                U.putInt(null, address + 24, 0x7fc12345);
                U.putLong(null, address + 32, 0x7ff8123456789abCL);

                Asserts.assertTrue(getBoolean(null, address));
                Asserts.assertEQ((byte) 0x81, getByte(null, address + 1));
                Asserts.assertEQ((short) 0x8123, getShort(null, address + 2));
                Asserts.assertEQ((char) 0x9123, getChar(null, address + 4));
                Asserts.assertEQ(0x87654321, getInt(null, address + 8));
                Asserts.assertEQ(0x8877665544332211L, getLong(null, address + 16));
                Asserts.assertEQ(0x7fc12345, Float.floatToRawIntBits(
                        getFloat(null, address + 24)));
                Asserts.assertEQ(0x7ff8123456789abCL, Double.doubleToRawLongBits(
                        getDouble(null, address + 32)));
            } finally {
                U.freeMemory(address);
            }
        }

        private static void testNullBaseReferenceFallback() throws Exception {
            WhiteBox whiteBox = WhiteBox.getWhiteBox();
            ArrayList<Method> methods = new ArrayList<>(nullBaseReferenceMethods());
            methods.add(mixedBaseReferenceMethod());
            methods.add(knownZeroRawAddressMethod());
            for (Method method : methods) {
                Asserts.assertTrue(whiteBox.enqueueMethodForCompilation(method, 4),
                        "failed to enqueue " + method.getName());
                for (int i = 0; i < 100 && !whiteBox.isMethodCompiled(method); i++) {
                    Thread.sleep(10);
                }
                Asserts.assertTrue(whiteBox.isMethodCompiled(method),
                        method.getName() + " was not compiled");
            }

            Holder holder = new Holder();
            holder.referenceValue = "mixed-base-reference";
            Asserts.assertEQ(holder.referenceValue,
                    getReferenceMixedBasePhi(false, holder,
                            offset("referenceValue")));
            System.out.println("NULL-BASE REFERENCE FALLBACKS PASSED");
        }

        private static void testAcquireSIGBUS() throws Exception {
            Path path = Files.createTempFile("jeandle-unsafe-acquire-", ".bin");
            MappedByteBuffer mapped = null;
            try {
                Files.write(path, new byte[4096]);
                try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "rw");
                     FileChannel channel = file.getChannel()) {
                    mapped = channel.map(FileChannel.MapMode.READ_WRITE, 0, 4096);
                }

                Field addressField = java.nio.Buffer.class.getDeclaredField("address");
                addressField.setAccessible(true);
                long address = addressField.getLong(mapped);

                try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "rw")) {
                    file.setLength(0);
                }

                try {
                    int value = getInt(null, address);
                    throw new RuntimeException("Expected InternalError, got: " + value);
                } catch (InternalError expected) {
                    System.out.println("SIGBUS correctly converted to InternalError");
                }
            } finally {
                Reference.reachabilityFence(mapped);
                Files.deleteIfExists(path);
            }
        }

        private static void testReferenceReferent() throws Exception {
            Object referent = new Object();
            WeakReference<Object> reference = new WeakReference<>(referent);
            Field field = Reference.class.getDeclaredField("referent");
            field.setAccessible(true);
            long referentOffset = U.objectFieldOffset(field);
            Asserts.assertEQ(referent, getReference(reference, referentOffset));
        }

        private static void testReleaseAcquirePublication() throws Exception {
            Holder holder = new Holder();
            long flagOffset = offset("intValue");
            final int iterations = 20_000;
            Thread writer = new Thread(() -> {
                for (int value = 1; value <= iterations; value++) {
                    while (U.getIntAcquire(holder, flagOffset) != 0) {
                        Thread.onSpinWait();
                    }
                    holder.longValue = value;
                    U.putIntRelease(holder, flagOffset, 1);
                }
            });
            // Do not turn a reader-side assertion failure into a jtreg timeout.
            writer.setDaemon(true);
            writer.start();

            for (int expected = 1; expected <= iterations; expected++) {
                long observed;
                while ((observed = consumePublishedLong(holder, flagOffset)) == 0) {
                    Thread.onSpinWait();
                }
                Asserts.assertEQ((long) expected, observed);
                U.putIntRelease(holder, flagOffset, 0);
            }
            writer.join();
        }

        private static long offset(String name) throws Exception {
            return U.objectFieldOffset(Holder.class.getDeclaredField(name));
        }
    }

    static class Holder {
        Object referenceValue;
        boolean booleanValue;
        byte byteValue;
        short shortValue;
        char charValue;
        int intValue;
        long longValue;
        float floatValue;
        double doubleValue;
    }
}
