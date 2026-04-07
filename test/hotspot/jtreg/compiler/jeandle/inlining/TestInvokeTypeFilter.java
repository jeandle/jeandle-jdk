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
 *
 */

/**
 * @test TestInvokeTypeFilter.java
 * @summary Verify that Jeandle inlining only applies to statically-bound calls
 *          (invokestatic, invokespecial, statically-bound invokevirtual),
 *          and does NOT inline virtual dispatch (invokevirtual non-bound)
 *          or interface dispatch (invokeinterface).
 * @library /test/lib /
 * @build compiler.jeandle.fileCheck.FileCheck
 * @build jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 *
 * @run main/othervm -XX:-TieredCompilation -Xcomp
 *      -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *      -XX:CompileCommand=compileonly,compiler.jeandle.inlining.TestInvokeTypeFilter::testMethod
 *      -XX:+UseJeandleCompiler -XX:+JeandleInline -XX:+JeandleDumpIR
 *      compiler.jeandle.inlining.TestInvokeTypeFilter
 */

package compiler.jeandle.inlining;

import jdk.test.lib.Asserts;
import compiler.jeandle.fileCheck.FileCheck;

public class TestInvokeTypeFilter {

    // ---- invoke targets ----

    // invokestatic target
    static int staticAdd(int a, int b) {
        return a + b;
    }

    // invokespecial target (private instance method)
    private int specialAdd(int a, int b) {
        return a + b;
    }

    // invokevirtual (statically bound) — final method in final class
    static final class FinalAdder {
        final int finalAdd(int a, int b) {
            return a + b;
        }
    }

    // invokevirtual (virtual dispatch) — non-final method, non-final class
    static class VirtualBase {
        int virtualAdd(int a, int b) {
            return a + b;
        }
    }

    static class VirtualSub extends VirtualBase {
        @Override
        int virtualAdd(int a, int b) {
            return a * b;
        }
    }

    // invokeinterface target — default method so it's concrete (passes pass_initial_checks),
    // but still dispatched via invokeinterface and should NOT be inlined.
    interface IAdder {
        default int interfaceAdd(int a, int b) {
            return a + b;
        }
    }

    static class AdderImpl implements IAdder {
        // inherits default interfaceAdd
    }

    static volatile int va = 3;
    static volatile int vb = 5;

    /**
     * Test method exercising all invoke types.
     * Only this method is compiled by Jeandle; callees are inlined (or not) at bytecode level.
     */
    static int testMethod() {
        int a = va;
        int b = vb;

        // 1. invokestatic — should be inlined
        int r1 = staticAdd(a, b);

        // 2. invokespecial (private instance method) — should be inlined
        TestInvokeTypeFilter self = new TestInvokeTypeFilter();
        int r2 = self.specialAdd(a, b);

        // 3. invokevirtual on final class/method — statically bound, should be inlined
        FinalAdder fa = new FinalAdder();
        int r3 = fa.finalAdd(a, b);

        // 4. invokevirtual (virtual dispatch) — NOT statically bound, should NOT be inlined
        VirtualBase vobj = new VirtualSub();
        int r4 = vobj.virtualAdd(a, b);

        // 5. invokeinterface (default method) — should NOT be inlined
        IAdder iadder = new AdderImpl();
        int r5 = iadder.interfaceAdd(a, b);

        return r1 + r2 + r3 + r4 + r5;
    }

    public static void main(String[] args) throws Exception {
        // Pre-load all inner classes before testMethod is JIT-compiled.
        // With -Xcomp + compileonly, testMethod is compiled on first call.
        // Inner classes are loaded lazily, so without this they would be
        // unloaded at compile time, causing uncommon_trap for 'new' bytecodes.
        new FinalAdder();
        new VirtualSub();
        new AdderImpl();

        int result = testMethod();
        // va=3, vb=5: r1=8, r2=8, r3=8, r4=15(3*5), r5=8 => 47
        Asserts.assertEquals(result, 47, "Unexpected result from testMethod");

        // --- IR verification on unoptimized LLVM IR of testMethod ---
        String currentDir = System.getProperty("user.dir");
        FileCheck fc = new FileCheck(currentDir,
                TestInvokeTypeFilter.class.getDeclaredMethod("testMethod"),
                false);

        // Verify method definition exists
        fc.checkPattern("define hotspotcc .* @.*TestInvokeTypeFilter_testMethod.*");

        // Use @"..._methodName to match function references only (not variable
        // names like %inlined_..._methodName_return_val in deopt bundles).

        // Case 1: invokestatic staticAdd — should be inlined, function call ABSENT
        fc.checkNotPattern("@\".*_staticAdd\\(");

        // Case 2: invokespecial specialAdd — should be inlined, function call ABSENT
        fc.checkNotPattern("@\".*_specialAdd\\(");

        // Case 3: invokevirtual finalAdd (statically bound) — should be inlined, function call ABSENT
        fc.checkNotPattern("@\".*_finalAdd\\(");

        // Case 4: invokevirtual virtualAdd (virtual dispatch) — should NOT be inlined, function call PRESENT
        fc.checkPattern("invoke .* @\".*_virtualAdd\\(");

        // Case 5: invokeinterface interfaceAdd (default method) — should NOT be inlined, function call PRESENT
        fc.checkPattern("invoke .* @\".*_interfaceAdd\\(");
    }
}
