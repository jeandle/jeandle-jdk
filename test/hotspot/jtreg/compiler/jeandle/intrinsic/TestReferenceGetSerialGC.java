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
 * @summary Verify Reference.get() intrinsic works correctly under SerialGC:
 *          no SATB barrier, CPUOrder fence still present, unordered load
 * @requires vm.gc.Serial
 * @library /test/lib /
 * @build compiler.jeandle.fileCheck.FileCheck
 * @run main/othervm -Xcomp -XX:-TieredCompilation
 *      -XX:+UseJeandleCompiler -XX:+UseSerialGC
 *      -XX:+JeandleDumpIR
 *      -XX:CompileCommand=compileonly,*TestReferenceGetSerialGC*::test*
 *      compiler.jeandle.intrinsic.TestReferenceGetSerialGC
 */

package compiler.jeandle.intrinsic;

import jdk.test.lib.Asserts;
import java.lang.ref.WeakReference;
import compiler.jeandle.fileCheck.FileCheck;

public class TestReferenceGetSerialGC {

    private static Object testReferenceGet(WeakReference<Object> ref) {
        // This call will be intrinsified to jeandle.reference_get
        return ref.get();
    }

    public static void main(String[] args) throws Exception {
        // Runtime correctness: verify get() returns expected values under SerialGC
        Object obj = new Object();
        WeakReference<Object> ref = new WeakReference<>(obj);
        Asserts.assertEquals(testReferenceGet(ref), obj,
                "get() should return the referent under SerialGC");

        // IR verification: check the jeandle.reference_get JavaOp under SerialGC
        String currentDir = System.getProperty("user.dir");
        FileCheck fc = new FileCheck(currentDir,
                TestReferenceGetSerialGC.class.getDeclaredMethod("testReferenceGet", WeakReference.class),
                false);

        // Verify the jeandle.reference_get function definition is present.
        // Under SerialGC, IR order: load -> fence -> ret (no barrier call)
        fc.checkPattern("define.*jeandle.reference_get");

        // Verify the load is unordered atomic
        fc.checkPattern("load atomic ptr addrspace\\(1\\).*unordered");

        // CPUOrder fence must still be present (GC-independent)
        fc.checkPattern("fence.*seq_cst");
    }
}
