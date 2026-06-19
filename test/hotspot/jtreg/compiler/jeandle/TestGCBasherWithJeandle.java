/*
 * @test id=g1-deopt-nmethod
 * @key stress
 * @library /
 * @requires vm.flagless
 * @summary Stress G1 GC with nmethod barrier forced deoptimization under Jeandle compilation.
 *
 * @run main/othervm/timeout=200 -Xlog:gc*=info,nmethod+barrier=trace -Xmx1g
 *      -XX:+UnlockDiagnosticVMOptions
 *      -XX:+UseG1GC
 *      -XX:+UseJeandleCompiler
 *      -XX:+DeoptimizeNMethodBarriersALot -XX:-Inline
 *      -XX:+G1VerifyHeapRegionCodeRoots
 *      TestGCBasherWithJeandle 120000
 */

import java.io.IOException;

public class TestGCBasherWithJeandle {
    public static void main(String[] args) throws IOException {
        gc.stress.gcbasher.TestGCBasher.main(args);
    }
}
