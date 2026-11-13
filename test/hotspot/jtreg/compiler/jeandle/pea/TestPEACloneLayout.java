/*
 * @test
 * @summary Concrete instance clone field-state folding is independent of initial card-mark reduction
 * @library /test/lib /
 * @modules java.base/jdk.internal.misc
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler compiler.jeandle.pea.TestPEACloneLayout
 * @run main/othervm -XX:-UseJeandleCompiler -XX:-UseCompressedOops
 *      -XX:-UseCompressedClassPointers compiler.jeandle.pea.TestPEACloneLayout
 */

package compiler.jeandle.pea;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;

import jdk.test.lib.Asserts;
import jdk.test.whitebox.WhiteBox;

public class TestPEACloneLayout {
    private static final String WRAPPER = TestWrapper.class.getName();

    public static void main(String[] args) throws Exception {
        Method noEscape = TestWrapper.class.getMethod("testNoEscape");
        Method replay = TestWrapper.class.getMethod("testReplay");
        Method deopt = TestWrapper.class.getMethod("testDeopt");
        Method publish = TestWrapper.class.getDeclaredMethod("publish", TestWrapper.class);
        Method requestDeopt = TestWrapper.class.getDeclaredMethod("requestDeopt");
        Method[] targets = {noEscape, replay, deopt};

        for (String collector : new String[]{"-XX:+UseG1GC", "-XX:+UseSerialGC"}) {
            String[] flags = {collector, "-XX:-ReduceInitialCardMarks",
                    "-Xms64m", "-Xmx64m", "-XX:+VerifyBeforeGC", "-XX:+VerifyAfterGC",
                    "-Xlog:gc=info"};
            PEATestUtils.behaviorRun(WRAPPER, targets)
                    .extraFlags(flags).dontinline(publish).dontinline(requestDeopt)
                    .runPEAOnOffEquivalent();

            try (PEATestUtils.RunResult run = PEATestUtils.shapeRun(WRAPPER, targets)
                    .extraFlags(flags).dontinline(publish).dontinline(requestDeopt).run()) {
                for (Method target : targets) {
                    Asserts.assertEquals(run.report(target).round(0).copyFolds(), 1,
                            target + ": semantic clone field copy must fold with card marks enabled");
                    run.report(target).round(0).before().assertPresent("@jeandle.copy");
                    run.finalIR(target).assertAbsent("@jeandle.copy");
                }
                Asserts.assertEquals(run.report(noEscape).round(0).neverEscapes(), 1,
                        "the unread identity of a concrete-source clone is removable");
                Asserts.assertEquals(run.report(replay).round(0).partiallyEscapes(), 1,
                        "the published clone replays its copied fields");
                run.report(replay).round(0).after().assertPresent(PEATestUtils.referenceStore());
                Asserts.assertEquals(run.report(deopt).round(0).neverEscapes(), 1,
                        "deoptimization reconstructs the virtual clone");
            }
        }
    }

    public static final class TestWrapper implements Cloneable {
        private static final TestWrapper SOURCE = new TestWrapper();
        private static TestWrapper published;
        private static Method deoptTarget;
        private int x = 19;
        private long wide = 0x123456789abcdef0L;
        private Object ref;

        public static void main(String[] args) throws Exception {
            SOURCE.ref = new Marker();
            SOURCE.clone();
            deoptTarget = TestWrapper.class.getMethod("testDeopt");
            PEATestUtils.compileConfiguredTargetsAtLevel4();

            Asserts.assertEquals(testNoEscape(), 20, "non-escaping clone fields");
            WeakReference<Object> weak = prepareReplay();
            SOURCE.ref = null;
            WhiteBox wb = WhiteBox.getWhiteBox();
            for (int iteration = 0; iteration < 4; iteration++) {
                wb.youngGC();
            }
            Asserts.assertNotNull(weak.get(), "published clone keeps its referent alive");
            Asserts.assertSame(published.ref, weak.get(), "replay preserves reference identity after GC");
            Asserts.assertEquals(((Marker) published.ref).value, 0x5a17c0de,
                    "replayed reference contents survive GC");
            Asserts.assertEquals(published.wide, 0x123456789abcdef0L, "replayed wide field");
            SOURCE.ref = published.ref;
            Asserts.assertEquals(testDeopt(), 24, "reconstructed clone fields");
            System.out.println("PEA-RESULT:clone-layout:20:24:reference-and-wide-fields");
        }

        private static WeakReference<Object> prepareReplay() throws Exception {
            Asserts.assertEquals(testReplay(), 20, "escaping clone fields");
            Asserts.assertTrue(published != SOURCE, "clone has independent identity");
            return new WeakReference<>(published.ref);
        }

        public static int testNoEscape() throws CloneNotSupportedException {
            TestWrapper copy = (TestWrapper) SOURCE.clone();
            return copy.x + (copy.ref == SOURCE.ref && copy.wide == SOURCE.wide ? 1 : 0);
        }

        public static int testReplay() throws CloneNotSupportedException {
            TestWrapper copy = (TestWrapper) SOURCE.clone();
            publish(copy);
            return copy.x + (copy.ref == SOURCE.ref && copy.wide == SOURCE.wide ? 1 : 0);
        }

        public static int testDeopt() throws CloneNotSupportedException {
            TestWrapper copy = (TestWrapper) SOURCE.clone();
            requestDeopt();
            Asserts.assertTrue(copy != SOURCE, "deopt preserves independent clone identity");
            Asserts.assertSame(copy.ref, SOURCE.ref, "deopt preserves the copied reference");
            Asserts.assertEquals(copy.wide, 0x123456789abcdef0L, "deopt preserves the wide field");
            copy.x = 23;
            Asserts.assertEquals(SOURCE.x, 19, "clone update does not change its source");
            return copy.x + (copy.ref == SOURCE.ref ? 1 : 0);
        }

        private static void publish(TestWrapper copy) {
            published = copy;
        }

        private static void requestDeopt() {
            PEATestUtils.ActiveFrameDeoptEvidence evidence =
                    PEATestUtils.deoptimizeActiveFrame(deoptTarget, 2);
            Asserts.assertTrue(evidence.frameDeoptimized(), "active clone frame was deoptimized");
            System.out.println("CLONE-LAYOUT-DEOPT:level=" + evidence.compilationLevel()
                    + ":marked=" + evidence.markedNMethods()
                    + ":frame=" + evidence.frameDeoptimized());
        }
    }

    private static final class Marker {
        final int value = 0x5a17c0de;
    }
}
