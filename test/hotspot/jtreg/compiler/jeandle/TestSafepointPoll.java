/**
 * @test
 * @library /test/lib
 * @requires os.arch=="x86_64"
 * @build jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *      -XX:CompileCommand=compileonly,TestSafepointPoll::test -Xcomp -XX:-TieredCompilation
 *      -XX:+UseJeandleCompiler -Xlog:thread*=trace::time,tags -XX:+JeandleDumpIR -XX:+JeandleDumpObjects -XX:+JeandleDumpRuntimeStubs TestSafepointPoll
 */

import java.lang.reflect.Method;

import jdk.test.whitebox.WhiteBox;

public class TestSafepointPoll {
    private final static WhiteBox wb = WhiteBox.getWhiteBox();
    static volatile boolean stop = false;

    public static void main(String[] args) throws Exception {
        new Thread(() -> {
            test();
        }).start();

        Method method = TestSafepointPoll.class.getDeclaredMethod("test");
        while (!wb.isMethodCompiled(method)) {
            Thread.yield();
        }

        Thread.sleep(100);

        wb.forceSafepoint();

        stop = true;
    }

    static void test() {
        while (!stop) {}
    }
}
