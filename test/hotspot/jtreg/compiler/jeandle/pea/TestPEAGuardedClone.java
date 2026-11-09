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
 * @summary Concrete clones remain virtual after a guard refines a superclass receiver
 * @library /test/lib /
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler compiler.jeandle.pea.TestPEAGuardedClone
 * @run main/othervm -XX:-UseJeandleCompiler -XX:-UseCompressedOops
 *      -XX:-UseCompressedClassPointers compiler.jeandle.pea.TestPEAGuardedClone
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;

import jdk.test.lib.Asserts;
import jdk.test.whitebox.WhiteBox;

public class TestPEAGuardedClone {
    public static void main(String[] args) throws Exception {
        Method guarded = Base.class.getMethod("guarded", Base.class);
        Method exact = Base.class.getMethod("exact", Sub.class);
        Method[] targets = {guarded, exact};
        String wrapper = Base.class.getName();
        PEATestUtils.behaviorRun(wrapper, targets).runPEAOnOffEquivalent();
        try (PEATestUtils.RunResult run = PEATestUtils.shapeRun(wrapper, targets).run()) {
            for (Method target : targets) {
                run.report(target).round(0).before().assertPresent("@jeandle.copy");
                Asserts.assertEquals(run.report(target).round(0).copyFolds(), 1,
                        target + ": the concrete clone's field copy must fold");
                Asserts.assertEquals(run.report(target).round(0).neverEscapes(), 1,
                        target + ": the clone must stay virtual");
                run.finalIR(target).assertAbsent("@jeandle.new_instance");
                run.finalIR(target).assertAbsent("@jeandle.copy");
            }
        }
    }

    public static class Base implements Cloneable {
        int value;

        Base(int value) {
            this.value = value;
        }

        public static int guarded(Base source) throws CloneNotSupportedException {
            if (!(source instanceof Sub)) {
                return -1;
            }
            Base copy = (Base) source.clone();
            return copy.value;
        }

        public static int exact(Sub source) throws CloneNotSupportedException {
            Base copy = (Base) source.clone();
            return copy.value;
        }

        public static void main(String[] args) throws Exception {
            Sub source = new Sub(37);
            Base.exact(source);
            PEATestUtils.compileConfiguredTargetsAtLevel4();
            Asserts.assertEquals(Base.guarded(source), 37, "guarded clone field");
            Asserts.assertEquals(Base.exact(source), 37, "exact clone field");
            WhiteBox wb = WhiteBox.getWhiteBox();
            Asserts.assertEquals(wb.getMethodCompilationLevel(
                    Base.class.getMethod("guarded", Base.class)), 4);
            Asserts.assertEquals(wb.getMethodCompilationLevel(
                    Base.class.getMethod("exact", Sub.class)), 4);
            Asserts.assertEquals(Base.guarded(new Base(11)), -1, "failed type guard");
            Asserts.assertEquals(Base.guarded(null), -1, "null fails the guard");
            System.out.println("PEA-RESULT:guarded-clone:37:37:-1:-1");
        }
    }

    public static final class Sub extends Base {
        Sub(int value) {
            super(value);
        }
    }
}
