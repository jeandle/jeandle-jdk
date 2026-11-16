/*
 * @test
 * @summary Zero-length arraycopy must retain source bounds exceptions after intrinsic traps disable validation
 * @library /test/lib /
 * @build jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *      -Xbatch -XX:-TieredCompilation -XX:+UseJeandleCompiler
 *      -XX:CompileCommand=compileonly,compiler.jeandle.pea.TestPEACopyZeroLength::copyInvalid
 *      -XX:JeandleLLVMOptions=-jeandle-dump-pea-stats
 *      compiler.jeandle.pea.TestPEACopyZeroLength
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;

import jdk.test.lib.Asserts;
import jdk.test.whitebox.WhiteBox;

public class TestPEACopyZeroLength {
    private static int[] source = new int[3];

    public static int copyInvalid() {
        int[] dest = new int[4];
        try {
            System.arraycopy(source, 5, dest, 0, 0);
            return dest[0];
        } catch (ArrayIndexOutOfBoundsException expected) {
            return -1;
        }
    }

    public static void main(String[] args) throws Exception {
        Method target = TestPEACopyZeroLength.class.getMethod("copyInvalid");
        WhiteBox wb = WhiteBox.getWhiteBox();
        // Populate profiling data, including the failed intrinsic guard,
        // before explicitly compiling the conservative copy path.
        for (int i = 0; i < 30_000; i++) {
            Asserts.assertEQ(copyInvalid(), -1, "source position exceeds length even for an empty copy");
        }
        PEATestUtils.enqueueAndAwaitLevel4(target);
        for (int i = 0; i < 1_000; i++) {
            Asserts.assertEQ(copyInvalid(), -1, "unvalidated copy must throw");
        }
        Asserts.assertTrue(wb.isMethodCompiled(target), "conservative copy executes compiled code");
        Asserts.assertEQ(wb.getMethodCompilationLevel(target), 4, "Jeandle compilation level");
    }
}
