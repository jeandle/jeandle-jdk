/*
 * @test
 * @summary PEA folds jeandle.copy (System.arraycopy / Arrays.copyOf[Range] /
 *          array / instance clone) into virtual field state: scalarization,
 *          escape replay, active-frame deopt reconstruction with copied
 *          entries, and exception-path conservatism are behavior-equivalent
 *          with PEA off
 * @library /test/lib /
 * @modules java.base/jdk.internal.misc
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.pea.TestPEACopyFold
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;
import java.util.Arrays;

import jdk.test.lib.Asserts;

public class TestPEACopyFold {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestPEACopyFold$TestWrapper";

    public static void main(String[] args) throws Exception {
        Method v2vInt = TestWrapper.class.getMethod("testArrayCopyIntV2V");
        Method v2vRef = TestWrapper.class.getMethod("testArrayCopyRefV2V");
        Method copyOf = TestWrapper.class.getMethod("testCopyOfString");
        Method copyOfRange = TestWrapper.class.getMethod("testCopyOfRangeString");
        Method overlap = TestWrapper.class.getMethod("testSelfOverlapInt");
        Method escape = TestWrapper.class.getMethod("testEscapeReplayInt");
        Method deoptV2V = TestWrapper.class.getMethod("testDeoptIntV2V");
        Method deoptConcrete = TestWrapper.class.getMethod("testDeoptRefConcrete");
        Method cloneRef = TestWrapper.class.getMethod("testCloneRefV2V");
        Method cloneInt = TestWrapper.class.getMethod("testCloneIntArray");
        Method cloneInstance = TestWrapper.class.getMethod("testCloneInstance");
        Method exceptions = TestWrapper.class.getMethod("testExceptions");
        Method largeCopy = TestWrapper.class.getMethod("testLargeVirtualCopy");
        Method largeOverlap = TestWrapper.class.getMethod("testLargeVirtualOverlap");
        Method largeClone = TestWrapper.class.getMethod("testLargeVirtualClone");
        Method largeEscape = TestWrapper.class.getMethod("testLargeVirtualEscape");
        Method denseEscape = TestWrapper.class.getMethod("testDenseReferenceEscape");
        Method requestDeopt = TestWrapper.class.getDeclaredMethod("requestDeopt");
        Method[] targets = {v2vInt, v2vRef, copyOf, copyOfRange, overlap,
                            escape, deoptV2V, deoptConcrete, cloneRef, cloneInt,
                            cloneInstance, exceptions, largeCopy, largeOverlap, largeClone,
                            largeEscape, denseEscape};

        PEATestUtils.behaviorRun(WRAPPER, targets)
                .dontinline(requestDeopt)
                .runPEAOnOffEquivalent();

        // Each copy kind must fold through the real frontend and execute
        // with the same configuration as the behavior comparison.
        try (PEATestUtils.RunResult run = PEATestUtils.shapeRun(WRAPPER, v2vInt, cloneRef, cloneInt, deoptConcrete,
                                                              copyOf, copyOfRange, cloneInstance,
                                                              largeCopy, largeOverlap, largeClone,
                                                              largeEscape, denseEscape)
                .dontinline(requestDeopt)
                .run()) {
            assertFold(run, v2vInt, 2);
            assertFold(run, cloneRef, 2);
            assertFold(run, cloneInt, 2);
            assertFold(run, deoptConcrete, 1);
            assertFold(run, copyOf, 2);
            assertFold(run, copyOfRange, 2);
            assertFold(run, largeCopy, 2);
            assertFold(run, largeOverlap, 1);
            assertFold(run, largeClone, 2);
            assertFold(run, largeEscape, 1);
            Asserts.assertEquals(run.report(denseEscape).round(0).copyFolds(), 0,
                    "dense reference replay keeps bulk copy");
            run.report(denseEscape).round(0).after().assertPresent("@jeandle.copy");
            // The clone fast path folds, then joins the native slow path.
            // Both identities remain real across that join, exercising replay.
            assertFold(run, cloneInstance, 0);
            Asserts.assertEquals(run.report(cloneInstance).round(0).partiallyEscapes(), 2,
                    "instance clone source and destination replay at the slow-path join");
        }
    }

    private static void assertFold(PEATestUtils.RunResult run, Method target,
                                   int neverEscapes) throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        Asserts.assertTrue(report.round(0).hasStats(),
                target + ": stats present");
        Asserts.assertEquals(report.round(0).copyFolds(), 1,
                target + ": exactly one jeandle.copy folded");
        Asserts.assertEquals(report.round(0).neverEscapes(), neverEscapes,
                target + ": objects that never escape");
        Asserts.assertTrue(report.effects("ReplaceCall").stream()
                        .anyMatch(e -> e.detail().contains("jeandle.copy")),
                target + ": ReplaceCall effect on the pseudo call");
        run.finalIR(target).assertAbsent("@jeandle.copy");
    }

    public static class TestWrapper implements Cloneable {
        private static final String[] SRC_STRINGS = {"aa", "bb", "cc", "dd"};
        private static final int[] SRC_INTS = {10, 20, 30, 40, 50, 60};
        private static int[] sinkInt;
        private static String[] sinkRef;

        private static Method deoptTarget;
        private int x;
        private int y;
        private Object ref;

        public static void main(String[] args) throws Exception {
            // Resolve the intrinsic holders and their class constants before
            // the explicit target compilation.
            Arrays.copyOf(SRC_STRINGS, 4, String[].class);
            Arrays.copyOfRange(SRC_STRINGS, 1, 3, String[].class);
            new TestWrapper().clone();
            PEATestUtils.compileConfiguredTargetsAtLevel4();

            long payload = 0x6A09E667F3BCC909L;
            // A shape run configures only a subset of the targets; run
            // exactly the configured ones so every workload is compiled
            // before its deopt request.
            if (configured("testArrayCopyIntV2V")) {
                payload = mix(payload, testArrayCopyIntV2V());
            }
            if (configured("testArrayCopyRefV2V")) {
                payload = mix(payload, testArrayCopyRefV2V());
            }
            if (configured("testCopyOfString")) {
                deoptTarget = TestWrapper.class.getMethod("testCopyOfString");
                payload = mix(payload, testCopyOfString());
            }
            if (configured("testCopyOfRangeString")) {
                deoptTarget = TestWrapper.class.getMethod("testCopyOfRangeString");
                payload = mix(payload, testCopyOfRangeString());
            }
            if (configured("testSelfOverlapInt")) {
                payload = mix(payload, testSelfOverlapInt());
            }
            if (configured("testEscapeReplayInt")) {
                payload = mix(payload, testEscapeReplayInt());
            }
            if (configured("testDeoptIntV2V")) {
                deoptTarget = TestWrapper.class.getMethod("testDeoptIntV2V");
                payload = mix(payload, testDeoptIntV2V());
            }
            if (configured("testDeoptRefConcrete")) {
                deoptTarget = TestWrapper.class.getMethod("testDeoptRefConcrete");
                payload = mix(payload, testDeoptRefConcrete());
            }
            if (configured("testCloneRefV2V")) {
                payload = mix(payload, testCloneRefV2V());
            }
            if (configured("testCloneIntArray")) {
                payload = mix(payload, testCloneIntArray());
            }
            if (configured("testCloneInstance")) {
                deoptTarget = TestWrapper.class.getMethod("testCloneInstance");
                payload = mix(payload, testCloneInstance());
            }
            if (configured("testExceptions")) {
                payload = mix(payload, testExceptions());
            }
            if (configured("testLargeVirtualCopy")) {
                deoptTarget = TestWrapper.class.getMethod("testLargeVirtualCopy");
                payload = mix(payload, testLargeVirtualCopy());
            }
            if (configured("testLargeVirtualOverlap")) {
                payload = mix(payload, testLargeVirtualOverlap());
            }
            if (configured("testLargeVirtualClone")) {
                payload = mix(payload, testLargeVirtualClone());
            }
            if (configured("testLargeVirtualEscape")) {
                payload = mix(payload, testLargeVirtualEscape());
            }
            if (configured("testDenseReferenceEscape")) {
                payload = mix(payload, testDenseReferenceEscape());
            }

            System.out.println("PEA-RESULT:" + Long.toUnsignedString(payload, 16));
        }

        private static boolean configured(String methodName) {
            return System.getProperty(
                    "compiler.jeandle.pea.configuredTargets", "").contains(methodName);
        }

        // Virtual-to-virtual System.arraycopy over a primitive array, with an
        // interior range so untouched default slots are also observed.
        public static long testArrayCopyIntV2V() {
            int[] src = new int[8];
            for (int i = 0; i < 8; i++) {
                src[i] = (i + 1) * 11;
            }
            int[] dst = new int[8];
            System.arraycopy(src, 1, dst, 2, 5);
            long sum = 0;
            for (int i = 0; i < 8; i++) {
                sum = sum * 31 + dst[i];
            }
            return sum;
        }

        // Virtual-to-virtual System.arraycopy over a reference array; the
        // transferred entries keep element identity.
        public static long testArrayCopyRefV2V() {
            String[] src = {"aa", "bb", "cc", "dd"};
            String[] dst = new String[4];
            System.arraycopy(src, 0, dst, 0, 4);
            long sum = 0;
            for (int i = 0; i < 4; i++) {
                if (dst[i] != SRC_STRINGS[i] || dst[i].length() != 2) {
                    return Long.MIN_VALUE + 1;
                }
                sum = sum * 31 + dst[i].charAt(0);
            }
            return sum;
        }

        // Reference-array clone. Under the default flags the frontend emits
        // the byte-payload clone-array kind, which folds by element
        // transfer over the payload window.
        public static long testCloneRefV2V() {
            String[] src = {"aa", "bb", "cc", "dd"};
            String[] c = src.clone();
            long sum = 0;
            for (int i = 0; i < 4; i++) {
                if (c[i] != SRC_STRINGS[i] || c.length != 4) {
                    return Long.MIN_VALUE + 10;
                }
                sum = sum * 31 + c[i].charAt(0);
            }
            return sum;
        }

        // Primitive-array clone over a fresh source: the byte-payload
        // clone-array kind folds by element transfer.
        public static long testCloneIntArray() {
            int[] src = new int[6];
            for (int i = 0; i < 6; i++) {
                src[i] = (i + 1) * 7;
            }
            int[] c = src.clone();
            long sum = 0;
            for (int i = 0; i < 6; i++) {
                if (c[i] != (i + 1) * 7 || c.length != 6) {
                    return Long.MIN_VALUE + 11;
                }
                sum = sum * 31 + c[i];
            }
            return sum;
        }

        // Instance clone over a fresh source, including a shared reference.
        public static long testCloneInstance() throws CloneNotSupportedException {
            TestWrapper p = new TestWrapper();
            p.x = 13;
            p.y = 29;
            p.ref = SRC_STRINGS;
            // The inherited protected native method is accessible here,
            // exposing a fresh receiver directly to the clone intrinsic.
            TestWrapper q = (TestWrapper) p.clone();
            requestDeopt();
            if (q == p || q.x != 13 || q.y != 29 || q.ref != p.ref || q.ref != SRC_STRINGS) {
                throw new AssertionError("clone fields and identity after deopt");
            }
            long result = q.x * 31 + q.y;
            q.x = 41;
            if (p.x != 13) {
                throw new AssertionError("clone has independent storage");
            }
            return result;
        }

        // Arrays.copyOf over a VIRTUAL source: the source's arraylength is
        // a compile-time constant, so the intrinsic's count expression
        // min(length, srcLength - start) folds and the copyof kind folds by
        // entry transfer. A concrete source of
        // unknown length keeps the select and stays conservative (matching
        // Graal's constant-length requirement).
        public static long testCopyOfString() {
            String[] src = {"aa", "bb", "cc", "dd"};
            String[] copy = Arrays.copyOf(src, 6, String[].class);
            requestDeopt();
            if (copy == src || copy.length != 6 || copy[4] != null || copy[5] != null) {
                throw new AssertionError("copyOf identity, length and padding after deopt");
            }
            long sum = 0;
            for (int i = 0; i < 4; i++) {
                if (copy[i] != SRC_STRINGS[i]) {
                    throw new AssertionError("copyOf element identity after deopt");
                }
                sum = sum * 31 + copy[i].charAt(1);
            }
            return sum;
        }

        public static long testCopyOfRangeString() {
            String[] src = {"aa", "bb", "cc", "dd"};
            String[] copy = Arrays.copyOfRange(src, 1, 3, String[].class);
            requestDeopt();
            if (copy == src || copy.length != 2 || copy[0] != SRC_STRINGS[1]
                    || copy[1] != SRC_STRINGS[2] || copy[0].length() != 2) {
                throw new AssertionError("copyOfRange values and identity after deopt");
            }
            return copy[0].charAt(0) * 31 + copy[1].charAt(0);
        }

        // Overlapping same-array copy toward higher indices (reverse-order
        // transfer).
        public static long testSelfOverlapInt() {
            int[] a = {1, 2, 3, 4, 5, 6};
            System.arraycopy(a, 0, a, 1, 4);
            long sum = 0;
            for (int i = 0; i < 6; i++) {
                sum = sum * 31 + a[i];
            }
            return sum;
        }

        // The destination escapes AFTER the copy: the copy still folds, the
        // array materializes at the publication, and the published object
        // carries the copied values under a stable identity.
        public static long testEscapeReplayInt() {
            int[] dst = new int[6];
            System.arraycopy(SRC_INTS, 0, dst, 0, 6);
            sinkInt = dst;
            if (sinkInt != dst) {
                return Long.MIN_VALUE + 4;
            }
            long sum = 0;
            for (int i = 0; i < 6; i++) {
                if (sinkInt[i] != SRC_INTS[i]) {
                    return Long.MIN_VALUE + 5;
                }
                sum = sum * 31 + sinkInt[i];
            }
            return sum;
        }

        // Active-frame deopt after a virtual-to-virtual copy: the deopt pool
        // must reconstruct the destination array with the TRANSFERRED entries.
        public static long testDeoptIntV2V() {
            int[] src = new int[6];
            for (int i = 0; i < 6; i++) {
                src[i] = 1000 + i;
            }
            int[] dst = new int[6];
            System.arraycopy(src, 0, dst, 0, 6);

            requestDeopt();

            if (dst.length != 6) {
                return Long.MIN_VALUE + 6;
            }
            long sum = 0;
            for (int i = 0; i < 6; i++) {
                if (dst[i] != 1000 + i) {
                    return Long.MIN_VALUE + 7;
                }
                sum = sum * 31 + dst[i];
            }
            return sum;
        }

        // Active-frame deopt after a concrete-source copy: the deopt pool
        // reconstructs the destination from the element-load entries, and the
        // reconstructed elements keep identity with the source's elements.
        public static long testDeoptRefConcrete() {
            String[] dst = new String[4];
            System.arraycopy(SRC_STRINGS, 0, dst, 0, 4);

            requestDeopt();

            if (dst.length != 4) {
                return Long.MIN_VALUE + 8;
            }
            long sum = 0;
            for (int i = 0; i < 4; i++) {
                if (dst[i] != SRC_STRINGS[i]) {
                    return Long.MIN_VALUE + 9;
                }
                sum = sum * 31 + dst[i].charAt(0);
            }
            return sum;
        }

        public static long testLargeVirtualCopy() {
            int[] src = new int[16];
            src[0] = 19;
            src[15] = 31;
            int[] dst = new int[16];
            dst[7] = 99;
            System.arraycopy(src, 0, dst, 0, 16);
            requestDeopt();
            for (int i = 0; i < 16; i++) {
                Asserts.assertEQ(dst[i], i == 0 ? 19 : i == 15 ? 31 : 0,
                        "large copy entries survive active-frame deoptimization");
            }
            return dst[0] + dst[7] + dst[15];
        }

        public static long testLargeVirtualOverlap() {
            int[] array = new int[17];
            array[0] = 19;
            array[15] = 31;
            array[16] = 99;
            System.arraycopy(array, 0, array, 1, 16);
            Asserts.assertEQ(array[1], 19, "overlap first entry");
            Asserts.assertEQ(array[15], 0, "overlap default entry");
            Asserts.assertEQ(array[16], 31, "overlap last entry");
            return array[0] + array[1] + array[15] + array[16];
        }

        public static long testLargeVirtualClone() {
            int[] src = new int[16];
            src[0] = 19;
            src[15] = 31;
            int[] dst = src.clone();
            Asserts.assertEQ(dst[0], 19, "clone first entry");
            Asserts.assertEQ(dst[7], 0, "clone default entry");
            Asserts.assertEQ(dst[15], 31, "clone last entry");
            return dst[0] + dst[7] + dst[15];
        }

        public static long testLargeVirtualEscape() {
            int[] src = new int[16];
            src[0] = 19;
            src[15] = 31;
            int[] dst = new int[16];
            dst[7] = 99;
            System.arraycopy(src, 0, dst, 0, 16);
            sinkInt = dst;
            Asserts.assertTrue(sinkInt == dst, "published destination identity");
            for (int i = 0; i < 16; i++) {
                Asserts.assertEQ(sinkInt[i], i == 0 ? 19 : i == 15 ? 31 : 0,
                        "large copy replay preserves entries and defaults");
            }
            return sinkInt[0] + sinkInt[7] + sinkInt[15];
        }

        public static long testDenseReferenceEscape() {
            String[] src = {SRC_STRINGS[0], SRC_STRINGS[1], SRC_STRINGS[2], SRC_STRINGS[3],
                            SRC_STRINGS[0], SRC_STRINGS[1], SRC_STRINGS[2], SRC_STRINGS[3],
                            SRC_STRINGS[0], SRC_STRINGS[1], SRC_STRINGS[2], SRC_STRINGS[3],
                            SRC_STRINGS[0], SRC_STRINGS[1], SRC_STRINGS[2], SRC_STRINGS[3]};
            String[] dst = src.clone();
            sinkRef = dst;
            Asserts.assertTrue(sinkRef == dst, "dense destination identity");
            Asserts.assertTrue(dst != src, "dense clone has independent identity");
            for (int i = 0; i < 16; i++) {
                Asserts.assertTrue(sinkRef[i] == SRC_STRINGS[i & 3],
                        "dense bulk copy preserves every reference");
            }
            return sinkRef.length;
        }

        // The exception shapes must survive the optimization: null, bounds,
        // and store-check failures are observed identically.
        public static long testExceptions() {
            long caught = 0;
            try {
                System.arraycopy(null, 0, new int[1], 0, 1);
            } catch (NullPointerException e) {
                caught += 1;
            }
            try {
                System.arraycopy(new int[4], 2, new int[4], 0, 3);
            } catch (ArrayIndexOutOfBoundsException e) {
                caught += 10;
            }
            try {
                System.arraycopy(new Integer[]{1}, 0, new String[1], 0, 1);
            } catch (ArrayStoreException e) {
                caught += 100;
            }
            try {
                System.arraycopy(new int[2], 0, new int[2], 0, -1);
            } catch (ArrayIndexOutOfBoundsException e) {
                caught += 1000;
            }
            return caught;
        }

        private static void requestDeopt() {
            PEATestUtils.ActiveFrameDeoptEvidence evidence =
                    PEATestUtils.deoptimizeActiveFrame(deoptTarget, 2);
            if (!evidence.frameDeoptimized()
                    || evidence.compilationLevel() != 4
                    || evidence.markedNMethods() != 1) {
                throw new AssertionError("exact active-frame deopt evidence");
            }
        }

        private static long mix(long accumulator, long value) {
            return Long.rotateLeft(accumulator ^ value, 13)
                    * 0x9E3779B97F4A7C15L;
        }
    }
}
