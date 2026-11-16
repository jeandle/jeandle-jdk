/*
 * @test
 * @summary Loop-carried clone receivers retain their runtime class and overrides
 * @library /test/lib /
 * @build jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run driver TestCloneLoopKlass
 */

import java.util.List;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.ProcessTools;
import jdk.test.whitebox.WhiteBox;

public class TestCloneLoopKlass {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            for (String pea : List.of("+", "-")) {
                var output = ProcessTools.executeProcess(
                        ProcessTools.createLimitedTestJavaProcessBuilder(
                                "-Xbootclasspath/a:.", "-XX:+UnlockDiagnosticVMOptions", "-XX:+WhiteBoxAPI",
                                "-Xcomp", "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler",
                                "-XX:+CIPrintCompilerName", "-Xlog:jit+compilation=debug",
                                "-XX:" + pea + "JeandleDoPEA",
                                "-XX:CompileCommand=compileonly," + Base.class.getName() + "::loop",
                                "-XX:CompileCommand=compileonly," + Finalized.class.getName() + "::copy",
                                TestCloneLoopKlass.class.getName(), "child"));
                output.shouldHaveExitValue(0).shouldContain("LOOP_CLONE_PASS");
                output.shouldMatch("Jeandle:.*TestCloneLoopKlass\\$Base::loop");
                output.shouldMatch("Jeandle:.*TestCloneLoopKlass\\$Finalized::copy");
            }
            return;
        }

        Sub second = new Sub();
        Override overridden = new Override();
        // Resolve Object.clone before compiling the loop.
        Base.resolveClone();
        Object copy = Base.loop(second);
        Asserts.assertTrue(copy instanceof Sub, "the backedge receiver determines the clone class");
        Asserts.assertTrue(copy != second, "native clone has a fresh identity");
        Asserts.assertEQ(((Sub) copy).x, 19);
        Asserts.assertEQ(((Sub) copy).y, 37, "subclass payload survives the clone");
        Asserts.assertSame(Base.loop(overridden), overridden.marker, "backedge override result");
        Asserts.assertEQ(overridden.calls, 1, "override executes exactly once");
        Finalized finalizable = new Finalized();
        Finalized finalizableCopy = (Finalized) Finalized.copy(finalizable);
        Asserts.assertTrue(finalizableCopy != finalizable, "finalizable clone has a fresh identity");
        Asserts.assertEQ(finalizableCopy.value, 71, "finalizable clone payload");
        Asserts.assertSame(finalizableCopy.reference, finalizable.reference, "finalizable shallow clone");
        Asserts.assertTrue(WhiteBox.getWhiteBox().isMethodCompiled(
                Finalized.class.getDeclaredMethod("copy", Finalized.class)),
                "finalizable clone executed installed compiled code");
        Asserts.assertTrue(WhiteBox.getWhiteBox().isMethodCompiled(
                Base.class.getDeclaredMethod("loop", Base.class)), "loop executed installed compiled code");
        System.out.println("LOOP_CLONE_PASS");
    }

    public static class Base implements Cloneable {
        int x = 19;

        static void resolveClone() throws CloneNotSupportedException {
            new Base().clone();
        }

        public static Object loop(Base second) throws CloneNotSupportedException {
            Base current = new Base();
            Object copy = null;
            for (int i = 0; i < 2; i++) {
                copy = current.clone();
                current = second;
            }
            return copy;
        }
    }

    public static final class Sub extends Base {
        int y = 37;
    }

    public static final class Override extends Base {
        int calls;
        final Object marker = new Object();

        public Object clone() {
            calls++;
            return marker;
        }
    }

    @SuppressWarnings("removal")
    public static final class Finalized implements Cloneable {
        static int finalized;
        int value = 71;
        Object reference = new Object();

        static Object copy(Finalized source) throws CloneNotSupportedException {
            return source.clone();
        }

        protected void finalize() {
            finalized++;
        }
    }
}
