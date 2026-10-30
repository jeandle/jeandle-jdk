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
 * @summary String operations preserve virtual arrays, stable contents, and live arrays across GC and deoptimization
 * @library /test/lib /
 * @modules java.base/jdk.internal.vm.annotation
 * @build jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @compile --patch-module java.base=${test.src} java/lang/JeandleStringIntrinsicCaller.java
 * @compile --patch-module java.base=${test.classes} TestStringIntrinsicIntegration.java
 * @run main/othervm/timeout=600 -XX:-UseJeandleCompiler compiler.jeandle.intrinsic.TestStringIntrinsicIntegration
 */
package compiler.jeandle.intrinsic;

import java.lang.JeandleStringIntrinsicCaller;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import compiler.jeandle.pea.PEATestUtils;
import jdk.internal.vm.annotation.Stable;
import jdk.test.lib.Asserts;
import jdk.test.lib.process.ProcessTools;
import jdk.test.whitebox.WhiteBox;

public class TestStringIntrinsicIntegration {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            // Patch only the helper package; test classes remain in the unnamed module.
            Path patch = Files.createTempDirectory("string-integration-patch");
            Path destination = patch.resolve("java/lang/JeandleStringIntrinsicCaller.class");
            Files.createDirectories(destination.getParent());
            try (var input = TestStringIntrinsicIntegration.class.getClassLoader()
                    .getResourceAsStream("java/lang/JeandleStringIntrinsicCaller.class")) {
                if (input == null) throw new AssertionError("Missing compiled string helper");
                Files.copy(input, destination);
            }
            ProcessTools.executeTestJava("--patch-module=java.base=" + patch,
                    "--add-exports=java.base/jdk.internal.vm.annotation=ALL-UNNAMED",
                    "-Dtest.jdk=" + System.getProperty("test.jdk"),
                    "-Dtest.class.path=" + System.getProperty("test.class.path"),
                    "-Dtest.classes=" + System.getProperty("test.classes"),
                    "-XX:-UseJeandleCompiler", TestStringIntrinsicIntegration.class.getName(),
                    patch.toString()).shouldHaveExitValue(0).shouldContain("String integration PASSED");
            return;
        }
        Method stable = Wrapper.class.getMethod("stable");
        Method mutable = Wrapper.class.getMethod("mutable");
        Method stableIndex = Wrapper.class.getMethod("stableIndex");
        Method virtual = Wrapper.class.getMethod("virtual", int.class);
        Method pressure = Wrapper.class.getMethod("pressure", char[].class);
        Method inlineRoot = Wrapper.class.getMethod("inlineRoot", char[].class);
        Method allocation = Wrapper.class.getMethod("allocation", char[].class, int.class, int.class);
        Method checkpoint = Wrapper.class.getDeclaredMethod("checkpoint");
        Method[] targets = {stable, stableIndex, mutable, virtual, pressure, inlineRoot, allocation};
        try (var run = builder(true, targets, checkpoint, args[0], "G1", true)
                .keepDumps().run()) {
            Asserts.assertTrue(run.finalIR(stableIndex).lines().contains("ret i32 -1"),
                    "explicit-count search folds trusted stable contents");
            Asserts.assertTrue(run.finalIR(mutable).lines().stream()
                    .anyMatch(line -> line.contains("load") && line.contains("i8")),
                    "mutable array contents remain runtime loads");
            var report = run.report(virtual);
            Asserts.assertTrue(report.round0Before().peaAllocCount() >= 3,
                    "virtual test reaches PEA with source and destination allocations");
            Asserts.assertEquals(report.finalAfter().peaAllocCount(), 0,
                    "short string operations eliminate virtual arrays");
            Asserts.assertEquals(run.finalIR(virtual).loweredAllocCount(), 0,
                    "no removed array survives as a lowered allocation");
            String callee = PEATestUtils.MethodId.of(checkpoint).llvmFunctionName();
            var bundle = report.finalAfter().deoptBundleAtCall(callee, 0);
            Asserts.assertTrue(bundle.virtualObjects().size() >= 3,
                    "live arrays have reconstruction descriptors");
            var inlineBundle = run.report(inlineRoot).finalAfter().deoptBundleAtCall(callee, 0);
            Asserts.assertTrue(!inlineBundle.inlineScopes().isEmpty(),
                    "active deoptimization retains the inlined pressure scope");
            for (Method target : targets) {
                PEATestUtils.assertStructuralSoundness(run.finalIR(target), target.getName());
            }
            Asserts.assertTrue(run.finalIR(stable).lines().contains("ret i32 -1"),
                    "stable nonzero array contents fold to the exact comparison");
        }
        for (String gc : List.of("G1", "Serial")) {
            for (boolean compressed : new boolean[]{true, false}) {
                var builder = builder(false, targets, checkpoint, args[0], gc, compressed);
                builder.runPEAOnOffEquivalent();
            }
        }
        System.out.println("String integration PASSED");
    }

    private static PEATestUtils.RunBuilder builder(boolean shape, Method[] targets,
            Method checkpoint, String patch, String gc, boolean compressed) throws Exception {
        var builder = shape ? PEATestUtils.shapeRun(Wrapper.class.getName(), targets)
                            : PEATestUtils.behaviorRun(Wrapper.class.getName(), targets);
        builder.dontinline(checkpoint).inline(Wrapper.class.getMethod("pressure", char[].class));
        for (Method helper : JeandleStringIntrinsicCaller.class.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isPublic(helper.getModifiers())) builder.inline(helper);
        }
        return builder.extraFlags("--patch-module=java.base=" + patch,
                "-Xbootclasspath/a:.:" + System.getProperty("test.class.path"),
                "--add-exports=java.base/jdk.internal.vm.annotation=ALL-UNNAMED",
                "-XX:+Use" + gc + "GC", "-Xms32m", "-Xmx32m",
                "-XX:" + (compressed ? "+" : "-") + "UseCompressedOops",
                "-XX:" + (compressed ? "+" : "-") + "UseCompressedClassPointers",
                "-Xlog:gc", "-XX:CompileThreshold=20000");
    }

    public static class Wrapper {
        @Stable private static final byte[] STABLE_A = {1, 127, (byte) 254};
        @Stable private static final byte[] STABLE_B = {1, 127, (byte) 255};
        private static final byte[] MUTABLE_A = {1, 127, (byte) 254};
        private static final byte[] MUTABLE_B = {1, 127, (byte) 255};
        private static final WhiteBox WB = WhiteBox.getWhiteBox();
        private static final java.lang.management.GarbageCollectorMXBean[] COLLECTORS =
                java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()
                        .toArray(java.lang.management.GarbageCollectorMXBean[]::new);
        private static Method active;
        private static int checkpoints;
        private static volatile Object allocationSink;

        public static void main(String[] args) throws Exception {
            Asserts.assertNull(Wrapper.class.getClassLoader(), "Stable annotations require a trusted loader");
            // Link helper methods and obtain profiles before explicitly compiling targets.
            JeandleStringIntrinsicCaller.compareToL(STABLE_A, STABLE_B);
            char[] input = new char[257];
            for (int i = 0; i < input.length; i++) input[i] = (char) ((i * 73 + 129) & 255);
            virtual(11);
            pressure(input);
            inlineRoot(input);
            allocation(input, 1, 255);
            PEATestUtils.compileConfiguredTargetsAtLevel4();
            Asserts.assertEquals(stable(), -1);
            Asserts.assertEquals(stableIndex(), -1);
            Asserts.assertEquals(mutable(), -1);
            MUTABLE_A[2] = 0;
            Asserts.assertEquals(mutable(), -255, "compiled loads observe array mutation");
            MUTABLE_A[2] = (byte) 254;
            active = Wrapper.class.getMethod("virtual", int.class);
            Asserts.assertEquals(virtual(73), 3 + 73 + 255);
            active = Wrapper.class.getMethod("pressure", char[].class);
            Asserts.assertEquals(pressure(input), checksum(input));
            active = Wrapper.class.getMethod("inlineRoot", char[].class);
            Asserts.assertEquals(inlineRoot(input), checksum(input) + 17);
            active = null;
            // Resolve the verifier before creating the young source. Once the
            // source exists, only the compiled copy allocates pressure objects;
            // collection cannot fall on a competing source allocation.
            verifyCopy(input, 0, 0, new byte[0]);
            char[] source = new char[32768];
            for (int j = 0; j < source.length; j++) source[j] = (char) j;
            long before = collections();
            long copyCollections = 0;
            int relocatedSources = 0;
            int copies = 0;
            // Two heaps' worth of output allocations bounds the wait for GC.
            while (copies < 1024 && (copyCollections == 0 || relocatedSources == 0)) {
                long address = WB.getObjectAddress(source);
                long beforeCopy = collections();
                byte[] bytes = allocation(source, 1, source.length - 2);
                copyCollections += collections() - beforeCopy;
                if (address != WB.getObjectAddress(source)) relocatedSources++;
                verifyCopy(source, 1, source.length - 2, bytes);
                allocationSink = bytes;
                copies++;
            }
            Asserts.assertTrue(collections() > before, "allocation loop must cause real GC");
            Asserts.assertTrue(copyCollections > 0, "toBytes allocation itself must cause GC");
            Asserts.assertTrue(relocatedSources > 0, "live source must relocate across toBytes allocation");
            System.out.println("STRING-ALLOC-GC:" + copyCollections + ":relocated=" + relocatedSources
                    + ":copies=" + copies);
            char[] failingSource = new char[9_000_000];
            failingSource[0] = '\u1234';
            failingSource[failingSource.length - 1] = '\uffff';
            Asserts.assertTrue(WB.isMethodCompiled(Wrapper.class.getMethod("allocation",
                    char[].class, int.class, int.class)), "allocation root is compiled before OOME");
            try {
                allocation(failingSource, 0, failingSource.length);
                throw new AssertionError("copy must exceed the 32 MB heap with its live source");
            } catch (OutOfMemoryError expected) {
                Asserts.assertTrue(allocationSink == failingSource, "unwind retains original source identity");
                Asserts.assertEquals(failingSource[0], '\u1234');
                Asserts.assertEquals(failingSource[failingSource.length - 1], '\uffff');
            }
            allocationSink = null;
            char[] invalidSource = new char[1];
            try {
                allocation(invalidSource, 1, 4);
                throw new AssertionError("missing allocation copy exception");
            } catch (IndexOutOfBoundsException expected) {
                Asserts.assertTrue(allocationSink == invalidSource, "guard replay retains source identity");
            }
            Asserts.assertEquals(checkpoints, 3, "root, virtual, and inlinee active frames");
            System.out.println("PEA-RESULT:stable-mutable-virtual-pressure-allocation");
        }

        public static int stable() {
            return JeandleStringIntrinsicCaller.compareToL(STABLE_A, STABLE_B);
        }

        public static int stableIndex() {
            return JeandleStringIntrinsicCaller.indexOfIL(STABLE_A, 3, STABLE_B, 3, 0);
        }

        public static int mutable() {
            return JeandleStringIntrinsicCaller.compareToL(MUTABLE_A, MUTABLE_B);
        }

        public static int virtual(int seed) {
            char[] source = {(char) seed, 255, 0};
            byte[] bytes = new byte[3];
            char[] restored = new char[3];
            byte[] alias = bytes;
            int count = JeandleStringIntrinsicCaller.compressStringC(source, 0, bytes, 0, 3);
            JeandleStringIntrinsicCaller.inflateStringC(bytes, 0, restored, 0, 3);
            int same = JeandleStringIntrinsicCaller.compareToL(bytes, alias);
            checkpoint();
            if (bytes != alias || source == restored || count != 3 || same != 0
                    || bytes[0] != (byte) seed || (bytes[1] & 255) != 255 || bytes[2] != 0
                    || restored[0] != (char) seed || restored[1] != 255 || restored[2] != 0
                    || source[0] != (char) seed || source[1] != 255 || source[2] != 0) {
                return -1;
            }
            alias[0] = 42;
            if (bytes[0] != 42 || restored[0] != seed) return -2;
            return count + restored[0] + restored[1];
        }

        public static long inlineRoot(char[] input) {
            long value = pressure(input);
            return value + 17;
        }

        public static long pressure(char[] input) {
            int length = input.length;
            byte[] wide = JeandleStringIntrinsicCaller.toBytesStringU(input, 0, length);
            byte[] wideAlias = wide;
            byte[] latin = new byte[length];
            byte[] compressed = new byte[length];
            byte[] inflatedBytes = new byte[2 * length];
            char[] restored = new char[length];
            char[] inflatedChars = new char[length];
            int c = JeandleStringIntrinsicCaller.compressStringC(input, 0, latin, 0, length);
            int b = JeandleStringIntrinsicCaller.compressStringB(wide, 0, compressed, 0, length);
            JeandleStringIntrinsicCaller.inflateStringB(latin, 0, inflatedBytes, 0, length);
            JeandleStringIntrinsicCaller.inflateStringC(latin, 0, inflatedChars, 0, length);
            JeandleStringIntrinsicCaller.getCharsStringU(wide, 0, length, restored, 0);
            int lu = JeandleStringIntrinsicCaller.compareToLU(latin, wide);
            int ul = JeandleStringIntrinsicCaller.compareToUL(wide, latin);
            int uu = JeandleStringIntrinsicCaller.compareToU(wide, inflatedBytes);
            boolean equal = JeandleStringIntrinsicCaller.equalsU(wide, inflatedBytes);
            checkpoint();
            if (c != length || b != length || lu != 0 || ul != 0 || uu != 0 || !equal
                    || wideAlias != wide || wide == inflatedBytes || latin == compressed
                    || restored == inflatedChars || restored == input) {
                throw new AssertionError("live array identities and scalar results");
            }
            long digest = 0;
            for (int i = 0; i < length; i++) {
                if ((latin[i] & 255) != input[i] || compressed[i] != latin[i]
                        || restored[i] != input[i] || inflatedChars[i] != input[i]
                        || wide[2 * i] != inflatedBytes[2 * i]
                        || wide[2 * i + 1] != inflatedBytes[2 * i + 1]) {
                    throw new AssertionError("live array content at " + i);
                }
                digest = digest * 31 + restored[i];
            }
            wideAlias[0] ^= 1;
            if (wide[0] == inflatedBytes[0]) throw new AssertionError("independent byte arrays");
            return digest;
        }

        public static byte[] allocation(char[] input, int offset, int count) {
            try {
                return JeandleStringIntrinsicCaller.toBytesStringU(input, offset, count);
            } catch (IndexOutOfBoundsException | OutOfMemoryError failure) {
                allocationSink = input;
                throw failure;
            }
        }

        private static void checkpoint() {
            if (active == null) return;
            WB.fullGC();
            var evidence = PEATestUtils.deoptimizeActiveFrame(active, 2);
            if (!evidence.frameDeoptimized() || evidence.markedNMethods() != 1
                    || evidence.compilationLevel() != 4) throw new AssertionError("active frame proof");
            checkpoints++;
            System.out.println("STRING-ACTIVE-DEOPT:" + active.getName());
        }

        private static long collections() {
            long count = 0;
            for (var bean : COLLECTORS) {
                count += Math.max(0, bean.getCollectionCount());
            }
            return count;
        }

        private static long checksum(char[] source) {
            long value = 0;
            for (char c : source) value = value * 31 + c;
            return value;
        }

        private static void verifyCopy(char[] input, int offset, int count, byte[] bytes) {
            if (bytes.length != 2 * count) {
                throw new AssertionError("allocating copy length: " + bytes.length + " != " + 2 * count);
            }
            boolean big = java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.BIG_ENDIAN;
            for (int i = 0; i < count; i++) {
                int a = bytes[2 * i] & 255;
                int b = bytes[2 * i + 1] & 255;
                if ((big ? a << 8 | b : a | b << 8) != input[offset + i]) {
                    throw new AssertionError("allocating copy at " + i);
                }
            }
        }
    }
}
