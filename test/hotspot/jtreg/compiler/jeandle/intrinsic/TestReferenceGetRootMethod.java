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

/*
 * @test
 * @summary Verify Reference.get() works correctly when compiled as the root
 *          method (bytecode path, not intrinsic path). Tests that the
 *          bytecode fallback path in do_get_xxx() has the G1 SATB barrier
 *          and CPUOrder fence for the referent field.
 * @requires vm.gc.G1
 * @library /test/lib /
 * @build jdk.test.whitebox.WhiteBox compiler.jeandle.fileCheck.FileCheck
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *      -XX:+UseJeandleCompiler -XX:+UseG1GC -XX:-TieredCompilation -Xcomp
 *      -XX:+JeandleDumpIR
 *      -XX:CompileCommand=compileonly,java.lang.ref.Reference::get
 *      compiler.jeandle.intrinsic.TestReferenceGetRootMethod
 */

package compiler.jeandle.intrinsic;

import jdk.test.lib.Asserts;
import java.lang.ref.WeakReference;
import jdk.test.whitebox.WhiteBox;
import compiler.jeandle.fileCheck.FileCheck;

public class TestReferenceGetRootMethod {
    private static final WhiteBox wb = WhiteBox.getWhiteBox();

    public static void main(String[] args) throws Exception {
        // Runtime correctness: verify get() returns the referent
        Object obj = new Object();
        WeakReference<Object> ref = new WeakReference<>(obj);

        // Call get() which will be compiled as the root method (bytecode path)
        Object result = ref.get();
        Asserts.assertEquals(result, obj,
                "Reference.get() as root method should return the referent");

        // Verify the referent survives a young GC
        wb.youngGC();
        result = ref.get();
        Asserts.assertEquals(result, obj,
                "Reference.get() should still return the referent after young GC");

        // IR verification: check the compiled Reference.get method
        // When get() is compiled as the root method, it goes through the
        // bytecode path (do_get_xxx) rather than the jeandle.reference_get JavaOp.
        // Verify the CPUOrder fence is present in the bytecode-compiled path.
        String currentDir = System.getProperty("user.dir");
        FileCheck fc = new FileCheck(currentDir,
                java.lang.ref.Reference.class.getDeclaredMethod("get"),
                false);

        // CPUOrder fence must be present regardless of which compilation path
        fc.checkPattern("fence.*seq_cst");

        // G1 pre-barrier should be present under G1GC
        fc.check("jeandle.g1_pre_barrier_loaded");
    }
}
