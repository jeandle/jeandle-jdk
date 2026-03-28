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
 * @test TestConcurrentGCBreakpoints
 * @summary Test of WhiteBox concurrent GC control.
 * @requires vm.gc.G1
 * @library /test/lib
 * @build jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm
 *   -Xbootclasspath/a:.
 *   -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *   -XX:+UseG1GC -XX:CompileCommand=compileonly,TestConcurrentGCBreakpoints::writeRefField
 *   -Xcomp -XX:-TieredCompilation
 *   TestConcurrentGCBreakpoints
 */

import jdk.test.lib.Asserts;
import jdk.test.whitebox.WhiteBox;
import jdk.test.whitebox.gc.GC;
import java.lang.ref.WeakReference;

public class TestConcurrentGCBreakpoints {

    private static final WhiteBox WB = WhiteBox.getWhiteBox();

    static class Node {
        Object ref;
        Node(Object r) { ref = r; }
    }

    static volatile Node root;

    private static WeakReference<Object> ref = null;
    private static WeakReference<Object> ref2 = null;

    public static void writeRefField(Node node, Object newValue) {
        node.ref = newValue; // need G1GC barrier
    }

    private static void test(Node root) {
        try {
            WB.concurrentGCAcquireControl();
            WB.concurrentGCRunTo(WB.AFTER_MARKING_STARTED);
            writeRefField(root, new byte[100]);
            ref2 = new WeakReference<Object>(root.ref);
            WB.concurrentGCRunTo(WB.BEFORE_MARKING_COMPLETED);
            WB.concurrentGCRunTo(WB.G1_AFTER_REBUILD_STARTED);
            WB.concurrentGCRunTo(WB.G1_BEFORE_REBUILD_COMPLETED);
            WB.concurrentGCRunTo(WB.G1_AFTER_CLEANUP_STARTED);
            WB.concurrentGCRunTo(WB.G1_BEFORE_CLEANUP_COMPLETED);
            WB.concurrentGCRunToIdle();
            Asserts.assertTrue(!ref.refersTo(null), "Object lost! G1GC pre barrier missing");
            Asserts.assertTrue(!ref2.refersTo(null), "Object lost! G1GC post barrier missing");

            // Run a second cycle.
            WB.concurrentGCRunTo(WB.AFTER_MARKING_STARTED);
            WB.concurrentGCRunTo(WB.BEFORE_MARKING_COMPLETED);
            WB.concurrentGCRunTo(WB.G1_AFTER_REBUILD_STARTED);
            WB.concurrentGCRunTo(WB.G1_BEFORE_REBUILD_COMPLETED);
            WB.concurrentGCRunTo(WB.G1_AFTER_CLEANUP_STARTED);
            WB.concurrentGCRunTo(WB.G1_BEFORE_CLEANUP_COMPLETED);
            Asserts.assertTrue(ref.refersTo(null), "Object need clean now!");
            Asserts.assertTrue(!ref2.refersTo(null), "Object lost! G1GC post barrier missing");
        } finally {
            WB.concurrentGCReleaseControl();
        }
    }

    public static void main(String[] args) throws Exception {
        root = new Node(new byte[100]);
        ref = new WeakReference<Object>(root.ref);

        while (!WB.isObjectInOldGen(root)) {
            WB.youngGC();
        }

        Asserts.assertTrue(WB.isObjectInOldGen(root));
        test(root);
    }
}
