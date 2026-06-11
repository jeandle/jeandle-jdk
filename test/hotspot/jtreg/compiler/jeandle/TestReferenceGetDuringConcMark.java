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
 * @summary Verify Reference.get() works correctly under G1GC concurrent marking
 *          when compiled by Jeandle. Tests that the SATB pre-barrier correctly
 *          records the referent so it survives concurrent marking.
 * @requires vm.gc.G1
 * @library /test/lib
 * @build jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -Xbootclasspath/a:.
 *      -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *      -XX:+UseJeandleCompiler -XX:+UseG1GC -XX:-TieredCompilation -Xcomp
 *      -XX:CompileCommand=compileonly,*TestReferenceGetDuringConcMark*::test*
 *      TestReferenceGetDuringConcMark
 */

import java.lang.ref.WeakReference;
import jdk.test.lib.Asserts;
import jdk.test.whitebox.WhiteBox;

public class TestReferenceGetDuringConcMark {
    private static final WhiteBox wb = WhiteBox.getWhiteBox();

    private static volatile Object testObject = null;
    private static WeakReference<Object> testWeak = null;

    private static void setup() {
        testObject = new Object();
        testWeak = new WeakReference<>(testObject);
    }

    private static void gcUntilOld(Object o) throws Exception {
        if (!wb.isObjectInOldGen(o)) {
            wb.fullGC();
            if (!wb.isObjectInOldGen(o)) {
                throw new RuntimeException("object not promoted by full gc");
            }
        }
    }

    // This method will be compiled by Jeandle
    private static Object testGetDuringConcMark(WeakReference<Object> ref) {
        return ref.get();
    }

    private static void testConcurrentCollection() throws Exception {
        setup();
        // Promote objects to old gen
        gcUntilOld(testWeak);

        wb.concurrentGCAcquireControl();
        try {
            // Verify initial state
            Object result = testGetDuringConcMark(testWeak);
            if (result == null) {
                throw new RuntimeException("testWeak.get() unexpectedly returned null before collection");
            }

            // Discard strong reference
            testObject = null;

            // Run concurrent marking to BEFORE_MARKING_COMPLETED
            wb.concurrentGCRunTo(wb.BEFORE_MARKING_COMPLETED);

            // During concurrent marking, calling get() should keep the referent alive
            // because the SATB pre-barrier records it
            result = testGetDuringConcMark(testWeak);
            if (result == null) {
                throw new RuntimeException("testWeak.get() unexpectedly returned null during concurrent marking");
            }

            // Complete the concurrent cycle
            wb.concurrentGCRunToIdle();

            // After concurrent marking, the referent should still be reachable
            // because the SATB barrier recorded it
            result = testGetDuringConcMark(testWeak);
            if (result == null) {
                throw new RuntimeException("testWeak.get() unexpectedly returned null after concurrent marking");
            }
        } finally {
            wb.concurrentGCReleaseControl();
        }
    }

    public static void main(String[] args) throws Exception {
        if (wb.supportsConcurrentGCBreakpoints()) {
            testConcurrentCollection();
        }
    }
}
