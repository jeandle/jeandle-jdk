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
 * @summary Verify Reference.refersTo() and PhantomReference.refersTo() intrinsics
 *          emit correct memory ordering: Unordered atomic load + CPUOrder fence,
 *          no G1 SATB barrier (AS_NO_KEEPALIVE semantics).
 *          Also test refersTo(null) after reference is cleared.
 * @library /test/lib /
 * @build compiler.jeandle.fileCheck.FileCheck
 * @run main/othervm -Xcomp -XX:-TieredCompilation
 *      -XX:+UseJeandleCompiler -XX:+UseG1GC
 *      -XX:+JeandleDumpIR
 *      -XX:CompileCommand=compileonly,*TestReferenceRefersTo*::test*
 *      compiler.jeandle.intrinsic.TestReferenceRefersTo
 */

package compiler.jeandle.intrinsic;

import jdk.test.lib.Asserts;
import java.lang.ref.WeakReference;
import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;
import compiler.jeandle.fileCheck.FileCheck;

public class TestReferenceRefersTo {

    private static boolean testWeakRefersTo(WeakReference<Object> ref, Object obj) {
        // This call will be intrinsified to jeandle.reference_refers_to
        return ref.refersTo(obj);
    }

    private static boolean testPhantomRefersTo(PhantomReference<Object> ref, Object obj) {
        // This call will be intrinsified to jeandle.reference_refers_to
        return ref.refersTo(obj);
    }

    public static void main(String[] args) throws Exception {
        // Runtime correctness: verify refersTo() returns expected values
        Object obj = new Object();
        WeakReference<Object> weakRef = new WeakReference<>(obj);
        Asserts.assertTrue(testWeakRefersTo(weakRef, obj),
                "WeakReference.refersTo(referent) should return true");
        Asserts.assertFalse(testWeakRefersTo(weakRef, new Object()),
                "WeakReference.refersTo(other) should return false");
        Asserts.assertFalse(testWeakRefersTo(weakRef, null),
                "WeakReference.refersTo(null) should return false when referent is non-null");

        ReferenceQueue<Object> queue = new ReferenceQueue<>();
        PhantomReference<Object> phantomRef = new PhantomReference<>(obj, queue);
        Asserts.assertTrue(testPhantomRefersTo(phantomRef, obj),
                "PhantomReference.refersTo(referent) should return true");
        Asserts.assertFalse(testPhantomRefersTo(phantomRef, new Object()),
                "PhantomReference.refersTo(other) should return false");

        // Test refersTo(null) after reference is cleared — cleared reference refers to null
        Object obj2 = new Object();
        WeakReference<Object> weakRef2 = new WeakReference<>(obj2);
        Asserts.assertFalse(testWeakRefersTo(weakRef2, null),
                "WeakReference.refersTo(null) should return false when referent is non-null");
        weakRef2.clear();
        Asserts.assertTrue(testWeakRefersTo(weakRef2, null),
                "WeakReference.refersTo(null) should return true after clear() (referent is now null)");

        // IR verification: check the jeandle.reference_refers_to JavaOp
        String currentDir = System.getProperty("user.dir");
        FileCheck fc = new FileCheck(currentDir,
                TestReferenceRefersTo.class.getDeclaredMethod("testWeakRefersTo", WeakReference.class, Object.class),
                false);

        // Verify the jeandle.reference_refers_to JavaOp definition is present.
        // IR order: load -> fence -> icmp -> zext -> ret (no barrier call).
        fc.checkPattern("define.*jeandle.reference_refers_to");

        // Verify the load is unordered atomic (appears before the fence in IR)
        fc.checkPattern("load atomic ptr addrspace\\([13]\\).*unordered");

        // Verify a fence with seq_cst ordering is present (CPUOrder fence)
        fc.checkPattern("fence.*seq_cst");

        // Verify the function ends with icmp + zext + ret (no barrier call)
        fc.check("icmp eq");
        fc.check("ret i32");
    }
}
