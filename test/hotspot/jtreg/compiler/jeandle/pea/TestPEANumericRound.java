/*
 * Copyright (c) 2026, the Jeandle-JDK Authors. All Rights Reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License version 2 for more details (a copy of the
 * LICENSE file that accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 */

/*
 * @test
 * @summary Pure round JavaOps preserve scalar replacement of primitive fields
 * @library /test/lib /
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler compiler.jeandle.pea.TestPEANumericRound
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;
import jdk.test.lib.Asserts;

public class TestPEANumericRound {
    public static void main(String[] args) throws Exception {
        Method method = Kernel.class.getMethod("roundField", double.class);
        try (var run = PEATestUtils.shapeRun(Kernel.class.getName(), method).run()) {
            var report = run.report(method);
            Asserts.assertTrue(report.round0Before().peaAllocCount() > 0);
            Asserts.assertEQ(report.finalAfter().peaAllocCount(), 0);
            report.round0Before().assertLineCount("@jeandle.round.double", 1);
            Asserts.assertEQ(run.finalIR(method).loweredAllocCount(), 0);
            run.finalIR(method).assertAbsent("@jeandle.round.double");
        }
        PEATestUtils.assertPEAOnOffEquivalent(Kernel.class.getName(), new Method[] {method});
    }

    public static class Kernel {
        static class Box { double value; }
        static double loadMath = Math.abs(1.0);
        public static long roundField(double x) {
            Box b = new Box();
            b.value = x;
            return Math.round(b.value);
        }
        public static void main(String[] args) throws Exception {
            new Box();
            Method method = Kernel.class.getMethod("roundField", double.class);
            PEATestUtils.enqueueAndAwaitLevel4(method);
            long sum = 0;
            for (int i = 0; i < 30000; i++) sum += roundField((i - 15000) * 0.25);
            PEATestUtils.confirmLevel4(method);
            System.out.println("PEA-RESULT:" + sum);
        }
    }
}
