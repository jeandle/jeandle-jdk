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
 * You should have received a copy of the GNU General Public License
 * version 2 along with this work; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you have any questions.
 */

/*
 * @test
 * @summary Paired virtual-array hashes preserve narrow cells, aliases and overflow seeds across GC and deoptimization
 * @requires (vm.cpu.features ~= ".*avx2.*") | (vm.simpleArch == "aarch64")
 * @library /test/lib /
 * @modules java.base/jdk.internal.misc java.base/jdk.internal.util
 * @build jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler compiler.jeandle.intrinsic.TestVectorizedHashCodePairedDeopt
 */

package compiler.jeandle.intrinsic;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import compiler.jeandle.pea.PEATestUtils;
import jdk.internal.misc.Unsafe;
import jdk.internal.util.ArraysSupport;
import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;
import jdk.test.whitebox.WhiteBox;

public class TestVectorizedHashCodePairedDeopt {
    public static void main(String[] args) throws Exception {
        Method target = Child.class.getDeclaredMethod("target", int.class, int.class, boolean.class);
        Method checkpoint = Child.class.getDeclaredMethod("checkpoint", boolean.class);
        String root = PEATestUtils.MethodId.rootOf(target).llvmFunctionName();
        int[][] cases = {{-129, Integer.MIN_VALUE}, {-1, -1}, {0, 0}, {127, 31}, {255, Integer.MAX_VALUE}};
        for (int[] input : cases) {
            int value = input[0];
            int seed = input[1];
            Path dumps = Files.createTempDirectory(Path.of("."), "hash-paired-deopt-");
            Path log = dumps.resolve("compilation.xml");
            OutputAnalyzer output = ProcessTools.executeCommand(
                    ProcessTools.createLimitedTestJavaProcessBuilder(
                            "--add-exports=java.base/jdk.internal.util=ALL-UNNAMED",
                            "-Xbootclasspath/a:.", "-XX:+UnlockDiagnosticVMOptions", "-XX:+WhiteBoxAPI",
                            "-Xbatch", "-XX:-TieredCompilation", "-XX:CompileThreshold=100",
                            "-XX:+UseJeandleCompiler", "-XX:+JeandleDoPEA", "-Xlog:jeandle=debug",
                            "-XX:+LogCompilation", "-XX:LogFile=" + log,
                            "-XX:+JeandleDumpIR", "-XX:JeandleDumpDirectory=" + dumps,
                            "-XX:JeandleLLVMOptions=-jeandle-trace-pea -jeandle-dump-pea-stats"
                                    + " -jeandle-pea-analyze-function=" + root
                                    + " -jeandle-dump-pea-ir-function=" + root,
                            "-XX:CompileCommand=compileonly," + Child.class.getName() + "::target",
                            "-XX:CompileCommand=dontinline," + Child.class.getName() + "::checkpoint",
                            Child.class.getName(), Integer.toString(value), Integer.toString(seed)));
            output.shouldHaveExitValue(0)
                    .shouldContain("HASH-PAIRED-DEOPT:level=4:marked=1:frame=true:gc=true")
                    .shouldContain("HASH-PAIRED-DEOPT-PASSED:" + value + ":" + seed)
                    .shouldContain("jdk.internal.util.ArraysSupport.vectorizedHashCode")
                    .shouldContain("is parsed as intrinsic");
            Files.writeString(dumps.resolve("stdout.log"), output.getOutput());
            String method = Child.class.getName() + " target ";
            Asserts.assertTrue(Files.readString(log).lines().anyMatch(line -> line.contains("<nmethod")
                            && line.contains("compiler='jeandle'") && line.contains(method)
                            && !line.contains("compile_kind='osr'")),
                    "target executed through a normal-entry Jeandle nmethod");
            assertDeoptState(dumps, output.getStderr(), target, checkpoint);
        }
    }

    private static void assertDeoptState(Path dumps, String stderr, Method target, Method checkpoint)
            throws Exception {
        PEATestUtils.MethodId id = PEATestUtils.MethodId.rootOf(target);
        PEATestUtils.PEAReport report = PEATestUtils.PEAReport.parse(stderr, id).report(id);
        report.round0Before().assertPresent("@jeandle.new_array(");
        report.round0Before().assertPresent("@jeandle.vectorized_hashcode(");
        PEATestUtils.IRBody after = report.finalAfter();
        Asserts.assertEquals(after.peaAllocCount(), 0, "array remains virtual at the checkpoint");
        after.assertAbsent("jeandle.vectorized_hashcode");
        PEATestUtils.IRBody folded = report.rounds().get(0).after();
        folded.assertAbsent("mul <4 x i32>");
        if (System.getProperty("os.arch").equals("amd64")
                || System.getProperty("os.arch").equals("x86")) {
            Asserts.assertTrue(folded.lines().stream().anyMatch(line ->
                            line.contains(" = mul i32 ") && line.contains(", 961")),
                    "adjacent dynamic cells use a paired update on x86");
            Asserts.assertTrue(folded.lines().stream().anyMatch(line ->
                            line.contains(" = mul i32 ") && line.contains(", 29791")),
                    "constant runs are compressed before adjacent updates compose");
        }
        PEATestUtils.IRBody lowered = PEATestUtils.finalIR(dumps, id);
        Asserts.assertEquals(lowered.loweredAllocCount(), 0, "final code does not allocate");
        lowered.assertAbsent("%tlab_");
        PEATestUtils.DeoptBundle bundle = after.deoptBundleAtCall(
                PEATestUtils.MethodId.of(checkpoint).llvmFunctionName(), 0);
        bundle.assertVirtualObjectIds(0);
        Asserts.assertEquals(bundle.scopes().size(), 1, "one exact active root scope");
        PEATestUtils.VirtualObjectDescriptor array = bundle.virtualObject(0);
        Asserts.assertEquals(array.kind(), PEATestUtils.DescriptorKind.ARRAY, "virtual array descriptor");
        int base = Unsafe.ARRAY_BYTE_BASE_OFFSET;
        Asserts.assertEquals(array.elements().size(), 17, "all initialized cells are described");
        PEATestUtils.DeoptValue hash = bundle.rootScope().locals().get(5);
        Asserts.assertNotNull(hash, "live scalar hash local");
        Asserts.assertEquals(hash.kind(), PEATestUtils.DeoptValueKind.SCALAR, "hash is scalar state");
        Asserts.assertEquals(hash.basicType(), PEATestUtils.DeoptBasicType.INT, "hash wraps as int");
        Asserts.assertTrue(hash.operand().startsWith("i32 %"), "hash depends on the runtime input");
        Asserts.assertEquals(array.elements().get(base + 8 * Unsafe.ARRAY_BYTE_INDEX_SCALE).value().kind(),
                PEATestUtils.DeoptValueKind.SCALAR, "narrowed hash remains a dynamic virtual cell");
        for (int local : new int[]{3, 4}) {
            PEATestUtils.DeoptValue alias = bundle.rootScope().locals().get(local);
            Asserts.assertNotNull(alias, "live array alias local " + local);
            Asserts.assertEquals(alias.kind(), PEATestUtils.DeoptValueKind.VO_REF, "alias is virtual");
            Asserts.assertEquals(alias.virtualObjectId(), array.id(), "both aliases reconstruct one array");
        }
    }

    public static class Child {
        private static final WhiteBox WB = WhiteBox.getWhiteBox();
        private static final Method TARGET;
        private static volatile int observed;
        private static int events;

        static {
            try {
                TARGET = Child.class.getDeclaredMethod("target", int.class, int.class, boolean.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        static int target(int value, int seed, boolean trigger) {
            // Thirteen dynamic byte cells and two constant runs cost 43,
            // leaving an odd final affine segment below the scalar limit.
            byte[] a = {
                    (byte) value, 7, -1, (byte) (value + 3), (byte) (value + 4),
                    (byte) (value + 5), (byte) (value + 6), 0, 5,
                    (byte) (value + 9), (byte) (value + 10), (byte) (value + 11),
                    (byte) (value + 12), (byte) (value + 13), (byte) (value + 14),
                    (byte) (value + 15), (byte) (value + 16)
            };
            byte[] alias = a;
            int hash = ArraysSupport.vectorizedHashCode(a, 0, 17, seed, ArraysSupport.T_BYTE);
            a[8] = (byte) hash;
            checkpoint(trigger);
            if (a != alias || alias.length != 17) {
                throw new AssertionError("reconstructed array identity or length");
            }
            for (int i = 0; i < alias.length; i++) {
                byte expected = switch (i) {
                    case 1 -> 7;
                    case 2 -> -1;
                    case 7 -> 0;
                    case 8 -> (byte) hash;
                    default -> (byte) (value + i);
                };
                if (alias[i] != expected) {
                    throw new AssertionError("reconstructed paired input cell " + i);
                }
            }
            alias[1]++;
            if (a[1] != 8) {
                throw new AssertionError("reconstructed aliases diverged");
            }
            observed = a[8];
            return hash + a[0] + a[1] + a[8];
        }

        static void checkpoint(boolean trigger) {
            if (!trigger) {
                return;
            }
            int level = WB.getMethodCompilationLevel(TARGET, false);
            if (!WB.isMethodCompiled(TARGET, false) || level != 4) {
                throw new AssertionError("target must be compiled at level 4: " + level);
            }
            long before = collectionCount();
            WB.fullGC();
            Asserts.assertGT(collectionCount(), before, "full GC must execute with the target active");
            int marked = WB.deoptimizeMethod(TARGET, false);
            // WhiteBox counts its native frame; the suspended target is depth 2.
            boolean frame = WB.isFrameDeoptimized(2);
            if (marked != 1 || !frame) {
                throw new AssertionError("missing active-frame deoptimization: " + marked + "/" + frame);
            }
            events++;
            System.out.println("HASH-PAIRED-DEOPT:level=" + level + ":marked=" + marked
                    + ":frame=" + frame + ":gc=true");
        }

        private static long collectionCount() {
            return ManagementFactory.getGarbageCollectorMXBeans().stream()
                    .mapToLong(bean -> Math.max(0, bean.getCollectionCount())).sum();
        }

        public static void main(String[] args) throws Exception {
            int value = Integer.parseInt(args[0]);
            int seed = Integer.parseInt(args[1]);
            for (int i = 0; i < 2_000; i++) {
                target(i, ~i, false);
            }
            PEATestUtils.enqueueAndAwaitLevel4(TARGET);
            int hash = seed;
            for (int i = 0; i < 17; i++) {
                int cell = switch (i) {
                    case 1 -> 7;
                    case 2 -> -1;
                    case 7 -> 0;
                    case 8 -> 5;
                    default -> (byte) (value + i);
                };
                hash = 31 * hash + cell;
            }
            int actual = target(value, seed, true);
            Asserts.assertEquals(actual, hash + (byte) value + 8 + (byte) hash,
                    "return after active deoptimization");
            Asserts.assertEquals(observed, (int) (byte) hash, "stored narrow hash after active deoptimization");
            Asserts.assertEquals(events, 1, "one exact active-frame deoptimization");
            Asserts.assertFalse(WB.isMethodCompiled(TARGET, false), "the requested nmethod was invalidated");
            System.out.println("HASH-PAIRED-DEOPT-PASSED:" + value + ":" + seed);
        }
    }
}
