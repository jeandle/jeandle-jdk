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
 * @summary Hash results in virtual arrays survive active-frame deoptimization
 * @requires (vm.cpu.features ~= ".*avx2.*") | (vm.simpleArch == "aarch64")
 * @library /test/lib /
 * @modules java.base/jdk.internal.misc
 * @build jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler compiler.jeandle.intrinsic.TestVectorizedHashCodeDeopt
 */

package compiler.jeandle.intrinsic;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import compiler.jeandle.pea.PEATestUtils;
import jdk.internal.misc.Unsafe;
import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;
import jdk.test.whitebox.WhiteBox;

public class TestVectorizedHashCodeDeopt {
    public static void main(String[] args) throws Exception {
        Method target = Child.class.getDeclaredMethod("target", int.class, boolean.class);
        Method checkpoint = Child.class.getDeclaredMethod("checkpoint", boolean.class);
        String root = PEATestUtils.MethodId.rootOf(target).llvmFunctionName();
        for (int value : new int[]{Integer.MIN_VALUE, -1, 0, 31, Integer.MAX_VALUE}) {
            Path dumps = Files.createTempDirectory(Path.of("."), "hash-deopt-");
            Path log = dumps.resolve("compilation.xml");
            OutputAnalyzer output = ProcessTools.executeCommand(
                    ProcessTools.createLimitedTestJavaProcessBuilder(
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
                            Child.class.getName(), Integer.toString(value)));
            output.shouldHaveExitValue(0)
                    .shouldContain("HASH-DEOPT:level=4:marked=1:frame=true")
                    .shouldContain("HASH-DEOPT-PASSED:" + value)
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
        PEATestUtils.IRBody lowered = PEATestUtils.finalIR(dumps, id);
        Asserts.assertEquals(lowered.loweredAllocCount(), 0, "final code does not allocate");
        lowered.assertAbsent("%tlab_");
        PEATestUtils.DeoptBundle bundle = after.deoptBundleAtCall(
                PEATestUtils.MethodId.of(checkpoint).llvmFunctionName(), 0);
        bundle.assertVirtualObjectIds(0);
        Asserts.assertEquals(bundle.scopes().size(), 1, "one exact active root scope");
        PEATestUtils.VirtualObjectDescriptor array = bundle.virtualObject(0);
        Asserts.assertEquals(array.kind(), PEATestUtils.DescriptorKind.ARRAY, "virtual array descriptor");
        int base = Unsafe.ARRAY_INT_BASE_OFFSET;
        Asserts.assertEquals(array.elements().size(), 4, "all four initialized cells are described");
        PEATestUtils.DeoptValue hash = bundle.rootScope().locals().get(4);
        Asserts.assertNotNull(hash, "live scalar hash local");
        Asserts.assertEquals(hash.kind(), PEATestUtils.DeoptValueKind.SCALAR, "hash is scalar state");
        Asserts.assertEquals(hash.basicType(), PEATestUtils.DeoptBasicType.INT, "hash wraps as int");
        Asserts.assertTrue(hash.operand().startsWith("i32 %"), "hash depends on the runtime input");
        Asserts.assertEquals(array.elements().get(base + 2 * Unsafe.ARRAY_INT_INDEX_SCALE).value(), hash,
                "virtual cell and scalar local refer to the identical hash result");
        for (int local : new int[]{2, 3}) {
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
                TARGET = Child.class.getDeclaredMethod("target", int.class, boolean.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        static int target(int value, boolean trigger) {
            int[] a = {value, 7, 0, -1};
            int[] alias = a;
            int hash = Arrays.hashCode(a);
            a[2] = hash;
            checkpoint(trigger);
            if (a != alias || alias.length != 4 || alias[0] != value
                    || alias[1] != 7 || alias[2] != hash || alias[3] != -1) {
                throw new AssertionError("reconstructed array identity or cells");
            }
            alias[1]++;
            if (a[1] != 8) {
                throw new AssertionError("reconstructed aliases diverged");
            }
            observed = a[2];
            return a[0] + a[1] + a[2] + a[3] + hash;
        }

        static void checkpoint(boolean trigger) {
            if (!trigger) {
                return;
            }
            int level = WB.getMethodCompilationLevel(TARGET, false);
            if (!WB.isMethodCompiled(TARGET, false) || level != 4) {
                throw new AssertionError("target must be compiled at level 4: " + level);
            }
            System.gc();
            int marked = WB.deoptimizeMethod(TARGET, false);
            // WhiteBox counts its native frame; the suspended target is depth 2.
            boolean frame = WB.isFrameDeoptimized(2);
            if (marked != 1 || !frame) {
                throw new AssertionError("missing active-frame deoptimization: " + marked + "/" + frame);
            }
            events++;
            System.out.println("HASH-DEOPT:level=" + level + ":marked=" + marked + ":frame=" + frame);
        }

        public static void main(String[] args) throws Exception {
            int value = Integer.parseInt(args[0]);
            for (int i = 0; i < 2_000; i++) {
                target(i, false);
            }
            PEATestUtils.enqueueAndAwaitLevel4(TARGET);
            int hash = 1;
            for (int cell : new int[]{value, 7, 0, -1}) {
                hash = 31 * hash + cell;
            }
            int actual = target(value, true);
            Asserts.assertEquals(actual, value + 7 + hash + hash, "return after active deoptimization");
            Asserts.assertEquals(observed, hash, "stored hash after active deoptimization");
            Asserts.assertEquals(events, 1, "one exact active-frame deoptimization");
            Asserts.assertFalse(WB.isMethodCompiled(TARGET, false), "the requested nmethod was invalidated");
            System.out.println("HASH-DEOPT-PASSED:" + value);
        }
    }
}
